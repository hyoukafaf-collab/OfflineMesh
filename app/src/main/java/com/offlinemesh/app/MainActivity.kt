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
import com.offlinemesh.app.mesh.MeshController
import com.offlinemesh.app.service.MeshForegroundService
import com.offlinemesh.app.service.MeshService
import com.offlinemesh.app.ui.AppRoot

class MainActivity : ComponentActivity() {
    private var afterPermission: (() -> Unit)? = null

    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        val required = requiredPermissions()
        if (required.all { r[it] == true }) {
            afterPermission?.invoke()
        } else {
            val msg = "Chưa cấp đủ quyền: ${required.filter { p -> r[p] != true }.joinToString { p -> p.substringAfterLast('.') }}"
            EchoController.update { it.copy(error = msg) }
            MeshController.update { it.copy(error = msg) }
        }
        afterPermission = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                AppRoot(
                    onStartEcho = { requestThen { start(MeshForegroundService::class.java) } },
                    onStopEcho = { stopService(Intent(this, MeshForegroundService::class.java)) },
                    onStartMesh = { requestThen { start(MeshService::class.java) } },
                    onStopMesh = { stopService(Intent(this, MeshService::class.java)) },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        EchoController.clearStaleMessages()
        MeshController.clearStaleMessages()
    }

    /** Quyền bắt buộc theo phiên bản Android. */
    private fun requiredPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= 31) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun requestThen(action: () -> Unit) {
        afterPermission = action
        val all = requiredPermissions() +
            if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        askPerms.launch(all.toTypedArray())
    }

    private fun start(service: Class<*>) {
        ContextCompat.startForegroundService(this, Intent(this, service))
    }
}
