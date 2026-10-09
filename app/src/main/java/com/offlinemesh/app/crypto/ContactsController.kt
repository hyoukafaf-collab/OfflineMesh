package com.offlinemesh.app.crypto

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.offlinemesh.core.mesh.crypto.AddResult
import com.offlinemesh.core.mesh.crypto.ContactBook
import com.offlinemesh.core.mesh.crypto.ContactCard
import com.offlinemesh.core.mesh.crypto.Identity
import com.offlinemesh.core.model.PeerId
import java.io.File

/** Danh bạ lưu bền (SharedPreferences): mỗi thẻ lưu dạng chuỗi OM1:..., khóa đầy đủ được ghim. */
class PrefsContactBook(private val prefs: SharedPreferences) : ContactBook {
    private val map = LinkedHashMap<PeerId, ContactCard>()

    init {
        prefs.getStringSet("cards", emptySet())!!.mapNotNull { ContactCard.decode(it) }.forEach { map[it.peerId] = it }
    }

    private fun save() = prefs.edit().putStringSet("cards", map.values.map { it.encode() }.toSet()).apply()

    override fun byPeer(peer: PeerId) = map[peer]
    override fun all() = map.values.toList()

    override fun remove(peer: PeerId) { map.remove(peer); save() }

    override fun add(card: ContactCard, self: PeerId): AddResult {
        val id = card.peerId
        if (id == self) return AddResult.IsSelf
        val old = map[id]
        if (old != null) return if (old.sameKeysAs(card)) AddResult.AlreadyKnown else AddResult.Conflict
        map[id] = card
        save()
        return AddResult.Added
    }
}

/** Đọc/xác minh khóa riêng: tệp identity.bin chỉ chứa bản đã bọc bằng Keystore. */
object IdentityStore {
    @Synchronized
    fun get(ctx: Context): Identity {
        val f = File(ctx.filesDir, "identity.bin")
        if (f.exists()) {
            val loaded = runCatching { Identity.deserialize(AndroidSecretStore.unwrap(f.readBytes())) }.getOrNull()
            if (loaded != null) return loaded
        }
        val fresh = Identity.generate()
        f.writeBytes(AndroidSecretStore.wrap(fresh.serialize()))
        return fresh
    }
}

data class ContactUi(val peer: String, val name: String, val fingerprint: String)

data class ContactsUiState(
    val myName: String = "",
    val myCard: String = "",
    val myFingerprint: String = "",
    val contacts: List<ContactUi> = emptyList(),
    val message: String? = null,
)

/** Ranh giới giữa giao diện Liên hệ và lõi mật mã. Gọi init() một lần ở Application. */
object ContactsController {
    lateinit var identity: Identity
        private set
    lateinit var book: ContactBook
        private set
    private lateinit var prefs: SharedPreferences

    @Volatile
    var state = ContactsUiState()
        private set

    @Volatile
    var listener: ((ContactsUiState) -> Unit)? = null

    fun init(ctx: Context) {
        val app = ctx.applicationContext
        identity = IdentityStore.get(app)
        prefs = app.getSharedPreferences("contacts", Context.MODE_PRIVATE)
        book = PrefsContactBook(prefs)
        refresh(null)
    }

    private fun myName(): String = prefs.getString("my_name", null) ?: Build.MODEL

    private fun refresh(message: String?) {
        val card = ContactCard(myName(), identity.signPub, identity.encPub)
        synchronized(this) {
            state = ContactsUiState(
                myName = myName(), myCard = card.encode(), myFingerprint = card.fingerprint(),
                contacts = book.all().map { ContactUi(it.peerId.hex, it.name, it.fingerprint()) },
                message = message,
            )
        }
        listener?.invoke(state)
    }

    fun setMyName(name: String) { prefs.edit().putString("my_name", name.trim().take(24)).apply(); refresh(null) }

    fun addFromText(text: String) {
        val card = ContactCard.decode(text)
        if (card == null) { refresh("Thẻ không hợp lệ (sai định dạng, sai mã kiểm tra hoặc khóa hỏng)"); return }
        refresh(
            when (book.add(card, identity.peerId)) {
                AddResult.Added -> "Đã thêm ${card.name}. Hãy đọc mã vân tay cho nhau để chắc chắn: ${card.fingerprint()}"
                AddResult.AlreadyKnown -> "${card.name} đã có trong danh bạ"
                AddResult.Conflict -> "CẢNH BÁO: đã có liên hệ cùng ID nhưng khóa khác. Không ghi đè."
                AddResult.IsSelf -> "Đây là thẻ của chính bạn"
            },
        )
    }

    fun remove(peer: String) { book.remove(PeerId(peer)); refresh("Đã xóa liên hệ") }

    fun setMessage(msg: String) = refresh(msg)
}
