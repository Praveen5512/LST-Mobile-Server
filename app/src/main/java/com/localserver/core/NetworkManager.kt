package com.localserver.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NetworkAddress(
    val interfaceName: String,
    val displayName: String,
    val ipAddress: String,
    val type: NetworkType
) {
    fun toUrl(port: Int): String = "http://$ipAddress:$port"
}

enum class NetworkType {
    WIFI,
    HOTSPOT,
    ETHERNET,
    USB_TETHERING,
    OTHER
}

class NetworkManager(private val context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _availableAddresses = MutableStateFlow<List<NetworkAddress>>(emptyList())
    val availableAddresses: StateFlow<List<NetworkAddress>> = _availableAddresses.asStateFlow()

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        refresh()
    }

    fun startMonitoring() {
        if (networkCallback != null) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network became available")
                refresh()
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "Network lost")
                refresh()
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                refresh()
            }
        }
        networkCallback = callback
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager?.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network callback: ${e.message}")
            // Fallback: register default callback if supported
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    connectivityManager?.registerDefaultNetworkCallback(callback)
                } catch (e2: Exception) {
                    Log.w(TAG, "Failed to register default network callback: ${e2.message}")
                }
            }
        }
        refresh()
    }

    fun stopMonitoring() {
        networkCallback?.let { callback ->
            try {
                connectivityManager?.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering network callback: ${e.message}")
            }
            networkCallback = null
        }
    }

    fun refresh(): List<NetworkAddress> {
        val list = mutableListOf<NetworkAddress>()
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue

                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        val name = intf.name.lowercase()
                        val type = categorizeInterface(name, host)
                        val displayName = formatDisplayName(intf.displayName, name, type)
                        list.add(
                            NetworkAddress(
                                interfaceName = intf.name,
                                displayName = displayName,
                                ipAddress = host,
                                type = type
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error enumerating network interfaces", e)
        }

        // Sort so Wi-Fi and Hotspot are prioritized at the top
        val sorted = list.sortedBy { addr ->
            when (addr.type) {
                NetworkType.HOTSPOT -> 0
                NetworkType.WIFI -> 1
                NetworkType.ETHERNET -> 2
                NetworkType.USB_TETHERING -> 3
                NetworkType.OTHER -> 4
            }
        }
        _availableAddresses.value = sorted
        return sorted
    }

    private fun categorizeInterface(name: String, ip: String): NetworkType {
        // Android Hotspot / SoftAP interfaces often use ap0, swlan0, wlan1, or default hotspot subnet 192.168.43.x / 192.168.49.x
        return when {
            name.startsWith("ap") || name.startsWith("swlan") || ip.startsWith("192.168.43.") || ip.startsWith("192.168.49.") -> {
                NetworkType.HOTSPOT
            }
            name.startsWith("wlan") -> {
                NetworkType.WIFI
            }
            name.startsWith("eth") -> {
                NetworkType.ETHERNET
            }
            name.startsWith("rndis") || name.startsWith("usb") -> {
                NetworkType.USB_TETHERING
            }
            else -> NetworkType.OTHER
        }
    }

    private fun formatDisplayName(sysDisplayName: String, name: String, type: NetworkType): String {
        return when (type) {
            NetworkType.HOTSPOT -> "Android Hotspot ($name)"
            NetworkType.WIFI -> "Wi-Fi ($name)"
            NetworkType.ETHERNET -> "Ethernet ($name)"
            NetworkType.USB_TETHERING -> "USB Tethering ($name)"
            NetworkType.OTHER -> "$sysDisplayName ($name)"
        }
    }

    companion object {
        private const val TAG = "NetworkManager"
    }
}
