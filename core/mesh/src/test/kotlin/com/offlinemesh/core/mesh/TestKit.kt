package com.offlinemesh.core.mesh

import com.offlinemesh.core.common.FakeClock
import com.offlinemesh.core.common.MeshLog
import com.offlinemesh.core.common.RingBufferSink
import com.offlinemesh.core.mesh.codec.BundleCodec
import com.offlinemesh.core.mesh.node.MeshNode
import com.offlinemesh.core.mesh.node.NoCipher
import com.offlinemesh.core.mesh.node.NoCrypto
import com.offlinemesh.core.mesh.node.PayloadCipher
import com.offlinemesh.core.mesh.node.Signer
import com.offlinemesh.core.mesh.node.Verifier
import com.offlinemesh.core.mesh.routing.DefaultPolicy
import com.offlinemesh.core.mesh.routing.Defaults
import com.offlinemesh.core.mesh.routing.ForwardingPolicy
import com.offlinemesh.core.mesh.routing.KindDefaults
import com.offlinemesh.core.mesh.sim.OffsetClock
import com.offlinemesh.core.mesh.transport.FakeNetwork
import com.offlinemesh.core.model.BundleCore
import com.offlinemesh.core.model.BundleId
import com.offlinemesh.core.model.BundleIds
import com.offlinemesh.core.model.Kind
import com.offlinemesh.core.model.PeerId
import com.offlinemesh.core.model.StoredBundle
import java.security.MessageDigest
import kotlin.random.Random

const val H = 3_600_000L

fun pid(n: Int) = PeerId("%016x".format(n.toLong()))

private var nonceCounter = 0L

/** Tạo một StoredBundle để test chính sách/cache mà không cần chạy cả nút. */
fun stored(
    kind: Kind = Kind.TEXT, size: Int = 10, lifetimeMs: Long = 48 * H, ageMs: Long = 0,
    dst: PeerId? = pid(9), src: PeerId = pid(1), hops: Int = 0, copies: Int = 8,
    maxHops: Int = 12, originated: Boolean = false,
): StoredBundle {
    val payload = ByteArray(size) { it.toByte() }
    val nonce = ++nonceCounter
    val id = BundleIds.derive(src, dst, kind, lifetimeMs, maxHops, copies, nonce, payload)
    val core = BundleCore(id, src, dst, kind, lifetimeMs, maxHops, copies, nonce, payload)
    return StoredBundle(core, hops, ageMs, 0L, copies, originated)
}

/** Ký giả (SHA-256 với khóa bí mật chung) để chứng minh móc ký/xác minh hoạt động. */
class HmacCrypto(private val secret: String) : Signer, Verifier {
    private fun mac(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(secret.toByteArray() + data)
    override fun sign(data: ByteArray): ByteArray = mac(data)
    override fun verify(core: BundleCore): Boolean =
        core.signature.contentEquals(mac(BundleCodec.immutableBytes(core)))
}

/** Phòng thí nghiệm nhỏ: n nút trên FakeNetwork, log vào vòng đệm, gom tin nhận được. */
class Lab(
    val n: Int, seed: Int = 1, frame: Int = 244, skews: List<Long> = List(n) { 0L },
    signer: (Int) -> Signer = { NoCrypto }, verifier: (Int) -> Verifier = { NoCrypto },
    policy: () -> ForwardingPolicy = { DefaultPolicy() },
    defaults: (Kind) -> KindDefaults = { Defaults.of(it) },
    autoResync: Boolean = false,
    cipher: (Int) -> PayloadCipher = { NoCipher },
    idsOverride: List<PeerId>? = null,
) {
    val clock = FakeClock(0)
    val net = FakeNetwork(seed)
    val ring = RingBufferSink(50_000)
    val ids = idsOverride ?: List(n) { pid(it + 1) }
    val received: List<MutableList<StoredBundle>> = List(n) { mutableListOf() }
    val nodes: List<MeshNode> = List(n) { i ->
        MeshNode(
            ids[i], net.add(ids[i], frame), OffsetClock(clock, skews[i]), MeshLog(clock, listOf(ring)),
            Random(100 + i), policy = policy(), signer = signer(i), verifier = verifier(i), defaultsFor = defaults, autoResync = autoResync, cipher = cipher(i),
            onReceived = { received[i] += it },
        ).also { it.start() }
    }

    fun at(ms: Long) = clock.set(ms)

    fun meet(a: Int, b: Int, budget: Long = Long.MAX_VALUE) {
        net.connect(ids[a], ids[b], budget)
        net.runAll()
        net.disconnect(ids[a], ids[b])
        net.runAll()
    }

    fun has(node: Int, id: BundleId) = nodes[node].stored().any { it.core.id == id }
    fun tokens(node: Int, id: BundleId) = nodes[node].stored().firstOrNull { it.core.id == id }?.copies
}

/** Bọc một Transport để ép kích thước khung theo peer và ghi lại khung lớn nhất đã gửi. */
class RecordingTransport(
    private val inner: com.offlinemesh.core.mesh.transport.Transport,
    private val size: (PeerId) -> Int,
) : com.offlinemesh.core.mesh.transport.Transport by inner {
    var maxSeen = 0
    override fun frameSize(peer: PeerId): Int = size(peer)
    override fun send(to: PeerId, data: ByteArray): Boolean {
        maxSeen = maxOf(maxSeen, data.size)
        return inner.send(to, data)
    }
}
