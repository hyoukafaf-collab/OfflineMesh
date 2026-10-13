package com.offlinemesh.core.mesh.payload

import java.nio.ByteBuffer

/**
 * Nội dung tin ảnh.
 * - Ảnh thường (riêng/công khai), bản 1: [ver=1][định dạng:1 (1 = JPEG)][rộng:2][cao:2][dữ liệu JPEG] (giữ nguyên để tương thích máy cũ).
 * - Ảnh SOS, bản 2: [ver=2][định dạng:1][cờ:1 (bit0 = ảnh SOS)][rộng:2][cao:2][dữ liệu JPEG]. Máy nhận xếp ảnh này vào mục SOS
 *   thay vì mục Công khai. Máy cũ (chỉ hiểu bản 1) bỏ qua ảnh bản 2.
 */
class ImagePayload(val width: Int, val height: Int, val jpeg: ByteArray, val sos: Boolean = false) {
    fun encode(): ByteArray =
        if (!sos) {
            ByteBuffer.allocate(HEADER_V1 + jpeg.size).put(1).put(1).putShort(width.toShort()).putShort(height.toShort()).put(jpeg).array()
        } else {
            ByteBuffer.allocate(HEADER_V2 + jpeg.size).put(2).put(1).put(FLAG_SOS.toByte()).putShort(width.toShort()).putShort(height.toShort()).put(jpeg).array()
        }

    companion object {
        const val HEADER = 6           // giữ tên cũ (bản 1)
        const val HEADER_V1 = 6
        const val HEADER_V2 = 7
        const val FLAG_SOS = 1

        fun decode(b: ByteArray): ImagePayload? {
            if (b.size < 2 || b[1].toInt() != 1) return null
            val sos: Boolean
            val dataStart: Int
            val dimStart: Int
            when (b[0].toInt()) {
                1 -> { sos = false; dimStart = 2; dataStart = HEADER_V1 }
                2 -> {
                    if (b.size < HEADER_V2) return null
                    sos = (b[2].toInt() and FLAG_SOS) != 0; dimStart = 3; dataStart = HEADER_V2
                }
                else -> return null
            }
            if (b.size < dataStart + 4) return null
            val bb = ByteBuffer.wrap(b, dimStart, 4)
            val w = bb.short.toInt() and 0xFFFF
            val h = bb.short.toInt() and 0xFFFF
            if (w !in 1..8192 || h !in 1..8192) return null
            val data = b.copyOfRange(dataStart, b.size)
            // chữ ký đầu tệp JPEG: FF D8 FF
            if (data[0] != 0xFF.toByte() || data[1] != 0xD8.toByte() || data[2] != 0xFF.toByte()) return null
            return ImagePayload(w, h, data, sos)
        }
    }
}
