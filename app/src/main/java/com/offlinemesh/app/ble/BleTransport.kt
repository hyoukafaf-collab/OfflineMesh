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
import android.os.Build
import android.os.Handler
import android.os.ParcelUuid
import android.os.SystemClock
import com.offlinemesh.core.common.Layer
import com.offlinemesh.core.common.Level
import com.offlinemesh.core.common.MeshLog
import com.offlinemesh.core.mesh.transport.ConnectConfig
import com.offlinemesh.core.mesh.transport.ConnectPolicy
import com.offlinemesh.core.mesh.transport.LinkFrame
import com.offlinemesh.core.mesh.transport.LinkFrames
import com.offlinemesh.core.mesh.transport.SendQueue
import com.offlinemesh.core.mesh.transport.Transport
import com.offlinemesh.core.mesh.transport.TransportListener
import com.offlinemesh.core.model.PeerId
import com.offlinemesh.core.model.PeerIds
import com.offlinemesh.core.model.hexToBytes
import com.offlinemesh.core.model.toHex

/**
 * Transport thật trên BLE. Mỗi máy vừa là Peripheral (quảng bá + GATT server) vừa là Central (quét + nối).
 *
 * - Chỉ MỘT luồng (handler) được chạm vào trạng thái ở đây; mọi callback của hệ thống được post vào handler.
 * - ConnectPolicy quyết định nối ai/khi nào (máy ID nhỏ nối trước, lùi dần khi lỗi, mỗi lúc một lần nối).
 * - Bên Central tự giới thiệu ID bằng khung IDENT; onPeerFound chỉ gọi khi liên kết đã SẴN SÀNG.
 * - Mỗi liên kết có SendQueue: mỗi lúc chỉ một thao tác ghi/notify đang bay, khung kế tiếp chờ callback.
 * - Trùng liên kết (hai máy cùng nối nhau): giữ liên kết có Central là máy ID nhỏ hơn, đóng cái còn lại.
 * - Liên kết rảnh quá IDLE_CLOSE_MS thì Central đóng (giải phóng chỗ, tiết kiệm pin).
 */
@SuppressLint("MissingPermission")
class BleTransport(
    private val ctx: Context,
    override val selfId: PeerId,
    private val handler: Handler,
    private val log: MeshLog,
    cfg: ConnectConfig = ConnectConfig(reconnectCooldownMs = 15_000),
) : Transport {

    companion object {
        const val CONNECT_TIMEOUT_MS = 12_000L
        const val IDLE_CLOSE_MS = 30_000L
        const val HOUSEKEEPING_MS = 10_000L
        const val MAX_RETRIES = 200
        const val MTU_REQUEST = 247
    }

    init { require(PeerIds.isValid(selfId)) { "selfId phải là 12 ký tự hex" } }

    override val maxFrame: Int = 244

    /** (đang thấy quảng bá, đang có liên kết sống). Gọi trên luồng handler. */
    var onPeersChanged: (seen: List<PeerId>, linked: List<PeerId>) -> Unit = { _, _ -> }

    /** Lỗi nặng (Bluetooth tắt, máy không hỗ trợ quảng bá...). */
    var onFatal: (String) -> Unit = {}

    private enum class Role { CENTRAL, PERIPHERAL }

    private inner class Link(val peer: PeerId, val role: Role, val device: BluetoothDevice) {
        var gatt: BluetoothGatt? = null                 // vai Central
        var rx: BluetoothGattCharacteristic? = null     // vai Central: đặc tính RX của peer
        var mtu = 23
        var ready = false
        var closed = false
        var subscribed = false                          // vai Peripheral: Central đã bật notify
        var retries = 0
        var lastActivity = now()
        val queue = SendQueue()
        val idle = Runnable { onIdle(this) }
    }

    private class Incoming(val device: BluetoothDevice) {
        var mtu = 23
        var subscribed = false
        var peer: PeerId? = null
    }

    private var listener: TransportListener? = null
    private var started = false
    private val policy = ConnectPolicy(selfId, cfg)
    private val devices = HashMap<PeerId, BluetoothDevice>()
    private val lastSighting = HashMap<PeerId, Long>()
    private val links = HashMap<PeerId, Link>()
    private val incoming = HashMap<String, Incoming>()
    private var connecting: Link? = null
    private var connectTimeout: Runnable? = null
    private var lastEmitted: Pair<List<String>, List<String>>? = null

    private var adapter: BluetoothAdapter? = null
    private var manager: BluetoothManager? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var server: BluetoothGattServer? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private val housekeeping = object : Runnable {
        override fun run() {
            if (!started) return
            emitPeers()
            maybeConnect()
            handler.postDelayed(this, HOUSEKEEPING_MS)
        }
    }

    private fun now(): Long = SystemClock.elapsedRealtime()

    private fun i(event: String, detail: String? = null, peer: PeerId? = null) =
        log.log(Level.INFO, Layer.TRANSPORT, event, peer = peer, detail = detail)

    private fun w(event: String, detail: String? = null, peer: PeerId? = null) =
        log.log(Level.WARN, Layer.TRANSPORT, event, peer = peer, detail = detail)

    private fun fatal(msg: String) { w("fatal", msg); onFatal(msg) }

    // ================= vòng đời =================
    override fun start(listener: TransportListener) {
        this.listener = listener
        val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        manager = mgr
        adapter = mgr.adapter
        val ad = adapter
        if (ad == null || !ad.isEnabled) { fatal("Bluetooth đang tắt hoặc không có"); return }
        val s = mgr.openGattServer(ctx, serverCb)
        if (s == null) { fatal("openGattServer trả về null"); return }
        server = s
        val svc = BluetoothGattService(BleConstants.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val rx = BluetoothGattCharacteristic(
            BleConstants.RX_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val tx = BluetoothGattCharacteristic(
            BleConstants.TX_UUID, BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ,
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
        started = true
        s.addService(svc)
        i("transport_start", "self=${selfId.hex} sdk=${Build.VERSION.SDK_INT} model=${Build.MODEL}")
        handler.postDelayed(housekeeping, HOUSEKEEPING_MS)
    }

    override fun stop() {
        if (!started && server == null) return
        started = false
        handler.removeCallbacks(housekeeping)
        connectTimeout?.let { handler.removeCallbacks(it) }
        connectTimeout = null
        runCatching { advertiser?.stopAdvertising(advCb) }
        runCatching { scanner?.stopScan(scanCb) }
        for (l in links.values.toList()) {
            l.closed = true
            handler.removeCallbacks(l.idle)
            runCatching { l.gatt?.close() }
        }
        connecting?.let { c -> c.closed = true; runCatching { c.gatt?.close() } }
        connecting = null
        links.clear(); incoming.clear(); devices.clear(); lastSighting.clear()
        runCatching { server?.close() }
        server = null; txChar = null; advertiser = null; scanner = null
        listener = null
        i("transport_stop")
    }

    // ================= gửi =================
    override fun frameSize(peer: PeerId): Int {
        val mtu = links[peer]?.mtu ?: 23
        return (mtu - 3 - LinkFrames.OVERHEAD).coerceIn(10, maxFrame)
    }

    override fun send(to: PeerId, data: ByteArray): Boolean {
        val l = links[to]
        if (!started || l == null || !l.ready || l.closed || data.size > frameSize(to)) return false
        touch(l)
        if (!l.queue.offer(LinkFrames.data(data))) { w("queue_full", peer = to); return false }
        pump(l)
        return true
    }

    private fun pump(l: Link) {
        if (l.closed) return
        val f = l.queue.poll() ?: return
        val ok = when (l.role) {
            Role.CENTRAL -> {
                val g = l.gatt
                val ch = l.rx
                g != null && ch != null && writeNoResponse(g, ch, f)
            }
            Role.PERIPHERAL -> l.subscribed && notifyCentral(l.device, f)
        }
        if (ok) { l.retries = 0; return }
        l.queue.retryFront(f)                      // stack đang bận (hoặc Central chưa bật notify): thử lại sau
        if (++l.retries > MAX_RETRIES) { w("send_stuck", peer = l.peer); closeLink(l); return }
        handler.postDelayed({ pump(l) }, 15)
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

    @Suppress("DEPRECATION")
    private fun notifyCentral(dev: BluetoothDevice, data: ByteArray): Boolean {
        val tx = txChar ?: return false
        val s = server ?: return false
        return if (Build.VERSION.SDK_INT >= 33) {
            s.notifyCharacteristicChanged(dev, tx, false, data) == BluetoothStatusCodes.SUCCESS
        } else {
            tx.value = data
            s.notifyCharacteristicChanged(dev, tx, false)
        }
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

    // ================= nhận =================
    private fun deliver(peer: PeerId, raw: ByteArray) {
        val l = links[peer] ?: return
        when (val f = LinkFrames.parse(raw)) {
            is LinkFrame.Data -> { touch(l); listener?.onData(peer, f.payload) }
            is LinkFrame.Ident -> { /* IDENT đã xử lý lúc tạo liên kết; bỏ qua bản lặp */ }
            null -> w("bad_frame", peer = peer)
        }
    }

    // ================= quảng bá + quét =================
    private fun startAdvertising(fallback: Boolean = false) {
        val adv = adapter?.bluetoothLeAdvertiser
        if (adv == null) { fatal("Máy này không hỗ trợ quảng bá BLE (Peripheral)"); return }
        runCatching { advertiser?.stopAdvertising(advCb) }
        advertiser = adv
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val idBytes = selfId.hex.hexToBytes()
        if (!fallback) {
            // 3 (flags) + 18 (UUID 128 bit) + 10 (6 byte ID) = 31 byte, vừa khít
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
                .addManufacturerData(BleConstants.COMPANY_ID, idBytes)
                .build()
            adv.startAdvertising(settings, data, advCb)
        } else {
            val data = AdvertiseData.Builder().setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID)).build()
            val scanResponse = AdvertiseData.Builder().setIncludeDeviceName(false)
                .addManufacturerData(BleConstants.COMPANY_ID, idBytes).build()
            adv.startAdvertising(settings, data, scanResponse, advCb)
        }
    }

    private fun startScan() {
        val sc = adapter?.bluetoothLeScanner
        if (sc == null) { fatal("Không mở được bộ quét BLE"); return }
        runCatching { scanner?.stopScan(scanCb) }
        scanner = sc
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
        sc.startScan(filters, settings, scanCb)
        i("scan_start")
    }

    private val advCb = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) { handler.post { i("adv_ok") } }
        override fun onStartFailure(errorCode: Int) {
            handler.post {
                // 1=DATA_TOO_LARGE 2=TOO_MANY_ADVERTISERS 3=ALREADY_STARTED 4=INTERNAL_ERROR 5=FEATURE_UNSUPPORTED
                when (errorCode) {
                    ADVERTISE_FAILED_ALREADY_STARTED -> {}
                    ADVERTISE_FAILED_DATA_TOO_LARGE -> { w("adv_retry_scan_response"); startAdvertising(fallback = true) }
                    else -> fatal("Quảng bá lỗi code=$errorCode")
                }
            }
        }
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val mf = result.scanRecord?.getManufacturerSpecificData(BleConstants.COMPANY_ID) ?: return
            if (mf.size != PeerIds.BYTES) return
            val peer = PeerId(mf.toHex())
            val dev = result.device
            val rssi = result.rssi
            handler.post { onSighting(peer, dev, rssi) }
        }

        override fun onScanFailed(errorCode: Int) { handler.post { fatal("Quét lỗi code=$errorCode") } }
    }

    private fun onSighting(peer: PeerId, dev: BluetoothDevice, rssi: Int) {
        if (!started || peer == selfId) return
        val t = now()
        val last = lastSighting[peer]
        if (last != null && t - last < 1_000) return            // giảm tải: tối đa 1 lần/giây cho mỗi máy
        lastSighting[peer] = t
        devices[peer] = dev
        policy.onSighting(peer, rssi, t)
        maybeConnect()
        emitPeers()
    }

    private fun emitPeers() {
        val seen = policy.recentlySeen(now()).map { it.hex }.sorted()
        val linked = links.filterValues { it.ready && !it.closed }.keys.map { it.hex }.sorted()
        if (lastEmitted == seen to linked) return
        lastEmitted = seen to linked
        onPeersChanged(seen.map { PeerId(it) }, linked.map { PeerId(it) })
    }

    // ================= vai Central: nối ra =================
    private fun maybeConnect() {
        if (!started || connecting != null) return
        val peer = policy.nextToConnect(now(), links.keys) ?: return
        val dev = devices[peer] ?: return
        val link = Link(peer, Role.CENTRAL, dev)
        connecting = link
        policy.onConnectStarted(peer)
        i("connect", peer = peer)
        link.gatt = dev.connectGatt(ctx, false, clientCallback(link), BluetoothDevice.TRANSPORT_LE)
        if (link.gatt == null) { finishCentral(link); return }
        val timeout = Runnable { if (connecting === link) { w("connect_timeout", peer = peer); finishCentral(link) } }
        connectTimeout = timeout
        handler.postDelayed(timeout, CONNECT_TIMEOUT_MS)
    }

    private fun clientCallback(link: Link) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (link.closed) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    if (!gatt.requestMtu(MTU_REQUEST)) gatt.discoverServices()
                } else {
                    // status 133 là lỗi GATT chung rất hay gặp; ghi lại rồi dọn sạch, ConnectPolicy sẽ lùi dần
                    w("link_down", "status=$status state=$newState", link.peer)
                    finishCentral(link)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            handler.post {
                if (link.closed) return@post
                link.mtu = mtu
                i("mtu", "mtu=$mtu status=$status", link.peer)
                gatt.discoverServices()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            handler.post {
                if (link.closed) return@post
                val svc = gatt.getService(BleConstants.SERVICE_UUID)
                val tx = svc?.getCharacteristic(BleConstants.TX_UUID)
                val rx = svc?.getCharacteristic(BleConstants.RX_UUID)
                val cccd = tx?.getDescriptor(BleConstants.CCCD_UUID)
                if (status != BluetoothGatt.GATT_SUCCESS || tx == null || rx == null || cccd == null) {
                    w("no_mesh_service", "status=$status", link.peer)
                    finishCentral(link)
                    return@post
                }
                link.rx = rx
                gatt.setCharacteristicNotification(tx, true)
                writeDescriptor(gatt, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            handler.post {
                if (link.closed) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) { w("cccd_failed", "status=$status", link.peer); finishCentral(link); return@post }
                centralReady(link)
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            handler.post {
                if (link.closed) return@post
                link.queue.complete()
                if (status != BluetoothGatt.GATT_SUCCESS) { w("write_failed", "status=$status", link.peer); finishCentral(link) }
                else pump(link)
            }
        }

        // Android 13+
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handler.post { if (!link.closed) deliver(link.peer, value) }
        }

        // Android 12 trở xuống
        @Suppress("DEPRECATION")
        @Deprecated("Dùng cho API < 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val v = characteristic.value ?: return
            handler.post { if (!link.closed) deliver(link.peer, v) }
        }
    }

    private fun centralReady(link: Link) {
        val peer = link.peer
        connectTimeout?.let { handler.removeCallbacks(it) }
        connectTimeout = null
        if (connecting === link) connecting = null
        link.ready = true
        policy.onLinkReady(peer, weAreCentral = true)
        val existing = links[peer]
        if (existing != null) {
            // Trùng: peer cũng đã nối vào ta. Giữ liên kết có Central là máy ID nhỏ hơn.
            if (selfId.hex < peer.hex) {
                i("duplicate_keep_ours", peer = peer)
                dropPeripheral(existing)
            } else {
                i("duplicate_keep_theirs", peer = peer)
                finishCentral(link)
                return
            }
        }
        link.queue.offer(LinkFrames.ident(selfId))     // phải là khung ĐẦU TIÊN, trước cả Hello của MeshNode
        register(link)                                 // MeshNode gửi Hello ngay trong onPeerFound, nằm sau IDENT
        pump(link)
    }

    private fun finishCentral(link: Link) {
        if (link.closed) return
        link.closed = true
        handler.removeCallbacks(link.idle)
        if (connecting === link) {
            connecting = null
            connectTimeout?.let { handler.removeCallbacks(it) }
            connectTimeout = null
        }
        runCatching { link.gatt?.close() }
        link.queue.clear()
        val wasLinked = links[link.peer] === link
        if (wasLinked) { links.remove(link.peer); listener?.onPeerLost(link.peer) }
        policy.onLinkClosed(link.peer, now(), wasReady = link.ready)
        i("link_closed", "role=central ready=${link.ready}", link.peer)
        emitPeers()
        handler.postDelayed({ maybeConnect() }, 500)
    }

    // ================= vai Peripheral: máy khác nối vào =================
    private val serverCb = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            handler.post {
                i("server_ready", "status=$status")
                if (status == BluetoothGatt.GATT_SUCCESS && started) { startAdvertising(); startScan() }
                else if (started) fatal("Không đăng ký được dịch vụ GATT (status=$status)")
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            handler.post {
                if (!started) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    incoming[device.address] = Incoming(device)
                } else {
                    val inc = incoming.remove(device.address)
                    val peer = inc?.peer
                    val l = peer?.let { links[it] }
                    if (l != null && l.role == Role.PERIPHERAL && l.device.address == device.address) dropPeripheral(l, cancel = false)
                }
                startAdvertising()          // nhiều máy dừng quảng bá khi có kết nối; bật lại cho chắc
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            handler.post {
                incoming[device.address]?.let { inc ->
                    inc.mtu = mtu
                    inc.peer?.let { links[it]?.mtu = mtu }
                }
                i("server_mtu", "mtu=$mtu")
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            val on = value?.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == true
            handler.post {
                if (descriptor.uuid != BleConstants.CCCD_UUID) return@post
                incoming[device.address]?.let { inc ->
                    inc.subscribed = on
                    inc.peer?.let { links[it]?.subscribed = on }
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            val v = value ?: return
            handler.post { onServerWrite(device, v) }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            handler.post {
                val peer = incoming[device.address]?.peer ?: return@post
                val l = links[peer] ?: return@post
                if (l.role != Role.PERIPHERAL || l.closed) return@post
                l.queue.complete()
                if (status != BluetoothGatt.GATT_SUCCESS) { w("notify_failed", "status=$status", peer); dropPeripheral(l) }
                else pump(l)
            }
        }
    }

    private fun onServerWrite(device: BluetoothDevice, raw: ByteArray) {
        if (!started) return
        val inc = incoming.getOrPut(device.address) { Incoming(device) }
        val known = inc.peer
        if (known != null) { deliver(known, raw); return }
        val f = LinkFrames.parse(raw)
        if (f !is LinkFrame.Ident) { w("data_before_ident"); return }
        val peer = f.peer
        if (!PeerIds.isValid(peer) || peer == selfId) { w("bad_ident"); runCatching { server?.cancelConnection(device) }; return }
        inc.peer = peer
        val link = Link(peer, Role.PERIPHERAL, device)
        link.mtu = inc.mtu
        link.subscribed = inc.subscribed
        link.ready = true

        val mine = connecting
        if (mine != null && mine.peer == peer) {
            // Ta đang nối ra chính peer này trong khi nó đã nối vào ta
            if (selfId.hex < peer.hex) { i("duplicate_keep_ours", peer = peer); runCatching { server?.cancelConnection(device) }; return }
            finishCentral(mine)
        }
        val existing = links[peer]
        if (existing != null) {
            if (existing.role == Role.CENTRAL && selfId.hex < peer.hex) {
                i("duplicate_keep_ours", peer = peer)
                runCatching { server?.cancelConnection(device) }
                return
            }
            if (existing.role == Role.CENTRAL) finishCentral(existing) else dropPeripheral(existing)
        }
        register(link)
    }

    private fun dropPeripheral(link: Link, cancel: Boolean = true) {
        if (link.closed) return
        link.closed = true
        handler.removeCallbacks(link.idle)
        link.queue.clear()
        if (links[link.peer] === link) { links.remove(link.peer); listener?.onPeerLost(link.peer) }
        if (cancel) runCatching { server?.cancelConnection(link.device) }
        policy.onRemoteLinkClosed(link.peer, now())
        i("link_closed", "role=peripheral", link.peer)
        emitPeers()
    }

    // ================= dùng chung =================
    private fun register(link: Link) {
        links[link.peer] = link
        touch(link)
        i("link_ready", "role=${link.role} mtu=${link.mtu}", link.peer)
        listener?.onPeerFound(link.peer)
        emitPeers()
    }

    private fun touch(l: Link) {
        l.lastActivity = now()
        handler.removeCallbacks(l.idle)
        handler.postDelayed(l.idle, if (l.role == Role.CENTRAL) IDLE_CLOSE_MS else IDLE_CLOSE_MS * 3)
    }

    private fun onIdle(l: Link) {
        if (l.closed) return
        val limit = if (l.role == Role.CENTRAL) IDLE_CLOSE_MS else IDLE_CLOSE_MS * 3
        if (now() - l.lastActivity >= limit - 200) { i("idle_close", peer = l.peer); closeLink(l) }
        else handler.postDelayed(l.idle, limit)
    }

    private fun closeLink(l: Link) = if (l.role == Role.CENTRAL) finishCentral(l) else dropPeripheral(l)
}
