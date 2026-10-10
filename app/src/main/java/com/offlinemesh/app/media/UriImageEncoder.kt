package com.offlinemesh.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.offlinemesh.core.mesh.payload.Encoded
import com.offlinemesh.core.mesh.payload.ImageEncoder
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Bộ mã hóa ảnh Android cho ImageCompressor: đọc ảnh từ Uri, xoay theo EXIF, thu nhỏ cạnh dài về maxEdgePx,
 * nén JPEG theo chất lượng. Mỗi lần gọi giải mã lại từ tệp (tiết kiệm RAM, chấp nhận chậm vì chỉ chạy vài lần).
 */
class UriImageEncoder(private val ctx: Context, private val uri: Uri) : ImageEncoder {
    private val orientation: Int = runCatching {
        ctx.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
    }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

    private val longEdge: Int = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        max(o.outWidth, o.outHeight)
    }.getOrDefault(0)

    private fun applyOrientation(m: Matrix) {
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(-90f); m.postScale(-1f, 1f) }
        }
    }

    override fun encode(maxEdgePx: Int, quality: Int): Encoded? {
        if (longEdge <= 0) return null
        var sample = 1
        while (longEdge / (sample * 2) >= maxEdgePx) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val scale = min(1f, maxEdgePx.toFloat() / max(decoded.width, decoded.height))
        val m = Matrix().apply { postScale(scale, scale); applyOrientation(this) }
        val out = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
        val bos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        val result = Encoded(bos.toByteArray(), out.width, out.height)
        if (out !== decoded) decoded.recycle()
        out.recycle()
        return result
    }
}
