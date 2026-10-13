package com.offlinemesh.core.mesh

import com.offlinemesh.core.mesh.power.BatteryCsv
import com.offlinemesh.core.mesh.power.BatterySample
import com.offlinemesh.core.mesh.power.BatteryStats
import com.offlinemesh.core.mesh.power.PowerGovernor
import com.offlinemesh.core.mesh.power.PowerLevel
import com.offlinemesh.core.mesh.power.PowerMode
import com.offlinemesh.core.mesh.power.PowerPolicy
import com.offlinemesh.core.mesh.power.RelayRule
import com.offlinemesh.core.mesh.routing.Limits
import com.offlinemesh.core.mesh.transport.ConnectConfig
import com.offlinemesh.core.mesh.transport.ConnectPolicy
import com.offlinemesh.core.model.Kind
import com.offlinemesh.core.model.PeerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PowerTest {
    private val N = PowerMode.NORMAL
    private val E = PowerMode.EMERGENCY

    @Test
    fun levelsFollowBatteryThresholdsAndClampOutOfRangeValues() {
        for ((b, l) in listOf(100 to PowerLevel.FULL, 50 to PowerLevel.FULL, 49 to PowerLevel.SAVER, 20 to PowerLevel.SAVER,
            19 to PowerLevel.CRITICAL, 0 to PowerLevel.CRITICAL, -5 to PowerLevel.CRITICAL, 150 to PowerLevel.FULL)) {
            assertEquals(l, PowerPolicy.levelFor(b, false, N), "battery=$b")
        }
    }

    @Test
    fun chargingRaisesOneLevelInNormalModeButEmergencyStaysFrugal() {
        assertEquals(PowerLevel.SAVER, PowerPolicy.levelFor(19, true, N))
        assertEquals(PowerLevel.FULL, PowerPolicy.levelFor(30, true, N))
        assertEquals(PowerLevel.FULL, PowerPolicy.levelFor(80, true, N))
        assertEquals(PowerLevel.SAVER, PowerPolicy.levelFor(100, false, E))
        assertEquals(PowerLevel.SAVER, PowerPolicy.levelFor(50, false, E))
        assertEquals(PowerLevel.CRITICAL, PowerPolicy.levelFor(19, false, E))
        assertEquals(PowerLevel.SAVER, PowerPolicy.levelFor(19, true, E))
        assertEquals(PowerLevel.SAVER, PowerPolicy.levelFor(80, true, E))
    }

    @Test
    fun profilesGetStrictlyMoreFrugalAndRespectPlatformLimits() {
        val p = listOf(PowerLevel.FULL, PowerLevel.SAVER, PowerLevel.CRITICAL).map { PowerPolicy.profile(it) }
        assertEquals(listOf(100, 25, 10), p.map { it.scan.dutyPercent })
        for ((hi, lo) in p.zipWithNext()) {
            assertTrue(hi.scan.dutyPercent > lo.scan.dutyPercent)
            assertTrue(hi.maxCentralLinks >= lo.maxCentralLinks)
            assertTrue(hi.idleCloseMs >= lo.idleCloseMs)
            assertTrue(hi.relay.kinds.containsAll(lo.relay.kinds))
            assertTrue(hi.relay.maxBytes >= lo.relay.maxBytes)
        }
        for (x in p) {
            if (!x.scan.continuous) assertTrue(x.scan.onMs + x.scan.offMs >= 6_000, "Android giới hạn 5 lần bắt đầu quét / 30 giây")
            assertFalse(Kind.VIDEO in x.relay.kinds)
            assertFalse(Kind.SOS in x.relay.kinds || Kind.ACK in x.relay.kinds)      // SOS/ACK luôn được nhận, không qua bộ lọc
            assertTrue(x.maxCentralLinks >= 1)
        }
        assertTrue(p.first().relay.allow(Kind.IMAGE, Limits.maxPayload(Kind.IMAGE)))
        assertFalse(p.last().relay.allow(Kind.IMAGE, 100))                           // cực tiết kiệm không chuyển tiếp ảnh
        assertTrue(p.last().relay.allow(Kind.TEXT, 1024) && !p.last().relay.allow(Kind.TEXT, 1025))
    }

    @Test
    fun relayRuleChecksKindAndSizeBoundaries() {
        val r = RelayRule(setOf(Kind.TEXT), 100)
        assertTrue(r.allow(Kind.TEXT, 100)); assertFalse(r.allow(Kind.TEXT, 101)); assertFalse(r.allow(Kind.IMAGE, 1))
    }

    @Test
    fun governorUsesHysteresisSoItDoesNotFlapAroundThresholds() {
        val g = PowerGovernor()
        assertEquals(PowerLevel.FULL, g.update(60, false).level)
        assertEquals(PowerLevel.FULL, g.update(50, false).level)
        assertEquals(PowerLevel.SAVER, g.update(49, false).level)           // xuống ngay khi chạm ngưỡng
        assertEquals(PowerLevel.SAVER, g.update(52, false).level)           // chưa lên: cần vượt ngưỡng thêm 3%
        assertEquals(PowerLevel.FULL, g.update(53, false).level)
        assertEquals(PowerLevel.CRITICAL, g.update(19, false).level)
        assertEquals(PowerLevel.CRITICAL, g.update(22, false).level)
        assertEquals(PowerLevel.SAVER, g.update(23, false).level)
    }

    @Test
    fun governorReactsImmediatelyToChargingAndModeAndFirstReadingIsTakenAsIs() {
        assertEquals(PowerLevel.SAVER, PowerGovernor().update(22, false).level)              // lần đầu: không trễ
        val g = PowerGovernor()
        g.update(19, false)
        assertEquals(PowerLevel.SAVER, g.update(19, true).level)            // cắm sạc: lên ngay
        assertEquals(PowerLevel.CRITICAL, g.update(19, false).level)        // rút sạc: xuống ngay
        val m = PowerGovernor()
        assertEquals(PowerLevel.FULL, m.update(80, false).level)
        m.mode = PowerMode.EMERGENCY
        assertEquals(PowerLevel.SAVER, m.update(80, false).level)           // bật khẩn cấp: xuống ngay
        m.mode = PowerMode.NORMAL
        assertEquals(PowerLevel.FULL, m.update(80, false).level)
    }

    @Test
    fun forcedLevelOverridesBatteryForMeasurements() {
        val g = PowerGovernor()
        g.forced = PowerLevel.CRITICAL
        assertEquals(PowerLevel.CRITICAL, g.update(100, true).level)
        assertEquals(10, g.profile.scan.dutyPercent)
        g.forced = null
        assertEquals(PowerLevel.FULL, g.update(100, false).level)
    }

    @Test
    fun reconfigureLowersTheNumberOfSimultaneousCentralLinks() {
        val low = PeerId("000000000001"); val a = PeerId("ffffffffff01"); val b = PeerId("ffffffffff02")
        val p = ConnectPolicy(low, ConnectConfig(maxCentralLinks = 3))
        p.onSighting(a, -50, 0); p.onSighting(b, -60, 0)
        p.onConnectStarted(a); p.onLinkReady(a, true)
        assertNotNull(p.nextToConnect(0, setOf(a)))
        p.reconfigure(ConnectConfig(maxCentralLinks = 1))
        assertNull(p.nextToConnect(0, setOf(a)))
    }

    // ---------- thống kê pin ----------
    private val h = 3_600_000L

    @Test
    fun statsAttributeTimeAndDropToTheRunningProfileAndSkipChargingAndGaps() {
        val s = listOf(
            BatterySample(0, 100, false, "FULL"), BatterySample(h, 98, false, "FULL"), BatterySample(2 * h, 96, false, "FULL"),
            BatterySample(3 * h, 95, true, "FULL"),                          // khoảng có sạc: bỏ
            BatterySample(4 * h, 95, false, "SAVER"), BatterySample(4 * h + h / 2, 95, false, "SAVER"),
            BatterySample(20 * h, 80, false, "SAVER"),                       // cách quá 6 giờ: bỏ
            BatterySample(21 * h, 81, false, "SAVER"),                       // pin tăng không sạc: coi là tụt 0
        )
        val rows = BatteryStats.summarize(s)
        assertEquals(listOf("FULL", "SAVER"), rows.map { it.label })
        assertEquals(2.0, rows[0].hours, 1e-9); assertEquals(4.0, rows[0].dropPercent, 1e-9); assertEquals(2.0, rows[0].perHour!!, 1e-9)
        assertEquals(1.5, rows[1].hours, 1e-9); assertEquals(0.0, rows[1].dropPercent, 1e-9)
        assertTrue(BatteryStats.summarize(listOf(BatterySample(0, 90, false, "X"), BatterySample(h / 2, 89, false, "X")))[0].perHour == null)
        assertTrue(BatteryStats.summarize(emptyList()).isEmpty())
    }

    @Test
    fun csvRoundTripsAndRejectsBadLines() {
        val s = BatterySample(1_700_000_000_000, 63, true, "SAVER/EM, thử")
        val back = assertNotNull(BatteryCsv.decode(BatteryCsv.encode(s)))
        assertEquals(s.copy(label = "SAVER/EM; thử"), back)
        for (bad in listOf("", "a,b", "1,2,3", "x,50,0,L", "1,101,0,L", "1,-1,0,L", "1,50,2,L", "1,50,0")) assertNull(BatteryCsv.decode(bad), bad)
    }
}
