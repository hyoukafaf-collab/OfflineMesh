package com.offlinemesh.app.data

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.offlinemesh.app.mesh.MeshController
import com.offlinemesh.app.service.MeshService

/** Điều kiện để mesh chạy: điều khoản, Bluetooth, quyền, chạy nền. Cập nhật khi mở app/quay lại app (theo sự kiện, không quét định kỳ). */
object AppGate {
    var btOn by mutableStateOf(true)
        private set
    var bgAllowed by mutableStateOf(true)
        private set

    /** Người dùng chọn "Để sau" ở màn chạy nền trong lần mở app này; mở app lần sau sẽ hỏi lại. */
    var bgSkipped by mutableStateOf(false)

    @Volatile
    var foreground = false

    val showBackgroundPrompt: Boolean get() = AppSettings.termsAccepted && !bgAllowed && !bgSkipped

    fun refresh(ctx: Context) {
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        btOn = adapter?.isEnabled == true
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        bgAllowed = pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    fun requiredPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun permissionsGranted(ctx: Context): Boolean =
        requiredPermissions().all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

    /** Tự bật mesh khi đủ điều kiện, không cần nút Bắt đầu. */
    fun ensureMesh(ctx: Context) {
        if (!AppSettings.termsAccepted || !btOn || !permissionsGranted(ctx) || MeshController.state.running) return
        ContextCompat.startForegroundService(ctx, Intent(ctx, MeshService::class.java))
    }
}
