package com.offlinemesh.app

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import com.offlinemesh.app.data.AppGate
import com.offlinemesh.app.mesh.MeshController
import com.offlinemesh.app.ui.AppRoot

/** Chỉ chế độ dọc (khai báo trong manifest). Mesh tự bật khi đủ điều kiện, không có nút Bắt đầu. */
class MainActivity : ComponentActivity() {
    // Bluetooth bật/tắt: sự kiện hệ thống, không quét định kỳ
    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            AppGate.refresh(this@MainActivity)
            if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_ON) AppGate.ensureMesh(this@MainActivity)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppRoot() }
    }

    override fun onStart() {
        super.onStart()
        AppGate.foreground = true
        ContextCompat.registerReceiver(this, btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onResume() {
        super.onResume()
        AppGate.refresh(this)               // kiểm tra lại Bluetooth và quyền chạy nền mỗi lần vào app
        MeshController.clearStaleMessages()
        AppGate.ensureMesh(this)
    }

    override fun onStop() {
        AppGate.foreground = false
        runCatching { unregisterReceiver(btReceiver) }
        super.onStop()
    }
}
