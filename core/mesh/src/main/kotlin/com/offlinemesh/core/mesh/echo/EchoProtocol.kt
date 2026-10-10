package com.offlinemesh.core.mesh.echo

import java.nio.ByteBuffer
import java.util.zip.CRC32

/** Kết quả bên nhận gửi lại sau khi nhận khung END. */
data class EchoReport(val received: Int, val badCrc: Int, val outOfOrder: Int, val bytes: Int)

/**
 * Giao thức thử nghiệm đường truyền (không phải giao thức tin nhắn thật).
 * DATA  : [1][seq:4][crc32(payload):4][payload...]  (payload xác định theo seq)
 * END   : [2][tổng số khung đã gửi:4]
 * REPORT: [3][received:4][badCrc:4][outOfOrder:4][bytes:4]
 */
object EchoProtocol {
    const val TYPE_DATA: Byte = 1
    const val TYPE_END: Byte = 2
    const val TYPE_REPORT: Byte = 3
    const val HEADER = 9

    fun dataFrame(seq: Int, frameSize: Int): ByteArray {
        require(frameSize > HEADER) { "frameSize phải lớn hơn $HEADER" }
        val payload = ByteArray(frameSize - HEADER) { (seq + it).toByte() }
        return ByteBuffer.allocate(frameSize)
            .put(TYPE_DATA).putInt(seq).putInt(crc(payload)).put(payload).array()
    }

    fun endFrame(sent: Int): ByteArray =
        ByteBuffer.allocate(5).put(TYPE_END).putInt(sent).array()

    fun reportFrame(r: EchoReport): ByteArray =
        ByteBuffer.allocate(17).put(TYPE_REPORT)
            .putInt(r.received).putInt(r.badCrc).putInt(r.outOfOrder).putInt(r.bytes).array()

    fun parseReport(b: ByteArray): EchoReport? {
        if (b.size != 17 || b[0] != TYPE_REPORT) return null
        val bb = ByteBuffer.wrap(b, 1, 16)
        return EchoReport(bb.int, bb.int, bb.int, bb.int)
    }

    internal fun crc(payload: ByteArray): Int =
        CRC32().apply { update(payload) }.value.toInt()
}

/** Bên nhận: đếm khung đúng/sai, tự reset khi gặp seq=0 (bắt đầu một lượt thử mới). */
class EchoReceiver {
    var received = 0
        private set
    var badCrc = 0
        private set
    var outOfOrder = 0
        private set
    var bytes = 0
        private set
    private var expected = 0

    fun reset() {
        received = 0; badCrc = 0; outOfOrder = 0; bytes = 0; expected = 0
    }

    /** Trả khung REPORT khi nhận END, các trường hợp khác trả null. */
    fun onFrame(f: ByteArray): ByteArray? {
        if (f.isEmpty()) return null
        when (f[0]) {
            EchoProtocol.TYPE_DATA -> {
                if (f.size <= EchoProtocol.HEADER) { badCrc++; return null }
                val bb = ByteBuffer.wrap(f)
                bb.get()
                val seq = bb.int
                val want = bb.int
                if (seq == 0) reset()
                val payload = f.copyOfRange(EchoProtocol.HEADER, f.size)
                if (EchoProtocol.crc(payload) == want) { received++; bytes += f.size } else badCrc++
                if (seq != expected) outOfOrder++
                expected = seq + 1
            }
            EchoProtocol.TYPE_END ->
                return EchoProtocol.reportFrame(EchoReport(received, badCrc, outOfOrder, bytes))
        }
        return null
    }
}
