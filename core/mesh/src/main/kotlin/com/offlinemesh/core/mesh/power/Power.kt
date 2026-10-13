package com.offlinemesh.core.mesh.power

import com.offlinemesh.core.mesh.routing.RelayFilter
import com.offlinemesh.core.model.Kind

enum class PowerMode { NORMAL, EMERGENCY }

/** rank lớn = nhiều tài nguyên hơn. */
enum class PowerLevel(val rank: Int) { CRITICAL(0), SAVER(1), FULL(2) }

/** Khoảng quảng bá: BALANCED ≈ 250 ms, LOW_POWER ≈ 1 s (ánh xạ sang ADVERTISE_MODE_* của Android). */
enum class AdvInterval { BALANCED, LOW_POWER }

/**
 * Quét BLE theo chu kỳ: bật onMs rồi tắt offMs. offMs = 0 nghĩa là quét liên tục.
 * Android chặn ứng dụng bắt đầu quét quá 5 lần trong 30 giây, nên một chu kỳ phải dài ít nhất 6 giây.
 */
data class ScanDuty(val onMs: Long, val offMs: Long) {
    val continuous: Boolean get() = offMs == 0L
    val dutyPercent: Int get() = if (continuous) 100 else (onMs * 100 / (onMs + offMs)).toInt()
}

/** Chỉ chặn các tin chuyển tiếp hộ người khác: tin gửi cho chính mình, SOS và ACK luôn được nhận. */
data class RelayRule(val kinds: Set<Kind>, val maxBytes: Int) : RelayFilter {
    override fun allow(kind: Kind, size: Int) = kind in kinds && size <= maxBytes
}

data class PowerProfile(
    val level: PowerLevel,
    val scan: ScanDuty,
    val adv: AdvInterval,
    val maxCentralLinks: Int,
    val idleCloseMs: Long,
    val relay: RelayRule,
)

object PowerPolicy {
    const val FULL_FROM = 50
    const val SAVER_FROM = 20
    const val HYSTERESIS = 3

    /**
     * NORMAL: ≥ 50% đầy đủ; 20–49% tiết kiệm; < 20% cực tiết kiệm. Đang sạc thì lên một mức.
     * EMERGENCY (người dùng bật chế độ khẩn cấp): tối đa là mức tiết kiệm dù pin còn nhiều; < 20% cực tiết kiệm;
     * đang sạc chỉ nâng cực tiết kiệm lên tiết kiệm.
     */
    fun levelFor(battery: Int, charging: Boolean, mode: PowerMode): PowerLevel {
        val b = battery.coerceIn(0, 100)
        var level = when {
            b >= FULL_FROM -> PowerLevel.FULL
            b >= SAVER_FROM -> PowerLevel.SAVER
            else -> PowerLevel.CRITICAL
        }
        if (mode == PowerMode.EMERGENCY && level == PowerLevel.FULL) level = PowerLevel.SAVER
        if (charging) {
            level = when (level) {
                PowerLevel.CRITICAL -> PowerLevel.SAVER
                PowerLevel.SAVER -> if (mode == PowerMode.NORMAL) PowerLevel.FULL else PowerLevel.SAVER
                PowerLevel.FULL -> PowerLevel.FULL
            }
        }
        return level
    }

    /** Giá trị khởi điểm; chỉnh lại sau khi đo pin thực tế (xem BatteryStats). */
    fun profile(level: PowerLevel): PowerProfile = when (level) {
        PowerLevel.FULL -> PowerProfile(
            level, ScanDuty(10_000, 0), AdvInterval.BALANCED, maxCentralLinks = 3, idleCloseMs = 30_000,
            relay = RelayRule(setOf(Kind.TEXT, Kind.IMAGE), 64 * 1024),
        )
        PowerLevel.SAVER -> PowerProfile(
            level, ScanDuty(3_000, 9_000), AdvInterval.LOW_POWER, maxCentralLinks = 2, idleCloseMs = 20_000,
            relay = RelayRule(setOf(Kind.TEXT, Kind.IMAGE), 32 * 1024),
        )
        PowerLevel.CRITICAL -> PowerProfile(
            level, ScanDuty(2_000, 18_000), AdvInterval.LOW_POWER, maxCentralLinks = 1, idleCloseMs = 10_000,
            relay = RelayRule(setOf(Kind.TEXT), 1024),
        )
    }
}

/**
 * Giữ mức hiện tại và đổi mức có độ trễ: xuống mức thấp hơn ngay khi pin chạm ngưỡng, nhưng chỉ lên mức cao hơn
 * khi pin vượt ngưỡng thêm HYSTERESIS %, tránh nhấp nháy quanh ngưỡng. Chỉ được gọi khi có sự kiện pin/chế độ đổi
 * (không đọc pin định kỳ).
 */
class PowerGovernor {
    var mode: PowerMode = PowerMode.NORMAL

    /** Ép một mức cố định để đo pin từng chế độ ở cùng mức pin; null = tự động. */
    var forced: PowerLevel? = null

    var level: PowerLevel = PowerLevel.FULL
        private set
    private var initialized = false

    val profile: PowerProfile get() = PowerPolicy.profile(level)

    fun update(battery: Int, charging: Boolean): PowerProfile {
        val f = forced
        if (f != null) { level = f; initialized = true; return profile }
        val target = PowerPolicy.levelFor(battery, charging, mode)
        if (!initialized) {
            level = target; initialized = true
        } else if (target.rank < level.rank) {
            level = target
        } else {
            val withMargin = PowerPolicy.levelFor(battery - PowerPolicy.HYSTERESIS, charging, mode)
            if (withMargin.rank > level.rank) level = withMargin
        }
        return profile
    }
}
