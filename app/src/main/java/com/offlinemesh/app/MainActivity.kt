package com.offlinemesh.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.core.content.ContextCompat
import com.offlinemesh.app.echo.EchoController
import com.offlinemesh.app.service.MeshForegroundService
import com.offlinemesh.app.ui.EchoScreen

class MainActivity : ComponentActivity() {
    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        val required = requiredPermissions()
        if (required.all { r[it] == true }) startMesh()
        else EchoController.update { it.copy(error = "Chưa cấp đủ quyền: ${required.filter { p -> r[p] != true }.joinToString { p -> p.substringAfterLast('.') }}") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { EchoScreen(onStart = ::requestAndStart, onStop = ::stopMesh) } }
    }

    override fun onStart() {
        super.onStart()
        EchoController.clearStaleMessages()
    }

    /** Quyền bắt buộc theo phiên bản Android. */
    private fun requiredPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= 31) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun requestAndStart() {
        val all = requiredPermissions() +
            if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        askPerms.launch(all.toTypedArray())
    }

    private fun startMesh() =
        ContextCompat.startForegroundService(this, Intent(this, MeshForegroundService::class.java))

    private fun stopMesh() { stopService(Intent(this, MeshForegroundService::class.java)) }
}
