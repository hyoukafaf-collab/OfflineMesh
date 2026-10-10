package com.offlinemesh.core.mesh.crypto

import com.offlinemesh.core.model.PeerId

sealed interface AddResult {
    data object Added : AddResult
    data object AlreadyKnown : AddResult

    /** Cùng khóa nhưng thẻ mang tên khác. KHÔNG tự đổi: tên chỉ là nhãn, người dùng phải xác nhận. */
    data class NameDiffers(val oldName: String, val newName: String) : AddResult
    /** Cùng PeerId nhưng khóa khác: hoặc va chạm 48 bit, hoặc giả mạo. Không ghi đè. */
    data object Conflict : AddResult
    data object IsSelf : AddResult
}

/** Danh bạ đã xác minh (ghim khóa đầy đủ). Bản Android lưu bền vào SharedPreferences. */
interface ContactBook {
    fun byPeer(peer: PeerId): ContactCard?
    fun all(): List<ContactCard>
    fun add(card: ContactCard, self: PeerId): AddResult
    fun remove(peer: PeerId)

    /** Đổi nhãn hiển thị của một liên hệ. KHÔNG bao giờ đổi khóa. false nếu không có liên hệ hoặc tên rỗng sau khi làm sạch. */
    fun rename(peer: PeerId, newName: String): Boolean

    /** Có liên hệ khác đang dùng đúng tên này không (dễ gây nhầm lẫn). */
    fun nameTaken(name: String, except: PeerId? = null): Boolean = all().any { it.name == name && it.peerId != except }

    /** Tin có đúng là từ liên hệ đã ghim không: khóa ký trong tin phải TRÙNG khóa đã ghim, không chỉ trùng PeerId. */
    fun isPinned(peer: PeerId, signPub: ByteArray): Boolean =
        byPeer(peer)?.signPub?.contentEquals(signPub) == true
}

class InMemoryContactBook : ContactBook {
    private val map = LinkedHashMap<PeerId, ContactCard>()
    override fun byPeer(peer: PeerId) = map[peer]
    override fun all() = map.values.toList()
    override fun remove(peer: PeerId) { map.remove(peer) }
    override fun rename(peer: PeerId, newName: String): Boolean {
        val old = map[peer] ?: return false
        val n = DisplayName.clean(newName)
        if (n.isEmpty()) return false
        map[peer] = ContactCard(n, old.signPub, old.encPub)
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
        return AddResult.Added
    }
}
