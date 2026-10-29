package com.localserver

import com.localserver.core.MdnsPacket
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MdnsPacketTest {

    @Test
    fun testDomainNameEncodingAndDecoding() {
        val out = ByteArrayOutputStream()
        MdnsPacket.writeDomainName(out, "_http._tcp.local")
        val bytes = out.toByteArray()

        val (decoded, finalOffset) = MdnsPacket.readDomainName(bytes, 0)
        assertEquals("_http._tcp.local", decoded)
        assertEquals(bytes.size, finalOffset)
    }

    @Test
    fun testResponsePacketStructure() {
        val serviceName = "LocalMediaServer-TestPC"
        val port = 8080
        val ips = listOf("192.168.1.50", "192.168.43.1")

        val packet = MdnsPacket.buildResponsePacket(
            serviceName = serviceName,
            port = port,
            ipAddresses = ips,
            ttlSeconds = 120L
        )

        assertTrue(packet.size > 50)

        val bb = ByteBuffer.wrap(packet)
        val transactionId = bb.short
        val flags = bb.short.toInt() and 0xFFFF
        val qdCount = bb.short.toInt()
        val anCount = bb.short.toInt()

        assertEquals(0.toShort(), transactionId)
        assertEquals(0x8400, flags) // QR=1, AA=1
        assertEquals(0, qdCount)
        assertEquals(5, anCount) // 1 PTR + 1 SRV + 1 TXT + 2 A records = 5
    }

    @Test
    fun testGoodbyePacketHasZeroTtl() {
        val serviceName = "LocalMediaServer-Goodbye"
        val port = 8080
        val ips = listOf("192.168.1.10")

        val packet = MdnsPacket.buildResponsePacket(
            serviceName = serviceName,
            port = port,
            ipAddresses = ips,
            ttlSeconds = 0L
        )

        // Read through packet to verify TTL in PTR record
        val bb = ByteBuffer.wrap(packet)
        bb.position(12) // Skip header

        val (domain, offset) = MdnsPacket.readDomainName(packet, 12)
        assertEquals("_http._tcp.local", domain)

        bb.position(offset)
        val type = bb.short.toInt()
        val clazz = bb.short.toInt() and 0xFFFF
        val ttl = bb.int

        assertEquals(MdnsPacket.TYPE_PTR, type)
        assertEquals(MdnsPacket.CLASS_IN_FLUSH, clazz)
        assertEquals(0, ttl) // Goodbye TTL must be 0
    }

    @Test
    fun testQueryPacketAndQueryDetection() {
        val queryPacket = MdnsPacket.buildQueryPacket("_http._tcp.local")
        assertTrue(queryPacket.size >= 12)

        val isHttpQuery = MdnsPacket.isQueryForHttpService(queryPacket, queryPacket.size)
        assertTrue("Expected query packet to be detected as HTTP service query", isHttpQuery)

        // Non-matching query
        val dummyQuery = MdnsPacket.buildQueryPacket("_printer._tcp.local")
        val isPrinterQueryForHttp = MdnsPacket.isQueryForHttpService(dummyQuery, dummyQuery.size)
        assertFalse("Expected printer query to not match http service", isPrinterQueryForHttp)
    }

    @Test
    fun testIsQueryForHttpServiceWithShortBuffer() {
        val shortBuffer = ByteArray(8)
        assertFalse(MdnsPacket.isQueryForHttpService(shortBuffer, shortBuffer.size))
    }
}
