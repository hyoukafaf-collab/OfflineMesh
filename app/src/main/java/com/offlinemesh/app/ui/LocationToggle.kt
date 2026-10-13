package com.offlinemesh.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.offlinemesh.app.settings.PrivacySettings

private fun hasLocationPermission(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/**
 * Hàm bật/tắt "Chia sẻ vị trí trong SOS" dùng chung cho Cài đặt và mục SOS. Bật thì xin quyền vị trí nếu chưa có;
 * trạng thái nằm ở PrivacySettings (trạng thái Compose) nên mọi công tắc hiển thị nó đều cập nhật ngay.
 */
@Composable
fun rememberLocationToggle(): (Boolean) -> Unit {
    val ctx = LocalContext.current
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        PrivacySettings.setShareLocation(r.values.any { it })
    }
    return { on ->
        if (!on) PrivacySettings.setShareLocation(false)
        else if (hasLocationPermission(ctx)) PrivacySettings.setShareLocation(true)
        else request.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
}
