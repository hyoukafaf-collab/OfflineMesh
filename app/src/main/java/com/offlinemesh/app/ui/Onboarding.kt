package com.offlinemesh.app.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import com.offlinemesh.app.data.AppGate
import com.offlinemesh.app.data.AppSettings

private val TERMS = listOf(
    "Ứng dụng đang trong giai đoạn phát triển (thử nghiệm). Có thể có lỗi; tin có thể đến chậm, không đến, hoặc bị mất. Không đảm bảo giao tin.",
    "Ứng dụng KHÔNG thay thế dịch vụ khẩn cấp. Nếu còn gọi được, hãy gọi số khẩn cấp của địa phương.",
    "Tin SOS và tin, ảnh công khai được mọi người ở gần đọc được, kèm ID máy của bạn. Ứng dụng không kiểm duyệt và hiện không đủ khả năng chặn nội dung xấu.",
    "Tiêu chuẩn cộng đồng: không gửi tin giả, đùa cợt SOS, quấy rối, lừa đảo, thù ghét, đe dọa hay spam. Ứng dụng chưa có cơ chế xử lý tập trung; bạn chịu trách nhiệm về nội dung mình gửi, và vi phạm có thể gây hậu quả cho người khác và cho chính bạn.",
    "Cần bật Bluetooth để gửi và nhận tin. Cần cho phép chạy nền để nhận tin khi tắt màn hình; Android sẽ hiện một thông báo nhỏ, im lặng khi ứng dụng chạy nền.",
    "Vị trí chỉ được gửi khi bạn tự bật công tắc chia sẻ vị trí trong Cài đặt.",
)

/** Lần đầu dùng: điều khoản → Bluetooth → chạy nền (hỏi một lần). Xong thì không hiện lại. */
@Composable
fun Onboarding() {
    val ctx = LocalContext.current
    var step by remember { mutableStateOf(0) }
    var agreed by remember { mutableStateOf(false) }
    val enableBt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { AppGate.refresh(ctx) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        AppGate.refresh(ctx)
        if (!AppGate.btOn) runCatching { enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Bước ${step + 1}/3", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (step) {
            0 -> {
                Text("Điều khoản sử dụng", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TERMS.forEachIndexed { i, t -> Text("${i + 1}. $t") }
                    Text("(Nội dung mẫu cho bản thử nghiệm, cần rà soát pháp lý trước khi phát hành rộng rãi.)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { agreed = !agreed }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = agreed, onCheckedChange = { agreed = it })
                    Text("Tôi đã đọc và đồng ý")
                }
                Button(onClick = { step = 1 }, enabled = agreed, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Tiếp") }
            }
            1 -> {
                Text("Bật Bluetooth", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Không có Internet, tin đi qua Bluetooth của các máy ở gần nhau. Cần cấp quyền Bluetooth (và thông báo) và bật Bluetooth để nhận tin.")
                Text(if (AppGate.btOn) "Bluetooth: đã bật ✓" else "Bluetooth: đang tắt", color = if (AppGate.btOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                Spacer(Modifier.weight(1f))
                OutlinedButton(
                    onClick = {
                        val list = AppGate.requiredPermissions() + if (Build.VERSION.SDK_INT >= 33) listOf(android.Manifest.permission.POST_NOTIFICATIONS) else emptyList()
                        perms.launch(list.toTypedArray())
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) { Text("Cấp quyền và bật Bluetooth") }
                Button(onClick = { step = 2 }, enabled = AppGate.btOn, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Tiếp") }
                TextButton(onClick = { step = 2 }, modifier = Modifier.fillMaxWidth()) { Text("Để sau") }
            }
            else -> {
                Text("Cho phép chạy nền", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Để nhận tin và SOS khi tắt màn hình, ứng dụng cần được chạy nền. Màn này chỉ hỏi một lần lúc đầu; sau này nếu thấy chưa được cho phép, ứng dụng sẽ nhắc lại mỗi lần mở.")
                Text(if (AppGate.bgAllowed) "Chạy nền: đã cho phép ✓" else "Chạy nền: chưa cho phép", color = if (AppGate.bgAllowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { openBackgroundSettings(ctx) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Cho phép chạy nền") }
                Button(
                    onClick = { AppSettings.acceptTerms(); AppGate.refresh(ctx); AppGate.ensureMesh(ctx) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) { Text("Xong, vào ứng dụng") }
            }
        }
    }
}

fun openBackgroundSettings(ctx: android.content.Context) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
    val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    if (runCatching { ctx.startActivity(direct) }.isFailure) runCatching { ctx.startActivity(list) }
}

/** Hiện mỗi lần mở app khi thấy chưa được cho phép chạy nền; vẫn cho vào app ("Để sau"). */
@Composable
fun BackgroundPrompt() {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Cho phép chạy nền để nhận tin khi tắt màn hình", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Nếu ứng dụng bị Android đưa vào trạng thái ngủ, bạn sẽ bỏ lỡ tin và SOS.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("1. Bấm \"Mở cài đặt\" và chọn Cho phép (hoặc: Cài đặt › Ứng dụng › OfflineMesh › Pin › Không giới hạn).")
        Text("2. Samsung: Pin › Giới hạn dùng nền › thêm vào \"Ứng dụng không bao giờ ngủ\".")
        Text("3. Android bắt buộc một thông báo nhỏ khi ứng dụng chạy nền; ứng dụng đặt nó ở mức im lặng, thu gọn.")
        Spacer(Modifier.weight(1f))
        Button(onClick = { openBackgroundSettings(ctx) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Mở cài đặt") }
        OutlinedButton(onClick = { AppGate.bgSkipped = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Để sau, vẫn vào ứng dụng") }
        Text("Màn này hiện lại mỗi lần mở ứng dụng cho đến khi bạn cho phép.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
