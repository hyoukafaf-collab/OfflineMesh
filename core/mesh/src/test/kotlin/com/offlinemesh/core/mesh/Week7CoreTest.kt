package com.offlinemesh.core.mesh

import com.offlinemesh.core.mesh.crypto.ContactCard
import com.offlinemesh.core.mesh.crypto.Identity
import com.offlinemesh.core.mesh.crypto.InMemoryContactBook
import com.offlinemesh.core.mesh.crypto.MeshCrypto
import com.offlinemesh.core.mesh.history.MessageCodec
import com.offlinemesh.core.mesh.history.MessageLog
import com.offlinemesh.core.mesh.history.StoredMessage
import com.offlinemesh.core.mesh.payload.GeoPoint
import com.offlinemesh.core.mesh.payload.ReplayConfig
import com.offlinemesh.core.mesh.payload.ReplaySchedule
import com.offlinemesh.core.mesh.payload.SosEntry
import com.offlinemesh.core.mesh.payload.SosFeed
import com.offlinemesh.core.mesh.payload.SosPayload
import com.offlinemesh.core.mesh.payload.SosPresets
import com.offlinemesh.core.mesh.payload.SosReplayQueue
import com.offlinemesh.core.mesh.payload.SosType
import com.offlinemesh.core.mesh.power.PowerLevel
import com.offlinemesh.core.mesh.power.PowerPolicy
import com.offlinemesh.core.mesh.routing.AllowAllRelay
import com.offlinemesh.core.mesh.settings.ManualRange
import com.offlinemesh.core.mesh.settings.Retention
import com.offlinemesh.core.model.BundleId
import com.offlinemesh.core.model.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Week7CoreTest {
    // ---------- SOS v2: session + seq ----------
    @Test
    fun sosPayloadCarriesSessionAndSeqAndClampsThem() {
        val p = SosPayload(SosType.TRAPPED, GeoPoint(1_000_000, 2_000_000, 8), 30, "kẹt tầng 2", sessionId = 0xABCD, seq = 7)
        val back = assertNotNull(SosPayload.decode(p.encode()))
        assertEquals(0xABCD, back.sessionId); assertEquals(7, back.seq)
        assertEquals(19 + "kẹt tầng 2".toByteArray().size, p.encode().size)
        val clamp = SosPayload.decode(SosPayload(SosType.SAFE, null, 0, "x", sessionId = 70_000, seq = 999).encode())!!
        assertEquals(70_000 and 0xFFFF, clamp.sessionId); assertEquals(255, clamp.seq)
        assertNull(SosPayload.decode(p.encode().also { it[0] = 1 }))            // bản v1 không còn được chấp nhận
    }

    // ---------- phát lại SOS ----------
    @Test
    fun replayDelaysDoubleFromOneMinuteUpToOneHour() {
        val c = ReplayConfig()
        assertEquals(listOf(60L, 120L, 240L, 480L, 960L, 1920L, 3600L, 3600L), (0..7).map { ReplaySchedule.delayMs(it, c) / 1000 })
        assertEquals(3_600_000L, ReplaySchedule.delayMs(500, c))
    }

    @Test
    fun replayQueueReturnsItemsInFifoOrderOnSchedule() {
        val q = SosReplayQueue<String>(ReplayConfig(durationMs = 3 * 3_600_000L))
        q.begin(0)
        q.add("a"); q.add("b"); q.add("c")
        assertEquals(60_000L, q.nextDueAtMs())
        assertTrue(q.due(59_999).isEmpty())
        assertEquals(listOf("a", "b", "c"), q.due(60_000))                         // lần 1: cũ nhất trước
        assertEquals(180_000L, q.nextDueAtMs())                                    // +2 phút
        q.add("d")                                                                 // mục mới nối vào cuối
        assertEquals(listOf("a", "b", "c", "d"), q.due(180_000))
        assertEquals(420_000L, q.nextDueAtMs())                                    // +4 phút
    }

    @Test
    fun replayStopsByItselfAfterTheDurationOrWhenCancelled() {
        val q = SosReplayQueue<Int>(ReplayConfig(durationMs = ReplayConfig.MIN_DURATION_MS))   // 5 phút
        q.begin(0); q.add(1)
        assertEquals(listOf(1), q.due(60_000)); assertEquals(listOf(1), q.due(180_000))
        assertTrue(q.active)
        assertTrue(q.due(420_000).isEmpty())                                      // quá 5 phút: tự dừng
        assertFalse(q.active); assertNull(q.nextDueAtMs())
        q.begin(1_000_000); q.add(2)
        q.cancel()                                                                 // bấm "Tôi an toàn"
        assertTrue(q.due(2_000_000).isEmpty()); assertFalse(q.active); assertEquals(0, q.size)
    }

    @Test
    fun aLateTickFiresOnceAndReschedulesFromNow() {
        val q = SosReplayQueue<String>(); q.begin(0); q.add("x")
        assertEquals(listOf("x"), q.due(600_000))                                  // trễ 10 phút
        assertEquals(600_000L + 120_000L, q.nextDueAtMs())
        assertTrue(q.due(600_001).isEmpty())
    }

    @Test
    fun durationChoicesAreInsideTheAllowedRangeAndPresetsFitAnSos() {
        assertEquals(ReplayConfig.MIN_DURATION_MS, ReplayConfig.clampDuration(1))
        assertEquals(ReplayConfig.MAX_DURATION_MS, ReplayConfig.clampDuration(Long.MAX_VALUE))
        assertTrue(ReplayConfig.DURATION_CHOICES_MIN.all { it * 60_000L in ReplayConfig.MIN_DURATION_MS..ReplayConfig.MAX_DURATION_MS })
        assertEquals(ReplayConfig.DURATION_CHOICES_MIN.sorted(), ReplayConfig.DURATION_CHOICES_MIN)
        for (p in SosPresets.QUICK + SosPresets.SAFE) {
            assertTrue(p.text.toByteArray().size <= SosPayload.MAX_TEXT)
            assertEquals(p.text, SosPayload.decode(SosPayload(p.type, null, 0, p.text).encode())!!.text)
        }
        assertEquals(SosType.SAFE, SosPresets.SAFE.type)
    }

    // ---------- gộp SOS ----------
    private fun entry(src: Int, at: Long, type: SosType, session: Int, seq: Int, text: String = "t") =
        SosEntry(pid(src), at, SosPayload(type, null, 0, text, session, seq))

    @Test
    fun feedKeepsOnlyTheLatestUpdatePerSessionAndNotifiesOnlyOnNewSessions() {
        val all = listOf(entry(1, 100, SosType.TRAPPED, 5, 0), entry(1, 200, SosType.TRAPPED, 5, 1), entry(1, 250, SosType.TRAPPED, 5, 2),
            entry(1, 300, SosType.INJURED, 6, 0), entry(2, 150, SosType.NEED_RESCUE, 5, 0))
        val items = SosFeed.collapse(all)
        assertEquals(3, items.size)
        assertEquals(2, items.first { it.latest.src == pid(1) && it.latest.session == 5 }.latest.seq)
        assertTrue(SosFeed.isNewSession(all.take(1), all[3]))                      // session khác: mới
        assertFalse(SosFeed.isNewSession(all.take(1), all[1]))                     // cùng session: chỉ là cập nhật
        assertFalse(SosFeed.isNewSession(all.take(1), entry(1, 999, SosType.TRAPPED, 5, 9)))
        assertTrue(SosFeed.isNewSession(all, entry(3, 5, SosType.TRAPPED, 5, 0)))  // người gửi khác, cùng mã session
    }

    @Test
    fun aSafeMessageResolvesEarlierSosFromTheSameSenderAndSortsUnresolvedFirst() {
        val all = listOf(entry(1, 100, SosType.TRAPPED, 5, 0), entry(1, 400, SosType.SAFE, 9, 0), entry(2, 200, SosType.INJURED, 5, 0))
        val items = SosFeed.collapse(all)
        assertEquals(listOf(pid(2), pid(1), pid(1)), items.map { it.latest.src })   // chưa giải quyết trước
        assertEquals(listOf(false, true, true), items.map { it.resolved })
        val later = SosFeed.collapse(all + entry(1, 500, SosType.NEED_RESCUE, 12, 0))   // SOS mới sau "an toàn" thì không bị coi là đã giải quyết
        assertFalse(later.first { it.latest.session == 12 }.resolved)
    }

    // ---------- lịch sử tin ----------
    private fun msg(id: String, conv: String = "C1", t: Long = 1_000, out: Boolean = false, text: String = "xin chào") =
        StoredMessage(id, conv, out, "TEXT", text, null, t)

    @Test
    fun messageCodecRoundTripsAwkwardTextAndRejectsBadLines() {
        val m = StoredMessage("ab12", "SOS", false, "SOS", "dòng 1\ndòng 2\ttab \\ gạch", null, 123456789012L, 1, "0a0b0c0d0e0f", 3, 40,
            2, 777, 4, 21_000_000, 105_000_000, 12, 95, true, pinned = true)
        val back = assertNotNull(MessageCodec.decode(MessageCodec.encode(m)))
        assertEquals(m.text, back.text); assertEquals(m.tMs, back.tMs); assertEquals(21_000_000, back.latE6)
        assertEquals(12, back.accM); assertTrue(back.read); assertEquals(777, back.session); assertTrue(back.pinned)
        assertFalse(MessageCodec.encode(m).contains('\n'))
        assertNull(MessageCodec.decode("a\tb")); assertNull(MessageCodec.decode("")); assertNull(MessageCodec.decode(MessageCodec.encode(m).replace("\tSOS\t", "\tXYZ\t")))
        val img = MessageCodec.decode(MessageCodec.encode(StoredMessage("i1", "PUBLIC", true, "IMAGE", "", "i1.jpg", 5)))!!
        assertEquals("i1.jpg", img.image); assertNull(img.latE6)
    }

    @Test
    fun logAddsOnceOrdersByTimeTracksStateUpAndUnread() {
        val log = MessageLog()
        assertTrue(log.add(msg("a", t = 30))); assertTrue(log.add(msg("b", t = 10))); assertFalse(log.add(msg("a")))
        assertEquals(listOf("b", "a"), log.conversation("C1").map { it.id })
        assertEquals(2, log.unread("C1"))
        assertTrue(log.markRead("C1")); assertEquals(0, log.unread("C1")); assertFalse(log.markRead("C1"))
        log.add(msg("o", out = true)); assertEquals(0, log.unread("C1"))
        assertTrue(log.setState("o", 1)); assertTrue(log.setState("o", 2)); assertFalse(log.setState("o", 1)); assertFalse(log.setState("nope", 1))
        assertEquals(2, log.get("o")!!.state)
        val copy = MessageLog().also { it.load(log.encodeAll()) }
        assertEquals(log.size, copy.size); assertEquals(2, copy.get("o")!!.state)
    }

    @Test
    fun purgeRemovesOnlyOldMessagesInTheChosenConversation() {
        val log = MessageLog()
        val now = 100L * 86_400_000L
        log.add(msg("old1", "C1", now - 40 * 86_400_000L)); log.add(msg("new1", "C1", now - 2 * 86_400_000L))
        log.add(msg("old2", "C2", now - 40 * 86_400_000L)); log.add(msg("mid1", "C1", now - 10 * 86_400_000L))
        assertEquals(listOf("old1", "mid1"), log.purgeOlderThan(ManualRange.WEEK.cutoffMs(now), "C1").map { it.id })
        assertEquals(setOf("new1", "old2"), log.all().map { it.id }.toSet())          // C2 không bị ảnh hưởng
        assertEquals(listOf("old2"), log.purgeOlderThan(Retention.cutoffMs(now, 30)).map { it.id })
        assertEquals(1, log.deleteConversation("C1").size); assertEquals(0, log.size)
    }

    @Test
    fun sosMessagesBecomeFeedEntries() {
        val log = MessageLog()
        log.add(StoredMessage("s1", "SOS", false, "SOS", "kẹt", null, 50, src = "0a0b0c0d0e0f", sosType = 2, session = 4, seq = 1, latE6 = 1, lonE6 = 2))
        log.add(StoredMessage("s2", "SOS", true, "SOS", "của tôi", null, 60, sosType = 2))
        val e = log.sosEntries().single()
        assertEquals(4, e.session); assertEquals(1, e.payload.position!!.latE6)
    }

    @Test
    fun retentionRulesStayInsideTheAllowedRange() {
        assertEquals(1, Retention.clampDays(0)); assertEquals(90, Retention.clampDays(500)); assertEquals(30, Retention.DEFAULT_DAYS)
        assertTrue(Retention.CHOICES.all { it in 1..90 } && Retention.CHOICES.sorted() == Retention.CHOICES && 30 in Retention.CHOICES)
        assertEquals(1_000_000_000L - 30 * 86_400_000L, Retention.cutoffMs(1_000_000_000L, 30))
        assertEquals(listOf("1 ngày", "3 ngày", "1 tuần", "2 tuần", "1 tháng", "2 tháng", "3 tháng"), Retention.CHOICES.map { Retention.label(it) })
        assertEquals(listOf(1, 7, 14, 30), ManualRange.values().map { it.days })
    }

    // ---------- ACK báo về cho người gửi ----------
    @Test
    fun theSenderIsToldWhenItsOwnMessageIsAcknowledged() {
        val lab = Lab(2)
        val acked = mutableListOf<BundleId>()
        lab.nodes[0].onAcked = { acked += it }
        val id = lab.nodes[0].send(lab.ids[1], Kind.TEXT, "x".toByteArray())
        lab.meet(0, 1)
        assertTrue(acked.isEmpty())                                                // ACK được tạo sau khi chào hàng
        lab.at(60_000); lab.meet(0, 1)
        assertEquals(listOf(id), acked)
        lab.at(120_000); lab.meet(0, 1)
        assertEquals(1, acked.size)                                                // chỉ báo một lần
    }

    // ---------- tin và ảnh công khai ----------
    @Test
    fun publicTextAndImagesFloodLikeSosButUseNormalLimitsAndHops() {
        val lab = Lab(3)
        lab.nodes[0].send(null, Kind.TEXT, "xin chào mọi người".toByteArray())
        val img = ByteArray(30_000) { it.toByte() }
        lab.nodes[0].send(null, Kind.IMAGE, img)
        lab.meet(0, 1); lab.at(1_000); lab.meet(1, 2)
        for (i in 1..2) assertEquals(listOf(Kind.TEXT, Kind.IMAGE), lab.received[i].map { it.core.kind }.sortedBy { it.rank }.let { l -> listOf(Kind.TEXT, Kind.IMAGE).filter { it in l } })
        assertEquals(listOf(1, 2), listOf(1, 2).map { i -> lab.received[i].first { it.core.kind == Kind.TEXT }.hops })
        assertTrue(lab.received[2].all { it.core.dst == null })
        assertFailsWith<IllegalArgumentException> { lab.nodes[0].send(null, Kind.TEXT, ByteArray(4097)) }
        assertFailsWith<IllegalArgumentException> { lab.nodes[0].send(null, Kind.IMAGE, ByteArray(65_537)) }
    }

    @Test
    fun publicPostsAreSignedButNotEncrypted() {
        val ids = List(2) { Identity.generate() }
        val crypto = ids.map { MeshCrypto(it, InMemoryContactBook()) }
        val lab = Lab(2, signer = { crypto[it] }, verifier = { crypto[it] }, cipher = { crypto[it] }, idsOverride = ids.map { it.peerId })
        lab.nodes[0].send(null, Kind.TEXT, "công khai".toByteArray())
        lab.meet(0, 1)
        val got = lab.received[1].single().core
        assertEquals("công khai", String(got.payload)); assertEquals(64, got.signature.size); assertEquals(33, got.pubKey.size)
        assertNull(got.dst)
    }

    @Test
    fun aVeryFrugalNodeStillTakesShortPublicTextButNotPublicImages() {
        val critical = PowerPolicy.profile(PowerLevel.CRITICAL).relay
        val lab = Lab(2, relayFilter = { if (it == 1) critical else AllowAllRelay })
        lab.nodes[0].send(null, Kind.TEXT, "ngắn".toByteArray())
        lab.nodes[0].send(null, Kind.IMAGE, ByteArray(5_000))
        lab.meet(0, 1)
        assertEquals(listOf(Kind.TEXT), lab.received[1].map { it.core.kind })
    }
}
