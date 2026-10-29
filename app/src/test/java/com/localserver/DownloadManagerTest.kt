package com.localserver

import com.localserver.core.DownloadManager
import com.localserver.core.DownloadStatus
import com.localserver.core.DownloadTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadManagerTest {

    @Test
    fun testDownloadTaskProgressPercent() {
        val task0 = DownloadTask(
            fileName = "sample.mp4",
            fileUrl = "http://localhost:8080/sample.mp4",
            destinationFolder = "/downloads",
            totalBytes = 1000L,
            downloadedBytes = 0L
        )
        assertEquals(0, task0.progressPercent)

        val task50 = task0.copy(downloadedBytes = 500L)
        assertEquals(50, task50.progressPercent)

        val task100 = task0.copy(downloadedBytes = 1000L)
        assertEquals(100, task100.progressPercent)

        val taskOver = task0.copy(downloadedBytes = 1500L)
        assertEquals(100, taskOver.progressPercent)

        val taskZeroTotal = task0.copy(totalBytes = 0L, downloadedBytes = 500L)
        assertEquals(0, taskZeroTotal.progressPercent)
    }

    @Test
    fun testDownloadTaskIsFinished() {
        val task = DownloadTask(
            fileName = "sample.mp4",
            fileUrl = "http://localhost:8080/sample.mp4",
            destinationFolder = "/downloads",
            totalBytes = 1000L
        )

        assertFalse(task.copy(status = DownloadStatus.QUEUED).isFinished)
        assertFalse(task.copy(status = DownloadStatus.DOWNLOADING).isFinished)
        assertFalse(task.copy(status = DownloadStatus.PAUSED).isFinished)

        assertTrue(task.copy(status = DownloadStatus.COMPLETED).isFinished)
        assertTrue(task.copy(status = DownloadStatus.FAILED).isFinished)
        assertTrue(task.copy(status = DownloadStatus.CANCELLED).isFinished)
    }

    @Test
    fun testFormatBytes() {
        assertEquals("0 B", DownloadManager.formatBytes(0L))
        assertEquals("0 B", DownloadManager.formatBytes(-50L))
        assertEquals("500.0 B", DownloadManager.formatBytes(500L))
        assertEquals("1.0 KB", DownloadManager.formatBytes(1024L))
        assertEquals("2.5 MB", DownloadManager.formatBytes((2.5 * 1024 * 1024).toLong()))
        assertEquals("1.0 GB", DownloadManager.formatBytes(1024L * 1024L * 1024L))
    }

    @Test
    fun testDownloadTaskImmutabilityAndCopy() {
        val task1 = DownloadTask(
            fileName = "sample.mp4",
            fileUrl = "http://localhost:8080/sample.mp4",
            destinationFolder = "/downloads",
            totalBytes = 10000L,
            downloadedBytes = 1000L,
            status = DownloadStatus.DOWNLOADING,
            speedBytesPerSec = 500000L
        )

        val task2 = task1.copy(downloadedBytes = 2000L, speedBytesPerSec = 600000L)

        // Ensure task1 was not mutated
        assertEquals(1000L, task1.downloadedBytes)
        assertEquals(500000L, task1.speedBytesPerSec)

        // Ensure task2 has updated values
        assertEquals(2000L, task2.downloadedBytes)
        assertEquals(600000L, task2.speedBytesPerSec)

        // Ensure equals correctly identifies them as distinct for StateFlow
        assertNotEquals(task1, task2)
    }
}
