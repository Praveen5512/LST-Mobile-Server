package com.localserver.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Encodes and decodes Multicast DNS (mDNS) packets per RFC 6762 / RFC 1035.
 * Used for zero-configuration network discovery across Wi-Fi and Hotspot networks.
 */
object MdnsPacket {

    const val MDNS_PORT = 5353
    const val MDNS_MULTICAST_IPV4 = "224.0.0.251"
    const val DEFAULT_TTL = 120L

    const val TYPE_A = 1
    const val TYPE_PTR = 12
    const val TYPE_TXT = 16
    const val TYPE_SRV = 33

    const val CLASS_IN = 1
    const val CLASS_IN_FLUSH = 0x8001 // Internet class + Cache-Flush bit

    /**
     * Builds an mDNS Authoritative Response packet advertising the LocalMediaServer HTTP service.
     * Includes PTR, SRV, TXT, and A records.
     * When ttlSeconds == 0, this forms a Goodbye packet to immediately purge from client caches.
     */
    fun buildResponsePacket(
        serviceName: String,
        port: Int,
        ipAddresses: List<String>,
        ttlSeconds: Long = DEFAULT_TTL
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)

        val cleanName = serviceName.trim()
        val serviceType = "_http._tcp.local"
        val fullServiceName = "$cleanName._http._tcp.local"
        val hostName = "$cleanName.local"

        // Header (12 bytes)
        dos.writeShort(0) // Transaction ID
        dos.writeShort(0x8400) // Flags: Standard response, Authoritative (AA=1)
        dos.writeShort(0) // Questions: 0

        val validIps = ipAddresses.mapNotNull { ip ->
            runCatching {
                val inet = InetAddress.getByName(ip)
                if (inet is Inet4Address && !inet.isLoopbackAddress) inet else null
            }.getOrNull()
        }

        val answerCount = 3 + validIps.size // PTR + SRV + TXT + A records
        dos.writeShort(answerCount)
        dos.writeShort(0) // Authority RRs
        dos.writeShort(0) // Additional RRs

        val ttl = ttlSeconds.toInt()

        // 1. PTR Record: _http._tcp.local -> <serviceName>._http._tcp.local
        writeDomainName(out, serviceType)
        dos.writeShort(TYPE_PTR)
        dos.writeShort(CLASS_IN_FLUSH)
        dos.writeInt(ttl)
        val ptrData = ByteArrayOutputStream().apply { writeDomainName(this, fullServiceName) }.toByteArray()
        dos.writeShort(ptrData.size)
        dos.write(ptrData)

        // 2. SRV Record: <serviceName>._http._tcp.local -> port, target <hostName>
        writeDomainName(out, fullServiceName)
        dos.writeShort(TYPE_SRV)
        dos.writeShort(CLASS_IN_FLUSH)
        dos.writeInt(ttl)
        val srvData = ByteArrayOutputStream().apply {
            val sDos = DataOutputStream(this)
            sDos.writeShort(0) // Priority
            sDos.writeShort(0) // Weight
            sDos.writeShort(port) // Port
            writeDomainName(this, hostName)
        }.toByteArray()
        dos.writeShort(srvData.size)
        dos.write(srvData)

        // 3. TXT Record: <serviceName>._http._tcp.local -> attributes
        writeDomainName(out, fullServiceName)
        dos.writeShort(TYPE_TXT)
        dos.writeShort(CLASS_IN_FLUSH)
        dos.writeInt(ttl)
        val txtData = ByteArrayOutputStream().apply {
            val attributes = listOf(
                "path=/api/shares",
                "version=1.0",
                "name=$cleanName"
            )
            for (attr in attributes) {
                val b = attr.toByteArray(Charsets.UTF_8)
                val len = b.size.coerceAtMost(255)
                write(len)
                write(b, 0, len)
            }
        }.toByteArray()
        dos.writeShort(txtData.size)
        dos.write(txtData)

        // 4. A Records: <hostName> -> IPv4 addresses
        for (inet in validIps) {
            writeDomainName(out, hostName)
            dos.writeShort(TYPE_A)
            dos.writeShort(CLASS_IN_FLUSH)
            dos.writeInt(ttl)
            val ipBytes = inet.address
            dos.writeShort(ipBytes.size)
            dos.write(ipBytes)
        }

        return out.toByteArray()
    }

    /**
     * Builds an mDNS Query packet asking for _http._tcp.local.
     */
    fun buildQueryPacket(queryDomain: String = "_http._tcp.local"): ByteArray {
        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)

        dos.writeShort(0) // ID
        dos.writeShort(0x0000) // Standard Query
        dos.writeShort(1) // 1 Question
        dos.writeShort(0)
        dos.writeShort(0)
        dos.writeShort(0)

        writeDomainName(out, queryDomain)
        dos.writeShort(TYPE_PTR)
        dos.writeShort(CLASS_IN) // IN class

        return out.toByteArray()
    }

    /**
     * Checks if an incoming UDP packet is an mDNS query interested in HTTP services or this media server.
     */
    fun isQueryForHttpService(data: ByteArray, length: Int): Boolean {
        if (length < 12) return false
        val flags = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val isQuery = (flags and 0x8000) == 0
        if (!isQuery) return false

        val qdCount = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
        if (qdCount <= 0) return false

        // Parse question names
        var offset = 12
        for (i in 0 until qdCount) {
            if (offset >= length) break
            val (name, nextOffset) = readDomainName(data, offset, length)
            offset = nextOffset + 4 // skip QTYPE (2) + QCLASS (2)
            val lower = name.lowercase()
            if (lower.contains("_http") || lower.contains("localmediaserver") || lower == "_services._dns-sd._udp.local") {
                return true
            }
        }
        return false
    }

    /**
     * Writes a domain name as RFC 1035 length-prefixed labels ending with a zero byte.
     */
    fun writeDomainName(out: ByteArrayOutputStream, name: String) {
        val clean = name.trim().trimEnd('.')
        if (clean.isEmpty()) {
            out.write(0)
            return
        }
        val parts = clean.split('.')
        for (part in parts) {
            val bytes = part.toByteArray(Charsets.UTF_8)
            val len = bytes.size.coerceAtMost(63)
            out.write(len)
            out.write(bytes, 0, len)
        }
        out.write(0)
    }

    /**
     * Reads a domain name with support for DNS compression pointers (0xC0).
     */
    fun readDomainName(buffer: ByteArray, startOffset: Int, maxLen: Int = buffer.size): Pair<String, Int> {
        var cur = startOffset
        val labels = mutableListOf<String>()
        var jumped = false
        var returnOffset = -1
        var steps = 0

        while (cur < maxLen && steps < 100) {
            steps++
            val len = buffer[cur].toInt() and 0xFF
            if (len == 0) {
                if (!jumped) returnOffset = cur + 1
                break
            }
            if ((len and 0xC0) == 0xC0) {
                if (cur + 1 >= maxLen) break
                val ptr = ((len and 0x3F) shl 8) or (buffer[cur + 1].toInt() and 0xFF)
                if (!jumped) {
                    returnOffset = cur + 2
                    jumped = true
                }
                cur = ptr
            } else {
                cur++
                if (cur + len <= maxLen) {
                    labels.add(String(buffer, cur, len, Charsets.UTF_8))
                    cur += len
                } else {
                    break
                }
            }
        }

        val finalOffset = if (returnOffset != -1) returnOffset else cur
        return Pair(labels.joinToString("."), finalOffset)
    }
}
