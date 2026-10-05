package com.offlinemesh.app

import android.app.Application

class MeshApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
    }
}
