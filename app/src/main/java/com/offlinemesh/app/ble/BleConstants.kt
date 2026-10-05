package com.offlinemesh.app.ble

import java.util.UUID

object BleConstants {
    val SERVICE_UUID: UUID = UUID.fromString("7b1c0001-4e6d-4a3b-9c2e-5d1f0a9b8c01")

    /** Peer ghi vào đặc tính này (Write Without Response). */
    val RX_UUID: UUID = UUID.fromString("7b1c0002-4e6d-4a3b-9c2e-5d1f0a9b8c01")

    /** Máy này notify cho peer qua đặc tính này. */
    val TX_UUID: UUID = UUID.fromString("7b1c0003-4e6d-4a3b-9c2e-5d1f0a9b8c01")

    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** 0xFFFF là company id dành cho thử nghiệm; mang 4 byte ID của máy trong gói quảng bá. */
    const val COMPANY_ID = 0xFFFF
    const val MTU_REQUEST = 247
}
