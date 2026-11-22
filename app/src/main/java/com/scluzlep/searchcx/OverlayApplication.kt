package com.scluzlep.searchcx

import android.app.Application
import com.scluzlep.searchcx.xposed.ModuleStatus

class OverlayApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ModuleStatus.init()
    }
}
