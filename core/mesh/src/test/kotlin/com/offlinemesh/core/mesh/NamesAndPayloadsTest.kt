package com.offlinemesh.core.mesh

import com.offlinemesh.core.mesh.crypto.AddResult
import com.offlinemesh.core.mesh.crypto.ContactCard
import com.offlinemesh.core.mesh.crypto.DisplayName
import com.offlinemesh.core.mesh.crypto.Identity
import com.offlinemesh.core.mesh.crypto.InMemoryContactBook
import com.offlinemesh.core.mesh.crypto.Sha256
import com.offlinemesh.core.mesh.payload.Encoded
import com.offlinemesh.core.mesh.payload.GeoMath
import com.offlinemesh.core.mesh.payload.GeoPoint
import com.offlinemesh.core.mesh.payload.ImageCompressor
import com.offlinemesh.core.mesh.payload.ImagePayload
import com.offlinemesh.core.mesh.payload.ImageTier
import com.offlinemesh.core.mesh.payload.SosPayload
import com.offlinemesh.core.mesh.payload.SosType
import java.util.Base64
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NamesAndPayloadsTest {
    private fun deg(lat: Double, lon: Double, acc: Int? = null) =
        GeoPoint(Math.round(lat * 1e6).toInt(), Math.round(lon * 1e6).toInt(), acc)

    // ---------- làm sạch tên ----------
    @Test
    fun cleaningRemovesSpoofingCharactersButKeepsVietnameseAndEmoji() {
        assertEquals("evil", DisplayName.clean("\u202Eevil"))                       // đảo chiều chữ
        assertEquals("Mẹ", DisplayName.clean("M\u200Bẹ"))                           // ký tự rộng 0
        assertEquals("a b", DisplayName.clean("a\n\tb\u0000"))
        assertEquals("Tầng 2 Có 3 người", DisplayName.clean("Tầng 2\nCó 3 người"))
        assertEquals("a b", DisplayName.clean("  a \u00A0 \u2003 b  "))             // gộp mọi loại khoảng trắng
        assertEquals("Nguyễn Thị Hồng", DisplayName.clean("Nguyễn Thị Hồng"))
        assertEquals("Bé 🙂", DisplayName.clean("Bé 🙂"))
        assertEquals("", DisplayName.clean("\u202E\u200B\n"))
        val long = DisplayName.clean("ẹ".repeat(20))                                // mỗi chữ 3 byte
        assertEquals(8, long.length)
        assertTrue(long.toByteArray().size <= 24)
        assertTrue(DisplayName.clean("x".repeat(100)).length == 24)
    }

    private fun rawCard(nameBytes: ByteArray, id: Identity): String {
        val body = byteArrayOf(1, nameBytes.size.toByte()) + nameBytes + id.signPub + id.encPub
        val chk = Sha256.hash("OfflineMesh/card/v1".toByteArray(), body).copyOf(4)
        return "OM1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(body + chk)
    }

    @Test
    fun cardsFromOthersAreSanitizedOnDecode() {
        val id = Identity.generate()
        val evil = assertNotNull(ContactCard.decode(rawCard("\u202EMẹ\n\u0000".toByteArray(), id)))
        assertEquals("Mẹ", evil.name)
        val empty = assertNotNull(ContactCard.decode(rawCard("\u202E\n".toByteArray(), id)))
        assertEquals("Máy ${id.peerId.hex.take(4)}", empty.name)                    // tên rỗng dùng tên theo ID
        val own = ContactCard("A\u202EB", id.signPub, id.encPub).encode()           // thẻ của chính mình cũng được làm sạch
        assertEquals("AB", ContactCard.decode(own)!!.name)
    }

    // ---------- đổi tên ----------
    @Test
    fun rescanWithAnotherNameAsksInsteadOfOverwritingAndRenameKeepsKeys() {
        val me = Identity.generate(); val a = Identity.generate()
        val book = InMemoryContactBook()
        assertEquals(AddResult.Added, book.add(ContactCard("A cũ", a.signPub, a.encPub), me.peerId))
        val r = book.add(ContactCard("A mới", a.signPub, a.encPub), me.peerId)
        assertEquals(AddResult.NameDiffers("A cũ", "A mới"), r)
        assertEquals("A cũ", book.byPeer(a.peerId)!!.name)                          // chưa đổi khi chưa được xác nhận
        assertTrue(book.rename(a.peerId, "A mới\u202E"))
        val after = book.byPeer(a.peerId)!!
        assertEquals("A mới", after.name)                                           // đổi và đã làm sạch
        assertContentEquals(a.signPub, after.signPub)                               // khóa không đổi
        assertContentEquals(a.encPub, after.encPub)
        assertTrue(book.isPinned(a.peerId, a.signPub))
        assertEquals(AddResult.AlreadyKnown, book.add(ContactCard("A mới", a.signPub, a.encPub), me.peerId))
        assertFalse(book.rename(Identity.generate().peerId, "x"))
        assertFalse(book.rename(a.peerId, "\u200B\u202E"))                          // tên rỗng sau khi làm sạch: từ chối, giữ tên cũ
        assertEquals("A mới", book.byPeer(a.peerId)!!.name)
    }

    @Test
    fun differentKeysWithTheSamePeerIdOrNameAreStillFlagged() {
        val me = Identity.generate(); val a = Identity.generate(); val b = Identity.generate()
        val book = InMemoryContactBook()
        book.add(ContactCard("Mẹ", a.signPub, a.encPub), me.peerId)
        assertTrue(book.nameTaken("Mẹ"))                                            // thêm người mới trùng tên: cần cảnh báo
        assertFalse(book.nameTaken("Mẹ", except = a.peerId))
        assertEquals(AddResult.Added, book.add(ContactCard("Mẹ", b.signPub, b.encPub), me.peerId))
        assertEquals(2, book.all().size)
    }

    // ---------- SOS ----------
    @Test
    fun sosRoundTripWithAndWithoutPosition() {
        val pos = deg(21.0278, 105.8342, 12)
        val withPos = SosPayload(SosType.TRAPPED, pos, 95, "Mắc kẹt tầng 2, có 3 người")
        val back = assertNotNull(SosPayload.decode(withPos.encode()))
        assertEquals(SosType.TRAPPED, back.type)
        assertEquals(21_027_800, back.position!!.latE6); assertEquals(105_834_200, back.position!!.lonE6)
        assertEquals(12, back.position!!.accuracyM)
        assertEquals(95, back.fixAgeSec)
        assertEquals("Mắc kẹt tầng 2, có 3 người", back.text)
        val noPos = assertNotNull(SosPayload.decode(SosPayload(SosType.SAFE, null, 0, "Tôi an toàn").encode()))
        assertNull(noPos.position)
        assertEquals(16 + "Tôi an toàn".toByteArray().size, SosPayload(SosType.SAFE, null, 0, "Tôi an toàn").encode().size)
        assertNull(SosPayload.decode(SosPayload(SosType.SAFE, GeoPoint(0, 0, null), 0, "").encode())!!.position?.accuracyM)
    }

    @Test
    fun sosClampsSanitizesAndRejectsGarbage() {
        val big = SosPayload(SosType.NEED_RESCUE, GeoPoint(1, 1, 99_999), 1_000_000, "x".repeat(500) + "\u202E").encode()
        val back = SosPayload.decode(big)!!
        assertEquals(140, back.text.length)
        assertNull(back.position!!.accuracyM)                                        // 65535 trở lên được coi là không rõ
        assertEquals(65535, back.fixAgeSec)
        assertTrue(big.size <= 16 + 140)
        val ok = SosPayload(SosType.INJURED, GeoPoint(5, 5), 1, "ok").encode()
        for (len in 0 until ok.size) assertNull(SosPayload.decode(ok.copyOf(len)), "len=$len")
        assertNull(SosPayload.decode(ok + byteArrayOf(0)))
        assertNull(SosPayload.decode(ok.copyOf().also { it[0] = 9 }))               // phiên bản lạ
        assertNull(SosPayload.decode(ok.copyOf().also { it[1] = 77 }))              // loại lạ
        assertNull(SosPayload.decode(ok.copyOf().also { it[3] = 0x7F }))            // vĩ độ ngoài khoảng
        val rng = Random(4)
        repeat(3000) { SosPayload.decode(ByteArray(rng.nextInt(0, 40)).also { b -> rng.nextBytes(b) }) }
    }

    // ---------- khoảng cách và hướng ----------
    @Test
    fun distanceAndBearingMatchKnownValues() {
        val o = GeoPoint(0, 0)
        assertTrue(abs(GeoMath.distanceM(o, GeoPoint(0, 1_000_000)) - 111_194.9) < 5)
        assertTrue(abs(GeoMath.bearingDeg(o, GeoPoint(0, 1_000_000)) - 90.0) < 0.01)
        assertTrue(abs(GeoMath.bearingDeg(o, GeoPoint(1_000_000, 0)) - 0.0) < 0.01)
        assertTrue(abs(GeoMath.bearingDeg(o, GeoPoint(-1_000_000, 0)) - 180.0) < 0.01)
        assertTrue(abs(GeoMath.bearingDeg(o, GeoPoint(0, -1_000_000)) - 270.0) < 0.01)
        val hn = deg(21.0278, 105.8342); val hcm = deg(10.8231, 106.6297)
        assertTrue(abs(GeoMath.distanceM(hn, hcm) - HN_HCM_M) < 1_000, "d=${GeoMath.distanceM(hn, hcm)}")
        assertEquals("Nam", GeoMath.compass(GeoMath.bearingDeg(hn, hcm)))
        assertEquals(listOf("Bắc", "Đông Bắc", "Đông", "Đông Nam", "Nam", "Tây Nam", "Tây", "Tây Bắc", "Bắc"),
            listOf(0.0, 45.0, 90.0, 135.0, 180.0, 225.0, 270.0, 315.0, 359.0).map { GeoMath.compass(it) })
        assertEquals("850 m", GeoMath.formatDistance(850.2)); assertEquals("2.3 km", GeoMath.formatDistance(2_340.0))
    }

    // ---------- ảnh ----------
    private val jpegHead = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())

    @Test
    fun imagePayloadRoundTripAndValidation() {
        val img = ImagePayload(320, 240, jpegHead + ByteArray(100))
        val back = assertNotNull(ImagePayload.decode(img.encode()))
        assertEquals(320, back.width); assertEquals(240, back.height)
        assertContentEquals(img.jpeg, back.jpeg)
        assertNull(ImagePayload.decode(ImagePayload(320, 240, ByteArray(50)).encode()))          // không phải JPEG
        assertNull(ImagePayload.decode(ImagePayload(0, 240, jpegHead + ByteArray(10)).encode()))
        assertNull(ImagePayload.decode(ImagePayload(9000, 240, jpegHead + ByteArray(10)).encode()))
        assertNull(ImagePayload.decode(img.encode().copyOf(8)))
        assertNull(ImagePayload.decode(img.encode().also { it[1] = 5 }))
    }

    /** Bộ mã hóa giả: kích thước ≈ density × cạnh² × chất lượng. */
    private fun fake(density: Double, calls: IntArray = IntArray(1)) = com.offlinemesh.core.mesh.payload.ImageEncoder { edge, q ->
        calls[0]++
        Encoded(ByteArray((density * edge * edge * q / 100.0).toInt().coerceAtLeast(4)), edge, edge * 3 / 4)
    }

    @Test
    fun compressorAlwaysFitsPrefersBigEdgesAndUsesFewEncodes() {
        for (tier in ImageTier.values()) for (density in listOf(0.02, 0.1, 0.3, 0.6)) {
            val calls = IntArray(1)
            val r = ImageCompressor.compress(fake(density, calls), tier)
            val r2 = assertNotNull(r, "tier=$tier density=$density")
            assertTrue(r2.image.bytes.size <= tier.maxBytes)
            assertTrue(calls[0] <= tier.edges.size * 4, "calls=${calls[0]}")
                        assertTrue(r2.edgePx in tier.edges)
        }
        // ảnh đơn giản (ít chi tiết) giữ được cạnh lớn nhất; ảnh phức tạp phải thu nhỏ
        assertEquals(640, ImageCompressor.compress(fake(0.02), ImageTier.NORMAL)!!.edgePx)
        assertTrue(ImageCompressor.compress(fake(0.6), ImageTier.NORMAL)!!.edgePx < 640)
        assertTrue(ImageCompressor.compress(fake(0.3), ImageTier.HIGH)!!.edgePx >= ImageCompressor.compress(fake(0.3), ImageTier.SMALL)!!.edgePx)
        // chất lượng không tụt dưới 45 trừ ở cạnh nhỏ nhất
        val r = ImageCompressor.compress(fake(0.3), ImageTier.NORMAL)!!
        assertTrue(r.quality >= 45 || r.edgePx == ImageTier.NORMAL.edges.last())
    }

    @Test
    fun compressorGivesUpCleanly() {
        assertNull(ImageCompressor.compress({ _, _ -> Encoded(ByteArray(1_000_000), 10, 10) }, ImageTier.NORMAL))
        assertNull(ImageCompressor.compress({ _, _ -> null }, ImageTier.NORMAL))
        assertTrue(ImageTier.SMALL.maxBytes < ImageTier.NORMAL.maxBytes && ImageTier.NORMAL.maxBytes < ImageTier.HIGH.maxBytes)
    }

    private companion object {
        const val HN_HCM_M = 1_137_884.0
    }
}
