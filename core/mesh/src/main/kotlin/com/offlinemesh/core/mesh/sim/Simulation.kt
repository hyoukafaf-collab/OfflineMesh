package com.offlinemesh.core.mesh.sim

import com.offlinemesh.core.common.Clock
import com.offlinemesh.core.common.FakeClock
import com.offlinemesh.core.common.MeshLog
import com.offlinemesh.core.mesh.node.MeshNode
import com.offlinemesh.core.mesh.routing.Defaults
import com.offlinemesh.core.mesh.routing.KindDefaults
import com.offlinemesh.core.mesh.routing.DefaultPolicy
import com.offlinemesh.core.mesh.routing.ForwardingPolicy
import com.offlinemesh.core.mesh.store.CacheConfig
import com.offlinemesh.core.mesh.transport.FakeNetwork
import com.offlinemesh.core.model.BundleCore
import com.offlinemesh.core.model.BundleId
import com.offlinemesh.core.model.Kind
import com.offlinemesh.core.model.PeerId
import kotlin.math.ln
import kotlin.random.Random

/** Đồng hồ của một máy bị lệch so với đồng hồ chung (để kiểm tra tuổi tương đối). */
class OffsetClock(private val base: Clock, private val offsetMs: Long) : Clock {
    override fun nowMs(): Long = base.nowMs() + offsetMs
}

data class Contact(val atMs: Long, val a: Int, val b: Int, val durationMs: Long)

data class Injection(
    val atMs: Long, val from: Int, val to: Int?, val kind: Kind,
    val payloadBytes: Int, val copies: Int? = null, val lifetimeMs: Long? = null,
)

object Mobility {
    private fun duration(rng: Random, meanMs: Long): Long =
        (-ln(1.0 - rng.nextDouble()) * meanMs).toLong().coerceIn(5_000, 600_000)

    /** Mọi cặp đều có thể gặp nhau với tần suất như nhau. */
    fun uniform(n: Int, hours: Int, perNodePerHour: Double, seed: Int = 1, meanDurationMs: Long = 60_000): List<Contact> {
        val rng = Random(seed)
        val horizon = hours * 3_600_000L
        val total = (n * perNodePerHour * hours / 2).toInt()
        return List(total) {
            val a = rng.nextInt(n)
            var b = rng.nextInt(n - 1)
            if (b >= a) b++
            Contact(rng.nextLong(horizon), a, b, duration(rng, meanDurationMs))
        }.sortedBy { it.atMs }
    }

    /**
     * `clusters` cụm, mỗi cụm `perCluster` người chỉ gặp nhau trong cụm; thêm `mules` người đi lại giữa
     * các cụm. Tổng số nút = clusters * perCluster + mules (mule đánh số sau cùng).
     */
    fun communities(
        clusters: Int, perCluster: Int, mules: Int, hours: Int,
        intraPerNodePerHour: Double, mulePerHour: Double,
        visitMs: Long = 3_600_000L, seed: Int = 1, meanDurationMs: Long = 60_000,
    ): List<Contact> {
        val rng = Random(seed)
        val horizon = hours * 3_600_000L
        val out = ArrayList<Contact>()
        for (c in 0 until clusters) {
            val total = (perCluster * intraPerNodePerHour * hours / 2).toInt()
            repeat(total) {
                val a = rng.nextInt(perCluster)
                var b = rng.nextInt(perCluster - 1)
                if (b >= a) b++
                out += Contact(rng.nextLong(horizon), c * perCluster + a, c * perCluster + b, duration(rng, meanDurationMs))
            }
        }
        for (m in 0 until mules) {
            val mule = clusters * perCluster + m
            repeat((mulePerHour * hours).toInt()) {
                val t = rng.nextLong(horizon)
                val cluster = (((t / visitMs) + m) % clusters).toInt()
                out += Contact(t, mule, cluster * perCluster + rng.nextInt(perCluster), duration(rng, meanDurationMs))
            }
        }
        return out.sortedBy { it.atMs }
    }
}

data class SimConfig(
    val nodes: Int,
    val seed: Int = 1,
    val bandwidthBytesPerSec: Long = 20_000,
    val frameSize: Int = 244,
    val clockSkewMs: Long = 0,
    val cooldownMs: Long = 300_000,
    val cacheConfig: CacheConfig = CacheConfig(),
    val policy: () -> ForwardingPolicy = { DefaultPolicy() },
    val defaults: (Kind) -> KindDefaults = { Defaults.of(it) },
)

data class SimResult(
    val sent: Int,
    val delivered: Int,
    val medianLatencyMs: Long?,
    val transmissions: Int,
    val txMegabytes: Double,
    val p90LatencyMs: Long? = null,
    val dataTransfers: Int = 0,               // số lần chuyển tin dữ liệu (không tính ACK)
    val ackTransfers: Int = 0,
    val afterDeliveryTransfers: Int = 0,      // chuyển tin dữ liệu SAU khi tin đó đã tới đích (lãng phí)
    val avgRelayCopies: Double = 0.0,         // số bản sao đang nằm ở máy trung gian (trung bình theo cuộc gặp)
    val peakRelayCopies: Int = 0,
    val avgStaleCopies: Double = 0.0,         // trong đó bản của tin đã giao nhưng chưa được dọn
    val contacts: Int = 0,
    val congestedContacts: Int = 0,           // cuộc gặp hết ngân sách byte trước khi trao đổi xong
) {
    val ratio: Double get() = if (sent == 0) 0.0 else delivered.toDouble() / sent
}

/** Chạy N nút trên mạng giả theo một bản ghi các cuộc gặp, đo tỉ lệ giao, độ trễ, chi phí. */
class Simulation(private val cfg: SimConfig) {
    val clock = FakeClock(0)
    private val net = FakeNetwork(cfg.seed)
    private val rng = Random(cfg.seed)
    val ids: List<PeerId> = List(cfg.nodes) { PeerId("%016x".format(it + 1L)) }
    val firstDelivery = HashMap<BundleId, Long>()
    val sentAt = HashMap<BundleId, Long>()
    val privateIds = ArrayList<BundleId>()
    val broadcastReceivers = HashMap<BundleId, MutableSet<Int>>()
    val maxHolders = HashMap<BundleId, Int>()
    var trackHolders = false
    private var dataTransfers = 0
    private var ackTransfers = 0
    private var afterDelivery = 0
    private var contacts = 0
    private var samples = 0
    private var relaySum = 0L
    private var staleSum = 0L
    private var relayPeak = 0

    val nodes: List<MeshNode> = List(cfg.nodes) { i ->
        val skew = if (cfg.clockSkewMs == 0L) 0L else rng.nextLong(-cfg.clockSkewMs, cfg.clockSkewMs + 1)
        MeshNode(
            ids[i], net.add(ids[i], cfg.frameSize), OffsetClock(clock, skew),
            MeshLog(clock, emptyList()), Random(cfg.seed * 1000 + i),
            policy = cfg.policy(), cacheConfig = cfg.cacheConfig, cooldownMs = cfg.cooldownMs,
            defaultsFor = cfg.defaults,
        ).also { node ->
            node.onBundleSent = { core, _ -> recordTransfer(core) }
            node.onReceived = { sb ->
                val c = sb.core
                if (c.dst == null) broadcastReceivers.getOrPut(c.id) { HashSet() }.add(i)
                else if (c.dst == ids[i]) firstDelivery.putIfAbsent(c.id, clock.nowMs())
            }
            node.start()
        }
    }

    fun inject(inj: Injection): BundleId {
        val id = nodes[inj.from].send(
            inj.to?.let { ids[it] }, inj.kind, ByteArray(inj.payloadBytes) { it.toByte() }, inj.copies, inj.lifetimeMs,
        )
        sentAt[id] = clock.nowMs()
        if (inj.to != null) privateIds += id
        return id
    }

    fun meet(c: Contact) {
        val a = ids[c.a]
        val b = ids[c.b]
        net.connect(a, b, cfg.bandwidthBytesPerSec * c.durationMs / 1000)
        net.runAll()
        net.disconnect(a, b)
        net.runAll()
        contacts++
        nodes.forEach { it.tick() }                 // máy thật cũng dọn định kỳ; tránh đếm bản sao đã hết hạn
        sampleNetwork()
        if (trackHolders) sampleHolders()
    }

    private fun recordTransfer(c: BundleCore) {
        if (c.kind == Kind.ACK) { ackTransfers++; return }
        dataTransfers++
        val d = firstDelivery[c.id]
        if (c.dst != null && d != null && clock.nowMs() > d) afterDelivery++
    }

    private fun sampleNetwork() {
        var relay = 0
        var stale = 0
        for (n in nodes) for (b in n.stored()) {
            if (b.core.kind == Kind.ACK || b.originated) continue
            relay++
            if (b.core.id in firstDelivery) stale++
        }
        samples++
        relaySum += relay
        staleSum += stale
        if (relay > relayPeak) relayPeak = relay
    }

    fun run(contacts: List<Contact>, injections: List<Injection>) {
        data class Ev(val at: Long, val order: Int, val contact: Contact?, val inj: Injection?)
        val events = (injections.map { Ev(it.atMs, 0, null, it) } + contacts.map { Ev(it.atMs, 1, it, null) })
            .sortedWith(compareBy({ it.at }, { it.order }))
        for (e in events) {
            clock.set(e.at)
            e.inj?.let { inject(it) }
            e.contact?.let { meet(it) }
        }
    }

    private fun sampleHolders() {
        for (id in privateIds) {
            val n = nodes.count { node -> node.stored().any { it.core.id == id } }
            if (n > (maxHolders[id] ?: 0)) maxHolders[id] = n
        }
    }

    fun result(): SimResult {
        val delivered = privateIds.count { it in firstDelivery }
        val lat = privateIds.mapNotNull { id -> firstDelivery[id]?.let { it - sentAt.getValue(id) } }.sorted()
        return SimResult(
            privateIds.size, delivered, lat.getOrNull(lat.size / 2),
            nodes.sumOf { it.stats.bundlesSent }, nodes.sumOf { it.stats.bytesSent } / 1_048_576.0,
            p90LatencyMs = lat.getOrNull(((lat.size - 1) * 0.9).toInt()),
            dataTransfers = dataTransfers, ackTransfers = ackTransfers, afterDeliveryTransfers = afterDelivery,
            avgRelayCopies = if (samples == 0) 0.0 else relaySum.toDouble() / samples,
            peakRelayCopies = relayPeak,
            avgStaleCopies = if (samples == 0) 0.0 else staleSum.toDouble() / samples,
            contacts = contacts, congestedContacts = net.budgetCuts,
        )
    }
}
