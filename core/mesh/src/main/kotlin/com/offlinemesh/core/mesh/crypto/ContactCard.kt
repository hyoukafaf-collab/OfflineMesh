package com.offlinemesh.core.mesh.crypto

import com.offlinemesh.core.model.PeerId
import java.util.Base64

/**
 * Thẻ liên hệ trao đổi bằng QR khi gặp trực tiếp. Chứa khóa công khai ký + khóa công khai mã hóa.
 * Chuỗi: "OM1:" + base64url( [ver=1][độ dài tên][tên UTF-8 ≤ 24 byte][khóa ký 33][khóa mã hóa 33][4 byte kiểm tra] ).
 * 4 byte kiểm tra (đầu SHA-256) chỉ để bắt lỗi gõ/dán sai, KHÔNG phải cơ chế bảo mật.
 */
class ContactCard(val name: String, val signPub: ByteArray, val encPub: ByteArray) {
    val peerId: PeerId get() = Identity.peerIdOf(signPub)

    /** Mã vân tay 20 chữ số hex chia nhóm để hai người đọc cho nhau nghe, so với màn hình của đối phương. */
    fun fingerprint(): String =
        Sha256.hash(FP_LABEL, signPub, encPub).copyOf(10)
            .joinToString("") { "%02X".format(it) }.chunked(4).joinToString(" ")

    fun encode(): String {
        val n = name.toByteArray(Charsets.UTF_8).let { if (it.size > MAX_NAME) it.copyOf(MAX_NAME) else it }
        val body = byteArrayOf(1, n.size.toByte()) + n + signPub + encPub
        val chk = Sha256.hash(CHK_LABEL, body).copyOf(4)
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(body + chk)
    }

    fun sameKeysAs(o: ContactCard) = signPub.contentEquals(o.signPub) && encPub.contentEquals(o.encPub)

    companion object {
        const val PREFIX = "OM1:"
        private const val MAX_NAME = 24
        private val FP_LABEL = "OfflineMesh/fp/v1".toByteArray()
        private val CHK_LABEL = "OfflineMesh/card/v1".toByteArray()

        /** Trả null nếu sai tiền tố, sai độ dài, sai kiểm tra hoặc khóa không nằm trên đường cong. */
        fun decode(text: String): ContactCard? {
            val t = text.trim()
            if (!t.startsWith(PREFIX)) return null
            val raw = try { Base64.getUrlDecoder().decode(t.substring(PREFIX.length)) } catch (e: IllegalArgumentException) { return null }
            if (raw.size < 2 + 33 + 33 + 4 || raw[0].toInt() != 1) return null
            val nl = raw[1].toInt() and 0xFF
            if (nl > MAX_NAME || raw.size != 2 + nl + 66 + 4) return null
            val body = raw.copyOfRange(0, raw.size - 4)
            if (!Sha256.hash(CHK_LABEL, body).copyOf(4).contentEquals(raw.copyOfRange(raw.size - 4, raw.size))) return null
            val name = String(raw, 2, nl, Charsets.UTF_8)
            val sign = raw.copyOfRange(2 + nl, 2 + nl + 33)
            val enc = raw.copyOfRange(2 + nl + 33, 2 + nl + 66)
            if (!P256.isValidPublic(sign) || !P256.isValidPublic(enc)) return null
            return ContactCard(name, sign, enc)
        }
    }
}
