package com.offlinemesh.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.offlinemesh.app.AppLog
import com.offlinemesh.app.mesh.MeshController
import com.offlinemesh.app.mesh.MeshRuntime

/**
 * Giữ tiến trình sống khi tắt màn hình; MeshNode + BleTransport chạy trong MeshRuntime của dịch vụ này.
 * Lắng nghe sự kiện hệ thống "Bluetooth bị tắt" (không quét định kỳ nên không tốn pin).
 */
class MeshService : Service() {
    private var runtime: MeshRuntime? = null

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                // Giao diện tự hiện dòng mờ "Bluetooth đang tắt..." nhờ AppGate (cũng theo sự kiện hệ thống), nên không cần thêm lỗi ở đây.
                shutdown()
            }
        }
    }
    private var receiverRegistered = false

    // Pin: nhận sự kiện hệ thống khi % pin hoặc trạng thái sạc đổi (không đọc định kỳ nên không tốn pin).
    private var batteryRegistered = false
    private var lastPct = -1
    private var lastCharging = false
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { handleBattery(intent) }
    }

    private fun handleBattery(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return
        val pct = level * 100 / scale
        val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        if (pct == lastPct && charging == lastCharging) return      // ACTION_BATTERY_CHANGED còn bắn khi nhiệt độ/điện áp đổi
        lastPct = pct; lastCharging = charging
        runtime?.onBattery(pct, charging)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android bắt buộc có một thông báo khi ứng dụng chạy nền liên tục. Đặt ở mức MIN: im lặng, thu gọn, không hiện biểu tượng trên thanh trạng thái.
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Chạy nền", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("OfflineMesh")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(2, n)
        }

        // Mỗi lần dịch vụ được khởi động (vào app hoặc Bluetooth bật lại): dừng bản cũ, tạo bản mới và đọc lại Bluetooth ngay lúc đó.
        runtime?.stop()
        MeshController.commands = null
        val fresh = MeshRuntime(this, AppLog.log)
        fresh.onFatal = { shutdown() }
        if (fresh.start()) {
            runtime = fresh
            MeshController.commands = fresh
            if (!receiverRegistered) {
                ContextCompat.registerReceiver(
                    this, btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                receiverRegistered = true
            }
            lastPct = -1
            if (!batteryRegistered) {
                ContextCompat.registerReceiver(
                    this, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                batteryRegistered = true
            }
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { handleBattery(it) }   // trạng thái hiện tại
        } else {
            stopSelf()   // start() đã hiện lỗi; không giữ dịch vụ nền vô ích
        }
        return START_NOT_STICKY
    }

    private fun shutdown() {
        runtime?.stop()
        runtime = null
        MeshController.commands = null
        stopSelf()
    }

    override fun onDestroy() {
        if (receiverRegistered) { runCatching { unregisterReceiver(btReceiver) }; receiverRegistered = false }
        if (batteryRegistered) { runCatching { unregisterReceiver(batteryReceiver) }; batteryRegistered = false }
        runtime?.stop()
        runtime = null
        MeshController.commands = null
        MeshController.update { it.copy(running = false) }
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "mesh_run"
    }
}
