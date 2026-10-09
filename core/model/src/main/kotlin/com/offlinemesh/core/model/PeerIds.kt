package com.offlinemesh.core.model

import kotlin.random.Random

/**
 * ID máy dài 6 byte (12 ký tự hex): vừa trong gói quảng bá BLE 31 byte cùng UUID dịch vụ.
 * 48 bit đủ để xác suất trùng ID giữa 10.000 máy chỉ cỡ 1e-7. Tuần 4 sẽ lấy từ hash khóa công khai.
 */
object PeerIds {
    const val BYTES = 6

    fun random(rng: Random = Random.Default): PeerId = PeerId(rng.nextBytes(BYTES).toHex())

    fun isValid(id: PeerId): Boolean =
        id.hex.length == BYTES * 2 && id.hex.all { it in '0'..'9' || it in 'a'..'f' }
}
