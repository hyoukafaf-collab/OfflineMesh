package com.offlinemesh.core.model

import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Phần BẤT BIẾN của một tin (được ký, id tính từ đây). Số hop, tuổi, số bản sao là phần
 * thay đổi dọc đường nên nằm ngoài (WireBundle / StoredBundle).
 */
class BundleCore(
    val id: BundleId,
    val src: PeerId,
    val dst: PeerId?,            // null = broadcast (SOS, thông báo, ACK)
    val kind: Kind,
    val lifetimeMs: Long,        // tổng thời gian sống từ lúc tạo
    val maxHops: Int,
    val initialCopies: Int,      // L của Spray and Wait (tin riêng)
    val nonce: Long,
    val payload: ByteArray,
    val signature: ByteArray = ByteArray(0),
    val pubKey: ByteArray = ByteArray(0),     // khóa công khai ký của người gửi (33 byte), nằm trong phần được ký
) {
    val size: Int get() = payload.size

    fun withSignature(sig: ByteArray) =
        BundleCore(id, src, dst, kind, lifetimeMs, maxHops, initialCopies, nonce, payload, sig, pubKey)

    /** Dùng khi giao cho người nhận: thay bản mã bằng bản rõ (id giữ nguyên, bản này không được lưu/relay tiếp). */
    fun withPayload(p: ByteArray) =
        BundleCore(id, src, dst, kind, lifetimeMs, maxHops, initialCopies, nonce, p, signature, pubKey)
}

/** Dạng gửi qua mạng: phần bất biến + trạng thái hiện tại (tuổi, hop, số bản sao trao cho người nhận). */
class WireBundle(val core: BundleCore, val hops: Int, val ageMs: Long, val copies: Int)

/** Bản đang lưu trên máy này. Tuổi tính tương đối: tuổi lúc nhận + thời gian giữ trên máy này. */
class StoredBundle(
    val core: BundleCore,
    val hops: Int,
    val ageAtReceiveMs: Long,
    val receivedAtMs: Long,
    var copies: Int,
    val originated: Boolean,
) {
    fun ageMs(nowMs: Long): Long = ageAtReceiveMs + (nowMs - receivedAtMs).coerceAtLeast(0)
    fun remainingMs(nowMs: Long): Long = core.lifetimeMs - ageMs(nowMs)
    fun isExpired(nowMs: Long): Boolean = remainingMs(nowMs) <= 0
}

object BundleIds {
    /** id = 8 byte đầu của SHA-256 trên toàn bộ phần bất biến (trừ chữ ký). */
    fun derive(
        src: PeerId, dst: PeerId?, kind: Kind, lifetimeMs: Long,
        maxHops: Int, initialCopies: Int, nonce: Long, payload: ByteArray,
    ): BundleId {
        val s = src.hex.hexToBytes()
        val d = dst?.hex?.hexToBytes() ?: ByteArray(0)
        val bb = ByteBuffer.allocate(2 + s.size + d.size + 3 + 16 + payload.size)
        bb.put(s.size.toByte()).put(s).put(d.size.toByte()).put(d)
        bb.put(kind.code.toByte()).put(maxHops.toByte()).put(initialCopies.toByte())
        bb.putLong(lifetimeMs).putLong(nonce).put(payload)
        val h = MessageDigest.getInstance("SHA-256").digest(bb.array())
        return BundleId(h.copyOf(8).toHex())
    }

    fun of(c: BundleCore): BundleId =
        derive(c.src, c.dst, c.kind, c.lifetimeMs, c.maxHops, c.initialCopies, c.nonce, c.payload)
}
