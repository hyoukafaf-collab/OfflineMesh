package com.offlinemesh.core.mesh.transport

import com.offlinemesh.core.model.PeerId

interface TransportListener {
    fun onPeerFound(peer: PeerId)
    fun onPeerLost(peer: PeerId)
    fun onData(from: PeerId, data: ByteArray)
}

/**
 * Lớp thấp nhất: gửi/nhận khung byte giữa các máy trong tầm.
 * Bản thật (BLE) nằm ở tầng Android; bản test là FakeTransport.
 */
interface Transport {
    val selfId: PeerId

    /** Kích thước tối đa của một khung gửi qua send(). */
    val maxFrame: Int

    fun start(listener: TransportListener)
    fun stop()

    /** false nếu không gửi được (chưa start, peer ngoài tầm, khung quá lớn). */
    fun send(to: PeerId, data: ByteArray): Boolean
}
