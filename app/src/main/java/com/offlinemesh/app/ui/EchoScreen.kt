package com.offlinemesh.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.offlinemesh.app.echo.EchoController

/** Giao diện thô cho thử nghiệm; chỉ đọc EchoUiState và gọi EchoController. Sẽ thay sau. */
@Composable
fun EchoScreen(onStart: () -> Unit, onStop: () -> Unit) {
    var s by remember { mutableStateOf(EchoController.state) }
    DisposableEffect(Unit) {
        EchoController.listener = { s = it }
        s = EchoController.state
        onDispose { EchoController.listener = null }
    }

    Scaffold { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("ID máy này: ${s.selfId}", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStart, enabled = !s.running) { Text("Bắt đầu") }
                Button(onClick = onStop, enabled = s.running) { Text("Dừng") }
            }

            Text("Máy đã thấy:", style = MaterialTheme.typography.titleSmall)
            if (s.peers.isEmpty()) Text("(chưa có)")
            s.peers.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(p)
                    Button(onClick = { EchoController.connect(p) }, enabled = s.linkedPeer == null) { Text("Kết nối") }
                }
            }

            s.linkedPeer?.let {
                Text("Đã nối tới $it (bạn là bên gửi)")
                Button(onClick = { EchoController.runEcho(500) }) { Text("Chạy echo 500 khung") }
            }
            s.incomingFrom?.let { Text("Có máy ...$it nối vào (bạn là bên nhận)") }

            if (s.progress.isNotEmpty()) Text(s.progress)
            if (s.result.isNotEmpty()) Text(s.result, style = MaterialTheme.typography.bodyLarge)
            s.error?.let { Text("Lỗi: $it", color = MaterialTheme.colorScheme.error) }

            Text("Log gần nhất:", style = MaterialTheme.typography.titleSmall)
            s.logTail.forEach { Text(it, style = MaterialTheme.typography.labelSmall) }
        }
    }
}
