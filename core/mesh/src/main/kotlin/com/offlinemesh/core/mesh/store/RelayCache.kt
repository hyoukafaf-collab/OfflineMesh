package com.offlinemesh.core.mesh.store

import com.offlinemesh.core.common.Layer
import com.offlinemesh.core.common.Level
import com.offlinemesh.core.common.MeshLog
import com.offlinemesh.core.model.Kind
import com.offlinemesh.core.model.StoredBundle

private const val MB = 1024L * 1024L

/** Hạn mức riêng cho từng loại: một loạt ảnh không thể đẩy SOS ra khỏi cache. */
data class CacheConfig(
    val capBytes: Map<Kind, Long> = mapOf(
        Kind.ACK to 1 * MB, Kind.SOS to 5 * MB, Kind.TEXT to 20 * MB,
        Kind.IMAGE to 60 * MB, Kind.VIDEO to 14 * MB,
    ),
)

/**
 * Chỉ tính các bản relay (tin do chính mình tạo nằm ở "hộp đi", không bị đuổi và không tính hạn mức).
 * Khi đầy: đuổi bản còn ít thời gian sống nhất, nhưng chỉ đuổi bản có thời gian còn lại KHÔNG hơn tin mới.
 */
class RelayCache(
    private val store: BundleStore,
    private val cfg: CacheConfig,
    private val log: MeshLog,
) {
    /** commit=false chỉ kiểm tra có nhận được không, không đuổi gì. */
    fun admit(kind: Kind, size: Int, remainingMs: Long, nowMs: Long, commit: Boolean = true): Boolean {
        val cap = cfg.capBytes[kind] ?: return false
        if (size > cap) return false
        val relay = store.all().filter { it.core.kind == kind && !it.originated }
        val need = relay.sumOf { it.core.size.toLong() } + size - cap
        if (need <= 0) return true
        val victims = relay.filter { it.remainingMs(nowMs) <= remainingMs }
            .sortedWith(compareBy<StoredBundle> { it.remainingMs(nowMs) }.thenByDescending { it.core.size })
        if (victims.sumOf { it.core.size.toLong() } < need) return false
        if (!commit) return true
        var freed = 0L
        for (v in victims) {
            if (freed >= need) break
            store.remove(v.core.id)
            freed += v.core.size
            log.log(Level.INFO, Layer.CACHE, "evicted", bundle = v.core.id, detail = "remaining=${v.remainingMs(nowMs)}ms size=${v.core.size}")
        }
        return true
    }
}
