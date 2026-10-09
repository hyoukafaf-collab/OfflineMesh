package com.offlinemesh.core.mesh.crypto

import com.offlinemesh.core.model.PeerId
import com.offlinemesh.core.model.PeerIds
import com.offlinemesh.core.model.toHex
import java.security.SecureRandom

/**
 * Danh tính của một máy: HAI cặp khóa P-256 riêng biệt (tách chức năng, không dùng một khóa cho cả ký và trao khóa):
 * khóa ký (ECDSA) và khóa mã hóa (ECDH). PeerId = 6 byte đầu của SHA-256(nhãn || khóa ký).
 */
class Identity(
    val signPriv: ByteArray, val signPub: ByteArray,
    val encPriv: ByteArray, val encPub: ByteArray,
) {
    val peerId: PeerId get() = peerIdOf(signPub)

    /** Dạng lưu trữ (chứa KHÓA RIÊNG): trên Android phải được bọc bằng Keystore trước khi ghi xuống đĩa. */
    fun serialize(): ByteArray = byteArrayOf(1) + signPriv + signPub + encPriv + encPub

    companion object {
        private val ID_LABEL = "OfflineMesh/id/v1".toByteArray()
        private const val SIZE = 1 + 32 + 33 + 32 + 33

        fun peerIdOf(signPub: ByteArray): PeerId =
            PeerId(Sha256.hash(ID_LABEL, signPub).copyOf(PeerIds.BYTES).toHex())

        fun generate(random: SecureRandom = SecureRandom()): Identity {
            val s = P256.generate(random)
            val e = P256.generate(random)
            return Identity(s.priv, s.pub, e.priv, e.pub)
        }

        fun deserialize(b: ByteArray): Identity? {
            if (b.size != SIZE || b[0].toInt() != 1) return null
            val id = Identity(b.copyOfRange(1, 33), b.copyOfRange(33, 66), b.copyOfRange(66, 98), b.copyOfRange(98, 131))
            return if (P256.isValidPublic(id.signPub) && P256.isValidPublic(id.encPub)) id else null
        }
    }
}
