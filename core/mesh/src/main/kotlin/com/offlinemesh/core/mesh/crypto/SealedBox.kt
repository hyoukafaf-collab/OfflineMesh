package com.offlinemesh.core.mesh.crypto

import java.security.SecureRandom

/**
 * Mã hóa lai kiểu ECIES/HPKE từ các thành phần chuẩn (xem CRYPTO.md):
 *   khóa tạm (ephemeral) ECDH P-256 → HKDF-SHA256 → AES-256-GCM, AAD gắn người gửi/người nhận/loại tin.
 * Định dạng: [version=1][khóa tạm nén 33][bản mã || thẻ GCM 16].  Không gửi nonce: nonce được dẫn xuất
 * từ HKDF cùng khóa, và mỗi tin dùng một khóa tạm mới nên cặp (khóa, nonce) không bao giờ lặp.
 */
object SealedBox {
    const val VERSION: Byte = 1
    const val OVERHEAD = 1 + 33 + 16
    private val LABEL = "OfflineMesh/e2ee/v1".toByteArray()

    /** Dữ liệu được xác thực kèm (không mã hóa): ràng buộc bản mã với người gửi, người nhận và loại tin. */
    fun aad(kind: Int, src: ByteArray, dst: ByteArray): ByteArray =
        LABEL + byteArrayOf(kind.toByte(), src.size.toByte()) + src + byteArrayOf(dst.size.toByte()) + dst

    private fun keyAndNonce(z: ByteArray, ephPub: ByteArray, recipientPub: ByteArray): Pair<ByteArray, ByteArray> {
        val okm = Hkdf.derive(salt = ByteArray(0), ikm = z, info = LABEL + ephPub + recipientPub, length = 44)
        return okm.copyOfRange(0, 32) to okm.copyOfRange(32, 44)
    }

    fun seal(
        recipientEncPub: ByteArray, plaintext: ByteArray, aad: ByteArray, random: SecureRandom = SecureRandom(),
    ): ByteArray {
        val eph = P256.generate(random)
        val z = P256.ecdh(eph.priv, recipientEncPub)
        val (key, nonce) = keyAndNonce(z, eph.pub, recipientEncPub)
        return byteArrayOf(VERSION) + eph.pub + AesGcm.encrypt(key, nonce, aad, plaintext)
    }

    /** Trả null nếu sai khóa, sai AAD, bị cắt/sửa hoặc định dạng không hợp lệ. */
    fun open(recipientEncPriv: ByteArray, recipientEncPub: ByteArray, blob: ByteArray, aad: ByteArray): ByteArray? {
        if (blob.size < OVERHEAD || blob[0] != VERSION) return null
        val ephPub = blob.copyOfRange(1, 34)
        if (!P256.isValidPublic(ephPub)) return null
        val z = try { P256.ecdh(recipientEncPriv, ephPub) } catch (e: Exception) { return null }
        val (key, nonce) = keyAndNonce(z, ephPub, recipientEncPub)
        return AesGcm.decrypt(key, nonce, aad, blob.copyOfRange(34, blob.size))
    }
}
