package com.offlinemesh.core.mesh.routing

import com.offlinemesh.core.model.Kind

/** Kích thước tối đa của phần NỘI DUNG (trước khi mã hóa) cho mỗi loại tin. Vượt thì từ chối gửi, và máy nhận bỏ tin quá khổ. */
object Limits {
    /** Dư địa cho bản mã (khóa tạm + thẻ GCM = 50 byte) và tiêu đề. */
    const val WIRE_SLACK = 128

    fun maxPayload(kind: Kind): Int = when (kind) {
        Kind.ACK -> 32
        Kind.SOS -> 512
        Kind.TEXT -> 4_096
        Kind.IMAGE -> 64 * 1024
        Kind.VIDEO -> 256 * 1024      // lõi cho phép; ứng dụng hiện chưa gửi video
    }

    fun maxWire(kind: Kind): Int = maxPayload(kind) + WIRE_SLACK
}
