package com.offlinemesh.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.offlinemesh.core.mesh.payload.GeoPoint

/** Lấy vị trí không cần Internet (GPS/mạng di động) bằng LocationManager, không cần thư viện ngoài. Gọi trên luồng chính. */
class LocationProvider(private val ctx: Context) {
    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Vị trí đã biết gần nhất trong mọi nguồn (có thể cũ). */
    @SuppressLint("MissingPermission")
    fun lastKnown(): Location? {
        if (!hasPermission()) return null
        return lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    }

    fun lastKnownPoint(): GeoPoint? = lastKnown()?.let { toPoint(it) }

    /**
     * Xin một lần định vị mới trong tối đa timeoutMs; hết hạn hoặc lỗi thì dùng vị trí đã biết gần nhất (có thể null).
     * Android 10 trở xuống không có API tiện lợi nên dùng thẳng vị trí đã biết.
     */
    @SuppressLint("MissingPermission")
    fun current(timeoutMs: Long, cb: (Location?) -> Unit) {
        if (!hasPermission()) { cb(null); return }
        var done = false
        fun finish(l: Location?) { if (done) return; done = true; cb(l ?: lastKnown()) }
        if (Build.VERSION.SDK_INT < 30) { finish(null); return }
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (provider == null) { finish(null); return }
        val cancel = CancellationSignal()
        Handler(Looper.getMainLooper()).postDelayed({ cancel.cancel(); finish(null) }, timeoutMs)
        lm.getCurrentLocation(provider, cancel, ContextCompat.getMainExecutor(ctx)) { loc -> finish(loc) }
    }

    companion object {
        fun toPoint(l: Location) = GeoPoint(
            Math.round(l.latitude * 1e6).toInt(), Math.round(l.longitude * 1e6).toInt(),
            if (l.hasAccuracy()) l.accuracy.toInt() else null,
        )
    }
}
