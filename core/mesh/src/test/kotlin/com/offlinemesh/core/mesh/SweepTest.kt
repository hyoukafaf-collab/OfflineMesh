package com.offlinemesh.core.mesh

import com.offlinemesh.core.mesh.routing.Defaults
import com.offlinemesh.core.mesh.sim.Sweep
import com.offlinemesh.core.mesh.sim.SweepArgs
import com.offlinemesh.core.mesh.sim.SweepConfig
import com.offlinemesh.core.model.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SweepTest {
    private val small = SweepConfig(nodes = 20, rate = 0.5, hours = 36, messages = 25, seeds = 3)

    @Test
    fun argumentsAreParsedAndValidated() {
        val c = SweepArgs.parse(arrayOf("L=2,4,100", "nodes=10", "kind=image", "size=30000", "life=72", "model=clusters"))
        assertEquals(listOf(2, 4, 100), c.ls)
        assertEquals(10, c.nodes); assertEquals(Kind.IMAGE, c.kind); assertEquals(72, c.lifeHours); assertEquals("clusters", c.model)
        assertFailsWith<IllegalArgumentException> { SweepArgs.parse(arrayOf("L=0")) }
        assertFailsWith<IllegalArgumentException> { SweepArgs.parse(arrayOf("L=256")) }
        assertFailsWith<IllegalArgumentException> { SweepArgs.parse(arrayOf("foo=1")) }
        assertFailsWith<IllegalArgumentException> { SweepArgs.parse(arrayOf("L")) }
    }

    @Test
    fun lEqualsOneBehavesExactlyLikeDirectDelivery() {
        for (seed in 1..3) {
            val spray1 = Sweep.runOne(small, copies = 1, seed = seed)
            val direct = Sweep.runOne(small.copy(policy = "direct"), copies = 8, seed = seed)
            assertEquals(direct.delivered, spray1.delivered)
            assertEquals(direct.dataTransfers, spray1.dataTransfers)
        }
    }

    @Test
    fun moreCopiesCostMoreTransfersAndNeverDeliverLess() {
        fun total(l: Int, f: (com.offlinemesh.core.mesh.sim.SimResult) -> Int) = (1..3).sumOf { f(Sweep.runOne(small, l, it)) }
        assertTrue(total(16) { it.dataTransfers } > total(2) { it.dataTransfers })
        assertTrue(total(16) { it.delivered } >= total(2) { it.delivered })
        assertTrue(total(1) { it.delivered } <= total(4) { it.delivered })
    }

    @Test
    fun sameSeedGivesSameNumbers() {
        val a = Sweep.runOne(small, 8, 2)
        val b = Sweep.runOne(small, 8, 2)
        assertEquals(a.delivered, b.delivered)
        assertEquals(a.dataTransfers, b.dataTransfers)
        assertEquals(a.medianLatencyMs, b.medianLatencyMs)
    }

    @Test
    fun tablePrintsOneRowPerL() {
        val text = Sweep.table(small.copy(ls = listOf(1, 8), seeds = 2))
        println(text)
        assertTrue(text.lines().count { it.trim().startsWith("1 ") || it.trim().startsWith("8 ") } == 2)
    }

    @Test
    fun copyCountIsValidatedAndDefaultsAreConfigurable() {
        val lab = Lab(2)
        assertFailsWith<IllegalArgumentException> { lab.nodes[0].send(lab.ids[1], Kind.TEXT, ByteArray(1), copies = 256) }
        assertFailsWith<IllegalArgumentException> { lab.nodes[0].send(lab.ids[1], Kind.TEXT, ByteArray(1), copies = 0) }
        val custom = Lab(2, defaults = { Defaults.of(it).copy(copies = 20) })
        val id = custom.nodes[0].send(custom.ids[1], Kind.TEXT, ByteArray(1))
        assertEquals(20, custom.tokens(0, id))
        val id2 = custom.nodes[0].send(custom.ids[1], Kind.TEXT, ByteArray(1), copies = 3)   // ghi đè từng tin
        assertEquals(3, custom.tokens(0, id2))
    }
}
