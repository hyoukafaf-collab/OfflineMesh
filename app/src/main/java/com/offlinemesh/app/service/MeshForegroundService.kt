package com.offlinemesh.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.offlinemesh.app.AppLog
import com.offlinemesh.app.ble.BleSpike
import com.offlinemesh.app.echo.EchoController

/** Giữ tiến trình sống khi tắt màn hình; BLE chạy trong service này, không chạy trong Activity. */
class MeshForegroundService : Service() {
    private var spike: BleSpike? = null

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
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1, n)
        }
        // Hai chế độ dùng chung dịch vụ GATT nên không chạy cùng lúc.
        stopService(Intent(this, MeshService::class.java))
        // Mỗi lần bấm Bắt đầu đều khởi động lại sạch để kiểm tra lại Bluetooth, không dùng lại trạng thái cũ.
        spike?.stop()
        spike = null
        EchoController.commands = null
        val fresh = BleSpike(this, AppLog.log)
        if (fresh.start()) {
            spike = fresh
            EchoController.commands = fresh
        } else {
            stopSelf()   // start() đã hiện lỗi trên giao diện; không giữ dịch vụ nền vô ích
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        spike?.stop()
        spike = null
        EchoController.commands = null
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "mesh"
    }
}
