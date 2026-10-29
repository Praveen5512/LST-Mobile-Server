package com.localserver.core

import android.util.Log
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Pure Kotlin Multicast DNS (mDNS) responder running on UDP port 5353.
 *
 * Operates alongside Android's native NsdManager to provide:
 * 1. Immediate broadcast announcements on server startup.
 * 2. Guaranteed discovery over Android Hotspot / SoftAP interfaces (where native NsdManager is blind).
 * 3. Support for Python Zeroconf and TV client query responses.
 * 4. Graceful goodbye broadcast (TTL=0) on shutdown.
 */
class MdnsResponder {

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var socket: MulticastSocket? = null
    private var listenerJob: Job? = null
    private var tickerJob: Job? = null

    private var currentPort: Int = 8080
    private var currentServiceName: String = "LocalMediaServer"
    private var currentIpAddresses: List<String> = emptyList()
    private val joinedInterfaces = mutableListOf<NetworkInterface>()

    @Volatile
    private var isRunning: Boolean = false

    companion object {
        private const val TAG = "MdnsResponder"
        private const val ANNOUNCE_INTERVAL_MS = 30_000L // 30 seconds periodic refresh
    }

    @Synchronized
    fun start(port: Int, serviceName: String, ipAddresses: List<String>) {
        if (isRunning) {
            update(port, ipAddresses)
            return
        }

        currentPort = port
        currentServiceName = serviceName
        currentIpAddresses = ipAddresses

        try {
            val sock = MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(MdnsPacket.MDNS_PORT))
                soTimeout = 2000
                timeToLive = 255
            }
            socket = sock
            isRunning = true

            joinMulticastOnInterfaces(sock)
            sendAnnouncement()

            listenerJob = scope.launch {
                listenForQueries(sock)
            }

            tickerJob = scope.launch {
                while (isActive && isRunning) {
                    delay(ANNOUNCE_INTERVAL_MS)
                    if (isRunning) {
                        sendAnnouncement()
                    }
                }
            }

            Log.i(TAG, "MdnsResponder started on UDP port ${MdnsPacket.MDNS_PORT} for $serviceName ($ipAddresses:$port)")
        } catch (e: Exception) {
            Log.w(TAG, "Unable to bind raw mDNS socket on port 5353 (Android native NsdManager will handle discovery): ${e.message}")
            isRunning = false
        }
    }

    @Synchronized
    fun update(port: Int, ipAddresses: List<String>) {
        currentPort = port
        currentIpAddresses = ipAddresses
        if (!isRunning) return

        socket?.let { sock ->
            joinMulticastOnInterfaces(sock)
            sendAnnouncement()
        }
    }

    @Synchronized
    fun stop() {
        if (!isRunning) return
        isRunning = false

        // Broadcast goodbye packet with TTL = 0 to inform clients server is stopping
        runCatching {
            sendGoodbye()
        }

        listenerJob?.cancel()
        listenerJob = null
        tickerJob?.cancel()
        tickerJob = null

        socket?.let { sock ->
            val group = runCatching { InetAddress.getByName(MdnsPacket.MDNS_MULTICAST_IPV4) }.getOrNull()
            if (group != null) {
                for (intf in joinedInterfaces) {
                    runCatching { sock.leaveGroup(InetSocketAddress(group, MdnsPacket.MDNS_PORT), intf) }
                }
            }
            joinedInterfaces.clear()
            runCatching { sock.close() }
        }
        socket = null
        Log.i(TAG, "MdnsResponder stopped")
    }

    private fun joinMulticastOnInterfaces(sock: MulticastSocket) {
        val group = runCatching { InetAddress.getByName(MdnsPacket.MDNS_MULTICAST_IPV4) }.getOrNull() ?: return
        val groupAddress = InetSocketAddress(group, MdnsPacket.MDNS_PORT)

        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback || !intf.supportsMulticast()) continue
                if (!joinedInterfaces.contains(intf)) {
                    try {
                        sock.joinGroup(groupAddress, intf)
                        joinedInterfaces.add(intf)
                        Log.d(TAG, "Joined mDNS multicast group on ${intf.name}")
                    } catch (e: Exception) {
                        Log.v(TAG, "Could not join mDNS group on ${intf.name}: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error discovering network interfaces for mDNS join: ${e.message}")
        }
    }

    private fun sendAnnouncement() {
        if (!isRunning) return
        val sock = socket ?: return
        val packetBytes = MdnsPacket.buildResponsePacket(
            serviceName = currentServiceName,
            port = currentPort,
            ipAddresses = currentIpAddresses,
            ttlSeconds = MdnsPacket.DEFAULT_TTL
        )
        sendPacketToMulticast(sock, packetBytes)
    }

    private fun sendGoodbye() {
        val sock = socket ?: return
        val goodbyeBytes = MdnsPacket.buildResponsePacket(
            serviceName = currentServiceName,
            port = currentPort,
            ipAddresses = currentIpAddresses,
            ttlSeconds = 0L // TTL 0 = Goodbye
        )
        sendPacketToMulticast(sock, goodbyeBytes)
    }

    private fun sendPacketToMulticast(sock: MulticastSocket, bytes: ByteArray) {
        try {
            val group = InetAddress.getByName(MdnsPacket.MDNS_MULTICAST_IPV4)
            val packet = DatagramPacket(bytes, bytes.size, group, MdnsPacket.MDNS_PORT)
            sock.send(packet)
            Log.d(TAG, "Sent mDNS broadcast for $currentServiceName (${bytes.size} bytes)")
        } catch (e: Exception) {
            Log.w(TAG, "Error broadcasting mDNS packet: ${e.message}")
        }
    }

    private fun listenForQueries(sock: MulticastSocket) {
        val buffer = ByteArray(2048)
        val packet = DatagramPacket(buffer, buffer.size)

        while (isRunning && !sock.isClosed) {
            try {
                sock.receive(packet)
                if (packet.length < 12) continue

                if (MdnsPacket.isQueryForHttpService(buffer, packet.length)) {
                    Log.d(TAG, "Received relevant mDNS query from ${packet.address}:${packet.port}")
                    // Reply to multicast and unicast
                    val responseBytes = MdnsPacket.buildResponsePacket(
                        serviceName = currentServiceName,
                        port = currentPort,
                        ipAddresses = currentIpAddresses,
                        ttlSeconds = MdnsPacket.DEFAULT_TTL
                    )
                    sendPacketToMulticast(sock, responseBytes)

                    // Also unicast response directly back to the querying port
                    try {
                        val directPacket = DatagramPacket(responseBytes, responseBytes.size, packet.address, packet.port)
                        sock.send(directPacket)
                    } catch (_: Exception) { }
                }
            } catch (_: java.net.SocketTimeoutException) {
                // Expected timeout for loop cancellation check
            } catch (e: Exception) {
                if (isRunning) {
                    Log.d(TAG, "Socket receive notice: ${e.message}")
                }
                break
            }
        }
    }
}
