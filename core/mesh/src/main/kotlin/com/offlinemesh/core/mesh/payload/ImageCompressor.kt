package com.offlinemesh.core.mesh.payload

/** Kết quả một lần mã hóa ảnh thử (bản Android dùng Bitmap.compress JPEG). */
class Encoded(val bytes: ByteArray, val width: Int, val height: Int)

fun interface ImageEncoder {
    /** Thu nhỏ để cạnh dài ≤ maxEdgePx rồi nén JPEG với chất lượng 1..100. null nếu không đọc được ảnh. */
    fun encode(maxEdgePx: Int, quality: Int): Encoded?
}

/** Ngân sách kích thước JPEG và các cạnh dài thử (từ lớn đến nhỏ). */
enum class ImageTier(val maxBytes: Int, val edges: List<Int>) {
    SMALL(12_000, listOf(320, 240, 160, 120)),
    NORMAL(30_000, listOf(640, 480, 360, 240, 160)),
    HIGH(50_000, listOf(800, 640, 480, 360, 240)),
}

class Compressed(val image: Encoded, val edgePx: Int, val quality: Int)

/**
 * Tìm ảnh to nhất có thể mà vẫn nằm trong ngân sách: thử cạnh dài từ lớn xuống nhỏ; với mỗi cạnh,
 * tìm nhị phân chất lượng cao nhất còn vừa ngân sách (kích thước JPEG giảm khi chất lượng giảm).
 * Cạnh nhỏ nhất được phép hạ chất lượng sâu hơn; không vừa thì trả null (từ chối gửi).
 */
object ImageCompressor {
    private val NORMAL_QUALITIES = listOf(85, 75, 65, 55, 45)
    private val LAST_RESORT_QUALITIES = NORMAL_QUALITIES + listOf(35, 25)

    fun compress(encoder: ImageEncoder, tier: ImageTier): Compressed? {
        for ((i, edge) in tier.edges.withIndex()) {
            val qs = if (i == tier.edges.lastIndex) LAST_RESORT_QUALITIES else NORMAL_QUALITIES
            var lo = 0
            var hi = qs.lastIndex
            var best: Compressed? = null
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                val e = encoder.encode(edge, qs[mid]) ?: return null
                if (e.bytes.size <= tier.maxBytes) { best = Compressed(e, edge, qs[mid]); hi = mid - 1 } else lo = mid + 1
            }
            if (best != null) return best
        }
        return null
    }
}
