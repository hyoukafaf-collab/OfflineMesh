package com.offlinemesh.core.mesh.transport

import com.offlinemesh.core.model.PeerId
import com.offlinemesh.core.model.hexToBytes
import com.offlinemesh.core.model.toHex

sealed interface LinkFrame {
    /** Bên Central tự giới thiệu ID (bên Peripheral chỉ thấy địa chỉ BLE ngẫu nhiên). */
    data class Ident(val peer: PeerId) : LinkFrame

    /** Một khung của tầng mesh (đã qua Framing). */
    class Data(val payload: ByteArray) : LinkFrame
}

/**
 * Khung ở tầng BLE, bọc ngoài khung của MeshNode:
 *   IDENT [0xA1][ver:1][len:1][id]      DATA [0xA2][payload]
 * Phần thêm vào mỗi khung DATA chỉ 1 byte.
 */
object LinkFrames {
    const val VERSION = 1
    const val OVERHEAD = 1
    private const val T_IDENT: Byte = 0xA1.toByte()
    private const val T_DATA: Byte = 0xA2.toByte()

    fun ident(id: PeerId): ByteArray {
        val b = id.hex.hexToBytes()
        return byteArrayOf(T_IDENT, VERSION.toByte(), b.size.toByte()) + b
    }

    fun data(payload: ByteArray): ByteArray = byteArrayOf(T_DATA) + payload

    /** Trả null nếu sai định dạng hoặc khác phiên bản; không ném lỗi. */
    fun parse(f: ByteArray): LinkFrame? {
        if (f.isEmpty()) return null
        return when (f[0]) {
            T_DATA -> LinkFrame.Data(f.copyOfRange(1, f.size))
            T_IDENT -> {
                if (f.size < 3 || f[1].toInt() != VERSION) return null
                val len = f[2].toInt() and 0xFF
                if (len == 0 || len > 16 || f.size != 3 + len) return null
                LinkFrame.Ident(PeerId(f.copyOfRange(3, f.size).toHex()))
            }
            else -> null
        }
    }
}
