package com.offlinemesh.app.apk

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

/**
 * Chia sẻ chính ứng dụng này cho máy khác qua Bluetooth (hoặc bất kỳ cách chia sẻ tệp nào) khi chưa có Internet.
 * Chỉ chia sẻ được khi bản đang chạy là MỘT tệp APK. Bản cài từ gói AAB gồm nhiều APK (base + split) không chia sẻ riêng
 * base.apk được vì máy kia sẽ không cài nổi; khi đó dùng APK "universal" đặt sẵn trong assets.
 */
object ApkShare {
    class Info(val file: File, val sizeBytes: Long, val sha256: String, val versionName: String, val certSha256: String?)

    sealed interface Result {
        class Ready(val info: Info) : Result
        class Unsupported(val reason: String) : Result
    }

    fun prepare(ctx: Context): Result {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val out = File(dir, "OfflineMesh.apk")
        val ai = ctx.applicationInfo
        // APK cài bằng nút Run của Android Studio thường bị đánh dấu testOnly: máy khác KHÔNG cài được bằng trình cài đặt
        // thông thường (báo "ứng dụng chưa được cài đặt" hoặc "gói có vẻ không hợp lệ"). Báo rõ thay vì gửi tệp vô dụng.
        if (ai.flags and ApplicationInfo.FLAG_TEST_ONLY != 0) {
            return Result.Unsupported(
                "Bản đang chạy là bản testOnly (cài bằng nút Run của Android Studio) nên máy khác không cài được. " +
                    "Hãy dựng APK bằng Build > Build APK(s) (hoặc ./gradlew assembleDebug) rồi cài lại bản đó lên máy này " +
                    "(gỡ app cũ trước; danh tính và danh bạ sẽ phải trao đổi lại), sau đó chia sẻ. " +
                    "Hoặc thêm android.injected.testOnly=false vào gradle.properties rồi Run lại.",
            )
        }
        val splits = ai.splitSourceDirs
        try {
            if (splits == null || splits.isEmpty()) {
                File(ai.sourceDir).inputStream().use { i -> out.outputStream().use { o -> i.copyTo(o) } }
            } else {
                val bundled = runCatching { ctx.assets.open(UNIVERSAL_ASSET) }.getOrNull()
                    ?: return Result.Unsupported(
                        "Bản cài này gồm nhiều APK (cài từ gói AAB). Hãy cài bản APK trực tiếp từ Android Studio, " +
                            "hoặc tạo APK universal bằng bundletool và đặt vào app/src/main/assets/$UNIVERSAL_ASSET.",
                    )
                bundled.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
            }
        } catch (e: Exception) {
            return Result.Unsupported("Không đọc được tệp cài đặt: ${e.message}")
        }
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
        return Result.Ready(Info(out, out.length(), sha256(out), version, certSha256(ctx)))
    }

    fun shareIntent(ctx: Context, info: Info): Intent {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", info.file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Gửi ứng dụng (chọn Bluetooth)").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** SHA-256 của chứng chỉ ký app này: hai máy có chữ ký khác nhau thì không cài đè lên nhau được. */
    @Suppress("DEPRECATION")
    private fun certSha256(ctx: Context): String? = runCatching {
        val pm = ctx.packageManager
        val sig = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        }
        sig?.toByteArray()?.let { MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { b -> "%02x".format(b) } }
    }.getOrNull()

    /** 32 ký tự hex đầu chia nhóm 4 để đọc/đối chiếu bằng mắt. */
    fun group(hex: String): String = hex.take(32).uppercase().chunked(4).joinToString(" ")

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private const val UNIVERSAL_ASSET = "OfflineMesh-universal.apk"
}
