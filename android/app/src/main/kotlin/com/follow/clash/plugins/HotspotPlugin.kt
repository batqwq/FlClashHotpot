package com.follow.clash.plugins

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import be.mygod.vpnhotspot.HotspotController
import be.mygod.vpnhotspot.root.WifiCountryCodeCommands
import be.mygod.vpnhotspot.util.readableMessage
import com.follow.clash.common.Components
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class HotspotPlugin : FlutterPlugin, MethodChannel.MethodCallHandler, ActivityAware {
    private lateinit var context: Context
    private lateinit var methodChannel: MethodChannel
    private lateinit var stateEventChannel: EventChannel
    private lateinit var quickStateEventChannel: EventChannel
    private lateinit var autoMatchEventChannel: EventChannel

    private var activityBinding: ActivityPluginBinding? = null
    private var activity: Activity? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var stateCollectJob: Job? = null
    private var quickStateCollectJob: Job? = null
    private var autoMatchCollectJob: Job? = null
    private var pendingPermissionAction: Pair<Int, (Boolean) -> Unit>? = null

    private val permissionListener = PluginRegistry.RequestPermissionsResultListener { requestCode, _, grantResults ->
        val pending = pendingPermissionAction ?: return@RequestPermissionsResultListener false
        if (pending.first != requestCode) return@RequestPermissionsResultListener false
        pendingPermissionAction = null
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        pending.second(granted)
        true
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        methodChannel = MethodChannel(binding.binaryMessenger, "${Components.PACKAGE_NAME}/hotspot")
        methodChannel.setMethodCallHandler(this)

        stateEventChannel = EventChannel(binding.binaryMessenger, "${Components.PACKAGE_NAME}/hotspot/state")
        stateEventChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                stateCollectJob?.cancel()
                HotspotController.startObserving()
                stateCollectJob = scope.launch {
                    HotspotController.snapshot.collect { snapshot ->
                        mainHandler.post { events.success(snapshot.toMap()) }
                    }
                }
            }

            override fun onCancel(arguments: Any?) {
                if (stateCollectJob != null) {
                    stateCollectJob?.cancel()
                    stateCollectJob = null
                    HotspotController.stopObserving()
                }
            }
        })

        quickStateEventChannel = EventChannel(binding.binaryMessenger, "${Components.PACKAGE_NAME}/hotspot/quick_state")
        quickStateEventChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                quickStateCollectJob?.cancel()
                quickStateCollectJob = scope.launch {
                    try {
                        HotspotController.observeQuickState().collect { events.success(it.toMap()) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        events.error("hotspot_quick_state", e.readableMessage, null)
                    }
                }
            }

            override fun onCancel(arguments: Any?) {
                quickStateCollectJob?.cancel()
                quickStateCollectJob = null
            }
        })

        autoMatchEventChannel = EventChannel(
            binding.binaryMessenger,
            "${Components.PACKAGE_NAME}/hotspot/auto_match_country_code",
        )
        autoMatchEventChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                autoMatchCollectJob?.cancel()
                autoMatchCollectJob = scope.launch {
                    HotspotController.observeAutoMatchCountryCode().collect { enabled ->
                        mainHandler.post { events.success(enabled) }
                    }
                }
            }

            override fun onCancel(arguments: Any?) {
                autoMatchCollectJob?.cancel()
                autoMatchCollectJob = null
            }
        })
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel.setMethodCallHandler(null)
        stateEventChannel.setStreamHandler(null)
        quickStateEventChannel.setStreamHandler(null)
        autoMatchEventChannel.setStreamHandler(null)
        if (stateCollectJob != null) {
            stateCollectJob?.cancel()
            stateCollectJob = null
            HotspotController.stopObserving()
        }
        autoMatchCollectJob?.cancel()
        autoMatchCollectJob = null
        quickStateCollectJob?.cancel()
        quickStateCollectJob = null
        detachFromActivity()
        scope.cancel()
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activityBinding?.removeRequestPermissionsResultListener(permissionListener)
        activityBinding = binding
        activity = binding.activity
        binding.addRequestPermissionsResultListener(permissionListener)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        detachFromActivity()
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }

    override fun onDetachedFromActivity() {
        detachFromActivity()
    }

    private fun detachFromActivity() {
        activityBinding?.removeRequestPermissionsResultListener(permissionListener)
        activityBinding = null
        activity = null
        pendingPermissionAction = null
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "getState" -> launchResult(result) {
                HotspotController.refresh().toMap()
            }

            "getAutoMatchCountryCode" -> result.success(WifiCountryCodeCommands.autoMatchEnabled)

            "setInterfaceManaged" -> {
                val iface = call.argument<String>("iface") ?: return result.error(ERROR_ARGUMENT, "Missing iface", null)
                val enabled = call.argument<Boolean>("enabled") ?: return result.error(ERROR_ARGUMENT, "Missing enabled", null)
                runSyncResult(result) {
                    HotspotController.setInterfaceManaged(activeContext(), iface, enabled)
                }
            }

            "setInterfaceMonitored" -> {
                val iface = call.argument<String>("iface") ?: return result.error(ERROR_ARGUMENT, "Missing iface", null)
                val enabled = call.argument<Boolean>("enabled") ?: return result.error(ERROR_ARGUMENT, "Missing enabled", null)
                runSyncResult(result) {
                    HotspotController.setInterfaceMonitored(activeContext(), iface, enabled)
                }
            }

            "setRepeaterEnabled" -> {
                val enabled = call.argument<Boolean>("enabled") ?: return result.error(ERROR_ARGUMENT, "Missing enabled", null)
                if (!enabled) {
                    runSyncResult(result) { HotspotController.setRepeaterEnabled(activeContext(), false) }
                } else {
                    ensureWifiPermission(REQUEST_CODE_REPEATER, result) { granted ->
                        if (granted) {
                            runSyncResult(result) { HotspotController.setRepeaterEnabled(activeContext(), true) }
                        } else {
                            result.error(ERROR_PERMISSION, "Required Wi-Fi/location permission denied", null)
                        }
                    }
                }
            }

            "setLocalOnlyHotspotEnabled" -> {
                val enabled = call.argument<Boolean>("enabled") ?: return result.error(ERROR_ARGUMENT, "Missing enabled", null)
                if (!enabled) {
                    runSyncResult(result) { HotspotController.setLocalOnlyHotspotEnabled(activeContext(), false) }
                } else {
                    ensureWifiPermission(REQUEST_CODE_LOCAL_ONLY, result) {
                        runSyncResult(result) { HotspotController.setLocalOnlyHotspotEnabled(activeContext(), true) }
                    }
                }
            }

            "setSystemTetheringEnabled" -> {
                val type = call.argument<String>("type") ?: return result.error(ERROR_ARGUMENT, "Missing type", null)
                val enabled = call.argument<Boolean>("enabled") ?: return result.error(ERROR_ARGUMENT, "Missing enabled", null)
                launchResult(result) {
                    HotspotController.setSystemTetheringEnabled(activeContext(), type, enabled)
                }
            }

            "setMasqueradeMode" -> {
                val mode = call.argument<String>("mode") ?: return result.error(ERROR_ARGUMENT, "Missing mode", null)
                runSyncResult(result) { HotspotController.setMasqueradeMode(mode) }
            }

            "setIpv6Mode" -> {
                val mode = call.argument<String>("mode") ?: return result.error(ERROR_ARGUMENT, "Missing mode", null)
                runSyncResult(result) { HotspotController.setIpv6Mode(mode) }
            }

            "setTetherOffloadEnabled" -> {
                val enabled = call.argument<Boolean>("enabled") ?: return result.error(ERROR_ARGUMENT, "Missing enabled", null)
                launchResult(result) {
                    HotspotController.setTetherOffloadEnabled(enabled)
                    null
                }
            }

            "setAutoStartEnabled" -> {
                val enabled = call.argument<Boolean>("enabled") ?: return result.error(ERROR_ARGUMENT, "Missing enabled", null)
                launchResult(result) {
                    HotspotController.setAutoStartEnabled(enabled)
                    null
                }
            }

            "setUpstreamInterfaces" -> {
                val primary = call.argument<String>("primary")
                val fallback = call.argument<String>("fallback")
                runSyncResult(result) { HotspotController.setUpstreamInterfaces(primary, fallback) }
            }

            "setAutoMatchCountryCode" -> {
                val enabled = call.argument<Boolean>("enabled") ?: return result.error(ERROR_ARGUMENT, "Missing enabled", null)
                launchResult(result) {
                    HotspotController.setAutoMatchCountryCode(enabled)
                    null
                }
            }

            "setCountryCode" -> {
                val countryCode = call.argument<String>("countryCode")
                launchResult(result) {
                    HotspotController.setCountryCode(countryCode)
                }
            }

            "cleanRouting" -> launchResult(result) {
                HotspotController.cleanRouting()
                null
            }

            "setAlwaysShareAll" -> {
                val enabled = call.argument<Boolean>("enabled")
                    ?: return result.error(ERROR_ARGUMENT, "Missing enabled", null)
                runSyncResult(result) { HotspotController.setAlwaysShareAll(activeContext(), enabled) }
            }

            "setMacWiredMode" -> {
                val enabled = call.argument<Boolean>("enabled")
                    ?: return result.error(ERROR_ARGUMENT, "Missing enabled", null)
                launchResult(result) {
                    HotspotController.setMacWiredMode(activeContext(), enabled)
                    null
                }
            }

            "openSystemTetheringSettings" -> runSyncResult(result) {
                HotspotController.openSystemTetheringSettings(activeContext())
            }

            else -> result.notImplemented()
        }
    }

    private fun activeContext(): Context = activity ?: context

    private fun ensureWifiPermission(
        requestCode: Int,
        result: MethodChannel.Result,
        onDone: (Boolean) -> Unit,
    ) {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
            onDone(true)
            return
        }
        val currentActivity = activity
        if (currentActivity == null) {
            onDone(false)
            return
        }
        if (pendingPermissionAction != null) {
            result.error(ERROR_IN_PROGRESS, "Permission request already in progress", null)
            return
        }
        pendingPermissionAction = requestCode to onDone
        ActivityCompat.requestPermissions(currentActivity, arrayOf(permission), requestCode)
    }

    private fun runSyncResult(result: MethodChannel.Result, block: () -> Any?) {
        try {
            val value = block()
            result.success(if (value === Unit) null else value)
        } catch (e: Exception) {
            result.error(ERROR_HOTSPOT, e.readableMessage, null)
        }
    }

    private fun launchResult(result: MethodChannel.Result, block: suspend () -> Any?) {
        scope.launch {
            try {
                val value = block()
                result.success(if (value === Unit) null else value)
            } catch (e: CancellationException) {
                result.error(ERROR_CANCELLED, e.message ?: "Cancelled", null)
            } catch (e: Exception) {
                result.error(ERROR_HOTSPOT, e.readableMessage, null)
            }
        }
    }

    private companion object {
        const val REQUEST_CODE_REPEATER = 2201
        const val REQUEST_CODE_LOCAL_ONLY = 2202
        const val ERROR_ARGUMENT = "INVALID_ARGUMENT"
        const val ERROR_PERMISSION = "PERMISSION_DENIED"
        const val ERROR_IN_PROGRESS = "IN_PROGRESS"
        const val ERROR_CANCELLED = "CANCELLED"
        const val ERROR_HOTSPOT = "HOTSPOT_ERROR"
    }
}
