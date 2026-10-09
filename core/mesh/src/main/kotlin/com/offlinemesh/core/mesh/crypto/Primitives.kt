package com.offlinemesh.core.mesh.crypto

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Toàn bộ mật mã ở đây chỉ GỌI các thuật toán chuẩn của JCA (có sẵn trên JVM và Android 8+):
 * NIST P-256 (ECDSA, ECDH), SHA-256, HMAC-SHA256, AES-256-GCM. Không có thuật toán tự chế.
 */
object Sha256 {
    fun hash(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest()
    }
}

/** Đường cong NIST P-256 (secp256r1). Khóa riêng 32 byte, khóa công khai nén 33 byte (SEC1 §2.3.3). */
object P256 {
    class KeyPair(val priv: ByteArray, val pub: ByteArray)

    private val params: ECParameterSpec by lazy {
        val ap = AlgorithmParameters.getInstance("EC")
        ap.init(ECGenParameterSpec("secp256r1"))
        ap.getParameterSpec(ECParameterSpec::class.java)
    }
    private val p: BigInteger by lazy { (params.curve.field as ECFieldFp).p }
    private val order: BigInteger by lazy { params.order }

    fun generate(random: SecureRandom = SecureRandom()): KeyPair {
        val g = KeyPairGenerator.getInstance("EC")
        g.initialize(ECGenParameterSpec("secp256r1"), random)
        val kp = g.generateKeyPair()
        return KeyPair(fixed((kp.private as ECPrivateKey).s, 32), compress((kp.public as ECPublicKey).w))
    }

    private fun fixed(x: BigInteger, len: Int): ByteArray {
        val raw = x.toByteArray()
        val out = ByteArray(len)
        val n = minOf(raw.size, len)
        System.arraycopy(raw, raw.size - n, out, len - n, n)
        return out
    }

    fun compress(w: ECPoint): ByteArray =
        byteArrayOf(if (w.affineY.testBit(0)) 3 else 2) + fixed(w.affineX, 32)

    /** Giải nén và KIỂM TRA điểm nằm trên đường cong (chống tấn công điểm không hợp lệ). Trả null nếu sai. */
    fun decompress(b: ByteArray): ECPoint? {
        if (b.size != 33 || (b[0].toInt() != 2 && b[0].toInt() != 3)) return null
        val x = BigInteger(1, b.copyOfRange(1, 33))
        if (x >= p) return null
        val rhs = (x.pow(3) + params.curve.a * x + params.curve.b).mod(p)
        val y0 = rhs.modPow((p + BigInteger.ONE).shiftRight(2), p)          // p ≡ 3 (mod 4)
        if (y0.multiply(y0).mod(p) != rhs) return null                      // x không ứng với điểm nào
        val y = if (y0.testBit(0) == (b[0].toInt() == 3)) y0 else p - y0
        return ECPoint(x, y)
    }

    fun isValidPublic(pub: ByteArray): Boolean = decompress(pub) != null

    private fun publicKey(pub: ByteArray): ECPublicKey {
        val pt = decompress(pub) ?: throw IllegalArgumentException("khóa công khai không hợp lệ")
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(pt, params)) as ECPublicKey
    }

    private fun privateKey(priv: ByteArray): ECPrivateKey {
        val s = BigInteger(1, priv)
        require(s.signum() > 0 && s < order) { "khóa riêng ngoài khoảng" }
        return KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(s, params)) as ECPrivateKey
    }

    /** ECDH (NIST SP 800-56A): trả tọa độ x của điểm chung, 32 byte. Chưa phải khóa, phải qua HKDF. */
    fun ecdh(priv: ByteArray, peerPub: ByteArray): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(privateKey(priv))
        ka.doPhase(publicKey(peerPub), true)
        return ka.generateSecret()
    }

    /** ECDSA-P256-SHA256, chữ ký dạng thô r||s (64 byte). */
    fun sign(priv: ByteArray, msg: ByteArray): ByteArray {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(privateKey(priv))
        sig.update(msg)
        return derToRaw(sig.sign())
    }

    fun verify(pub: ByteArray, msg: ByteArray, raw: ByteArray): Boolean = try {
        if (raw.size != 64) false else {
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(publicKey(pub))
            sig.update(msg)
            sig.verify(rawToDer(raw))
        }
    } catch (e: Exception) { false }

    // --- chuyển đổi DER <-> thô (r||s) ---
    private fun derToRaw(der: ByteArray): ByteArray {
        var pos = 2 + if (der[1].toInt() and 0x80 != 0) (der[1].toInt() and 0x7F) else 0
        require(der[pos].toInt() == 0x02)
        val rl = der[pos + 1].toInt() and 0xFF
        val r = BigInteger(1, der.copyOfRange(pos + 2, pos + 2 + rl)); pos += 2 + rl
        require(der[pos].toInt() == 0x02)
        val sl = der[pos + 1].toInt() and 0xFF
        val s = BigInteger(1, der.copyOfRange(pos + 2, pos + 2 + sl))
        return fixed(r, 32) + fixed(s, 32)
    }

    private fun rawToDer(raw: ByteArray): ByteArray {
        fun enc(b: ByteArray): ByteArray {
            var v = b.dropWhile { it.toInt() == 0 }.toByteArray()
            if (v.isEmpty()) v = byteArrayOf(0)
            if (v[0].toInt() and 0x80 != 0) v = byteArrayOf(0) + v
            return byteArrayOf(0x02, v.size.toByte()) + v
        }
        val body = enc(raw.copyOfRange(0, 32)) + enc(raw.copyOfRange(32, 64))
        return byteArrayOf(0x30, body.size.toByte()) + body
    }
}

/** HKDF-SHA256 (RFC 5869). */
object Hkdf {
    private fun hmac(key: ByteArray, vararg data: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256"))
        data.forEach { m.update(it) }
        return m.doFinal()
    }

    fun derive(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * 32)
        val prk = hmac(salt, ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var i = 1
        while (pos < length) {
            t = hmac(prk, t, info, byteArrayOf(i.toByte()))
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n; i++
        }
        return out
    }
}

/** AES-256-GCM (NIST SP 800-38D), thẻ xác thực 128 bit nối vào cuối bản mã. */
object AesGcm {
    fun encrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(aad)
        return c.doFinal(plaintext)
    }

    /** Trả null nếu thẻ xác thực sai (bản mã/AAD/khóa bị sai hoặc bị sửa). */
    fun decrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(aad)
        c.doFinal(ciphertext)
    } catch (e: Exception) { null }
}
