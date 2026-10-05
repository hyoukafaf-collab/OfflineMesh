package com.offlinemesh.core.mesh

import com.offlinemesh.core.common.FakeClock
import com.offlinemesh.core.common.MeshLog
import com.offlinemesh.core.common.RingBufferSink
import com.offlinemesh.core.mesh.link.LinkSession
import com.offlinemesh.core.mesh.link.LinkState
import com.offlinemesh.core.model.ErrorCode
import com.offlinemesh.core.model.PeerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkSessionTest {
    private fun newSession(): Pair<LinkSession, RingBufferSink> {
        val ring = RingBufferSink()
        return LinkSession("s1", PeerId("p1p1p1p1"), MeshLog(FakeClock(), listOf(ring))) to ring
    }

    @Test
    fun legalPathIsLogged() {
        val (s, ring) = newSession()
        assertTrue(s.to(LinkState.HANDSHAKE, "found"))
        assertTrue(s.to(LinkState.EXCHANGE, "hello ok"))
        assertTrue(s.to(LinkState.TRANSFER, "need 3 bundles"))
        assertTrue(s.to(LinkState.DONE, "all sent"))
        assertEquals(LinkState.DONE, s.state)
        assertEquals(4, ring.snapshot().count { it.event == "state" })
    }

    @Test
    fun illegalTransitionIsRejectedAndLogged() {
        val (s, ring) = newSession()
        s.to(LinkState.HANDSHAKE, "found")
        assertFalse(s.to(LinkState.TRANSFER, "skip exchange"))
        assertEquals(LinkState.HANDSHAKE, s.state)
        assertEquals(ErrorCode.ILLEGAL_TRANSITION, ring.snapshot().last().error)
    }

    @Test
    fun failedIsTerminal() {
        val (s, _) = newSession()
        assertTrue(s.to(LinkState.FAILED, "timeout", ErrorCode.LINK_TIMEOUT))
        assertFalse(s.to(LinkState.HANDSHAKE, "retry"))
        assertEquals(LinkState.FAILED, s.state)
    }
}
