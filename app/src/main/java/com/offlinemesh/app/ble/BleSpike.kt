package com.offlinemesh.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.location.LocationManagerCompat
import com.offlinemesh.app.echo.EchoController
import com.offlinemesh.app.echo.EchoUiState
import com.offlinemesh.core.common.Layer
import com.offlinemesh.core.common.Level
import com.offlinemesh.core.common.MeshLog
import com.offlinemesh.core.mesh.echo.EchoProtocol
import com.offlinemesh.core.mesh.echo.EchoReceiver
import com.offlinemesh.core.mesh.echo.EchoReport
import com.offlinemesh.core.model.PeerId
import kotlin.random.Random

/**
 * Thử nghiệm tuần 1: mỗi máy vừa quảng bá + chạy GATT server (Peripheral), vừa quét (Central).
 * Người dùng chọn một máy trong danh sách để nối; bên nối gửi N khung echo, bên kia đếm và báo cáo.
 * Chưa phải BleTransport hoàn chỉnh (tuần 2).
 */
@SuppressLint("MissingPermission")
class BleSpike(private val ctx: Context, private val log: MeshLog) : EchoController.Commands {
    private val ui = EchoController
    private val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = mgr.adapter
    private val main = Handler(Looper.getMainLooper())
    private val selfBytes = Random.nextBytes(4)
    private val selfHex = selfBytes.toHex()

    // Vai Peripheral: máy khác nối vào ta
    private var server: BluetoothGattServer? = null
    private var txChar: BluetoothGattCharacteristic? = null
    @Volatile private var subscribed = false
    private val receiver = EchoReceiver()

    // Vai Central: ta nối tới máy khác
    @Volatile private var client: BluetoothGatt? = null
    @Volatile private var rxChar: BluetoothGattCharacteristic? = null
    @Volatile private var mtu = 23
    @Volatile private var pendingPeer: String? = null
    @Volatile private var test: EchoRun? = null

    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private val found = LinkedHashMap<String, BluetoothDevice>()

    // ---------- log ----------
    private fun i(event: String, detail: String? = null, peer: String? = null) =
        log.log(Level.INFO, Layer.TRANSPORT, event, peer = peer?.let { PeerId(it) }, detail = detail)

    private fun w(event: String, detail: String? = null, peer: String? = null) =
        log.log(Level.WARN, Layer.TRANSPORT, event, peer = peer?.let { PeerId(it) }, detail = detail)

    private fun fail(msg: String) {
        w("error", msg)
        ui.update { it.copy(error = msg) }
    }

    // ---------- vòng đời ----------
    /**
     * Gọi ở MỖI lần người dùng bấm Bắt đầu: đọc lại trạng thái Bluetooth/Vị trí ngay lúc đó
     * (không theo dõi định kỳ để tiết kiệm pin). Trả false và hiện lỗi nếu chưa chạy được.
     */
    fun start(): Boolean {
        val ad = adapter
        val btState = if (ad == null) "không có" else if (ad.isEnabled) "bật" else "tắt"
        i("start_requested", "bluetooth=$btState sdk=${Build.VERSION.SDK_INT}")
        ui.update { it.copy(error = null, result = "", progress = "") }
        if (ad == null) { fail("Máy này không có Bluetooth"); return false }
        if (!ad.isEnabled) { fail("Bluetooth đang tắt: hãy bật Bluetooth rồi bấm Bắt đầu lại"); return false }
        if (Build.VERSION.SDK_INT < 31) {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (!LocationManagerCompat.isLocationEnabled(lm)) {
                fail("Android 11 trở xuống cần bật Vị trí (GPS) để quét BLE")
                return false
            }
        }
        val s = mgr.openGattServer(ctx, serverCb)
        if (s == null) { fail("openGattServer trả về null"); return false }
        server = s

        val svc = BluetoothGattService(BleConstants.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val rx = BluetoothGattCharacteristic(
            BleConstants.RX_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val tx = BluetoothGattCharacteristic(
            BleConstants.TX_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        tx.addDescriptor(
            BluetoothGattDescriptor(
                BleConstants.CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )
        svc.addCharacteristic(rx)
        svc.addCharacteristic(tx)
        txChar = tx
        s.addService(svc)
        i("start", "self=$selfHex sdk=${Build.VERSION.SDK_INT} model=${Build.MODEL}")
        ui.update { it.copy(running = true, selfId = selfHex, error = null) }
        return true
    }

    fun stop() {
        runCatching { advertiser?.stopAdvertising(advCb) }
        runCatching { scanner?.stopScan(scanCb) }
        runCatching { client?.close() }
        runCatching { server?.close() }
        client = null; server = null; rxChar = null; txChar = null; test = null
        found.clear()
        i("stop")
        ui.update { EchoUiState(selfId = it.selfId, logTail = it.logTail) }
    }

    // ---------- quảng bá + quét ----------
    private fun startAdvertising() {
        val adv = adapter?.bluetoothLeAdvertiser
        if (adv == null) { fail("Máy này không hỗ trợ quảng bá BLE (Peripheral)"); return }
        runCatching { advertiser?.stopAdvertising(advCb) }
        advertiser = adv
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
            .addManufacturerData(BleConstants.COMPANY_ID, selfBytes)
            .build()
        adv.startAdvertising(settings, data, advCb)
    }

    private fun startScan() {
        val sc = adapter?.bluetoothLeScanner
        if (sc == null) { fail("Không mở được bộ quét BLE"); return }
        runCatching { scanner?.stopScan(scanCb) }
        scanner = sc
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        sc.startScan(filters, settings, scanCb)
        i("scan_start")
    }

    private val advCb = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) { i("adv_ok") }
        override fun onStartFailure(errorCode: Int) {
            // 1=DATA_TOO_LARGE 2=TOO_MANY_ADVERTISERS 3=ALREADY_STARTED 4=INTERNAL_ERROR 5=FEATURE_UNSUPPORTED
            if (errorCode != ADVERTISE_FAILED_ALREADY_STARTED) fail("Quảng bá lỗi code=$errorCode")
        }
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val mf = result.scanRecord?.getManufacturerSpecificData(BleConstants.COMPANY_ID) ?: return
            if (mf.size != 4) return
            val id = mf.toHex()
            if (id == selfHex) return
            val isNew = synchronized(found) { found.put(id, result.device) == null }
            if (isNew) {
                i("peer_found", "rssi=${result.rssi}", id)
                ui.update { it.copy(peers = synchronized(found) { found.keys.toList() }) }
            }
        }

        override fun onScanFailed(errorCode: Int) { fail("Quét lỗi code=$errorCode") }
    }

    // ---------- vai Peripheral ----------
    private val serverCb = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            i("server_ready", "status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) { startAdvertising(); startScan() }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val tag = device.address.takeLast(5)
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                i("server_peer_connected", "addr=$tag")
                ui.update { it.copy(incomingFrom = tag) }
            } else {
                i("server_peer_disconnected", "addr=$tag status=$status")
                subscribed = false
                ui.update { it.copy(incomingFrom = null) }
                startAdvertising()
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) { i("server_mtu", "mtu=$mtu") }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (descriptor.uuid == BleConstants.CCCD_UUID) {
                subscribed = value?.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == true
                i("server_subscribed", "on=$subscribed")
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            val v = value ?: return
            val reply = receiver.onFrame(v)
            if (receiver.received % 50 == 0 && v.isNotEmpty() && v[0] == EchoProtocol.TYPE_DATA) {
                ui.update { it.copy(progress = "Đang nhận: ${receiver.received} khung, ${receiver.bytes}B") }
            }
            if (reply != null) {
                i("echo_end_received", "received=${receiver.received} bad=${receiver.badCrc} ooo=${receiver.outOfOrder}")
                ui.update { it.copy(progress = "", result = "Nhận xong: ${receiver.received} khung đúng, sai CRC ${receiver.badCrc}, sai thứ tự ${receiver.outOfOrder}") }
                notifyPeer(device, reply)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun notifyPeer(dev: BluetoothDevice, data: ByteArray) {
        val tx = txChar ?: return
        val s = server ?: return
        if (!subscribed) { w("notify_skipped", "peer chưa đăng ký notify"); return }
        if (Build.VERSION.SDK_INT >= 33) {
            s.notifyCharacteristicChanged(dev, tx, false, data)
        } else {
            tx.value = data
            s.notifyCharacteristicChanged(dev, tx, false)
        }
    }

    // ---------- vai Central ----------
    override fun connect(peer: String) {
        val dev = synchronized(found) { found[peer] }
        if (dev == null) { fail("Chưa thấy máy $peer"); return }
        if (client != null) return
        runCatching { scanner?.stopScan(scanCb) }
        pendingPeer = peer
        i("connect", peer = peer)
        client = dev.connectGatt(ctx, false, clientCb, BluetoothDevice.TRANSPORT_LE)
    }

    private val clientCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                i("link_connected", peer = pendingPeer)
                gatt.requestMtu(BleConstants.MTU_REQUEST)
            } else {
                // status 133 là lỗi GATT chung rất hay gặp; log lại rồi dọn sạch để thử nối lại.
                w("link_down", "status=$status state=$newState", pendingPeer)
                test?.abort("link đứt")
                gatt.close()
                if (client === gatt) client = null
                rxChar = null
                ui.update { it.copy(linkedPeer = null) }
                startScan()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            this@BleSpike.mtu = mtu
            i("mtu", "mtu=$mtu status=$status")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val svc = gatt.getService(BleConstants.SERVICE_UUID)
            val tx = svc?.getCharacteristic(BleConstants.TX_UUID)
            val rx = svc?.getCharacteristic(BleConstants.RX_UUID)
            val cccd = tx?.getDescriptor(BleConstants.CCCD_UUID)
            if (status != BluetoothGatt.GATT_SUCCESS || tx == null || rx == null || cccd == null) {
                fail("Không thấy dịch vụ mesh trên máy kia (status=$status)")
                gatt.disconnect()
                return
            }
            rxChar = rx
            gatt.setCharacteristicNotification(tx, true)
            writeDescriptor(gatt, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            i("link_ready", "cccd status=$status", pendingPeer)
            ui.update { it.copy(linkedPeer = pendingPeer, error = null) }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            test?.onWritten(status)
        }

        // Android 13+
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleNotify(value)
        }

        // Android 12 trở xuống
        @Suppress("DEPRECATION")
        @Deprecated("Dùng cho API < 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleNotify(characteristic.value ?: return)
        }
    }

    private fun handleNotify(v: ByteArray) {
        EchoProtocol.parseReport(v)?.let { test?.onReport(it) }
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, v: ByteArray) {
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, v)
        } else {
            d.value = v
            g.writeDescriptor(d)
        }
    }

    @Suppress("DEPRECATION")
    private fun writeNoResponse(g: BluetoothGatt, ch: BluetoothGattCharacteristic, data: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            ch.value = data
            g.writeCharacteristic(ch)
        }

    // ---------- chạy thử echo ----------
    override fun runEcho(frames: Int) {
        if (client == null || rxChar == null) { fail("Chưa nối tới máy nào"); return }
        if (test != null) return
        val size = (mtu - 3).coerceIn(20, 512)
        ui.update { it.copy(error = null, result = "", progress = "Bắt đầu…") }
        EchoRun(frames, size).also { test = it }.begin()
    }

    /** Gửi lần lượt từng khung; khung kế tiếp chỉ được ghi khi callback ghi trước đã xong (tránh tràn hàng đợi). */
    private inner class EchoRun(val frames: Int, val frameSize: Int) {
        private var done = 0
        private var t0 = 0L
        private var t1 = 0L

        fun begin() {
            t0 = SystemClock.elapsedRealtime()
            i("echo_begin", "frames=$frames size=$frameSize")
            pump()
        }

        private fun pump() {
            val g = client
            val ch = rxChar
            if (g == null || ch == null) { abort("mất kết nối giữa chừng"); return }
            val data = when {
                done < frames -> EchoProtocol.dataFrame(done, frameSize)
                done == frames -> EchoProtocol.endFrame(frames)
                else -> return
            }
            if (!writeNoResponse(g, ch, data)) abort("ghi thất bại ở khung $done")
        }

        fun onWritten(status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { abort("ghi lỗi status=$status ở khung $done"); return }
            done++
            if (done == frames + 1) {
                t1 = SystemClock.elapsedRealtime()
                main.postDelayed({ if (test === this) abort("không nhận được báo cáo sau 5s") }, 5000)
            } else if (done % 50 == 0) {
                ui.update { it.copy(progress = "Đã gửi $done/$frames khung") }
            }
            pump()
        }

        fun onReport(r: EchoReport) {
            val ms = maxOf(1L, t1 - t0)
            val bytes = frames.toLong() * frameSize
            val lost = frames - r.received
            val text = "Gửi $frames×${frameSize}B trong ${ms}ms = ${"%.1f".format(bytes.toDouble() / ms)} kB/s | " +
                "nhận ${r.received}, mất $lost, sai CRC ${r.badCrc}, sai thứ tự ${r.outOfOrder}"
            i("echo_result", text)
            test = null
            ui.update { it.copy(progress = "", result = text) }
        }

        fun abort(why: String) {
            w("echo_abort", why)
            test = null
            ui.update { it.copy(progress = "", error = "Echo dừng: $why") }
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
