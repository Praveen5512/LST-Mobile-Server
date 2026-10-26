package com.localserver.core

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.InputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class FileEntry(
    val id: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val mimeType: String,
    val relativePath: String
)

data class ResolvedFile(
    val documentFile: DocumentFile,
    val name: String,
    val size: Long,
    val mimeType: String,
    val uri: Uri
)

class FileRepository(
    private val context: Context,
    private val shareRegistry: ShareRegistry
) {

    /**
     * Sanitizes and normalizes a relative path within a share root.
     * Prevents directory traversal: rejects '..', null bytes, and leading/trailing separators.
     */
    fun sanitizeRelativePath(rawPath: String?): String? {
        if (rawPath.isNullOrBlank() || rawPath == "/" || rawPath == ".") {
            return ""
        }
        val decoded = try {
            URLDecoder.decode(rawPath, StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            return null
        }

        // Security check: reject null bytes, backslashes, or parent traversal
        if (decoded.contains('\u0000') || decoded.contains('\\')) {
            return null
        }

        val segments = decoded.split('/').filter { it.isNotEmpty() }
        for (segment in segments) {
            if (segment == ".." || segment == ".") {
                return null // Attempt to escape directory hierarchy
            }
        }
        return segments.joinToString("/")
    }

    /**
     * Lists directory contents at the given relative sub-path within a share.
     */
    fun listDirectory(shareId: String, relativePath: String): List<FileEntry>? {
        val share = shareRegistry.getShare(shareId) ?: return null
        val cleanPath = sanitizeRelativePath(relativePath) ?: return null

        val rootDoc = DocumentFile.fromTreeUri(context, share.uri) ?: return null
        val targetDir = navigateToSubdirectory(rootDoc, cleanPath) ?: return null

        if (!targetDir.isDirectory) return null

        val entries = mutableListOf<FileEntry>()
        val children = try {
            targetDir.listFiles()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to list files for share $shareId (permissions may have been revoked): ${e.message}")
            return null
        }

        for (child in children) {
            val name = child.name ?: continue
            val isDir = child.isDirectory
            val childRelPath = if (cleanPath.isEmpty()) name else "$cleanPath/$name"
            val id = URLEncoder.encode(childRelPath, StandardCharsets.UTF_8.name())
            val mime = if (isDir) "inode/directory" else MimeTypeResolver.resolve(name, child.type)
            val size = if (isDir) 0L else child.length()
            val modified = child.lastModified()

            entries.add(
                FileEntry(
                    id = id,
                    name = name,
                    isDirectory = isDir,
                    size = size,
                    lastModified = modified,
                    mimeType = mime,
                    relativePath = childRelPath
                )
            )
        }

        // Directories first, then alphabetical
        return entries.sortedWith(
            compareBy<FileEntry> { !it.isDirectory }
                .thenBy { it.name.lowercase() }
        )
    }

    /**
     * Resolves a file for streaming or downloading.
     */
    fun resolveFile(shareId: String, fileId: String): ResolvedFile? {
        val share = shareRegistry.getShare(shareId) ?: return null
        val relativePath = sanitizeRelativePath(fileId) ?: return null
        if (relativePath.isEmpty()) return null

        val rootDoc = DocumentFile.fromTreeUri(context, share.uri) ?: return null
        val doc = navigateToFile(rootDoc, relativePath) ?: return null

        if (!doc.isFile || !doc.canRead()) return null

        val name = doc.name ?: "file"
        val mime = MimeTypeResolver.resolve(name, doc.type)
        val size = doc.length()

        return ResolvedFile(
            documentFile = doc,
            name = name,
            size = size,
            mimeType = mime,
            uri = doc.uri
        )
    }

    fun openInputStream(uri: Uri): InputStream? {
        return try {
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening InputStream for URI: $uri", e)
            null
        }
    }

    fun openFileDescriptor(uri: Uri): ParcelFileDescriptor? {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (e: Exception) {
            Log.w(TAG, "Error opening ParcelFileDescriptor for URI: $uri: ${e.message}")
            null
        }
    }

    private fun navigateToSubdirectory(root: DocumentFile, cleanPath: String): DocumentFile? {
        if (cleanPath.isEmpty()) return root
        val parts = cleanPath.split('/')
        var current: DocumentFile = root
        for (part in parts) {
            val next = current.findFile(part) ?: return null
            if (!next.isDirectory) return null
            current = next
        }
        return current
    }

    private fun navigateToFile(root: DocumentFile, cleanPath: String): DocumentFile? {
        val parts = cleanPath.split('/')
        if (parts.isEmpty()) return null
        var current: DocumentFile = root
        for (i in 0 until parts.size - 1) {
            val next = current.findFile(parts[i]) ?: return null
            if (!next.isDirectory) return null
            current = next
        }
        return current.findFile(parts.last())
    }

    companion object {
        private const val TAG = "FileRepository"
    }
}
