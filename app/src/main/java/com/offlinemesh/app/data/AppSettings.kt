package com.offlinemesh.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import com.offlinemesh.core.mesh.payload.ReplayConfig
import com.offlinemesh.core.mesh.settings.Retention

/** Cài đặt người dùng, lưu bền. Là trạng thái Compose nên giao diện tự cập nhật khi đổi. */
object AppSettings {
    private lateinit var prefs: SharedPreferences

    // Mỗi cài đặt là một trạng thái Compose riêng tư + getter công khai chỉ đọc. Không dùng `var x by mutableStateOf(..); private set`
    // vì với thuộc tính ủy quyền, Kotlin vẫn tạo hàm setX() riêng tư, trùng chữ ký JVM với hàm setX(v) công khai bên dưới
    // ("Platform declaration clash"). Đọc `.value` trong giao diện vẫn được Compose theo dõi nên giao diện tự cập nhật.
    private val themeS = mutableStateOf(0)                // 0 theo hệ thống, 1 sáng, 2 tối
    private val notifMsgS = mutableStateOf(true)
    private val soundMsgS = mutableStateOf(false)
    private val notifSosS = mutableStateOf(true)
    private val soundSosS = mutableStateOf(true)
    private val replayMinS = mutableStateOf(120)
    private val retentionDaysS = mutableStateOf(Retention.DEFAULT_DAYS)
    private val termsAcceptedS = mutableStateOf(false)
    private val warnSosHiddenS = mutableStateOf(false)
    private val warnPublicHiddenS = mutableStateOf(false)
    private val restoreAskedS = mutableStateOf(false)         // đã hỏi "khôi phục bản sao lưu cũ?" ở lần mở đầu tiên
    private val lastBackupS = mutableStateOf(0L)               // giờ (ms) của lần sao lưu gần nhất, 0 = chưa từng
    private val contactsOnlyS = mutableStateOf(false)          // true: chỉ nhận TIN RIÊNG từ người trong danh bạ (công khai và SOS không bị ảnh hưởng)

    val theme: Int get() = themeS.value
    val notifMsg: Boolean get() = notifMsgS.value
    val soundMsg: Boolean get() = soundMsgS.value
    val notifSos: Boolean get() = notifSosS.value
    val soundSos: Boolean get() = soundSosS.value
    val replayMin: Int get() = replayMinS.value
    val retentionDays: Int get() = retentionDaysS.value
    val termsAccepted: Boolean get() = termsAcceptedS.value
    val warnSosHidden: Boolean get() = warnSosHiddenS.value
    val warnPublicHidden: Boolean get() = warnPublicHiddenS.value
    val restoreAsked: Boolean get() = restoreAskedS.value
    val lastBackupMs: Long get() = lastBackupS.value
    val contactsOnly: Boolean get() = contactsOnlyS.value

    fun init(ctx: Context) {
        prefs = ctx.applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        themeS.value = prefs.getInt("theme", 0)
        notifMsgS.value = prefs.getBoolean("notif_msg", true)
        soundMsgS.value = prefs.getBoolean("sound_msg", false)
        notifSosS.value = prefs.getBoolean("notif_sos", true)
        soundSosS.value = prefs.getBoolean("sound_sos", true)
        replayMinS.value = prefs.getInt("replay_min", 120)
        retentionDaysS.value = Retention.clampDays(prefs.getInt("retention_days", Retention.DEFAULT_DAYS))
        termsAcceptedS.value = prefs.getBoolean("terms", false)
        warnSosHiddenS.value = prefs.getBoolean("warn_sos_hidden", false)
        warnPublicHiddenS.value = prefs.getBoolean("warn_public_hidden", false)
        restoreAskedS.value = prefs.getBoolean("restore_asked", false)
        lastBackupS.value = prefs.getLong("last_backup", 0L)
        contactsOnlyS.value = prefs.getBoolean("contacts_only", false)
    }

    fun replayDurationMs(): Long = ReplayConfig.clampDuration(replayMin * 60_000L)

    fun setTheme(v: Int) { themeS.value = v; prefs.edit().putInt("theme", v).apply() }
    fun setNotifMsg(v: Boolean) { notifMsgS.value = v; prefs.edit().putBoolean("notif_msg", v).apply() }
    fun setSoundMsg(v: Boolean) { soundMsgS.value = v; prefs.edit().putBoolean("sound_msg", v).apply() }
    fun setNotifSos(v: Boolean) { notifSosS.value = v; prefs.edit().putBoolean("notif_sos", v).apply() }
    fun setSoundSos(v: Boolean) { soundSosS.value = v; prefs.edit().putBoolean("sound_sos", v).apply() }
    fun setReplayMin(v: Int) { replayMinS.value = v; prefs.edit().putInt("replay_min", v).apply() }
    fun setRetentionDays(v: Int) { retentionDaysS.value = Retention.clampDays(v); prefs.edit().putInt("retention_days", retentionDaysS.value).apply() }
    fun acceptTerms() { termsAcceptedS.value = true; prefs.edit().putBoolean("terms", true).apply() }
    fun hideSosWarning() { warnSosHiddenS.value = true; prefs.edit().putBoolean("warn_sos_hidden", true).apply() }
    fun markRestoreAsked() { restoreAskedS.value = true; prefs.edit().putBoolean("restore_asked", true).apply() }
    fun setLastBackup(ms: Long) { lastBackupS.value = ms; prefs.edit().putLong("last_backup", ms).apply() }
    fun setContactsOnly(v: Boolean) { contactsOnlyS.value = v; prefs.edit().putBoolean("contacts_only", v).apply() }
    fun hidePublicWarning() { warnPublicHiddenS.value = true; prefs.edit().putBoolean("warn_public_hidden", true).apply() }
}
