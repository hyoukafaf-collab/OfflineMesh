package com.offlinemesh.core.mesh.backup

/** Một cài đặt (khóa + giá trị). Giá trị: String, Int, Long, Boolean, Float hoặc Set<String>. */
class PrefItem(val key: String, val value: Any) {
    val tag: Char get() = tagOf(value) ?: '?'

    companion object {
        fun tagOf(v: Any?): Char? = when (v) {
            is String -> 's'; is Int -> 'i'; is Long -> 'l'; is Boolean -> 'b'; is Float -> 'f'
            is Set<*> -> if (v.all { it is String }) 'S' else null
            else -> null
        }
    }
}

/**
 * Cài đặt (SharedPreferences) dạng văn bản để đưa vào bản sao lưu: mỗi dòng "loại<TAB>khóa<TAB>giá trị…".
 * Chỉ hỗ trợ kiểu cơ bản; kiểu lạ bị bỏ qua khi mã hóa và dòng sai bị từ chối khi giải mã.
 */
object PrefsText {
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

    fun encode(map: Map<String, Any?>): ByteArray {
        val lines = ArrayList<String>()
        for (k in map.keys.sorted()) {
            val v = map[k] ?: continue
            val t = PrefItem.tagOf(v) ?: continue
            val fields = ArrayList<String>()
            fields += t.toString(); fields += esc(k)
            if (v is Set<*>) v.map { it as String }.sorted().forEach { fields += esc(it) } else fields += esc(v.toString())
            lines += fields.joinToString("\t")
        }
        return lines.joinToString("\n").toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): List<PrefItem>? {
        val text = bytes.toString(Charsets.UTF_8)
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<PrefItem>()
        for (line in text.split('\n')) {
            val f = line.split('\t')
            if (f.size < 2 || f[0].length != 1) return null
            val key = unesc(f[1])
            if (key.isEmpty()) return null
            val v: Any = try {
                when (f[0][0]) {
                    's' -> { if (f.size != 3) return null; unesc(f[2]) }
                    'i' -> { if (f.size != 3) return null; f[2].toInt() }
                    'l' -> { if (f.size != 3) return null; f[2].toLong() }
                    'f' -> { if (f.size != 3) return null; f[2].toFloat() }
                    'b' -> { if (f.size != 3 || (f[2] != "true" && f[2] != "false")) return null; f[2] == "true" }
                    'S' -> f.drop(2).map { unesc(it) }.toSet()
                    else -> return null
                }
            } catch (e: NumberFormatException) { return null }
            out += PrefItem(key, v)
        }
        return out
    }
}

/** Những gì được đưa vào / nhận lại từ bản sao lưu. Chỉ nhận lại các khóa trong danh sách cho phép, đúng kiểu. */
object BackupContent {
    const val IDENTITY = "identity"
    const val CONTACTS = "prefs/contacts"
    const val SETTINGS = "prefs/settings"
    const val MESSAGES = "messages"
    const val IMAGE_DIR = "images/"

    /** Cài đặt giao diện được khôi phục. KHÔNG gồm: điều khoản (phải đồng ý lại), chia sẻ vị trí và chế độ khẩn cấp (luôn tắt khi cài mới). */
    val SETTINGS_KEYS: Map<String, Char> = mapOf(
        "theme" to 'i', "notif_msg" to 'b', "sound_msg" to 'b', "notif_sos" to 'b', "sound_sos" to 'b',
        "replay_min" to 'i', "retention_days" to 'i', "warn_sos_hidden" to 'b', "warn_public_hidden" to 'b',
        "contacts_only" to 'b',
    )
    val CONTACTS_KEYS: Map<String, Char> = mapOf("my_name" to 's', "cards" to 'S', "blocked" to 'S')

    fun sanitize(items: List<PrefItem>, allowed: Map<String, Char>): List<PrefItem> =
        items.filter { allowed[it.key] == it.tag }

    private val IMAGE_FILE = Regex("[0-9A-Fa-f]{1,64}\\.jpg")
    fun imageFileOk(file: String): Boolean = IMAGE_FILE.matches(file)
    fun imageEntryName(file: String): String = IMAGE_DIR + file
    fun imageFileOf(entryName: String): String? =
        entryName.removePrefix(IMAGE_DIR).takeIf { entryName.startsWith(IMAGE_DIR) && imageFileOk(it) }
}
