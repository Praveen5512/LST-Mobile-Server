package com.localserver.core

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import java.io.InputStream
import kotlin.math.min

object StreamingHandler {

    data class ByteRange(
        val start: Long,
        val end: Long,
        val totalLength: Long
    ) {
        val contentLength: Long get() = (end - start) + 1
    }

    /**
     * Parses the HTTP Range header (e.g. "bytes=0-1023", "bytes=500-", "bytes=-500").
     * Returns null if no range is requested or invalid.
     */
    fun parseRangeHeader(rangeHeader: String?, totalSize: Long): ByteRange? {
        if (rangeHeader == null || !rangeHeader.startsWith("bytes=")) {
            return null
        }
        val rangeSpec = rangeHeader.removePrefix("bytes=").trim()
        val parts = rangeSpec.split("-", limit = 2)
        if (parts.size != 2) return null

        val startStr = parts[0].trim()
        val endStr = parts[1].trim()

        return try {
            when {
                startStr.isNotEmpty() && endStr.isNotEmpty() -> {
                    val s = startStr.toLong()
                    val e = min(endStr.toLong(), totalSize - 1)
                    if (s in 0..e) ByteRange(s, e, totalSize) else null
                }
                startStr.isNotEmpty() && endStr.isEmpty() -> {
                    val s = startStr.toLong()
                    if (s in 0 until totalSize) ByteRange(s, totalSize - 1, totalSize) else null
                }
                startStr.isEmpty() && endStr.isNotEmpty() -> {
                    val suffixLength = endStr.toLong()
                    if (suffixLength <= 0) return null
                    val s = (totalSize - suffixLength).coerceAtLeast(0)
                    ByteRange(s, totalSize - 1, totalSize)
                }
                else -> null
            }
        } catch (_: NumberFormatException) {
            null
        }
    }

    /**
     * Streams data from an InputStream or byte source with optional byte-range handling.
     * Uses bounded buffers (64KB chunks) to prevent loading large files into memory.
     */
    suspend fun streamContent(
        call: ApplicationCall,
        contentType: ContentType,
        totalSize: Long,
        fileName: String? = null,
        openInputStream: () -> InputStream
    ) {
        val rangeHeader = call.request.headers[HttpHeaders.Range]
        val range = parseRangeHeader(rangeHeader, totalSize)

        call.response.header(HttpHeaders.AcceptRanges, "bytes")
        if (fileName != null) {
            call.response.header(
                HttpHeaders.ContentDisposition,
                "inline; filename=\"$fileName\""
            )
        }

        if (range != null) {
            // 206 Partial Content
            call.response.header(
                HttpHeaders.ContentRange,
                "bytes ${range.start}-${range.end}/$totalSize"
            )
            call.response.header(HttpHeaders.ContentLength, range.contentLength.toString())

            call.respondBytesWriter(
                contentType = contentType,
                status = HttpStatusCode.PartialContent
            ) {
                openInputStream().use { stream ->
                    copyRangeToChannel(stream, this, range.start, range.contentLength)
                }
            }
        } else {
            // 200 OK
            call.response.header(HttpHeaders.ContentLength, totalSize.toString())

            call.respondBytesWriter(
                contentType = contentType,
                status = HttpStatusCode.OK
            ) {
                openInputStream().use { stream ->
                    copyRangeToChannel(stream, this, 0L, totalSize)
                }
            }
        }
    }

    private suspend fun copyRangeToChannel(
        input: InputStream,
        channel: ByteWriteChannel,
        skipBytes: Long,
        length: Long
    ) {
        if (skipBytes > 0) {
            var skipped = 0L
            while (skipped < skipBytes) {
                val n = input.skip(skipBytes - skipped)
                if (n <= 0) break
                skipped += n
            }
        }

        val buffer = ByteArray(64 * 1024) // 64 KB bounded buffer
        var remaining = length
        while (remaining > 0) {
            val toRead = min(remaining, buffer.size.toLong()).toInt()
            val read = input.read(buffer, 0, toRead)
            if (read == -1) break
            channel.writeFully(buffer, 0, read)
            remaining -= read
        }
        channel.flush()
    }
}
