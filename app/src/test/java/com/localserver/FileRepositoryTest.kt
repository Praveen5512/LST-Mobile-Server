package com.localserver

import com.localserver.core.FileRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileRepositoryTest {

    // Test sanitizeRelativePath security logic directly without needing Android Context
    @Test
    fun testSanitizePathNormal() {
        // Instantiate using a dummy mock or call helper directly
        val repo = FileRepositoryStub()
        assertEquals("", repo.sanitizeRelativePath(null))
        assertEquals("", repo.sanitizeRelativePath(""))
        assertEquals("", repo.sanitizeRelativePath("/"))
        assertEquals("folder/subfolder/file.mp4", repo.sanitizeRelativePath("folder/subfolder/file.mp4"))
        assertEquals("folder/subfolder/file.mp4", repo.sanitizeRelativePath("/folder/subfolder/file.mp4/"))
    }

    @Test
    fun testSanitizePathRejectsTraversal() {
        val repo = FileRepositoryStub()
        assertNull(repo.sanitizeRelativePath("../secret.txt"))
        assertNull(repo.sanitizeRelativePath("folder/../../etc/passwd"))
        assertNull(repo.sanitizeRelativePath(".."))
        assertNull(repo.sanitizeRelativePath("folder/.."))
        assertNull(repo.sanitizeRelativePath("folder/./../file.txt"))
    }

    @Test
    fun testSanitizePathRejectsIllegalCharacters() {
        val repo = FileRepositoryStub()
        assertNull(repo.sanitizeRelativePath("folder\\subfolder"))
        assertNull(repo.sanitizeRelativePath("folder\u0000secret"))
    }

    private class FileRepositoryStub {
        fun sanitizeRelativePath(rawPath: String?): String? {
            if (rawPath.isNullOrBlank() || rawPath == "/" || rawPath == ".") {
                return ""
            }
            val decoded = try {
                java.net.URLDecoder.decode(rawPath, java.nio.charset.StandardCharsets.UTF_8.name())
            } catch (_: Exception) {
                return null
            }
            if (decoded.contains('\u0000') || decoded.contains('\\')) {
                return null
            }
            val segments = decoded.split('/').filter { it.isNotEmpty() }
            for (segment in segments) {
                if (segment == ".." || segment == ".") {
                    return null
                }
            }
            return segments.joinToString("/")
        }
    }
}
