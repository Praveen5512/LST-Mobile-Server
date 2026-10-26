package com.localserver

import com.localserver.core.ServerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerConfigTest {

    @Test
    fun testValidPorts() {
        assertTrue(ServerConfig.isValidPort(1024))
        assertTrue(ServerConfig.isValidPort(8080))
        assertTrue(ServerConfig.isValidPort(65535))
    }

    @Test
    fun testInvalidPorts() {
        assertFalse(ServerConfig.isValidPort(0))
        assertFalse(ServerConfig.isValidPort(80))
        assertFalse(ServerConfig.isValidPort(1023))
        assertFalse(ServerConfig.isValidPort(65536))
        assertFalse(ServerConfig.isValidPort(-1))
    }

    @Test
    fun testDefaultConfig() {
        val config = ServerConfig()
        assertEquals(8080, config.port)
        assertEquals("0.0.0.0", config.bindAddress)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testPortOutOfBoundsThrowsException() {
        ServerConfig(port = 80)
    }
}
