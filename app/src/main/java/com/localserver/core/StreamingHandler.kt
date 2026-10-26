package com.localserver.core

import android.os.ParcelFileDescriptor
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import java.io.FileInputStream
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
     * Streams data with HTTP Range and HEAD support.
     * If a ParcelFileDescriptor is available, uses NIO FileChannel.position(start) for O(1) seeking.
     */
    suspend fun streamFile(
        call: ApplicationCall,
        contentType: ContentType,
        totalSize: Long,
        fileName: String,
        asAttachment: Boolean = false,
        pfdProvider: (() -> ParcelFileDescriptor?)? = null,
        streamProvider: () -> InputStream?
    ) {
        val rangeHeader = call.request.headers[HttpHeaders.Range]
        val range = parseRangeHeader(rangeHeader, totalSize)
        val isHead = call.request.local.method == HttpMethod.Head

        call.response.header(HttpHeaders.AcceptRanges, "bytes")
        val dispositionType = if (asAttachment) "attachment" else "inline"
        call.response.header(
            HttpHeaders.ContentDisposition,
            "$dispositionType; filename=\"$fileName\""
        )

        if (range != null) {
            // 206 Partial Content
            call.response.header(
                HttpHeaders.ContentRange,
                "bytes ${range.start}-${range.end}/$totalSize"
            )
            call.response.header(HttpHeaders.ContentLength, range.contentLength.toString())

            if (isHead) {
                call.respond(HttpStatusCode.PartialContent)
                return
            }

            call.respondBytesWriter(
                contentType = contentType,
                status = HttpStatusCode.PartialContent
            ) {
                try {
                    streamData(this, range.start, range.contentLength, pfdProvider, streamProvider)
                } catch (_: Throwable) {
                    // Client disconnected or connection was interrupted
                }
            }
        } else {
            // 200 OK
            call.response.header(HttpHeaders.ContentLength, totalSize.toString())

            if (isHead) {
                call.respond(HttpStatusCode.OK)
                return
            }

            call.respondBytesWriter(
                contentType = contentType,
                status = HttpStatusCode.OK
            ) {
                try {
                    streamData(this, 0L, totalSize, pfdProvider, streamProvider)
                } catch (_: Throwable) {
                    // Client disconnected or connection was interrupted
                }
            }
        }
    }

    private suspend fun streamData(
        channel: ByteWriteChannel,
        startOffset: Long,
        length: Long,
        pfdProvider: (() -> ParcelFileDescriptor?)?,
        streamProvider: () -> InputStream?
    ) {
        val pfd = pfdProvider?.invoke()
        if (pfd != null) {
            pfd.use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { fis ->
                    if (startOffset > 0) {
                        fis.channel.position(startOffset)
                    }
                    copyBounded(fis, channel, length)
                }
            }
        } else {
            val stream = streamProvider() ?: return
            stream.use { fis ->
                if (startOffset > 0) {
                    var skipped = 0L
                    while (skipped < startOffset) {
                        val n = fis.skip(startOffset - skipped)
                        if (n <= 0) break
                        skipped += n
                    }
                }
                copyBounded(fis, channel, length)
            }
        }
    }

    private suspend fun copyBounded(
        input: InputStream,
        channel: ByteWriteChannel,
        length: Long
    ) {
        val buffer = ByteArray(64 * 1024) // 64 KB bounded chunk buffer
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
