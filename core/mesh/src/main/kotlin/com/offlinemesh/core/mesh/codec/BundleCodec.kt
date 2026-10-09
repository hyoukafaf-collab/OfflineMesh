package com.offlinemesh.core.mesh.codec

import com.offlinemesh.core.model.BundleCore
import com.offlinemesh.core.model.BundleId
import com.offlinemesh.core.model.Kind
import com.offlinemesh.core.model.PeerId
import com.offlinemesh.core.model.WireBundle
import com.offlinemesh.core.model.hexToBytes
import com.offlinemesh.core.model.toHex
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

/**
 * Định dạng (big-endian):
 * [ver:1][hops:1][copies:1][age:8] + IMMUTABLE + [sigLen:2][sig]
 * IMMUTABLE = [kind:1][maxHops:1][initCopies:1][lifetime:8][nonce:8][idLen:1][id][srcLen:1][src][dstLen:1][dst][pubLen:1][pub][payloadLen:4][payload]
 * dstLen = 0 nghĩa là broadcast. Chữ ký ký lên đúng IMMUTABLE.
 */
object BundleCodec {
    const val VERSION = 2

    fun immutableBytes(c: BundleCore): ByteArray {
        val id = c.id.hex.hexToBytes()
        val src = c.src.hex.hexToBytes()
        val dst = c.dst?.hex?.hexToBytes() ?: ByteArray(0)
        val bb = ByteBuffer.allocate(3 + 16 + 4 + id.size + src.size + dst.size + c.pubKey.size + 4 + c.payload.size)
        bb.put(c.kind.code.toByte()).put(c.maxHops.toByte()).put(c.initialCopies.toByte())
        bb.putLong(c.lifetimeMs).putLong(c.nonce)
        bb.put(id.size.toByte()).put(id)
        bb.put(src.size.toByte()).put(src)
        bb.put(dst.size.toByte()).put(dst)
        bb.put(c.pubKey.size.toByte()).put(c.pubKey)
        bb.putInt(c.payload.size).put(c.payload)
        return bb.array()
    }

    fun encode(w: WireBundle): ByteArray {
        val imm = immutableBytes(w.core)
        val sig = w.core.signature
        val bb = ByteBuffer.allocate(3 + 8 + imm.size + 2 + sig.size)
        bb.put(VERSION.toByte()).put(w.hops.toByte()).put(w.copies.toByte()).putLong(w.ageMs)
        bb.put(imm).putShort(sig.size.toShort()).put(sig)
        return bb.array()
    }

    /** Trả null nếu dữ liệu sai/thiếu/khác phiên bản; không bao giờ ném lỗi. */
    fun decode(b: ByteArray): WireBundle? = try { parse(b) } catch (e: RuntimeException) { null }

    private fun bytes(bb: ByteBuffer, n: Int): ByteArray {
        if (n < 0 || n > bb.remaining()) throw BufferUnderflowException()
        return ByteArray(n).also { bb.get(it) }
    }

    private fun parse(b: ByteArray): WireBundle? {
        val bb = ByteBuffer.wrap(b)
        if (bb.get().toInt() != VERSION) return null
        val hops = bb.get().toInt() and 0xFF
        val copies = bb.get().toInt() and 0xFF
        val age = bb.long
        val kind = Kind.fromCode(bb.get().toInt() and 0xFF) ?: return null
        val maxHops = bb.get().toInt() and 0xFF
        val initCopies = bb.get().toInt() and 0xFF
        val lifetime = bb.long
        val nonce = bb.long
        val id = bytes(bb, bb.get().toInt() and 0xFF)
        val src = bytes(bb, bb.get().toInt() and 0xFF)
        val dst = bytes(bb, bb.get().toInt() and 0xFF)
        val pub = bytes(bb, bb.get().toInt() and 0xFF)
        val payload = bytes(bb, bb.int)
        val sig = bytes(bb, bb.short.toInt() and 0xFFFF)
        if (bb.hasRemaining()) return null
        if (id.size != 8 || src.isEmpty() || age < 0 || lifetime <= 0) return null
        val core = BundleCore(
            BundleId(id.toHex()), PeerId(src.toHex()), if (dst.isEmpty()) null else PeerId(dst.toHex()),
            kind, lifetime, maxHops, initCopies, nonce, payload, sig, pub,
        )
        return WireBundle(core, hops, age, copies)
    }
}
