package com.offlinemesh.core.mesh.link

import com.offlinemesh.core.common.Layer
import com.offlinemesh.core.common.Level
import com.offlinemesh.core.common.MeshLog
import com.offlinemesh.core.model.ErrorCode
import com.offlinemesh.core.model.PeerId

enum class LinkState { IDLE, HANDSHAKE, EXCHANGE, TRANSFER, DONE, FAILED }

/** Một lần gặp một peer. Mọi chuyển trạng thái đều qua to() nên luôn có log kèm lý do. */
class LinkSession(val id: String, val peer: PeerId, private val log: MeshLog) {
    var state: LinkState = LinkState.IDLE
        private set

    /** Chuyển sai luật thì không đổi trạng thái, ghi log lỗi và trả false. */
    fun to(next: LinkState, reason: String, error: ErrorCode? = null): Boolean {
        if (next !in ALLOWED.getValue(state)) {
            log.log(
                Level.ERROR, Layer.LINK, "illegal_transition", peer = peer, session = id,
                error = ErrorCode.ILLEGAL_TRANSITION, detail = "$state->$next ($reason)",
            )
            return false
        }
        val level = if (next == LinkState.FAILED) Level.WARN else Level.INFO
        log.log(
            level, Layer.LINK, "state", peer = peer, session = id,
            error = error, detail = "$state->$next: $reason",
        )
        state = next
        return true
    }

    private companion object {
        val ALLOWED: Map<LinkState, Set<LinkState>> = mapOf(
            LinkState.IDLE to setOf(LinkState.HANDSHAKE, LinkState.FAILED),
            LinkState.HANDSHAKE to setOf(LinkState.EXCHANGE, LinkState.FAILED),
            LinkState.EXCHANGE to setOf(LinkState.TRANSFER, LinkState.DONE, LinkState.FAILED),
            LinkState.TRANSFER to setOf(LinkState.DONE, LinkState.FAILED),
            LinkState.DONE to emptySet(),
            LinkState.FAILED to emptySet(),
        )
    }
}
