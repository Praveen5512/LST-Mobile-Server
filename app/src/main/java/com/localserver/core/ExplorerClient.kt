package com.localserver.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class RemoteShare(
    val id: String,
    val name: String,
    val isAccessible: Boolean
)

data class RemoteItem(
    val id: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val mimeType: String,
    val relativePath: String,
    val streamUrl: String,
    val downloadUrl: String
) {
    val isVideo: Boolean get() = mimeType.startsWith("video/") || name.endsWithAny(".mp4", ".mkv", ".webm", ".avi", ".mov", ".3gp", ".ts", ".m4v")
    val isAudio: Boolean get() = mimeType.startsWith("audio/") || name.endsWithAny(".mp3", ".wav", ".flac", ".ogg", ".m4a", ".aac", ".wma")
    val isImage: Boolean get() = mimeType.startsWith("image/") || name.endsWithAny(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".svg")
    val isText: Boolean get() = mimeType.startsWith("text/") || name.endsWithAny(".txt", ".json", ".xml", ".log", ".md", ".html", ".csv", ".yaml", ".yml", ".ini")

    private fun String.endsWithAny(vararg extensions: String): Boolean {
        val lower = this.lowercase()
        return extensions.any { lower.endsWith(it) }
    }
}

data class BreadcrumbSegment(
    val title: String,
    val shareId: String? = null,
    val path: String = ""
)

object ExplorerClient {

    fun resolveUrl(serverUrl: String, relativeOrAbsolute: String): String {
        val cleanBase = serverUrl.trimEnd('/')
        return if (relativeOrAbsolute.startsWith("http://") || relativeOrAbsolute.startsWith("https://")) {
            relativeOrAbsolute
        } else if (relativeOrAbsolute.startsWith("/")) {
            "$cleanBase$relativeOrAbsolute"
        } else {
            "$cleanBase/$relativeOrAbsolute"
        }
    }

    suspend fun checkServerStatus(serverUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL(resolveUrl(serverUrl, "/api/status"))
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 3000
                requestMethod = "GET"
            }
            conn.connect()
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (_: Exception) {
            false
        }
    }

    suspend fun fetchShares(serverUrl: String): Result<List<RemoteShare>> = withContext(Dispatchers.IO) {
        try {
            val url = URL(resolveUrl(serverUrl, "/api/shares"))
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 7000
                requestMethod = "GET"
            }
            conn.connect()
            if (conn.responseCode !in 200..299) {
                return@withContext Result.failure(Exception("HTTP error ${conn.responseCode}"))
            }

            val text = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            conn.disconnect()

            val array = JSONArray(text)
            val shares = mutableListOf<RemoteShare>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                shares.add(
                    RemoteShare(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        isAccessible = obj.optBoolean("isAccessible", true)
                    )
                )
            }
            Result.success(shares)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun browseDirectory(
        serverUrl: String,
        shareId: String,
        path: String
    ): Result<List<RemoteItem>> = withContext(Dispatchers.IO) {
        try {
            val encodedPath = URLEncoder.encode(path, StandardCharsets.UTF_8.name())
            val targetUrl = resolveUrl(serverUrl, "/api/shares/$shareId/browse?path=$encodedPath")
            val url = URL(targetUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 10000
                requestMethod = "GET"
            }
            conn.connect()
            if (conn.responseCode !in 200..299) {
                return@withContext Result.failure(Exception("HTTP error ${conn.responseCode}"))
            }

            val text = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            conn.disconnect()

            val array = JSONArray(text)
            val items = mutableListOf<RemoteItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val isDir = obj.getBoolean("isDirectory")
                val streamRel = obj.optString("streamUrl", "")
                val downloadRel = obj.optString("downloadUrl", "")

                items.add(
                    RemoteItem(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        isDirectory = isDir,
                        size = obj.optLong("size", 0L),
                        lastModified = obj.optLong("lastModified", 0L),
                        mimeType = obj.optString("mimeType", if (isDir) "directory" else "application/octet-stream"),
                        relativePath = obj.optString("relativePath", ""),
                        streamUrl = if (streamRel.isNotEmpty()) resolveUrl(serverUrl, streamRel) else "",
                        downloadUrl = if (downloadRel.isNotEmpty()) resolveUrl(serverUrl, downloadRel) else ""
                    )
                )
            }
            Result.success(items)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchTextContent(urlStr: String, maxLength: Int = 100000): Result<String> = withContext(Dispatchers.IO) {
        try {
            val url = URL(urlStr)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 8000
                requestMethod = "GET"
            }
            conn.connect()
            if (conn.responseCode !in 200..299) {
                return@withContext Result.failure(Exception("HTTP error ${conn.responseCode}"))
            }

            val reader = BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8))
            val charBuffer = CharArray(2048)
            val sb = java.lang.StringBuilder()
            var read: Int
            while (reader.read(charBuffer).also { read = it } != -1) {
                sb.append(charBuffer, 0, read)
                if (sb.length >= maxLength) {
                    sb.append("\n\n... [Content Truncated at 100 KB] ...")
                    break
                }
            }
            reader.close()
            conn.disconnect()
            Result.success(sb.toString())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
