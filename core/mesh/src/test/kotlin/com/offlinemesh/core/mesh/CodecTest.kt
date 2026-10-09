package com.offlinemesh.core.mesh

import com.offlinemesh.core.mesh.codec.BundleCodec
import com.offlinemesh.core.mesh.codec.LinkCodec
import com.offlinemesh.core.mesh.codec.LinkMsg
import com.offlinemesh.core.mesh.codec.OfferItem
import com.offlinemesh.core.model.BundleId
import com.offlinemesh.core.model.BundleIds
import com.offlinemesh.core.model.Kind
import com.offlinemesh.core.model.WireBundle
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodecTest {
    private fun wire(dst: Boolean = true, sig: ByteArray = byteArrayOf(1, 2, 3), pub: ByteArray = ByteArray(33) { (it + 1).toByte() }): WireBundle {
        val s = stored(Kind.TEXT, size = 40, dst = if (dst) pid(7) else null, src = pid(3))
        return WireBundle(com.offlinemesh.core.model.BundleCore(s.core.id, s.core.src, s.core.dst, s.core.kind, s.core.lifetimeMs, s.core.maxHops, s.core.initialCopies, s.core.nonce, s.core.payload, sig, pub), hops = 2, ageMs = 123_456, copies = 4)
    }

    @Test
    fun bundleRoundTrip() {
        for (w in listOf(wire(true), wire(false), wire(true, ByteArray(0)))) {
            val back = assertNotNull(BundleCodec.decode(BundleCodec.encode(w)))
            assertEquals(w.core.id, back.core.id)
            assertEquals(w.core.src, back.core.src)
            assertEquals(w.core.dst, back.core.dst)
            assertEquals(w.core.kind, back.core.kind)
            assertEquals(w.core.lifetimeMs, back.core.lifetimeMs)
            assertEquals(w.core.maxHops, back.core.maxHops)
            assertEquals(w.core.initialCopies, back.core.initialCopies)
            assertEquals(w.core.nonce, back.core.nonce)
            assertContentEquals(w.core.payload, back.core.payload)
            assertContentEquals(w.core.signature, back.core.signature)
            assertContentEquals(w.core.pubKey, back.core.pubKey)
            assertEquals(Triple(2, 123_456L, 4), Triple(back.hops, back.ageMs, back.copies))
            assertEquals(back.core.id, BundleIds.of(back.core))
        }
    }

    @Test
    fun everyTruncationIsRejectedWithoutException() {
        val bytes = BundleCodec.encode(wire())
        for (len in 0 until bytes.size) assertNull(BundleCodec.decode(bytes.copyOf(len)), "len=$len")
        val extra = bytes + byteArrayOf(0)
        assertNull(BundleCodec.decode(extra))
    }

    @Test
    fun otherVersionIsRejected() {
        val bytes = BundleCodec.encode(wire())
        bytes[0] = 9
        assertNull(BundleCodec.decode(bytes))
    }

    @Test
    fun goldenBytesStayStable() {
        val core = com.offlinemesh.core.model.BundleCore(
            BundleId("0102030405060708"), pid(0xAB), pid(0xCD), Kind.TEXT,
            lifetimeMs = 1000, maxHops = 12, initialCopies = 8, nonce = 5, payload = "hi".toByteArray(),
            signature = byteArrayOf(9),
        )
        val hex = BundleCodec.encode(WireBundle(core, hops = 1, ageMs = 2, copies = 3))
            .joinToString("") { "%02x".format(it) }
        assertEquals(GOLDEN, hex)
    }

    @Test
    fun linkMessagesRoundTrip() {
        val ids = listOf(BundleId("0000000000000001"), BundleId("ffffffffffffffff"), BundleId("0123456789abcdef"))
        val msgs: List<LinkMsg> = listOf(
            LinkMsg.Hello(1, pid(5)),
            LinkMsg.Summary(ids),
            LinkMsg.Summary(emptyList()),
            LinkMsg.Offer(listOf(OfferItem(ids[0], Kind.SOS, null, 120, 5_000), OfferItem(ids[1], Kind.IMAGE, pid(8), 50_000, 99))),
            LinkMsg.Request(ids.take(2)),
            LinkMsg.Done,
        )
        for (m in msgs) {
            val bytes = LinkCodec.encode(m)
            assertEquals(m, LinkCodec.decode(bytes))
            for (len in 0 until bytes.size) assertNull(LinkCodec.decode(bytes.copyOf(len)), "$m len=$len")
        }
        val w = wire()
        val back = LinkCodec.decode(LinkCodec.encode(LinkMsg.BundleMsg(w))) as LinkMsg.BundleMsg
        assertEquals(w.core.id, back.wire.core.id)
    }

    @Test
    fun randomGarbageNeverThrows() {
        val rng = Random(42)
        repeat(3000) {
            val b = ByteArray(rng.nextInt(0, 80)).also { rng.nextBytes(it) }
            LinkCodec.decode(b)
            BundleCodec.decode(b)
        }
        // Chuỗi bắt đầu bằng đúng byte loại để đi sâu vào bộ phân tích
        repeat(3000) {
            val b = ByteArray(rng.nextInt(1, 80)).also { rng.nextBytes(it) }
            b[0] = (1 + rng.nextInt(6)).toByte()
            LinkCodec.decode(b)
        }
        assertTrue(true)
    }

    private companion object {
        const val GOLDEN = "0201030000000000000002020c0800000000000003e800000000000000050801020304050607080800000000000000ab0800000000000000cd00000000026869000109"
    }
}
