package com.offlinemesh.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.offlinemesh.app.crypto.ContactsController
import com.offlinemesh.app.data.AppSettings
import com.offlinemesh.app.power.PowerController
import com.offlinemesh.app.settings.PrivacySettings
import com.offlinemesh.core.mesh.payload.ReplayConfig
import com.offlinemesh.core.mesh.settings.Retention

@Composable
fun ScreenColumn(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopBar(title, onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { content() }
    }
}

// ---------------------------------------------------------------- danh sách cài đặt
@Composable
fun SettingsHome(onBack: () -> Unit, go: (String) -> Unit) {
    val toggleShare = rememberLocationToggle()           // cùng hàm với công tắc trong mục SOS; trạng thái là Compose nên cập nhật ngay
    val power = PowerController.state
    ScreenColumn("Cài đặt", onBack) {
        SectionHeader("TÀI KHOẢN")
        SettingRow("Cá nhân", "Tên, mã QR, thẻ liên hệ", { go("profile") })
        SettingRow("Danh bạ ghim khóa", "${ContactsController.state.contacts.size} người", { go("contacts") })
        SwitchRow("Chỉ nhận tin riêng từ danh bạ", contactsOnlySubtitle(), AppSettings.contactsOnly) { AppSettings.setContactsOnly(it) }
        SettingRow("Sao lưu và tài khoản", backupSubtitle(), { go("backup") })
        SectionHeader("THIẾT BỊ")
        SettingRow("Pin và chế độ", "${power.levelText}${if (power.battery >= 0) " · ${power.battery}%" else ""}${if (power.emergency) " · khẩn cấp" else ""}", { go("battery") })
        SettingRow("Giao diện", trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("Sáng", AppSettings.theme == 1) { AppSettings.setTheme(1) }
                Pill("Tối", AppSettings.theme == 2) { AppSettings.setTheme(2) }
                Pill("Máy", AppSettings.theme == 0) { AppSettings.setTheme(0) }
            }
        })
        SectionHeader("THÔNG BÁO")
        SwitchRow("Thông báo tin nhắn", null, AppSettings.notifMsg) { AppSettings.setNotifMsg(it) }
        SwitchRow("Âm thanh tin nhắn", null, AppSettings.soundMsg) { AppSettings.setSoundMsg(it) }
        SwitchRow("Thông báo SOS", null, AppSettings.notifSos) { AppSettings.setNotifSos(it) }
        SwitchRow("Âm thanh SOS", null, AppSettings.soundSos) { AppSettings.setSoundSos(it) }
        SectionHeader("SOS")
        SettingRow("Phát lại SOS", "Mỗi 1 phút, giãn dần đến 1 giờ · trong ${replayLabel(AppSettings.replayMin)}", { go("replay") })
        SwitchRow("Chia sẻ vị trí trong SOS", if (PrivacySettings.shareLocation) "Bật: SOS kèm tọa độ, mọi máy nhận được đều thấy" else "Tắt: SOS gửi ngay, không kèm vị trí", PrivacySettings.shareLocation) { on -> toggleShare(on) }
        SectionHeader("DỮ LIỆU")
        SettingRow("Lưu tin nhắn", "${Retention.label(AppSettings.retentionDays)} (từ 1 ngày đến 3 tháng), tin cũ hơn tự xóa", { go("retention") })
        SettingRow("Chia sẻ ứng dụng", "Gửi bản cài đặt qua Bluetooth", { go("share") })
    }
}

private fun contactsOnlySubtitle(): String =
    if (AppSettings.contactsOnly) "Bật: tin riêng từ người chưa có trong danh bạ bị bỏ qua. Tin công khai và SOS vẫn nhận"
    else "Tắt: người lạ nhắn riêng sẽ hiện ở mục \"Người lạ\" (chỉ đọc). Tin công khai và SOS luôn nhận"

private fun backupSubtitle(): String {
    val t = AppSettings.lastBackupMs
    return if (t <= 0L) "Chưa sao lưu lần nào · khôi phục hoặc xóa tài khoản"
    else "Sao lưu lần cuối ${java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t))}"
}

private fun replayLabel(min: Int): String = if (min >= 60 && min % 60 == 0) "${min / 60} giờ" else "$min phút"

// ---------------------------------------------------------------- cá nhân: tên, QR, thẻ, thêm liên hệ
@Composable
fun ProfileScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val s = ContactsController.state
    var name by remember { mutableStateOf(s.myName) }
    var pasted by remember { mutableStateOf("") }
    val qr = remember(s.myCard) { if (s.myCard.isEmpty()) null else qrBitmap(s.myCard).asImageBitmap() }
    ScreenColumn("Cá nhân", onBack) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Tên hiển thị (ngắn)") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { ContactsController.setMyName(name) }) { Text("Lưu") }
            }
            qr?.let { Image(it, contentDescription = "Mã QR thẻ liên hệ", modifier = Modifier.size(240.dp).align(Alignment.CenterHorizontally)) }
            Text("Mã vân tay: ${s.myFingerprint}", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = {
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("OfflineMesh card", s.myCard))
                ContactsController.setMessage("Đã sao chép thẻ của bạn")
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Sao chép thẻ (văn bản)") }

            Text("Thêm liên hệ (gặp trực tiếp)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Button(onClick = {
                val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                GmsBarcodeScanning.getClient(ctx, options).startScan()
                    .addOnSuccessListener { b -> b.rawValue?.let { ContactsController.addFromText(it) } }
                    .addOnFailureListener { e -> ContactsController.setMessage("Không quét được: ${e.message}") }
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Quét QR của người kia") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = pasted, onValueChange = { pasted = it }, label = { Text("Hoặc dán thẻ OM1:… của người khác") }, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { ContactsController.addFromText(pasted); pasted = "" }, enabled = pasted.isNotBlank()) { Text("Thêm") }
            }
            s.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            s.pendingRename?.let { p ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { ContactsController.confirmRename() }) { Text("Đổi thành \"${p.newName}\"") }
                    OutlinedButton(onClick = { ContactsController.cancelRename() }) { Text("Giữ tên cũ") }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- danh bạ ghim khóa
@Composable
fun ContactsManageScreen(onBack: () -> Unit) {
    val s = ContactsController.state
    var confirm by remember { mutableStateOf<String?>(null) }
    ScreenColumn("Danh bạ ghim khóa", onBack) {
        SwitchRow("Chỉ nhận tin riêng từ danh bạ", contactsOnlySubtitle(), AppSettings.contactsOnly) { AppSettings.setContactsOnly(it) }
        if (s.contacts.isEmpty()) Text("Chưa có liên hệ. Thêm ở mục Cá nhân bằng cách quét QR hoặc dán thẻ.", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        s.contacts.forEach { c ->
            SettingRow(c.name, "ID ${c.peer} · ${c.fingerprint}", trailing = { OutlinedButton(onClick = { confirm = c.peer }) { Text("Xóa") } })
        }
        if (s.blocked.isNotEmpty()) {
            SectionHeader("ĐÃ CHẶN")
            s.blocked.forEach { id ->
                SettingRow("⚠ ${id.take(8)}", "ID $id · tin riêng và tin công khai của họ bị bỏ qua (SOS vẫn nhận)", trailing = { OutlinedButton(onClick = { ContactsController.unblock(id) }) { Text("Bỏ chặn") } })
            }
        }
        Text("Người lạ = người chưa có trong danh bạ mà nhắn RIÊNG cho bạn (tin mã hóa gửi đúng cho bạn, chỉ là bạn chưa biết khóa của họ). Họ hiện ở mục \"Người lạ nhắn riêng\" (chỉ đọc, xóa hoặc chặn được), bạn chưa trả lời được cho tới khi thêm thẻ của họ. Người đăng công khai thì khác: tin của họ chỉ nằm trong mục \"Công khai\", không thành \"người lạ\".", modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Khóa của mỗi liên hệ được ghim: tin chỉ hiện ✔ khi đúng khóa đã ghim. Xóa liên hệ thì không gửi tin riêng cho họ được nữa cho tới khi thêm lại.", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    confirm?.let { peer ->
        WarningDialog(
            title = "Xóa liên hệ?", text = "Bạn sẽ không gửi được tin riêng cho người này cho tới khi thêm lại thẻ của họ. Cuộc trò chuyện cũ vẫn còn ở mục \"Người lạ nhắn riêng\", chỉ đọc.",
            confirmText = "Xóa", danger = true, onConfirm = { ContactsController.remove(peer); confirm = null }, onDismiss = { confirm = null },
        )
    }
}

// ---------------------------------------------------------------- pin và chế độ
@Composable
fun BatteryScreen(onBack: () -> Unit) {
    val p = PowerController.state
    var confirm by remember { mutableStateOf(false) }
    var exitDialog by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { PowerController.refreshTable() }
    val auto = p.levelText
    ScreenColumn("Pin và chế độ", onBack) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("Pin ${if (p.battery >= 0) "${p.battery}%" else "?"}${if (p.charging) " · đang sạc" else ""}", fontWeight = FontWeight.Bold)
                    Text("Thống kê sử dụng theo chế độ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    p.table.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        OutlinedButton(onClick = { PowerController.refreshTable() }) { Text("Cập nhật") }
                        OutlinedButton(onClick = { PowerController.clearTable() }) { Text("Xóa số liệu") }
                    }
                }
            }
            Text("Chế độ làm việc", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            ModeRow("Đầy đủ", "Pin từ 50%: quét liên tục", !p.emergency && auto == "Đầy đủ")
            ModeRow("Tiết kiệm", "Pin 20–49%: quét 3 giây mỗi 12 giây", !p.emergency && auto == "Tiết kiệm")
            ModeRow("Cực tiết kiệm", "Pin dưới 20%: quét 2 giây mỗi 20 giây", !p.emergency && auto == "Cực tiết kiệm")
            ModeRow("Khẩn cấp", "Mở gửi SOS; text và ảnh vẫn giới hạn, chuyển tiếp hộ người khác chỉ text ngắn và ảnh nhỏ", p.emergency, danger = true)
            Text("Mức Đầy đủ, Tiết kiệm, Cực tiết kiệm tự đổi theo pin. ${p.detail}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SwitchRow("Chế độ khẩn cấp", if (p.emergency) "Đang bật: SOS gửi được" else "Tắt. Bật sẽ hỏi xác nhận", p.emergency) { on ->
            if (on) confirm = true else tryExitEmergency { exitDialog = true }       // tắt khẩn cấp = kết thúc SOS (gửi "Tôi an toàn")
        }
        SectionHeader("ÉP MỨC (CHỈ ĐỂ ĐO PIN)")
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("AUTO" to "Tự động", "FULL" to "Đầy đủ", "SAVER" to "Tiết kiệm", "CRITICAL" to "Cực tiết kiệm").forEach { (k, label) ->
                Pill(label, p.forced == k) { PowerController.setForced(k) }
            }
        }
    }
    if (exitDialog) ExitEmergencyDialog { exitDialog = false }
    if (confirm) {
        WarningDialog(
            title = "Bật chế độ khẩn cấp?", text = "Tiết kiệm pin tối đa và mở chức năng gửi SOS. Chỉ bật khi thật sự cần cứu hộ.",
            extra = "Vẫn nhận mọi tin gửi cho bạn. Chuyển tiếp hộ người khác chỉ còn text ngắn và ảnh nhỏ.",
            confirmText = "Bật chế độ khẩn cấp", danger = true, dismissText = "Không",
            onConfirm = { PowerController.setEmergency(true); confirm = false }, onDismiss = { confirm = false },
        )
    }
}

@Composable
private fun ModeRow(title: String, desc: String, active: Boolean, danger: Boolean = false) {
    val c = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(12.dp), color = if (active) (if (danger) c.errorContainer else c.primaryContainer) else c.surface, tonalElevation = 1.dp) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(desc, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
            if (active) Text("Đang dùng", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
    }
}

// ---------------------------------------------------------------- phát lại SOS
@Composable
fun ReplayScreen(onBack: () -> Unit) {
    ScreenColumn("Phát lại SOS", onBack) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Sau khi gửi SOS, ứng dụng tự gửi lại một bản cập nhật (vị trí và thời điểm mới) cho tới khi bạn bấm \"Tôi an toàn\" hoặc hết thời gian.", style = MaterialTheme.typography.bodyMedium)
            Text("Lịch: lần đầu sau 1 phút, rồi giãn dần gấp đôi (2, 4, 8, 16, 32 phút) đến tối đa 1 giờ một lần. Nhiều SOS thì phát lại theo thứ tự đã gửi (cũ nhất trước).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Người nhận chỉ thấy một mục cho mỗi SOS (bản mới nhất) và chỉ được báo âm thanh ở lần đầu, nên không bị nhận trùng.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Phát lại trong:", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            ReplayConfig.DURATION_CHOICES_MIN.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { m -> Pill(replayLabel(m), AppSettings.replayMin == m) { AppSettings.setReplayMin(m) } } }
            }
            Text("Thay đổi áp dụng cho SOS gửi sau này (từ 5 phút đến 12 giờ).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------- lưu tin nhắn
@Composable
fun RetentionScreen(onBack: () -> Unit) {
    ScreenColumn("Lưu tin nhắn", onBack) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Tin nhắn cũ hơn thời gian này sẽ tự xóa khỏi máy (kèm ảnh). Chỉ ảnh hưởng máy này, người khác không bị ảnh hưởng. Mặc định 1 tháng.", style = MaterialTheme.typography.bodyMedium)
            Retention.CHOICES.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { d ->
                        Pill(Retention.label(d), AppSettings.retentionDays == d) {
                            AppSettings.setRetentionDays(d)
                            com.offlinemesh.app.data.MessageStore.purgeOlderThan(Retention.cutoffMs(System.currentTimeMillis(), d))
                        }
                    }
                }
            }
            Text("Muốn xóa ngay một phần: mở cuộc trò chuyện, bấm ⋮ rồi chọn Xóa tin cũ.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------- chia sẻ ứng dụng
@Composable
fun ShareScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val p = PowerController.state
    ScreenColumn("Chia sẻ ứng dụng", onBack) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Dùng khi máy khác chưa có ứng dụng và không có Internet. Bên nhận phải cho phép cài từ nguồn không xác định.", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { PowerController.shareApk(ctx) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Chuẩn bị và gửi qua Bluetooth") }
            p.apkInfo?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            p.apkMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            Text("Lưu ý: bản cài bằng nút Run của Android Studio (testOnly) không cài được trên máy khác; hãy dựng APK bằng Build > Build APK(s).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
