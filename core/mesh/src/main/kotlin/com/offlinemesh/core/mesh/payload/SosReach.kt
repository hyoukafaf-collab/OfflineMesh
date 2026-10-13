package com.offlinemesh.core.mesh.payload

import java.util.Locale

/**
 * Cận trên khoảng cách giữa người gửi SOS và người nhận, chỉ dựa vào số hop và tuổi của tin (không lộ vị trí của ai):
 *
 *     khoảng cách ≤ số hop × tầm Bluetooth + tốc độ người mang × tuổi tin
 *
 * Số hop một mình KHÔNG chặn được khoảng cách (một hop có thể là người đi xa mang tin rồi mới gặp máy kế tiếp),
 * nên phải cộng thêm quãng đường tối đa người mang đi được trong khoảng thời gian tin đã đi. Chỉ là ước lượng
 * theo giả định (tầm 100 m ngoài trời, đi bộ 2 m/s); hop và tuổi do máy chuyển tiếp ghi nên có thể bị làm sai lệch.
 */
object SosReach {
    data class Config(val rangeM: Double = 100.0, val walkSpeedMps: Double = 2.0) {
        init { require(rangeM > 0 && walkSpeedMps >= 0) }
    }

    enum class Confidence { HIGH, MEDIUM, LOW, NONE }

    fun maxDistanceM(hops: Int, ageMs: Long, cfg: Config = Config()): Double =
        maxOf(1, hops) * cfg.rangeM + cfg.walkSpeedMps * (maxOf(0L, ageMs) / 1000.0)

    /** ≤ 500 m: khá hữu ích; ≤ 1,5 km: tham khảo; ≤ 5 km: yếu; hơn nữa: tin đã đi quá lâu để ước lượng. */
    fun confidence(boundM: Double): Confidence = when {
        boundM <= 500 -> Confidence.HIGH
        boundM <= 1_500 -> Confidence.MEDIUM
        boundM <= 5_000 -> Confidence.LOW
        else -> Confidence.NONE
    }

    fun describe(hops: Int, ageMs: Long, cfg: Config = Config()): String {
        val d = maxDistanceM(hops, ageMs, cfg)
        if (confidence(d) == Confidence.NONE) return "tin đã đi quá lâu nên không ước lượng được khoảng cách tới người gửi"
        val assumption = "giả định tầm Bluetooth ${cfg.rangeM.toInt()} m, đi bộ ${String.format(Locale.US, "%.0f", cfg.walkSpeedMps)} m/s"
        return "người gửi cách bạn tối đa khoảng ${GeoMath.formatDistance(d)} ($assumption)"
    }
}
