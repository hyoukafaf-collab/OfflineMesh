package com.offlinemesh.core.model

/** Mã định danh dài (hex); short() là 8 ký tự đầu, dùng trong log. */
@JvmInline
value class BundleId(val hex: String) {
    fun short(): String = hex.take(8)
    override fun toString(): String = short()
}

@JvmInline
value class PeerId(val hex: String) {
    fun short(): String = hex.take(8)
    override fun toString(): String = short()
}

/** Số nhỏ = ưu tiên cao hơn khi chọn bundle để relay. */
enum class Priority(val rank: Int) { SOS(0), TEXT(1), IMAGE(2), VIDEO(3) }

/** Mã lỗi cố định, dùng trong log thay cho chuỗi tự do. */
enum class ErrorCode {
    UNKNOWN, LINK_TIMEOUT, LINK_LOST, HANDSHAKE_VERSION, BAD_SIGNATURE, BAD_HASH,
    FRAGMENT_MISSING, CACHE_FULL, EXPIRED, HOP_LIMIT, ILLEGAL_TRANSITION,
}
