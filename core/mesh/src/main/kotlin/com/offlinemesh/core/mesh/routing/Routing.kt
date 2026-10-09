package com.offlinemesh.core.mesh.routing

import com.offlinemesh.core.model.Kind
import com.offlinemesh.core.model.PeerId
import com.offlinemesh.core.model.StoredBundle

/** Số bản sao (token Spray and Wait) trao cho người nhận. */
data class Give(val copies: Int)

/** Chính sách chuyển tiếp: có nên đưa bản này cho peer (peer chưa có) không, và kèm bao nhiêu token. */
interface ForwardingPolicy {
    fun decide(b: StoredBundle, peer: PeerId, nowMs: Long): Give?
}

/**
 * - Tin broadcast (SOS, ACK, thông báo): lan có kiểm soát, giới hạn hop.
 * - Tin riêng: Spray and Wait nhị phân; gặp đúng người nhận thì luôn trao tận tay;
 *   hop cuối chỉ dành cho người nhận (người khác nhận được cũng không chuyển tiếp thêm được).
 */
class DefaultPolicy : ForwardingPolicy {
    override fun decide(b: StoredBundle, peer: PeerId, nowMs: Long): Give? {
        if (b.isExpired(nowMs)) return null
        val c = b.core
        val nextHops = b.hops + 1
        if (c.dst == null) return if (nextHops <= c.maxHops) Give(1) else null
        if (peer == c.dst) return if (nextHops <= c.maxHops) Give(1) else null
        if (nextHops >= c.maxHops) return null
        if (b.copies <= 1) return null
        return Give(b.copies / 2)
    }
}

/** Mốc so sánh: tin riêng cũng lan tràn tới mọi peer (giao nhiều nhất, tốn nhất). */
class EpidemicPolicy : ForwardingPolicy {
    override fun decide(b: StoredBundle, peer: PeerId, nowMs: Long): Give? {
        if (b.isExpired(nowMs)) return null
        return if (b.hops + 1 <= b.core.maxHops) Give(1) else null
    }
}

/** Mốc so sánh: tin riêng chỉ trao tận tay người nhận; broadcast vẫn lan như mặc định. */
class DirectOnlyPolicy : ForwardingPolicy {
    private val base = DefaultPolicy()
    override fun decide(b: StoredBundle, peer: PeerId, nowMs: Long): Give? {
        val dst = b.core.dst ?: return base.decide(b, peer, nowMs)
        return if (peer == dst) base.decide(b, peer, nowMs) else null
    }
}

data class KindDefaults(val lifetimeMs: Long, val maxHops: Int, val copies: Int)

/** Giá trị mặc định ban đầu; chỉnh bằng mô phỏng. */
object Defaults {
    private const val H = 3_600_000L
    const val MIN_ACK_LIFETIME_MS = H

    fun of(kind: Kind): KindDefaults = when (kind) {
        Kind.SOS -> KindDefaults(72 * H, 12, 1)
        Kind.TEXT -> KindDefaults(48 * H, 12, 8)
        Kind.IMAGE -> KindDefaults(48 * H, 12, 4)
        Kind.VIDEO -> KindDefaults(24 * H, 1, 1)   // chỉ trao tận tay
        Kind.ACK -> KindDefaults(48 * H, 12, 1)    // thực tế đặt theo hạn còn lại của tin gốc
    }
}
