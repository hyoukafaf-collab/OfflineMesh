package com.offlinemesh.core.mesh.payload

import kotlin.math.max
import kotlin.math.min

/**
 * Lịch phát lại SOS: lần đầu sau firstIntervalMs rồi giãn gấp đôi mỗi lần đến tối đa maxIntervalMs
 * (mặc định 1 phút, 2, 4, 8, 16, 32, rồi 60 phút), và tự dừng sau durationMs (5 phút đến 12 giờ, mặc định 2 giờ).
 */
data class ReplayConfig(
    val firstIntervalMs: Long = 60_000,
    val maxIntervalMs: Long = 3_600_000,
    val durationMs: Long = DEFAULT_DURATION_MS,
) {
    init { require(firstIntervalMs > 0 && maxIntervalMs >= firstIntervalMs && durationMs > 0) }

    companion object {
        const val MIN_DURATION_MS = 5 * 60_000L
        const val MAX_DURATION_MS = 12 * 3_600_000L
        const val DEFAULT_DURATION_MS = 2 * 3_600_000L

        /** Các mức cho người dùng chọn (phút). */
        val DURATION_CHOICES_MIN = listOf(5, 15, 30, 60, 120, 240, 480, 720)

        fun clampDuration(ms: Long): Long = ms.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
    }
}

object ReplaySchedule {
    fun delayMs(round: Int, cfg: ReplayConfig): Long = min(cfg.firstIntervalMs shl min(round, 20), cfg.maxIntervalMs)
}

/**
 * Hàng đợi phát lại theo thứ tự vào trước ra trước (FIFO): mỗi lần đến hạn trả TẤT CẢ các mục theo thứ tự tạo
 * (cũ nhất trước). Mục mới thêm giữa chừng nối vào cuối. Dừng khi cancel() (ví dụ bấm "Tôi an toàn") hoặc hết hạn.
 * Phát lại tạo bản tin MỚI (vị trí mới, seq tăng) chứ không gửi lại bản cũ: bản cũ vốn đã được chào cho mọi máy
 * chưa có nó, nên gửi lại y nguyên không có tác dụng.
 */
class SosReplayQueue<T>(private val cfg: ReplayConfig = ReplayConfig()) {
    private val items = ArrayList<T>()
    private var startMs = 0L
    private var round = 0
    private var nextAt = Long.MAX_VALUE

    var active: Boolean = false
        private set

    val size: Int get() = items.size
    val endAtMs: Long get() = startMs + cfg.durationMs

    fun begin(nowMs: Long) {
        items.clear(); startMs = nowMs; round = 0
        nextAt = nowMs + ReplaySchedule.delayMs(0, cfg)
        active = true
    }

    fun add(item: T) { items += item }

    fun nextDueAtMs(): Long? = if (active) nextAt else null

    fun remainingMs(nowMs: Long): Long = if (active) max(0, endAtMs - nowMs) else 0

    /** Trả các mục đến hạn phát lại (FIFO); trống nếu chưa tới hạn, đã hết hạn (tự dừng) hoặc không hoạt động. */
    fun due(nowMs: Long): List<T> {
        if (!active) return emptyList()
        if (nowMs >= endAtMs) { cancel(); return emptyList() }
        if (nowMs < nextAt) return emptyList()
        val out = items.toList()
        round++
        nextAt += ReplaySchedule.delayMs(round, cfg)
        if (nextAt <= nowMs) nextAt = nowMs + ReplaySchedule.delayMs(round, cfg)     // xử lý trễ: tính lại từ bây giờ
        return out
    }

    fun cancel() { active = false; items.clear(); nextAt = Long.MAX_VALUE }
}

/** Các câu SOS nhanh (một chạm). Độ dài luôn ≤ SosPayload.MAX_TEXT byte. */
object SosPresets {
    class Preset(val type: SosType, val text: String)

    val QUICK = listOf(
        Preset(SosType.TRAPPED, "Tôi bị kẹt, cần cứu hộ"),
        Preset(SosType.INJURED, "Tôi bị thương, cần cứu hộ"),
        Preset(SosType.NEED_RESCUE, "Cần cứu hộ gấp"),
        Preset(SosType.NEED_SUPPLIES, "Cần nước và thực phẩm"),
    )
    val SAFE = Preset(SosType.SAFE, "Tôi an toàn")
}
