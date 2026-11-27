package be.mygod.vpnhotspot.net

import android.content.IntentFilter
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import be.mygod.librootkotlinx.ParcelableBoolean
import be.mygod.librootkotlinx.RootCommand
import be.mygod.vpnhotspot.App.Companion.app
import be.mygod.vpnhotspot.root.RootManager
import be.mygod.vpnhotspot.root.SettingsGlobalPut
import be.mygod.vpnhotspot.root.TetheringCommands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.parcelize.Parcelize
import java.io.IOException

internal object MacUsbMode {
    private const val FORCE_USB_FUNCTION = "tether_force_usb_functions"
    private const val OVERRIDE_PENDING = "service.hotspot.macUsbOverridePending"
    private const val PREVIOUS_FUNCTION = "service.hotspot.macUsbPreviousFunction"
    private const val NCM = "1"

    val supported get() = Build.VERSION.SDK_INT >= 34
    val hasOverride get() = app.pref.getBoolean(OVERRIDE_PENDING, false)
    private val ncmGadgetActive get() = app.registerReceiver(null, IntentFilter("android.hardware.usb.action.USB_STATE"))
        ?.getBooleanExtra("ncm", false) == true
    @Volatile
    var active = false
        private set

    fun tetherType(iface: String): TetherType {
        val systemType = TetherType.ofInterface(iface)
        return if (systemType == TetherType.USB && active && ncmGadgetActive) TetherType.NCM else systemType
    }

    @RequiresApi(30)
    suspend fun start() = withContext(Dispatchers.IO) {
        val previous = Settings.Global.getString(app.contentResolver, FORCE_USB_FUNCTION)
        check(app.pref.edit().putBoolean(OVERRIDE_PENDING, true).putString(PREVIOUS_FUNCTION, previous).commit())
        try {
            if (previous != NCM) writeFunction(NCM)
            val accepted = withTimeoutOrNull(5000) {
                RootManager.use { server ->
                    while (!server.execute(PlatformUsesNcm()).value) delay(200)
                }
                true
            } ?: false
            if (!accepted) throw IOException("Android did not accept the USB NCM compatibility setting")
            RootManager.use { it.execute(TetheringCommands.Start(TetheringManagerCompat.TETHERING_USB, true)) }
            active = true
        } catch (e: Exception) {
            try {
                restore()
            } catch (cleanup: Exception) {
                e.addSuppressed(cleanup)
            }
            throw e
        }
    }

    @RequiresApi(30)
    suspend fun stop() {
        try {
            if (active && Settings.Global.getString(app.contentResolver, FORCE_USB_FUNCTION) == NCM) {
                RootManager.use { it.execute(TetheringCommands.Stop(TetheringManagerCompat.TETHERING_USB)) }
            }
        } finally {
            restore()
        }
    }

    suspend fun restore() = withContext(NonCancellable + Dispatchers.IO) {
        if (!hasOverride) return@withContext
        val previous = app.pref.getString(PREVIOUS_FUNCTION, null)
        if (previous != NCM && Settings.Global.getString(app.contentResolver, FORCE_USB_FUNCTION) == NCM) {
            writeFunction(previous)
            check(Settings.Global.getString(app.contentResolver, FORCE_USB_FUNCTION) == previous) {
                "Could not restore the previous Android USB tethering setting"
            }
        }
        check(app.pref.edit().remove(OVERRIDE_PENDING).remove(PREVIOUS_FUNCTION).commit())
        active = false
    }

    private suspend fun writeFunction(value: String?) {
        RootManager.use { it.execute(SettingsGlobalPut(FORCE_USB_FUNCTION, value)) }
    }

    @Parcelize
    private class PlatformUsesNcm : RootCommand<ParcelableBoolean> {
        override suspend fun execute() = withContext(Dispatchers.IO) {
            val process = ProcessBuilder("/system/bin/dumpsys", "-t", "1", "tethering")
                .redirectErrorStream(true).start()
            try {
                val found = process.inputStream.bufferedReader().useLines { lines ->
                    lines.fold(false) { matched, line -> matched || line.trim() == "mUsbTetheringFunction: NCM" }
                }
                ParcelableBoolean(process.waitFor() == 0 && found)
            } finally {
                process.destroy()
            }
        }
    }
}
