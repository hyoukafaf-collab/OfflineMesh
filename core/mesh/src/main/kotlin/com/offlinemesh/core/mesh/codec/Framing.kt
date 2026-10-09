package com.offlinemesh.core.mesh.codec

import java.nio.ByteBuffer

/**
 * Chia một thông điệp thành các khung ≤ maxFrame. Header 6 byte: [msgId:2][index:2][total:2].
 * Giả định đường truyền tin cậy và đúng thứ tự (GATT); khung mất thì thông điệp không ghép xong
 * và được xin lại ở lần gặp sau.
 */
object Framing {
    const val HEADER = 6

    fun split(msgId: Int, data: ByteArray, maxFrame: Int): List<ByteArray> {
        require(maxFrame > HEADER) { "maxFrame phải lớn hơn $HEADER" }
        val chunk = maxFrame - HEADER
        val total = maxOf(1, (data.size + chunk - 1) / chunk)
        require(total <= 0xFFFF) { "thông điệp quá lớn" }
        return List(total) { i ->
            val from = i * chunk
            val len = minOf(chunk, data.size - from)
            ByteBuffer.allocate(HEADER + len)
                .putShort(msgId.toShort()).putShort(i.toShort()).putShort(total.toShort())
                .put(data, from, len).array()
        }
    }
}

class Reassembler(private val maxPending: Int = 4) {
    private class Partial(val total: Int) {
        val parts = arrayOfNulls<ByteArray>(total)
        var count = 0
    }

    private val pending = LinkedHashMap<Int, Partial>()

    /** Trả thông điệp đầy đủ khi nhận đủ khung; nếu chưa đủ trả null. Chịu được khung trùng/đảo thứ tự. */
    fun accept(frame: ByteArray): ByteArray? {
        if (frame.size < Framing.HEADER) return null
        val bb = ByteBuffer.wrap(frame)
        val msgId = bb.short.toInt() and 0xFFFF
        val idx = bb.short.toInt() and 0xFFFF
        val total = bb.short.toInt() and 0xFFFF
        if (total == 0 || idx >= total) return null
        if (!pending.containsKey(msgId) && pending.size >= maxPending) pending.remove(pending.keys.first())
        val p = pending.getOrPut(msgId) { Partial(total) }
        if (p.total != total) { pending.remove(msgId); return null }
        if (p.parts[idx] == null) {
            p.parts[idx] = frame.copyOfRange(Framing.HEADER, frame.size)
            p.count++
        }
        if (p.count < p.total) return null
        pending.remove(msgId)
        val out = ByteBuffer.allocate(p.parts.sumOf { it!!.size })
        p.parts.forEach { out.put(it!!) }
        return out.array()
    }

    fun clear() = pending.clear()
}
