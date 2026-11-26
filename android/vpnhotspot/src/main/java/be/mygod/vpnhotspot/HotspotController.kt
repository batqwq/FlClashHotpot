package be.mygod.vpnhotspot

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.MacAddress
import android.net.TetheringManager
import android.net.wifi.p2p.WifiP2pGroup
import android.os.Build
import android.provider.Settings
import androidx.collection.ScatterSet
import androidx.collection.emptyScatterSet
import androidx.core.content.edit
import androidx.core.net.toUri
import be.mygod.vpnhotspot.App.Companion.app
import be.mygod.vpnhotspot.manage.ManageBar
import be.mygod.vpnhotspot.net.NetlinkNeighbour
import be.mygod.vpnhotspot.net.Routing
import be.mygod.vpnhotspot.net.TetherOffloadManager
import be.mygod.vpnhotspot.net.TetherStates
import be.mygod.vpnhotspot.net.TetherType
import be.mygod.vpnhotspot.net.TetheringManagerCompat
import be.mygod.vpnhotspot.net.monitor.Upstream
import be.mygod.vpnhotspot.net.monitor.Upstreams
import be.mygod.vpnhotspot.net.wifi.SoftApConfigurationCompat
import be.mygod.vpnhotspot.net.wifi.WifiApManager
import be.mygod.vpnhotspot.room.AppDatabase
import be.mygod.vpnhotspot.root.RootManager
import be.mygod.vpnhotspot.root.WifiApCommands
import be.mygod.vpnhotspot.root.WifiCountryCodeCommands
import be.mygod.vpnhotspot.root.daemon.MasqueradeMode
import be.mygod.vpnhotspot.root.daemon.NeighbourState
import be.mygod.vpnhotspot.util.Services
import be.mygod.vpnhotspot.util.UnblockCentral
import be.mygod.vpnhotspot.util.allInterfaceNames
import be.mygod.vpnhotspot.util.bindServiceFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import java.net.NetworkInterface
import java.net.SocketException

object HotspotController {
    data class InterfaceSnapshot(
        val name: String,
        val type: String,
        val managed: Boolean,
        val monitored: Boolean,
        val inactive: Boolean,
        val addresses: List<String>,
    ) {
        fun toMap(): Map<String, Any?> = mapOf(
            "name" to name,
            "type" to type,
            "managed" to managed,
            "monitored" to monitored,
            "inactive" to inactive,
            "addresses" to addresses,
        )
    }

    data class ClientSnapshot(
        val mac: String,
        val iface: String?,
        val type: String,
        val nickname: String,
        val blocked: Boolean,
        val active: Boolean,
        val addresses: List<String>,
    ) {
        fun toMap(): Map<String, Any?> = mapOf(
            "mac" to mac,
            "iface" to iface,
            "type" to type,
            "nickname" to nickname,
            "blocked" to blocked,
            "active" to active,
            "addresses" to addresses,
        )
    }

    data class Snapshot(
        val supported: Boolean = true,
        val vpnUpstream: String? = null,
        val fallbackUpstream: String? = null,
        val primaryPreference: String = "",
        val fallbackPreference: String = "",
        val masqueradeMode: String = "simple",
        val ipv6Mode: String = "block",
        val tetherOffloadEnabled: Boolean = false,
        val autoStart: Boolean = false,
        val alwaysShareAll: Boolean = false,
        val macWiredMode: Boolean = false,
        val autoMatchCountryCode: Boolean = false,
        val currentCountryCode: String? = null,
        val appliedCountryCode: String? = null,
        val repeaterSupported: Boolean = false,
        val repeaterActive: Boolean = false,
        val repeaterInterface: String? = null,
        val repeaterNetworkName: String? = null,
        val localOnlyActive: Boolean = false,
        val localOnlyInterface: String? = null,
        val localOnlySsid: String? = null,
        val systemHotspotActive: Boolean = false,
        val usbTetheringActive: Boolean = false,
        val ncmTetheringActive: Boolean = false,
        val ncmSupported: Boolean = Build.VERSION.SDK_INT >= 30,
        val ethernetTetheringActive: Boolean = false,
        val ethernetSupported: Boolean = Build.VERSION.SDK_INT >= 30,
        val interfaces: List<InterfaceSnapshot> = emptyList(),
        val clients: List<ClientSnapshot> = emptyList(),
        val activeClientCount: Int = 0,
    ) {
        fun toMap(): Map<String, Any?> = mapOf(
            "supported" to supported,
            "vpnUpstream" to vpnUpstream,
            "fallbackUpstream" to fallbackUpstream,
            "primaryPreference" to primaryPreference,
            "fallbackPreference" to fallbackPreference,
            "masqueradeMode" to masqueradeMode,
            "ipv6Mode" to ipv6Mode,
            "tetherOffloadEnabled" to tetherOffloadEnabled,
            "autoStart" to autoStart,
            "alwaysShareAll" to alwaysShareAll,
            "macWiredMode" to macWiredMode,
            "autoMatchCountryCode" to autoMatchCountryCode,
            "currentCountryCode" to currentCountryCode,
            "appliedCountryCode" to appliedCountryCode,
            "repeaterSupported" to repeaterSupported,
            "repeaterActive" to repeaterActive,
            "repeaterInterface" to repeaterInterface,
            "repeaterNetworkName" to repeaterNetworkName,
            "localOnlyActive" to localOnlyActive,
            "localOnlyInterface" to localOnlyInterface,
            "localOnlySsid" to localOnlySsid,
            "systemHotspotActive" to systemHotspotActive,
            "usbTetheringActive" to usbTetheringActive,
            "ncmTetheringActive" to ncmTetheringActive,
            "ncmSupported" to ncmSupported,
            "ethernetTetheringActive" to ethernetTetheringActive,
            "ethernetSupported" to ethernetSupported,
            "interfaces" to interfaces.map { it.toMap() },
            "clients" to clients.map { it.toMap() },
            "activeClientCount" to activeClientCount,
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1, "HotspotController"))
    private val _snapshot = MutableStateFlow(readStaticSnapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    private var observerCount = 0
    private var observerJob: Job? = null

    private var tetherStates = TetherStates()
    private var primaryUpstream: Upstream? = null
    private var fallbackUpstream: Upstream? = null
    private var managedIfaces: ScatterSet<String> = emptyScatterSet()
    private var inactiveIfaces: ScatterSet<String> = emptyScatterSet()
    private var monitoredIfaces: ScatterSet<String> = emptyScatterSet()
    private var repeaterBinder: RepeaterService.Binder? = null
    private var repeaterActive = false
    private var repeaterGroup: WifiP2pGroup? = null
    private var localOnlyBinder: LocalOnlyHotspotService.Binder? = null
    private var localOnlyIface: String? = null
    private var localOnlyConfig: SoftApConfigurationCompat? = null
    private var neighbourSnapshot = NetlinkNeighbour.Snapshot()
    private var cachedCountryCode: String? = null

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        scope.launch { rebuildSnapshot() }
    }

    fun observeAutoMatchCountryCode(): Flow<Boolean> = callbackFlow {
        trySend(WifiCountryCodeCommands.autoMatchEnabled)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == WifiCountryCodeCommands.KEY_AUTO_MATCH) {
                trySend(WifiCountryCodeCommands.autoMatchEnabled)
            }
        }
        app.pref.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { app.pref.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun startObserving() {
        synchronized(this) {
            observerCount++
            if (observerCount > 1) return
        }
        app.pref.registerOnSharedPreferenceChangeListener(prefListener)
        observerJob = scope.launch {
            launch {
                TetherStates.flow.collect {
                    tetherStates = it
                    rebuildSnapshot()
                }
            }
            launch {
                Upstreams.primary.collect {
                    primaryUpstream = it
                    rebuildSnapshot()
                }
            }
            launch {
                Upstreams.fallback.collect {
                    fallbackUpstream = it
                    rebuildSnapshot()
                }
            }
            launch {
                try {
                    app.bindServiceFlow(Intent(app, TetheringService::class.java)).collectLatest { raw ->
                        val binder = raw as? TetheringService.Binder
                        if (binder == null) {
                            managedIfaces = emptyScatterSet()
                            inactiveIfaces = emptyScatterSet()
                            monitoredIfaces = emptyScatterSet()
                            rebuildSnapshot()
                        } else coroutineScope {
                            launch {
                                binder.managedIfaces.collect {
                                    managedIfaces = it
                                    rebuildSnapshot()
                                }
                            }
                            launch {
                                binder.inactiveIfaces.collect {
                                    inactiveIfaces = it
                                    rebuildSnapshot()
                                }
                            }
                            launch {
                                binder.monitoredIfaces.collect {
                                    monitoredIfaces = it
                                    rebuildSnapshot()
                                }
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e)
                }
            }
            if (Services.p2p != null) {
                launch {
                    try {
                        app.bindServiceFlow(Intent(app, RepeaterService::class.java)).collectLatest { raw ->
                            val binder = raw as? RepeaterService.Binder
                            repeaterBinder = binder
                            if (binder == null) {
                                repeaterActive = false
                                repeaterGroup = null
                                rebuildSnapshot()
                            } else coroutineScope {
                                launch {
                                    binder.active.collect {
                                        repeaterActive = it
                                        rebuildSnapshot()
                                    }
                                }
                                launch {
                                    binder.group.collect {
                                        repeaterGroup = it
                                        rebuildSnapshot()
                                    }
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.w(e)
                    } finally {
                        repeaterBinder = null
                    }
                }
            }
            launch {
                try {
                    app.bindServiceFlow(Intent(app, LocalOnlyHotspotService::class.java)).collectLatest { raw ->
                        val binder = raw as? LocalOnlyHotspotService.Binder
                        localOnlyBinder = binder
                        if (binder == null) {
                            localOnlyIface = null
                            localOnlyConfig = null
                            rebuildSnapshot()
                        } else coroutineScope {
                            launch {
                                binder.iface.collect {
                                    localOnlyIface = it
                                    rebuildSnapshot()
                                }
                            }
                            launch {
                                binder.configuration.collect {
                                    localOnlyConfig = it
                                    rebuildSnapshot()
                                }
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e)
                } finally {
                    localOnlyBinder = null
                }
            }
            launch {
                try {
                    NetlinkNeighbour.monitorSnapshots.collect {
                        neighbourSnapshot = it
                        rebuildSnapshot()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.d(e)
                }
            }
            if (Build.VERSION.SDK_INT >= 31) {
                launch {
                    try {
                        WifiApCommands.softApCallbackFlow().collect { event ->
                            if (event is WifiApManager.Event.OnCapabilityChanged) {
                                val code = WifiCountryCodeCommands.normalizeCountryCode(
                                    UnblockCentral.getCountryCode(event.capability),
                                )
                                if (code != null && code != cachedCountryCode) {
                                    cachedCountryCode = code
                                    rebuildSnapshot()
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.d(e)
                    }
                }
            }
            refreshCountryCode()
            rebuildSnapshot()
        }
    }

    fun stopObserving() {
        val shouldStop = synchronized(this) {
            if (observerCount > 0) observerCount--
            observerCount == 0
        }
        if (!shouldStop) return
        app.pref.unregisterOnSharedPreferenceChangeListener(prefListener)
        observerJob?.cancel()
        observerJob = null
    }

    suspend fun refresh(): Snapshot {
        refreshCountryCode()
        return withContext(scope.coroutineContext) {
            rebuildSnapshot()
            _snapshot.value
        }
    }

    suspend fun refreshCountryCode(): String? {
        val code = try {
            RootManager.use { it.execute(WifiCountryCodeCommands.GetCountryCode()) }.value
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d(e)
            cachedCountryCode
        }
        withContext(scope.coroutineContext) {
            cachedCountryCode = code
            rebuildSnapshot()
        }
        return code
    }

    fun setInterfaceManaged(context: Context, iface: String, enabled: Boolean) {
        if (enabled) {
            context.startForegroundService(
                Intent(context, TetheringService::class.java)
                    .putExtra(TetheringService.EXTRA_ADD_INTERFACES, arrayOf(iface)),
            )
        } else {
            context.startService(
                Intent(context, TetheringService::class.java)
                    .putExtra(TetheringService.EXTRA_REMOVE_INTERFACE, iface),
            )
        }
    }

    fun setInterfaceMonitored(context: Context, iface: String, enabled: Boolean) {
        if (enabled) {
            context.startForegroundService(
                Intent(context, TetheringService::class.java)
                    .putExtra(TetheringService.EXTRA_ADD_INTERFACE_MONITOR, iface),
            )
        } else {
            context.startService(
                Intent(context, TetheringService::class.java)
                    .putExtra(TetheringService.EXTRA_REMOVE_INTERFACE_MONITOR, iface),
            )
        }
    }

    fun setRepeaterEnabled(context: Context, enabled: Boolean) {
        if (enabled) {
            app.startServiceWithLocation<RepeaterService>(context)
        } else {
            repeaterBinder?.shutdown() ?: context.stopService(Intent(context, RepeaterService::class.java))
        }
    }

    fun setLocalOnlyHotspotEnabled(context: Context, enabled: Boolean) {
        if (enabled) {
            app.startServiceWithLocation<LocalOnlyHotspotService>(context)
        } else {
            localOnlyBinder?.stop() ?: context.stopService(Intent(context, LocalOnlyHotspotService::class.java))
        }
    }

    suspend fun setSystemTetheringEnabled(context: Context, type: String, enabled: Boolean): Boolean {
        if (!Settings.System.canWrite(context)) {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, "package:${context.packageName}".toUri()).apply {
                if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return false
        }
        val tetherType = when (type) {
            "wifi" -> TetheringManager.TETHERING_WIFI
            "usb" -> TetheringManagerCompat.TETHERING_USB
            "ncm" -> {
                if (Build.VERSION.SDK_INT < 30) {
                    throw UnsupportedOperationException("USB NCM tethering requires Android 11+")
                }
                TetheringManagerCompat.TETHERING_NCM
            }
            "ethernet" -> TetheringManagerCompat.TETHERING_ETHERNET
            else -> throw IllegalArgumentException("Unsupported tethering type: $type")
        }
        if (enabled) {
            TetheringManagerCompat.startTethering(tetherType, true)
        } else {
            TetheringManagerCompat.stopTethering(tetherType)
        }
        return true
    }

    fun setMasqueradeMode(mode: String) {
        RoutingManager.masqueradeMode = when (mode) {
            "none" -> MasqueradeMode.MASQUERADE_MODE_NONE
            "simple" -> MasqueradeMode.MASQUERADE_MODE_SIMPLE
            "netd" -> MasqueradeMode.MASQUERADE_MODE_NETD
            else -> throw IllegalArgumentException("Invalid masquerade mode: $mode")
        }
        scope.launch { rebuildSnapshot() }
    }

    fun setIpv6Mode(mode: String) {
        RoutingManager.ipv6Mode = when (mode) {
            "system" -> Routing.Ipv6Mode.System
            "block" -> Routing.Ipv6Mode.Block
            "nat" -> Routing.Ipv6Mode.Nat
            else -> throw IllegalArgumentException("Invalid IPv6 mode: $mode")
        }
        scope.launch { rebuildSnapshot() }
    }

    suspend fun setTetherOffloadEnabled(enabled: Boolean) {
        TetherOffloadManager.setEnabled(enabled)
        withContext(scope.coroutineContext) { rebuildSnapshot() }
    }

    suspend fun setAutoStartEnabled(enabled: Boolean) {
        app.pref.edit { putBoolean(BootReceiver.KEY, enabled) }
        BootReceiver.onUserSettingUpdated(enabled)
        withContext(scope.coroutineContext) { rebuildSnapshot() }
    }

    fun setUpstreamInterfaces(primary: String?, fallback: String?) {
        app.pref.edit {
            putString(Upstreams.KEY_PRIMARY, primary?.trim()?.ifEmpty { null })
            putString(Upstreams.KEY_FALLBACK, fallback?.trim()?.ifEmpty { null })
        }
        scope.launch { rebuildSnapshot() }
    }

    suspend fun setAutoMatchCountryCode(enabled: Boolean) {
        WifiCountryCodeCommands.autoMatchEnabled = enabled
        withContext(scope.coroutineContext) { rebuildSnapshot() }
        if (!enabled && WifiCountryCodeCommands.appliedCountryCode != null) {
            setCountryCode(null)
        }
    }

    suspend fun setCountryCode(countryCode: String?): String? {
        val normalized = WifiCountryCodeCommands.normalizeCountryCode(countryCode)
        require(countryCode == null || normalized != null) { "Invalid Wi-Fi country code" }
        val ok = RootManager.use { server ->
            if (normalized == null) {
                server.execute(WifiCountryCodeCommands.ClearCountryCode()).value
            } else {
                server.execute(WifiCountryCodeCommands.SetCountryCode(normalized)).value
            }
        }
        if (!ok) {
            throw IOException(
                if (normalized == null) "Android could not clear the Wi-Fi country code override"
                else "Android could not set the Wi-Fi country code to $normalized",
            )
        }
        WifiCountryCodeCommands.appliedCountryCode = normalized
        refreshCountryCode()
        return normalized
    }

    suspend fun cleanRouting() {
        RoutingManager.clean().join()
        withContext(scope.coroutineContext) { rebuildSnapshot() }
    }

    fun setAlwaysShareAll(context: Context, enabled: Boolean) {
        context.startForegroundService(
            Intent(context, TetheringService::class.java)
                .putExtra(TetheringService.EXTRA_ALWAYS_SHARE_ALL, enabled),
        )
    }

    suspend fun setMacWiredMode(context: Context, enabled: Boolean) {
        context.startForegroundService(
            Intent(context, TetheringService::class.java)
                .putExtra(TetheringService.EXTRA_MAC_WIRED_MODE, enabled),
        )
        if (enabled && Build.VERSION.SDK_INT >= 30 && Settings.System.canWrite(context)) {
            val tetheredTypes = tetherStates.tethered.mapTo(HashSet()) { TetherType.ofInterface(it) }
            if (TetherType.NCM !in tetheredTypes && TetherType.ETHERNET !in tetheredTypes) {
                try {
                    TetheringManagerCompat.startTethering(TetheringManagerCompat.TETHERING_NCM, true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.d(e, "Best-effort USB NCM start skipped")
                }
            }
        }
        withContext(scope.coroutineContext) { rebuildSnapshot() }
    }

    fun openSystemTetheringSettings(context: Context) {
        ManageBar.start(context::startActivity)
    }

    private fun readStaticSnapshot(): Snapshot = Snapshot(
        supported = true,
        primaryPreference = app.pref.getString(Upstreams.KEY_PRIMARY, null).orEmpty(),
        fallbackPreference = app.pref.getString(Upstreams.KEY_FALLBACK, null).orEmpty(),
        masqueradeMode = masqueradeModeName(RoutingManager.masqueradeMode),
        ipv6Mode = ipv6ModeName(RoutingManager.ipv6Mode),
        tetherOffloadEnabled = TetherOffloadManager.enabled,
        autoStart = app.pref.getBoolean(BootReceiver.KEY, false),
        alwaysShareAll = TetheringService.alwaysShareAll,
        macWiredMode = TetheringService.macWiredMode,
        autoMatchCountryCode = WifiCountryCodeCommands.autoMatchEnabled,
        currentCountryCode = cachedCountryCode,
        appliedCountryCode = WifiCountryCodeCommands.appliedCountryCode,
        repeaterSupported = Services.p2p != null,
    )

    private suspend fun rebuildSnapshot() {
        val ifaceLookup = try {
            NetworkInterface.getNetworkInterfaces()?.asSequence()?.associateBy { it.name }.orEmpty()
        } catch (_: SocketException) {
            emptyMap()
        }
        val managed = managedIfaces.asSet()
        val inactive = inactiveIfaces.asSet()
        val monitored = monitoredIfaces.asSet()
        val allIfaces = (tetherStates.tethered + tetherStates.available + monitored).toSortedSet()
        val interfaceList = allIfaces.map { iface ->
            val addresses = ifaceLookup[iface]?.interfaceAddresses?.mapNotNull { addr ->
                val host = addr.address?.hostAddress?.substringBefore('%') ?: return@mapNotNull null
                "$host/${addr.networkPrefixLength}"
            }.orEmpty()
            InterfaceSnapshot(
                name = iface,
                type = tetherTypeName(TetherType.ofInterface(iface)),
                managed = iface in managed && iface !in inactive,
                monitored = iface in monitored,
                inactive = iface in inactive || iface !in tetherStates.tethered,
                addresses = addresses,
            )
        }
        val tetheredTypes = tetherStates.tethered.mapTo(HashSet()) { TetherType.ofInterface(it) }
        val clients = buildClientSnapshots(tetherStates.tethered)
        val activeClientCount = clients.count { it.active }
        _snapshot.value = Snapshot(
            supported = true,
            vpnUpstream = primaryUpstream?.properties?.allInterfaceNames?.firstOrNull(),
            fallbackUpstream = fallbackUpstream?.properties?.allInterfaceNames?.firstOrNull(),
            primaryPreference = app.pref.getString(Upstreams.KEY_PRIMARY, null).orEmpty(),
            fallbackPreference = app.pref.getString(Upstreams.KEY_FALLBACK, null).orEmpty(),
            masqueradeMode = masqueradeModeName(RoutingManager.masqueradeMode),
            ipv6Mode = ipv6ModeName(RoutingManager.ipv6Mode),
            tetherOffloadEnabled = TetherOffloadManager.enabled,
            autoStart = app.pref.getBoolean(BootReceiver.KEY, false),
            alwaysShareAll = TetheringService.alwaysShareAll,
            macWiredMode = TetheringService.macWiredMode,
            autoMatchCountryCode = WifiCountryCodeCommands.autoMatchEnabled,
            currentCountryCode = cachedCountryCode,
            appliedCountryCode = WifiCountryCodeCommands.appliedCountryCode,
            repeaterSupported = Services.p2p != null,
            repeaterActive = repeaterActive,
            repeaterInterface = if (repeaterActive) repeaterGroup?.`interface` else null,
            repeaterNetworkName = if (repeaterActive) repeaterGroup?.networkName else null,
            localOnlyActive = localOnlyIface != null,
            localOnlyInterface = localOnlyIface?.takeIf { it.isNotEmpty() },
            localOnlySsid = localOnlyConfig?.ssid?.decode() ?: localOnlyConfig?.ssid?.toString(),
            systemHotspotActive = TetherType.WIFI in tetheredTypes,
            usbTetheringActive = TetherType.USB in tetheredTypes || TetherType.NCM in tetheredTypes,
            ncmTetheringActive = TetherType.NCM in tetheredTypes,
            ncmSupported = Build.VERSION.SDK_INT >= 30,
            ethernetTetheringActive = TetherType.ETHERNET in tetheredTypes,
            ethernetSupported = Build.VERSION.SDK_INT >= 30,
            interfaces = interfaceList,
            clients = clients,
            activeClientCount = activeClientCount,
        )
    }

    private suspend fun buildClientSnapshots(tethered: Set<String>): List<ClientSnapshot> {
        val grouped = LinkedHashMap<Pair<String, MacAddress>, MutableList<NetlinkNeighbour>>()
        for (neighbour in neighbourSnapshot.neighbours) {
            val lladdr = neighbour.lladdr ?: continue
            val iface = neighbourSnapshot.bridgeMasterByMember[neighbour.dev] ?: neighbour.dev
            if (iface !in tethered && iface != localOnlyIface && iface != repeaterGroup?.`interface`) continue
            grouped.getOrPut(iface to lladdr) { ArrayList() }.add(neighbour)
        }
        return grouped.entries.map { (key, entries) ->
            val (iface, mac) = key
            val record = withContext(Dispatchers.IO) {
                try {
                    AppDatabase.instance.clientRecordDao.lookupOrDefault(mac)
                } catch (e: Exception) {
                    Timber.d(e)
                    null
                }
            }
            val active = entries.any { it.state == NeighbourState.NEIGHBOUR_STATE_VALID }
            val ips = entries.mapNotNull { it.ip.hostAddress?.substringBefore('%') }.distinct().sorted()
            ClientSnapshot(
                mac = mac.toString(),
                iface = iface,
                type = tetherTypeName(TetherType.ofInterface(iface)),
                nickname = record?.nickname?.text.orEmpty(),
                blocked = record?.blocked == true,
                active = active,
                addresses = ips,
            )
        }.sortedWith(compareBy<ClientSnapshot>({ !it.active }, { it.iface }, { it.mac }))
    }

    private fun masqueradeModeName(mode: MasqueradeMode) = when (mode) {
        MasqueradeMode.MASQUERADE_MODE_NONE -> "none"
        MasqueradeMode.MASQUERADE_MODE_SIMPLE -> "simple"
        MasqueradeMode.MASQUERADE_MODE_NETD -> "netd"
        is MasqueradeMode.Unrecognized -> "simple"
    }

    private fun ipv6ModeName(mode: Routing.Ipv6Mode) = when (mode) {
        Routing.Ipv6Mode.System -> "system"
        Routing.Ipv6Mode.Block -> "block"
        Routing.Ipv6Mode.Nat -> "nat"
    }

    private fun tetherTypeName(type: TetherType) = when (type) {
        TetherType.WIFI -> "wifi"
        TetherType.WIFI_P2P -> "wifi_p2p"
        TetherType.USB, TetherType.NCM -> "usb"
        TetherType.BLUETOOTH -> "bluetooth"
        TetherType.ETHERNET -> "ethernet"
        TetherType.WIGIG -> "wifi"
        TetherType.VIRTUAL, TetherType.NONE -> "other"
    }
}
