package com.localserver.core

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class RequestRouter(
    private val config: ServerConfig,
    private val networkManager: NetworkManager
) {

    fun setupRoutes(application: Application) {
        application.routing {
            get("/") {
                val addresses = networkManager.availableAddresses.value
                val html = generateTestHtmlPage(addresses)
                call.respondText(html, ContentType.Text.Html)
            }

            get("/sample.txt") {
                val sampleContent = buildString {
                    appendLine("==================================================")
                    appendLine("Android Local Media Server - Sample Test File")
                    appendLine("Milestone 1 Networking Verification")
                    appendLine("==================================================")
                    appendLine("Timestamp: ${System.currentTimeMillis()}")
                    appendLine("Configured Port: ${config.port}")
                    appendLine("This proves that another device can connect and download")
                    appendLine("files from the Android device over local Wi-Fi / Hotspot.")
                    appendLine("==================================================")
                }.toByteArray(StandardCharsets.UTF_8)

                StreamingHandler.streamContent(
                    call = call,
                    contentType = ContentType.Text.Plain.withCharset(Charsets.UTF_8),
                    totalSize = sampleContent.size.toLong(),
                    fileName = "sample.txt"
                ) {
                    ByteArrayInputStream(sampleContent)
                }
            }

            get("/api/status") {
                val addresses = networkManager.availableAddresses.value
                val addrsJson = addresses.joinToString(prefix = "[", postfix = "]") { addr ->
                    """{"interface":"${addr.interfaceName}","displayName":"${addr.displayName}","ip":"${addr.ipAddress}","url":"${addr.toUrl(config.port)}","type":"${addr.type}"}"""
                }
                val json = """
                    {
                      "status": "running",
                      "port": ${config.port},
                      "bindAddress": "${config.bindAddress}",
                      "timestamp": ${System.currentTimeMillis()},
                      "addresses": $addrsJson
                    }
                """.trimIndent()
                call.respondText(json, ContentType.Application.Json)
            }
        }
    }

    private fun generateTestHtmlPage(addresses: List<NetworkAddress>): String {
        val addressListHtml = if (addresses.isEmpty()) {
            "<li class='text-muted'>No active network interface detected</li>"
        } else {
            addresses.joinToString("\n") { addr ->
                val url = addr.toUrl(config.port)
                "<li><strong>${addr.displayName}:</strong> <a href='$url' target='_blank'>$url</a> <span class='badge'>${addr.type}</span></li>"
            }
        }

        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Local Media Server - Milestone 1 Proof of Concept</title>
                <style>
                    :root {
                        --bg: #090d16;
                        --card: #131d31;
                        --border: #223252;
                        --accent: #38bdf8;
                        --text: #f8fafc;
                        --text-muted: #94a3b8;
                        --success: #10b981;
                    }
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;
                        background: var(--bg);
                        color: var(--text);
                        padding: 24px 16px;
                        line-height: 1.5;
                        display: flex;
                        justify-content: center;
                    }
                    .container {
                        max-width: 680px;
                        width: 100%;
                    }
                    .card {
                        background: var(--card);
                        border: 1px solid var(--border);
                        border-radius: 12px;
                        padding: 24px;
                        margin-bottom: 20px;
                        box-shadow: 0 4px 20px rgba(0,0,0,0.3);
                    }
                    h1 { font-size: 1.5rem; margin-bottom: 8px; color: var(--accent); }
                    p { color: var(--text-muted); margin-bottom: 16px; }
                    .status-pill {
                        display: inline-flex;
                        align-items: center;
                        gap: 8px;
                        background: rgba(16, 185, 129, 0.15);
                        color: var(--success);
                        border: 1px solid rgba(16, 185, 129, 0.3);
                        padding: 6px 14px;
                        border-radius: 9999px;
                        font-weight: 600;
                        font-size: 0.875rem;
                        margin-bottom: 20px;
                    }
                    .status-dot {
                        width: 10px;
                        height: 10px;
                        background: var(--success);
                        border-radius: 50%;
                        display: inline-block;
                        box-shadow: 0 0 10px var(--success);
                    }
                    ul { list-style: none; margin: 12px 0; }
                    li {
                        padding: 10px 12px;
                        background: rgba(255,255,255,0.03);
                        margin-bottom: 8px;
                        border-radius: 8px;
                        border: 1px solid var(--border);
                        word-break: break-all;
                    }
                    a { color: var(--accent); text-decoration: none; font-weight: 500; }
                    a:hover { text-decoration: underline; }
                    .badge {
                        background: rgba(56, 189, 248, 0.15);
                        color: var(--accent);
                        padding: 2px 8px;
                        border-radius: 4px;
                        font-size: 0.75rem;
                        margin-left: 8px;
                    }
                    .btn {
                        display: inline-block;
                        background: var(--accent);
                        color: #0f172a;
                        padding: 10px 18px;
                        border-radius: 8px;
                        text-decoration: none;
                        font-weight: 600;
                        margin-right: 12px;
                        margin-top: 8px;
                    }
                    .btn:hover { background: #7dd3fc; }
                    .security-box {
                        border-left: 4px solid #f59e0b;
                        background: rgba(245, 158, 11, 0.1);
                        padding: 12px 16px;
                        border-radius: 0 8px 8px 0;
                        color: #fbbf24;
                        font-size: 0.875rem;
                        margin-top: 16px;
                    }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="card">
                        <div class="status-pill">
                            <span class="status-dot"></span>
                            HTTP Server Active on Port ${config.port}
                        </div>
                        <h1>Android Local Media Server</h1>
                        <p>Milestone 1: Networking &amp; Connectivity Proof of Concept</p>

                        <h3 style="margin-top: 16px; font-size: 1rem; color: var(--text);">Reachable Network Addresses:</h3>
                        <ul>
                            $addressListHtml
                        </ul>

                        <h3 style="margin-top: 20px; font-size: 1rem; color: var(--text);">Test Resources:</h3>
                        <div style="margin-top: 8px;">
                            <a class="btn" href="/sample.txt">Download sample.txt</a>
                            <a class="btn" href="/api/status" style="background: transparent; color: var(--accent); border: 1px solid var(--accent);">View JSON /api/status</a>
                        </div>

                        <div class="security-box">
                            🔒 <strong>Local Network Notice:</strong> Files and endpoints served by this device are accessible to any device connected to the same Wi-Fi or Hotspot network.
                        </div>
                    </div>
                </div>
            </body>
            </html>
        """.trimIndent()
    }
}
