package com.offlinemesh.app.ui

import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.offlinemesh.app.data.AccountManager
import com.offlinemesh.app.data.AppSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sao lưu và tài khoản: tạo tệp sao lưu mã hóa (bằng mật khẩu của bạn), khôi phục từ tệp, hoặc xóa hẳn tài khoản trên máy này.
 * Mọi việc do người dùng chủ động; ứng dụng không tự sao lưu hay gửi dữ liệu đi đâu.
 */
@Composable
fun BackupScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext
    var askPassword by remember { mutableStateOf(false) }
    var includeImages by remember { mutableStateOf(true) }
    var pendingPw by remember { mutableStateOf<CharArray?>(null) }
    var pendingImages by remember { mutableStateOf(true) }
    var working by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageOk by remember { mutableStateOf(true) }
    var confirmRestore by remember { mutableStateOf(false) }
    var confirmWipe by remember { mutableStateOf(false) }

    val flow = rememberRestoreFlow { _ ->
        ChatState.selected = "SOS"
        AppSettings.markRestoreAsked()          // sau khi khôi phục, điều khoản chưa được đồng ý nên ứng dụng sẽ đưa về các bước đầu
    }

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val pw = pendingPw
        val withImages = pendingImages
        pendingPw = null
        if (uri != null && pw != null) {
            working = "Đang mã hóa và ghi tệp…"
            message = null
            Thread {
                val r = try {
                    AccountManager.writeTo(app, uri, AccountManager.createBackup(app, pw, withImages))
                } catch (e: Exception) {
                    AccountManager.Result.Failed("Không tạo được bản sao lưu: ${e.message ?: e.javaClass.simpleName}")
                } finally {
                    pw.fill('\u0000')
                }
                Handler(Looper.getMainLooper()).post {
                    working = null
                    when (r) {
                        is AccountManager.Result.Done -> { message = r.summary; messageOk = true }
                        is AccountManager.Result.Failed -> { message = r.message; messageOk = false }
                    }
                }
            }.start()
        } else {
            pw?.fill('\u0000')
        }
    }

    ScreenColumn("Sao lưu và tài khoản", onBack) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Tệp sao lưu (.omb) gồm danh tính (khóa riêng), danh bạ, tin nhắn, ảnh và cài đặt giao diện. Nó được mã hóa bằng mật khẩu do bạn đặt và lưu ở nơi bạn chọn " +
                    "(bộ nhớ máy, thẻ nhớ, USB, Drive…). Không cần Internet, và ứng dụng không tự sao lưu hay gửi dữ liệu đi đâu.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer) {
                Text(
                    "Không có cách lấy lại mật khẩu: quên mật khẩu thì không mở được tệp. Ai có cả tệp và mật khẩu sẽ có khóa riêng của bạn, nên hãy giữ kín cả hai.",
                    modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            val last = AppSettings.lastBackupMs
            Text(
                if (last > 0L) "Sao lưu lần cuối: ${SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(last))}" else "Bạn chưa sao lưu lần nào.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            working?.let {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(it)
                }
            }
            message?.let { Text(it, color = if (messageOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
            Button(onClick = { askPassword = true }, enabled = working == null, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Sao lưu ngay") }
            OutlinedButton(onClick = { confirmRestore = true }, enabled = working == null, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Khôi phục từ tệp sao lưu…") }
        }
        SectionHeader("XÓA TÀI KHOẢN")
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Xóa khóa riêng, danh bạ, tin nhắn, ảnh và cài đặt trên máy này, rồi tạo danh tính mới. Không hoàn tác được. Người khác phải thêm lại thẻ liên hệ mới của bạn.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (AppSettings.lastBackupMs <= 0L) {
                Text("Bạn chưa sao lưu lần nào. Nên bấm \"Sao lưu ngay\" trước khi xóa.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = { confirmWipe = true }, enabled = working == null,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) { Text("Xóa tài khoản và toàn bộ dữ liệu") }
        }
    }

    if (askPassword) {
        PasswordDialog(
            title = "Đặt mật khẩu cho bản sao lưu",
            intro = "Mật khẩu từ ${com.offlinemesh.core.mesh.backup.BackupCodec.MIN_PASSWORD} ký tự trở lên. Ghi nhớ kỹ: không thể khôi phục mật khẩu.",
            askConfirm = true, confirmText = "Chọn nơi lưu", busy = false, error = null,
            onSubmit = { pw ->
                pendingPw = pw
                pendingImages = includeImages
                askPassword = false
                create.launch("OfflineMesh-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".omb")
            },
            onCancel = { askPassword = false },
            extra = {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Kèm ảnh trong tin nhắn (tệp lớn hơn)", modifier = Modifier.weight(1f))
                    Switch(checked = includeImages, onCheckedChange = { includeImages = it })
                }
            },
        )
    }
    if (confirmRestore) {
        WarningDialog(
            title = "Khôi phục từ tệp sao lưu?",
            text = "Tài khoản, danh bạ, tin nhắn và cài đặt hiện tại trên máy này sẽ được THAY BẰNG nội dung trong tệp. Sau đó bạn sẽ xem lại điều khoản và các bước cấp quyền.",
            extra = if (AppSettings.lastBackupMs <= 0L) "Bạn chưa sao lưu dữ liệu hiện tại; nó sẽ mất nếu tiếp tục." else null,
            confirmText = "Chọn tệp", danger = true, dismissText = "Hủy",
            onConfirm = { _ -> confirmRestore = false; flow.openPicker() }, onDismiss = { confirmRestore = false },
        )
    }
    RestoreDialogs(flow)
    if (confirmWipe) {
        WarningDialog(
            title = "Xóa tài khoản và toàn bộ dữ liệu?",
            text = "Khóa riêng, danh bạ, tin nhắn, ảnh và cài đặt trên máy này sẽ bị xóa vĩnh viễn, rồi ứng dụng tạo danh tính mới. SOS đang phát lại cũng dừng.",
            extra = "Không thể hoàn tác. Nếu muốn giữ lại, hãy hủy và sao lưu trước.",
            confirmText = "Xóa tất cả", danger = true, dismissText = "Hủy",
            onConfirm = { _ ->
                confirmWipe = false
                working = "Đang xóa…"
                Thread {
                    val r = AccountManager.wipeAll(app)
                    Handler(Looper.getMainLooper()).post {
                        working = null
                        when (r) {
                            is AccountManager.Result.Done -> { ChatState.selected = "SOS" }          // restoreAsked đã bị xóa nên màn hỏi khôi phục hiện lại ngay
                            is AccountManager.Result.Failed -> { message = r.message; messageOk = false }
                        }
                    }
                }.start()
            },
            onDismiss = { confirmWipe = false },
        )
    }
}
