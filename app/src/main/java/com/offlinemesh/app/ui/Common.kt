package com.offlinemesh.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import com.offlinemesh.app.data.Emergency
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Nút biểu tượng tròn 48dp (đủ lớn để chạm), vẽ bằng mã nên không cần ảnh. */
@Composable
fun IconTap(description: String, onClick: () -> Unit, badge: Boolean = false, content: @Composable () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClick).semantics { contentDescription = description; role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        content()
        if (badge) Box(Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 10.dp).size(9.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
    }
}

/** Mũi tên quay lại kiểu "←": thân ngang + đầu mũi tên, nét tròn đầu. Lưới 24x24. */
@Composable
fun BackArrowIcon(color: Color = MaterialTheme.colorScheme.onSurface) {
    Canvas(Modifier.size(24.dp)) {
        val u = size.width / 24f
        val path = Path().apply {
            moveTo(19.5f * u, 12f * u); lineTo(4.5f * u, 12f * u)                         // thân
            moveTo(11f * u, 5.5f * u); lineTo(4.5f * u, 12f * u); lineTo(11f * u, 18.5f * u)   // đầu mũi tên
        }
        drawPath(path, color, style = Stroke(width = 2.4f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Biểu tượng ba gạch (mở thanh danh sách trò chuyện trên điện thoại). */
@Composable
fun MenuIcon(color: Color = MaterialTheme.colorScheme.onSurface) {
    Canvas(Modifier.size(24.dp)) {
        val u = size.width / 24f
        for (y in listOf(6f, 12f, 18f)) drawLine(color, Offset(4f * u, y * u), Offset(20f * u, y * u), strokeWidth = 2.4f * u, cap = StrokeCap.Round)
    }
}

/**
 * Thanh tiêu đề. onBack != null: nút quay lại "←". Ngược lại onMenu != null: nút ba gạch (kèm chấm đỏ nếu menuBadge = có tin chưa đọc ở mục khác).
 */
@Composable
fun TopBar(title: String, onBack: (() -> Unit)? = null, onMenu: (() -> Unit)? = null, menuBadge: Boolean = false, actions: @Composable RowScope.() -> Unit = {}) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                onBack != null -> IconTap("Quay lại", onBack) { BackArrowIcon() }
                onMenu != null -> IconTap("Mở danh sách trò chuyện", onMenu, badge = menuBadge) { MenuIcon() }
                else -> Spacer(Modifier.width(12.dp))
            }
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 4.dp), maxLines = 1)
            actions()
        }
    }
}

@Composable
fun Divider1() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)))
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
fun SettingRow(title: String, subtitle: String? = null, onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailing != null) trailing()
        else if (onClick != null) Text("›", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Divider1()
}

@Composable
fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingRow(title, subtitle, trailing = { Switch(checked = checked, onCheckedChange = onChange) })
}

/** Nút chọn dạng viên thuốc; danger = màu đỏ (SOS). */
@Composable
fun Pill(text: String, selected: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val bg = if (danger) c.errorContainer else if (selected) c.primaryContainer else c.surfaceVariant
    val fg = if (danger) c.onErrorContainer else if (selected) c.onPrimaryContainer else c.onSurface
    Surface(shape = RoundedCornerShape(20.dp), color = bg, modifier = Modifier.clickable(onClick = onClick)) {
        Text(text, color = fg, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
    }
}

/**
 * Hộp thoại cảnh báo/xác nhận. checkboxLabel != null thì có ô "không hiển thị lại"; onConfirm nhận trạng thái ô đó.
 */
@Composable
fun WarningDialog(
    title: String, text: String, extra: String? = null, checkboxLabel: String? = null,
    confirmText: String, danger: Boolean = false, dismissText: String = "Hủy",
    onConfirm: (Boolean) -> Unit, onDismiss: () -> Unit,
) {
    var dontShow by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(text)
                if (extra != null) Text(extra, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (checkboxLabel != null) {
                    Row(Modifier.clickable { dontShow = !dontShow }.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = dontShow, onCheckedChange = { dontShow = it })
                        Text(checkboxLabel)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(dontShow) },
                colors = if (danger) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError) else ButtonDefaults.buttonColors(),
            ) { Text(confirmText) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissText) } },
    )
}

/** Tắt chế độ khẩn cấp: nếu còn SOS đang mở thì hỏi một lần; không thì tắt luôn. Mục SOS và màn Pin dùng chung. */
fun tryExitEmergency(showDialog: () -> Unit) {
    if (Emergency.hasOpenOwnSos()) showDialog() else Emergency.exit()
}

@Composable
fun ExitEmergencyDialog(onClose: () -> Unit) {
    WarningDialog(
        title = "Tắt chế độ khẩn cấp?",
        text = "Ứng dụng sẽ gửi \"Tôi an toàn\" để báo SOS của bạn đã xong và dừng phát lại. Chia sẻ vị trí trong SOS cũng được tắt, rồi máy quay về chế độ tiết kiệm pin thường.",
        confirmText = "Gửi \"Tôi an toàn\" và tắt", dismissText = "Giữ khẩn cấp",
        onConfirm = { Emergency.exit(); onClose() }, onDismiss = onClose,
    )
}

fun fmtDuration(ms: Long): String {
    val s = (ms.coerceAtLeast(0) / 1000).toInt()
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}
