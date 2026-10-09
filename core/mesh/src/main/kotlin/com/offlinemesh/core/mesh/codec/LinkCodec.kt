package com.offlinemesh.core.mesh.codec

import com.offlinemesh.core.model.BundleId
import com.offlinemesh.core.model.Kind
import com.offlinemesh.core.model.PeerId
import com.offlinemesh.core.model.WireBundle
import com.offlinemesh.core.model.hexToBytes
import com.offlinemesh.core.model.toHex
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

data class OfferItem(val id: BundleId, val kind: Kind, val dst: PeerId?, val size: Int, val remainingMs: Long)

/** Thông điệp giữa hai máy trong một cuộc gặp. */
sealed interface LinkMsg {
    data class Hello(val version: Int, val node: PeerId) : LinkMsg
    data class Summary(val seen: List<BundleId>) : LinkMsg
    data class Offer(val items: List<OfferItem>) : LinkMsg
    data class Request(val ids: List<BundleId>) : LinkMsg
    class BundleMsg(val wire: WireBundle) : LinkMsg
    data object Done : LinkMsg
}

/** [type:1] + thân. Danh sách: [count:4] + phần tử. BundleId luôn 8 byte. */
object LinkCodec {
    private const val T_HELLO: Byte = 1
    private const val T_SUMMARY: Byte = 2
    private const val T_OFFER: Byte = 3
    private const val T_REQUEST: Byte = 4
    private const val T_BUNDLE: Byte = 5
    private const val T_DONE: Byte = 6

    fun encode(m: LinkMsg): ByteArray = when (m) {
        is LinkMsg.Hello -> {
            val n = m.node.hex.hexToBytes()
            ByteBuffer.allocate(3 + n.size).put(T_HELLO).put(m.version.toByte()).put(n.size.toByte()).put(n).array()
        }
        is LinkMsg.Summary -> idList(T_SUMMARY, m.seen)
        is LinkMsg.Request -> idList(T_REQUEST, m.ids)
        is LinkMsg.Offer -> {
            val parts = m.items.map { it to (it.dst?.hex?.hexToBytes() ?: ByteArray(0)) }
            val bb = ByteBuffer.allocate(5 + parts.sumOf { 8 + 1 + 4 + 8 + 1 + it.second.size })
            bb.put(T_OFFER).putInt(m.items.size)
            for ((o, d) in parts) {
                bb.put(o.id.hex.hexToBytes()).put(o.kind.code.toByte()).putInt(o.size).putLong(o.remainingMs)
                bb.put(d.size.toByte()).put(d)
            }
            bb.array()
        }
        is LinkMsg.BundleMsg -> byteArrayOf(T_BUNDLE) + BundleCodec.encode(m.wire)
        LinkMsg.Done -> byteArrayOf(T_DONE)
    }

    private fun idList(type: Byte, ids: List<BundleId>): ByteArray {
        val bb = ByteBuffer.allocate(5 + 8 * ids.size)
        bb.put(type).putInt(ids.size)
        ids.forEach { bb.put(it.hex.hexToBytes()) }
        return bb.array()
    }

    /** Trả null nếu sai định dạng; không bao giờ ném lỗi. */
    fun decode(b: ByteArray): LinkMsg? = try { parse(b) } catch (e: RuntimeException) { null }

    private fun bytes(bb: ByteBuffer, n: Int): ByteArray {
        if (n < 0 || n > bb.remaining()) throw BufferUnderflowException()
        return ByteArray(n).also { bb.get(it) }
    }

    private fun ids(bb: ByteBuffer): List<BundleId> {
        val n = bb.int
        if (n < 0 || n.toLong() * 8 > bb.remaining()) return emptyList<BundleId>().also { throw BufferUnderflowException() }
        return List(n) { BundleId(bytes(bb, 8).toHex()) }
    }

    private fun parse(b: ByteArray): LinkMsg? {
        if (b.isEmpty()) return null
        val bb = ByteBuffer.wrap(b)
        val msg: LinkMsg = when (bb.get()) {
            T_HELLO -> {
                val v = bb.get().toInt() and 0xFF
                val n = bytes(bb, bb.get().toInt() and 0xFF)
                if (n.isEmpty()) return null
                LinkMsg.Hello(v, PeerId(n.toHex()))
            }
            T_SUMMARY -> LinkMsg.Summary(ids(bb))
            T_REQUEST -> LinkMsg.Request(ids(bb))
            T_OFFER -> {
                val n = bb.int
                if (n < 0 || n > bb.remaining()) return null
                val items = List(n) {
                    val id = BundleId(bytes(bb, 8).toHex())
                    val kind = Kind.fromCode(bb.get().toInt() and 0xFF) ?: throw BufferUnderflowException()
                    val size = bb.int
                    val rem = bb.long
                    val d = bytes(bb, bb.get().toInt() and 0xFF)
                    OfferItem(id, kind, if (d.isEmpty()) null else PeerId(d.toHex()), size, rem)
                }
                LinkMsg.Offer(items)
            }
            T_BUNDLE -> LinkMsg.BundleMsg(BundleCodec.decode(b.copyOfRange(1, b.size)) ?: return null)
            T_DONE -> LinkMsg.Done
            else -> return null
        }
        // BundleMsg đã tự kiểm tra hết dữ liệu; các loại khác không được thừa byte.
        if (msg !is LinkMsg.BundleMsg && bb.hasRemaining()) return null
        return msg
    }
}
