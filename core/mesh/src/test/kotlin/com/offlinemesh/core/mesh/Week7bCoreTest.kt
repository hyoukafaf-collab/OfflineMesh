package com.offlinemesh.core.mesh

import com.offlinemesh.core.mesh.backup.BackupCodec
import com.offlinemesh.core.mesh.backup.BackupContent
import com.offlinemesh.core.mesh.backup.BackupEntry
import com.offlinemesh.core.mesh.backup.BackupResult
import com.offlinemesh.core.mesh.backup.PrefItem
import com.offlinemesh.core.mesh.backup.PlanResult
import com.offlinemesh.core.mesh.backup.PrefsText
import com.offlinemesh.core.mesh.backup.RestorePlanner
import com.offlinemesh.core.mesh.crypto.ContactCard
import com.offlinemesh.core.mesh.crypto.Identity
import com.offlinemesh.core.mesh.history.MessageCodec
import com.offlinemesh.core.mesh.history.StoredMessage
import com.offlinemesh.core.mesh.payload.ImagePayload
import com.offlinemesh.core.mesh.payload.SosFeed
import com.offlinemesh.core.mesh.payload.SosType
import com.offlinemesh.core.model.PeerId
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Week7bCoreTest {
    private val jpegHead = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())

    // ---------- ảnh SOS (bản 2) ----------
    @Test
    fun normalImagesKeepTheOldLayoutAndSosImagesCarryAFlag() {
        val normal = ImagePayload(320, 240, jpegHead + ByteArray(20)).encode()
        assertEquals(1, normal[0].toInt())                                  // bản 1 giữ nguyên để máy cũ vẫn đọc được
        assertEquals(6 + 24, normal.size)
        assertFalse(assertNotNull(ImagePayload.decode(normal)).sos)

        val sos = ImagePayload(320, 240, jpegHead + ByteArray(20), sos = true).encode()
        assertEquals(2, sos[0].toInt())
        assertEquals(7 + 24, sos.size)
        val back = assertNotNull(ImagePayload.decode(sos))
        assertTrue(back.sos)
        assertEquals(320, back.width); assertEquals(240, back.height)
        assertContentEquals(jpegHead + ByteArray(20), back.jpeg)
    }

    @Test
    fun imageDecodeRejectsUnknownVersionsTruncationAndNonJpeg() {
        val sos = ImagePayload(100, 100, jpegHead + ByteArray(20), sos = true).encode()
        assertNull(ImagePayload.decode(sos.also { it[0] = 3 }.copyOf()))
        assertNull(ImagePayload.decode(ImagePayload(100, 100, jpegHead + ByteArray(20), sos = true).encode().copyOf(9)))
        assertNull(ImagePayload.decode(ImagePayload(100, 100, ByteArray(30), sos = true).encode()))
        assertNull(ImagePayload.decode(ByteArray(1)))
        assertNull(ImagePayload.decode(ByteArray(0)))
    }

    // ---------- SOS của chính mình hiện trong danh sách ----------
    private fun own(id: String, t: Long, type: SosType, session: Int, seq: Int, text: String = "") =
        StoredMessage(id, "SOS", true, "SOS", text, null, t, sosType = type.code, session = session, seq = seq)

    @Test
    fun ownSosMessagesBecomeFeedEntriesOnlyWhenTheSelfIdIsKnown() {
        val m = own("a1", 100, SosType.TRAPPED, 7, 0, "kẹt")
        assertNull(m.toSosEntry())                                         // tin gửi đi không lưu mã người gửi
        val self = "aabbccddeeff"
        val e = assertNotNull(m.toSosEntry(self))
        assertEquals(PeerId(self), e.src)
        assertEquals(7, e.session)
        // các lần phát lại của cùng một SOS gộp thành một mục; "Tôi an toàn" sau đó đánh dấu đã giải quyết
        val all = listOf(
            own("a1", 100, SosType.TRAPPED, 7, 0), own("a2", 200, SosType.TRAPPED, 7, 1), own("a3", 300, SosType.TRAPPED, 7, 2),
            own("a4", 400, SosType.SAFE, 9, 0),
        ).mapNotNull { it.toSosEntry(self) }
        val items = SosFeed.collapse(all)
        assertEquals(2, items.size)
        val trapped = items.first { it.latest.session == 7 }
        assertEquals(2, trapped.latest.seq)
        assertTrue(trapped.resolved)
        // tin nhận từ người khác vẫn giữ nguyên mã người gửi thật, không bị thay bằng selfSrc
        val other = StoredMessage("b1", "SOS", false, "SOS", "x", null, 50, src = "112233445566", sosType = 0, session = 1, seq = 0)
        assertEquals(PeerId("112233445566"), other.toSosEntry(self)!!.src)
    }

    // ---------- văn bản cài đặt ----------
    @Test
    fun prefsTextRoundTripsAwkwardValuesAndAllBasicTypes() {
        val map = mapOf<String, Any?>(
            "name" to "Tên\twith\ttabs\nvà dòng mới \\ gạch",
            "empty" to "", "n" to 42, "big" to 9_000_000_000L, "on" to true, "off" to false, "f" to 1.5f,
            "cards" to setOf("OM1:abc", "OM1:def\tx"), "none" to emptySet<String>(), "blank" to setOf(""),
            "skip" to null, "weird" to listOf(1, 2),
        )
        val items = assertNotNull(PrefsText.decode(PrefsText.encode(map))).associateBy { it.key }
        assertEquals("Tên\twith\ttabs\nvà dòng mới \\ gạch", items["name"]!!.value)
        assertEquals("", items["empty"]!!.value)
        assertEquals(42, items["n"]!!.value); assertEquals(9_000_000_000L, items["big"]!!.value)
        assertEquals(true, items["on"]!!.value); assertEquals(false, items["off"]!!.value); assertEquals(1.5f, items["f"]!!.value)
        assertEquals(setOf("OM1:abc", "OM1:def\tx"), items["cards"]!!.value)
        assertEquals(emptySet<String>(), items["none"]!!.value)
        assertEquals(setOf(""), items["blank"]!!.value)
        assertFalse("skip" in items); assertFalse("weird" in items)              // kiểu lạ và null bị bỏ qua
        assertEquals(emptyList(), PrefsText.decode(ByteArray(0)))
    }

    @Test
    fun prefsTextRejectsMalformedLines() {
        assertNull(PrefsText.decode("x\tkey\tv".toByteArray()))                 // loại lạ
        assertNull(PrefsText.decode("i\tn\tabc".toByteArray()))                 // không phải số
        assertNull(PrefsText.decode("b\tk\tmaybe".toByteArray()))
        assertNull(PrefsText.decode("s\tk".toByteArray()))                      // thiếu giá trị
        assertNull(PrefsText.decode("s\t\tv".toByteArray()))                    // khóa rỗng
        assertNull(PrefsText.decode("s\tk\tv\textra".toByteArray()))
    }

    @Test
    fun onlyAllowListedSettingsOfTheRightTypeAreRestored() {
        val items = listOf(
            PrefItem("theme", 2), PrefItem("terms", true), PrefItem("share_location", true), PrefItem("emergency", true),
            PrefItem("notif_msg", "yes"), PrefItem("replay_min", 120), PrefItem("warn_sos_hidden", true),
        )
        val ok = BackupContent.sanitize(items, BackupContent.SETTINGS_KEYS).map { it.key }.toSet()
        assertEquals(setOf("theme", "replay_min", "warn_sos_hidden"), ok)       // điều khoản, vị trí, khẩn cấp và sai kiểu đều bị loại
        val c = BackupContent.sanitize(listOf(PrefItem("my_name", "An"), PrefItem("cards", setOf("OM1:x")), PrefItem("other", "z")), BackupContent.CONTACTS_KEYS)
        assertEquals(setOf("my_name", "cards"), c.map { it.key }.toSet())
    }

    @Test
    fun imageEntryNamesAreRestrictedToPlainJpegFileNames() {
        assertTrue(BackupContent.imageFileOk("00aa11bb22cc33dd.jpg"))
        assertFalse(BackupContent.imageFileOk("../x.jpg")); assertFalse(BackupContent.imageFileOk("a.png")); assertFalse(BackupContent.imageFileOk(".jpg"))
        assertFalse(BackupContent.imageFileOk("a/b.jpg")); assertFalse(BackupContent.imageFileOk("a b.jpg"))
        assertEquals("00aa.jpg", BackupContent.imageFileOf(BackupContent.imageEntryName("00aa.jpg")))
        assertNull(BackupContent.imageFileOf("images/../evil.jpg")); assertNull(BackupContent.imageFileOf("messages"))
    }

    // ---------- tệp sao lưu mã hóa bằng mật khẩu ----------
    private val fastIter = 1_000          // chỉ để test chạy nhanh; bản thật dùng DEFAULT_ITERATIONS (600.000)
    private val pw = "mật khẩu đủ dài 123".toCharArray()
    private fun entries() = listOf(
        BackupEntry("identity", ByteArray(100) { it.toByte() }),
        BackupEntry("prefs/contacts", "dữ liệu tiếng Việt".toByteArray()),
        BackupEntry("images/00ff.jpg", ByteArray(5000) { (it * 7).toByte() }),
        BackupEntry("empty", ByteArray(0)),
    )

    @Test
    fun backupRoundTripsAndEveryFileIsDifferentBecauseOfRandomSaltAndNonce() {
        val a = BackupCodec.encrypt(entries(), pw, fastIter)
        val b = BackupCodec.encrypt(entries(), pw, fastIter)
        assertFalse(a.contentEquals(b))
        assertEquals("OMB1", String(a, 0, 4))
        val res = assertIs<BackupResult.Ok>(BackupCodec.decrypt(a, pw))
        assertEquals(entries().map { it.name }, res.entries.map { it.name })
        entries().zip(res.entries).forEach { (x, y) -> assertContentEquals(x.data, y.data) }
        assertEquals(0, assertIs<BackupResult.Ok>(BackupCodec.decrypt(BackupCodec.encrypt(emptyList(), pw, fastIter), pw)).entries.size)
    }

    @Test
    fun aWrongPasswordOrAnyChangedByteIsRejected() {
        val file = BackupCodec.encrypt(entries(), pw, fastIter)
        assertIs<BackupResult.WrongPasswordOrDamaged>(BackupCodec.decrypt(file, "sai mật khẩu hoàn toàn".toCharArray()))
        for (pos in listOf(5, 9, 20, 30, 40, file.size / 2, file.size - 1)) {      // số vòng lặp, muối, nonce, thân, thẻ
            val bad = file.copyOf().also { it[pos] = (it[pos].toInt() xor 1).toByte() }
            val r = BackupCodec.decrypt(bad, pw)
            assertTrue(r is BackupResult.WrongPasswordOrDamaged || r is BackupResult.NotABackup, "byte $pos")
        }
        assertIs<BackupResult.WrongPasswordOrDamaged>(BackupCodec.decrypt(file.copyOf(file.size - 1), pw))   // cắt cụt
    }

    @Test
    fun nonBackupFilesAndNewerVersionsAreRecognisedWithoutTryingToDecrypt() {
        assertIs<BackupResult.NotABackup>(BackupCodec.decrypt(ByteArray(0), pw))
        assertIs<BackupResult.NotABackup>(BackupCodec.decrypt("hello world, not a backup at all......".toByteArray(), pw))
        assertIs<BackupResult.UnsupportedVersion>(BackupCodec.decrypt("OMB2".toByteArray() + ByteArray(80), pw))
        assertIs<BackupResult.NotABackup>(BackupCodec.decrypt("OMB1".toByteArray() + ByteArray(10), pw))       // quá ngắn
        val zeroIter = BackupCodec.encrypt(entries(), pw, fastIter).also { it[4] = 0; it[5] = 0; it[6] = 0; it[7] = 0 }
        assertIs<BackupResult.NotABackup>(BackupCodec.decrypt(zeroIter, pw))                                   // 0 vòng lặp bị từ chối
    }

    @Test
    fun entryNamesCannotEscapeTheirFolder() {
        for (bad in listOf("", "../x", "/abs", "a//b", "a/./b", "a/../b", "a b", "tên-có-dấu", ".", "..", "a/", "x".repeat(129)))
            assertFalse(BackupCodec.validName(bad), "\"$bad\"")
        for (ok in listOf("identity", "prefs/contacts", "images/00ff.jpg", "a.b-c_d/e")) assertTrue(BackupCodec.validName(ok), ok)
        assertFailsWith<IllegalArgumentException> { BackupCodec.encrypt(listOf(BackupEntry("../x", ByteArray(1))), pw, fastIter) }
        assertFailsWith<IllegalArgumentException> { BackupCodec.encrypt(listOf(BackupEntry("a", ByteArray(1)), BackupEntry("a", ByteArray(2))), pw, fastIter) }
        assertFailsWith<IllegalArgumentException> { BackupCodec.encrypt(entries(), pw, 0) }
    }

    @Test
    fun thePasswordIsCheckedForLengthAndNormalisedSoComposedAndDecomposedAccentsMatch() {
        assertNotNull(BackupCodec.checkPassword("ngắn".toCharArray()))
        assertNull(BackupCodec.checkPassword("đủ tám ký tự".toCharArray()))
        val composed = "mật khẩu 1234".toCharArray()                                      // ậ, ẩ dựng sẵn
        val decomposed = java.text.Normalizer.normalize(String(composed), java.text.Normalizer.Form.NFD).toCharArray()
        assertFalse(composed.contentEquals(decomposed))
        val file = BackupCodec.encrypt(entries(), composed, fastIter)
        assertIs<BackupResult.Ok>(BackupCodec.decrypt(file, decomposed))                          // gõ trên bàn phím khác vẫn mở được
    }

    @Test
    fun aLargeBackupRoundTrips() {
        val big = ByteArray(3 * 1024 * 1024).also { SecureRandom().nextBytes(it) }
        val file = BackupCodec.encrypt(listOf(BackupEntry("messages", big)), pw, fastIter)
        val r = assertIs<BackupResult.Ok>(BackupCodec.decrypt(file, pw))
        assertContentEquals(big, r.entries.single().data)
    }

    // ---------- kế hoạch khôi phục (kiểm tra nội dung tệp sao lưu trước khi ghi) ----------
    private fun msgLine(id: String, image: String?) = MessageCodec.encode(StoredMessage(id, "PUBLIC", false, if (image == null) "TEXT" else "IMAGE", "xin chào", image, 1000, src = "112233445566"))

    private fun planEntries(
        identity: Identity? = Identity.generate(), cards: Set<String> = emptySet(), settings: Map<String, Any?> = emptyMap(),
        messages: List<String> = emptyList(), extra: List<BackupEntry> = emptyList(),
    ): List<BackupEntry> {
        val out = ArrayList<BackupEntry>()
        if (identity != null) out += BackupEntry(BackupContent.IDENTITY, identity.serialize())
        out += BackupEntry(BackupContent.CONTACTS, PrefsText.encode(mapOf("my_name" to "Lan", "cards" to cards, "evil" to "x")))
        out += BackupEntry(BackupContent.SETTINGS, PrefsText.encode(settings))
        out += BackupEntry(BackupContent.MESSAGES, messages.joinToString("\n").toByteArray())
        return out + extra
    }

    private fun ok(r: PlanResult) = assertIs<PlanResult.Ok>(r, (r as? PlanResult.Bad)?.reason).plan

    @Test
    fun planKeepsGoodDataAndDropsEverythingNotOnTheAllowList() {
        val id = Identity.generate()
        val other = Identity.generate()
        val card = ContactCard("Bao", other.signPub, other.encPub).encode()
        val plan = ok(RestorePlanner.plan(planEntries(
            identity = id, cards = setOf(card, "OM1:khong-hop-le"),
            settings = mapOf("theme" to 2, "terms" to true, "share_location" to true, "emergency" to true, "retention_days" to "90"),
            messages = listOf(msgLine("a1", null), msgLine("a2", "abc123.jpg")),
            extra = listOf(BackupEntry("images/abc123.jpg", jpegHead + ByteArray(50))),
        )))
        assertEquals(id.peerId, plan.identity.peerId)
        assertEquals(1, plan.cardCount)                                   // thẻ hỏng bị bỏ, thẻ tốt còn
        assertEquals(listOf("theme"), plan.settings.map { it.key })        // điều khoản/vị trí/khẩn cấp không được khôi phục; retention_days sai kiểu (chuỗi) bị bỏ
        assertEquals(2, plan.settings.single().value)
        assertEquals(listOf("cards", "my_name"), plan.contacts.map { it.key }.sorted())   // khóa lạ "evil" bị bỏ
        assertEquals(2, plan.messageCount)
        assertEquals(listOf("abc123.jpg"), plan.images.map { it.first })
    }

    @Test
    fun planDropsMessagesWithPathTraversalImageNames() {
        val plan = ok(RestorePlanner.plan(planEntries(messages = listOf(msgLine("ok1", "abc.jpg"), msgLine("bad1", "../../databases/x.jpg"), msgLine("bad2", "a/b.jpg"), msgLine("bad3", "x.png")))))
        assertEquals(1, plan.messageCount)
        assertTrue(String(plan.messages).contains("ok1"))
        assertFalse(String(plan.messages).contains("bad"))
    }

    @Test
    fun planOnlyAcceptsWellFormedJpegImagesOfReasonableSize() {
        val plan = ok(RestorePlanner.plan(planEntries(extra = listOf(
            BackupEntry("images/aa.jpg", jpegHead + ByteArray(10)),
            BackupEntry("images/bb.jpg", ByteArray(100)),                                         // không phải JPEG
            BackupEntry("images/cc.png", jpegHead + ByteArray(10)),                               // sai đuôi
            BackupEntry("images/dd.jpg", jpegHead + ByteArray(RestorePlanner.MAX_IMAGE_BYTES)),   // quá lớn
            BackupEntry("images/sub/ee.jpg", jpegHead + ByteArray(10)),                           // thư mục con
            BackupEntry("other/ff.jpg", jpegHead + ByteArray(10)),                                // ngoài thư mục ảnh
        ))))
        assertEquals(listOf("aa.jpg"), plan.images.map { it.first })
    }

    @Test
    fun planRejectsMissingOrBrokenIdentityAndCorruptPrefs() {
        assertIs<PlanResult.Bad>(RestorePlanner.plan(planEntries(identity = null)))
        val broken = planEntries().map { if (it.name == BackupContent.IDENTITY) BackupEntry(it.name, it.data.copyOf(50)) else it }
        assertIs<PlanResult.Bad>(RestorePlanner.plan(broken))
        val badPrefs = planEntries().map { if (it.name == BackupContent.SETTINGS) BackupEntry(it.name, "i\ttheme\tkhong-phai-so".toByteArray()) else it }
        assertIs<PlanResult.Bad>(RestorePlanner.plan(badPrefs))
    }

    @Test
    fun planWorksOnAnEmptyBackupOfAFreshAccount() {
        val plan = ok(RestorePlanner.plan(planEntries()))
        assertEquals(0, plan.messageCount); assertEquals(0, plan.cardCount); assertTrue(plan.images.isEmpty())
    }

    @Test
    fun backupFileRoundTripsThroughThePlanner() {
        val id = Identity.generate()
        val file = BackupCodec.encrypt(planEntries(identity = id, messages = listOf(msgLine("z1", null))), "mật khẩu dài".toCharArray(), iterations = 1000)
        val dec = assertIs<BackupResult.Ok>(BackupCodec.decrypt(file, "mật khẩu dài".toCharArray()))
        val plan = ok(RestorePlanner.plan(dec.entries))
        assertEquals(id.peerId, plan.identity.peerId)
        assertEquals(1, plan.messageCount)
    }
}
