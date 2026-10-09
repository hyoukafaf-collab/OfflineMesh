package com.offlinemesh.core.mesh.store

import com.offlinemesh.core.model.BundleId
import com.offlinemesh.core.model.StoredBundle

/** Nơi lưu tin + tập "đã thấy" + tập "đã được ACK". Bản Room sẽ cài interface này sau. */
interface BundleStore {
    /** Tăng mỗi khi tập tin thay đổi (put/remove); dùng để biết có gì mới cần trao đổi. */
    val version: Int

    fun get(id: BundleId): StoredBundle?
    fun put(b: StoredBundle)
    fun remove(id: BundleId)
    fun all(): List<StoredBundle>

    fun markSeen(id: BundleId, untilMs: Long)
    fun isSeen(id: BundleId): Boolean
    fun seenIds(limit: Int): List<BundleId>

    fun markAcked(id: BundleId, untilMs: Long)
    fun isAcked(id: BundleId): Boolean

    /** Xóa các mục seen/acked đã hết hạn. */
    fun purgeSeen(nowMs: Long)
}

class InMemoryBundleStore : BundleStore {
    private val bundles = LinkedHashMap<BundleId, StoredBundle>()
    private val seen = LinkedHashMap<BundleId, Long>()
    private val acked = HashMap<BundleId, Long>()
    private var v = 0

    override val version: Int get() = v

    override fun get(id: BundleId) = bundles[id]
    override fun put(b: StoredBundle) { bundles[b.core.id] = b; v++ }
    override fun remove(id: BundleId) { if (bundles.remove(id) != null) v++ }
    override fun all(): List<StoredBundle> = bundles.values.toList()

    override fun markSeen(id: BundleId, untilMs: Long) { seen[id] = untilMs }
    override fun isSeen(id: BundleId) = seen.containsKey(id)
    override fun seenIds(limit: Int): List<BundleId> = seen.keys.toList().takeLast(limit)

    override fun markAcked(id: BundleId, untilMs: Long) { acked[id] = untilMs }
    override fun isAcked(id: BundleId) = acked.containsKey(id)

    override fun purgeSeen(nowMs: Long) {
        seen.entries.removeIf { it.value <= nowMs }
        acked.entries.removeIf { it.value <= nowMs }
    }
}
