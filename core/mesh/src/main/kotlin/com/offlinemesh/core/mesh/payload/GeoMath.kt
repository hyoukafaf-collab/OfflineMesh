package com.offlinemesh.core.mesh.payload

import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Khoảng cách (haversine, cầu bán kính 6371 km, sai số vài phần nghìn) và hướng giữa hai điểm. */
object GeoMath {
    private const val EARTH_M = 6_371_000.0
    private fun rad(e6: Int) = Math.toRadians(e6 / 1e6)

    fun distanceM(a: GeoPoint, b: GeoPoint): Double {
        val dLat = rad(b.latE6) - rad(a.latE6)
        val dLon = rad(b.lonE6) - rad(a.lonE6)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(rad(a.latE6)) * cos(rad(b.latE6)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Hướng từ a tới b, độ: 0 = Bắc, 90 = Đông. */
    fun bearingDeg(a: GeoPoint, b: GeoPoint): Double {
        val dLon = rad(b.lonE6) - rad(a.lonE6)
        val y = sin(dLon) * cos(rad(b.latE6))
        val x = cos(rad(a.latE6)) * sin(rad(b.latE6)) - sin(rad(a.latE6)) * cos(rad(b.latE6)) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private val NAMES = listOf("Bắc", "Đông Bắc", "Đông", "Đông Nam", "Nam", "Tây Nam", "Tây", "Tây Bắc")
    fun compass(deg: Double): String = NAMES[(((deg + 22.5) % 360.0) / 45.0).toInt() % 8]

    fun formatDistance(m: Double): String =
        if (m < 1000) "${((m / 10).roundToInt() * 10)} m" else String.format(Locale.US, "%.1f km", m / 1000)
}
