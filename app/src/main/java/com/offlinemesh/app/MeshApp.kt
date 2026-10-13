package com.offlinemesh.app

import android.app.Application
import com.offlinemesh.app.crypto.ContactsController
import com.offlinemesh.app.data.AppSettings
import com.offlinemesh.app.data.MessageStore
import com.offlinemesh.app.data.Notifier
import com.offlinemesh.app.power.PowerController
import com.offlinemesh.app.settings.PrivacySettings
import com.offlinemesh.core.mesh.settings.Retention

class MeshApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppSettings.init(this)
        PrivacySettings.init(this)
        PowerController.init(this)
        ContactsController.init(this)      // tạo/đọc danh tính (khóa riêng được Keystore bọc) và danh bạ
        MessageStore.init(this)
        Notifier.init(this)
        MessageStore.purgeOlderThan(Retention.cutoffMs(System.currentTimeMillis(), AppSettings.retentionDays))   // tự xóa tin quá hạn lưu
    }
}
