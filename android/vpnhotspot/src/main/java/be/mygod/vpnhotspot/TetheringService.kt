package be.mygod.vpnhotspot

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.collection.MutableScatterMap
import androidx.collection.MutableScatterSet
import androidx.collection.ScatterSet
import androidx.collection.emptyScatterSet
import androidx.collection.toMutableScatterMap
import androidx.core.content.edit
import be.mygod.vpnhotspot.App.Companion.app
import be.mygod.vpnhotspot.net.Routing
import be.mygod.vpnhotspot.net.TetherStates
import be.mygod.vpnhotspot.net.TetherType
import be.mygod.vpnhotspot.net.TetheringManagerCompat
import be.mygod.vpnhotspot.root.WifiApCommands
import be.mygod.vpnhotspot.util.TileServiceDismissHandle
import be.mygod.vpnhotspot.widget.SmartSnackbar
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import timber.log.Timber

class TetheringService : NetlinkNeighbourMonitoringService() {
    companion object {
        const val EXTRA_ADD_INTERFACES = "interface.add"
        const val EXTRA_ADD_INTERFACE_MONITOR = "interface.add.monitor"
        const val EXTRA_REMOVE_INTERFACE = "interface.remove"
        const val EXTRA_REMOVE_INTERFACE_MONITOR = "interface.remove.monitor"
        const val EXTRA_ALWAYS_SHARE_ALL = "interface.alwaysShareAll"
        const val EXTRA_MAC_WIRED_MODE = "interface.macWiredMode"
        private const val KEY_ALWAYS_SHARE_ALL = "service.hotspot.alwaysShareAll"
        private const val KEY_MAC_WIRED_MODE = "service.hotspot.macWiredMode"

        val alwaysShareAll get() = app.pref.getBoolean(KEY_ALWAYS_SHARE_ALL, false)
        val macWiredMode get() = app.pref.getBoolean(KEY_MAC_WIRED_MODE, false)

        fun isWiredTetherInterface(iface: String): Boolean = when (TetherType.ofInterface(iface)) {
            TetherType.NCM, TetherType.ETHERNET, TetherType.USB -> true
            else -> false
        }

        var dismissHandle: TileServiceDismissHandle? = null
        private fun dismissIfApplicable() = dismissHandle?.run {
            get()?.dismiss()
            dismissHandle = null
        }
    }

    class Binder(owner: TetheringService) : android.os.Binder() {
        val managedIfaces = owner.managedIfaces.asStateFlow()
        val inactiveIfaces = owner.inactiveIfaceSet.asStateFlow()
        val monitoredIfaces = owner.monitoredIfaceSet.asStateFlow()

        fun isActive(iface: String) = managedIfaces.value.contains(iface)
    }

    private class Downstream(caller: Any, downstream: String, var manual: Boolean = false, var monitor: Boolean = false) :
            RoutingManager(caller, downstream) {
        override fun Routing.configure() {
            ipv6Mode = RoutingManager.ipv6Mode
        }
    }

    @Parcelize
    data class Starter(val monitored: ArrayList<String>) : BootReceiver.Startable {
        override fun start(context: Context) {
            context.startForegroundService(Intent(context, TetheringService::class.java))
        }
    }

    override val coroutineContext = Job()
    private val managedIfaces = MutableStateFlow<ScatterSet<String>>(emptyScatterSet())
    private val inactiveIfaceSet = MutableStateFlow<ScatterSet<String>>(emptyScatterSet())
    private val monitoredIfaceSet = MutableStateFlow<ScatterSet<String>>(emptyScatterSet())
    override val interfaces = MutableStateFlow<Interfaces?>(null)
    private val binder = Binder(this)
    private var downstreams = MutableScatterMap<String, Downstream>()
    /**
     * Protects downstream state across suspending start/stop transitions.
     */
    private val downstreamsMutex = Mutex()
    private var tetherStatesJob: Job? = null
    private var tetheredIfaces: Set<String>? = null
    private var restored = false
    private var handledStartId = 0

    private fun launchTetherStatesJob() = launch {
        if (Build.VERSION.SDK_INT >= 30) launch {
            TetheringManagerCompat.eventFlow
                .filterIsInstance<TetheringManagerCompat.Event.OffloadStatusChanged>()
                .collect { (status) ->
                    when (status) {
                        TetheringManagerCompat.TETHER_HARDWARE_OFFLOAD_STOPPED,
                        TetheringManagerCompat.TETHER_HARDWARE_OFFLOAD_FAILED -> { }
                        TetheringManagerCompat.TETHER_HARDWARE_OFFLOAD_STARTED -> {
                            Timber.w("TETHER_HARDWARE_OFFLOAD_STARTED")
                            SmartSnackbar.make(R.string.tethering_manage_offload_enabled).show()
                        }
                        else -> Timber.w(IllegalStateException("Unknown onOffloadStatusChanged $status"))
                    }
                }
        }
        TetherStates.flow.map { it.tethered }.distinctUntilChanged().collect { tetheredInterfaces ->
            downstreamsMutex.withLock {
                tetheredIfaces = tetheredInterfaces
                reconcileDownstreamsLocked()
                onDownstreamsChangedLocked()
            }
        }
    }

    private suspend fun reconcileDownstreamsLocked() {
        val tethered = tetheredIfaces ?: return
        val all = alwaysShareAll
        val macWired = macWiredMode
        if (all || macWired) for (iface in tethered) {
            if (all || isWiredTetherInterface(iface)) {
                downstreams.compute(iface) { _, current -> current ?: Downstream(this, iface) }
            }
        }
        downstreams.toMutableScatterMap().forEach { iface, downstream ->
            val autoManaged = all || (macWired && isWiredTetherInterface(iface))
            if (iface in tethered && (autoManaged || downstream.manual || downstream.monitor)) {
                if (!downstream.started && !downstream.start()) dismissIfApplicable()
            } else {
                if (!downstream.monitor) check(downstreams.remove(iface, downstream))
                downstream.stop()
            }
        }
    }

    private suspend fun onDownstreamsChangedLocked() {
        val monitoredIfaces = MutableScatterSet<String>()
        val inactiveIfaces = MutableScatterSet<String>()
        val managedIfaces = MutableScatterSet<String>()
        val active = ArrayList<String>(downstreams.size)
        val notificationInactive = ArrayList<String>(downstreams.size)
        downstreams.forEachValue { downstream ->
            managedIfaces.add(downstream.downstream)
            if (downstream.started) active.add(downstream.downstream) else notificationInactive.add(downstream.downstream)
            if (downstream.monitor) {
                monitoredIfaces.add(downstream.downstream)
                if (!downstream.started) inactiveIfaces.add(downstream.downstream)
            }
        }
        val keepListening = alwaysShareAll || macWiredMode
        monitoredIfaces.also { ifaces ->
            if (ifaces.isEmpty() && !keepListening) {
                BootReceiver.delete<TetheringService>()
            } else BootReceiver.add<TetheringService>(Starter(ArrayList<String>(ifaces.size).apply {
                ifaces.forEach { iface -> add(iface) }
            }))
        }
        // Publish to the bound binder before the teardown below: when reached from the TetherStates
        // collector, unregisterReceiver() cancels tetherStatesJob — this coroutine's own parent — which
        // would otherwise skip this withContext and leave stale managedIfaces visible to bound UI.
        withContext(Dispatchers.Main) {
            monitoredIfaceSet.value = monitoredIfaces
            inactiveIfaceSet.value = inactiveIfaces
            this@TetheringService.managedIfaces.value = managedIfaces
        }
        if (managedIfaces.isEmpty() && !keepListening) {
            if (stopSelfResult(handledStartId)) {
                interfaces.value = null
                ServiceNotification.stopForeground(this)
                unregisterReceiver()
            }
        } else {
            if (tetherStatesJob == null) tetherStatesJob = launchTetherStatesJob()
            interfaces.value = Interfaces(active, notificationInactive)
        }
    }

    override fun onBind(intent: Intent?) = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        launch { BootReceiver.startIfEnabled() }
        ServiceNotification.startForeground(this)   // call this first just in case we are shutting down immediately
        launch(start = CoroutineStart.UNDISPATCHED) {
            downstreamsMutex.withLock {
                withContext(Dispatchers.Default) {
                    applyCommandLocked(intent, startId)
                }
            }
        }
        return START_STICKY
    }

    private suspend fun applyCommandLocked(intent: Intent?, startId: Int) {
        handledStartId = startId
        if (!restored) {
            BootReceiver.tetheringConfig()?.monitored?.forEach { iface ->
                downstreams[iface] = Downstream(this, iface, monitor = true)
            }
            restored = true
        }
        if (intent != null) {
            if (intent.hasExtra(EXTRA_ALWAYS_SHARE_ALL)) app.pref.edit {
                putBoolean(KEY_ALWAYS_SHARE_ALL, intent.getBooleanExtra(EXTRA_ALWAYS_SHARE_ALL, false))
            }
            if (intent.hasExtra(EXTRA_MAC_WIRED_MODE)) app.pref.edit {
                putBoolean(KEY_MAC_WIRED_MODE, intent.getBooleanExtra(EXTRA_MAC_WIRED_MODE, false))
            }
            for (iface in intent.getStringArrayExtra(EXTRA_ADD_INTERFACES) ?: emptyArray()) {
                val downstream = downstreams.compute(iface) { _, current ->
                    (current ?: Downstream(this, iface)).apply { manual = true }
                }
                if (!downstream.started && !downstream.start()) dismissIfApplicable()
            }
            intent.getStringExtra(EXTRA_ADD_INTERFACE_MONITOR)?.also { iface ->
                downstreams.compute(iface) { _, current ->
                    (current ?: Downstream(this, iface)).apply { monitor = true }
                }
            }
            intent.getStringExtra(EXTRA_REMOVE_INTERFACE)?.also { downstreams.remove(it)?.stop() }
            intent.getStringExtra(EXTRA_REMOVE_INTERFACE_MONITOR)?.also { iface ->
                downstreams[iface]?.let { downstream ->
                    downstream.monitor = false
                    if (!downstream.manual && !alwaysShareAll && !(macWiredMode && isWiredTetherInterface(iface))) {
                        downstreams.remove(iface)?.stop()
                    }
                }
            }
        }
        reconcileDownstreamsLocked()
        onDownstreamsChangedLocked()
    }

    override fun onDestroy() {
        interfaces.value = null
        ServiceNotification.stopForeground(this)
        launch {
            downstreamsMutex.withLock {
                unregisterReceiver()
                val oldDownstreams = downstreams
                downstreams = MutableScatterMap()
                oldDownstreams.forEachValue { it.stop() }    // force clean to prevent leakage
                cancel()
            }
        }
        super.onDestroy()
    }

    private fun unregisterReceiver() {
        tetherStatesJob?.cancel()
        tetherStatesJob = null
        tetheredIfaces = null
    }

    override fun countsFlow(active: List<String>) = if (Build.VERSION.SDK_INT >= 31) {
        softApCountsFlow(active, WifiApCommands.softApCallbackFlow())
    } else super.countsFlow(active)
}
