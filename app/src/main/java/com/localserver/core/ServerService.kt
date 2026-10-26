package com.localserver.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.localserver.MainActivity
import com.localserver.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ServerService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "ServerService onCreate")
        ensureNotificationChannel()
        acquireLocks()
        networkManager.startMonitoring()

        serviceScope.launch {
            serverController.serverState.collect { state ->
                _serviceState.value = state
                updateNotification(state)
                if (state is ServerState.Stopped) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }

        serviceScope.launch {
            networkManager.availableAddresses.collect { addresses ->
                val currentState = serverController.serverState.value
                if (currentState is ServerState.Running) {
                    updateNotification(currentState.copy(addresses = addresses))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            val config = lastConfig ?: ServerConfig()
            Log.i(TAG, "ServerService auto-restarting after system kill on port ${config.port}")
            startForeground(NOTIFICATION_ID, buildNotification("Restoring local media server..."))
            serverController.start(config)
            return START_STICKY
        }

        when (intent.action) {
            ACTION_STOP -> {
                Log.i(TAG, "Received ACTION_STOP intent")
                serverController.stop()
            }
            ACTION_START -> {
                val port = intent.getIntExtra(EXTRA_PORT, ServerConfig.DEFAULT_PORT)
                val bind = intent.getStringExtra(EXTRA_BIND_ADDRESS) ?: ServerConfig.DEFAULT_BIND_ADDRESS
                val config = ServerConfig(port = port, bindAddress = bind)
                lastConfig = config
                Log.i(TAG, "Received ACTION_START intent for port $port on $bind")
                startForeground(NOTIFICATION_ID, buildNotification("Starting local media server..."))
                serverController.start(config)
            }
            else -> {
                Log.d(TAG, "Unhandled intent action: ${intent.action}")
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "ServerService onDestroy")
        serverController.stop()
        networkManager.stopMonitoring()
        releaseLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingActivityIntent = PendingIntent.getActivity(
            this,
            0,
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val stopIntent = Intent(this, ServerService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStopIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_stat_server)
            .setOngoing(true)
            .setContentIntent(pendingActivityIntent)
            .addAction(
                R.drawable.ic_stat_server,
                getString(R.string.notification_stop_action),
                pendingStopIntent
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(state: ServerState) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val text = when (state) {
            is ServerState.Running -> {
                val urls = state.addresses.joinToString(", ") { it.toUrl(state.config.port) }
                if (urls.isNotEmpty()) "Available at: $urls" else "Running on port ${state.config.port}"
            }
            is ServerState.Starting -> "Starting server..."
            is ServerState.Error -> "Error: ${state.message}"
            is ServerState.Stopped -> "Stopped"
        }
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "LocalMediaServer:WakeLock"
            )?.apply {
                setReferenceCounted(false)
                acquire(24 * 60 * 60 * 1000L) // 24 hours max safeguard
            }

            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifiManager?.createWifiLock(lockMode, "LocalMediaServer:WifiLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire wake/wifi locks: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            wifiLock?.let { if (it.isHeld) it.release() }
            wifiLock = null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release wake/wifi locks: ${e.message}")
        }
    }

    companion object {
        const val TAG = "ServerService"
        const val CHANNEL_ID = "local_media_server_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.localserver.action.START"
        const val ACTION_STOP = "com.localserver.action.STOP"
        const val EXTRA_PORT = "extra_port"
        const val EXTRA_BIND_ADDRESS = "extra_bind_address"

        // Singleton instances shared across app
        lateinit var networkManager: NetworkManager
        lateinit var shareRegistry: ShareRegistry
        lateinit var fileRepository: FileRepository
        lateinit var serverController: ServerController

        private val _serviceState = MutableStateFlow<ServerState>(ServerState.Stopped)
        val serviceState: StateFlow<ServerState> = _serviceState.asStateFlow()
        var lastConfig: ServerConfig? = null

        fun initSingletons(context: Context) {
            if (!::networkManager.isInitialized) {
                val appCtx = context.applicationContext
                networkManager = NetworkManager(appCtx)
                shareRegistry = ShareRegistry(appCtx)
                fileRepository = FileRepository(appCtx, shareRegistry)
                serverController = ServerController(appCtx, networkManager, shareRegistry, fileRepository)
            }
        }

        fun startServer(context: Context, port: Int, bindAddress: String = ServerConfig.DEFAULT_BIND_ADDRESS) {
            initSingletons(context)
            val intent = Intent(context, ServerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_BIND_ADDRESS, bindAddress)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopServer(context: Context) {
            val intent = Intent(context, ServerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
