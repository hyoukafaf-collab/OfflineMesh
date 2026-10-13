package com.offlinemesh.core.mesh.power

/** Một mẫu đo: thời điểm, % pin, đang sạc không, nhãn chế độ đang chạy (ví dụ "SAVER", "FULL/EM", "CRITICAL/ÉP"). */
data class BatterySample(val tMs: Long, val percent: Int, val charging: Boolean, val label: String)

data class BatteryRow(val label: String, val hours: Double, val dropPercent: Double) {
    /** % pin tụt mỗi giờ; null nếu dữ liệu chưa đủ (pin chỉ có độ phân giải 1% nên cần từ 1 giờ trở lên mới đáng tin). */
    val perHour: Double? get() = if (hours >= 1.0) dropPercent / hours else null
}

object BatteryStats {
    private const val MAX_GAP_MS = 6 * 3_600_000L

    /**
     * Ghép các cặp mẫu liên tiếp; khoảng thời gian được tính cho nhãn của mẫu đầu. Bỏ qua khoảng có sạc,
     * khoảng cách quá 6 giờ (dịch vụ đã dừng) hoặc thời gian không tăng. Pin tăng trong khoảng không sạc coi là tụt 0.
     */
    fun summarize(samples: List<BatterySample>): List<BatteryRow> {
        val hours = LinkedHashMap<String, Double>()
        val drop = LinkedHashMap<String, Double>()
        for ((a, b) in samples.zipWithNext()) {
            val dt = b.tMs - a.tMs
            if (a.charging || b.charging || dt <= 0 || dt > MAX_GAP_MS) continue
            hours[a.label] = (hours[a.label] ?: 0.0) + dt / 3_600_000.0
            drop[a.label] = (drop[a.label] ?: 0.0) + maxOf(0, a.percent - b.percent)
        }
        return hours.keys.sorted().map { BatteryRow(it, hours.getValue(it), drop.getValue(it)) }
    }
}

/** Dạng một dòng CSV: tMs,percent,charging(0/1),label. */
object BatteryCsv {
    fun encode(s: BatterySample) = "${s.tMs},${s.percent},${if (s.charging) 1 else 0},${s.label.replace(',', ';')}"

    fun decode(line: String): BatterySample? {
        val p = line.trim().split(",", limit = 4)
        if (p.size != 4) return null
        val t = p[0].toLongOrNull() ?: return null
        val pct = p[1].toIntOrNull()?.takeIf { it in 0..100 } ?: return null
        val ch = when (p[2]) { "1" -> true; "0" -> false; else -> return null }
        return BatterySample(t, pct, ch, p[3])
    }
}
