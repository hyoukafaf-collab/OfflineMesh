package com.offlinemesh.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.offlinemesh.app.data.AppGate
import com.offlinemesh.app.data.AppSettings

@Composable
fun AppRoot() {
    val dark = when (AppSettings.theme) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    OfflineMeshTheme(dark) {
        Surface(Modifier.fillMaxSize().systemBarsPadding().imePadding(), color = MaterialTheme.colorScheme.background) {
            when {
                !AppSettings.restoreAsked -> FirstRunChoice()                 // hỏi một lần: khôi phục từ tệp sao lưu hay bắt đầu mới
                !AppSettings.termsAccepted -> Onboarding()
                AppGate.showBackgroundPrompt -> BackgroundPrompt()
                else -> MainShell()
            }
        }
    }
}

/** Điều hướng đơn giản bằng trạng thái: chat ⇄ cài đặt ⇄ các màn con. */
@Composable
fun MainShell() {
    var screen by remember { mutableStateOf("chat") }
    BackHandler(enabled = screen != "chat") { screen = if (screen == "settings") "chat" else "settings" }
    when (screen) {
        "chat" -> ChatScreen(openSettings = { screen = "settings" })
        "settings" -> SettingsHome(onBack = { screen = "chat" }, go = { screen = it })
        "profile" -> ProfileScreen { screen = "settings" }
        "contacts" -> ContactsManageScreen { screen = "settings" }
        "battery" -> BatteryScreen { screen = "settings" }
        "replay" -> ReplayScreen { screen = "settings" }
        "retention" -> RetentionScreen { screen = "settings" }
        "share" -> ShareScreen { screen = "settings" }
        "backup" -> BackupScreen { screen = "settings" }
    }
}
