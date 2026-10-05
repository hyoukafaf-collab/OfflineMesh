package com.offlinemesh.core.mesh

import com.offlinemesh.core.mesh.transport.FakeNetwork
import com.offlinemesh.core.mesh.transport.TransportListener
import com.offlinemesh.core.model.PeerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FakeNetworkTest {
    private class Recorder : TransportListener {
        val found = mutableListOf<PeerId>()
        val lost = mutableListOf<PeerId>()
        val data = mutableListOf<Pair<PeerId, String>>()
        override fun onPeerFound(peer: PeerId) { found += peer }
        override fun onPeerLost(peer: PeerId) { lost += peer }
        override fun onData(from: PeerId, data: ByteArray) { this.data += from to String(data) }
    }

    private val a = PeerId("aaaaaaaa")
    private val b = PeerId("bbbbbbbb")
    private val c = PeerId("cccccccc")

    @Test
    fun deliversOnlyAfterStepAndOnlyInRange() {
        val net = FakeNetwork()
        val ta = net.add(a); val tb = net.add(b); val tc = net.add(c)
        val ra = Recorder(); val rb = Recorder()
        ta.start(ra); tb.start(rb); tc.start(Recorder())
        net.connect(a, b)
        net.runAll()
        assertEquals(listOf(b), ra.found)
        assertTrue(ta.send(b, "hi".toByteArray()))
        assertTrue(rb.data.isEmpty())
        net.runAll()
        assertEquals(listOf(a to "hi"), rb.data)
        assertFalse(ta.send(c, "x".toByteArray()))
    }

    @Test
    fun rejectsOversizeFrameAndStoppedNode() {
        val net = FakeNetwork()
        val ta = net.add(a, maxFrame = 4); val tb = net.add(b)
        ta.start(Recorder()); tb.start(Recorder())
        net.connect(a, b); net.runAll()
        assertFalse(ta.send(b, ByteArray(5)))
        assertTrue(ta.send(b, ByteArray(4)))
        ta.stop()
        assertFalse(ta.send(b, ByteArray(1)))
    }

    @Test
    fun disconnectBeforeDeliveryDropsFrame() {
        val net = FakeNetwork()
        val ta = net.add(a); val tb = net.add(b); val rb = Recorder()
        ta.start(Recorder()); tb.start(rb)
        net.connect(a, b); net.runAll()
        assertTrue(ta.send(b, "late".toByteArray()))
        net.disconnect(a, b)
        net.runAll()
        assertTrue(rb.data.isEmpty())
        assertEquals(listOf(a), rb.lost)
    }

    private fun delivered(seed: Int, loss: Double): Int {
        val net = FakeNetwork(seed, loss)
        val ta = net.add(a); val tb = net.add(b); val rb = Recorder()
        ta.start(Recorder()); tb.start(rb)
        net.connect(a, b); net.runAll()
        repeat(200) { ta.send(b, byteArrayOf(it.toByte())) }
        net.runAll()
        return rb.data.size
    }

    @Test
    fun lossIsDeterministicForSameSeed() {
        assertEquals(delivered(7, 0.5), delivered(7, 0.5))
        assertTrue(delivered(7, 0.5) in 60..140)
        assertEquals(200, delivered(7, 0.0))
        assertEquals(0, delivered(7, 1.0))
    }
}
