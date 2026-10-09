package com.offlinemesh.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.offlinemesh.app.crypto.ContactsController
import com.offlinemesh.app.mesh.MeshController

/** Giao diện thô cho thử nghiệm tuần 2; chỉ đọc MeshUiState và gọi MeshController. Sẽ thay sau. */
@Composable
fun MeshScreen(onStart: () -> Unit, onStop: () -> Unit) {
    var s by remember { mutableStateOf(MeshController.state) }
    var text by remember { mutableStateOf("Xin chào từ điện thoại") }
    var contacts by remember { mutableStateOf(ContactsController.state.contacts.map { it.peer }.toSet()) }
    DisposableEffect(Unit) {
        MeshController.listener = { s = it }
        s = MeshController.state
        contacts = ContactsController.state.contacts.map { it.peer }.toSet()
        onDispose { MeshController.listener = null }
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
            s.error?.let { Text("Lỗi: $it", color = MaterialTheme.colorScheme.error) }
            if (s.stats.isNotEmpty()) Text(s.stats, style = MaterialTheme.typography.bodySmall)

            OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Nội dung tin") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { MeshController.sendSos(text) }, enabled = s.running) { Text("Gửi SOS (ký, công khai)") }

            Text("Máy đang thấy / đã nối:", style = MaterialTheme.typography.titleSmall)
            val all = (s.seen + s.linked).distinct()
            if (all.isEmpty()) Text("(chưa có)")
            all.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(p + if (p in s.linked) "  [đã nối]" else "")
                    if (p in contacts) {
                        Button(onClick = { MeshController.sendText(p, text) }, enabled = s.running) { Text("Gửi text (mã hóa)") }
                    } else {
                        Text("(chưa có khóa: thêm ở tab Liên hệ)", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Text("Hộp thư:", style = MaterialTheme.typography.titleSmall)
            if (s.inbox.isEmpty()) Text("(trống)")
            s.inbox.forEach { Text(it) }

            Text("Log gần nhất:", style = MaterialTheme.typography.titleSmall)
            s.logTail.forEach { Text(it, style = MaterialTheme.typography.labelSmall) }
        }
    }
}
