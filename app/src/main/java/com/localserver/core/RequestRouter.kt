package com.localserver.core

import android.content.Context
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

class RequestRouter(
    private val context: Context,
    private val config: ServerConfig,
    private val networkManager: NetworkManager,
    private val shareRegistry: ShareRegistry,
    private val fileRepository: FileRepository
) {

    fun setupRoutes(application: Application) {
        application.routing {
            // Web Client Interface
            get("/") {
                val html = try {
                    this@RequestRouter.context.assets.open("web/index.html").bufferedReader(Charsets.UTF_8).use { reader ->
                        reader.readText()
                    }
                } catch (e: Exception) {
                    "<html><body><h1>Local Media Server</h1><p>Error loading client assets: ${e.message}</p></body></html>"
                }
                call.respondText(html, ContentType.Text.Html.withCharset(Charsets.UTF_8))
            }

            // Milestone 1 legacy sample verification
            get("/sample.txt") {
                val sampleContent = buildString {
                    appendLine("==================================================")
                    appendLine("Android Local Media Server - Sample Test File")
                    appendLine("Milestone 1 & 2 Verification")
                    appendLine("==================================================")
                    appendLine("Timestamp: ${System.currentTimeMillis()}")
                    appendLine("Configured Port: ${config.port}")
                    appendLine("Streaming, file download, and HTTP range seeking active.")
                    appendLine("==================================================")
                }.toByteArray(StandardCharsets.UTF_8)

                StreamingHandler.streamFile(
                    call = call,
                    contentType = ContentType.Text.Plain.withCharset(Charsets.UTF_8),
                    totalSize = sampleContent.size.toLong(),
                    fileName = "sample.txt",
                    asAttachment = false,
                    pfdProvider = null
                ) {
                    ByteArrayInputStream(sampleContent)
                }
            }

            // Status API
            get("/api/status") {
                val addresses = networkManager.availableAddresses.value
                val addrsArray = JSONArray()
                for (addr in addresses) {
                    addrsArray.put(
                        JSONObject().apply {
                            put("interface", addr.interfaceName)
                            put("displayName", addr.displayName)
                            put("ip", addr.ipAddress)
                            put("url", addr.toUrl(config.port))
                            put("type", addr.type.name)
                        }
                    )
                }

                val json = JSONObject().apply {
                    put("status", "running")
                    put("port", config.port)
                    put("bindAddress", config.bindAddress)
                    put("timestamp", System.currentTimeMillis())
                    put("addresses", addrsArray)
                    put("sharesCount", shareRegistry.shares.value.size)
                }
                call.respondText(json.toString(), ContentType.Application.Json)
            }

            // Shares API
            route("/api/shares") {
                get {
                    val shares = shareRegistry.shares.value
                    val array = JSONArray()
                    for (share in shares) {
                        array.put(
                            JSONObject().apply {
                                put("id", share.id)
                                put("name", share.name)
                                put("createdAt", share.createdAt)
                            }
                        )
                    }
                    call.respondText(array.toString(), ContentType.Application.Json)
                }

                route("/{shareId}") {
                    // Directory listing
                    get("/browse") {
                        val shareId = call.parameters["shareId"] ?: run {
                            call.respond(HttpStatusCode.BadRequest, "Missing shareId")
                            return@get
                        }
                        val path = call.request.queryParameters["path"] ?: ""
                        val items = fileRepository.listDirectory(shareId, path)
                        if (items == null) {
                            call.respond(HttpStatusCode.NotFound, "Directory not found or access denied")
                            return@get
                        }

                        val array = JSONArray()
                        for (item in items) {
                            array.put(
                                JSONObject().apply {
                                    put("id", item.id)
                                    put("name", item.name)
                                    put("isDirectory", item.isDirectory)
                                    put("size", item.size)
                                    put("lastModified", item.lastModified)
                                    put("mimeType", item.mimeType)
                                    put("relativePath", item.relativePath)
                                    if (!item.isDirectory) {
                                        put("streamUrl", "/api/shares/$shareId/files/${item.id}/stream")
                                        put("downloadUrl", "/api/shares/$shareId/files/${item.id}/download")
                                    }
                                }
                            )
                        }
                        call.respondText(array.toString(), ContentType.Application.Json)
                    }

                    // File streaming and downloading
                    route("/files/{fileId}") {
                        get("/stream") {
                            handleFileServe(call, asAttachment = false)
                        }
                        head("/stream") {
                            handleFileServe(call, asAttachment = false)
                        }
                        get("/download") {
                            handleFileServe(call, asAttachment = true)
                        }
                        head("/download") {
                            handleFileServe(call, asAttachment = true)
                        }
                    }
                }
            }
        }
    }

    private suspend fun handleFileServe(call: io.ktor.server.application.ApplicationCall, asAttachment: Boolean) {
        val shareId = call.parameters["shareId"] ?: run {
            call.respond(HttpStatusCode.BadRequest, "Missing shareId")
            return
        }
        val fileId = call.parameters["fileId"] ?: run {
            call.respond(HttpStatusCode.BadRequest, "Missing fileId")
            return
        }

        val resolved = fileRepository.resolveFile(shareId, fileId)
        if (resolved == null) {
            call.respond(HttpStatusCode.NotFound, "File not found or access denied")
            return
        }

        val parsedContentType = try {
            ContentType.parse(resolved.mimeType)
        } catch (_: Exception) {
            ContentType.Application.OctetStream
        }

        StreamingHandler.streamFile(
            call = call,
            contentType = parsedContentType,
            totalSize = resolved.size,
            fileName = resolved.name,
            asAttachment = asAttachment,
            pfdProvider = { fileRepository.openFileDescriptor(resolved.uri) },
            streamProvider = { fileRepository.openInputStream(resolved.uri) }
        )
    }
}
