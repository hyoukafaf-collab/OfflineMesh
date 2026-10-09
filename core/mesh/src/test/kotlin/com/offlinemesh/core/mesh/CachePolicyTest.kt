package com.offlinemesh.core.mesh

import com.offlinemesh.core.common.FakeClock
import com.offlinemesh.core.common.MeshLog
import com.offlinemesh.core.mesh.routing.DefaultPolicy
import com.offlinemesh.core.mesh.routing.Defaults
import com.offlinemesh.core.mesh.routing.Give
import com.offlinemesh.core.mesh.store.CacheConfig
import com.offlinemesh.core.mesh.store.InMemoryBundleStore
import com.offlinemesh.core.mesh.store.RelayCache
import com.offlinemesh.core.model.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CachePolicyTest {
    private val policy = DefaultPolicy()
    private val peer = pid(50)

    @Test
    fun broadcastFloodsUntilHopLimit() {
        assertEquals(Give(1), policy.decide(stored(Kind.SOS, dst = null, hops = 11), peer, 0))
        assertNull(policy.decide(stored(Kind.SOS, dst = null, hops = 12), peer, 0))
    }

    @Test
    fun privateMessageUsesBinarySprayAndWait() {
        assertEquals(Give(4), policy.decide(stored(copies = 8), peer, 0))
        assertEquals(Give(1), policy.decide(stored(copies = 2), peer, 0))
        assertEquals(Give(1), policy.decide(stored(copies = 3), peer, 0))   // 3 → trao 1, giữ 2
        assertNull(policy.decide(stored(copies = 1), peer, 0))               // pha chờ
    }

    @Test
    fun destinationAlwaysGetsHandDelivery() {
        val dst = pid(9)
        assertEquals(Give(1), policy.decide(stored(dst = dst, copies = 1), dst, 0))
    }

    @Test
    fun lastHopIsReservedForDestination() {
        val relay = stored(dst = pid(9), copies = 8, hops = 11, maxHops = 12)
        assertNull(policy.decide(relay, peer, 0))
        assertEquals(Give(1), policy.decide(relay, pid(9), 0))
    }

    @Test
    fun expiredIsNeverForwarded() {
        assertNull(policy.decide(stored(lifetimeMs = H, ageMs = H), peer, 0))
    }

    @Test
    fun videoOnlyTravelsOneHop() {
        val v = Defaults.of(Kind.VIDEO)
        assertEquals(1, v.maxHops)
        val s = stored(Kind.VIDEO, dst = pid(9), maxHops = 1, copies = 1)
        assertNull(policy.decide(s, peer, 0))
        assertEquals(Give(1), policy.decide(s, pid(9), 0))
    }

    @Test
    fun defaultsMatchAgreedValues() {
        assertEquals(72 * H, Defaults.of(Kind.SOS).lifetimeMs)
        assertEquals(48 * H, Defaults.of(Kind.TEXT).lifetimeMs)
        assertEquals(48 * H, Defaults.of(Kind.IMAGE).lifetimeMs)   // ảnh sống 2 ngày
        assertEquals(8, Defaults.of(Kind.TEXT).copies)
        assertEquals(4, Defaults.of(Kind.IMAGE).copies)
    }

    // ---- cache ----
    private fun cache(cap: Long = 100): Pair<RelayCache, InMemoryBundleStore> {
        val store = InMemoryBundleStore()
        return RelayCache(store, CacheConfig(mapOf(Kind.TEXT to cap)), MeshLog(FakeClock(), emptyList())) to store
    }

    @Test
    fun evictsShortestRemainingFirstAndKeepsLonger() {
        val (c, store) = cache()
        val long = stored(size = 40, ageMs = 0)          // còn 48h
        val short = stored(size = 40, ageMs = 43 * H)    // còn 5h
        store.put(long); store.put(short)
        assertTrue(c.admit(Kind.TEXT, 40, 8 * H, 0))
        assertTrue(store.get(long.core.id) != null)
        assertTrue(store.get(short.core.id) == null)
    }

    @Test
    fun rejectsIncomingThatIsWorseThanEverythingStored() {
        val (c, store) = cache()
        val a = stored(size = 50); val b = stored(size = 50)
        store.put(a); store.put(b)
        assertFalse(c.admit(Kind.TEXT, 40, 1 * H, 0))
        assertEquals(2, store.all().size)
    }

    @Test
    fun originatedMessagesAreNeitherEvictedNorCounted() {
        val (c, store) = cache()
        store.put(stored(size = 90, originated = true))
        assertTrue(c.admit(Kind.TEXT, 90, 10 * H, 0))
        assertEquals(1, store.all().size)
    }

    @Test
    fun oversizeAndUnknownKindAreRefusedAndDryRunEvictsNothing() {
        val (c, store) = cache()
        assertFalse(c.admit(Kind.TEXT, 101, 10 * H, 0))
        assertFalse(c.admit(Kind.IMAGE, 1, 10 * H, 0))
        val a = stored(size = 100, ageMs = 40 * H)
        store.put(a)
        assertTrue(c.admit(Kind.TEXT, 10, 40 * H, 0, commit = false))
        assertEquals(1, store.all().size)
    }
}
