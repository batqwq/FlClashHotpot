package be.mygod.vpnhotspot.root

import android.net.wifi.WifiManager
import android.os.Parcelable
import androidx.core.content.edit
import be.mygod.librootkotlinx.ParcelableBoolean
import be.mygod.librootkotlinx.RootCommand
import be.mygod.librootkotlinx.io.awaitExit
import be.mygod.vpnhotspot.App.Companion.app
import be.mygod.vpnhotspot.util.Services
import be.mygod.vpnhotspot.util.UnblockCentral
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.Locale

object WifiCountryCodeCommands {
    const val KEY_AUTO_MATCH = "service.hotspot.autoMatchCountryCode"
    const val KEY_APPLIED_COUNTRY_CODE = "service.hotspot.appliedCountryCode"

    private const val CMD_BINARY = "/system/bin/cmd"
    private val INVALID_CODES = setOf("00", "XX", "ZZ", "T1", "EU")
    private val COUNTRY_CODE_LINE = Regex("""(?:^|[=:\s])([A-Za-z]{2})(?:\s|$)""")

    var autoMatchEnabled: Boolean
        get() = app.pref.getBoolean(KEY_AUTO_MATCH, false)
        set(value) = app.pref.edit { putBoolean(KEY_AUTO_MATCH, value) }

    var appliedCountryCode: String?
        get() = normalizeCountryCode(app.pref.getString(KEY_APPLIED_COUNTRY_CODE, null))
        set(value) = app.pref.edit { putString(KEY_APPLIED_COUNTRY_CODE, normalizeCountryCode(value)) }

    fun normalizeCountryCode(raw: String?): String? {
        val code = raw?.trim()?.uppercase(Locale.US) ?: return null
        if (code.length != 2 || code.any { it !in 'A'..'Z' }) return null
        if (code in INVALID_CODES) return null
        return code
    }

    internal fun parseCountryCodeOutput(output: String): String? {
        for (line in output.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val afterEquals = trimmed.substringAfter('=', "").trim()
            if (afterEquals.isNotEmpty()) {
                normalizeCountryCode(afterEquals)?.let { return it }
            }
            if (trimmed.length == 2) {
                normalizeCountryCode(trimmed)?.let { return it }
            }
            val match = COUNTRY_CODE_LINE.findAll(trimmed).lastOrNull()?.groupValues?.get(1)
            normalizeCountryCode(match)?.let { return it }
        }
        return null
    }

    @Parcelize
    data class CountryCodeResult(val value: String?) : Parcelable

    @Parcelize
    class GetCountryCode : RootCommand<CountryCodeResult> {
        override suspend fun execute(): CountryCodeResult {
            readViaWifiService()?.let { return CountryCodeResult(it) }
            val result = runProcess(CMD_BINARY, "wifi", "get-country-code")
            return CountryCodeResult(result?.let(::parseCountryCodeOutput))
        }
    }

    @Parcelize
    data class SetCountryCode(val countryCode: String) : RootCommand<ParcelableBoolean> {
        override suspend fun execute(): ParcelableBoolean {
            val normalized = normalizeCountryCode(countryCode)
                ?: throw IllegalArgumentException("Invalid ISO 3166-1 alpha-2 country code: $countryCode")
            if (runProcessOk(CMD_BINARY, "wifi", "force-country-code", "enabled", normalized)) {
                return ParcelableBoolean(true)
            }
            if (invokeWifiService("setOverrideCountryCode", normalized) ||
                invokeWifiService("setCountryCode", normalized)) {
                return ParcelableBoolean(true)
            }
            for (iw in arrayOf("/system/bin/iw", "/vendor/bin/iw")) {
                if (File(iw).canExecute() && runProcessOk(iw, "reg", "set", normalized)) {
                    return ParcelableBoolean(true)
                }
            }
            return ParcelableBoolean(false)
        }
    }

    @Parcelize
    class ClearCountryCode : RootCommand<ParcelableBoolean> {
        override suspend fun execute(): ParcelableBoolean {
            if (runProcessOk(CMD_BINARY, "wifi", "force-country-code", "disabled")) {
                return ParcelableBoolean(true)
            }
            if (invokeWifiServiceNoArgs("clearOverrideCountryCode")) {
                return ParcelableBoolean(true)
            }
            return ParcelableBoolean(false)
        }
    }

    private fun readViaWifiService(): String? {
        try {
            val method = WifiManager::class.java.getMethod("getCountryCode")
            normalizeCountryCode(method.invoke(Services.wifi) as? String)?.let { return it }
        } catch (e: ReflectiveOperationException) {
            Timber.d(e)
        } catch (e: SecurityException) {
            Timber.d(e)
        }
        return try {
            val service = UnblockCentral.WifiManager_mService.get(Services.wifi) ?: return null
            val method = service.javaClass.getMethod("getCountryCode")
            normalizeCountryCode(method.invoke(service) as? String)
        } catch (e: ReflectiveOperationException) {
            Timber.d(e)
            null
        } catch (e: SecurityException) {
            Timber.d(e)
            null
        }
    }

    private fun invokeWifiService(methodName: String, arg: String): Boolean {
        return try {
            val service = UnblockCentral.WifiManager_mService.get(Services.wifi) ?: return false
            val method = service.javaClass.getMethod(methodName, String::class.java)
            method.invoke(service, arg)
            true
        } catch (e: ReflectiveOperationException) {
            Timber.d(e)
            false
        } catch (e: SecurityException) {
            Timber.d(e)
            false
        }
    }

    private fun invokeWifiServiceNoArgs(methodName: String): Boolean {
        return try {
            val service = UnblockCentral.WifiManager_mService.get(Services.wifi) ?: return false
            val method = service.javaClass.getMethod(methodName)
            method.invoke(service)
            true
        } catch (e: ReflectiveOperationException) {
            Timber.d(e)
            false
        } catch (e: SecurityException) {
            Timber.d(e)
            false
        }
    }

    private suspend fun runProcess(vararg command: String): String? = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder(*command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (process.awaitExit() == 0) output else null
        } catch (e: IOException) {
            Timber.d(e)
            null
        }
    }

    private suspend fun runProcessOk(vararg command: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder(*command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.awaitExit()
            if (exitCode != 0) {
                Timber.d("Command %s exited with %d: %s", command.joinToString(" "), exitCode, output.trim())
            }
            exitCode == 0
        } catch (e: IOException) {
            Timber.d(e)
            false
        }
    }
}
