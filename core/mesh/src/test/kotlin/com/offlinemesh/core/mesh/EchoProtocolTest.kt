package com.offlinemesh.core.mesh

import com.offlinemesh.core.mesh.echo.EchoProtocol
import com.offlinemesh.core.mesh.echo.EchoReceiver
import com.offlinemesh.core.mesh.echo.EchoReport
import com.offlinemesh.core.mesh.transport.FakeNetwork
import com.offlinemesh.core.mesh.transport.TransportListener
import com.offlinemesh.core.model.PeerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EchoProtocolTest {
    private fun run(
        frames: Int, size: Int, rx: EchoReceiver = EchoReceiver(),
        tamper: (Int, ByteArray) -> ByteArray? = { _, f -> f },
    ): EchoReport {
        for (i in 0 until frames) {
            val f = tamper(i, EchoProtocol.dataFrame(i, size)) ?: continue
            rx.onFrame(f)
        }
        val reply = rx.onFrame(EchoProtocol.endFrame(frames))
        return EchoProtocol.parseReport(assertNotNull(reply))!!
    }

    @Test
    fun cleanRun() {
        assertEquals(EchoReport(10, 0, 0, 640), run(10, 64))
        assertEquals(64, EchoProtocol.dataFrame(0, 64).size)
    }

    @Test
    fun corruptedPayloadCountsBadCrc() {
        val r = run(10, 64) { i, f -> if (i == 4) f.also { it[20] = (it[20] + 1).toByte() } else f }
        assertEquals(9, r.received)
        assertEquals(1, r.badCrc)
        assertEquals(0, r.outOfOrder)
    }

    @Test
    fun droppedFrameCountsOutOfOrder() {
        val r = run(10, 64) { i, f -> if (i == 3) null else f }
        assertEquals(9, r.received)
        assertEquals(1, r.outOfOrder)
    }

    @Test
    fun secondRunResetsAtSeqZero() {
        val rx = EchoReceiver()
        run(10, 64, rx)
        assertEquals(EchoReport(5, 0, 0, 5 * 32), run(5, 32, rx))
    }

    @Test
    fun reportRoundTripAndGarbage() {
        val r = EchoReport(7, 1, 2, 999)
        assertEquals(r, EchoProtocol.parseReport(EchoProtocol.reportFrame(r)))
        assertNull(EchoProtocol.parseReport(ByteArray(5)))
        assertNull(EchoReceiver().onFrame(ByteArray(0)))
    }

    @Test
    fun overLossyFakeNetwork() {
        val a = PeerId("aaaaaaaa"); val b = PeerId("bbbbbbbb")
        val net = FakeNetwork(seed = 3, lossRate = 0.2)
        val ta = net.add(a, maxFrame = 64); val tb = net.add(b, maxFrame = 64)
        val rx = EchoReceiver()
        tb.start(object : TransportListener {
            override fun onPeerFound(peer: PeerId) {}
            override fun onPeerLost(peer: PeerId) {}
            override fun onData(from: PeerId, data: ByteArray) { rx.onFrame(data) }
        })
        ta.start(object : TransportListener {
            override fun onPeerFound(peer: PeerId) {}
            override fun onPeerLost(peer: PeerId) {}
            override fun onData(from: PeerId, data: ByteArray) {}
        })
        net.connect(a, b); net.runAll()
        repeat(200) { assertTrue(ta.send(b, EchoProtocol.dataFrame(it, 64))) }
        net.runAll()
        assertTrue(rx.received in 120..190, "received=${rx.received}")
        assertEquals(0, rx.badCrc)
        assertTrue(rx.outOfOrder > 0)
    }
}
