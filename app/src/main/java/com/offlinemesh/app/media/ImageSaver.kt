package com.offlinemesh.app.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Lưu ảnh nhận/gửi về máy (thư viện ảnh). Ảnh là bản JPEG đã nén dùng để truyền, đúng như đang hiện trong tin nhắn.
 *  - Android 10 (API 29) trở lên: ghi thẳng vào Pictures/OfflineMesh qua MediaStore, không cần xin quyền.
 *  - Android cũ hơn: dùng bộ chọn tệp (CreateDocument) để người dùng chọn nơi lưu; ghi bằng copyToUri.
 */
object ImageSaver {
    val needsPicker: Boolean get() = Build.VERSION.SDK_INT < 29

    /** API 29+. true nếu lưu được. */
    fun saveToGallery(ctx: Context, src: File, displayName: String): Boolean {
        if (Build.VERSION.SDK_INT < 29 || !src.exists()) return false
        val resolver = ctx.applicationContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/OfflineMesh")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = try { resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) } catch (e: Exception) { null } ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } } ?: throw java.io.IOException("không mở được nơi lưu")
            val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            true
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }          // dọn mục dang dở, không để lại ảnh hỏng trong thư viện
            false
        }
    }

    /** Sao chép vào một Uri do người dùng chọn (Android cũ). */
    fun copyToUri(ctx: Context, src: File, dest: Uri): Boolean = try {
        ctx.applicationContext.contentResolver.openOutputStream(dest, "wt")?.use { out -> src.inputStream().use { it.copyTo(out) } } != null
    } catch (e: Exception) {
        false
    }
}
