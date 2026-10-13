package com.offlinemesh.core.mesh.payload

/**
 * Quyết định lấy vị trí cho SOS sao cho KHÔNG làm chậm việc gửi.
 * Trong nhà, một lần định vị mới có thể không bao giờ có, nên không được chờ lâu:
 * - có vị trí đã biết còn mới (≤ 2 phút): gửi NGAY với vị trí đó (kèm tuổi của lần định vị);
 * - ngược lại chờ tối đa 3 giây một lần định vị mới; hết hạn thì dùng vị trí cũ hơn nếu còn dùng được (≤ 6 giờ),
 *   nếu không thì gửi không kèm tọa độ.
 * Tuổi của lần định vị luôn được ghi vào tin để người nhận biết vị trí đã cũ đến đâu.
 */
object SosLocationPlan {
    const val FRESH_ENOUGH_MS = 120_000L
    const val WAIT_FRESH_MS = 3_000L
    const val MAX_USABLE_AGE_MS = 6 * 3_600_000L

    class Plan(val sendNow: Boolean, val waitForFixMs: Long)

    /** lastFixAgeMs: tuổi lần định vị đã biết gần nhất (ms), null nếu chưa có. */
    fun plan(lastFixAgeMs: Long?): Plan =
        if (lastFixAgeMs != null && lastFixAgeMs in 0..FRESH_ENOUGH_MS) Plan(true, 0) else Plan(false, WAIT_FRESH_MS)

    /** Vị trí có đủ gần để còn đáng gửi không (cũ hơn thì gửi không kèm tọa độ còn hơn gửi sai chỗ). */
    fun isUsable(ageMs: Long): Boolean = ageMs in 0..MAX_USABLE_AGE_MS
}
