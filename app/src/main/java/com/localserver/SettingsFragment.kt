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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.localserver.core.NetworkAddress
import com.localserver.core.QrCodeGenerator
import com.localserver.core.ServerConfig
import com.localserver.core.ServerService
import com.localserver.core.ServerState
import com.localserver.core.ShareRoot
import com.localserver.databinding.FragmentSettingsBinding
import com.localserver.databinding.ItemSharedFolderBinding
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val openDocumentTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                val newShare = ServerService.shareRegistry.addShare(uri)
                if (newShare != null) {
                    Toast.makeText(requireContext(), "Shared folder: ${newShare.name}", Toast.LENGTH_SHORT).show()
                }
            }
        }

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                Toast.makeText(requireContext(), "Notification permission granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Notification permission is recommended for background server operation", Toast.LENGTH_LONG).show()
            }
            updateDiagnosticsInfo()
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initSavedConfig()
        setupListeners()
        observeServerServiceState()
        observeShares()
        observeNetworkAddresses()
        updateDiagnosticsInfo()
    }

    override fun onResume() {
        super.onResume()
        checkBatteryOptimization()
        updateDiagnosticsInfo()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun initSavedConfig() {
        val savedName = ServerConfig.getSavedServerName(requireContext())
        val savedPort = ServerConfig.getSavedPort(requireContext())
        val isExternalAudio = ServerConfig.isExternalAudioPlayerEnabled(requireContext())

        binding.etServerName.setText(savedName)
        binding.etSettingsPort.setText(savedPort.toString())
        binding.switchExternalAudio.isChecked = isExternalAudio
    }

    private fun setupListeners() {
        // Media Playback External Audio Switch
        binding.switchExternalAudio.setOnCheckedChangeListener { _, isChecked ->
            ServerConfig.setExternalAudioPlayerEnabled(requireContext(), isChecked)
            val msg = if (isChecked) "Audio will open in external player" else "Audio will open in inbuilt player"
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        // Start Server
        binding.btnSettingsStart.setOnClickListener {
            val portText = binding.etSettingsPort.text?.toString()?.trim().orEmpty()
            val port = portText.toIntOrNull()

            if (port == null || !ServerConfig.isValidPort(port)) {
                binding.tilSettingsPort.error = "Port must be between ${ServerConfig.MIN_PORT} and ${ServerConfig.MAX_PORT}"
                return@setOnClickListener
            }
            binding.tilSettingsPort.error = null

            val name = binding.etServerName.text?.toString()?.trim().orEmpty().ifEmpty {
                ServerConfig.getDefaultServerName()
            }

            ServerConfig.saveServerName(requireContext(), name)
            ServerConfig.savePort(requireContext(), port)

            ServerService.startServer(requireContext(), port, serverName = name)
            Toast.makeText(requireContext(), "Starting server on port $port...", Toast.LENGTH_SHORT).show()
        }

        // Stop Server
        binding.btnSettingsStop.setOnClickListener {
            ServerService.stopServer(requireContext())
            Toast.makeText(requireContext(), "Stopping server...", Toast.LENGTH_SHORT).show()
        }

        // Add Folder
        binding.btnSettingsAddFolder.setOnClickListener {
            openDocumentTreeLauncher.launch(null)
        }

        // Battery optimization
        binding.btnSettingsDisableBatteryOpt.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${requireContext().packageName}")
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(fallbackIntent)
                }
            }
        }

        // QR Code
        binding.btnSettingsShowQr.setOnClickListener {
            val state = ServerService.serviceState.value
            val port = if (state is ServerState.Running) state.config.port else binding.etSettingsPort.text?.toString()?.toIntOrNull() ?: ServerConfig.DEFAULT_PORT
            val addresses = ServerService.networkManager.availableAddresses.value
            val primaryUrl = addresses.firstOrNull()?.toUrl(port) ?: "http://localhost:$port"
            showQrCodeDialog(primaryUrl)
        }

        // Copy URL
        binding.btnSettingsCopyUrl.setOnClickListener {
            val state = ServerService.serviceState.value
            val port = if (state is ServerState.Running) state.config.port else binding.etSettingsPort.text?.toString()?.toIntOrNull() ?: ServerConfig.DEFAULT_PORT
            val addresses = ServerService.networkManager.availableAddresses.value
            val primaryUrl = addresses.firstOrNull()?.toUrl(port) ?: "http://localhost:$port"
            copyToClipboard(primaryUrl)
        }
    }

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = requireContext().getSystemService(Context.POWER_SERVICE) as? PowerManager
            val isIgnoring = powerManager?.isIgnoringBatteryOptimizations(requireContext().packageName) == true
            binding.cardSettingsBattery.visibility = if (isIgnoring) View.GONE else View.VISIBLE
        } else {
            binding.cardSettingsBattery.visibility = View.GONE
        }
    }

    private fun observeServerServiceState() {
        viewLifecycleOwner.lifecycleScope.launch {
            ServerService.serviceState.collect { state ->
                renderState(state)
            }
        }
    }

    private fun renderState(state: ServerState) {
        if (_binding == null) return

        when (state) {
            is ServerState.Stopped -> {
                binding.tvSettingsServerStatus.text = getString(R.string.server_status_stopped)
                binding.tvSettingsServerStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_red))
                binding.tvSettingsServerStatusDetail.text = "Server stopped. Configure port and press Start Server."
                binding.btnSettingsStart.isEnabled = true
                binding.btnSettingsStop.isEnabled = false
                binding.etSettingsPort.isEnabled = true
                binding.etServerName.isEnabled = true
            }
            is ServerState.Starting -> {
                binding.tvSettingsServerStatus.text = getString(R.string.server_status_starting)
                binding.tvSettingsServerStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.accent))
                binding.tvSettingsServerStatusDetail.text = "Binding to network interfaces and registering mDNS..."
                binding.btnSettingsStart.isEnabled = false
                binding.btnSettingsStop.isEnabled = false
                binding.etSettingsPort.isEnabled = false
                binding.etServerName.isEnabled = false
            }
            is ServerState.Running -> {
                binding.tvSettingsServerStatus.text = getString(R.string.server_status_running)
                binding.tvSettingsServerStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_green))
                binding.tvSettingsServerStatusDetail.text = "Listening on 0.0.0.0:${state.config.port} • Name: ${state.config.serverName ?: "Default"}"
                binding.btnSettingsStart.isEnabled = false
                binding.btnSettingsStop.isEnabled = true
                binding.etSettingsPort.isEnabled = false
                binding.etServerName.isEnabled = false
                updateUrlsDisplay(state.addresses, state.config.port)
            }
            is ServerState.Error -> {
                binding.tvSettingsServerStatus.text = getString(R.string.server_status_error)
                binding.tvSettingsServerStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_red))
                binding.tvSettingsServerStatusDetail.text = state.message
                binding.btnSettingsStart.isEnabled = true
                binding.btnSettingsStop.isEnabled = false
                binding.etSettingsPort.isEnabled = true
                binding.etServerName.isEnabled = true

                if (state.message.contains("already in use", ignoreCase = true)) {
                    val current = binding.etSettingsPort.text?.toString()?.toIntOrNull() ?: ServerConfig.DEFAULT_PORT
                    binding.etSettingsPort.setText((current + 1).toString())
                }
            }
        }
    }

    private fun observeShares() {
        viewLifecycleOwner.lifecycleScope.launch {
            ServerService.shareRegistry.shares.collect { shares ->
                renderSharesList(shares)
                updateDiagnosticsInfo()
            }
        }
    }

    private fun renderSharesList(shares: List<ShareRoot>) {
        if (_binding == null) return

        binding.layoutSettingsSharesContainer.removeAllViews()

        if (shares.isEmpty()) {
            binding.layoutSettingsSharesContainer.addView(binding.tvSettingsEmptyShares)
            return
        }

        for (share in shares) {
            val itemBinding = ItemSharedFolderBinding.inflate(layoutInflater, binding.layoutSettingsSharesContainer, false)

            val prefix = if (share.isAccessible) "📁 " else "⚠️ (Inaccessible) "
            itemBinding.tvShareName.text = "$prefix${share.name}"
            itemBinding.tvShareName.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (share.isAccessible) R.color.text_primary else R.color.status_red
                )
            )

            itemBinding.btnRemoveShare.setOnClickListener {
                ServerService.shareRegistry.removeShare(share.id)
                Toast.makeText(requireContext(), "Removed shared folder: ${share.name}", Toast.LENGTH_SHORT).show()
            }

            binding.layoutSettingsSharesContainer.addView(itemBinding.root)
        }
    }

    private fun observeNetworkAddresses() {
        viewLifecycleOwner.lifecycleScope.launch {
            ServerService.networkManager.availableAddresses.collect { addresses ->
                val state = ServerService.serviceState.value
                val port = if (state is ServerState.Running) {
                    state.config.port
                } else {
                    binding.etSettingsPort.text?.toString()?.toIntOrNull() ?: ServerConfig.DEFAULT_PORT
                }
                updateUrlsDisplay(addresses, port)
                updateDiagnosticsInfo()
            }
        }
    }

    private fun updateUrlsDisplay(addresses: List<NetworkAddress>, port: Int) {
        if (_binding == null) return

        if (addresses.isEmpty()) {
            binding.tvSettingsUrls.text = getString(R.string.no_network_found)
        } else {
            val formatted = addresses.joinToString("\n\n") { addr ->
                "• ${addr.displayName}\n  ${addr.toUrl(port)}"
            }
            binding.tvSettingsUrls.text = formatted
        }
    }

    private fun updateDiagnosticsInfo() {
        if (_binding == null) return

        val state = ServerService.serviceState.value
        val stateText = when (state) {
            is ServerState.Running -> "Active (Listening on 0.0.0.0:${state.config.port})"
            is ServerState.Starting -> "Starting up"
            is ServerState.Error -> "Error: ${state.message}"
            is ServerState.Stopped -> "Stopped"
        }

        val sharesCount = ServerService.shareRegistry.shares.value.size
        val interfacesCount = ServerService.networkManager.availableAddresses.value.size

        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true

        val sb = StringBuilder()
        sb.appendLine("• Server Service: $stateText")
        sb.appendLine("• Configured Shares: $sharesCount folder(s)")
        sb.appendLine("• Active Network Interfaces: $interfacesCount detected")
        sb.appendLine("• Notification Permission: ${if (notifGranted) "Granted ✓" else "Not Granted ⚠️"}")
        sb.appendLine("• Storage Access: Android Scoped Storage / SAF (Active)")
        sb.append("• mDNS Discovery: Dual-Engine (Native NSD + UDP 5353 SoftAP)")

        binding.tvSettingsDiagnostics.text = sb.toString()
    }

    private fun showQrCodeDialog(url: String) {
        val bitmap = QrCodeGenerator.generateQrBitmap(url, 600, 600)
        if (bitmap == null) {
            Toast.makeText(requireContext(), "Failed to generate QR code", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(40, 30, 40, 20)
        }

        val qrImageView = android.widget.ImageView(requireContext()).apply {
            setImageBitmap(bitmap)
            layoutParams = LinearLayout.LayoutParams(500, 500)
        }

        val urlText = TextView(requireContext()).apply {
            text = url
            textSize = 14f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.accent))
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 0)
        }

        dialogLayout.addView(qrImageView)
        dialogLayout.addView(urlText)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_App_MaterialAlertDialog)
            .setTitle("Scan to Connect")
            .setView(dialogLayout)
            .setPositiveButton("Close", null)
            .setNeutralButton("Copy URL") { _, _ ->
                copyToClipboard(url)
            }
            .show()
    }

    private fun copyToClipboard(url: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Media Server URL", url)
        clipboard?.setPrimaryClip(clip)
        Toast.makeText(requireContext(), "Copied URL: $url", Toast.LENGTH_SHORT).show()
    }
}
