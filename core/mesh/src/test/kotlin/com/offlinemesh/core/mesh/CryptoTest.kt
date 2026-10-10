package com.offlinemesh.core.mesh

import com.offlinemesh.core.mesh.crypto.AesGcm
import com.offlinemesh.core.mesh.crypto.ContactCard
import com.offlinemesh.core.mesh.crypto.Hkdf
import com.offlinemesh.core.mesh.crypto.Identity
import com.offlinemesh.core.mesh.crypto.P256
import com.offlinemesh.core.mesh.crypto.SealedBox
import com.offlinemesh.core.mesh.crypto.AddResult
import com.offlinemesh.core.mesh.crypto.InMemoryContactBook
import com.offlinemesh.core.model.hexToBytes
import com.offlinemesh.core.model.toHex
import java.security.SecureRandom
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CryptoTest {
    // ---------- vector chuẩn công bố ----------
    @Test
    fun hkdfMatchesRfc5869Vectors() {
        val tc1 = Hkdf.derive("000102030405060708090a0b0c".hexToBytes(), ByteArray(22) { 0x0b }, "f0f1f2f3f4f5f6f7f8f9".hexToBytes(), 42)
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", tc1.toHex())
        val tc3 = Hkdf.derive(ByteArray(0), ByteArray(22) { 0x0b }, ByteArray(0), 42)
        assertEquals("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8", tc3.toHex())
    }

    @Test
    fun aesGcmMatchesNistVectorsAndDetectsTampering() {
        val c14 = AesGcm.encrypt(ByteArray(32), ByteArray(12), ByteArray(0), ByteArray(16))
        assertEquals("cea7403d4d606b6e074ec5d3baf39d18" + "d0d1c8a799996bf0265b98b5d48ab919", c14.toHex())
        val k = "feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308".hexToBytes()
        val iv = "cafebabefacedbaddecaf888".hexToBytes()
        val p = ("d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a72" +
            "1c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39").hexToBytes()
        val a = "feedfacedeadbeeffeedfacedeadbeefabaddad2".hexToBytes()
        val c16 = AesGcm.encrypt(k, iv, a, p)
        assertEquals(("522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa" +
            "8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662" + "76fc6ece0f4e1768cddf8853bb2d551b"), c16.toHex())
        assertContentEquals(p, AesGcm.decrypt(k, iv, a, c16))
        for (i in c16.indices) assertNull(AesGcm.decrypt(k, iv, a, c16.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }), "byte $i")
        assertNull(AesGcm.decrypt(k, iv, a + 0, c16))                        // AAD sai
    }

    // ---------- P-256 ----------
    @Test
    fun compressedPointsRoundTripAndBadInputsAreRejected() {
        repeat(100) {
            val kp = P256.generate()
            val pt = assertNotNull(P256.decompress(kp.pub))
            assertContentEquals(kp.pub, P256.compress(pt))
        }
        val good = P256.generate().pub
        assertNull(P256.decompress(good.copyOf(32)))
        assertNull(P256.decompress(good.copyOf().also { it[0] = 4 }))
        assertNull(P256.decompress(byteArrayOf(2) + ByteArray(32) { 0xFF.toByte() }))   // x >= p
        var offCurve = 0
        for (x in 1..60) if (P256.decompress(byteArrayOf(2) + ByteArray(31) + byteArrayOf(x.toByte())) == null) offCurve++
        assertTrue(offCurve in 10..50, "khoảng một nửa x không có điểm tương ứng: $offCurve")   // x không hợp lệ bị từ chối
    }

    @Test
    fun ecdhIsSymmetricAndRejectsInvalidPublicKeys() {
        repeat(20) {
            val a = P256.generate(); val b = P256.generate()
            assertContentEquals(P256.ecdh(a.priv, b.pub), P256.ecdh(b.priv, a.pub))
        }
        val a = P256.generate()
        assertFailsWith<IllegalArgumentException> { P256.ecdh(a.priv, ByteArray(33)) }
    }

    @Test
    fun ecdsaSignsVerifiesAndRejectsAnyTampering() {
        val kp = P256.generate()
        val msg = "tin nhắn thử nghiệm".toByteArray()
        val sig = P256.sign(kp.priv, msg)
        assertEquals(64, sig.size)
        assertTrue(P256.verify(kp.pub, msg, sig))
        for (i in sig.indices) assertFalse(P256.verify(kp.pub, msg, sig.copyOf().also { it[i] = (it[i].toInt() xor 0x40).toByte() }), "sig byte $i")
        for (i in msg.indices) assertFalse(P256.verify(kp.pub, msg.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }, sig), "msg byte $i")
        assertFalse(P256.verify(P256.generate().pub, msg, sig))
        assertFalse(P256.verify(kp.pub, msg, sig.copyOf(63)))
        assertFalse(P256.verify(kp.pub, msg, ByteArray(64)))
        assertFalse(P256.verify(ByteArray(33), msg, sig))
        repeat(30) { assertTrue(P256.verify(kp.pub, msg, P256.sign(kp.priv, msg))) }   // chữ ký ngẫu hóa nhưng luôn hợp lệ
    }

    // ---------- SealedBox ----------
    private val src = "0102030405aa".hexToBytes()
    private val dst = "0102030405bb".hexToBytes()
    private val aad = SealedBox.aad(2, src, dst)

    @Test
    fun sealOpenRoundTripAtManySizes() {
        val r = P256.generate()
        for (n in listOf(0, 1, 15, 16, 17, 100, 5000)) {
            val pt = ByteArray(n).also { Random(n).nextBytes(it) }
            val blob = SealedBox.seal(r.pub, pt, aad)
            assertEquals(n + SealedBox.OVERHEAD, blob.size)
            assertContentEquals(pt, SealedBox.open(r.priv, r.pub, blob, aad), "n=$n")
        }
    }

    @Test
    fun onlyTheRightRecipientAndTheRightContextCanOpen() {
        val r = P256.generate(); val other = P256.generate()
        val blob = SealedBox.seal(r.pub, "bí mật".toByteArray(), aad)
        assertNull(SealedBox.open(other.priv, other.pub, blob, aad))
        assertNull(SealedBox.open(r.priv, r.pub, blob, SealedBox.aad(2, dst, src)))      // đổi người gửi/nhận
        assertNull(SealedBox.open(r.priv, r.pub, blob, SealedBox.aad(3, src, dst)))      // đổi loại tin
    }

    @Test
    fun anyChangeToTheCiphertextIsDetected() {
        val r = P256.generate()
        val blob = SealedBox.seal(r.pub, "toàn vẹn".toByteArray(), aad)
        for (i in blob.indices) assertNull(SealedBox.open(r.priv, r.pub, blob.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }, aad), "byte $i")
        for (len in 0 until blob.size) assertNull(SealedBox.open(r.priv, r.pub, blob.copyOf(len), aad), "len=$len")
        val rng = Random(9)
        repeat(300) { SealedBox.open(r.priv, r.pub, ByteArray(rng.nextInt(0, 120)).also { rng.nextBytes(it) }, aad) }
    }

    @Test
    fun everyMessageUsesAFreshEphemeralKeyAndHidesThePlaintext() {
        val r = P256.generate()
        val pt = ByteArray(200) { 0x41 }                                               // toàn chữ 'A'
        val a = SealedBox.seal(r.pub, pt, aad); val b = SealedBox.seal(r.pub, pt, aad)
        assertNotEquals(a.toHex(), b.toHex())
        assertNotEquals(a.copyOfRange(1, 34).toHex(), b.copyOfRange(1, 34).toHex())    // khóa tạm khác nhau
        assertTrue(a.copyOfRange(34, a.size).toSet().size > 50)                        // không lộ cấu trúc lặp
    }

    // ---------- danh tính, thẻ liên hệ ----------
    @Test
    fun identityPersistsAndGarbageIsRejected() {
        val id = Identity.generate()
        val back = assertNotNull(Identity.deserialize(id.serialize()))
        assertEquals(id.peerId, back.peerId)
        assertContentEquals(id.encPriv, back.encPriv)
        assertEquals(12, id.peerId.hex.length)
        assertNull(Identity.deserialize(ByteArray(131)))
        assertNull(Identity.deserialize(id.serialize().copyOf(130)))
        assertNotEquals(Identity.generate().peerId, id.peerId)
    }

    @Test
    fun contactCardRoundTripFingerprintAndCorruption() {
        val id = Identity.generate()
        val card = ContactCard("Mẹ 👩", id.signPub, id.encPub)
        val text = card.encode()
        assertTrue(text.startsWith("OM1:"))
        val back = assertNotNull(ContactCard.decode(" $text \n"))
        assertEquals("Mẹ 👩", back.name)
        assertTrue(back.sameKeysAs(card))
        assertEquals(id.peerId, back.peerId)
        assertEquals(card.fingerprint(), back.fingerprint())
        assertTrue(Regex("([0-9A-F]{4} ){4}[0-9A-F]{4}").matches(card.fingerprint()))
        val other = Identity.generate()
        assertNotEquals(card.fingerprint(), ContactCard("Mẹ 👩", other.signPub, other.encPub).fingerprint())
        for (i in 4 until text.length step 7) {                                        // sửa một ký tự bất kỳ phải bị bắt
            val c = if (text[i] == 'A') 'B' else 'A'
            assertNull(ContactCard.decode(text.substring(0, i) + c + text.substring(i + 1)), "pos $i")
        }
        assertNull(ContactCard.decode(text.substring(4)))
        assertNull(ContactCard.decode("OM1:"))
        assertNull(ContactCard.decode(ContactCard("x", ByteArray(33), id.encPub).encode()))   // khóa không hợp lệ
    }

    @Test
    fun contactBookPinsFullKeysNotJustPeerIds() {
        val me = Identity.generate(); val a = Identity.generate()
        val book = InMemoryContactBook()
        val card = ContactCard("A", a.signPub, a.encPub)
        assertEquals(AddResult.Added, book.add(card, me.peerId))
        assertEquals(AddResult.AlreadyKnown, book.add(card, me.peerId))
        assertEquals(AddResult.IsSelf, book.add(ContactCard("tôi", me.signPub, me.encPub), me.peerId))
        assertTrue(book.isPinned(a.peerId, a.signPub))
        assertFalse(book.isPinned(a.peerId, Identity.generate().signPub))              // cùng PeerId nhưng khóa khác: không tin
        book.remove(a.peerId)
        assertNull(book.byPeer(a.peerId))
    }
}
