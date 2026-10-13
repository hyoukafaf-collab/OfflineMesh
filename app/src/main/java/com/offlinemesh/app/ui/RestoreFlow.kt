package com.offlinemesh.app.ui

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.offlinemesh.app.data.AccountManager
import com.offlinemesh.app.data.AppSettings
import com.offlinemesh.core.mesh.backup.BackupCodec

/**
 * Hộp thoại nhập mật khẩu dùng chung. askConfirm = true (tạo sao lưu): nhập hai lần và kiểm tra độ dài tối thiểu.
 * `error` là lỗi từ phía xử lý (vd. sai mật khẩu); `extra` là phần thêm bên dưới ô nhập (vd. công tắc "kèm ảnh").
 */
@Composable
fun PasswordDialog(
    title: String, intro: String?, askConfirm: Boolean, confirmText: String, busy: Boolean, error: String?,
    onSubmit: (CharArray) -> Unit, onCancel: () -> Unit, extra: (@Composable () -> Unit)? = null,
) {
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var local by remember { mutableStateOf<String?>(null) }
    val visual = if (show) VisualTransformation.None else PasswordVisualTransformation()
    val keys = KeyboardOptions(keyboardType = KeyboardType.Password)
    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (intro != null) Text(intro, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = pw, onValueChange = { pw = it; local = null }, label = { Text("Mật khẩu") }, singleLine = true, enabled = !busy,
                    visualTransformation = visual, keyboardOptions = keys, modifier = Modifier.fillMaxWidth(),
                )
                if (askConfirm) {
                    OutlinedTextField(
                        value = pw2, onValueChange = { pw2 = it; local = null }, label = { Text("Nhập lại mật khẩu") }, singleLine = true, enabled = !busy,
                        visualTransformation = visual, keyboardOptions = keys, modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(Modifier.clickable { show = !show }.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = show, onCheckedChange = { show = it })
                    Text("Hiện mật khẩu")
                }
                if (extra != null) extra()
                val msg = error ?: local
                if (msg != null) Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Đang xử lý, vài giây…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && pw.isNotEmpty(),
                onClick = {
                    val problem = if (!askConfirm) null else BackupCodec.checkPassword(pw.toCharArray()) ?: if (pw != pw2) "Hai mật khẩu chưa giống nhau" else null
                    if (problem != null) local = problem else onSubmit(pw.toCharArray())
                },
            ) { Text(confirmText) }
        },
        dismissButton = { TextButton(onClick = onCancel, enabled = !busy) { Text("Hủy") } },
    )
}

/** Trạng thái của việc "khôi phục từ tệp": chọn tệp → nhập mật khẩu → giải mã/ghi ở luồng nền → báo kết quả. */
class RestoreFlow {
    var picked by mutableStateOf<Uri?>(null)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var openPicker: () -> Unit = {}
    var onRestored: (String) -> Unit = {}
}

@Composable
fun rememberRestoreFlow(onRestored: (String) -> Unit): RestoreFlow {
    val flow = remember { RestoreFlow() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { flow.error = null; flow.picked = uri }
    }
    flow.openPicker = { launcher.launch(arrayOf("*/*")) }
    flow.onRestored = onRestored
    return flow
}

/** Hiện hộp thoại mật khẩu khi đã chọn tệp. Đặt một lần trong màn hình dùng RestoreFlow. */
@Composable
fun RestoreDialogs(flow: RestoreFlow) {
    val app = LocalContext.current.applicationContext
    val uri = flow.picked ?: return
    PasswordDialog(
        title = "Mật khẩu của tệp sao lưu", intro = "Nhập mật khẩu bạn đã đặt khi sao lưu. Sau khi khôi phục, tài khoản hiện tại trên máy này sẽ được thay bằng tài khoản trong tệp.",
        askConfirm = false, confirmText = "Khôi phục", busy = flow.busy, error = flow.error,
        onSubmit = { pw ->
            flow.busy = true
            flow.error = null
            Thread {
                val r = try { AccountManager.restoreFrom(app, uri, pw) } finally { pw.fill('\u0000') }
                Handler(Looper.getMainLooper()).post {
                    flow.busy = false
                    when (r) {
                        is AccountManager.Result.Done -> { flow.picked = null; flow.onRestored(r.summary) }
                        is AccountManager.Result.Failed -> flow.error = r.message
                    }
                }
            }.start()
        },
        onCancel = { if (!flow.busy) { flow.picked = null; flow.error = null } },
    )
}

/**
 * Hỏi MỘT LẦN ở lần mở đầu tiên (sau mỗi lần xóa tài khoản cũng hỏi lại): có muốn khôi phục từ bản sao lưu không.
 * Quyền quyết định thuộc về người dùng; chọn "không" khi máy còn dữ liệu cũ thì dữ liệu cũ bị xóa sạch.
 */
@Composable
fun FirstRunChoice() {
    val app = LocalContext.current.applicationContext
    val hasOld = remember { AccountManager.hasOldData() }
    val summary = remember { AccountManager.oldDataSummary() }
    var confirmWipe by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val flow = rememberRestoreFlow { _ ->
        ChatState.selected = "SOS"
        AppSettings.markRestoreAsked()          // đặt SAU khi khôi phục (khôi phục ghi đè cài đặt)
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Khôi phục tài khoản?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (hasOld) {
            Text("Máy này còn dữ liệu của một tài khoản cũ ($summary). Có thể đó là bản cài trước đó, hoặc Android tự trả lại dữ liệu sau khi bạn gỡ rồi cài lại ứng dụng.")
            Text("Nếu bạn có tệp sao lưu, hãy khôi phục từ tệp. Nếu không, bạn có thể xóa hẳn dữ liệu cũ để bắt đầu sạch.")
        } else {
            Text("Nếu trước đây bạn đã sao lưu tài khoản ra một tệp (.omb), bạn có thể khôi phục nó ngay bây giờ: danh tính, danh bạ, tin nhắn, ảnh và cài đặt giao diện.")
        }
        Text(
            "Tệp sao lưu do chính bạn tạo trong Cài đặt › Sao lưu và tài khoản, mã hóa bằng mật khẩu của bạn. Ứng dụng không tự sao lưu hay gửi dữ liệu đi đâu.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.weight(1f))
        Button(onClick = { flow.openPicker() }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Khôi phục từ tệp sao lưu") }
        if (hasOld) {
            OutlinedButton(onClick = { confirmWipe = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Không khôi phục, xóa dữ liệu cũ") }
            TextButton(onClick = { AppSettings.markRestoreAsked() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Giữ nguyên tài khoản hiện tại") }
        } else {
            OutlinedButton(onClick = { AppSettings.markRestoreAsked() }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Không, bắt đầu tài khoản mới") }
        }
    }
    RestoreDialogs(flow)
    if (confirmWipe) {
        WarningDialog(
            title = "Xóa dữ liệu cũ?",
            text = "Khóa riêng, danh bạ, tin nhắn, ảnh và cài đặt của tài khoản cũ trên máy này sẽ bị xóa vĩnh viễn, rồi ứng dụng tạo tài khoản mới. Không thể hoàn tác.",
            extra = "Nếu chưa có tệp sao lưu, bạn sẽ không lấy lại được. Người khác sẽ phải thêm lại thẻ liên hệ mới của bạn.",
            confirmText = "Xóa và bắt đầu mới", danger = true, dismissText = "Hủy",
            onConfirm = { _ ->
                confirmWipe = false
                busy = true
                Thread {
                    val r = AccountManager.wipeAll(app)
                    Handler(Looper.getMainLooper()).post {
                        busy = false
                        when (r) {
                            is AccountManager.Result.Done -> { ChatState.selected = "SOS"; AppSettings.markRestoreAsked() }
                            is AccountManager.Result.Failed -> failure = r.message
                        }
                    }
                }.start()
            },
            onDismiss = { confirmWipe = false },
        )
    }
}
