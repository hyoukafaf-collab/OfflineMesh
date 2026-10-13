package com.offlinemesh.app.data

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import com.offlinemesh.app.crypto.ContactsController
import com.offlinemesh.app.crypto.IdentityStore
import com.offlinemesh.app.mesh.MeshController
import com.offlinemesh.app.mesh.SosStatus
import com.offlinemesh.app.power.PowerController
import com.offlinemesh.app.service.MeshService
import com.offlinemesh.app.settings.PrivacySettings
import com.offlinemesh.core.mesh.backup.BackupCodec
import com.offlinemesh.core.mesh.backup.BackupContent
import com.offlinemesh.core.mesh.backup.BackupEntry
import com.offlinemesh.core.mesh.backup.BackupResult
import com.offlinemesh.core.mesh.backup.PrefItem
import com.offlinemesh.core.mesh.backup.PlanResult
import com.offlinemesh.core.mesh.backup.PrefsText
import com.offlinemesh.core.mesh.backup.RestorePlanner
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Sao lưu / khôi phục / xóa tài khoản. Mọi thứ do NGƯỜI DÙNG chủ động và nằm trong tay họ:
 *  - sao lưu là một tệp .omb mã hóa bằng mật khẩu của họ, lưu ở nơi họ chọn (không tự đẩy đi đâu);
 *  - ứng dụng KHÔNG dùng Auto Backup của Android (xem manifest), nên không có bản sao nào ngầm tồn tại sau khi gỡ ứng dụng;
 *  - khôi phục kiểm tra toàn bộ trong bộ nhớ trước, chỉ nhận lại đúng những khóa/cài đặt trong danh sách cho phép;
 *  - xóa tài khoản xóa sạch khóa riêng, danh bạ, tin nhắn, ảnh, cài đặt, rồi tạo danh tính mới.
 * Các hàm nặng (mã hóa/giải mã có PBKDF2, ghi tệp) phải gọi từ luồng nền, không phải luồng giao diện.
 */
object AccountManager {
    sealed class Result {
        class Done(val summary: String) : Result()
        class Failed(val message: String) : Result()
    }

    private const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
    private const val MAX_IMAGES_TOTAL = 48L * 1024 * 1024

    /** Trên máy còn dữ liệu của một tài khoản đã dùng (tin, liên hệ, hoặc đã đồng ý điều khoản trước đây). */
    fun hasOldData(): Boolean =
        MessageStore.all().isNotEmpty() || ContactsController.state.contacts.isNotEmpty() || AppSettings.termsAccepted

    fun oldDataSummary(): String =
        "${MessageStore.all().size} tin nhắn, ${ContactsController.state.contacts.size} liên hệ"

    // ------------------------------------------------------------------ sao lưu
    private fun readPrefs(ctx: Context, name: String): Map<String, Any?> =
        ctx.getSharedPreferences(name, Context.MODE_PRIVATE).all.mapValues { it.value }

    private fun collect(ctx: Context, includeImages: Boolean): List<BackupEntry> {
        val out = ArrayList<BackupEntry>()
        out += BackupEntry(BackupContent.IDENTITY, ContactsController.identity.serialize())
        out += BackupEntry(BackupContent.CONTACTS, PrefsText.encode(readPrefs(ctx, "contacts").filterKeys { it in BackupContent.CONTACTS_KEYS }))
        out += BackupEntry(BackupContent.SETTINGS, PrefsText.encode(readPrefs(ctx, "app_settings").filterKeys { it in BackupContent.SETTINGS_KEYS }))
        out += BackupEntry(BackupContent.MESSAGES, MessageStore.exportText())
        if (includeImages) {
            var total = 0L
            for (f in MessageStore.imageFiles()) {
                val len = f.length()
                if (len <= 0 || len > MAX_IMAGE_BYTES || total + len > MAX_IMAGES_TOTAL) continue
                out += BackupEntry(BackupContent.imageEntryName(f.name), f.readBytes())
                total += len
            }
        }
        return out
    }

    /** Tạo nội dung tệp sao lưu (đã mã hóa). Chậm vài giây vì PBKDF2: gọi ở luồng nền. */
    fun createBackup(ctx: Context, password: CharArray, includeImages: Boolean): ByteArray =
        BackupCodec.encrypt(collect(ctx.applicationContext, includeImages), password)

    fun writeTo(ctx: Context, uri: Uri, bytes: ByteArray): Result = try {
        val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("không mở được nơi lưu")
        out.use { it.write(bytes); it.flush() }
        AppSettings.setLastBackup(System.currentTimeMillis())
        Result.Done("Đã lưu bản sao lưu (${(bytes.size + 1023) / 1024} KB)")
    } catch (e: Exception) {
        Result.Failed("Không ghi được tệp sao lưu: ${e.message ?: e.javaClass.simpleName}")
    }

    // ------------------------------------------------------------------ khôi phục
    private fun readLimited(input: InputStream, max: Int): ByteArray? {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            buf.write(chunk, 0, n)
            if (buf.size() > max) return null
        }
        return buf.toByteArray()
    }

    private fun putAll(e: SharedPreferences.Editor, items: List<PrefItem>) {
        for (i in items) when (val v = i.value) {
            is String -> e.putString(i.key, v)
            is Int -> e.putInt(i.key, v)
            is Long -> e.putLong(i.key, v)
            is Boolean -> e.putBoolean(i.key, v)
            is Float -> e.putFloat(i.key, v)
            is Set<*> -> e.putStringSet(i.key, v.filterIsInstance<String>().toSet())
        }
    }

    private fun writePrefs(ctx: Context, name: String, items: List<PrefItem>) {
        val e = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
        putAll(e, items)
        e.commit()
    }


    /** Đọc tệp ở `uri` rồi khôi phục. Luồng nền. Thành công thì dữ liệu hiện tại bị THAY THẾ hoàn toàn. */
    fun restoreFrom(ctx: Context, uri: Uri, password: CharArray): Result {
        val data = try {
            ctx.contentResolver.openInputStream(uri)?.use { readLimited(it, BackupCodec.MAX_PLAIN + 1024) }
        } catch (e: Exception) {
            return Result.Failed("Không đọc được tệp: ${e.message ?: e.javaClass.simpleName}")
        } ?: return Result.Failed("Tệp quá lớn hoặc không đọc được")
        return restore(ctx, data, password)
    }

    fun restore(ctx: Context, data: ByteArray, password: CharArray): Result {
        val app = ctx.applicationContext
        val entries = when (val r = BackupCodec.decrypt(data, password)) {
            is BackupResult.Ok -> r.entries
            BackupResult.NotABackup -> return Result.Failed("Đây không phải tệp sao lưu của OfflineMesh")
            BackupResult.UnsupportedVersion -> return Result.Failed("Tệp sao lưu từ phiên bản mới hơn, hãy cập nhật ứng dụng")
            BackupResult.WrongPasswordOrDamaged -> return Result.Failed("Sai mật khẩu, hoặc tệp bị hỏng")
            BackupResult.TooLarge -> return Result.Failed("Tệp sao lưu quá lớn")
        }
        // ---- kiểm tra và làm sạch TOÀN BỘ trong bộ nhớ trước (RestorePlanner, có test trên JVM); chưa đụng gì tới dữ liệu hiện tại
        val plan = when (val p = RestorePlanner.plan(entries)) {
            is PlanResult.Ok -> p.plan
            is PlanResult.Bad -> return Result.Failed(p.reason)
        }

        // ---- ghi: dừng dịch vụ mesh, ghi tin/ảnh/cài đặt trước, danh tính CUỐI CÙNG (bước "chuyển tài khoản")
        return try {
            app.stopService(Intent(app, MeshService::class.java))
            MessageStore.replaceFiles(plan.messages, plan.images)
            writePrefs(app, "contacts", plan.contacts)
            writePrefs(app, "app_settings", plan.settings)        // không có "terms": phải đồng ý điều khoản lại
            writePrefs(app, "privacy", emptyList())          // vị trí luôn tắt sau khi khôi phục
            writePrefs(app, "power", emptyList())            // chế độ khẩn cấp luôn tắt
            File(app.filesDir, "battery.csv").delete()
            IdentityStore.replace(app, plan.identity)
            reloadAll(app)
            Result.Done("Đã khôi phục ${plan.cardCount} liên hệ và ${plan.messageCount} tin nhắn")
        } catch (e: Exception) {
            Result.Failed("Không ghi được dữ liệu khôi phục: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------------ xóa tài khoản
    /** Xóa sạch tài khoản trên máy này rồi tạo danh tính mới. Không hoàn tác được. Luồng nền. */
    fun wipeAll(ctx: Context): Result {
        val app = ctx.applicationContext
        return try {
            app.stopService(Intent(app, MeshService::class.java))       // dừng mesh và việc phát lại SOS
            IdentityStore.delete(app)
            MessageStore.replaceFiles(ByteArray(0), emptyList())
            for (name in listOf("app_settings", "privacy", "power", "contacts")) app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            File(app.filesDir, "battery.csv").delete()
            File(app.cacheDir, "share").deleteRecursively()
            app.getSystemService(NotificationManager::class.java)?.cancelAll()
            reloadAll(app)
            Result.Done("Đã xóa tài khoản và toàn bộ dữ liệu trên máy này")
        } catch (e: Exception) {
            Result.Failed("Không xóa hết được: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Nạp lại các bộ nhớ trong tiến trình từ đĩa, để giao diện hiện đúng dữ liệu mới mà không cần khởi động lại ứng dụng. */
    private fun reloadAll(app: Context) {
        AppSettings.init(app)
        PrivacySettings.init(app)
        PowerController.init(app)
        ContactsController.init(app)       // đọc danh tính mới (hoặc tạo mới nếu vừa xóa) và danh bạ
        MessageStore.init(app)
        MeshController.update { it.copy(sos = SosStatus(), notice = null, error = null) }
    }
}
