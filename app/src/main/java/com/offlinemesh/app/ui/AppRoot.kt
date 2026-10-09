package com.offlinemesh.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Ba tab: Echo (đường truyền, tuần 1), Mesh (BleTransport + MeshNode), Liên hệ (danh tính + trao đổi khóa, tuần 4). */
@Composable
fun AppRoot(onStartEcho: () -> Unit, onStopEcho: () -> Unit, onStartMesh: () -> Unit, onStopMesh: () -> Unit) {
    var tab by remember { mutableStateOf(1) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 8.dp, top = 40.dp, end = 8.dp)) {
            Button(onClick = { tab = 0 }, enabled = tab != 0) { Text("Echo") }
            Button(onClick = { tab = 1 }, enabled = tab != 1, modifier = Modifier.padding(start = 8.dp)) { Text("Mesh") }
            Button(onClick = { tab = 2 }, enabled = tab != 2, modifier = Modifier.padding(start = 8.dp)) { Text("Liên hệ") }
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> EchoScreen(onStartEcho, onStopEcho)
                1 -> MeshScreen(onStartMesh, onStopMesh)
                else -> ContactsScreen()
            }
        }
    }
}
