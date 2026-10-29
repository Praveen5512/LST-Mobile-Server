package com.localserver.core

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.HttpURLConnection
import java.net.URL

enum class ServerConnectionStatus {
    ONLINE,
    CHECKING,
    UNREACHABLE
}

data class DiscoveredServer(
    val serviceName: String,
    val host: String,
    val port: Int,
    val url: String = "http://$host:$port",
    var status: ServerConnectionStatus = ServerConnectionStatus.CHECKING
)

/**
 * Handles mDNS network discovery and service advertisement for the Android Local Media Server.
 *
 * Utilizes a dual-engine architecture:
 * 1. Android Native NsdManager: registers standard mDNS service on local Wi-Fi.
 * 2. Embedded MdnsResponder: raw UDP 5353 responder for Hotspot (SoftAP) interfaces and immediate announcements.
 */
class NsdDiscoveryManager(private val context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null

    private val mdnsResponder = MdnsResponder()

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private var isRegistered = false
    private var isRegistering = false
    private var currentRegisteredPort: Int? = null
    private var currentRegisteredName: String? = null

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private val _discoveredServers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val discoveredServers: StateFlow<List<DiscoveredServer>> = _discoveredServers.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private val resolveQueue = java.util.concurrent.ConcurrentLinkedQueue<NsdServiceInfo>()
    private var isResolving = false

    companion object {
        private const val TAG = "NsdDiscoveryManager"
        const val SERVICE_TYPE = "_http._tcp"
        const val SERVICE_TYPE_WITH_DOT = "_http._tcp."
        const val SERVICE_NAME_PREFIX = "LocalMediaServer"
    }

    /**
     * Registers the server as an mDNS / Zeroconf service so TV clients can auto-discover it.
     */
    @Synchronized
    fun registerServer(port: Int, customName: String? = null, ipAddresses: List<String> = emptyList()) {
        val cleanModel = Build.MODEL.replace("[^a-zA-Z0-9]".toRegex(), "").ifEmpty { "Mobile" }.take(12)
        val baseName = customName ?: "$SERVICE_NAME_PREFIX-$cleanModel"

        if (isRegistered && currentRegisteredPort == port && currentRegisteredName == baseName) {
            Log.d(TAG, "mDNS server already registered with name $baseName on port $port, updating IPs")
            mdnsResponder.update(port, ipAddresses)
            return
        }

        acquireMulticastLock()

        currentRegisteredPort = port
        currentRegisteredName = baseName

        // Start pure Kotlin UDP mDNS responder for immediate broadcast & Hotspot coverage
        mdnsResponder.start(port, baseName, ipAddresses)

        // Register with Android's system NsdManager
        if (nsdManager == null) {
            Log.w(TAG, "NsdManager not available on this device; relying on MdnsResponder")
            return
        }

        if (isRegistered || isRegistering) {
            unregisterNativeNsdOnly()
        }

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = baseName
            serviceType = SERVICE_TYPE
            this.port = port
            try {
                setAttribute("path", "/api/shares")
                setAttribute("version", "1.0")
                setAttribute("name", baseName)
            } catch (e: Exception) {
                Log.w(TAG, "Unable to set NsdServiceInfo attributes: ${e.message}")
            }
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(registeredService: NsdServiceInfo) {
                isRegistered = true
                isRegistering = false
                Log.i(TAG, "Service registered successfully via mDNS: ${registeredService.serviceName} on port $port")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                isRegistered = false
                isRegistering = false
                Log.e(TAG, "mDNS Service registration failed with error code $errorCode (MdnsResponder continues active)")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                isRegistered = false
                isRegistering = false
                Log.i(TAG, "mDNS Service unregistered successfully: ${serviceInfo.serviceName}")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                isRegistered = false
                isRegistering = false
                Log.w(TAG, "mDNS Service unregistration failed: $errorCode")
            }
        }
        registrationListener = listener
        isRegistering = true

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            isRegistering = false
            Log.e(TAG, "Exception during NSD registerService", e)
        }
    }

    /**
     * Refreshes active IP addresses for mDNS broadcasting when network interfaces change.
     */
    @Synchronized
    fun refreshServer(port: Int, ipAddresses: List<String>) {
        mdnsResponder.update(port, ipAddresses)
        if (!isRegistered && !isRegistering) {
            registerServer(port, currentRegisteredName, ipAddresses)
        }
    }

    /**
     * Unregisters the mDNS service when the server is stopped.
     */
    @Synchronized
    fun unregisterServer() {
        mdnsResponder.stop()
        unregisterNativeNsdOnly()
        currentRegisteredPort = null
        currentRegisteredName = null
        releaseMulticastLock()
    }

    private fun unregisterNativeNsdOnly() {
        val listener = registrationListener
        registrationListener = null
        if (nsdManager != null && listener != null && (isRegistered || isRegistering)) {
            try {
                nsdManager.unregisterService(listener)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering NSD service: ${e.message}")
            } finally {
                isRegistered = false
                isRegistering = false
            }
        }
    }

    /**
     * Starts discovering other media servers on the local network (mDNS / NSD).
     */
    @Synchronized
    fun startDiscovery() {
        if (nsdManager == null || isDiscovering.value) return

        acquireMulticastLock()
        _discoveredServers.value = emptyList()
        synchronized(resolveQueue) {
            resolveQueue.clear()
            isResolving = false
        }

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                _isDiscovering.value = true
                Log.i(TAG, "NSD Discovery started for $regType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.i(TAG, "NSD Service found: ${service.serviceName} (${service.serviceType})")
                val name = service.serviceName
                val type = service.serviceType
                if (name.contains(SERVICE_NAME_PREFIX, ignoreCase = true) ||
                    type.contains("http", ignoreCase = true)) {
                    queueServiceForResolve(service)
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.i(TAG, "NSD Service lost: ${service.serviceName}")
                _discoveredServers.value = _discoveredServers.value.filter { it.serviceName != service.serviceName }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                _isDiscovering.value = false
                Log.i(TAG, "NSD Discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                _isDiscovering.value = false
                Log.e(TAG, "NSD Start Discovery failed: $errorCode")
                stopDiscovery()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                _isDiscovering.value = false
                Log.w(TAG, "NSD Stop Discovery failed: $errorCode")
            }
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE_WITH_DOT, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting NSD discovery", e)
            _isDiscovering.value = false
        }
    }

    private fun queueServiceForResolve(service: NsdServiceInfo) {
        synchronized(resolveQueue) {
            resolveQueue.add(service)
            processNextResolve()
        }
    }

    private fun processNextResolve() {
        synchronized(resolveQueue) {
            if (isResolving) return
            val nextService = resolveQueue.poll() ?: return
            isResolving = true

            val resolver = object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                    finishResolve()
                }

                @Suppress("DEPRECATION")
                override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                    val host = resolvedInfo.host?.hostAddress
                    val port = resolvedInfo.port
                    val name = resolvedInfo.serviceName

                    if (host != null && port > 0) {
                        val server = DiscoveredServer(name, host, port, status = ServerConnectionStatus.CHECKING)
                        // Deduplicate by host:port and serviceName
                        val current = _discoveredServers.value.filter {
                            !(it.host == host && it.port == port) && it.serviceName != name
                        }
                        _discoveredServers.value = current + server
                        Log.i(TAG, "Resolved server: $name at $host:$port (Deduplicated)")
                        checkServerReachability(server)
                    }
                    finishResolve()
                }

                private fun finishResolve() {
                    synchronized(resolveQueue) {
                        isResolving = false
                        processNextResolve()
                    }
                }
            }

            try {
                @Suppress("DEPRECATION")
                nsdManager?.resolveService(nextService, resolver)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to call resolveService for ${nextService.serviceName}: ${e.message}")
                isResolving = false
                processNextResolve()
            }
        }
    }

    @Synchronized
    fun stopDiscovery() {
        val listener = discoveryListener
        discoveryListener = null
        if (nsdManager != null && listener != null && isDiscovering.value) {
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping discovery: ${e.message}")
            } finally {
                _isDiscovering.value = false
            }
        } else {
            _isDiscovering.value = false
        }
        synchronized(resolveQueue) {
            resolveQueue.clear()
            isResolving = false
        }
        releaseMulticastLock()
    }

    private fun acquireMulticastLock() {
        try {
            if (multicastLock == null) {
                multicastLock = wifiManager?.createMulticastLock("LocalMediaServerMulticastLock")?.apply {
                    setReferenceCounted(true)
                }
            }
            multicastLock?.acquire()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire multicast lock: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release multicast lock: ${e.message}")
        }
    }

    fun checkServerReachability(server: DiscoveredServer) {
        scope.launch {
            val isReachable = try {
                val url = URL("${server.url}/api/status")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 2500
                    readTimeout = 2500
                    requestMethod = "GET"
                }
                conn.connect()
                val code = conn.responseCode
                conn.disconnect()
                code in 200..299
            } catch (_: Exception) {
                false
            }

            val newStatus = if (isReachable) ServerConnectionStatus.ONLINE else ServerConnectionStatus.UNREACHABLE
            val updated = _discoveredServers.value.map {
                if (it.url == server.url) it.copy(status = newStatus) else it
            }
            _discoveredServers.value = updated
        }
    }

    fun checkAllServersReachability() {
        for (server in _discoveredServers.value) {
            checkServerReachability(server)
        }
    }

    fun refreshDiscovery() {
        stopDiscovery()
        _discoveredServers.value = emptyList()
        startDiscovery()
    }

    fun addManualServer(input: String): DiscoveredServer {
        val cleanInput = input.trim()
        val url = if (cleanInput.startsWith("http://", ignoreCase = true) || cleanInput.startsWith("https://", ignoreCase = true)) {
            cleanInput
        } else {
            "http://$cleanInput"
        }

        val parsedUrl = try {
            URL(url)
        } catch (_: Exception) {
            URL("http://$cleanInput:8080")
        }

        val host = parsedUrl.host
        val port = if (parsedUrl.port > 0) parsedUrl.port else 8080
        val serviceName = "Manual ($host:$port)"
        val server = DiscoveredServer(serviceName, host, port, "http://$host:$port", ServerConnectionStatus.CHECKING)

        val current = _discoveredServers.value.filter { !(it.host == host && it.port == port) }
        _discoveredServers.value = current + server
        checkServerReachability(server)
        return server
    }
}

