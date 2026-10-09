package com.offlinemesh.core.mesh.crypto

import com.offlinemesh.core.model.PeerId

sealed interface AddResult {
    data object Added : AddResult
    data object AlreadyKnown : AddResult
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

    /** Tin có đúng là từ liên hệ đã ghim không: khóa ký trong tin phải TRÙNG khóa đã ghim, không chỉ trùng PeerId. */
    fun isPinned(peer: PeerId, signPub: ByteArray): Boolean =
        byPeer(peer)?.signPub?.contentEquals(signPub) == true
}

class InMemoryContactBook : ContactBook {
    private val map = LinkedHashMap<PeerId, ContactCard>()
    override fun byPeer(peer: PeerId) = map[peer]
    override fun all() = map.values.toList()
    override fun remove(peer: PeerId) { map.remove(peer) }
    override fun add(card: ContactCard, self: PeerId): AddResult {
        val id = card.peerId
        if (id == self) return AddResult.IsSelf
        val old = map[id]
        if (old != null) return if (old.sameKeysAs(card)) AddResult.AlreadyKnown else AddResult.Conflict
        map[id] = card
        return AddResult.Added
    }
}
