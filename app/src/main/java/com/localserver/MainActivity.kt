package com.localserver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.localserver.core.NetworkAddress
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

        binding.btnOpenBrowser.setOnClickListener {
            val state = ServerService.serviceState.value
            if (state is ServerState.Running) {
                val primaryUrl = state.addresses.firstOrNull()?.toUrl(state.config.port)
                    ?: "http://localhost:${state.config.port}"
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(primaryUrl))
                startActivity(browserIntent)
            }
        }
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
                text = "📁 ${share.name}"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
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

    private fun renderState(state: ServerState) {
        when (state) {
            is ServerState.Stopped -> {
                binding.tvStatus.text = getString(R.string.server_status_stopped)
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_red))
                binding.tvStatusDetail.text = "Server stopped. Configure port and tap Start."
                binding.btnStart.isEnabled = true
                binding.btnStop.isEnabled = false
                binding.etPort.isEnabled = true
                binding.btnOpenBrowser.isEnabled = false
            }
            is ServerState.Starting -> {
                binding.tvStatus.text = getString(R.string.server_status_starting)
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.accent))
                binding.tvStatusDetail.text = "Binding to network interfaces..."
                binding.btnStart.isEnabled = false
                binding.btnStop.isEnabled = false
                binding.etPort.isEnabled = false
                binding.btnOpenBrowser.isEnabled = false
            }
            is ServerState.Running -> {
                binding.tvStatus.text = getString(R.string.server_status_running)
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green))
                binding.tvStatusDetail.text = "Listening on 0.0.0.0:${state.config.port} (All interfaces)"
                binding.btnStart.isEnabled = false
                binding.btnStop.isEnabled = true
                binding.etPort.isEnabled = false
                binding.btnOpenBrowser.isEnabled = true
                updateAddressDisplay(state.addresses, state.config.port)
            }
            is ServerState.Error -> {
                binding.tvStatus.text = getString(R.string.server_status_error)
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_red))
                binding.tvStatusDetail.text = state.message
                binding.btnStart.isEnabled = true
                binding.btnStop.isEnabled = false
                binding.etPort.isEnabled = true
                binding.btnOpenBrowser.isEnabled = false
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
