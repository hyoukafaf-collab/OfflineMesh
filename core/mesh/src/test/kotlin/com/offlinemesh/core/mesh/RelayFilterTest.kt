package com.offlinemesh.core.mesh

import com.offlinemesh.core.mesh.codec.Framing
import com.offlinemesh.core.mesh.codec.LinkCodec
import com.offlinemesh.core.mesh.codec.LinkMsg
import com.offlinemesh.core.mesh.node.MeshNode
import com.offlinemesh.core.mesh.power.PowerLevel
import com.offlinemesh.core.mesh.power.PowerPolicy
import com.offlinemesh.core.mesh.routing.AllowAllRelay
import com.offlinemesh.core.mesh.transport.TransportListener
import com.offlinemesh.core.model.ErrorCode
import com.offlinemesh.core.model.Kind
import com.offlinemesh.core.model.PeerId
import com.offlinemesh.core.model.WireBundle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RelayFilterTest {
    private val critical = PowerPolicy.profile(PowerLevel.CRITICAL).relay      // chỉ TEXT ≤ 1 KB

    @Test
    fun aFrugalNodeStillCarriesSosAndShortTextButNotImagesOrLongText() {
        val lab = Lab(3, relayFilter = { if (it == 1) critical else AllowAllRelay })     // 0 gửi, 1 tiết kiệm pin, 2 đích
        val img = lab.nodes[0].send(lab.ids[2], Kind.IMAGE, ByteArray(2_000))
        val longText = lab.nodes[0].send(lab.ids[2], Kind.TEXT, ByteArray(2_000))
        val shortText = lab.nodes[0].send(lab.ids[2], Kind.TEXT, "ngắn".toByteArray())
        lab.nodes[0].send(null, Kind.SOS, "SOS".toByteArray())
        lab.meet(0, 1)
        assertTrue(!lab.has(1, img), "ảnh không được chuyển tiếp ở mức cực tiết kiệm")
        assertTrue(!lab.has(1, longText), "text dài hơn 1 KB không được chuyển tiếp")
        assertTrue(lab.has(1, shortText))
        assertEquals(1, lab.received[1].count { it.core.kind == Kind.SOS }, "SOS luôn được nhận")
        lab.at(1_000); lab.meet(1, 2)
        assertEquals(listOf(Kind.SOS, Kind.TEXT).sorted(), lab.received[2].map { it.core.kind }.sorted())
    }

    @Test
    fun messagesAddressedToAFrugalNodeAreNeverFiltered() {
        val lab = Lab(2, relayFilter = { critical })
        lab.nodes[0].send(lab.ids[1], Kind.IMAGE, ByteArray(30_000))
        lab.meet(0, 1)
        assertEquals(1, lab.received[1].size)
    }

    @Test
    fun relayCopiesAlreadyHeldAreNotOfferedOnceTheFilterTightensButDirectDeliveryStillWorks() {
        val lab = Lab(4)                                                   // 0 gửi, 1 trung gian, 2 người lạ, 3 đích
        val id = lab.nodes[0].send(lab.ids[3], Kind.IMAGE, ByteArray(5_000))
        lab.meet(0, 1)
        assertTrue(lab.has(1, id))
        lab.nodes[1].relayFilter = critical                                // pin tụt: trung gian chuyển sang cực tiết kiệm
        lab.at(1_000); lab.meet(1, 2)
        assertTrue(!lab.has(2, id), "bản chuyển tiếp bị cấm không được chào cho người khác")
        lab.at(2_000); lab.meet(1, 3)
        assertEquals(1, lab.received[3].size, "nhưng gặp đúng người nhận thì vẫn trao tận tay")
    }

    @Test
    fun relayBundlesPushedWithoutRequestAreRefused() {
        val lab = Lab(1, relayFilter = { critical })
        val rawId = pid(888)
        val raw = lab.net.add(rawId)
        raw.start(object : TransportListener {
            override fun onPeerFound(peer: PeerId) {}
            override fun onPeerLost(peer: PeerId) {}
            override fun onData(from: PeerId, data: ByteArray) {}
        })
        val img = stored(Kind.IMAGE, size = 2_000, dst = pid(55), src = rawId).core
        lab.net.connect(rawId, lab.ids[0]); lab.net.runAll()
        var seq = 0
        for (m in listOf(LinkMsg.Hello(MeshNode.PROTOCOL_VERSION, rawId), LinkMsg.Summary(emptyList()), LinkMsg.BundleMsg(WireBundle(img, 0, 0, 1)))) {
            Framing.split(seq++, LinkCodec.encode(m), 244).forEach { assertTrue(raw.send(lab.ids[0], it)) }
        }
        lab.net.runAll()
        assertTrue(lab.nodes[0].stored().none { it.core.id == img.id })
        assertTrue(lab.ring.snapshot().any { it.error == ErrorCode.RELAY_REFUSED })
    }
}
