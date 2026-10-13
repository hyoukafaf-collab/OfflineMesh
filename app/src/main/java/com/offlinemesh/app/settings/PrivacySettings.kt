package com.offlinemesh.app.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf

/**
 * Cài đặt quyền riêng tư, lưu bền qua các lần mở app. Mặc định TẮT: không bao giờ tự bật.
 * shareLocation: khi bật, mọi SOS của bạn kèm tọa độ và mọi máy nhận được (kể cả máy chuyển tiếp) đều thấy.
 *
 * Là trạng thái Compose (đọc `.value` bên trong getter) nên MỌI nơi hiển thị công tắc này (Cài đặt, mục SOS) tự cập nhật ngay khi đổi,
 * không cần thoát màn hình rồi vào lại.
 */
object PrivacySettings {
    private lateinit var prefs: SharedPreferences
    private val shareState = mutableStateOf(false)

    val shareLocation: Boolean get() = shareState.value

    @Volatile
    var listener: ((Boolean) -> Unit)? = null

    fun init(ctx: Context) {
        prefs = ctx.applicationContext.getSharedPreferences("privacy", Context.MODE_PRIVATE)
        shareState.value = prefs.getBoolean("share_location", false)
    }

    fun setShareLocation(on: Boolean) {
        shareState.value = on
        prefs.edit().putBoolean("share_location", on).apply()
        listener?.invoke(on)
    }
}
