package com.offlinemesh.core.mesh.transport

/**
 * Hàng đợi gửi cho MỘT liên kết BLE: stack chỉ cho một thao tác ghi/notify đang bay, khung kế tiếp
 * chỉ được đưa xuống khi callback "đã gửi xong" của khung trước tới.
 * Dùng: offer() → poll() nếu có thì ghi xuống stack → khi callback tới gọi complete() rồi poll() tiếp.
 */
class SendQueue(private val capacity: Int = 4000) {
    private val q = ArrayDeque<ByteArray>()
    private var inFlight = false

    val size: Int get() = q.size
    val busy: Boolean get() = inFlight

    /** false nếu hàng đợi đầy (khung bị từ chối, người gọi coi như liên kết nghẽn). */
    fun offer(frame: ByteArray): Boolean {
        if (q.size >= capacity) return false
        q.addLast(frame)
        return true
    }

    /** Khung tiếp theo nếu đang rảnh; đánh dấu đang bay. null nếu đang bận hoặc hết khung. */
    fun poll(): ByteArray? {
        if (inFlight) return null
        val f = q.removeFirstOrNull() ?: return null
        inFlight = true
        return f
    }

    /** Stack báo khung đang bay đã xong (thành công hay không). */
    fun complete() { inFlight = false }

    /** Ghi khung vừa lấy thất bại tạm thời (stack đang bận): trả lại đầu hàng để thử lại sau. */
    fun retryFront(frame: ByteArray) {
        q.addFirst(frame)
        inFlight = false
    }

    fun clear() {
        q.clear()
        inFlight = false
    }
}
