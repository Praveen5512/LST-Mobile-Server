package com.localserver.core

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DiscoveredServer(
    val serviceName: String,
    val host: String,
    val port: Int,
    val url: String = "http://$host:$port"
)

class NsdDiscoveryManager(private val context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var isRegistered = false

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private val _discoveredServers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val discoveredServers: StateFlow<List<DiscoveredServer>> = _discoveredServers.asStateFlow()

    private val resolveQueue = java.util.concurrent.ConcurrentLinkedQueue<NsdServiceInfo>()
    private var isResolving = false

    companion object {
        private const val TAG = "NsdDiscoveryManager"
        const val SERVICE_TYPE = "_http._tcp."
        const val SERVICE_NAME_PREFIX = "LocalMediaServer"
    }

    /**
     * Registers the server as an mDNS / NSD service so other devices on the LAN (like Android TV) can auto-discover it.
     */
    fun registerServer(port: Int, customName: String? = null) {
        if (nsdManager == null) {
            Log.w(TAG, "NsdManager not available on this device")
            return
        }
        if (isRegistered) {
            unregisterServer()
        }

        val baseName = customName ?: "$SERVICE_NAME_PREFIX-${Build.MODEL.filter { it.isLetterOrDigit() }.take(10)}"
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = baseName
            serviceType = SERVICE_TYPE
            this.port = port
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(registeredService: NsdServiceInfo) {
                isRegistered = true
                Log.i(TAG, "Service registered successfully via mDNS: ${registeredService.serviceName} on port $port")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                isRegistered = false
                Log.e(TAG, "mDNS Service registration failed with error code $errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                isRegistered = false
                Log.i(TAG, "mDNS Service unregistered successfully: ${serviceInfo.serviceName}")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                isRegistered = false
                Log.w(TAG, "mDNS Service unregistration failed: $errorCode")
            }
        }

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e(TAG, "Exception during NSD registerService", e)
        }
    }

    /**
     * Unregisters the mDNS service when the server is stopped.
     */
    fun unregisterServer() {
        val listener = registrationListener
        registrationListener = null
        if (nsdManager != null && listener != null && isRegistered) {
            try {
                nsdManager.unregisterService(listener)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering NSD service: ${e.message}")
            } finally {
                isRegistered = false
            }
        }
    }

    /**
     * Starts discovering other media servers on the local network (mDNS / NSD).
     */
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
                if (service.serviceName.contains(SERVICE_NAME_PREFIX, ignoreCase = true) ||
                    service.serviceType.contains("http", ignoreCase = true)) {
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
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
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

                override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                    val host = resolvedInfo.host?.hostAddress
                    val port = resolvedInfo.port
                    val name = resolvedInfo.serviceName

                    if (host != null && port > 0) {
                        val server = DiscoveredServer(name, host, port)
                        val current = _discoveredServers.value.filter { it.serviceName != name }
                        _discoveredServers.value = current + server
                        Log.i(TAG, "Resolved server: $name at $host:$port")
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
                nsdManager?.resolveService(nextService, resolver)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to call resolveService for ${nextService.serviceName}: ${e.message}")
                isResolving = false
                processNextResolve()
            }
        }
    }

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
}
