package com.offlinemesh.app.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.offlinemesh.core.mesh.backup.BackupContent
import com.offlinemesh.core.mesh.history.MessageLog
import com.offlinemesh.core.mesh.history.StoredMessage
import com.offlinemesh.core.mesh.payload.SosEntry
import java.io.File

/**
 * Lưu lịch sử tin trên máy này (tệp messages.tsv + thư mục images/). Logic nằm ở MessageLog (đã test trên JVM);
 * lớp này chỉ đọc/ghi tệp. `version` tăng mỗi khi có thay đổi để giao diện tự cập nhật.
 */
object MessageStore {
    private lateinit var file: File
    private lateinit var imgDir: File
    private val log = MessageLog()

    var version by mutableStateOf(0)
        private set

    @Synchronized
    fun init(ctx: Context) {
        file = File(ctx.filesDir, "messages.tsv")
        imgDir = File(ctx.filesDir, "images").apply { mkdirs() }
        log.load(if (file.exists()) file.readLines() else emptyList())
        version++
    }

    private fun rewrite() {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(log.encodeAll().joinToString("\n") + if (log.size > 0) "\n" else "")
        tmp.renameTo(file)
    }

    @Synchronized
    fun add(m: StoredMessage): Boolean {
        if (!log.add(m)) return false
        file.appendText(com.offlinemesh.core.mesh.history.MessageCodec.encode(m) + "\n")
        version++
        return true
    }

    @Synchronized fun setState(id: String, state: Int) { if (log.setState(id, state)) { rewrite(); version++ } }
    @Synchronized fun markRead(conv: String) { if (log.markRead(conv)) { rewrite(); version++ } }
    @Synchronized fun conversation(conv: String): List<StoredMessage> = log.conversation(conv)
    @Synchronized fun unread(conv: String): Int = log.unread(conv)
    @Synchronized fun sosEntries(): List<SosEntry> = log.sosEntries()
    @Synchronized fun all(): List<StoredMessage> = log.all()

    @Synchronized
    fun purgeOlderThan(cutoffMs: Long, conv: String? = null): Int {
        val gone = log.purgeOlderThan(cutoffMs, conv)
        gone.forEach { m -> m.image?.let { File(imgDir, it).delete() } }
        if (gone.isNotEmpty()) { rewrite(); version++ }
        return gone.size
    }

    /** Toàn bộ lịch sử dạng văn bản (cho bản sao lưu). */
    @Synchronized
    fun exportText(): ByteArray =
        (log.encodeAll().joinToString("\n") + if (log.size > 0) "\n" else "").toByteArray(Charsets.UTF_8)

    /** Các tệp ảnh hợp lệ (tên đúng dạng do ứng dụng tạo) để đưa vào bản sao lưu. */
    @Synchronized
    fun imageFiles(): List<File> =
        imgDir.listFiles()?.filter { it.isFile && BackupContent.imageFileOk(it.name) }.orEmpty()

    /**
     * Thay toàn bộ dữ liệu trên đĩa (khôi phục hoặc xóa tài khoản): xóa ảnh cũ, ghi ảnh mới, ghi tệp tin nhắn bằng tệp tạm.
     * Gọi `init(ctx)` sau đó để nạp lại vào bộ nhớ.
     */
    @Synchronized
    fun replaceFiles(messages: ByteArray, images: List<Pair<String, ByteArray>>) {
        imgDir.mkdirs()
        imgDir.listFiles()?.forEach { it.delete() }
        for ((name, bytes) in images) File(imgDir, name).writeBytes(bytes)
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(messages)
        if (!tmp.renameTo(file)) { file.writeBytes(messages); tmp.delete() }
    }

    fun saveImage(id: String, bytes: ByteArray): String {
        val name = "$id.jpg"
        File(imgDir, name).writeBytes(bytes)
        return name
    }

    fun imageFile(name: String): File = File(imgDir, name)
}
