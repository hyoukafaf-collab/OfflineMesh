package com.offlinemesh.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Màu theo bản thiết kế: xanh ngọc cho hành động chính, đỏ cho SOS; đủ tương phản ở cả sáng và tối. */
@Composable
fun OfflineMeshTheme(dark: Boolean, content: @Composable () -> Unit) {
    val scheme = if (dark) darkColorScheme(
        primary = Color(0xFF5CC7B6), onPrimary = Color(0xFF00201C), primaryContainer = Color(0xFF1E4A43), onPrimaryContainer = Color(0xFFCDEFE8),
        background = Color(0xFF0F1A18), onBackground = Color(0xFFE3ECE9), surface = Color(0xFF16221F), onSurface = Color(0xFFE3ECE9),
        surfaceVariant = Color(0xFF22322E), onSurfaceVariant = Color(0xFFA9B8B4), outline = Color(0xFF3D4F4A),
        error = Color(0xFFFFB4AB), onError = Color(0xFF410002), errorContainer = Color(0xFF5A1F1B), onErrorContainer = Color(0xFFFFDAD6),
    ) else lightColorScheme(
        primary = Color(0xFF0F6B5F), onPrimary = Color.White, primaryContainer = Color(0xFFDDF0EC), onPrimaryContainer = Color(0xFF0A4F46),
        background = Color(0xFFF6F7F5), onBackground = Color(0xFF1B2B28), surface = Color.White, onSurface = Color(0xFF1B2B28),
        surfaceVariant = Color(0xFFEEF2F0), onSurfaceVariant = Color(0xFF62706C), outline = Color(0xFFC9D3CF),
        error = Color(0xFFB3261E), onError = Color.White, errorContainer = Color(0xFFFBE7E5), onErrorContainer = Color(0xFF8C1D18),
    )
    MaterialTheme(colorScheme = scheme, content = content)
}
