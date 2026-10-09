package com.offlinemesh.app

import android.app.Application
import com.offlinemesh.app.crypto.ContactsController

class MeshApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        ContactsController.init(this)      // tạo/đọc danh tính (khóa riêng được Keystore bọc) và danh bạ
    }
}
