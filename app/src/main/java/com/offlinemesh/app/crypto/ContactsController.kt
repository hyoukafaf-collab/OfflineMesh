package com.offlinemesh.app.crypto

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.offlinemesh.core.mesh.crypto.AddResult
import com.offlinemesh.core.mesh.crypto.ContactBook
import com.offlinemesh.core.mesh.crypto.ContactCard
import com.offlinemesh.core.mesh.crypto.DisplayName
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

    override fun rename(peer: PeerId, newName: String): Boolean {
        val old = map[peer] ?: return false
        val n = DisplayName.clean(newName)
        if (n.isEmpty()) return false
        map[peer] = ContactCard(n, old.signPub, old.encPub)
        save()
        return true
    }

    override fun add(card: ContactCard, self: PeerId): AddResult {
        val id = card.peerId
        if (id == self) return AddResult.IsSelf
        val old = map[id]
        if (old != null) {
            return when {
                !old.sameKeysAs(card) -> AddResult.Conflict
                old.name == card.name -> AddResult.AlreadyKnown
                else -> AddResult.NameDiffers(old.name, card.name)
            }
        }
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

/** Thẻ vừa quét mang tên khác với tên đang lưu (cùng khóa): chờ người dùng quyết định. */
data class PendingRename(val peer: String, val oldName: String, val newName: String)

data class ContactsUiState(
    val myName: String = "",
    val myCard: String = "",
    val myFingerprint: String = "",
    val contacts: List<ContactUi> = emptyList(),
    val message: String? = null,
    val pendingRename: PendingRename? = null,
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

    private fun refresh(message: String?, pending: PendingRename? = null) {
        val card = ContactCard(myName(), identity.signPub, identity.encPub)
        synchronized(this) {
            state = ContactsUiState(
                myName = myName(), myCard = card.encode(), myFingerprint = card.fingerprint(),
                contacts = book.all().map { ContactUi(it.peerId.hex, it.name, it.fingerprint()) },
                message = message, pendingRename = pending,
            )
        }
        listener?.invoke(state)
    }

    fun setMyName(name: String) { prefs.edit().putString("my_name", name.trim().take(24)).apply(); refresh(null) }

    fun addFromText(text: String) {
        val card = ContactCard.decode(text)
        if (card == null) { refresh("Thẻ không hợp lệ (sai định dạng, sai mã kiểm tra hoặc khóa hỏng)"); return }
        when (val r = book.add(card, identity.peerId)) {
            AddResult.Added -> {
                val dup = if (book.nameTaken(card.name, except = card.peerId)) " Đã có liên hệ khác cùng tên: hãy dựa vào mã vân tay/ID để phân biệt." else ""
                refresh("Đã thêm ${card.name}. Hãy đọc mã vân tay cho nhau để chắc chắn: ${card.fingerprint()}.$dup")
            }
            AddResult.AlreadyKnown -> refresh("${card.name} đã có trong danh bạ")
            is AddResult.NameDiffers -> refresh(
                "Liên hệ này đã có (cùng khóa) với tên \"${r.oldName}\", còn thẻ vừa quét mang tên \"${r.newName}\". Khóa không đổi; tên chỉ là nhãn hiển thị.",
                PendingRename(card.peerId.hex, r.oldName, r.newName),
            )
            AddResult.Conflict -> refresh("CẢNH BÁO: đã có liên hệ cùng ID nhưng khóa khác. Không ghi đè.")
            AddResult.IsSelf -> refresh("Đây là thẻ của chính bạn")
        }
    }

    /** Người dùng xác nhận đổi nhãn theo thẻ vừa quét. Chỉ đổi tên, khóa đã ghim giữ nguyên. */
    fun confirmRename() {
        val p = state.pendingRename ?: return
        val ok = book.rename(PeerId(p.peer), p.newName)
        refresh(if (ok) "Đã đổi tên \"${p.oldName}\" thành \"${p.newName}\"" else "Không đổi được tên")
    }

    fun cancelRename() = refresh("Giữ nguyên tên \"${state.pendingRename?.oldName ?: ""}\"")

    fun remove(peer: String) { book.remove(PeerId(peer)); refresh("Đã xóa liên hệ") }

    fun setMessage(msg: String) = refresh(msg)
}
