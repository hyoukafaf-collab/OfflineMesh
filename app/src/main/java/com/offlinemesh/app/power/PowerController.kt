package com.offlinemesh.app.power

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.offlinemesh.app.apk.ApkShare
import com.offlinemesh.core.mesh.power.BatteryStats
import com.offlinemesh.core.mesh.power.PowerLevel
import com.offlinemesh.core.mesh.power.PowerProfile

data class PowerUiState(
    val emergency: Boolean = false,
    val forced: String = "AUTO",                 // AUTO | FULL | SAVER | CRITICAL
    val battery: Int = -1,
    val charging: Boolean = false,
    val levelText: String = "(chưa chạy)",
    val detail: String = "",
    val table: List<String> = emptyList(),
    val apkInfo: String? = null,
    val apkMessage: String? = null,
)

/** Ranh giới giữa giao diện Năng lượng và lõi. Gọi init() một lần ở Application. */
object PowerController {
    lateinit var recorder: BatteryRecorder
        private set
    private lateinit var prefs: SharedPreferences
    private val main = Handler(Looper.getMainLooper())

    var state by mutableStateOf(PowerUiState())
        private set

    @Volatile
    var listener: ((PowerUiState) -> Unit)? = null

    /** Runtime đặt hàm này khi đang chạy để được báo khi người dùng đổi chế độ khẩn cấp / ép mức. */
    @Volatile
    var onSettingsChanged: (() -> Unit)? = null

    val emergency: Boolean get() = state.emergency

    fun forcedLevel(): PowerLevel? = when (state.forced) {
        "FULL" -> PowerLevel.FULL; "SAVER" -> PowerLevel.SAVER; "CRITICAL" -> PowerLevel.CRITICAL; else -> null
    }

    fun init(ctx: Context) {
        val app = ctx.applicationContext
        recorder = BatteryRecorder(app)
        prefs = app.getSharedPreferences("power", Context.MODE_PRIVATE)
        update { it.copy(emergency = prefs.getBoolean("emergency", false), forced = prefs.getString("forced", "AUTO") ?: "AUTO") }
        refreshTable()
    }

    private fun update(f: (PowerUiState) -> PowerUiState) {
        synchronized(this) { state = f(state) }
        listener?.invoke(state)
    }

    fun setEmergency(on: Boolean) {
        prefs.edit().putBoolean("emergency", on).apply()
        update { it.copy(emergency = on) }
        onSettingsChanged?.invoke()
    }

    fun setForced(name: String) {
        prefs.edit().putString("forced", name).apply()
        update { it.copy(forced = name) }
        onSettingsChanged?.invoke()
    }

    /** Runtime báo mức năng lượng đang áp dụng. */
    fun onProfile(battery: Int, charging: Boolean, p: PowerProfile) {
        val levelText = when (p.level) { PowerLevel.FULL -> "Đầy đủ"; PowerLevel.SAVER -> "Tiết kiệm"; PowerLevel.CRITICAL -> "Cực tiết kiệm" }
        val scan = if (p.scan.continuous) "quét liên tục" else "quét ${p.scan.onMs / 1000}s mỗi ${(p.scan.onMs + p.scan.offMs) / 1000}s (${p.scan.dutyPercent}%)"
        val relay = p.relay.kinds.joinToString(", ") { it.name } + " ≤ ${p.relay.maxBytes / 1024} KB"
        update {
            it.copy(
                battery = battery, charging = charging, levelText = levelText,
                detail = "$scan · quảng bá ${p.adv.name} · tối đa ${p.maxCentralLinks} liên kết · chuyển tiếp hộ người khác: $relay " +
                    "(SOS, ACK và tin gửi cho bạn luôn được nhận)",
            )
        }
    }

    fun refreshTable() {
        val rows = BatteryStats.summarize(recorder.samples()).map {
            val ph = it.perHour?.let { v -> "%.1f %%/giờ".format(java.util.Locale.US, v) } ?: "chưa đủ dữ liệu (cần ≥ 1 giờ)"
            "${it.label}: ${"%.1f".format(java.util.Locale.US, it.hours)} giờ, tụt ${"%.0f".format(java.util.Locale.US, it.dropPercent)}% → $ph"
        }
        update { it.copy(table = rows.ifEmpty { listOf("(chưa có số liệu: chạy Mesh với màn hình tắt ít nhất 1 giờ cho mỗi chế độ)") }) }
    }

    fun clearTable() { recorder.clear(); refreshTable() }

    /** Chuẩn bị tệp APK (chạy nền) rồi mở bảng chọn chia sẻ (chọn Bluetooth). */
    fun shareApk(ctx: Context) {
        update { it.copy(apkMessage = "Đang chuẩn bị tệp cài đặt…") }
        Thread {
            val r = ApkShare.prepare(ctx)
            main.post {
                when (r) {
                    is ApkShare.Result.Unsupported -> update { it.copy(apkMessage = r.reason, apkInfo = null) }
                    is ApkShare.Result.Ready -> {
                        update {
                            it.copy(
                                apkInfo = "Phiên bản ${r.info.versionName} · ${r.info.sizeBytes / 1024} KB · SHA-256 tệp: ${ApkShare.group(r.info.sha256)}…" +
                                    (r.info.certSha256?.let { c -> " · chữ ký: ${ApkShare.group(c).take(19)}…" } ?: ""),
                                apkMessage = "Chọn Bluetooth trong bảng chia sẻ. Bên nhận: mở tệp vừa nhận rồi cho phép cài từ nguồn này.",
                            )
                        }
                        runCatching { ctx.startActivity(ApkShare.shareIntent(ctx, r.info)) }
                            .onFailure { e -> update { it.copy(apkMessage = "Không mở được bảng chia sẻ: ${e.message}") } }
                    }
                }
            }
        }.start()
    }
}
