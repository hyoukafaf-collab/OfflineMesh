package com.offlinemesh.core.mesh.backup

import com.offlinemesh.core.mesh.crypto.ContactCard
import com.offlinemesh.core.mesh.crypto.Identity
import com.offlinemesh.core.mesh.history.MessageCodec
import com.offlinemesh.core.mesh.history.MessageLog

/** Kết quả đã kiểm tra và làm sạch, sẵn sàng để tầng Android ghi xuống đĩa. */
class RestorePlan(
    val identity: Identity,
    val contacts: List<PrefItem>,
    val settings: List<PrefItem>,
    val messages: ByteArray,
    val messageCount: Int,
    val images: List<Pair<String, ByteArray>>,
    val cardCount: Int,
)

sealed class PlanResult {
    class Ok(val plan: RestorePlan) : PlanResult()
    class Bad(val reason: String) : PlanResult()
}

/**
 * Biến các mục đã giải mã của bản sao lưu thành kế hoạch khôi phục. Mọi kiểm tra nằm ở đây (chạy được trên JVM, có test), còn tầng Android
 * chỉ ghi. Nguyên tắc: không tin nội dung tệp dù đã giải mã được. Danh tính phải hợp lệ; cài đặt chỉ nhận khóa trong danh sách cho phép
 * đúng kiểu; thẻ liên hệ hỏng bị bỏ; tin nhắn trỏ tới tên ảnh lạ (vd. "../x") bị bỏ; ảnh phải đúng tên, đúng cỡ và là JPEG.
 */
object RestorePlanner {
    const val MAX_IMAGE_BYTES = 2 * 1024 * 1024

    private fun looksLikeJpeg(b: ByteArray) = b.size > 4 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()

    fun plan(entries: List<BackupEntry>): PlanResult {
        val byName = entries.associateBy { it.name }
        val identity = byName[BackupContent.IDENTITY]?.data?.let { Identity.deserialize(it) }
            ?: return PlanResult.Bad("Bản sao lưu không có danh tính hợp lệ")
        val contactItems = PrefsText.decode(byName[BackupContent.CONTACTS]?.data ?: ByteArray(0))
            ?.let { BackupContent.sanitize(it, BackupContent.CONTACTS_KEYS) }
            ?: return PlanResult.Bad("Bản sao lưu bị hỏng (danh bạ)")
        val contacts = contactItems.map { item ->
            if (item.key != "cards") item
            else PrefItem("cards", (item.value as? Set<*>).orEmpty().filterIsInstance<String>().filter { ContactCard.decode(it) != null }.toSet())
        }
        val settings = PrefsText.decode(byName[BackupContent.SETTINGS]?.data ?: ByteArray(0))
            ?.let { BackupContent.sanitize(it, BackupContent.SETTINGS_KEYS) }
            ?: return PlanResult.Bad("Bản sao lưu bị hỏng (cài đặt)")

        val log = MessageLog()
        log.load(String(byName[BackupContent.MESSAGES]?.data ?: ByteArray(0), Charsets.UTF_8).lines().filter { it.isNotBlank() })
        val messages = log.all().filter { m -> m.image?.let { BackupContent.imageFileOk(it) } ?: true }
        val messageBytes = messages.joinToString("") { MessageCodec.encode(it) + "\n" }.toByteArray(Charsets.UTF_8)

        val images = entries.mapNotNull { e ->
            val name = BackupContent.imageFileOf(e.name) ?: return@mapNotNull null
            if (e.data.size > MAX_IMAGE_BYTES || !looksLikeJpeg(e.data)) null else name to e.data
        }
        val cardCount = (contacts.firstOrNull { it.key == "cards" }?.value as? Set<*>)?.size ?: 0
        return PlanResult.Ok(RestorePlan(identity, contacts, settings, messageBytes, messages.size, images, cardCount))
    }
}
