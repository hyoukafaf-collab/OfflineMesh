package com.offlinemesh.app.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.offlinemesh.app.MainActivity

/**
 * Thông báo tin nhận được. Âm thanh của kênh không đổi được sau khi tạo, nên mỗi tổ hợp (tin/SOS × có/không âm) là một kênh riêng.
 * Thông báo "ứng dụng đang chạy" là kênh khác, ở mức im lặng tối thiểu (Android bắt buộc phải có).
 */
object Notifier {
    private lateinit var app: Context
    private var nextId = 100

    fun init(ctx: Context) {
        app = ctx.applicationContext
        val nm = app.getSystemService(NotificationManager::class.java)
        val alarm = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build()
        nm.createNotificationChannel(NotificationChannel("msg_sound", "Tin nhắn (có âm thanh)", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel("msg_silent", "Tin nhắn (im lặng)", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("sos_sound", "SOS (có âm thanh)", NotificationManager.IMPORTANCE_HIGH).apply { setSound(alarm, attrs) })
        nm.createNotificationChannel(NotificationChannel("sos_silent", "SOS (im lặng)", NotificationManager.IMPORTANCE_DEFAULT).apply { setSound(null, null) })
    }

    /** conv: cuộc trò chuyện chứa tin; bỏ qua nếu người dùng đang xem đúng cuộc trò chuyện đó. */
    fun notify(sos: Boolean, title: String, body: String, conv: String, viewingConv: String?) {
        if (sos && !AppSettings.notifSos) return
        if (!sos && !AppSettings.notifMsg) return
        if (viewingConv == conv) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val sound = if (sos) AppSettings.soundSos else AppSettings.soundMsg
        val channel = (if (sos) "sos_" else "msg_") + (if (sound) "sound" else "silent")
        val open = PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = android.app.Notification.Builder(app, channel)
            .setSmallIcon(if (sos) android.R.drawable.ic_dialog_alert else android.R.drawable.stat_notify_chat)
            .setContentTitle(title).setContentText(body).setContentIntent(open).setAutoCancel(true).build()
        app.getSystemService(NotificationManager::class.java).notify(nextId++, n)
    }
}
