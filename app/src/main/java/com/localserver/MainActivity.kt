package com.localserver

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.localserver.core.DiscoveredServer
import com.localserver.core.NetworkAddress
import com.localserver.core.QrCodeGenerator
import com.localserver.core.ServerConfig
import com.localserver.core.ServerService
import com.localserver.core.ServerState
import com.localserver.core.ShareRoot
import com.localserver.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (!isGranted) {
                Toast.makeText(
                    this,
                    "Notification permission is recommended to keep server running in background.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private val openDocumentTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                val newShare = ServerService.shareRegistry.addShare(uri)
                if (newShare != null) {
                    Toast.makeText(this, "Shared folder: ${newShare.name}", Toast.LENGTH_SHORT).show()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ServerService.initSingletons(this)

        requestNotificationPermissionIfNeeded()
        setupListeners()
        observeState()
    }

    override fun onResume() {
        super.onResume()
        checkBatteryOptimization()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            val isIgnoring = powerManager?.isIgnoringBatteryOptimizations(packageName) == true
            binding.cardBatteryOptimization.visibility = if (isIgnoring) View.GONE else View.VISIBLE
        } else {
            binding.cardBatteryOptimization.visibility = View.GONE
        }
    }

    private fun setupListeners() {
        binding.btnStart.setOnClickListener {
            val portText = binding.etPort.text?.toString()?.trim() ?: ""
            val port = portText.toIntOrNull()

            if (port == null || !ServerConfig.isValidPort(port)) {
                binding.tilPort.error = "Port must be between ${ServerConfig.MIN_PORT} and ${ServerConfig.MAX_PORT}"
                return@setOnClickListener
            }
            binding.tilPort.error = null

            ServerService.startServer(this, port)
        }

        binding.btnStop.setOnClickListener {
            ServerService.stopServer(this)
        }

        binding.btnAddFolder.setOnClickListener {
            openDocumentTreeLauncher.launch(null)
        }

        binding.btnDisableBatteryOptimization.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(fallbackIntent)
                }
            }
        }

        binding.btnShowQrCode.setOnClickListener {
            val state = ServerService.serviceState.value
            if (state is ServerState.Running) {
                val primaryUrl = state.addresses.firstOrNull()?.toUrl(state.config.port)
                    ?: "http://localhost:${state.config.port}"
                showQrCodeDialog(primaryUrl)
            }
        }

        binding.btnCopyUrl.setOnClickListener {
            val state = ServerService.serviceState.value
            if (state is ServerState.Running) {
                val primaryUrl = state.addresses.firstOrNull()?.toUrl(state.config.port)
                    ?: "http://localhost:${state.config.port}"
                copyToClipboard(primaryUrl)
            }
        }

        binding.btnOpenInAppViewer.setOnClickListener {
            val state = ServerService.serviceState.value
            if (state is ServerState.Running) {
                val primaryUrl = state.addresses.firstOrNull()?.toUrl(state.config.port)
                    ?: "http://localhost:${state.config.port}"
                ClientViewerActivity.start(this, primaryUrl, "Local Server (This Device)")
            }
        }

        binding.btnOpenBrowser.setOnClickListener {
            val state = ServerService.serviceState.value
            if (state is ServerState.Running) {
                val primaryUrl = state.addresses.firstOrNull()?.toUrl(state.config.port)
                    ?: "http://localhost:${state.config.port}"
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(primaryUrl))
                startActivity(browserIntent)
            }
        }

        binding.btnScanServers.setOnClickListener {
            if (ServerService.nsdDiscoveryManager.isDiscovering.value) {
                ServerService.nsdDiscoveryManager.stopDiscovery()
            } else {
                ServerService.nsdDiscoveryManager.startDiscovery()
            }
        }

        binding.btnConnectManual.setOnClickListener {
            val input = binding.etManualUrl.text?.toString()?.trim() ?: ""
            if (input.isEmpty() || input == "http://" || input == "https://") {
                binding.tilManualUrl.error = "Please enter a valid IP:port (e.g. 192.168.1.10:8080)"
                return@setOnClickListener
            }
            binding.tilManualUrl.error = null

            val url = if (input.startsWith("http://", ignoreCase = true) || input.startsWith("https://", ignoreCase = true)) {
                input
            } else {
                "http://$input"
            }

            ClientViewerActivity.start(this, url, "Remote Media Server")
        }
    }

    private fun showQrCodeDialog(url: String) {
        val bitmap = QrCodeGenerator.generateQrBitmap(url, 600, 600)
        if (bitmap == null) {
            Toast.makeText(this, "Failed to generate QR code", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(40, 30, 40, 20)
        }

        val qrImageView = android.widget.ImageView(this).apply {
            setImageBitmap(bitmap)
            layoutParams = LinearLayout.LayoutParams(500, 500)
        }

        val urlText = TextView(this).apply {
            text = url
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 0)
        }

        dialogLayout.addView(qrImageView)
        dialogLayout.addView(urlText)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Scan to Connect")
            .setView(dialogLayout)
            .setPositiveButton("Close", null)
            .setNeutralButton("Copy URL") { _, _ ->
                copyToClipboard(url)
            }
            .show()
    }

    private fun copyToClipboard(url: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Media Server URL", url)
        clipboard?.setPrimaryClip(clip)
        Toast.makeText(this, "Copied URL: $url", Toast.LENGTH_SHORT).show()
    }

    private fun observeState() {
        lifecycleScope.launch {
            ServerService.serviceState.collect { state ->
                renderState(state)
            }
        }

        lifecycleScope.launch {
            ServerService.networkManager.availableAddresses.collect { addresses ->
                val state = ServerService.serviceState.value
                val port = if (state is ServerState.Running) {
                    state.config.port
                } else {
                    binding.etPort.text?.toString()?.toIntOrNull() ?: ServerConfig.DEFAULT_PORT
                }
                updateAddressDisplay(addresses, port)
            }
        }

        lifecycleScope.launch {
            ServerService.shareRegistry.shares.collect { shares ->
                renderSharesList(shares)
            }
        }

        lifecycleScope.launch {
            ServerService.nsdDiscoveryManager.isDiscovering.collect { isScanning ->
                binding.progressScanning.visibility = if (isScanning) View.VISIBLE else View.GONE
                binding.btnScanServers.text = if (isScanning) "⏹ Stop Scan" else "🔍 Scan Network"
                binding.tvScanStatus.text = if (isScanning) "Scanning local network (mDNS)..." else "Scan stopped"
            }
        }

        lifecycleScope.launch {
            ServerService.nsdDiscoveryManager.discoveredServers.collect { servers ->
                renderDiscoveredServers(servers)
            }
        }
    }

    private fun renderSharesList(shares: List<ShareRoot>) {
        binding.layoutSharesContainer.removeAllViews()

        if (shares.isEmpty()) {
            binding.layoutSharesContainer.addView(binding.tvEmptyShares)
            return
        }

        for (share in shares) {
            val itemLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val iconAndName = TextView(this).apply {
                val prefix = if (share.isAccessible) "📁 " else "⚠️ (Inaccessible) "
                text = "$prefix${share.name}"
                setTextColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        if (share.isAccessible) R.color.text_primary else R.color.status_red
                    )
                )
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }

            val removeBtn = MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.borderlessButtonStyle
            ).apply {
                text = "✕"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_red))
                minWidth = 0
                minimumWidth = 0
                setPadding(16, 0, 16, 0)
                setOnClickListener {
                    ServerService.shareRegistry.removeShare(share.id)
                }
            }

            itemLayout.addView(iconAndName)
            itemLayout.addView(removeBtn)
            binding.layoutSharesContainer.addView(itemLayout)
        }
    }

    private fun renderDiscoveredServers(servers: List<DiscoveredServer>) {
        binding.layoutDiscoveredServers.removeAllViews()

        if (servers.isEmpty()) {
            binding.layoutDiscoveredServers.addView(binding.tvNoDiscoveredServers)
            return
        }

        for (server in servers) {
            val card = com.google.android.material.card.MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 8, 0, 8)
                }
                setCardBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.surface))
                strokeColor = ContextCompat.getColor(this@MainActivity, R.color.surface_border)
                strokeWidth = 1
                radius = 16f
            }

            val cardInner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 20, 24, 20)
            }

            val nameView = TextView(this).apply {
                text = "🖥️ ${server.serviceName}"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                textSize = 14f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }

            val urlView = TextView(this).apply {
                text = server.url
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(0, 4, 0, 12)
            }

            val buttonRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val openClientBtn = MaterialButton(this).apply {
                text = "📱 Open Client"
                textSize = 12f
                isAllCaps = false
                setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.background))
                cornerRadius = 12
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = 12
                }
                setOnClickListener {
                    ClientViewerActivity.start(this@MainActivity, server.url, server.serviceName)
                }
            }

            val openBrowserBtn = MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.borderlessButtonStyle
            ).apply {
                text = "🌐 Browser"
                textSize = 12f
                isAllCaps = false
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(server.url)))
                }
            }

            buttonRow.addView(openClientBtn)
            buttonRow.addView(openBrowserBtn)

            cardInner.addView(nameView)
            cardInner.addView(urlView)
            cardInner.addView(buttonRow)
            card.addView(cardInner)

            binding.layoutDiscoveredServers.addView(card)
        }
    }

    private fun renderState(state: ServerState) {
        when (state) {
            is ServerState.Stopped -> {
                binding.tvStatus.text = getString(R.string.server_status_stopped)
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_red))
                binding.tvStatusDetail.text = "Server stopped. Configure port and tap Start."
                binding.btnStart.isEnabled = true
                binding.btnStop.isEnabled = false
                binding.etPort.isEnabled = true
                binding.btnOpenInAppViewer.isEnabled = false
                binding.btnOpenBrowser.isEnabled = false
                binding.btnShowQrCode.isEnabled = false
                binding.btnCopyUrl.isEnabled = false
            }
            is ServerState.Starting -> {
                binding.tvStatus.text = getString(R.string.server_status_starting)
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.accent))
                binding.tvStatusDetail.text = "Binding to network interfaces..."
                binding.btnStart.isEnabled = false
                binding.btnStop.isEnabled = false
                binding.etPort.isEnabled = false
                binding.btnOpenInAppViewer.isEnabled = false
                binding.btnOpenBrowser.isEnabled = false
                binding.btnShowQrCode.isEnabled = false
                binding.btnCopyUrl.isEnabled = false
            }
            is ServerState.Running -> {
                binding.tvStatus.text = getString(R.string.server_status_running)
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green))
                binding.tvStatusDetail.text = "Listening on 0.0.0.0:${state.config.port} (All interfaces)"
                binding.btnStart.isEnabled = false
                binding.btnStop.isEnabled = true
                binding.etPort.isEnabled = false
                binding.btnOpenInAppViewer.isEnabled = true
                binding.btnOpenBrowser.isEnabled = true
                binding.btnShowQrCode.isEnabled = true
                binding.btnCopyUrl.isEnabled = true
                updateAddressDisplay(state.addresses, state.config.port)
            }
            is ServerState.Error -> {
                binding.tvStatus.text = getString(R.string.server_status_error)
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_red))
                binding.tvStatusDetail.text = state.message
                binding.btnStart.isEnabled = true
                binding.btnStop.isEnabled = false
                binding.etPort.isEnabled = true
                binding.btnOpenInAppViewer.isEnabled = false
                binding.btnOpenBrowser.isEnabled = false
                binding.btnShowQrCode.isEnabled = false
                binding.btnCopyUrl.isEnabled = false

                // If port in use, auto-suggest next port
                if (state.message.contains("already in use", ignoreCase = true)) {
                    val current = binding.etPort.text?.toString()?.toIntOrNull() ?: ServerConfig.DEFAULT_PORT
                    binding.etPort.setText((current + 1).toString())
                }
            }
        }
    }

    private fun updateAddressDisplay(addresses: List<NetworkAddress>, port: Int) {
        if (addresses.isEmpty()) {
            binding.tvUrls.text = getString(R.string.no_network_found)
        } else {
            val formatted = addresses.joinToString("\n\n") { addr ->
                "• ${addr.displayName}\n  ${addr.toUrl(port)}"
            }
            binding.tvUrls.text = formatted
        }
    }
}
