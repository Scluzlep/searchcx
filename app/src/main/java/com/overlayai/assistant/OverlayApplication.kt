package com.overlayai.assistant

import android.app.Application
import com.overlayai.assistant.xposed.ModuleStatus

class OverlayApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ModuleStatus.init()
    }
}
