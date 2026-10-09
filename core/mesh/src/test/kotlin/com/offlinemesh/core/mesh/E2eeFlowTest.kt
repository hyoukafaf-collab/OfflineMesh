package com.offlinemesh.core.mesh

import com.offlinemesh.core.mesh.codec.BundleCodec
import com.offlinemesh.core.mesh.codec.Framing
import com.offlinemesh.core.mesh.codec.LinkCodec
import com.offlinemesh.core.mesh.codec.LinkMsg
import com.offlinemesh.core.mesh.crypto.ContactCard
import com.offlinemesh.core.mesh.crypto.Identity
import com.offlinemesh.core.mesh.crypto.InMemoryContactBook
import com.offlinemesh.core.mesh.crypto.MeshCrypto
import com.offlinemesh.core.mesh.node.MeshNode
import com.offlinemesh.core.mesh.transport.TransportListener
import com.offlinemesh.core.model.BundleCore
import com.offlinemesh.core.model.BundleIds
import com.offlinemesh.core.model.ErrorCode
import com.offlinemesh.core.model.Kind
import com.offlinemesh.core.model.PeerId
import com.offlinemesh.core.model.WireBundle
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class E2eeFlowTest {
    /** n máy có danh tính thật. known[i] = các máy mà máy i đã quét QR (có khóa trong danh bạ). */
    private class World(n: Int, known: Map<Int, List<Int>>) {
        val idents = List(n) { Identity.generate() }
        val books = List(n) { InMemoryContactBook() }
        val crypto = List(n) { MeshCrypto(idents[it], books[it]) }
        val lab: Lab

        init {
            for ((i, js) in known) for (j in js) books[i].add(ContactCard("máy$j", idents[j].signPub, idents[j].encPub), idents[i].peerId)
            lab = Lab(n, signer = { crypto[it] }, verifier = { crypto[it] }, cipher = { crypto[it] }, idsOverride = idents.map { it.peerId })
        }
    }

    /** Bơm một dãy tin cho nút `victim` qua một đồng đẳng thô (không phải MeshNode) để mô phỏng kẻ tấn công. */
    private fun inject(lab: Lab, victim: Int, rawId: PeerId, vararg wires: WireBundle) {
        val raw = lab.net.add(rawId)
        raw.start(object : TransportListener {
            override fun onPeerFound(peer: PeerId) {}
            override fun onPeerLost(peer: PeerId) {}
            override fun onData(from: PeerId, data: ByteArray) {}
        })
        lab.net.connect(rawId, lab.ids[victim]); lab.net.runAll()
        var seq = 0
        val msgs = listOf(LinkMsg.Hello(MeshNode.PROTOCOL_VERSION, rawId), LinkMsg.Summary(emptyList())) + wires.map { LinkMsg.BundleMsg(it) }
        for (m in msgs) Framing.split(seq++, LinkCodec.encode(m), 244).forEach { assertTrue(raw.send(lab.ids[victim], it)) }
        lab.net.runAll()
    }

    /** Tạo tin được KÝ ĐÚNG QUY CÁCH bởi `by`, với src/payload tùy ý (để dựng các tình huống tấn công). */
    private fun signed(by: Identity, dst: PeerId?, kind: Kind, payload: ByteArray, src: PeerId = by.peerId): WireBundle {
        val life = 48 * H
        val nonce = 12345L
        val id = BundleIds.derive(src, dst, kind, life, 12, 1, nonce, payload)
        val c0 = BundleCore(id, src, dst, kind, life, 12, 1, nonce, payload, ByteArray(0), by.signPub)
        val sig = MeshCrypto(by, InMemoryContactBook()).sign(BundleCodec.immutableBytes(c0))
        return WireBundle(c0.withSignature(sig), 0, 0, 1)
    }

    @Test
    fun relayCannotReadButRecipientCanAndAckComesBack() {
        val w = World(3, mapOf(0 to listOf(2)))                      // A (0) có khóa của B (2); R (1) là máy trung gian
        val lab = w.lab
        val secret = "địa chỉ trú ẩn: kho lương thực".toByteArray()
        val id = lab.nodes[0].send(lab.ids[2], Kind.TEXT, secret)
        lab.at(1_000); lab.meet(0, 1)
        val atRelay = lab.nodes[1].stored().first { it.core.id == id }.core
        assertTrue(String(atRelay.payload, Charsets.ISO_8859_1).indexOf(String(secret, Charsets.ISO_8859_1)) < 0, "máy trung gian chỉ thấy bản mã")
        assertEquals(secret.size + 50, atRelay.payload.size)
        assertEquals(null, w.crypto[1].open(atRelay), "máy trung gian không giải mã được")
        lab.at(2_000); lab.meet(1, 2)
        assertEquals(1, lab.received[2].size)
        assertContentEquals(secret, lab.received[2][0].core.payload)
        assertEquals(lab.ids[0], lab.received[2][0].core.src)
        assertContentEquals(w.idents[0].signPub, lab.received[2][0].core.pubKey)
        lab.at(3_000); lab.meet(2, 1)
        assertTrue(!lab.has(1, id))
        lab.at(4_000); lab.meet(1, 0)
        assertTrue(!lab.has(0, id))
    }

    @Test
    fun cannotSendPrivateMessageToSomeoneWithoutTheirKeyButBroadcastIsSigned() {
        val w = World(2, emptyMap())
        val lab = w.lab
        assertFailsWith<IllegalStateException> { lab.nodes[0].send(lab.ids[1], Kind.TEXT, "x".toByteArray()) }
        lab.nodes[0].send(null, Kind.SOS, "SOS".toByteArray())
        lab.meet(0, 1)
        assertEquals(1, lab.received[1].size)
        assertEquals(64, lab.received[1][0].core.signature.size)
        assertContentEquals(w.idents[0].signPub, lab.received[1][0].core.pubKey)
        assertEquals("SOS", String(lab.received[1][0].core.payload))      // SOS công khai: ký nhưng không mã hóa
    }

    @Test
    fun modifiedContentIsRejectedWhetherOrNotTheIdIsRecomputed() {
        val w = World(2, mapOf(0 to listOf(1)))
        val lab = w.lab
        val victim = 1
        val good = signed(w.idents[0], lab.ids[victim], Kind.TEXT, ByteArray(60) { 7 }).core
        // (a) sửa nội dung, giữ nguyên id → mã băm không khớp
        val sameId = BundleCore(good.id, good.src, good.dst, good.kind, good.lifetimeMs, good.maxHops, good.initialCopies,
            good.nonce, ByteArray(60) { 9 }, good.signature, good.pubKey)
        inject(lab, victim, pid(900), WireBundle(sameId, 0, 0, 1))
        assertTrue(lab.ring.snapshot().any { it.error == ErrorCode.BAD_HASH })
        // (b) sửa nội dung và tính lại id nhưng giữ chữ ký cũ → chữ ký không khớp
        val newId = BundleIds.derive(good.src, good.dst, good.kind, good.lifetimeMs, good.maxHops, good.initialCopies, good.nonce, ByteArray(60) { 9 })
        val recomputed = BundleCore(newId, good.src, good.dst, good.kind, good.lifetimeMs, good.maxHops, good.initialCopies,
            good.nonce, ByteArray(60) { 9 }, good.signature, good.pubKey)
        inject(lab, victim, pid(901), WireBundle(recomputed, 0, 0, 1))
        assertTrue(lab.ring.snapshot().any { it.error == ErrorCode.BAD_SIGNATURE })
        assertTrue(lab.received[victim].isEmpty())
    }

    @Test
    fun spoofingAnotherSendersIdIsRejected() {
        val w = World(2, emptyMap())
        val lab = w.lab
        val attacker = Identity.generate()
        // kẻ tấn công ghi src = PeerId của máy 0 nhưng đính khóa của mình và ký bằng khóa của mình
        inject(lab, 1, pid(902), signed(attacker, null, Kind.SOS, "giả danh".toByteArray(), src = w.idents[0].peerId))
        assertTrue(lab.received[1].isEmpty())
        assertTrue(lab.ring.snapshot().any { it.error == ErrorCode.BAD_SIGNATURE })
    }

    @Test
    fun reSigningSomeoneElsesCiphertextDoesNotFoolTheRecipient() {
        val w = World(3, mapOf(0 to listOf(1)))                      // A (0) gửi riêng cho B (1); kẻ tấn công M có danh tính riêng
        val lab = w.lab
        val msg = "chuyển 5 triệu cho tôi".toByteArray()
        val id = lab.nodes[0].send(lab.ids[1], Kind.TEXT, msg)
        val ciphertext = lab.nodes[0].stored().first { it.core.id == id }.core.payload
        val attacker = Identity.generate()
        // M bóc chữ ký của A, đặt bản mã cũ dưới tên mình (src = M) và tự ký: chữ ký hợp lệ nhưng AAD không khớp
        inject(lab, 1, pid(903), signed(attacker, lab.ids[1], Kind.TEXT, ciphertext))
        assertTrue(lab.received[1].isEmpty(), "B không được tin rằng M là tác giả")
        assertTrue(lab.ring.snapshot().any { it.error == ErrorCode.DECRYPT_FAILED })
        assertTrue(lab.nodes[1].stored().none { it.core.kind == Kind.ACK }, "không giải mã được thì không phát ACK")
    }

    @Test
    fun onlyTheRealRecipientCanPurgeACopyWithAnAck() {
        val w = World(3, mapOf(0 to listOf(1)))                      // A gửi cho B; R (2) giữ bản sao
        val lab = w.lab
        val id = lab.nodes[0].send(lab.ids[1], Kind.TEXT, "x".toByteArray())
        lab.at(1_000); lab.meet(0, 2)
        assertTrue(lab.has(2, id))
        val ackPayload = id.hex.toByteArray(Charsets.US_ASCII)
        val attacker = Identity.generate()
        // (a) ACK ký đúng bởi kẻ tấn công (src = M, không phải đích) → không dọn
        inject(lab, 2, pid(904), signed(attacker, null, Kind.ACK, ackPayload))
        assertTrue(lab.has(2, id), "ACK của người lạ không được dọn tin của người khác")
        // (b) ACK giả danh đích (src = B) nhưng ký bằng khóa của M → bị từ chối
        inject(lab, 2, pid(905), signed(attacker, null, Kind.ACK, ackPayload, src = w.idents[1].peerId))
        assertTrue(lab.has(2, id))
        assertTrue(lab.ring.snapshot().any { it.error == ErrorCode.BAD_SIGNATURE })
        // (c) ACK thật của B → dọn
        lab.at(2_000); lab.meet(2, 1)
        lab.at(3_000); lab.meet(1, 2)
        assertTrue(!lab.has(2, id))
    }

    @Test
    fun plaintextFromANodeWithoutCryptoIsRejected() {
        val w = World(2, emptyMap())
        val lab = w.lab
        // máy 1 chạy mật mã; một đồng đẳng thô gửi tin không ký
        val unsigned = WireBundle(signed(w.idents[0], null, Kind.SOS, "x".toByteArray()).core.let {
            BundleCore(it.id, it.src, it.dst, it.kind, it.lifetimeMs, it.maxHops, it.initialCopies, it.nonce, it.payload)
        }, 0, 0, 1)
        inject(lab, 1, pid(906), unsigned)
        assertTrue(lab.received[1].isEmpty())
        assertTrue(lab.ring.snapshot().any { it.error == ErrorCode.BAD_SIGNATURE })
    }
}
