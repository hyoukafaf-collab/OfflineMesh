package com.offlinemesh.core.mesh.node

import com.offlinemesh.core.common.Clock
import com.offlinemesh.core.common.Layer
import com.offlinemesh.core.common.Level
import com.offlinemesh.core.common.MeshLog
import com.offlinemesh.core.mesh.codec.BundleCodec
import com.offlinemesh.core.mesh.codec.Framing
import com.offlinemesh.core.mesh.codec.LinkCodec
import com.offlinemesh.core.mesh.codec.LinkMsg
import com.offlinemesh.core.mesh.codec.OfferItem
import com.offlinemesh.core.mesh.codec.Reassembler
import com.offlinemesh.core.mesh.link.LinkSession
import com.offlinemesh.core.mesh.link.LinkState
import com.offlinemesh.core.mesh.routing.Defaults
import com.offlinemesh.core.mesh.routing.KindDefaults
import com.offlinemesh.core.mesh.routing.AllowAllRelay
import com.offlinemesh.core.mesh.routing.Limits
import com.offlinemesh.core.mesh.routing.RelayFilter
import com.offlinemesh.core.mesh.routing.DefaultPolicy
import com.offlinemesh.core.mesh.routing.ForwardingPolicy
import com.offlinemesh.core.mesh.store.BundleStore
import com.offlinemesh.core.mesh.store.CacheConfig
import com.offlinemesh.core.mesh.store.InMemoryBundleStore
import com.offlinemesh.core.mesh.store.RelayCache
import com.offlinemesh.core.mesh.transport.Transport
import com.offlinemesh.core.mesh.transport.TransportListener
import com.offlinemesh.core.model.BundleCore
import com.offlinemesh.core.model.BundleId
import com.offlinemesh.core.model.BundleIds
import com.offlinemesh.core.model.ErrorCode
import com.offlinemesh.core.model.Kind
import com.offlinemesh.core.model.PeerId
import com.offlinemesh.core.model.StoredBundle
import com.offlinemesh.core.model.WireBundle
import kotlin.random.Random

/**
 * Bộ máy lưu-mang-chuyển tiếp. Mỗi cuộc gặp (Encounter) chạy:
 *   Hello + Summary (tập "đã thấy") → Offer (tin mình có mà peer chưa có, theo chính sách)
 *   → Request (peer chọn tin nó nhận) → BundleMsg... → Done.
 * Hai chiều chạy song song và đối xứng. Giả định Transport tin cậy, đúng thứ tự; không an toàn
 * đa luồng (tuần sau đặt toàn bộ cuộc gọi vào một luồng/handler duy nhất).
 */
class MeshNode(
    val selfId: PeerId,
    private val transport: Transport,
    private val clock: Clock,
    private val log: MeshLog,
    private val rng: Random,
    private val store: BundleStore = InMemoryBundleStore(),
    private val policy: ForwardingPolicy = DefaultPolicy(),
    private val signer: Signer = NoCrypto,
    private val verifier: Verifier = NoCrypto,
    private val cipher: PayloadCipher = NoCipher,
    cacheConfig: CacheConfig = CacheConfig(),
    private val cooldownMs: Long = 300_000,
    private val maxSummary: Int = 4000,
    /** Giá trị mặc định theo loại tin (hạn sống, số hop, số bản sao L). Đổi ở đây để thử nghiệm L tùy ý. */
    private val defaultsFor: (Kind) -> KindDefaults = Defaults::of,
    /**
     * Khi liên kết còn sống mà có tin mới (tự tạo, nhận được, hoặc ACK vừa sinh ra) thì tự mở vòng
     * trao đổi mới với các peer đã xong vòng trước. Bật khi chạy trên BLE (liên kết kéo dài);
     * mô phỏng mặc định tắt để mỗi cuộc gặp chỉ có một vòng.
     */
    private val autoResync: Boolean = false,
    /** Có nhận/chuyển tiếp tin của người khác không (đổi theo chế độ năng lượng). Không áp dụng cho tin gửi cho mình, SOS và ACK. */
    relayFilter: RelayFilter = AllowAllRelay,
    /** Gọi khi có tin gửi cho mình hoặc tin broadcast (SOS...). Mỗi tin chỉ một lần. */
    var onReceived: (StoredBundle) -> Unit = {},
    /** Móc đo đạc: gọi sau mỗi lần gửi thành công một tin cho một peer (dùng cho mô phỏng/thống kê). */
    var onBundleSent: (BundleCore, PeerId) -> Unit = { _, _ -> },
) : TransportListener {

    companion object {
        const val PROTOCOL_VERSION = 2
        const val SEEN_MARGIN_MS = 3_600_000L
    }

    val stats = NodeStats()

    @Volatile
    var relayFilter: RelayFilter = relayFilter

    /** Gọi khi một tin do CHÍNH MÌNH gửi đã được người nhận xác nhận (ACK hợp lệ). */
    @Volatile
    var onAcked: (BundleId) -> Unit = {}
    private val cache = RelayCache(store, cacheConfig, log)
    private val encounters = HashMap<PeerId, Encounter>()
    private val lastDone = HashMap<PeerId, Pair<Long, Int>>()   // thời điểm, version lúc đã trao đổi

    private inner class Encounter(val peer: PeerId) {
        val session = LinkSession("${selfId.short()}>${peer.short()}@${clock.nowMs()}", peer, log)
        val reasm = Reassembler()
        var msgId = 0
        var peerSeen: Set<BundleId> = emptySet()
        val offered = HashSet<BundleId>()
        var offerVersion = 0
        var sentDone = false
        var gotDone = false
        val terminal: Boolean
            get() = session.state == LinkState.DONE || session.state == LinkState.FAILED
    }

    // ---------- API cho tầng trên ----------
    fun start() = transport.start(this)
    fun stop() = transport.stop()
    fun stored(): List<StoredBundle> = store.all()

    /** Tạo và đưa một tin vào hộp đi. dst = null nghĩa là broadcast. */
    fun send(dst: PeerId?, kind: Kind, payload: ByteArray, copies: Int? = null, lifetimeMs: Long? = null): BundleId {
        require(payload.size <= Limits.maxPayload(kind)) {
            "Tin $kind dài ${payload.size} byte, tối đa ${Limits.maxPayload(kind)} byte"
        }
        val d = defaultsFor(kind)
        val life = lifetimeMs ?: d.lifetimeMs
        val l = copies ?: d.copies
        require(l in 1..255) { "L (số bản sao) phải nằm trong 1..255, nhận được $l" }
        require(d.maxHops in 1..255) { "maxHops phải nằm trong 1..255, nhận được ${d.maxHops}" }
        require(life > 0) { "hạn sống phải dương" }
        val body = if (dst != null && kind != Kind.ACK) {
            cipher.seal(dst, kind, selfId, payload)
                ?: throw IllegalStateException("Chưa có khóa mã hóa của người nhận ${dst.short()}")
        } else payload
        val core = newBundle(dst, kind, body, life, d.maxHops, l)
        val now = clock.nowMs()
        store.put(StoredBundle(core, 0, 0, now, l, true))
        store.markSeen(core.id, now + life + SEEN_MARGIN_MS)
        ev(Level.INFO, "bundle_created", core.id, detail = "kind=$kind size=${payload.size} copies=$l")
        if (autoResync) resyncNow()
        return core.id
    }

    /** Mở vòng trao đổi mới với các peer đã xong vòng trước mà từ đó kho tin có thay đổi. */
    fun resyncNow() {
        for ((peer, e) in encounters.toList()) {
            if (e.session.state != LinkState.DONE) continue
            val last = lastDone[peer]
            if (last != null && last.second == store.version) continue
            encounters.remove(peer)
            begin(peer)
        }
    }

    /** Dọn tin hết hạn và các mục seen/acked cũ. Gọi định kỳ và khi bắt đầu một cuộc gặp. */
    fun tick() {
        val now = clock.nowMs()
        for (b in store.all()) {
            if (b.isExpired(now)) {
                store.remove(b.core.id)
                ev(Level.INFO, "expired", b.core.id)
            }
        }
        store.purgeSeen(now)
    }

    // ---------- Transport callbacks ----------
    override fun onPeerFound(peer: PeerId) {
        tick()
        val old = encounters[peer]
        if (old != null && !old.terminal) return
        val last = lastDone[peer]
        if (last != null && clock.nowMs() - last.first < cooldownMs && last.second == store.version) {
            ev(Level.DEBUG, "encounter_skipped", peer = peer, detail = "chưa có gì mới")
            return
        }
        begin(peer)
    }

    override fun onPeerLost(peer: PeerId) {
        val e = encounters.remove(peer) ?: return
        if (!e.terminal) e.session.to(LinkState.FAILED, "mất liên kết", ErrorCode.LINK_LOST)
    }

    override fun onData(from: PeerId, data: ByteArray) {
        var e = encounters[from] ?: begin(from)
        if (e.session.state == LinkState.DONE) {      // peer mở vòng mới trên liên kết đang sống
            encounters.remove(from)
            e = begin(from)
        }
        if (e.terminal) return
        val bytes = e.reasm.accept(data) ?: return
        val msg = LinkCodec.decode(bytes)
        if (msg == null) {
            ev(Level.WARN, "bad_message", peer = from, error = ErrorCode.BAD_FORMAT)
            return
        }
        handle(e, msg)
    }

    // ---------- giao thức một cuộc gặp ----------
    private fun begin(peer: PeerId): Encounter {
        tick()
        val e = Encounter(peer)
        encounters[peer] = e
        e.session.to(LinkState.HANDSHAKE, "bắt đầu")
        if (send(e, LinkMsg.Hello(PROTOCOL_VERSION, selfId))) {
            send(e, LinkMsg.Summary(store.seenIds(maxSummary)))
        }
        return e
    }

    private fun handle(e: Encounter, m: LinkMsg) {
        when (m) {
            is LinkMsg.Hello -> {
                if (m.version != PROTOCOL_VERSION) fail(e, ErrorCode.HANDSHAKE_VERSION, "peer v${m.version}")
                else advance(e, LinkState.EXCHANGE, "hello")
            }
            is LinkMsg.Summary -> { e.peerSeen = m.seen.toHashSet(); sendOffer(e) }
            is LinkMsg.Offer -> sendRequest(e, m)
            is LinkMsg.Request -> sendBundles(e, m)
            is LinkMsg.BundleMsg -> { receiveBundle(e, m.wire); if (autoResync) resyncNow() }
            is LinkMsg.Done -> { e.gotDone = true; maybeFinish(e) }
        }
    }

    private fun sendOffer(e: Encounter) {
        val now = clock.nowMs()
        e.offerVersion = store.version
        val items = store.all()
            .filter {
                it.core.id !in e.peerSeen && !it.isExpired(now) &&
                    policy.decide(it, e.peer, now) != null && ackUseful(it, e.peerSeen) && mayOffer(it, e.peer)
            }
            .sortedWith(compareBy<StoredBundle>({ if (it.core.dst == e.peer) 0 else 1 }, { it.core.kind.rank }, { it.core.size }))
            .map { OfferItem(it.core.id, it.core.kind, it.core.dst, it.core.size, it.remainingMs(now)) }
        e.offered.addAll(items.map { it.id })
        send(e, LinkMsg.Offer(items))
    }

    private fun sendRequest(e: Encounter, offer: LinkMsg.Offer) {
        val now = clock.nowMs()
        send(e, LinkMsg.Request(offer.items.filter { wants(it, now) }.map { it.id }))
    }

    /** Tin gửi cho mình, SOS và ACK luôn được nhận; các tin còn lại (chuyển tiếp hộ người khác) phụ thuộc bộ lọc năng lượng và hạn mức cache. */
    private fun wants(o: OfferItem, now: Long): Boolean {
        if (store.isSeen(o.id) || store.isAcked(o.id) || o.remainingMs <= 0) return false
        if (o.dst == selfId || isAlwaysRelayed(o.dst, o.kind)) return true
        if (!relayFilter.allow(o.kind, o.size)) return false
        if (o.dst == null) return true
        return cache.admit(o.kind, o.size, o.remainingMs, now, commit = false)
    }

    private fun isAlwaysRelayed(dst: PeerId?, kind: Kind) = dst == null && (kind == Kind.SOS || kind == Kind.ACK)

    /** Máy đang tiết kiệm pin không chào bản chuyển tiếp bị cấm; tin do mình tạo, tin cho đúng peer, SOS và ACK luôn được chào. */
    private fun mayOffer(b: StoredBundle, peer: PeerId): Boolean =
        b.originated || b.core.dst == peer || isAlwaysRelayed(b.core.dst, b.core.kind) ||
            relayFilter.allow(b.core.kind, b.core.size)

    private fun sendBundles(e: Encounter, req: LinkMsg.Request) {
        advance(e, LinkState.TRANSFER, "gửi tin")
        val now = clock.nowMs()
        for (id in req.ids) {
            if (id !in e.offered) continue
            val b = store.get(id) ?: continue
            val give = policy.decide(b, e.peer, now) ?: continue
            val wire = WireBundle(b.core, b.hops, b.ageMs(now), give.copies)
            if (!send(e, LinkMsg.BundleMsg(wire))) return
            // Trừ token ngay khi gửi (lạc quan): chấp nhận mất token nếu gửi hỏng, không bao giờ vượt L.
            if (b.core.dst != null && b.core.dst != e.peer) b.copies -= give.copies
            stats.bundlesSent++
            onBundleSent(b.core, e.peer)
            ev(Level.INFO, "bundle_sent", b.core.id, e.peer, e.session.id, detail = "give=${give.copies} left=${b.copies}")
        }
        if (send(e, LinkMsg.Done)) { e.sentDone = true; maybeFinish(e) }
    }

    private fun receiveBundle(e: Encounter, w: WireBundle) {
        advance(e, LinkState.TRANSFER, "nhận tin")
        val now = clock.nowMs()
        val c = w.core
        if (c.size > Limits.maxWire(c.kind)) { reject(c, e, ErrorCode.TOO_LARGE); return }   // chặn tin quá khổ trước mọi việc
        if (BundleIds.of(c) != c.id) { reject(c, e, ErrorCode.BAD_HASH); return }
        if (store.isSeen(c.id)) { stats.duplicates++; return }          // rẻ hơn kiểm chữ ký, nên làm trước
        if (!verifier.verify(c)) { reject(c, e, ErrorCode.BAD_SIGNATURE); return }
        if (w.ageMs >= c.lifetimeMs) { reject(c, e, ErrorCode.EXPIRED); return }
        if (w.hops + 1 > c.maxHops) { reject(c, e, ErrorCode.HOP_LIMIT); return }
        if (c.dst != selfId && !isAlwaysRelayed(c.dst, c.kind) && !relayFilter.allow(c.kind, c.size)) {
            reject(c, e, ErrorCode.RELAY_REFUSED); return
        }
        val remaining = c.lifetimeMs - w.ageMs
        val until = now + remaining + SEEN_MARGIN_MS
        stats.bundlesReceived++

        if (c.kind == Kind.ACK) {
            if (!applyAck(c, until)) { reject(c, e, ErrorCode.BAD_FORMAT); return }
            if (storeRelay(w, now, remaining)) store.markSeen(c.id, until)
            return
        }
        when (c.dst) {
            selfId -> {
                store.markSeen(c.id, until)      // đã qua kiểm chữ ký nên đánh dấu "đã thấy" là an toàn
                val plain = cipher.open(c)
                if (plain == null) { reject(c, e, ErrorCode.DECRYPT_FAILED); return }
                stats.delivered++
                ev(Level.INFO, "bundle_delivered", c.id, e.peer, e.session.id, detail = "hops=${w.hops + 1}")
                onReceived(StoredBundle(c.withPayload(plain), w.hops + 1, w.ageMs, now, 1, false))
                createAck(c, remaining, now)
            }
            null -> {
                store.markSeen(c.id, until)
                stats.delivered++
                ev(Level.INFO, "bundle_received_broadcast", c.id, e.peer, e.session.id, detail = "hops=${w.hops + 1}")
                onReceived(StoredBundle(c, w.hops + 1, w.ageMs, now, 1, false))
                storeRelay(w, now, remaining)
            }
            else -> if (storeRelay(w, now, remaining)) store.markSeen(c.id, until)
        }
    }

    private fun storeRelay(w: WireBundle, now: Long, remaining: Long): Boolean {
        val c = w.core
        if (!cache.admit(c.kind, c.size, remaining, now)) {
            stats.rejected++
            ev(Level.WARN, "rejected", c.id, error = ErrorCode.CACHE_FULL)
            return false
        }
        store.put(StoredBundle(c, w.hops + 1, w.ageMs, now, maxOf(1, w.copies), false))
        ev(Level.INFO, "bundle_stored", c.id, detail = "hops=${w.hops + 1} copies=${w.copies}")
        return true
    }

    private fun ackedId(ack: BundleCore): BundleId? {
        val hex = String(ack.payload, Charsets.US_ASCII)
        if (hex.length != 16 || !hex.all { it in '0'..'9' || it in 'a'..'f' }) return null
        return BundleId(hex)
    }

    /**
     * ACK chỉ có ích với máy đã thấy tin gốc (đang giữ hoặc từng giữ), nên chỉ đề nghị ACK cho peer
     * mà tập "đã thấy" của nó chứa id tin gốc. Nhờ vậy ACK không phải lan tới mọi máy trong mạng.
     */
    private fun ackUseful(b: StoredBundle, peerSeen: Set<BundleId>): Boolean {
        if (b.core.kind != Kind.ACK) return true
        val target = ackedId(b.core) ?: return false
        return target in peerSeen
    }

    /** ACK hợp lệ chỉ dọn bản sao khi người ký ACK đúng là đích của tin gốc. */
    private fun applyAck(ack: BundleCore, until: Long): Boolean {
        val target = ackedId(ack) ?: return false
        store.markAcked(target, until)
        val copy = store.get(target)
        if (copy != null && copy.core.dst == ack.src) {
            store.remove(target)
            ev(Level.INFO, "ack_applied", target, detail = "by=${ack.src.short()}")
            if (copy.originated) onAcked(target)
        }
        return true
    }

    private fun createAck(orig: BundleCore, remainingMs: Long, now: Long) {
        val life = maxOf(remainingMs, Defaults.MIN_ACK_LIFETIME_MS)
        val core = newBundle(null, Kind.ACK, orig.id.hex.toByteArray(Charsets.US_ASCII), life, Defaults.of(Kind.ACK).maxHops, 1)
        store.put(StoredBundle(core, 0, 0, now, 1, true))
        store.markSeen(core.id, now + life + SEEN_MARGIN_MS)
        ev(Level.INFO, "ack_created", orig.id)
    }

    private fun newBundle(dst: PeerId?, kind: Kind, payload: ByteArray, life: Long, maxHops: Int, copies: Int): BundleCore {
        val nonce = rng.nextLong()
        val id = BundleIds.derive(selfId, dst, kind, life, maxHops, copies, nonce, payload)
        val core = BundleCore(id, selfId, dst, kind, life, maxHops, copies, nonce, payload, ByteArray(0), signer.publicKey)
        return core.withSignature(signer.sign(BundleCodec.immutableBytes(core)))
    }

    private fun reject(c: BundleCore, e: Encounter, code: ErrorCode) {
        stats.rejected++
        ev(Level.WARN, "rejected", c.id, e.peer, e.session.id, code)
    }

    private fun maybeFinish(e: Encounter) {
        if (!(e.sentDone && e.gotDone) || e.terminal) return
        advance(e, LinkState.DONE, "cả hai đã gửi xong")
        lastDone[e.peer] = clock.nowMs() to e.offerVersion
        if (autoResync) resyncNow()                   // ví dụ ACK vừa sinh ra sau khi đã chào hàng
    }

    private fun advance(e: Encounter, target: LinkState, reason: String) {
        if (e.terminal) return
        val order = listOf(LinkState.IDLE, LinkState.HANDSHAKE, LinkState.EXCHANGE, LinkState.TRANSFER, LinkState.DONE)
        var i = order.indexOf(e.session.state)
        val t = order.indexOf(target)
        while (i < t) { i++; e.session.to(order[i], reason) }
    }

    private fun fail(e: Encounter, code: ErrorCode, reason: String) {
        if (!e.terminal) e.session.to(LinkState.FAILED, reason, code)
    }

    private fun send(e: Encounter, m: LinkMsg): Boolean {
        val bytes = LinkCodec.encode(m)
        for (f in Framing.split(e.msgId++ and 0xFFFF, bytes, transport.frameSize(e.peer))) {
            if (!transport.send(e.peer, f)) { fail(e, ErrorCode.LINK_LOST, "gửi thất bại"); return false }
        }
        stats.bytesSent += bytes.size
        return true
    }

    private fun ev(
        level: Level, event: String, bundle: BundleId? = null, peer: PeerId? = null,
        session: String? = null, error: ErrorCode? = null, detail: String? = null,
    ) = log.log(level, Layer.ROUTING, event, bundle, peer, session, error, detail)
}
