package com.offlinemesh.core.mesh.transport

import com.offlinemesh.core.model.PeerId
import kotlin.random.Random

/**
 * Mạng giả trong bộ nhớ, xác định (deterministic): sự kiện được xếp hàng và chỉ chạy khi gọi
 * step()/runAll(), mất gói dùng Random có seed. Gọi start() của các nút trước connect()/runAll().
 *
 * Mỗi liên kết có thể có ngân sách byte (mô phỏng cuộc gặp ngắn): hết ngân sách thì liên kết đứt,
 * các khung đến sau đó bị mất.
 */
class FakeNetwork(seed: Int = 1, var lossRate: Double = 0.0) {
    private val rng = Random(seed)
    private val nodes = LinkedHashMap<PeerId, FakeTransport>()
    private val links = HashMap<Set<PeerId>, Long>()
    private val queue = ArrayDeque<() -> Unit>()

    /** Số liên kết bị cắt vì hết ngân sách byte (cuộc gặp quá ngắn so với lượng dữ liệu cần trao đổi). */
    var budgetCuts = 0
        private set

    fun add(id: PeerId, maxFrame: Int = 244): FakeTransport =
        FakeTransport(id, maxFrame, this).also { nodes[id] = it }

    fun connect(a: PeerId, b: PeerId, budgetBytes: Long = Long.MAX_VALUE) {
        if (links.containsKey(setOf(a, b))) return
        links[setOf(a, b)] = budgetBytes
        queue.addLast { nodes[a]?.peerFound(b) }
        queue.addLast { nodes[b]?.peerFound(a) }
    }

    fun disconnect(a: PeerId, b: PeerId) {
        if (links.remove(setOf(a, b)) == null) return
        queue.addLast { nodes[a]?.peerLost(b) }
        queue.addLast { nodes[b]?.peerLost(a) }
    }

    fun inRange(a: PeerId, b: PeerId): Boolean = links.containsKey(setOf(a, b))

    internal fun enqueueDelivery(from: PeerId, to: PeerId, data: ByteArray) {
        queue.addLast {
            val key = setOf(from, to)
            val left = links[key]
            if (left != null) {
                if (left < data.size) {
                    budgetCuts++
                    disconnect(from, to)
                } else {
                    if (left != Long.MAX_VALUE) links[key] = left - data.size
                    if (rng.nextDouble() >= lossRate) nodes[to]?.receive(from, data)
                }
            }
        }
    }

    /** Chạy một sự kiện trong hàng đợi. false nếu hàng đợi trống. */
    fun step(): Boolean {
        val job = queue.removeFirstOrNull() ?: return false
        job()
        return true
    }

    fun runAll(limit: Int = 5_000_000) {
        var n = 0
        while (n++ < limit && step()) { /* chạy tới khi hết sự kiện */ }
    }
}

class FakeTransport internal constructor(
    override val selfId: PeerId,
    override val maxFrame: Int,
    private val net: FakeNetwork,
) : Transport {
    private var listener: TransportListener? = null

    override fun start(listener: TransportListener) { this.listener = listener }
    override fun stop() { listener = null }

    override fun send(to: PeerId, data: ByteArray): Boolean {
        if (listener == null || data.size > maxFrame || !net.inRange(selfId, to)) return false
        net.enqueueDelivery(selfId, to, data.copyOf())
        return true
    }

    internal fun peerFound(p: PeerId) { listener?.onPeerFound(p) }
    internal fun peerLost(p: PeerId) { listener?.onPeerLost(p) }
    internal fun receive(from: PeerId, data: ByteArray) { listener?.onData(from, data) }
}
