package com.offlinemesh.core.mesh

import com.offlinemesh.core.common.Level
import com.offlinemesh.core.mesh.codec.Framing
import com.offlinemesh.core.mesh.codec.LinkCodec
import com.offlinemesh.core.mesh.codec.LinkMsg
import com.offlinemesh.core.mesh.node.MeshNode
import com.offlinemesh.core.mesh.node.NoCrypto
import com.offlinemesh.core.mesh.transport.TransportListener
import com.offlinemesh.core.model.BundleCore
import com.offlinemesh.core.model.ErrorCode
import com.offlinemesh.core.model.Kind
import com.offlinemesh.core.model.PeerId
import com.offlinemesh.core.model.WireBundle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NodeFlowTest {
    @Test
    fun directDeliveryThenAckPurgesSenderCopy() {
        val lab = Lab(2)
        val id = lab.nodes[0].send(lab.ids[1], Kind.TEXT, "xin chào".toByteArray())
        lab.meet(0, 1)
        assertEquals(1, lab.received[1].size)
        assertEquals("xin chào", String(lab.received[1][0].core.payload))
        assertTrue(lab.has(0, id))                                         // chưa có ACK nên còn giữ
        assertTrue(lab.nodes[1].stored().any { it.core.kind == Kind.ACK })
        lab.at(60_000)
        lab.meet(0, 1)
        assertTrue(!lab.has(0, id))                                        // ACK đã dọn bản của người gửi
        assertEquals(1, lab.received[1].size)                              // không giao lần hai
    }

    @Test
    fun relayCarriesMessageAndAckFlowsBack() {
        val lab = Lab(3)   // 0 = người gửi, 1 = người mang tin, 2 = người nhận
        val id = lab.nodes[0].send(lab.ids[2], Kind.TEXT, "gửi C".toByteArray())
        lab.at(1_000); lab.meet(0, 1)
        assertTrue(lab.has(1, id))
        assertTrue(lab.received[2].isEmpty())
        lab.at(H); lab.meet(1, 2)
        assertEquals(1, lab.received[2].size)
        lab.at(H + 100_000); lab.meet(2, 1)
        assertTrue(!lab.has(1, id))
        lab.at(H + 200_000); lab.meet(1, 0)
        assertTrue(!lab.has(0, id))
    }

    @Test
    fun ackIsOnlyOfferedToMachinesThatSawTheBundle() {
        val lab = Lab(4)   // 0 gửi, 1 mang tin, 2 là đích, 3 chưa từng thấy tin
        val id = lab.nodes[0].send(lab.ids[2], Kind.TEXT, "x".toByteArray())
        lab.at(1_000); lab.meet(0, 1)
        lab.at(2_000); lab.meet(1, 2)
        lab.at(3_000); lab.meet(2, 3)
        assertTrue(lab.nodes[3].stored().none { it.core.kind == Kind.ACK })   // 3 không cần ACK
        lab.at(4_000); lab.meet(2, 1)
        assertTrue(!lab.has(1, id))                                           // 1 đang giữ bản nên nhận ACK
    }

    @Test
    fun sprayAndWaitHalvesTokensAndStopsAtOne() {
        val lab = Lab(6)   // đích là nút 4
        val id = lab.nodes[0].send(lab.ids[4], Kind.TEXT, ByteArray(10), copies = 8)
        lab.meet(0, 1); lab.meet(0, 2); lab.meet(0, 3)
        assertEquals(listOf(1, 4, 2, 1, null, null), (0..5).map { lab.tokens(it, id) })
        lab.meet(0, 5)                                         // nút 0 chỉ còn 1 token: pha chờ
        assertEquals(null, lab.tokens(5, id))
        lab.meet(1, 5)                                         // nút 1 còn 4: trao 2
        assertEquals(listOf(1, 2, 2, 1, null, 2), (0..5).map { lab.tokens(it, id) })
        assertEquals(8, (0..5).sumOf { lab.tokens(it, id) ?: 0 })
        lab.meet(0, 4)                                         // gặp đích: trao tận tay dù chỉ còn 1 token
        assertEquals(1, lab.received[4].size)
    }

    @Test
    fun sosFloodsOverTwelveHopsButNotBeyond() {
        val lab = Lab(15)
        lab.nodes[0].send(null, Kind.SOS, "SOS".toByteArray())
        for (i in 0..13) { lab.at(i * 1_000L); lab.meet(i, i + 1) }
        for (k in 1..12) assertEquals(1, lab.received[k].size, "node $k")
        assertTrue(lab.received[13].isEmpty() && lab.received[14].isEmpty())
        assertTrue(lab.received.all { it.size <= 1 })                      // không giao trùng
    }

    @Test
    fun alreadySeenBundleIsNotSentAgain() {
        val lab = Lab(3)
        lab.nodes[0].send(null, Kind.SOS, "SOS".toByteArray())
        lab.meet(0, 1); lab.meet(1, 2)
        val before = lab.nodes[0].stats.bundlesSent
        lab.at(10 * 60_000); lab.meet(0, 2)
        assertEquals(before, lab.nodes[0].stats.bundlesSent)
        assertEquals(1, lab.received[2].size)
    }

    @Test
    fun expiryUsesRelativeAgeNotWallClock() {
        val skew = 10L * 24 * H
        val lab = Lab(3, skews = listOf(0, skew, -skew))   // nút 1 lệch +10 ngày, nút 2 lệch -10 ngày
        val id = lab.nodes[0].send(lab.ids[2], Kind.TEXT, ByteArray(10))   // sống 48 giờ
        lab.at(H); lab.meet(0, 1)
        assertTrue(lab.has(1, id))
        lab.at(47 * H); lab.nodes[1].tick()
        assertTrue(lab.has(1, id))
        lab.at(49 * H); lab.nodes[1].tick(); lab.nodes[0].tick()
        assertTrue(!lab.has(1, id) && !lab.has(0, id))
    }

    @Test
    fun peerWithSkewedClockStillReceivesRemainingLifetime() {
        val lab = Lab(3, skews = listOf(0, 0, -30L * 24 * H))
        val id = lab.nodes[0].send(lab.ids[1], Kind.TEXT, ByteArray(10))
        lab.at(47 * H); lab.meet(0, 2)                     // nút 2 nhận khi tin đã 47 giờ tuổi
        lab.at(48 * H + 1); lab.nodes[2].tick()
        assertTrue(!lab.has(2, id))
    }

    @Test
    fun unsignedBundleIsRejectedWhenReceiverVerifies() {
        val lab = Lab(3, signer = { if (it == 0) NoCrypto else HmacCrypto("k1") }, verifier = { HmacCrypto("k1") })
        lab.nodes[0].send(lab.ids[1], Kind.TEXT, "không ký".toByteArray())
        lab.nodes[2].send(lab.ids[1], Kind.TEXT, "có ký".toByteArray())
        lab.meet(0, 1); lab.at(1_000); lab.meet(2, 1)
        assertEquals(listOf("có ký"), lab.received[1].map { String(it.core.payload) })
        assertTrue(lab.ring.snapshot().any { it.error == ErrorCode.BAD_SIGNATURE })
    }

    @Test
    fun tamperedPayloadIsRejectedAsBadHash() {
        val lab = Lab(1)
        val rawId = pid(99)
        val raw = lab.net.add(rawId)
        raw.start(object : TransportListener {
            override fun onPeerFound(peer: PeerId) {}
            override fun onPeerLost(peer: PeerId) {}
            override fun onData(from: PeerId, data: ByteArray) {}
        })
        val good = stored(Kind.TEXT, size = 20, dst = lab.ids[0], src = rawId).core
        val forged = BundleCore(good.id, good.src, good.dst, good.kind, good.lifetimeMs, good.maxHops,
            good.initialCopies, good.nonce, ByteArray(20) { 0x7f })             // nội dung bị sửa, id giữ nguyên
        lab.net.connect(rawId, lab.ids[0]); lab.net.runAll()
        var seq = 0
        for (m in listOf(LinkMsg.Hello(MeshNode.PROTOCOL_VERSION, rawId), LinkMsg.Summary(emptyList()), LinkMsg.BundleMsg(WireBundle(forged, 0, 0, 1)))) {
            Framing.split(seq++, LinkCodec.encode(m), 244).forEach { assertTrue(raw.send(lab.ids[0], it)) }
        }
        lab.net.runAll()
        assertTrue(lab.received[0].isEmpty())
        assertTrue(lab.ring.snapshot().any { it.error == ErrorCode.BAD_HASH })
    }

    @Test
    fun interruptedTransferIsRetriedAtNextContact() {
        val lab = Lab(2)
        lab.nodes[0].send(lab.ids[1], Kind.IMAGE, ByteArray(20_000))
        lab.meet(0, 1, budget = 8_000)                                     // cuộc gặp quá ngắn
        assertTrue(lab.received[1].isEmpty())
        assertTrue(lab.ring.snapshot().any { it.event == "state" && it.error == ErrorCode.LINK_LOST })
        lab.at(10 * 60_000); lab.meet(0, 1)
        assertEquals(1, lab.received[1].size)
        assertEquals(20_000, lab.received[1][0].core.size)
    }

    @Test
    fun videoIsOnlyHandedDirectlyToItsRecipient() {
        val lab = Lab(3)
        lab.nodes[0].send(lab.ids[2], Kind.VIDEO, ByteArray(500))
        lab.meet(0, 1)
        assertTrue(lab.nodes[1].stored().none { it.core.kind == Kind.VIDEO })
        lab.at(10 * 60_000); lab.meet(0, 2)
        assertEquals(1, lab.received[2].size)
    }

    @Test
    fun journeyOfOneBundleCanBeRebuiltFromLog() {
        val lab = Lab(2)
        val id = lab.nodes[0].send(lab.ids[1], Kind.TEXT, "x".toByteArray())
        lab.meet(0, 1)
        val steps = lab.ring.forBundle(id).map { it.event }
        assertEquals(listOf("bundle_created", "bundle_sent", "bundle_delivered"), steps.filter { it in setOf("bundle_created", "bundle_sent", "bundle_delivered") })
        assertNotNull(lab.ring.forBundle(id).first { it.event == "bundle_sent" }.session)
        assertTrue(lab.ring.snapshot().none { it.level == Level.ERROR })
    }
}
