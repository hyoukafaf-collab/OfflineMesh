package com.offlinemesh.core.mesh

import com.offlinemesh.core.common.FakeClock
import com.offlinemesh.core.common.MeshLog
import com.offlinemesh.core.mesh.node.MeshNode
import com.offlinemesh.core.mesh.transport.ConnectConfig
import com.offlinemesh.core.mesh.transport.ConnectPolicy
import com.offlinemesh.core.mesh.transport.FakeNetwork
import com.offlinemesh.core.mesh.transport.LinkFrame
import com.offlinemesh.core.mesh.transport.LinkFrames
import com.offlinemesh.core.mesh.transport.SendQueue
import com.offlinemesh.core.model.Kind
import com.offlinemesh.core.model.PeerId
import com.offlinemesh.core.model.PeerIds
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkLayerTest {
    // ---------- LinkFrames ----------
    @Test
    fun identAndDataFramesRoundTrip() {
        val id = PeerIds.random(Random(1))
        assertEquals(12, id.hex.length)
        assertTrue(PeerIds.isValid(id))
        assertEquals(LinkFrame.Ident(id), LinkFrames.parse(LinkFrames.ident(id)))
        val d = LinkFrames.parse(LinkFrames.data(byteArrayOf(1, 2, 3))) as LinkFrame.Data
        assertContentEquals(byteArrayOf(1, 2, 3), d.payload)
        assertEquals(1 + 3, LinkFrames.data(ByteArray(3)).size)
    }

    @Test
    fun malformedFramesAreRejected() {
        val ident = LinkFrames.ident(PeerIds.random(Random(2)))
        for (len in 0 until ident.size) assertNull(LinkFrames.parse(ident.copyOf(len)), "len=$len")
        assertNull(LinkFrames.parse(ident + byteArrayOf(0)))
        val badVersion = ident.copyOf().also { it[1] = 9 }
        assertNull(LinkFrames.parse(badVersion))
        assertNull(LinkFrames.parse(byteArrayOf(0x55, 1, 2)))
        val rng = Random(3)
        repeat(2000) { LinkFrames.parse(ByteArray(rng.nextInt(0, 30)).also { b -> rng.nextBytes(b) }) }
    }

    // ---------- SendQueue ----------
    @Test
    fun onlyOneFrameInFlightAndOrderIsKept() {
        val q = SendQueue()
        (1..3).forEach { assertTrue(q.offer(byteArrayOf(it.toByte()))) }
        assertEquals(1, q.poll()!![0].toInt())
        assertNull(q.poll())                               // đang bận
        q.complete()
        assertEquals(2, q.poll()!![0].toInt())
        q.complete()
        assertEquals(3, q.poll()!![0].toInt())
        q.complete()
        assertNull(q.poll())
        assertFalse(q.busy)
    }

    @Test
    fun capacityRetryFrontAndClear() {
        val q = SendQueue(capacity = 2)
        assertTrue(q.offer(byteArrayOf(1))); assertTrue(q.offer(byteArrayOf(2)))
        assertFalse(q.offer(byteArrayOf(3)))
        val f = q.poll()!!
        q.retryFront(f)                                     // stack bận: trả lại đầu hàng
        assertEquals(1, q.poll()!![0].toInt())
        q.clear()
        assertEquals(0, q.size)
        assertFalse(q.busy)
        assertNull(q.poll())
    }

    // ---------- ConnectPolicy ----------
    private val low = PeerId("000000000001")
    private val high = PeerId("ffffffffffff")
    private val mid = PeerId("888888888888")

    @Test
    fun smallerIdInitiatesLargerWaitsThenFallsBack() {
        val small = ConnectPolicy(low)
        small.onSighting(high, -60, 0)
        assertEquals(high, small.nextToConnect(0, emptySet()))

        val large = ConnectPolicy(high)
        large.onSighting(low, -60, 0)
        assertNull(large.nextToConnect(0, emptySet()))
        large.onSighting(low, -60, 10_000)
        assertNull(large.nextToConnect(10_000, emptySet()))
        large.onSighting(low, -60, 20_000)
        assertEquals(low, large.nextToConnect(20_000, emptySet()))      // thấy liên tục 20s mà chưa ai nối
    }

    @Test
    fun fallbackStreakResetsAfterGapInSightings() {
        val large = ConnectPolicy(high)
        large.onSighting(low, -60, 0)
        large.onSighting(low, -60, 30_000)                  // cách quãng > ttl: chuỗi thấy bị reset
        assertNull(large.nextToConnect(30_000, emptySet()))
    }

    @Test
    fun strongestSignalFirstAndAlreadyLinkedExcluded() {
        val p = ConnectPolicy(low)
        p.onSighting(high, -80, 0); p.onSighting(mid, -50, 0)
        assertEquals(mid, p.nextToConnect(0, emptySet()))
        assertEquals(high, p.nextToConnect(0, setOf(mid)))
    }

    @Test
    fun oneConnectAtATimeAndLimitOnCentralLinks() {
        val p = ConnectPolicy(low, ConnectConfig(maxCentralLinks = 1))
        p.onSighting(high, -60, 0); p.onSighting(mid, -70, 0)
        assertEquals(high, p.nextToConnect(0, emptySet()))
        p.onConnectStarted(high)
        assertNull(p.nextToConnect(0, emptySet()))          // đang nối một máy
        p.onLinkReady(high, weAreCentral = true)
        assertNull(p.nextToConnect(0, setOf(high)))         // đã đủ liên kết chủ động
        p.onLinkClosed(high, 1_000, wasReady = true)
        assertEquals(mid, p.nextToConnect(1_000, emptySet()))
    }

    @Test
    fun failuresBackOffExponentiallyAndSuccessCoolsDown() {
        val cfg = ConnectConfig()
        val p = ConnectPolicy(low, cfg)
        var now = 0L
        val waits = mutableListOf<Long>()
        repeat(7) {
            p.onSighting(high, -60, now)
            assertEquals(high, p.nextToConnect(now, emptySet()))
            p.onConnectStarted(high)
            p.onLinkClosed(high, now, wasReady = false)
            // tìm khoảng chờ nhỏ nhất để được nối lại
            var w = 0L
            while (true) {
                p.onSighting(high, -60, now + w)
                if (p.nextToConnect(now + w, emptySet()) != null) break
                w += 500
            }
            waits += w
            now += w
        }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L), waits)
        p.onConnectStarted(high); p.onLinkReady(high, true); p.onLinkClosed(high, now, wasReady = true)
        p.onSighting(high, -60, now + 30_000)
        assertNull(p.nextToConnect(now + 30_000, emptySet()))            // đang nghỉ sau lần thành công
        p.onSighting(high, -60, now + 60_000)
        assertNotNull(p.nextToConnect(now + 60_000, emptySet()))
    }

    @Test
    fun remoteLinkClosingOnlyStartsCooldownAndKeepsOurConnectAttempt() {
        val p = ConnectPolicy(low, ConnectConfig(reconnectCooldownMs = 10_000))
        p.onSighting(high, -60, 0); p.onSighting(mid, -70, 0)
        p.onConnectStarted(mid)                                  // ta đang nối mid
        p.onRemoteLinkClosed(high, 1_000)                        // high (đã nối vào ta) vừa đóng
        assertNull(p.nextToConnect(1_000, emptySet()))           // vẫn đang nối mid nên không nối thêm
        p.onLinkReady(mid, true)
        p.onSighting(high, -60, 5_000)
        assertEquals(null, p.nextToConnect(5_000, setOf(mid)))   // high còn đang nghỉ
        p.onSighting(high, -60, 11_000)
        assertEquals(high, p.nextToConnect(11_000, setOf(mid)))
    }

    @Test
    fun staleSightingsAreIgnored() {
        val p = ConnectPolicy(low)
        p.onSighting(high, -60, 0)
        assertNull(p.nextToConnect(16_000, emptySet()))
        assertTrue(p.recentlySeen(16_000).isEmpty())
        val q = ConnectPolicy(low)
        q.onSighting(low, -10, 0)                           // tự thấy chính mình thì bỏ qua
        assertTrue(q.recentlySeen(1).isEmpty())
    }

    // ---------- MeshNode tôn trọng kích thước khung theo peer ----------
    @Test
    fun nodeSplitsMessagesByPerPeerFrameSize() {
        val clock = FakeClock(0)
        val net = FakeNetwork()
        val a = pid(1); val b = pid(2)
        val ta = RecordingTransport(net.add(a, maxFrame = 244)) { 40 }      // liên kết MTU nhỏ
        val tb = net.add(b, maxFrame = 244)
        var got = 0
        val na = MeshNode(a, ta, clock, MeshLog(clock, emptyList()), Random(1)).also { it.start() }
        MeshNode(b, tb, clock, MeshLog(clock, emptyList()), Random(2), onReceived = { got++ }).also { it.start() }
        na.send(b, Kind.IMAGE, ByteArray(5_000) { it.toByte() })
        net.connect(a, b); net.runAll()
        assertEquals(1, got)
        assertTrue(ta.maxSeen <= 40, "khung lớn nhất = ${ta.maxSeen}")
    }
}
