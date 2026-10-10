package com.offlinemesh.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.offlinemesh.app.crypto.ContactsController
import com.offlinemesh.app.mesh.MeshController

private val SOS_TYPES = listOf(0 to "Cần cứu hộ", 1 to "Bị thương", 2 to "Mắc kẹt", 3 to "Tôi an toàn", 4 to "Cần nhu yếu phẩm")

/** Giao diện thô cho thử nghiệm tuần 2–5; chỉ đọc MeshUiState và gọi MeshController. Sẽ thay sau. */
@Composable
fun MeshScreen(onStart: () -> Unit, onStop: () -> Unit) {
    val ctx = LocalContext.current
    var s by remember { mutableStateOf(MeshController.state) }
    var text by remember { mutableStateOf("Xin chào từ điện thoại") }
    var sosType by remember { mutableStateOf(0) }
    var includeLoc by remember { mutableStateOf(true) }
    var imagePeer by remember { mutableStateOf<String?>(null) }
    var contacts by remember { mutableStateOf(ContactsController.state.contacts.map { it.peer }.toSet()) }
    DisposableEffect(Unit) {
        MeshController.listener = { s = it }
        s = MeshController.state
        contacts = ContactsController.state.contacts.map { it.peer }.toSet()
        onDispose { MeshController.listener = null }
    }

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        MeshController.sendSos(sosType, true, text)      // dù được cấp hay không, vẫn gửi (không có quyền thì SOS không kèm vị trí)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val peer = imagePeer
        if (uri != null && peer != null) MeshController.sendImage(peer, uri)
    }
    fun hasLocation() =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

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
            s.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            if (s.stats.isNotEmpty()) Text(s.stats, style = MaterialTheme.typography.bodySmall)

            OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Nội dung tin / lời nhắn SOS") }, modifier = Modifier.fillMaxWidth())

            Text("SOS (ký, công khai, lan tới mọi máy)", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SOS_TYPES.forEach { (code, label) -> Button(onClick = { sosType = code }, enabled = sosType != code) { Text(label) } }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = includeLoc, onCheckedChange = { includeLoc = it })
                Text("Kèm vị trí GPS")
            }
            Button(
                onClick = {
                    if (includeLoc && !hasLocation()) locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    else MeshController.sendSos(sosType, includeLoc, text)
                },
                enabled = s.running,
            ) { Text("Gửi SOS: ${SOS_TYPES.first { it.first == sosType }.second}") }

            Text("Máy đang thấy / đã nối:", style = MaterialTheme.typography.titleSmall)
            val all = (s.seen + s.linked).distinct()
            if (all.isEmpty()) Text("(chưa có)")
            all.forEach { p ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(p + if (p in s.linked) "  [đã nối]" else "")
                    if (p in contacts) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { MeshController.sendText(p, text) }, enabled = s.running) { Text("Gửi text (mã hóa)") }
                            Button(onClick = { imagePeer = p; picker.launch("image/*") }, enabled = s.running) { Text("Gửi ảnh (mã hóa)") }
                        }
                    } else {
                        Text("(chưa có khóa: thêm ở tab Liên hệ)", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Text("Hộp thư:", style = MaterialTheme.typography.titleSmall)
            if (s.inbox.isEmpty()) Text("(trống)")
            s.inbox.forEach { item ->
                Text(item.title, style = MaterialTheme.typography.titleSmall)
                Text(item.body)
                item.image?.let { bytes ->
                    val bmp = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
                    if (bmp != null) Image(bmp, contentDescription = "Ảnh nhận được", modifier = Modifier.size(220.dp))
                }
            }

            Text("Log gần nhất:", style = MaterialTheme.typography.titleSmall)
            s.logTail.forEach { Text(it, style = MaterialTheme.typography.labelSmall) }
        }
    }
}
