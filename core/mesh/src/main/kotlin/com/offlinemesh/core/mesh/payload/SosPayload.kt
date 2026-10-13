package com.offlinemesh.core.mesh.payload

import com.offlinemesh.core.mesh.crypto.DisplayName
import java.nio.ByteBuffer

enum class SosType(val code: Int, val label: String) {
    NEED_RESCUE(0, "Cần cứu hộ"),
    INJURED(1, "Có người bị thương"),
    TRAPPED(2, "Bị mắc kẹt"),
    SAFE(3, "Tôi an toàn"),
    NEED_SUPPLIES(4, "Cần nhu yếu phẩm");

    companion object {
        fun fromCode(c: Int): SosType? = values().firstOrNull { it.code == c }
    }
}

/** Vị trí theo đơn vị 1e-6 độ (≈ 0,11 m); accuracyM null = không rõ. */
data class GeoPoint(val latE6: Int, val lonE6: Int, val accuracyM: Int? = null) {
    val valid: Boolean get() = latE6 in -90_000_000..90_000_000 && lonE6 in -180_000_000..180_000_000
}

/**
 * Nội dung tin SOS (có cấu trúc, gọn để vừa vài khung BLE):
 * [ver=2][loại:1][cờ:1 (bit0 = có tọa độ)][lat:4][lon:4][độ chính xác m:2 (0xFFFF = không rõ)][tuổi của lần định vị, giây:2][session:2][seq:1][độ dài chữ:1][chữ UTF-8 ≤ 140 byte]
 * session = mã của MỘT thông điệp SOS được phát lại nhiều lần; seq tăng mỗi lần phát lại. Người nhận gộp theo (người gửi, session) và chỉ hiện bản seq cao nhất.
 * Không chứa giờ tuyệt đối vì đồng hồ các máy lệch nhau; chỉ có "tuổi của lần định vị lúc gửi".
 */
class SosPayload(
    val type: SosType, val position: GeoPoint?, val fixAgeSec: Int, text: String,
    sessionId: Int = 0, seq: Int = 0,
) {
    val sessionId: Int = sessionId and 0xFFFF
    val seq: Int = seq.coerceIn(0, 255)

    val text: String = DisplayName.clean(text, MAX_TEXT)

    fun encode(): ByteArray {
        val t = text.toByteArray(Charsets.UTF_8)
        val bb = ByteBuffer.allocate(HEADER + t.size)
        bb.put(2).put(type.code.toByte()).put(if (position != null) 1 else 0)
        bb.putInt(position?.latE6 ?: 0).putInt(position?.lonE6 ?: 0)
        bb.putShort((position?.accuracyM ?: 0xFFFF).coerceIn(0, 0xFFFF).toShort())
        bb.putShort(fixAgeSec.coerceIn(0, 0xFFFF).toShort())
        bb.putShort(sessionId.toShort()).put(seq.toByte())
        bb.put(t.size.toByte()).put(t)
        return bb.array()
    }

    companion object {
        const val MAX_TEXT = 140
        private const val HEADER = 19

        /** null nếu sai định dạng/giá trị; không ném lỗi. */
        fun decode(b: ByteArray): SosPayload? {
            if (b.size < HEADER || b[0].toInt() != 2) return null
            val bb = ByteBuffer.wrap(b)
            bb.get()
            val type = SosType.fromCode(bb.get().toInt() and 0xFF) ?: return null
            val flags = bb.get().toInt() and 0xFF
            val lat = bb.int
            val lon = bb.int
            val acc = bb.short.toInt() and 0xFFFF
            val age = bb.short.toInt() and 0xFFFF
            val session = bb.short.toInt() and 0xFFFF
            val seq = bb.get().toInt() and 0xFF
            val len = bb.get().toInt() and 0xFF
            if (len > MAX_TEXT || bb.remaining() != len) return null
            val raw = ByteArray(len).also { bb.get(it) }
            val pos = if (flags and 1 != 0) GeoPoint(lat, lon, if (acc == 0xFFFF) null else acc) else null
            if (pos != null && !pos.valid) return null
            return SosPayload(type, pos, age, String(raw, Charsets.UTF_8), session, seq)
        }
    }
}
