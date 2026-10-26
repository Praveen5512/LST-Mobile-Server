package com.localserver.core

import android.util.Log
import io.ktor.http.HttpHeaders
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.partialcontent.PartialContent
import java.net.BindException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class ServerState {
    object Stopped : ServerState()
    object Starting : ServerState()
    data class Running(val config: ServerConfig, val addresses: List<NetworkAddress>) : ServerState()
    data class Error(val message: String, val cause: Throwable? = null) : ServerState()
}

class ServerController(
    private val context: android.content.Context,
    private val networkManager: NetworkManager,
    private val shareRegistry: ShareRegistry,
    private val fileRepository: FileRepository
) {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var engine: ApplicationEngine? = null

    private val _serverState = MutableStateFlow<ServerState>(ServerState.Stopped)
    val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    @Synchronized
    fun start(config: ServerConfig) {
        if (_serverState.value is ServerState.Running || _serverState.value is ServerState.Starting) {
            Log.w(TAG, "Server is already starting or running")
            return
        }

        _serverState.value = ServerState.Starting

        scope.launch {
            try {
                Log.i(TAG, "Starting Ktor CIO server on ${config.bindAddress}:${config.port}")

                val router = RequestRouter(context, config, networkManager, shareRegistry, fileRepository)

                val newEngine = embeddedServer(
                    factory = CIO,
                    port = config.port,
                    host = config.bindAddress
                ) {
                    install(CORS) {
                        anyHost()
                        allowHeader(HttpHeaders.ContentType)
                        allowHeader(HttpHeaders.Range)
                        allowHeader(HttpHeaders.AcceptRanges)
                    }
                    install(PartialContent)
                    router.setupRoutes(this)
                }

                newEngine.start(wait = false)
                engine = newEngine

                val addresses = networkManager.refresh()
                _serverState.value = ServerState.Running(config, addresses)
                Log.i(TAG, "Server successfully started on port ${config.port}")
            } catch (e: BindException) {
                val errorMsg = "Port ${config.port} is already in use by another application. Please choose another port."
                Log.e(TAG, errorMsg, e)
                _serverState.value = ServerState.Error(errorMsg, e)
            } catch (e: Exception) {
                val errorMsg = "Failed to start server: ${e.localizedMessage ?: e.javaClass.simpleName}"
                Log.e(TAG, errorMsg, e)
                _serverState.value = ServerState.Error(errorMsg, e)
            }
        }
    }

    @Synchronized
    fun stop() {
        val currentEngine = engine
        engine = null
        if (currentEngine != null) {
            scope.launch {
                try {
                    Log.i(TAG, "Stopping Ktor CIO server...")
                    withContext(Dispatchers.IO) {
                        currentEngine.stop(gracePeriodMillis = 500, timeoutMillis = 1500)
                    }
                    Log.i(TAG, "Server stopped cleanly.")
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping server engine: ${e.message}")
                } finally {
                    _serverState.value = ServerState.Stopped
                }
            }
        } else {
            _serverState.value = ServerState.Stopped
        }
    }

    companion object {
        private const val TAG = "ServerController"
    }
}
