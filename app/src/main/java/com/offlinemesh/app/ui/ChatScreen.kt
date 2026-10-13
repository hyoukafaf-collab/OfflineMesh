package com.offlinemesh.app.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlinemesh.app.crypto.ContactsController
import com.offlinemesh.app.data.AppGate
import com.offlinemesh.app.data.AppSettings
import com.offlinemesh.app.data.MessageStore
import com.offlinemesh.app.location.LocationProvider
import com.offlinemesh.app.mesh.MeshController
import com.offlinemesh.app.mesh.SosStatus
import com.offlinemesh.app.power.PowerController
import com.offlinemesh.app.settings.PrivacySettings
import com.offlinemesh.core.mesh.history.StoredMessage
import com.offlinemesh.core.mesh.payload.GeoMath
import com.offlinemesh.core.mesh.payload.SosFeed
import com.offlinemesh.core.mesh.payload.SosPresets
import com.offlinemesh.core.mesh.payload.SosReach
import com.offlinemesh.core.mesh.payload.SosType
import com.offlinemesh.core.mesh.settings.ManualRange
import com.offlinemesh.core.model.PeerId
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/** Trạng thái giao diện chat: mục đang chọn ("SOS", "PUBLIC" hoặc PeerId của liên hệ) và thanh công cụ thu gọn (chỉ máy màn rộng). */
object ChatState {
    var selected by mutableStateOf("SOS")
    var collapsed by mutableStateOf(false)
    fun viewing(): String? = if (AppGate.foreground) selected else null
}

/** Từ độ rộng này (dp) trở lên là màn rộng (máy tính bảng): thanh dọc luôn hiện. Dưới mức này (điện thoại) thanh ẩn hẳn, mở bằng nút ba gạch. */
private const val WIDE_DP = 600

private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

private fun nameOrId(srcHex: String, pinned: Boolean): String {
    if (srcHex.isEmpty()) return "?"
    val c = ContactsController.book.byPeer(PeerId(srcHex))
    return if (pinned && c != null) "✔ ${c.name} [${srcHex.take(4)}]" else "⚠ ${srcHex.take(8)} (chưa xác minh)"
}

@Composable
fun ChatScreen(openSettings: () -> Unit) {
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    val mesh = MeshController.state
    val contacts = ContactsController.state.contacts
    val ver = MessageStore.version
    val sel = ChatState.selected
    val wide = LocalConfiguration.current.screenWidthDp >= WIDE_DP
    var menu by remember { mutableStateOf(false) }
    var drawer by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    val enableBt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { AppGate.refresh(ctx); AppGate.ensureMesh(ctx) }

    LaunchedEffect(sel, ver) { MessageStore.markRead(sel) }          // đang xem cuộc trò chuyện này thì coi như đã đọc
    LaunchedEffect(wide) { if (wide) drawer = false }
    BackHandler(enabled = drawer && !wide) { drawer = false }          // Quay lại khi đang mở thanh danh sách thì chỉ đóng thanh

    val title = when (sel) { "SOS" -> "SOS"; "PUBLIC" -> "Công khai"; else -> contacts.firstOrNull { it.peer == sel }?.name ?: "Tin nhắn" }
    // Chấm đỏ trên nút ba gạch: có tin chưa đọc ở một mục khác với mục đang xem
    val otherUnread = (ver >= 0) && (listOf("SOS", "PUBLIC") + contacts.map { it.peer }).any { it != sel && MessageStore.unread(it) > 0 }
    // Chạm ra ngoài ô nhập thì tắt bàn phím (các nút/ô nhập tự nhận chạm của chúng trước)
    Column(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { focus.clearFocus() } }) {
        TopBar(title, onMenu = if (wide) null else ({ drawer = true }), menuBadge = otherUnread, actions = {
            val near = (mesh.seen + mesh.linked).distinct().size
            if (near > 0) Text("$near máy gần", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 4.dp))
            Box {
                TextButton(onClick = { menu = true }) { Text("⋮", fontSize = 22.sp) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Xóa tin cũ…") }, onClick = { menu = false; deleteDialog = true })
                }
            }
            TextButton(onClick = openSettings) { Text("⚙", fontSize = 22.sp) }
        })
        if (!AppGate.btOn) {
            Text(
                "Bluetooth đang tắt, mở để gửi và nhận tin nhắn", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = Modifier.fillMaxWidth().clickable { runCatching { enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } }.padding(8.dp),
            )
        } else mesh.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth().padding(8.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Row(Modifier.fillMaxSize()) {
                if (wide) Rail(sel, contacts.map { it.peer to it.name })
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when (sel) { "SOS" -> SosPane(); "PUBLIC" -> PublicPane(); else -> PrivatePane(sel) }
                }
            }
            if (!wide) DrawerOverlay(drawer, sel, contacts.map { it.peer to it.name }, onPick = { ChatState.selected = it; drawer = false }, onClose = { drawer = false })
        }
    }
    if (deleteDialog) DeleteOldDialog(sel) { deleteDialog = false }
}

@Composable
private fun DeleteOldDialog(conv: String, onDone: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Xóa tin cũ trong mục này") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Chỉ xóa trên máy này; những người khác không bị ảnh hưởng.", style = MaterialTheme.typography.bodySmall)
                ManualRange.values().forEach { r ->
                    OutlinedButton(
                        onClick = {
                            val n = MessageStore.purgeOlderThan(r.cutoffMs(System.currentTimeMillis()), conv)
                            Toast.makeText(ctx, "Đã xóa $n tin cũ hơn ${r.label}", Toast.LENGTH_SHORT).show()
                            onDone()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Cũ hơn ${r.label}") }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDone) { Text("Đóng") } },
    )
}

// ---------------------------------------------------------------- danh sách mục: thanh dọc (màn rộng) và ngăn kéo (điện thoại)
@Composable
private fun Rail(sel: String, contacts: List<Pair<String, String>>) {
    val collapsed = ChatState.collapsed
    Column(Modifier.width(if (collapsed) 48.dp else 88.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface)) {
        RailItem("SOS", "SOS", sel == "SOS", MessageStore.unread("SOS"), danger = true, collapsed = collapsed)
        RailItem("PUBLIC", "Công khai", sel == "PUBLIC", MessageStore.unread("PUBLIC"), danger = false, collapsed = collapsed)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            contacts.forEach { (peer, name) -> RailItem(peer, name, sel == peer, MessageStore.unread(peer), danger = false, collapsed = collapsed) }
        }
        TextButton(onClick = { ChatState.collapsed = !collapsed }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(if (collapsed) "›" else "‹", fontSize = 22.sp) }
    }
}

@Composable
private fun RailItem(key: String, label: String, selected: Boolean, unread: Int, danger: Boolean, collapsed: Boolean) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).background(if (selected) c.primaryContainer else c.surface)
            .clickable { ChatState.selected = key }.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            if (collapsed) label.take(1) else label.take(9), maxLines = 1, fontWeight = FontWeight.SemiBold,
            color = if (danger) c.error else c.onSurface, style = MaterialTheme.typography.bodyMedium,
        )
        if (unread > 0) {
            Surface(shape = RoundedCornerShape(10.dp), color = if (danger) c.error else c.primary) {
                Text(if (collapsed) "" else "$unread", color = if (danger) c.onError else c.onPrimary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = if (collapsed) 4.dp else 6.dp, vertical = 2.dp))
            }
        }
    }
}

/**
 * Lớp phủ của ngăn kéo trên điện thoại: nền tối (chạm để đóng) + ngăn kéo trượt ra từ mép trái.
 * Viết thành hàm riêng (không nằm trong lambda của Column/Row) để `AnimatedVisibility` không bị nhầm sang bản mở rộng của ColumnScope.
 */
@Composable
private fun DrawerOverlay(visible: Boolean, sel: String, contacts: List<Pair<String, String>>, onPick: (String) -> Unit, onClose: () -> Unit) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).pointerInput(Unit) { detectTapGestures { onClose() } })
    }
    AnimatedVisibility(visible = visible, enter = slideInHorizontally { -it }, exit = slideOutHorizontally { -it }) {
        ChatDrawer(sel, contacts, onPick)
    }
}

/** Ngăn kéo cho điện thoại: tên đầy đủ (không cắt còn 1 chữ như thanh thu gọn), có số tin chưa đọc. */
@Composable
private fun ChatDrawer(sel: String, contacts: List<Pair<String, String>>, onPick: (String) -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(color = c.surface, shadowElevation = 8.dp, modifier = Modifier.width(280.dp).fillMaxHeight().pointerInput(Unit) { detectTapGestures { } }) {
        Column(Modifier.fillMaxSize()) {
            Text("Trò chuyện", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp))
            DrawerItem("SOS", sel == "SOS", MessageStore.unread("SOS"), danger = true) { onPick("SOS") }
            DrawerItem("Công khai", sel == "PUBLIC", MessageStore.unread("PUBLIC"), danger = false) { onPick("PUBLIC") }
            Divider1()
            SectionHeader("Liên hệ")
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                if (contacts.isEmpty()) {
                    Text("Chưa có liên hệ. Thêm trong Cài đặt › Liên hệ.", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
                contacts.forEach { (peer, name) -> DrawerItem(name, sel == peer, MessageStore.unread(peer), danger = false) { onPick(peer) } }
            }
        }
    }
}

@Composable
private fun DrawerItem(label: String, selected: Boolean, unread: Int, danger: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).background(if (selected) c.primaryContainer else c.surface).clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, maxLines = 1, fontWeight = FontWeight.SemiBold, color = if (danger) c.error else c.onSurface, modifier = Modifier.weight(1f))
        if (unread > 0) {
            Surface(shape = RoundedCornerShape(10.dp), color = if (danger) c.error else c.primary) {
                Text("$unread", color = if (danger) c.onError else c.onPrimary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- danh sách tin và ô nhập
@Composable
private fun MessageList(msgs: List<StoredMessage>, showSender: Boolean, modifier: Modifier) {
    val state = rememberLazyListState()
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) state.scrollToItem(msgs.size - 1) }      // tin mới nhất ở dưới cùng
    LazyColumn(modifier, state = state, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(msgs, key = { it.id }) { m -> Bubble(m, showSender) }
    }
}

private fun stateLabel(m: StoredMessage): String = when {
    !m.outgoing -> if (m.hops > 0) "hop ${m.hops}" else ""
    m.conv == "PUBLIC" || m.conv == "SOS" -> if (m.state >= 1) "đã phát" else "đang chờ máy gần"
    m.state >= 2 -> "đã nhận"
    m.state == 1 -> "đã chuyển đi"
    else -> "đang chờ máy gần"
}

@Composable
private fun Bubble(m: StoredMessage, showSender: Boolean) {
    val c = MaterialTheme.colorScheme
    val me = m.outgoing
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (me) Alignment.End else Alignment.Start) {
        Surface(shape = RoundedCornerShape(16.dp), color = if (me) c.primaryContainer else c.surface, border = BorderStroke(1.dp, c.outline.copy(alpha = 0.4f)), modifier = Modifier.widthIn(max = 280.dp)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (showSender && !me) Text(nameOrId(m.src, m.pinned), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = c.primary)
                m.image?.let { name ->
                    val bmp = remember(name) { BitmapFactory.decodeFile(MessageStore.imageFile(name).path)?.asImageBitmap() }
                    if (bmp != null) Image(bmp, contentDescription = "Ảnh", contentScale = ContentScale.Fit, modifier = Modifier.widthIn(max = 240.dp).heightIn(max = 240.dp))
                }
                // Giữ đè vào chữ để chọn và sao chép như văn bản bình thường trên điện thoại (chỉ chọn nội dung, không lẫn giờ gửi)
                if (m.text.isNotEmpty()) SelectionContainer { Text(m.text) }
                // Giờ gửi nhỏ và mờ (giờ điện thoại, không quy đổi múi giờ)
                Text(
                    listOf(timeFmt.format(Date(m.tMs)), stateLabel(m)).filter { it.isNotEmpty() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun InputBar(reminder: String?, placeholder: String, enabled: Boolean, onSend: (String) -> Unit, onPickImage: (() -> Unit)?) {
    var text by remember { mutableStateOf("") }
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(c.surface)) {
        if (reminder != null) {
            Text(reminder, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant.copy(alpha = 0.8f), modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp))
        }
        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onPickImage != null) TextButton(onClick = onPickImage, enabled = enabled) { Text("📎", fontSize = 20.sp) }
            OutlinedTextField(
                value = text, onValueChange = { text = it }, modifier = Modifier.weight(1f), enabled = enabled, maxLines = 4,
                placeholder = { Text(placeholder) }, shape = RoundedCornerShape(24.dp),
            )
            Button(onClick = { if (text.isNotBlank()) { onSend(text.trim()); text = "" } }, enabled = enabled && text.isNotBlank(), modifier = Modifier.padding(start = 6.dp)) { Text("Gửi") }
        }
    }
}

// ---------------------------------------------------------------- nhắn riêng
@Composable
private fun PrivatePane(peer: String) {
    val ver = MessageStore.version
    val msgs = remember(peer, ver) { MessageStore.conversation(peer) }
    val running = MeshController.state.running
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) MeshController.sendImage(peer, uri) }
    Column(Modifier.fillMaxSize()) {
        Text("Mã hóa đầu-cuối", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        MessageList(msgs, showSender = false, modifier = Modifier.weight(1f).fillMaxWidth())
        MeshController.state.notice?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 12.dp)) }
        InputBar(null, if (running) "Nhập tin nhắn…" else "Mesh chưa chạy", running, { MeshController.sendText(peer, it) }, { picker.launch("image/*") })
    }
}

// ---------------------------------------------------------------- công khai (tin + ảnh)
@Composable
private fun PublicPane() {
    val ver = MessageStore.version
    val msgs = remember(ver) { MessageStore.conversation("PUBLIC") }
    val running = MeshController.state.running
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun ask(action: () -> Unit) { if (AppSettings.warnPublicHidden) action() else pending = action }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) ask { MeshController.sendPublicImage(uri) } }
    Column(Modifier.fillMaxSize()) {
        MessageList(msgs, showSender = true, modifier = Modifier.weight(1f).fillMaxWidth())
        MeshController.state.notice?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 12.dp)) }
        InputBar(
            "Tin và ảnh công khai: ai cũng đọc được, kèm ID máy của bạn. Không spam, không quấy rối.",
            if (running) "Nhắn công khai…" else "Mesh chưa chạy", running, { t -> ask { MeshController.sendPublicText(t) } }, { picker.launch("image/*") },
        )
    }
    pending?.let { act ->
        WarningDialog(
            title = "Gửi công khai",
            text = "Tin và ảnh gửi đi sẽ được tất cả mọi người nhận được, kèm ID máy của bạn. Vui lòng chỉ gửi nội dung phù hợp, không spam, không quấy rối, không đăng tin sai sự thật!",
            extra = "Nội dung xấu có thể gây hại cho người khác. Bạn chịu trách nhiệm về những gì mình gửi.",
            checkboxLabel = "Không hiển thị lại thông báo này", confirmText = "Tôi hiểu, gửi",
            onConfirm = { hide -> if (hide) AppSettings.hidePublicWarning(); pending = null; act() }, onDismiss = { pending = null },
        )
    }
}

// ---------------------------------------------------------------- SOS
/** Một dòng trong mục SOS: thẻ SOS đã gộp (của người khác hoặc của mình) hoặc một ảnh SOS. Xếp theo giờ tăng dần: mới nhất ở dưới cùng. */
private sealed class SosRow(val t: Long, val key: String) {
    class Card(val item: SosFeed.Item, val msg: StoredMessage?, val mine: Boolean, val sentCount: Int) :
        SosRow(item.latest.atMs, "c-${item.latest.src.hex}-${item.latest.session}")
    class Img(val msg: StoredMessage) : SosRow(msg.tMs, "i-${msg.id}")
}

@Composable
private fun SosPane() {
    val ctx = LocalContext.current
    val ver = MessageStore.version
    val mesh = MeshController.state
    val emergency = PowerController.state.emergency
    val loc = remember { LocationProvider(ctx) }
    val selfHex = remember { ContactsController.identity.peerId.hex }
    val rows = remember(ver) { buildSosRows(MessageStore.conversation("SOS"), selfHex) }
    val share = PrivacySettings.shareLocation
    val toggleShare = rememberLocationToggle()
    var pendingSos by remember { mutableStateOf<(() -> Unit)?>(null) }
    var confirmEmergency by remember { mutableStateOf(false) }
    var exitDialog by remember { mutableStateOf(false) }
    fun askSos(action: () -> Unit) { if (AppSettings.warnSosHidden) action() else pendingSos = action }
    // Ảnh chọn ở mục SOS là ảnh SOS (nén nhỏ, vào mục SOS), tuyệt đối không phải ảnh công khai
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) askSos { MeshController.sendSosImage(uri) } }
    val listState = rememberLazyListState()
    LaunchedEffect(rows.size, rows.lastOrNull()?.key, rows.lastOrNull()?.t) { if (rows.isNotEmpty()) listState.scrollToItem(rows.lastIndex) }   // tin mới nhất ở dưới cùng

    Column(Modifier.fillMaxSize()) {
        if (emergency) EmergencyBanner(share, onToggleShare = toggleShare, onExit = { tryExitEmergency { exitDialog = true } })
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (rows.isEmpty()) item(key = "empty") {
                Text("Chưa có SOS nào. Tin SOS của mọi người ở gần (và tin bạn đã phát) sẽ hiện ở đây, kể cả khi bạn không ở chế độ khẩn cấp.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(rows, key = { it.key }) { r ->
                when (r) {
                    is SosRow.Card -> SosCard(r, loc)
                    is SosRow.Img -> Bubble(r.msg, showSender = true)
                }
            }
        }
        // ---- khu soạn SOS: chỉ gửi được khi đã bật chế độ khẩn cấp
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) {
            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!emergency) {
                    Text("Gửi SOS đang bị khóa", fontWeight = FontWeight.Bold)
                    Text("Để tránh bấm nhầm, chỉ gửi được SOS khi đã bật Chế độ khẩn cấp. Bạn vẫn đọc được mọi SOS.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { confirmEmergency = true }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Bật chế độ khẩn cấp…") }
                } else {
                    if (mesh.sos.active) MySosStatus(mesh.sos) { MeshController.markSafe(PrivacySettings.shareLocation) }
                    Text("Tin SOS giả sẽ làm chậm cứu hộ và có thể gây nguy hiểm cho người thật.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SosPresets.QUICK.forEach { p -> Pill(p.text, danger = true) { askSos { MeshController.sendSos(p.type.code, p.text, share) } } }
                        Pill("Tôi an toàn", selected = true) { MeshController.markSafe(share) }
                    }
                    mesh.notice?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                    InputBar(null, "Nhập lời nhắn SOS…", mesh.running, { t -> askSos { MeshController.sendSos(SosType.NEED_RESCUE.code, t, share) } }, { picker.launch("image/*") })
                }
            }
        }
    }
    if (confirmEmergency) {
        WarningDialog(
            title = "Bật chế độ khẩn cấp?", text = "Tiết kiệm pin tối đa và mở chức năng gửi SOS. Chỉ bật khi thật sự cần cứu hộ.",
            extra = "Vẫn nhận mọi tin gửi cho bạn. Chuyển tiếp hộ người khác chỉ còn text ngắn và ảnh nhỏ. Có nút tắt ngay đầu mục SOS.",
            confirmText = "Bật chế độ khẩn cấp", danger = true, dismissText = "Không",
            onConfirm = { PowerController.setEmergency(true); confirmEmergency = false }, onDismiss = { confirmEmergency = false },
        )
    }
    if (exitDialog) ExitEmergencyDialog { exitDialog = false }
    pendingSos?.let { act ->
        WarningDialog(
            title = "Trước khi gửi SOS",
            text = "Tất cả mọi người sẽ đọc được tin nhắn, ID máy của bạn, có thể cả vị trí. Vui lòng chỉ gửi đi trong trường hợp cần thiết!",
            extra = "Tin SOS giả, đùa cợt hoặc quấy rối làm chậm việc cứu hộ và có thể khiến người thật gặp nguy hiểm. Bạn chịu trách nhiệm về nội dung mình gửi, và ID máy của bạn đi kèm tin.",
            checkboxLabel = "Không hiển thị lại thông báo này", confirmText = "Tôi hiểu, gửi SOS", danger = true,
            onConfirm = { hide -> if (hide) AppSettings.hideSosWarning(); pendingSos = null; act() }, onDismiss = { pendingSos = null },
        )
    }
}

/**
 * Gộp tin trong mục SOS thành các dòng hiển thị. Tin SOS mình đã phát được lưu với src rỗng, nên truyền selfHex để nó cũng thành một thẻ
 * (nhiều lần phát lại của cùng một SOS gộp thành một thẻ, ghi rõ số lần đã phát).
 */
private fun buildSosRows(msgs: List<StoredMessage>, selfHex: String): List<SosRow> {
    val sos = msgs.filter { it.kind == "SOS" }
    val byKey = HashMap<Triple<String, Int, Int>, StoredMessage>()
    for (m in sos) byKey[Triple(m.src.ifEmpty { selfHex }, m.session, m.seq)] = m
    val sent = sos.filter { it.outgoing }.groupingBy { it.session }.eachCount()
    val cards = SosFeed.collapse(sos.mapNotNull { it.toSosEntry(selfHex) }).map { f ->
        val src = f.latest.src.hex
        SosRow.Card(f, byKey[Triple(src, f.latest.session, f.latest.seq)], src == selfHex, sent[f.latest.session] ?: 0)
    }
    val images = msgs.filter { it.kind == "IMAGE" }.map { SosRow.Img(it) }
    return (cards + images).sortedBy { it.t }
}

/** Dải đầu mục SOS khi đang ở chế độ khẩn cấp: nút tắt nhanh và công tắc chia sẻ vị trí (cập nhật ngay, không cần vào Cài đặt). */
@Composable
private fun EmergencyBanner(share: Boolean, onToggleShare: (Boolean) -> Unit, onExit: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(color = c.errorContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Chế độ khẩn cấp đang bật", fontWeight = FontWeight.Bold, color = c.onErrorContainer, modifier = Modifier.weight(1f))
                Button(
                    onClick = onExit, modifier = Modifier.heightIn(min = 44.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = c.error, contentColor = c.onError),
                ) { Text("Tắt khẩn cấp") }
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Chia sẻ vị trí trong SOS", color = c.onErrorContainer, modifier = Modifier.weight(1f))
                Switch(checked = share, onCheckedChange = onToggleShare)
            }
        }
    }
}

/** Trạng thái SOS của mình đang phát lại (gọn, đặt ngay trên ô soạn). */
@Composable
private fun MySosStatus(st: SosStatus, onSafe: () -> Unit) {
    var now by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(st.active) { while (st.active) { now = SystemClock.elapsedRealtime(); delay(1000) } }
    val c = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(12.dp), color = c.errorContainer) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("SOS của bạn đang được phát lại", fontWeight = FontWeight.Bold, color = c.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Đã phát ${st.sentCount} lần · kế sau ${fmtDuration(st.nextAtMs - now)} · tự dừng sau ${fmtDuration(st.endAtMs - now)}",
                    style = MaterialTheme.typography.labelSmall, color = c.onErrorContainer,
                )
            }
            Button(onClick = onSafe, modifier = Modifier.heightIn(min = 44.dp)) { Text("Tôi an toàn") }
        }
    }
}

@Composable
private fun SosCard(row: SosRow.Card, loc: LocationProvider) {
    val c = MaterialTheme.colorScheme
    val item = row.item
    val m = row.msg
    val p = item.latest.payload
    val lines = mutableListOf<String>()
    val pos = p.position
    if (row.mine) {
        lines += if (pos != null) "vị trí đã chia sẻ ${"%.5f".format(Locale.US, pos.latE6 / 1e6)}, ${"%.5f".format(Locale.US, pos.lonE6 / 1e6)}" + (pos.accuracyM?.let { " (±$it m)" } ?: "") else "không kèm vị trí"
        val n = row.sentCount.coerceAtLeast(1)
        lines += "đã phát $n lần · gần nhất ${timeFmt.format(Date(item.latest.atMs))}" + (m?.let { " · " + stateLabel(it) } ?: "")
    } else {
        if (pos != null) {
            lines += "vị trí ${"%.5f".format(Locale.US, pos.latE6 / 1e6)}, ${"%.5f".format(Locale.US, pos.lonE6 / 1e6)}" + (pos.accuracyM?.let { " (±$it m)" } ?: "")
            loc.lastKnownPoint()?.let { me -> lines += "cách bạn ${GeoMath.formatDistance(GeoMath.distanceM(me, pos))}, hướng ${GeoMath.compass(GeoMath.bearingDeg(me, pos))}" }
            if (p.fixAgeSec >= 60) lines += "vị trí lấy cách đây ${p.fixAgeSec / 60} phút"
        } else if (m != null) {
            lines += "không có vị trí: " + SosReach.describe(m.hops, m.ageSec * 1000L)     // V1: cận trên theo hop và tuổi tin
        }
        if (m != null) lines += "hop ${m.hops} · nhận ${timeFmt.format(Date(m.tMs))}" + if (p.seq > 0) " · cập nhật lần ${p.seq + 1}" else ""
    }
    Surface(
        shape = RoundedCornerShape(12.dp), color = if (item.resolved) c.surfaceVariant else if (row.mine) c.primaryContainer else c.surface,
        border = BorderStroke(1.dp, c.outline.copy(alpha = 0.4f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(shape = RoundedCornerShape(12.dp), color = if (p.type == SosType.SAFE) c.primaryContainer else c.errorContainer) {
                    Text(p.type.label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = if (p.type == SosType.SAFE) c.onPrimaryContainer else c.onErrorContainer, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
                }
                if (item.resolved && p.type != SosType.SAFE) Text("đã báo an toàn", style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
            }
            // Giữ đè để chọn và sao chép (tên máy, lời nhắn, tọa độ)
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (row.mine) "Bạn" else nameOrId(item.latest.src.hex, m?.pinned == true), fontWeight = FontWeight.SemiBold)
                    if (p.text.isNotEmpty()) Text(p.text)
                    Text(lines.joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                }
            }
        }
    }
}
