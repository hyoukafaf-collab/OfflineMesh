package com.offlinemesh.core.mesh

import com.offlinemesh.core.mesh.payload.SosReach
import com.offlinemesh.core.mesh.payload.SosReach.Confidence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SosReachTest {
    @Test
    fun boundCombinesHopsAndWalkingDistanceDuringTheMessageAge() {
        assertEquals(460.0, SosReach.maxDistanceM(4, 30_000), 1e-9)          // ví dụ trong LOCATION.md
        assertEquals(1_600.0, SosReach.maxDistanceM(4, 600_000), 1e-9)       // tin 10 phút tuổi: 400 + 1200
        assertEquals(100.0, SosReach.maxDistanceM(1, 0), 1e-9)               // nhận trực tiếp, tức thì
    }

    @Test
    fun badInputsAreClampedAndTheBoundGrowsWithHopsAndAge() {
        assertEquals(SosReach.maxDistanceM(1, 0), SosReach.maxDistanceM(0, 0), 1e-9)       // hop < 1 coi là 1
        assertEquals(SosReach.maxDistanceM(2, 0), SosReach.maxDistanceM(2, -9_999), 1e-9)  // tuổi âm coi là 0
        var prev = 0.0
        for (h in 1..10) { val d = SosReach.maxDistanceM(h, 1_000); assertTrue(d > prev); prev = d }
        prev = 0.0
        for (a in listOf(0L, 1_000L, 60_000L, 3_600_000L)) { val d = SosReach.maxDistanceM(3, a); assertTrue(d >= prev); prev = d }
    }

    @Test
    fun confidenceTiersAndConfigAreRespected() {
        assertEquals(Confidence.HIGH, SosReach.confidence(500.0))
        assertEquals(Confidence.MEDIUM, SosReach.confidence(501.0))
        assertEquals(Confidence.MEDIUM, SosReach.confidence(1_500.0))
        assertEquals(Confidence.LOW, SosReach.confidence(1_501.0))
        assertEquals(Confidence.LOW, SosReach.confidence(5_000.0))
        assertEquals(Confidence.NONE, SosReach.confidence(5_001.0))
        val indoors = SosReach.Config(rangeM = 20.0, walkSpeedMps = 1.0)
        assertEquals(4 * 20.0 + 30.0, SosReach.maxDistanceM(4, 30_000, indoors), 1e-9)
        assertFailsWith<IllegalArgumentException> { SosReach.Config(rangeM = 0.0) }
    }

    @Test
    fun descriptionStatesTheAssumptionsAndGivesUpWhenTheMessageIsTooOld() {
        val t = SosReach.describe(4, 30_000)
        assertTrue(t.contains("460 m") && t.contains("100 m") && t.contains("2 m/s"), t)
        assertTrue(SosReach.describe(4, 600_000).contains("1.6 km"))
        assertTrue(SosReach.describe(4, 3_600_000).contains("không ước lượng được"))        // 400 + 7200 m > 5 km
    }
}
