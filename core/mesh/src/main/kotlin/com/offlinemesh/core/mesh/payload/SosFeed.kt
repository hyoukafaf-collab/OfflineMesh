package com.offlinemesh.core.mesh.payload

import com.offlinemesh.core.model.PeerId

class SosEntry(val src: PeerId, val atMs: Long, val payload: SosPayload) {
    val session: Int get() = payload.sessionId
    val seq: Int get() = payload.seq
}

/** Gộp các bản phát lại của cùng một SOS thành một mục, để người nhận không thấy trùng. */
object SosFeed {
    class Item(val latest: SosEntry, val resolved: Boolean)

    /** Chưa từng thấy (người gửi, session) này: chỉ lần đầu mới thông báo/phát âm, các bản cập nhật sau im lặng. */
    fun isNewSession(existing: List<SosEntry>, e: SosEntry): Boolean =
        existing.none { it.src == e.src && it.session == e.session }

    /**
     * Mỗi (người gửi, session) giữ bản seq cao nhất (hòa thì bản đến sau). Mục "đã giải quyết" nếu bản mới nhất là
     * "Tôi an toàn" hoặc người gửi đã gửi "Tôi an toàn" sau đó. Chưa giải quyết xếp trước, mới nhất trước.
     */
    fun collapse(all: List<SosEntry>): List<Item> {
        val best = LinkedHashMap<Pair<PeerId, Int>, SosEntry>()
        for (e in all) {
            val k = e.src to e.session
            val cur = best[k]
            if (cur == null || e.seq > cur.seq || (e.seq == cur.seq && e.atMs > cur.atMs)) best[k] = e
        }
        val lastSafe = HashMap<PeerId, Long>()
        for (e in all) if (e.payload.type == SosType.SAFE) lastSafe[e.src] = maxOf(lastSafe[e.src] ?: Long.MIN_VALUE, e.atMs)
        val items = best.values.map { e ->
            Item(e, e.payload.type == SosType.SAFE || (lastSafe[e.src]?.let { it >= e.atMs } ?: false))
        }
        return items.sortedWith(compareBy<Item> { it.resolved }.thenByDescending { it.latest.atMs })
    }
}
