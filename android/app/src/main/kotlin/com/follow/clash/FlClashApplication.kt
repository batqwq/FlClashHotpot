package com.follow.clash

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import be.mygod.vpnhotspot.App as VpnHotspotApp
import com.follow.clash.common.GlobalState

class FlClashApplication : Application() {
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        GlobalState.init(this)
        VpnHotspotApp.attachBaseContext(this)
    }

    override fun onCreate() {
        super.onCreate()
        VpnHotspotApp.onCreate()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        VpnHotspotApp.onConfigurationChanged()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        VpnHotspotApp.onTrimMemory(level)
    }
}
