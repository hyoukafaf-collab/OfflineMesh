package com.offlinemesh.core.mesh.settings

/** Thời gian lưu tin nhắn trên máy này: từ 1 ngày đến 3 tháng, mặc định 1 tháng; tin cũ hơn tự xóa. */
object Retention {
    const val MIN_DAYS = 1
    const val MAX_DAYS = 90
    const val DEFAULT_DAYS = 30
    private const val DAY_MS = 86_400_000L

    val CHOICES = listOf(1, 3, 7, 14, 30, 60, 90)

    fun clampDays(d: Int): Int = d.coerceIn(MIN_DAYS, MAX_DAYS)
    fun cutoffMs(nowMs: Long, days: Int): Long = nowMs - clampDays(days) * DAY_MS

    fun label(days: Int): String = when {
        days >= 30 && days % 30 == 0 -> "${days / 30} tháng"
        days == 7 -> "1 tuần"
        days == 14 -> "2 tuần"
        else -> "$days ngày"
    }
}

/** Xóa thủ công trong một cuộc trò chuyện: các tin cũ hơn một mốc. Chỉ xóa trên máy này, người kia không bị ảnh hưởng. */
enum class ManualRange(val days: Int, val label: String) {
    DAY(1, "1 ngày"), WEEK(7, "1 tuần"), TWO_WEEKS(14, "2 tuần"), MONTH(30, "1 tháng");

    fun cutoffMs(nowMs: Long): Long = nowMs - days * 86_400_000L
}
