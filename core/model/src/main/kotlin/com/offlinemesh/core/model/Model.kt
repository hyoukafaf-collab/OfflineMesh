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

/** Loại tin. rank nhỏ = ưu tiên cao hơn; code là số cố định trên đường truyền (không đổi khi đổi rank). */
enum class Kind(val rank: Int, val code: Int) {
    ACK(0, 0), SOS(1, 1), TEXT(2, 2), IMAGE(3, 3), VIDEO(4, 4);

    companion object {
        fun fromCode(c: Int): Kind? = values().firstOrNull { it.code == c }
    }
}

/** Mã lỗi cố định, dùng trong log thay cho chuỗi tự do. */
enum class ErrorCode {
    UNKNOWN, LINK_TIMEOUT, LINK_LOST, HANDSHAKE_VERSION, BAD_SIGNATURE, BAD_HASH,
    FRAGMENT_MISSING, CACHE_FULL, EXPIRED, HOP_LIMIT, ILLEGAL_TRANSITION, BAD_FORMAT,
}
