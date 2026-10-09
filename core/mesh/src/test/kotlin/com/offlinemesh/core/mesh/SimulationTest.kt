package com.offlinemesh.core.mesh

import com.offlinemesh.core.mesh.routing.DefaultPolicy
import com.offlinemesh.core.mesh.routing.DirectOnlyPolicy
import com.offlinemesh.core.mesh.routing.EpidemicPolicy
import com.offlinemesh.core.mesh.routing.ForwardingPolicy
import com.offlinemesh.core.mesh.sim.Injection
import com.offlinemesh.core.mesh.sim.Mobility
import com.offlinemesh.core.mesh.sim.SimConfig
import com.offlinemesh.core.mesh.sim.SimResult
import com.offlinemesh.core.mesh.sim.Simulation
import com.offlinemesh.core.model.Kind
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimulationTest {
    private val policies: List<Pair<String, () -> ForwardingPolicy>> = listOf(
        "direct" to { DirectOnlyPolicy() }, "spray(L=8)" to { DefaultPolicy() }, "epidemic" to { EpidemicPolicy() },
    )

    private fun uniform(policy: () -> ForwardingPolicy, copies: Int? = null): SimResult {
        val n = 30
        val sim = Simulation(SimConfig(n, policy = policy, clockSkewMs = 6 * H))
        val rng = Random(5)
        val inj = List(40) {
            val a = rng.nextInt(n); var b = rng.nextInt(n - 1); if (b >= a) b++
            Injection(rng.nextLong(12 * H), a, b, Kind.TEXT, 300, copies)
        }
        sim.run(Mobility.uniform(n, hours = 48, perNodePerHour = 1.0, seed = 3), inj)
        return sim.result()
    }

    private fun clustered(policy: () -> ForwardingPolicy): SimResult {
        val sim = Simulation(SimConfig(3 * 8 + 2, policy = policy))
        val rng = Random(9)
        val inj = List(30) {
            val ca = rng.nextInt(3); val cb = (ca + 1 + rng.nextInt(2)) % 3     // luôn khác cụm
            Injection(rng.nextLong(12 * H), ca * 8 + rng.nextInt(8), cb * 8 + rng.nextInt(8), Kind.TEXT, 300)
        }
        sim.run(Mobility.communities(3, 8, 2, hours = 48, intraPerNodePerHour = 4.0, mulePerHour = 6.0, seed = 4), inj)
        return sim.result()
    }

    private fun show(title: String, rs: List<Pair<String, SimResult>>) {
        println("== $title")
        for ((name, r) in rs) {
            println("%-11s giao %2d/%2d (%3.0f%%) | trễ trung vị %s | số lần chuyển %5d | %.1f MB".format(
                name, r.delivered, r.sent, r.ratio * 100,
                r.medianLatencyMs?.let { "%.1fh".format(it / 3_600_000.0) } ?: "-", r.transmissions, r.txMegabytes))
        }
    }

    @Test
    fun uniformMobilityComparison() {
        val rs = policies.map { (n, p) -> n to uniform(p) }
        show("30 nút, mỗi nút ~1 lần gặp/giờ, 48 giờ, đồng hồ lệch ±6h", rs)
        val (d, s, e) = rs.map { it.second }
        assertTrue(s.delivered >= d.delivered, "spray >= direct")
        assertTrue(e.delivered >= s.delivered, "epidemic >= spray")
        assertTrue(e.transmissions > s.transmissions, "epidemic tốn hơn spray")
    }

    @Test
    fun communitiesNeedMulesToCrossClusters() {
        val rs = policies.map { (n, p) -> n to clustered(p) }
        show("3 cụm x 8 người + 2 người đi lại, tin luôn gửi sang cụm khác", rs)
        val (d, s, e) = rs.map { it.second }
        assertEquals(0, d.delivered, "không có người mang tin thì không qua được cụm")
        assertTrue(s.delivered > 0 && e.delivered >= s.delivered)
    }

    @Test
    fun sprayNeverHoldsMoreThanLPlusOneCopiesAndHopLimitHolds() {
        val n = 30
        val sim = Simulation(SimConfig(n))
        sim.trackHolders = true
        val rng = Random(11)
        val inj = List(25) {
            val a = rng.nextInt(n); var b = rng.nextInt(n - 1); if (b >= a) b++
            Injection(rng.nextLong(12 * H), a, b, Kind.TEXT, 200, copies = 8)
        }
        sim.run(Mobility.uniform(n, 24, 3.0, seed = 2), inj)
        assertTrue(sim.maxHolders.isNotEmpty())
        // ≤ L giữ token + 1 (người nhận cuối đang giữ bản chờ ACK) = 9
        assertTrue(sim.maxHolders.values.all { it <= 9 }, "max=${sim.maxHolders.values.maxOrNull()}")
        assertTrue(sim.nodes.all { node -> node.stored().all { it.hops <= it.core.maxHops } })
    }

    @Test
    fun deliveredAndAckedBundlesDisappearFromTheNetworkEventually() {
        val n = 20
        val sim = Simulation(SimConfig(n))
        val rng = Random(21)
        val inj = List(15) {
            val a = rng.nextInt(n); var b = rng.nextInt(n - 1); if (b >= a) b++
            Injection(rng.nextLong(6 * H), a, b, Kind.TEXT, 200)
        }
        sim.run(Mobility.uniform(n, 96, 4.0, seed = 8), inj)
        sim.nodes.forEach { it.tick() }                       // sau 96 giờ mọi tin 48h phải hết hạn
        assertTrue(sim.nodes.all { node -> node.stored().all { it.core.kind != Kind.TEXT } })
    }
}
