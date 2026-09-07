/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.connect

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Handles mDNS/DNS-SD discovery using native Android NsdManager.
 * Discovers and advertises Vivi Connect devices on the local Wi-Fi network.
 */
class ConnectDiscoveryService(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "ConnectDiscovery"
        const val SERVICE_TYPE = "_viviconnect._tcp"
        private const val DEVICE_EXPIRATION_MS = 30_000L
    }

    private val nsdManager: NsdManager? by lazy {
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    }

    private val wifiManager: WifiManager? by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    }

    private var multicastLock: WifiManager.MulticastLock? = null

    private val _discoveredDevices = MutableStateFlow<List<ConnectDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<ConnectDevice>> = _discoveredDevices.asStateFlow()

    private val deviceMap = ConcurrentHashMap<String, ConnectDevice>()

    private var localDeviceId: String = ""
    private var localDeviceName: String = ""
    private var localAccountHash: String = ""
    private var localPort: Int = 0
    private var requireSameAccount: Boolean = true

    private var isAdvertising = false
    private var isDiscovering = false
    private var cleanupJob: Job? = null

    // Queue to serialize NsdManager resolve requests (resolving concurrently fails on many Android builds)
    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    @Volatile
    private var isResolving = false

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun updateConfig(
        deviceId: String,
        deviceName: String,
        accountHash: String,
        sameAccountOnly: Boolean
    ) {
        val needsRestart = isAdvertising && (
            localDeviceId != deviceId ||
            localDeviceName != deviceName ||
            localAccountHash != accountHash
        )

        localDeviceId = deviceId
        localDeviceName = deviceName
        localAccountHash = accountHash
        requireSameAccount = sameAccountOnly

        if (needsRestart && localPort > 0) {
            stopAdvertising()
            startAdvertising(localPort)
        }
        filterDiscoveredDevices()
    }

    fun startAdvertising(port: Int) {
        if (port <= 0 || isAdvertising || nsdManager == null) return
        localPort = port

        acquireMulticastLock()

        val serviceInfo = NsdServiceInfo().apply {
            serviceType = SERVICE_TYPE
            serviceName = "Vivi-" + localDeviceId.take(8)
            this.port = port
            setAttribute("id", localDeviceId)
            setAttribute("name", localDeviceName)
            setAttribute("account", localAccountHash)
            setAttribute("model", Build.MODEL ?: "")
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(registeredService: NsdServiceInfo) {
                isAdvertising = true
                Timber.tag(TAG).d("Service registered successfully: ${registeredService.serviceName} on port $port")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                isAdvertising = false
                Timber.tag(TAG).e("Service registration failed: errorCode=$errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                isAdvertising = false
                Timber.tag(TAG).d("Service unregistered")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                isAdvertising = false
                Timber.tag(TAG).e("Service unregistration failed: errorCode=$errorCode")
            }
        }

        try {
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error registering NSD service")
        }
    }

    fun stopAdvertising() {
        if (!isAdvertising) return
        try {
            registrationListener?.let { nsdManager?.unregisterService(it) }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error unregistering NSD service")
        } finally {
            isAdvertising = false
            registrationListener = null
        }
    }

    fun startDiscovery() {
        if (isDiscovering || nsdManager == null) return
        acquireMulticastLock()

        deviceMap.clear()
        _discoveredDevices.value = emptyList()

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                isDiscovering = false
                Timber.tag(TAG).e("Start discovery failed: errorCode=$errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                isDiscovering = false
                Timber.tag(TAG).e("Stop discovery failed: errorCode=$errorCode")
            }

            override fun onDiscoveryStarted(serviceType: String) {
                isDiscovering = true
                Timber.tag(TAG).d("Discovery started for $serviceType")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                isDiscovering = false
                Timber.tag(TAG).d("Discovery stopped for $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val foundType = serviceInfo.serviceType.trimEnd('.')
                val targetType = SERVICE_TYPE.trimEnd('.')
                if (foundType.equals(targetType, ignoreCase = true) || foundType.contains("viviconnect")) {
                    Timber.tag(TAG).d("Service found: ${serviceInfo.serviceName}, queuing resolve")
                    enqueueResolve(serviceInfo)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Timber.tag(TAG).d("Service lost: ${serviceInfo.serviceName}")
                val entryToRemove = deviceMap.entries.firstOrNull { it.value.name == serviceInfo.serviceName }
                if (entryToRemove != null) {
                    deviceMap.remove(entryToRemove.key)
                    filterDiscoveredDevices()
                }
            }
        }

        try {
            nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            startCleanupJob()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error starting NSD discovery")
        }
    }

    fun stopDiscovery() {
        if (!isDiscovering) return
        try {
            discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error stopping NSD discovery")
        } finally {
            isDiscovering = false
            discoveryListener = null
            cleanupJob?.cancel()
            cleanupJob = null
            releaseMulticastLock()
        }
    }

    private fun enqueueResolve(serviceInfo: NsdServiceInfo) {
        resolveQueue.offer(serviceInfo)
        processNextResolve()
    }

    private fun processNextResolve() {
        if (isResolving) return
        val next = resolveQueue.poll() ?: return
        isResolving = true

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Timber.tag(TAG).w("Resolve failed for ${serviceInfo.serviceName}: errorCode=$errorCode")
                isResolving = false
                processNextResolve()
            }

            override fun onServiceResolved(resolvedService: NsdServiceInfo) {
                try {
                    handleResolvedService(resolvedService)
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error handling resolved service")
                } finally {
                    isResolving = false
                    processNextResolve()
                }
            }
        }

        try {
            nsdManager?.resolveService(next, resolveListener)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error executing resolveService")
            isResolving = false
            processNextResolve()
        }
    }

    private fun handleResolvedService(info: NsdServiceInfo) {
        val host: InetAddress = info.host ?: return
        val port = info.port
        if (port <= 0) return

        val attributes = info.attributes
        val id = attributes["id"]?.let { String(it, StandardCharsets.UTF_8) } ?: info.serviceName
        val name = attributes["name"]?.let { String(it, StandardCharsets.UTF_8) } ?: info.serviceName
        val account = attributes["account"]?.let { String(it, StandardCharsets.UTF_8) } ?: ""
        val model = attributes["model"]?.let { String(it, StandardCharsets.UTF_8) } ?: ""

        // Ignore self
        val isSelf = (id == localDeviceId) || (port == localPort && isAdvertising)
        if (isSelf) return

        val device = ConnectDevice(
            id = id,
            name = name,
            host = host.hostAddress ?: "",
            port = port,
            accountHash = account,
            model = model,
            isSelf = false,
            lastSeen = System.currentTimeMillis()
        )

        deviceMap[id] = device
        filterDiscoveredDevices()
    }

    private fun filterDiscoveredDevices() {
        val validDevices = deviceMap.values.filter { device ->
            if (device.isSelf) return@filter false
            if (device.host.isEmpty() || device.port <= 0) return@filter false
            
            if (requireSameAccount && localAccountHash.isNotEmpty()) {
                // Must match the same account hash
                device.accountHash == localAccountHash
            } else {
                true
            }
        }.sortedBy { it.name.lowercase() }

        _discoveredDevices.value = validDevices
    }

    private fun startCleanupJob() {
        cleanupJob?.cancel()
        cleanupJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(10_000L)
                val now = System.currentTimeMillis()
                val removedAny = deviceMap.entries.removeIf { now - it.value.lastSeen > DEVICE_EXPIRATION_MS }
                if (removedAny) {
                    filterDiscoveredDevices()
                }
            }
        }
    }

    private fun acquireMulticastLock() {
        try {
            if (multicastLock == null) {
                multicastLock = wifiManager?.createMulticastLock("ViViConnectMulticastLock")?.apply {
                    setReferenceCounted(true)
                }
            }
            if (multicastLock?.isHeld != true) {
                multicastLock?.acquire()
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not acquire MulticastLock")
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not release MulticastLock")
        }
    }

    fun release() {
        stopAdvertising()
        stopDiscovery()
        releaseMulticastLock()
        deviceMap.clear()
        _discoveredDevices.value = emptyList()
    }
}
