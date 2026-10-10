package com.offlinemesh.core.mesh.crypto

import com.offlinemesh.core.model.PeerId

/**
 * Làm sạch chuỗi do NGƯỜI LẠ đặt (tên thẻ, nội dung SOS) trước khi hiển thị:
 * bỏ ký tự điều khiển, ký tự định dạng (đảo chiều chữ U+202E, độ rộng bằng 0...), ký tự riêng/chưa gán,
 * xuống dòng; gộp khoảng trắng; cắt theo số byte UTF-8 tại ranh giới ký tự.
 * Không chống được tên GIỐNG nhau bằng chữ khác (ví dụ "Mẹ" hai người): xem ContactBook.nameTaken và hậu tố ID khi hiển thị.
 */
object DisplayName {
    const val MAX_BYTES = 24

    fun clean(raw: String, maxBytes: Int = MAX_BYTES): String {
        val sb = StringBuilder()
        var bytes = 0
        var lastSpace = true
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            i += Character.charCount(cp)
            val type = Character.getType(cp)
            // Xuống dòng, tab... coi như khoảng trắng (để "Tầng 2\nCó 3 người" không dính chữ); mọi loại khoảng trắng gộp thành một dấu cách.
            val isSpace = type == Character.SPACE_SEPARATOR.toInt() || Character.isWhitespace(cp) ||
                type == Character.LINE_SEPARATOR.toInt() || type == Character.PARAGRAPH_SEPARATOR.toInt()
            if (!isSpace && (type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt() ||
                type == Character.UNASSIGNED.toInt() || type == Character.PRIVATE_USE.toInt() ||
                type == Character.SURROGATE.toInt() || type == Character.LINE_SEPARATOR.toInt() ||
                type == Character.PARAGRAPH_SEPARATOR.toInt())
            ) continue
            if (isSpace) {
                if (lastSpace || bytes + 1 > maxBytes) continue
                sb.append(' '); bytes += 1; lastSpace = true
                continue
            }
            val len = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (bytes + len > maxBytes) break
            sb.appendCodePoint(cp)
            bytes += len
            lastSpace = false
        }
        return sb.toString().trimEnd()
    }

    /** Tên mặc định khi thẻ không có tên dùng được. */
    fun fallback(peer: PeerId): String = "Máy ${peer.hex.take(4)}"
}
