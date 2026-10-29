package com.localserver.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.localserver.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class DownloadTask(
    val id: String = UUID.randomUUID().toString(),
    val fileName: String,
    val fileUrl: String,
    val destinationFolder: String,
    val totalBytes: Long,
    val downloadedBytes: Long = 0L,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val speedBytesPerSec: Long = 0L,
    val error: String? = null,
    val keepScreenOn: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val localFilePath: String? = null
) {
    val progressPercent: Int
        get() = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else 0

    val isFinished: Boolean
        get() = status == DownloadStatus.COMPLETED || status == DownloadStatus.FAILED || status == DownloadStatus.CANCELLED
}

class DownloadManager private constructor(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val activeConnections = ConcurrentHashMap<String, HttpURLConnection>()
    private val pauseFlags = ConcurrentHashMap<String, Boolean>()

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private var wakeLock: PowerManager.WakeLock? = null
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    companion object {
        private const val TAG = "DownloadManager"
        private const val PREFS_NAME = "lms_downloads"
        private const val KEY_COMPLETED = "completed_downloads"
        private const val DOWNLOAD_CHANNEL_ID = "lms_download_channel"
        private const val NOTIFICATION_ID_BASE = 5000

        @Volatile
        private var instance: DownloadManager? = null

        fun getInstance(context: Context): DownloadManager {
            return instance ?: synchronized(this) {
                instance ?: DownloadManager(context.applicationContext).also {
                    instance = it
                    it.loadPersistedTasks()
                }
            }
        }

        fun getDefaultDownloadDir(): File {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val appDownloads = File(downloadsDir, "LSTMobileServer")
            if (!appDownloads.exists()) {
                appDownloads.mkdirs()
            }
            return appDownloads
        }

        fun formatBytes(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
            val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
            return String.format(java.util.Locale.US, "%.1f %s", value, units[digitGroups])
        }
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                DOWNLOAD_CHANNEL_ID,
                "File Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows download progress and completion notifications"
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun updateTask(taskId: String, transform: (DownloadTask) -> DownloadTask) {
        _tasks.update { list ->
            list.map { if (it.id == taskId) transform(it) else it }
        }
    }

    fun enqueueDownload(
        fileName: String,
        fileUrl: String,
        totalBytes: Long,
        destinationFolder: String = getDefaultDownloadDir().absolutePath,
        keepScreenOn: Boolean = false
    ): DownloadTask {
        val task = DownloadTask(
            fileName = fileName,
            fileUrl = fileUrl,
            destinationFolder = destinationFolder,
            totalBytes = totalBytes,
            keepScreenOn = keepScreenOn
        )

        pauseFlags.remove(task.id)
        _tasks.update { current ->
            current.filter { it.id != task.id } + task
        }
        startDownloadJob(task)
        return task
    }

    fun pauseDownload(taskId: String) {
        pauseFlags[taskId] = true
        activeConnections[taskId]?.let { conn ->
            runCatching { conn.disconnect() }
        }
        activeJobs[taskId]?.cancel()
        activeConnections.remove(taskId)
        activeJobs.remove(taskId)

        updateTask(taskId) { it.copy(status = DownloadStatus.PAUSED, speedBytesPerSec = 0L) }
        updateNotification(getTask(taskId))
        checkWakeLock()
    }

    fun resumeDownload(taskId: String) {
        val task = getTask(taskId) ?: return
        if (task.status == DownloadStatus.PAUSED || task.status == DownloadStatus.FAILED) {
            pauseFlags.remove(taskId)
            updateTask(taskId) { it.copy(status = DownloadStatus.QUEUED, error = null, speedBytesPerSec = 0L) }
            val updatedTask = getTask(taskId) ?: task
            startDownloadJob(updatedTask)
        }
    }

    fun cancelDownload(taskId: String) {
        pauseFlags[taskId] = true
        activeConnections[taskId]?.let { conn ->
            runCatching { conn.disconnect() }
        }
        activeJobs[taskId]?.cancel()
        activeJobs.remove(taskId)
        activeConnections.remove(taskId)

        val task = getTask(taskId)
        if (task != null) {
            task.localFilePath?.let { path ->
                runCatching { File(path).delete() }
            }
            cancelNotification(task)
        }

        // Cleanly remove from list so UI dismisses it immediately
        _tasks.update { list -> list.filter { it.id != taskId } }
        checkWakeLock()
    }

    fun removeCompletedTask(taskId: String) {
        val task = getTask(taskId)
        if (task != null) {
            cancelNotification(task)
        }
        _tasks.update { list -> list.filter { it.id != taskId } }
        persistCompletedTasks()
    }

    fun clearAllCompletedTasks() {
        val completed = _tasks.value.filter { it.status == DownloadStatus.COMPLETED }
        completed.forEach { cancelNotification(it) }
        _tasks.update { list -> list.filter { it.status != DownloadStatus.COMPLETED } }
        persistCompletedTasks()
    }

    fun getTask(taskId: String): DownloadTask? {
        return _tasks.value.find { it.id == taskId }
    }

    private fun startDownloadJob(initialTask: DownloadTask) {
        checkWakeLock()

        val taskId = initialTask.id
        val job = scope.launch {
            var connection: HttpURLConnection? = null
            var inputStream: InputStream? = null
            var outputStream: FileOutputStream? = null

            try {
                updateTask(taskId) { it.copy(status = DownloadStatus.DOWNLOADING) }

                val currentTask = getTask(taskId) ?: initialTask
                val destDir = File(currentTask.destinationFolder)
                if (!destDir.exists()) destDir.mkdirs()

                val targetFile = File(destDir, currentTask.fileName)
                val targetFilePath = targetFile.absolutePath
                updateTask(taskId) { it.copy(localFilePath = targetFilePath) }

                var existingLength = if (targetFile.exists()) targetFile.length() else 0L

                val url = URL(currentTask.fileUrl)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                }
                activeConnections[taskId] = connection

                // Range header request if partial file already exists
                if (existingLength > 0 && (currentTask.totalBytes <= 0 || existingLength < currentTask.totalBytes)) {
                    connection.setRequestProperty("Range", "bytes=$existingLength-")
                } else {
                    existingLength = 0L
                }

                connection.connect()

                val responseCode = connection.responseCode
                val isPartial = responseCode == HttpURLConnection.HTTP_PARTIAL
                val isOk = responseCode == HttpURLConnection.HTTP_OK

                if (!isOk && !isPartial) {
                    throw IllegalStateException("Server returned HTTP $responseCode")
                }

                // If server sent full 200 OK instead of 206 Partial, reset from byte 0
                val actualStartingOffset: Long
                if (isPartial) {
                    outputStream = FileOutputStream(targetFile, true)
                    actualStartingOffset = existingLength
                } else {
                    outputStream = FileOutputStream(targetFile, false)
                    actualStartingOffset = 0L
                }

                val contentLength = connection.contentLengthLong
                val finalTotalBytes = if (isPartial && currentTask.totalBytes > 0) {
                    currentTask.totalBytes
                } else if (contentLength > 0) {
                    if (isPartial) contentLength + existingLength else contentLength
                } else {
                    currentTask.totalBytes
                }

                updateTask(taskId) { it.copy(
                    downloadedBytes = actualStartingOffset,
                    totalBytes = finalTotalBytes
                ) }

                inputStream = connection.inputStream
                val buffer = ByteArray(32768) // 32 KB chunk
                var bytesRead: Int
                var currentDownloaded = actualStartingOffset
                var lastSpeedCalcTime = System.currentTimeMillis()
                var bytesReadSinceLastCalc = 0L
                var lastUiUpdateTime = System.currentTimeMillis()

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    if (pauseFlags[taskId] == true) {
                        break
                    }

                    outputStream.write(buffer, 0, bytesRead)
                    currentDownloaded += bytesRead
                    bytesReadSinceLastCalc += bytesRead

                    val now = System.currentTimeMillis()
                    val timeDelta = now - lastSpeedCalcTime

                    // Recalculate speed smoothly every 300ms
                    var currentSpeed = getTask(taskId)?.speedBytesPerSec ?: 0L
                    if (timeDelta >= 300) {
                        currentSpeed = (bytesReadSinceLastCalc * 1000) / timeDelta
                        bytesReadSinceLastCalc = 0L
                        lastSpeedCalcTime = now
                    }

                    // Update UI StateFlow and notification every 250ms
                    if (now - lastUiUpdateTime >= 250) {
                        lastUiUpdateTime = now
                        updateTask(taskId) { it.copy(
                            downloadedBytes = currentDownloaded,
                            speedBytesPerSec = currentSpeed,
                            totalBytes = finalTotalBytes
                        ) }
                        getTask(taskId)?.let { updateNotification(it) }
                    }
                }

                outputStream.flush()

                if (pauseFlags[taskId] == true) {
                    Log.d(TAG, "Download paused or cancelled for ${currentTask.fileName}")
                } else {
                    // Download complete!
                    val completedSize = if (finalTotalBytes > 0) finalTotalBytes else currentDownloaded
                    updateTask(taskId) { it.copy(
                        downloadedBytes = completedSize,
                        totalBytes = completedSize,
                        speedBytesPerSec = 0L,
                        status = DownloadStatus.COMPLETED,
                        completedAt = System.currentTimeMillis()
                    ) }
                    persistCompletedTasks()
                    getTask(taskId)?.let { showCompletionNotification(it) }
                }

            } catch (e: Exception) {
                if (pauseFlags[taskId] != true) {
                    Log.e(TAG, "Download failed for ${initialTask.fileName}", e)
                    updateTask(taskId) { it.copy(
                        status = DownloadStatus.FAILED,
                        error = e.message ?: "Download failed",
                        speedBytesPerSec = 0L
                    ) }
                    getTask(taskId)?.let { updateNotification(it) }
                }
            } finally {
                runCatching { outputStream?.close() }
                runCatching { inputStream?.close() }
                runCatching { connection?.disconnect() }
                activeConnections.remove(taskId)
                activeJobs.remove(taskId)
                checkWakeLock()
            }
        }

        activeJobs[taskId] = job
    }

    private fun checkWakeLock() {
        val anyActive = _tasks.value.any { it.status == DownloadStatus.DOWNLOADING }
        if (anyActive) {
            if (wakeLock == null) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = powerManager?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "LSTMobileServer:DownloadWakeLock"
                )?.apply {
                    setReferenceCounted(false)
                    acquire(2 * 60 * 60 * 1000L) // 2 hours safety limit
                }
            }
        } else {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        }
    }

    private fun updateNotification(task: DownloadTask?) {
        if (task == null || notificationManager == null) return
        val notifId = NOTIFICATION_ID_BASE + (task.id.hashCode() and 0x7FFF)

        if (task.status == DownloadStatus.DOWNLOADING) {
            val speedText = formatBytes(task.speedBytesPerSec) + "/s"
            val progressText = "${formatBytes(task.downloadedBytes)} / ${formatBytes(task.totalBytes)} ($speedText)"

            val notification = NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
                .setContentTitle("Downloading ${task.fileName}")
                .setContentText(progressText)
                .setSmallIcon(R.drawable.ic_download)
                .setProgress(100, task.progressPercent, task.totalBytes <= 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()

            notificationManager.notify(notifId, notification)
        } else if (task.status == DownloadStatus.PAUSED) {
            val notification = NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
                .setContentTitle("Paused: ${task.fileName}")
                .setContentText("${formatBytes(task.downloadedBytes)} downloaded")
                .setSmallIcon(R.drawable.ic_download)
                .setOngoing(false)
                .build()

            notificationManager.notify(notifId, notification)
        }
    }

    private fun showCompletionNotification(task: DownloadTask) {
        if (notificationManager == null) return
        val notifId = NOTIFICATION_ID_BASE + (task.id.hashCode() and 0x7FFF)

        val intent = createOpenFileIntent(task)
        val pendingIntent = intent?.let {
            PendingIntent.getActivity(
                context,
                notifId,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            )
        }

        val notification = NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
            .setContentTitle("Download Complete")
            .setContentText("${task.fileName} (${formatBytes(task.downloadedBytes)})")
            .setSmallIcon(R.drawable.ic_check_circle)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(notifId, notification)
    }

    private fun cancelNotification(task: DownloadTask) {
        val notifId = NOTIFICATION_ID_BASE + (task.id.hashCode() and 0x7FFF)
        notificationManager?.cancel(notifId)
    }

    fun createOpenFileIntent(task: DownloadTask): Intent? {
        val path = task.localFilePath ?: return null
        val file = File(path)
        if (!file.exists()) return null

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val ext = file.extension.lowercase()
        val mimeType = when (ext) {
            "mp4", "mkv", "webm", "avi", "mov", "3gp", "ts", "m4v" -> "video/*"
            "mp3", "wav", "flac", "ogg", "m4a", "aac", "wma" -> "audio/*"
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg" -> "image/*"
            "pdf" -> "application/pdf"
            "txt", "log", "md", "json", "xml" -> "text/plain"
            "apk" -> "application/vnd.android.package-archive"
            "zip", "tar", "gz", "7z", "rar" -> "application/zip"
            else -> if (ext.isNotEmpty()) MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*" else "*/*"
        }

        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun persistCompletedTasks() {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val completed = _tasks.value.filter { it.status == DownloadStatus.COMPLETED }
            val array = JSONArray()

            for (task in completed) {
                array.put(
                    JSONObject().apply {
                        put("id", task.id)
                        put("fileName", task.fileName)
                        put("fileUrl", task.fileUrl)
                        put("destinationFolder", task.destinationFolder)
                        put("totalBytes", task.totalBytes)
                        put("downloadedBytes", task.downloadedBytes)
                        put("createdAt", task.createdAt)
                        put("completedAt", task.completedAt ?: 0L)
                        put("localFilePath", task.localFilePath ?: "")
                    }
                )
            }

            prefs.edit().putString(KEY_COMPLETED, array.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist downloads: ${e.message}")
        }
    }

    private fun loadPersistedTasks() {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonStr = prefs.getString(KEY_COMPLETED, null) ?: return
            val array = JSONArray(jsonStr)
            val loaded = mutableListOf<DownloadTask>()

            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val path = obj.optString("localFilePath")
                val file = if (path.isNotEmpty()) File(path) else null

                // Verify file still exists on device
                if (file != null && file.exists()) {
                    loaded.add(
                        DownloadTask(
                            id = obj.getString("id"),
                            fileName = obj.getString("fileName"),
                            fileUrl = obj.getString("fileUrl"),
                            destinationFolder = obj.getString("destinationFolder"),
                            totalBytes = obj.getLong("totalBytes"),
                            downloadedBytes = obj.getLong("downloadedBytes"),
                            status = DownloadStatus.COMPLETED,
                            createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                            completedAt = obj.optLong("completedAt", System.currentTimeMillis()),
                            localFilePath = path
                        )
                    )
                }
            }

            _tasks.value = loaded
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load persisted downloads: ${e.message}")
        }
    }
}
