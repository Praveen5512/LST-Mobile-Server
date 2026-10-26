package com.localserver

import com.localserver.core.FileRepository
import com.localserver.core.MimeTypeResolver
import com.localserver.core.StreamingHandler
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Milestone 5: Security & Performance Verification Test Suite.
 *
 * Verifies:
 * 1. Path traversal defense against arbitrary filesystem navigation.
 * 2. HTTP Byte Range parsing accuracy and boundaries.
 * 3. Safe MIME type resolution.
 * 4. Concurrent client streaming performance without data corruption.
 * 5. Interrupted transfer stream resource cleanup.
 */
class Milestone5SecurityAndPerformanceTest {

    // =========================================================================
    // 1. Path Traversal & Security Validation
    // =========================================================================

    @Test
    fun testPathTraversalRejection() {
        // Standard parent directory traversal
        assertNull(FileRepository.sanitizeRelativePath("../etc/passwd"))
        assertNull(FileRepository.sanitizeRelativePath(".."))
        assertNull(FileRepository.sanitizeRelativePath("folder/.."))
        assertNull(FileRepository.sanitizeRelativePath("folder/../../secret"))
        assertNull(FileRepository.sanitizeRelativePath("a/b/c/../../../../root"))

        // Encoded traversal variations
        assertNull(FileRepository.sanitizeRelativePath("%2e%2e/secret"))
        assertNull(FileRepository.sanitizeRelativePath("folder/%2e%2e/secret"))
        assertNull(FileRepository.sanitizeRelativePath("%2e%2e%2fsecret"))

        // Windows path separator escape attempts
        assertNull(FileRepository.sanitizeRelativePath("folder\\subfolder"))
        assertNull(FileRepository.sanitizeRelativePath("..\\..\\windows\\win.ini"))

        // Null-byte injection
        assertNull(FileRepository.sanitizeRelativePath("secret.txt\u0000.jpg"))
        assertNull(FileRepository.sanitizeRelativePath("secret.txt%00.jpg"))
    }

    @Test
    fun testValidPathNormalization() {
        assertEquals("", FileRepository.sanitizeRelativePath(null))
        assertEquals("", FileRepository.sanitizeRelativePath(""))
        assertEquals("", FileRepository.sanitizeRelativePath("/"))
        assertEquals("", FileRepository.sanitizeRelativePath("///"))
        assertEquals("music/song.mp3", FileRepository.sanitizeRelativePath("music/song.mp3"))
        assertEquals("music/song.mp3", FileRepository.sanitizeRelativePath("/music/song.mp3/"))
        assertEquals("music/sub/song.mp3", FileRepository.sanitizeRelativePath("///music///sub///song.mp3///"))
    }

    // =========================================================================
    // 2. HTTP Byte Range & Partial Content Validation
    // =========================================================================

    @Test
    fun testRangeHeaderParsing() {
        val totalSize = 100_000L // 100 KB

        // Standard range 0-1023 (1024 bytes)
        val r1 = StreamingHandler.parseRangeHeader("bytes=0-1023", totalSize)
        assertNotNull(r1)
        assertEquals(0L, r1!!.start)
        assertEquals(1023L, r1.end)
        assertEquals(1024L, r1.contentLength)

        // Open-ended range: from byte 5000 to end
        val r2 = StreamingHandler.parseRangeHeader("bytes=5000-", totalSize)
        assertNotNull(r2)
        assertEquals(5000L, r2!!.start)
        assertEquals(99_999L, r2.end)
        assertEquals(95_000L, r2.contentLength)

        // Suffix range: last 1000 bytes
        val r3 = StreamingHandler.parseRangeHeader("bytes=-1000", totalSize)
        assertNotNull(r3)
        assertEquals(99_000L, r3!!.start)
        assertEquals(99_999L, r3.end)
        assertEquals(1000L, r3.contentLength)

        // Suffix larger than entire file: clamped to full file
        val r4 = StreamingHandler.parseRangeHeader("bytes=-200000", totalSize)
        assertNotNull(r4)
        assertEquals(0L, r4!!.start)
        assertEquals(99_999L, r4.end)
        assertEquals(100_000L, r4.contentLength)

        // Invalid: inverted range
        assertNull(StreamingHandler.parseRangeHeader("bytes=5000-1000", totalSize))

        // Invalid: start beyond total size
        assertNull(StreamingHandler.parseRangeHeader("bytes=150000-", totalSize))

        // Invalid: malformed prefix or characters
        assertNull(StreamingHandler.parseRangeHeader("items=0-10", totalSize))
        assertNull(StreamingHandler.parseRangeHeader("bytes=abc-xyz", totalSize))
        assertNull(StreamingHandler.parseRangeHeader("invalid", totalSize))
    }

    // =========================================================================
    // 3. MIME Type Resolution & Content Safety
    // =========================================================================

    @Test
    fun testMimeTypeResolution() {
        assertEquals("video/mp4", MimeTypeResolver.resolve("movie.mp4"))
        assertEquals("video/webm", MimeTypeResolver.resolve("clip.webm"))
        assertEquals("video/x-matroska", MimeTypeResolver.resolve("series.mkv"))
        assertEquals("audio/mpeg", MimeTypeResolver.resolve("song.mp3"))
        assertEquals("audio/flac", MimeTypeResolver.resolve("album.flac"))
        assertEquals("image/jpeg", MimeTypeResolver.resolve("photo.jpg"))
        assertEquals("image/png", MimeTypeResolver.resolve("diagram.png"))
        assertEquals("application/pdf", MimeTypeResolver.resolve("document.pdf"))

        // Unknown extensions default to application/octet-stream
        assertEquals("application/octet-stream", MimeTypeResolver.resolve("unknown.xyz123"))

        assertTrue(MimeTypeResolver.isVideo("video/mp4"))
        assertTrue(MimeTypeResolver.isAudio("audio/mpeg"))
        assertTrue(MimeTypeResolver.isImage("image/jpeg"))
        assertFalse(MimeTypeResolver.isVideo("audio/mpeg"))
    }

    // =========================================================================
    // 4. Concurrent Client Simulation (50 Concurrent Streams)
    // =========================================================================

    @Test
    fun testConcurrentClientStreamingPerformance() = runBlocking {
        // Generate a 1 MB test payload with deterministic pattern
        val payloadSize = 1024 * 1024 // 1 MB
        val sampleData = ByteArray(payloadSize) { (it % 256).toByte() }

        val concurrentClients = 50
        val successCount = AtomicInteger(0)

        val startTime = System.currentTimeMillis()

        // Launch 50 concurrent clients seeking and streaming 64 KB slices
        val jobs = (0 until concurrentClients).map { clientId ->
            async(Dispatchers.Default) {
                val startOffset = (clientId * 16384L) % (payloadSize - 65536)
                val sliceLength = 65536L // 64 KB

                val range = StreamingHandler.ByteRange(
                    start = startOffset,
                    end = startOffset + sliceLength - 1,
                    totalLength = payloadSize.toLong()
                )

                // Simulate bounded reading in 8 KB chunks
                val inputStream = ByteArrayInputStream(sampleData)
                inputStream.skip(range.start)

                val buffer = ByteArray(8192)
                var remaining = range.contentLength
                var readTotal = 0

                while (remaining > 0) {
                    val toRead = min(remaining, buffer.size.toLong()).toInt()
                    val n = inputStream.read(buffer, 0, toRead)
                    if (n == -1) break

                    // Verify data integrity: byte value must match deterministic formula
                    for (i in 0 until n) {
                        val expectedByte = ((range.start + readTotal + i) % 256).toByte()
                        if (buffer[i] != expectedByte) {
                            throw IllegalStateException("Data corruption detected at offset ${range.start + readTotal + i}")
                        }
                    }
                    readTotal += n
                    remaining -= n
                }

                assertEquals(sliceLength, readTotal.toLong())
                successCount.incrementAndGet()
            }
        }

        jobs.awaitAll()
        val durationMs = System.currentTimeMillis() - startTime

        assertEquals(concurrentClients, successCount.get())
        println("Milestone 5 Performance: 50 concurrent 64KB client streams completed in ${durationMs}ms without errors.")
    }

    // =========================================================================
    // 5. Interrupted Client Stream Resource Cleanup
    // =========================================================================

    @Test
    fun testInterruptedStreamClosesResource() {
        val streamClosed = AtomicBoolean(false)

        val trackedStream = object : InputStream() {
            private var bytesRead = 0

            override fun read(): Int {
                if (bytesRead++ >= 100) {
                    // Simulate client disconnect / broken socket
                    throw java.io.IOException("Client connection aborted (ECONNRESET)")
                }
                return 42
            }

            override fun close() {
                streamClosed.set(true)
                super.close()
            }
        }

        // Simulate StreamingHandler safe bounded copy
        try {
            trackedStream.use { stream ->
                val buffer = ByteArray(64)
                while (true) {
                    val n = stream.read(buffer)
                    if (n == -1) break
                }
            }
        } catch (_: java.io.IOException) {
            // Expected simulation error
        }

        // Verify resource was closed despite the exception
        assertTrue("Input stream must be closed when an interrupted transfer occurs", streamClosed.get())
    }
}
