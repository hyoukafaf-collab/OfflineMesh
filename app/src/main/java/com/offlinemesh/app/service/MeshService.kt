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
                MeshController.update { it.copy(error = "Bluetooth đã bị tắt: hãy bật lại rồi bấm Bắt đầu") }
                shutdown()
            }
        }
    }
    private var receiverRegistered = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Mesh", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("OfflineMesh đang chạy")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(2, n)
        }
        // Hai chế độ dùng chung dịch vụ GATT nên không chạy cùng lúc.
        stopService(Intent(this, MeshForegroundService::class.java))

        // Mỗi lần bấm Bắt đầu: dừng bản cũ, tạo bản mới và đọc lại Bluetooth ngay lúc đó.
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
        runtime?.stop()
        runtime = null
        MeshController.commands = null
        MeshController.update { it.copy(running = false) }
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "mesh"
    }
}
