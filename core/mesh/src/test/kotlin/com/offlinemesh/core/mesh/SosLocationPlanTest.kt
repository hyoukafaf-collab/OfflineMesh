package com.offlinemesh.core.mesh

import com.offlinemesh.core.mesh.payload.SosLocationPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SosLocationPlanTest {
    @Test
    fun aRecentKnownFixIsSentImmediatelyWithoutWaiting() {
        for (age in listOf(0L, 5_000L, 119_999L, 120_000L)) {
            val p = SosLocationPlan.plan(age)
            assertTrue(p.sendNow, "age=$age"); assertEquals(0, p.waitForFixMs)
        }
    }

    @Test
    fun anOldOrMissingFixWaitsAtMostThreeSecondsAndNeverMore() {
        for (age in listOf(null, 120_001L, 3_600_000L, -5L)) {
            val p = SosLocationPlan.plan(age)
            assertFalse(p.sendNow, "age=$age"); assertEquals(3_000, p.waitForFixMs)
        }
        assertTrue(SosLocationPlan.WAIT_FRESH_MS <= 3_000)              // SOS không được chờ định vị lâu
    }

    @Test
    fun veryOldFixesAreNotUsable() {
        assertTrue(SosLocationPlan.isUsable(0)); assertTrue(SosLocationPlan.isUsable(6 * 3_600_000L))
        assertFalse(SosLocationPlan.isUsable(6 * 3_600_000L + 1)); assertFalse(SosLocationPlan.isUsable(-1))
    }
}
