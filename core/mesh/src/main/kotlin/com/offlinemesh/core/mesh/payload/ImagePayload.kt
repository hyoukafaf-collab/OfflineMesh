package com.offlinemesh.core.mesh.payload

import java.nio.ByteBuffer

/** Nội dung tin ảnh: [ver=1][định dạng:1 (1 = JPEG)][rộng:2][cao:2][dữ liệu JPEG]. */
class ImagePayload(val width: Int, val height: Int, val jpeg: ByteArray) {
    fun encode(): ByteArray =
        ByteBuffer.allocate(HEADER + jpeg.size).put(1).put(1).putShort(width.toShort()).putShort(height.toShort()).put(jpeg).array()

    companion object {
        const val HEADER = 6

        fun decode(b: ByteArray): ImagePayload? {
            if (b.size < HEADER + 4 || b[0].toInt() != 1 || b[1].toInt() != 1) return null
            val bb = ByteBuffer.wrap(b, 2, 4)
            val w = bb.short.toInt() and 0xFFFF
            val h = bb.short.toInt() and 0xFFFF
            if (w !in 1..8192 || h !in 1..8192) return null
            val data = b.copyOfRange(HEADER, b.size)
            // chữ ký đầu tệp JPEG: FF D8 FF
            if (data[0] != 0xFF.toByte() || data[1] != 0xD8.toByte() || data[2] != 0xFF.toByte()) return null
            return ImagePayload(w, h, data)
        }
    }
}
