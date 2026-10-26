package com.localserver

import com.localserver.core.StreamingHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StreamingHandlerTest {

    private val totalFileSize = 10000L // 10,000 bytes

    @Test
    fun testParseRangeHeaderNullOrInvalid() {
        assertNull(StreamingHandler.parseRangeHeader(null, totalFileSize))
        assertNull(StreamingHandler.parseRangeHeader("", totalFileSize))
        assertNull(StreamingHandler.parseRangeHeader("invalid-header", totalFileSize))
        assertNull(StreamingHandler.parseRangeHeader("bytes=", totalFileSize))
        assertNull(StreamingHandler.parseRangeHeader("bytes=abc-def", totalFileSize))
    }

    @Test
    fun testParseRangeHeaderClosedRange() {
        val range = StreamingHandler.parseRangeHeader("bytes=0-499", totalFileSize)
        assertNotNull(range)
        assertEquals(0L, range!!.start)
        assertEquals(499L, range.end)
        assertEquals(500L, range.contentLength)
        assertEquals(10000L, range.totalLength)
    }

    @Test
    fun testParseRangeHeaderOpenEndedRange() {
        val range = StreamingHandler.parseRangeHeader("bytes=5000-", totalFileSize)
        assertNotNull(range)
        assertEquals(5000L, range!!.start)
        assertEquals(9999L, range.end)
        assertEquals(5000L, range.contentLength)
    }

    @Test
    fun testParseRangeHeaderSuffixRange() {
        val range = StreamingHandler.parseRangeHeader("bytes=-1000", totalFileSize)
        assertNotNull(range)
        assertEquals(9000L, range!!.start)
        assertEquals(9999L, range.end)
        assertEquals(1000L, range.contentLength)
    }

    @Test
    fun testParseRangeHeaderClampedToEnd() {
        val range = StreamingHandler.parseRangeHeader("bytes=9500-20000", totalFileSize)
        assertNotNull(range)
        assertEquals(9500L, range!!.start)
        assertEquals(9999L, range.end) // Clamped to totalSize - 1
        assertEquals(500L, range.contentLength)
    }

    @Test
    fun testParseRangeHeaderStartBeyondTotalReturnsNull() {
        val range = StreamingHandler.parseRangeHeader("bytes=15000-", totalFileSize)
        assertNull(range)
    }
}
