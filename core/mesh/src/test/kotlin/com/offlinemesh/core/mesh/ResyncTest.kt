package com.offlinemesh.core.mesh

import com.offlinemesh.core.model.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResyncTest {
    @Test
    fun liveLinkPushesNewMessageWithoutReconnectAndAckPurgesSender() {
        val lab = Lab(2, autoResync = true)
        lab.net.connect(lab.ids[0], lab.ids[1]); lab.net.runAll()          // liên kết sống, chưa có gì để trao đổi
        val id = lab.nodes[0].send(lab.ids[1], Kind.TEXT, "gửi sau khi đã nối".toByteArray())
        lab.net.runAll()
        assertEquals(1, lab.received[1].size)
        assertTrue(lab.net.inRange(lab.ids[0], lab.ids[1]))               // vẫn nối, không mở lại
        assertTrue(!lab.has(0, id), "ACK phải quay về dọn bản gốc ngay trên liên kết đang sống")
    }

    @Test
    fun relayForwardsOverLiveLinksAndAckComesBack() {
        val lab = Lab(3, autoResync = true)
        lab.net.connect(lab.ids[0], lab.ids[1]); lab.net.connect(lab.ids[1], lab.ids[2]); lab.net.runAll()
        val id = lab.nodes[0].send(lab.ids[2], Kind.TEXT, "qua B".toByteArray())
        lab.net.runAll()
        assertEquals(1, lab.received[2].size)
        assertTrue(!lab.has(0, id) && !lab.has(1, id), "ACK đi ngược C → B → A")
    }

    @Test
    fun noEndlessPingPongWhenNothingIsNew() {
        val lab = Lab(2, autoResync = true)
        lab.net.connect(lab.ids[0], lab.ids[1]); lab.net.runAll()
        lab.nodes[0].send(null, Kind.SOS, "SOS".toByteArray())
        lab.net.runAll()                                                  // phải tự dừng (runAll không chạm giới hạn)
        val sent = lab.nodes.sumOf { it.stats.bundlesSent }
        lab.net.runAll()
        assertEquals(sent, lab.nodes.sumOf { it.stats.bundlesSent })
        assertEquals(1, lab.received[1].size)
    }

    @Test
    fun withoutAutoResyncNewMessagesWaitForNextContact() {
        val lab = Lab(2)
        lab.net.connect(lab.ids[0], lab.ids[1]); lab.net.runAll()
        lab.nodes[0].send(lab.ids[1], Kind.TEXT, "x".toByteArray())
        lab.net.runAll()
        assertTrue(lab.received[1].isEmpty())
        lab.net.disconnect(lab.ids[0], lab.ids[1]); lab.net.runAll()
        lab.at(60_000); lab.meet(0, 1)
        assertEquals(1, lab.received[1].size)
    }
}
