package com.offlinemesh.core.mesh

import com.offlinemesh.core.mesh.codec.Framing
import com.offlinemesh.core.mesh.codec.Reassembler
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FramingTest {
    private val rng = Random(7)

    @Test
    fun splitsAndReassemblesAnySizeInAnyOrderWithDuplicates() {
        for (size in listOf(0, 1, 237, 238, 239, 5000)) {
            val data = ByteArray(size).also { rng.nextBytes(it) }
            val frames = Framing.split(77, data, 244)
            assertTrue(frames.all { it.size <= 244 })
            val shuffled = (frames + frames.take(2)).shuffled(rng)
            val r = Reassembler()
            var out: ByteArray? = null
            for (f in shuffled) r.accept(f)?.let { out = it }
            assertContentEquals(data, out, "size=$size")
        }
    }

    @Test
    fun incompleteMessageYieldsNothing() {
        val frames = Framing.split(1, ByteArray(1000), 100)
        val r = Reassembler()
        frames.dropLast(1).forEach { assertNull(r.accept(it)) }
        assertEquals(frames.size, 11)
    }

    @Test
    fun interleavedMessagesStayApart() {
        val a = ByteArray(600) { 1 }
        val b = ByteArray(600) { 2 }
        val fa = Framing.split(1, a, 100)
        val fb = Framing.split(2, b, 100)
        val r = Reassembler()
        val got = mutableListOf<ByteArray>()
        for (i in fa.indices) { r.accept(fa[i])?.let { got += it }; r.accept(fb[i])?.let { got += it } }
        assertEquals(2, got.size)
        assertTrue(got.any { it.contentEquals(a) } && got.any { it.contentEquals(b) })
    }

    @Test
    fun oldPartialMessagesAreDroppedToBoundMemory() {
        val r = Reassembler(maxPending = 2)
        for (id in 1..3) r.accept(Framing.split(id, ByteArray(500), 100).first())
        // message 1 đã bị đuổi: khung còn lại của nó không bao giờ ghép xong
        var done = false
        Framing.split(1, ByteArray(500), 100).drop(1).forEach { if (r.accept(it) != null) done = true }
        assertTrue(!done)
    }

    @Test
    fun garbageFramesAreIgnored() {
        val r = Reassembler()
        assertNull(r.accept(ByteArray(3)))
        assertNull(r.accept(byteArrayOf(0, 1, 0, 5, 0, 2, 9)))   // idx >= total
        assertNull(r.accept(byteArrayOf(0, 1, 0, 0, 0, 0)))      // total = 0
    }
}
