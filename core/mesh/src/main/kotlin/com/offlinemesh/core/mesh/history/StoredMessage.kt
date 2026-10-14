package com.offlinemesh.core.mesh.history

import com.offlinemesh.core.mesh.payload.GeoPoint
import com.offlinemesh.core.mesh.payload.SosEntry
import com.offlinemesh.core.mesh.payload.SosPayload
import com.offlinemesh.core.mesh.payload.SosType
import com.offlinemesh.core.model.PeerId

/** Một tin trong lịch sử trên máy này. Giờ là giờ điện thoại lúc gửi/nhận (không quy đổi múi giờ). */
class StoredMessage(
    val id: String,               // BundleId hex
    val conv: String,             // "SOS", "PUBLIC" hoặc PeerId hex của liên hệ
    val outgoing: Boolean,
    val kind: String,             // TEXT | IMAGE | SOS
    val text: String,
    val image: String?,           // tên tệp ảnh trong thư mục ảnh của ứng dụng
    val tMs: Long,
    var state: Int = 0,           // tin gửi: 0 đang chờ, 1 đã chuyển đi, 2 đã nhận (ACK)
    val src: String = "",
    val hops: Int = 0,
    val ageSec: Int = 0,          // tuổi của tin lúc nhận
    val sosType: Int = -1,
    val session: Int = 0,
    val seq: Int = 0,
    val latE6: Int? = null,
    val lonE6: Int? = null,
    val accM: Int? = null,
    val fixAgeSec: Int = 0,
    var read: Boolean = false,
    val pinned: Boolean = false,     // khóa ký trong tin trùng khóa đã ghim trong danh bạ (đã xác minh người gửi)
) {
    /**
     * Chuyển thành mục SOS để gộp/hiển thị. Tin SOS do chính mình gửi không lưu `src`, nên truyền mã máy của mình vào `selfSrc`
     * để cũng hiện được trong danh sách (người gửi = mình).
     */
    fun toSosEntry(selfSrc: String = ""): SosEntry? {
        val t = SosType.fromCode(sosType) ?: return null
        val from = src.ifEmpty { selfSrc }
        if (kind != "SOS" || from.isEmpty()) return null
        val pos = if (latE6 != null && lonE6 != null) GeoPoint(latE6, lonE6, accM) else null
        return SosEntry(PeerId(from), tMs, SosPayload(t, pos, fixAgeSec, text, session, seq))
    }
}

/** Mỗi tin một dòng, các trường cách nhau bằng tab; \\, tab, xuống dòng được thoát. */
object MessageCodec {
    private fun esc(s: String) = buildString {
        for (c in s) when (c) {
            '\\' -> append("\\\\"); '\t' -> append("\\t"); '\n' -> append("\\n"); '\r' -> append("\\r")
            else -> append(c)
        }
    }

    private fun unesc(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) { '\\' -> sb.append('\\'); 't' -> sb.append('\t'); 'n' -> sb.append('\n'); 'r' -> sb.append('\r'); else -> sb.append(s[i + 1]) }
                i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    fun encode(m: StoredMessage): String = listOf(
        m.id, m.conv, if (m.outgoing) "1" else "0", m.kind, esc(m.text), m.image ?: "", m.tMs.toString(), m.state.toString(),
        m.src, m.hops.toString(), m.ageSec.toString(), m.sosType.toString(), m.session.toString(), m.seq.toString(),
        m.latE6?.toString() ?: "", m.lonE6?.toString() ?: "", m.accM?.toString() ?: "", m.fixAgeSec.toString(), if (m.read) "1" else "0", if (m.pinned) "1" else "0",
    ).joinToString("\t")

    fun decode(line: String): StoredMessage? {
        val f = line.split('\t')
        if (f.size != 20) return null
        return try {
            StoredMessage(
                id = f[0], conv = f[1], outgoing = f[2] == "1", kind = f[3], text = unesc(f[4]), image = f[5].ifEmpty { null },
                tMs = f[6].toLong(), state = f[7].toInt(), src = f[8], hops = f[9].toInt(), ageSec = f[10].toInt(),
                sosType = f[11].toInt(), session = f[12].toInt(), seq = f[13].toInt(),
                latE6 = f[14].toIntOrNull(), lonE6 = f[15].toIntOrNull(), accM = f[16].toIntOrNull(),
                fixAgeSec = f[17].toInt(), read = f[18] == "1", pinned = f[19] == "1",
            ).takeIf { it.id.isNotEmpty() && it.conv.isNotEmpty() && it.kind in setOf("TEXT", "IMAGE", "SOS") }
        } catch (e: NumberFormatException) { null }
    }
}

/** Danh sách tin trong bộ nhớ (lưu bền do tầng Android làm). Chỉ chứa logic, kiểm chứng trên JVM. */
class MessageLog {
    private val items = ArrayList<StoredMessage>()

    val size: Int get() = items.size

    fun load(lines: List<String>) {
        items.clear()
        for (l in lines) MessageCodec.decode(l)?.let { m -> if (items.none { it.id == m.id }) items += m }
    }

    fun encodeAll(): List<String> = items.map { MessageCodec.encode(it) }
    fun all(): List<StoredMessage> = items.toList()
    fun get(id: String): StoredMessage? = items.firstOrNull { it.id == id }

    /** false nếu id đã có (chống ghi trùng). */
    fun add(m: StoredMessage): Boolean {
        if (items.any { it.id == m.id }) return false
        items += m
        return true
    }

    /** Trạng thái gửi chỉ tăng (đang chờ → đã chuyển đi → đã nhận), không bao giờ lùi. */
    fun setState(id: String, state: Int): Boolean {
        val m = get(id) ?: return false
        if (state <= m.state) return false
        m.state = state
        return true
    }

    fun conversation(conv: String): List<StoredMessage> = items.filter { it.conv == conv }.sortedBy { it.tMs }
    fun unread(conv: String): Int = items.count { it.conv == conv && !it.outgoing && !it.read }
    fun lastTime(conv: String): Long = items.filter { it.conv == conv }.maxOfOrNull { it.tMs } ?: 0L

    fun markRead(conv: String): Boolean {
        var changed = false
        for (m in items) if (m.conv == conv && !m.outgoing && !m.read) { m.read = true; changed = true }
        return changed
    }

    fun sosEntries(): List<SosEntry> = items.filter { it.kind == "SOS" && !it.outgoing }.mapNotNull { it.toSosEntry() }

    /** Xóa tin cũ hơn cutoffMs, chỉ trong một cuộc trò chuyện nếu conv != null. Trả các tin đã xóa (để xóa tệp ảnh). */
    fun purgeOlderThan(cutoffMs: Long, conv: String? = null): List<StoredMessage> {
        val gone = items.filter { it.tMs < cutoffMs && (conv == null || it.conv == conv) }
        items.removeAll(gone.toSet())
        return gone
    }

    /** Các cuộc trò chuyện riêng (không phải SOS / Công khai) đang có tin, cuộc có tin mới nhất xếp trước. */
    fun privateConvs(): List<String> =
        items.filter { it.conv != "SOS" && it.conv != "PUBLIC" }
            .groupBy { it.conv }.entries
            .sortedByDescending { e -> e.value.maxOf { it.tMs } }
            .map { it.key }

    /**
     * Xóa mọi tin của một người (dùng khi chặn): cuộc trò chuyện riêng với họ và các tin công khai họ gửi.
     * KHÔNG đụng tới mục SOS: tin cầu cứu luôn được giữ. Trả các tin đã xóa (để xóa tệp ảnh).
     */
    fun removePerson(peerHex: String): List<StoredMessage> {
        val gone = items.filter { it.conv == peerHex || (it.conv == "PUBLIC" && it.src == peerHex) }
        items.removeAll(gone.toSet())
        return gone
    }

    fun deleteConversation(conv: String): List<StoredMessage> {
        val gone = items.filter { it.conv == conv }
        items.removeAll(gone.toSet())
        return gone
    }
}
