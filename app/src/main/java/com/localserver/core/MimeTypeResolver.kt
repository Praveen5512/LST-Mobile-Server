package com.localserver.core

import android.webkit.MimeTypeMap
import java.util.Locale

object MimeTypeResolver {

    private val customMimeMap = mapOf(
        "mp4" to "video/mp4",
        "mkv" to "video/x-matroska",
        "webm" to "video/webm",
        "mov" to "video/quicktime",
        "avi" to "video/x-msvideo",
        "m4v" to "video/x-m4v",
        "ts" to "video/mp2t",
        "3gp" to "video/3gpp",
        "mp3" to "audio/mpeg",
        "flac" to "audio/flac",
        "wav" to "audio/wav",
        "aac" to "audio/aac",
        "m4a" to "audio/mp4",
        "ogg" to "audio/ogg",
        "opus" to "audio/opus",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "svg" to "image/svg+xml",
        "bmp" to "image/bmp",
        "pdf" to "application/pdf",
        "txt" to "text/plain",
        "json" to "application/json",
        "zip" to "application/zip"
    )

    fun resolve(fileName: String, fallback: String? = null): String {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext.isNotEmpty()) {
            customMimeMap[ext]?.let { return it }
            val systemMime = try {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            } catch (_: Throwable) {
                null
            }
            if (!systemMime.isNullOrEmpty()) {
                return systemMime
            }
        }
        if (!fallback.isNullOrEmpty() && fallback != "application/octet-stream") {
            return fallback
        }
        return "application/octet-stream"
    }

    fun isMedia(mimeType: String): Boolean {
        return mimeType.startsWith("video/") || mimeType.startsWith("audio/") || mimeType.startsWith("image/")
    }

    fun isVideo(mimeType: String): Boolean = mimeType.startsWith("video/")
    fun isAudio(mimeType: String): Boolean = mimeType.startsWith("audio/")
    fun isImage(mimeType: String): Boolean = mimeType.startsWith("image/")
}
