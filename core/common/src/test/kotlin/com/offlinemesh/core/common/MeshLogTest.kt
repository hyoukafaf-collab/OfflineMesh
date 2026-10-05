package com.offlinemesh.core.common

import com.offlinemesh.core.model.BundleId
import com.offlinemesh.core.model.PeerId
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MeshLogTest {
    private val b1 = BundleId("aaaaaaaa11")
    private val b2 = BundleId("bbbbbbbb22")

    @Test
    fun ringBufferKeepsNewest() {
        val ring = RingBufferSink(3)
        val log = MeshLog(FakeClock(), listOf(ring))
        repeat(5) { log.log(Level.INFO, Layer.ROUTING, "e$it") }
        assertEquals(listOf("e2", "e3", "e4"), ring.snapshot().map { it.event })
    }

    @Test
    fun filterByBundleRebuildsJourney() {
        val clock = FakeClock(100)
        val ring = RingBufferSink()
        val log = MeshLog(clock, listOf(ring))
        log.log(Level.INFO, Layer.ROUTING, "created", bundle = b1)
        clock.advance(5)
        log.log(Level.INFO, Layer.ROUTING, "created", bundle = b2)
        log.log(Level.INFO, Layer.TRANSPORT, "sent", bundle = b1, peer = PeerId("cccccccc33"))
        assertEquals(listOf("created", "sent"), ring.forBundle(b1).map { it.event })
        val text = ring.exportText()
        assertTrue(text.contains("bundle=aaaaaaaa"))
        assertTrue(text.contains("peer=cccccccc"))
        assertTrue(text.startsWith("100 INFO ROUTING created"))
    }

    @Test
    fun fileSinkRotates() {
        val dir = Files.createTempDirectory("meshlog").toFile()
        val f = File(dir, "mesh.log")
        val log = MeshLog(FakeClock(), listOf(FileSink(f, maxBytes = 200)))
        repeat(20) { log.log(Level.INFO, Layer.CACHE, "evict_$it", detail = "x".repeat(20)) }
        assertTrue(File(dir, "mesh.log.1").exists())
        assertTrue(f.length() < 400)
        dir.deleteRecursively()
    }
}
