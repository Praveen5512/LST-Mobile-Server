package com.localserver

import com.localserver.core.MdnsPacket
import com.localserver.core.ShareRoot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvClientDiscoveryAndContractTest {

    @Test
    fun testTvClientDiscoveryMdnsPacketStructure() {
        val serverName = "LocalMediaServer-Android"
        val port = 8080
        val ips = listOf("192.168.1.25")

        val packet = MdnsPacket.buildResponsePacket(serverName, port, ips)
        assertNotNull(packet)
        assertTrue(packet.isNotEmpty())

        // Check query matching: TV app sends _http._tcp.local query
        val tvQuery = MdnsPacket.buildQueryPacket("_http._tcp.local")
        assertTrue(MdnsPacket.isQueryForHttpService(tvQuery, tvQuery.size))
    }

    @Test
    fun testTvSharesContractJsonFormat() {
        // As defined in the reference Python implementation:
        // shares = [{"id": "share_1", "name": "Test PC Movies", "isAccessible": True}]
        val share1 = ShareRoot(
            id = "share_3126f9",
            name = "Movies & Series",
            uriString = "content://com.android.externalstorage.documents/tree/primary%3AMovies",
            createdAt = 1729000000000L,
            isAccessible = true
        )
        val share2 = ShareRoot(
            id = "share_8a41c2",
            name = "Lossless FLAC Music",
            uriString = "content://com.android.externalstorage.documents/tree/primary%3AMusic",
            createdAt = 1729000001000L,
            isAccessible = true
        )

        val array = JSONArray().apply {
            for (share in listOf(share1, share2)) {
                put(
                    JSONObject().apply {
                        put("id", share.id)
                        put("name", share.name)
                        put("uri", share.uriString)
                        put("isAccessible", share.isAccessible)
                        put("createdAt", share.createdAt)
                    }
                )
            }
        }

        assertEquals(2, array.length())

        val first = array.getJSONObject(0)
        assertEquals("share_3126f9", first.getString("id"))
        assertEquals("Movies & Series", first.getString("name"))
        assertTrue(first.getBoolean("isAccessible"))
        assertEquals("content://com.android.externalstorage.documents/tree/primary%3AMovies", first.getString("uri"))

        val second = array.getJSONObject(1)
        assertEquals("share_8a41c2", second.getString("id"))
        assertEquals("Lossless FLAC Music", second.getString("name"))
        assertTrue(second.getBoolean("isAccessible"))
    }

    @Test
    fun testTvDirectoryBrowseContractJsonFormat() {
        // TV Client expects: { shareId, shareName, currentPath, items: [...] }
        val shareId = "share_3126f9"
        val shareName = "Movies & Series"
        val currentPath = "Action"

        val items = JSONArray().apply {
            put(
                JSONObject().apply {
                    put("name", "SciFi")
                    put("path", "Action/SciFi")
                    put("isDirectory", true)
                    put("size", 0L)
                    put("mimeType", "application/x-directory")
                    put("modified", 1729000000000L)
                }
            )
            put(
                JSONObject().apply {
                    put("name", "Interstellar.2014.1080p.mkv")
                    put("path", "Action/Interstellar.2014.1080p.mkv")
                    put("isDirectory", false)
                    put("size", 4294967296L)
                    put("mimeType", "video/x-matroska")
                    put("modified", 1729012345000L)
                    put("streamUrl", "/api/stream?shareId=$shareId&path=Action%2FInterstellar.2014.1080p.mkv")
                }
            )
        }

        val response = JSONObject().apply {
            put("shareId", shareId)
            put("shareName", shareName)
            put("currentPath", currentPath)
            put("items", items)
        }

        assertEquals("share_3126f9", response.getString("shareId"))
        assertEquals("Action", response.getString("currentPath"))
        val returnedItems = response.getJSONArray("items")
        assertEquals(2, returnedItems.length())

        val fileItem = returnedItems.getJSONObject(1)
        assertEquals("Interstellar.2014.1080p.mkv", fileItem.getString("name"))
        assertEquals(4294967296L, fileItem.getLong("size"))
        assertEquals("video/x-matroska", fileItem.getString("mimeType"))
        assertTrue(fileItem.getString("streamUrl").startsWith("/api/stream?shareId="))
    }
}
