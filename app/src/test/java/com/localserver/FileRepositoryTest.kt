package com.localserver
 
import com.localserver.core.FileRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileRepositoryTest {

    @Test
    fun testSanitizePathNormal() {
        assertEquals("", FileRepository.sanitizeRelativePath(null))
        assertEquals("", FileRepository.sanitizeRelativePath(""))
        assertEquals("", FileRepository.sanitizeRelativePath("/"))
        assertEquals("folder/subfolder/file.mp4", FileRepository.sanitizeRelativePath("folder/subfolder/file.mp4"))
        assertEquals("folder/subfolder/file.mp4", FileRepository.sanitizeRelativePath("/folder/subfolder/file.mp4/"))
        assertEquals("folder/subfolder/file.mp4", FileRepository.sanitizeRelativePath("///folder///subfolder///file.mp4///"))
    }

    @Test
    fun testSanitizePathRejectsTraversal() {
        assertNull(FileRepository.sanitizeRelativePath("../secret.txt"))
        assertNull(FileRepository.sanitizeRelativePath("folder/../../etc/passwd"))
        assertNull(FileRepository.sanitizeRelativePath(".."))
        assertNull(FileRepository.sanitizeRelativePath("folder/.."))
        assertNull(FileRepository.sanitizeRelativePath("folder/./../file.txt"))
        assertNull(FileRepository.sanitizeRelativePath("%2e%2e/etc/passwd"))
        assertNull(FileRepository.sanitizeRelativePath("folder/%2e%2e/secret"))
    }

    @Test
    fun testSanitizePathRejectsIllegalCharacters() {
        assertNull(FileRepository.sanitizeRelativePath("folder\\subfolder"))
        assertNull(FileRepository.sanitizeRelativePath("folder\u0000secret"))
        assertNull(FileRepository.sanitizeRelativePath("folder%00secret"))
    }
}

