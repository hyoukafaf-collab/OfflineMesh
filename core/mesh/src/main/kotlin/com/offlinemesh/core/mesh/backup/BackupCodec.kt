package com.offlinemesh.core.mesh.backup

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupEntry(val name: String, val data: ByteArray)

sealed class BackupResult {
    class Ok(val entries: List<BackupEntry>) : BackupResult()
    /** Không phải tệp sao lưu của ứng dụng (sai đầu tệp hoặc quá ngắn). */
    object NotABackup : BackupResult()
    /** Tệp từ một phiên bản định dạng mới hơn mà bản này chưa hiểu. */
    object UnsupportedVersion : BackupResult()
    /** Sai mật khẩu HOẶC tệp bị sửa/hỏng: AES-GCM không phân biệt hai trường hợp (cố ý, để không lộ thông tin). */
    object WrongPasswordOrDamaged : BackupResult()
    object TooLarge : BackupResult()
}

/**
 * Tệp sao lưu do NGƯỜI DÙNG chủ động tạo, mã hóa bằng mật khẩu của họ. Chỉ dùng thuật toán chuẩn, không tự chế:
 *   khóa = PBKDF2-HMAC-SHA256(mật khẩu chuẩn hóa NFC, muối ngẫu nhiên 16 byte, số vòng lặp lưu trong tệp, 256 bit)
 *   dữ liệu = AES-256-GCM (nonce ngẫu nhiên 12 byte, thẻ 128 bit), đầu tệp được xác thực như dữ liệu kèm theo (AAD).
 *
 * Bố cục: ["OMB1":4][số vòng lặp:4][muối:16][nonce:12][bản mã + thẻ GCM].
 * Phần rõ (trước khi mã hóa): [số mục:4] rồi mỗi mục [độ dài tên:2][tên UTF-8][độ dài dữ liệu:4][dữ liệu].
 * Không có cách khôi phục mật khẩu: mất mật khẩu thì không mở được tệp.
 */
object BackupCodec {
    const val MAGIC = "OMB1"
    /** OWASP khuyến nghị ≥ 600.000 vòng cho PBKDF2-HMAC-SHA256. Giá trị được ghi vào tệp nên sau này tăng được. */
    const val DEFAULT_ITERATIONS = 600_000
    const val MAX_ITERATIONS = 10_000_000
    const val MIN_PASSWORD = 8
    const val MAX_PLAIN = 64 * 1024 * 1024
    const val MAX_ENTRIES = 100_000
    const val MAX_NAME = 128

    private const val SALT = 16
    private const val NONCE = 12
    private const val TAG_BITS = 128
    private const val HEADER = 4 + 4 + SALT + NONCE

    /** null nếu mật khẩu hợp lệ, ngược lại là lời nhắc cho người dùng. */
    fun checkPassword(pw: CharArray): String? =
        if (pw.size < MIN_PASSWORD) "Mật khẩu cần ít nhất $MIN_PASSWORD ký tự" else null

    private val SEGMENT = Regex("[A-Za-z0-9._-]+")

    /** Tên mục: các đoạn chữ/số/._- ngăn bằng "/"; không có đoạn "." hoặc ".." (chặn thoát khỏi thư mục khi khôi phục). */
    fun validName(n: String): Boolean {
        if (n.isEmpty() || n.length > MAX_NAME) return false
        return n.split('/').all { it.isNotEmpty() && it != "." && it != ".." && SEGMENT.matches(it) }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val normalized = Normalizer.normalize(String(password), Normalizer.Form.NFC).toCharArray()
        val spec = PBEKeySpec(normalized, salt, iterations, 256)
        try {
            val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(raw, "AES")
        } finally {
            spec.clearPassword()
            normalized.fill('\u0000')
        }
    }

    private fun serialize(entries: List<BackupEntry>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(ByteBuffer.allocate(4).putInt(entries.size).array())
        for (e in entries) {
            val nm = e.name.toByteArray(Charsets.UTF_8)
            out.write(ByteBuffer.allocate(2).putShort(nm.size.toShort()).array())
            out.write(nm)
            out.write(ByteBuffer.allocate(4).putInt(e.data.size).array())
            out.write(e.data)
        }
        return out.toByteArray()
    }

    private fun parse(b: ByteArray): List<BackupEntry>? {
        if (b.size < 4) return null
        val bb = ByteBuffer.wrap(b)
        val n = bb.int
        if (n < 0 || n > MAX_ENTRIES) return null
        val out = ArrayList<BackupEntry>()
        val seen = HashSet<String>()
        repeat(n) {
            if (bb.remaining() < 2) return null
            val nl = bb.short.toInt() and 0xFFFF
            if (nl == 0 || nl > MAX_NAME || bb.remaining() < nl + 4) return null
            val nm = ByteArray(nl).also { bb.get(it) }.toString(Charsets.UTF_8)
            val dl = bb.int
            if (dl < 0 || bb.remaining() < dl) return null
            val d = ByteArray(dl).also { bb.get(it) }
            if (!validName(nm) || !seen.add(nm)) return null
            out += BackupEntry(nm, d)
        }
        if (bb.hasRemaining()) return null
        return out
    }

    /** Mã hóa; ném IllegalArgumentException nếu tên mục sai, tên trùng, hoặc quá lớn. */
    fun encrypt(
        entries: List<BackupEntry>, password: CharArray,
        iterations: Int = DEFAULT_ITERATIONS, random: SecureRandom = SecureRandom(),
    ): ByteArray {
        require(iterations in 1..MAX_ITERATIONS) { "iterations" }
        require(entries.size <= MAX_ENTRIES) { "quá nhiều mục" }
        require(entries.all { validName(it.name) }) { "tên mục không hợp lệ" }
        require(entries.map { it.name }.toSet().size == entries.size) { "tên mục trùng" }
        require(entries.sumOf { it.data.size.toLong() } <= MAX_PLAIN) { "dữ liệu quá lớn" }
        val salt = ByteArray(SALT).also { random.nextBytes(it) }
        val nonce = ByteArray(NONCE).also { random.nextBytes(it) }
        val header = ByteBuffer.allocate(HEADER).put(MAGIC.toByteArray(Charsets.US_ASCII)).putInt(iterations).put(salt).put(nonce).array()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt, iterations), GCMParameterSpec(TAG_BITS, nonce))
        c.updateAAD(header)
        return header + c.doFinal(serialize(entries))
    }

    fun decrypt(data: ByteArray, password: CharArray): BackupResult {
        if (data.size < 4) return BackupResult.NotABackup
        val magic = String(data, 0, 4, Charsets.US_ASCII)
        if (magic != MAGIC) return if (magic.startsWith("OMB")) BackupResult.UnsupportedVersion else BackupResult.NotABackup
        if (data.size < HEADER + TAG_BITS / 8) return BackupResult.NotABackup
        if (data.size > HEADER + MAX_PLAIN + TAG_BITS / 8) return BackupResult.TooLarge
        val bb = ByteBuffer.wrap(data, 4, HEADER - 4)
        val iterations = bb.int
        if (iterations !in 1..MAX_ITERATIONS) return BackupResult.NotABackup
        val salt = ByteArray(SALT).also { bb.get(it) }
        val nonce = ByteArray(NONCE).also { bb.get(it) }
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, deriveKey(password, salt, iterations), GCMParameterSpec(TAG_BITS, nonce))
            c.updateAAD(data, 0, HEADER)
            val plain = c.doFinal(data, HEADER, data.size - HEADER)
            parse(plain)?.let { BackupResult.Ok(it) } ?: BackupResult.WrongPasswordOrDamaged
        } catch (e: java.security.GeneralSecurityException) {
            BackupResult.WrongPasswordOrDamaged
        }
    }
}
