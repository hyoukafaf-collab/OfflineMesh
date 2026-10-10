package com.offlinemesh.core.mesh

import com.offlinemesh.core.mesh.codec.Framing
import com.offlinemesh.core.mesh.codec.LinkCodec
import com.offlinemesh.core.mesh.codec.LinkMsg
import com.offlinemesh.core.mesh.codec.Reassembler
import com.offlinemesh.core.mesh.node.MeshNode
import com.offlinemesh.core.mesh.routing.Limits
import com.offlinemesh.core.mesh.transport.TransportListener
import com.offlinemesh.core.model.ErrorCode
import com.offlinemesh.core.model.Kind
import com.offlinemesh.core.model.PeerId
import com.offlinemesh.core.model.WireBundle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LimitsTest {
    @Test
    fun sendingAboveTheLimitIsRefusedAndAtTheLimitIsAccepted() {
        val lab = Lab(2)
        for (kind in listOf(Kind.SOS, Kind.TEXT, Kind.IMAGE)) {
            val max = Limits.maxPayload(kind)
            val dst = if (kind == Kind.SOS) null else lab.ids[1]
            lab.nodes[0].send(dst, kind, ByteArray(max))                                   // đúng giới hạn: được
            assertFailsWith<IllegalArgumentException>("$kind") { lab.nodes[0].send(dst, kind, ByteArray(max + 1)) }
        }
    }

    @Test
    fun anImageAtTheLimitTravelsEndToEnd() {
        val lab = Lab(2)
        lab.nodes[0].send(lab.ids[1], Kind.IMAGE, ByteArray(Limits.maxPayload(Kind.IMAGE)) { it.toByte() })
        lab.meet(0, 1)
        assertEquals(1, lab.received[1].size)
        assertEquals(Limits.maxPayload(Kind.IMAGE), lab.received[1][0].core.size)
    }

    @Test
    fun oversizedBundlesFromOthersAreRejected() {
        val lab = Lab(1)
        val rawId = pid(777)
        val raw = lab.net.add(rawId)
        raw.start(object : TransportListener {
            override fun onPeerFound(peer: PeerId) {}
            override fun onPeerLost(peer: PeerId) {}
            override fun onData(from: PeerId, data: ByteArray) {}
        })
        val big = stored(Kind.IMAGE, size = Limits.maxWire(Kind.IMAGE) + 1, dst = lab.ids[0], src = rawId).core
        lab.net.connect(rawId, lab.ids[0]); lab.net.runAll()
        var seq = 0
        for (m in listOf(LinkMsg.Hello(MeshNode.PROTOCOL_VERSION, rawId), LinkMsg.Summary(emptyList()), LinkMsg.BundleMsg(WireBundle(big, 0, 0, 1)))) {
            Framing.split(seq++, LinkCodec.encode(m), 244).forEach { assertTrue(raw.send(lab.ids[0], it)) }
        }
        lab.net.runAll()
        assertTrue(lab.received[0].isEmpty())
        assertTrue(lab.ring.snapshot().any { it.error == ErrorCode.TOO_LARGE })
    }

    @Test
    fun reassemblerRefusesMessagesThatClaimAbsurdLengths() {
        val r = Reassembler(maxFramesPerMessage = 100)
        val claims101 = byteArrayOf(0, 1, 0, 0, 0, 101, 9)
        assertNull(r.accept(claims101))
        val ok = Framing.split(5, ByteArray(100 * 20), 26)           // đúng 100 khung
        assertEquals(100, ok.size)
        var out: ByteArray? = null
        ok.forEach { r.accept(it)?.let { m -> out = m } }
        assertEquals(2000, out!!.size)
    }
}
