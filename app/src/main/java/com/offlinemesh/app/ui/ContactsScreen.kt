package com.offlinemesh.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.offlinemesh.app.crypto.ContactsController

/** Tab Liên hệ: thẻ của tôi (QR + vân tay), quét/dán thẻ người khác, danh bạ. Giao diện thô, sẽ thay sau. */
@Composable
fun ContactsScreen() {
    val ctx = LocalContext.current
    var s by remember { mutableStateOf(ContactsController.state) }
    var pasted by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(ContactsController.state.myName) }
    DisposableEffect(Unit) {
        ContactsController.listener = { s = it }
        s = ContactsController.state
        onDispose { ContactsController.listener = null }
    }
    val qr = remember(s.myCard) { if (s.myCard.isEmpty()) null else qrBitmap(s.myCard).asImageBitmap() }

    Scaffold { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Thẻ của tôi", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Tên hiển thị") }, modifier = Modifier.weight(1f))
                Button(onClick = { ContactsController.setMyName(name) }) { Text("Lưu") }
            }
            qr?.let { Image(it, contentDescription = "Mã QR thẻ liên hệ", modifier = Modifier.size(260.dp)) }
            Text("Mã vân tay: ${s.myFingerprint}", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = {
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("OfflineMesh card", s.myCard))
                ContactsController.setMessage("Đã sao chép thẻ của bạn")
            }) { Text("Sao chép thẻ (văn bản)") }

            Text("Thêm liên hệ (gặp trực tiếp)", style = MaterialTheme.typography.titleMedium)
            Button(onClick = {
                val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                GmsBarcodeScanning.getClient(ctx, options).startScan()
                    .addOnSuccessListener { b -> b.rawValue?.let { ContactsController.addFromText(it) } }
                    .addOnFailureListener { e -> ContactsController.setMessage("Không quét được: ${e.message}") }
            }) { Text("Quét QR của người kia") }
            OutlinedTextField(value = pasted, onValueChange = { pasted = it }, label = { Text("Hoặc dán thẻ OM1:...") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { ContactsController.addFromText(pasted); pasted = "" }, enabled = pasted.isNotBlank()) { Text("Thêm từ văn bản") }
            s.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            s.pendingRename?.let { p ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { ContactsController.confirmRename() }) { Text("Đổi thành \"${p.newName}\"") }
                    Button(onClick = { ContactsController.cancelRename() }) { Text("Giữ tên cũ") }
                }
            }

            Text("Danh bạ đã ghim khóa:", style = MaterialTheme.typography.titleMedium)
            if (s.contacts.isEmpty()) Text("(trống)")
            s.contacts.forEach { c ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(c.name, style = MaterialTheme.typography.bodyLarge)
                        Text("ID ${c.peer} · ${c.fingerprint}", style = MaterialTheme.typography.labelSmall)
                    }
                    Button(onClick = { ContactsController.remove(c.peer) }) { Text("Xóa") }
                }
            }
        }
    }
}
