package com.localserver

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.localserver.core.DiscoveredServer
import com.localserver.core.ServerConnectionStatus
import com.localserver.core.ServerService
import com.localserver.databinding.FragmentDiscoverBinding
import com.localserver.databinding.ItemDiscoveredServerBinding
import kotlinx.coroutines.launch

class DiscoverFragment : Fragment() {

    private var _binding: FragmentDiscoverBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDiscoverBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupListeners()
        observeDiscoveryState()

        // Start discovery automatically when entering the screen if not already running
        if (!ServerService.nsdDiscoveryManager.isDiscovering.value) {
            ServerService.nsdDiscoveryManager.startDiscovery()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupListeners() {
        binding.btnToggleScan.setOnClickListener {
            if (ServerService.nsdDiscoveryManager.isDiscovering.value) {
                ServerService.nsdDiscoveryManager.stopDiscovery()
            } else {
                ServerService.nsdDiscoveryManager.startDiscovery()
            }
        }

        binding.btnRefreshScan.setOnClickListener {
            ServerService.nsdDiscoveryManager.refreshDiscovery()
            Toast.makeText(requireContext(), "Refreshing local network discovery...", Toast.LENGTH_SHORT).show()
        }

        binding.btnConnectManual.setOnClickListener {
            val input = binding.etManualUrl.text?.toString()?.trim().orEmpty()
            if (input.isEmpty() || input == "http://" || input == "https://" || input == "http://192.168.") {
                binding.tilManualUrl.error = "Please enter an IP:port (e.g. 192.168.1.15:8080)"
                return@setOnClickListener
            }
            binding.tilManualUrl.error = null

            val server = ServerService.nsdDiscoveryManager.addManualServer(input)
            Toast.makeText(requireContext(), "Connecting to ${server.url}...", Toast.LENGTH_SHORT).show()

            // Open in Explorer screen
            openInExplorer(server.url, server.serviceName)
        }

        binding.btnManualBrowser.setOnClickListener {
            val input = binding.etManualUrl.text?.toString()?.trim().orEmpty()
            if (input.isEmpty() || input == "http://" || input == "https://") {
                binding.tilManualUrl.error = "Please enter an IP:port"
                return@setOnClickListener
            }
            binding.tilManualUrl.error = null

            val url = if (input.startsWith("http://") || input.startsWith("https://")) input else "http://$input"
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Cannot open browser: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun observeDiscoveryState() {
        viewLifecycleOwner.lifecycleScope.launch {
            ServerService.nsdDiscoveryManager.isDiscovering.collect { isScanning ->
                if (_binding == null) return@collect
                binding.progressScanning.visibility = if (isScanning) View.VISIBLE else View.GONE
                binding.btnToggleScan.text = if (isScanning) "⏹ Stop Scan" else "🔍 Scan Network"
                binding.btnToggleScan.backgroundTintList = ContextCompat.getColorStateList(
                    requireContext(),
                    if (isScanning) R.color.status_amber else R.color.accent
                )
                binding.tvDiscoveryStatus.text = if (isScanning) {
                    "Scanning local network via mDNS & Zeroconf..."
                } else {
                    val count = ServerService.nsdDiscoveryManager.discoveredServers.value.size
                    if (count > 0) "Scan stopped • $count servers found" else "Scan stopped"
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            ServerService.nsdDiscoveryManager.discoveredServers.collect { servers ->
                renderDiscoveredServers(servers)
            }
        }
    }

    private fun renderDiscoveredServers(servers: List<DiscoveredServer>) {
        if (_binding == null) return

        binding.layoutDiscoveredServersList.removeAllViews()

        if (servers.isEmpty()) {
            binding.cardNoDiscoveredServers.visibility = View.VISIBLE
            return
        }

        binding.cardNoDiscoveredServers.visibility = View.GONE

        for (server in servers) {
            val itemBinding = ItemDiscoveredServerBinding.inflate(layoutInflater, binding.layoutDiscoveredServersList, false)

            itemBinding.tvDiscoveredName.text = "🖥️ ${server.serviceName}"
            itemBinding.tvDiscoveredUrl.text = server.url

            // Set connection status badge
            when (server.status) {
                ServerConnectionStatus.ONLINE -> {
                    itemBinding.tvDiscoveredStatus.text = "🟢 Online"
                    itemBinding.tvDiscoveredStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_green))
                    itemBinding.tvDiscoveredStatus.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.status_green_bg)
                }
                ServerConnectionStatus.CHECKING -> {
                    itemBinding.tvDiscoveredStatus.text = "🟡 Checking..."
                    itemBinding.tvDiscoveredStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_amber))
                    itemBinding.tvDiscoveredStatus.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.status_amber_bg)
                }
                ServerConnectionStatus.UNREACHABLE -> {
                    itemBinding.tvDiscoveredStatus.text = "🔴 Unreachable"
                    itemBinding.tvDiscoveredStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_red))
                    itemBinding.tvDiscoveredStatus.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.status_red_bg)
                }
            }

            // Open In-App Client action (switches bottom nav to Explorer screen!)
            itemBinding.btnOpenInApp.setOnClickListener {
                openInExplorer(server.url, server.serviceName)
            }

            // Open in external browser action
            itemBinding.btnOpenInBrowser.setOnClickListener {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(server.url))
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "Cannot launch browser: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            binding.layoutDiscoveredServersList.addView(itemBinding.root)
        }
    }

    private fun openInExplorer(url: String, name: String) {
        val mainActivity = activity as? MainActivity
        if (mainActivity != null) {
            mainActivity.navigateToExplorer(url, name)
            Toast.makeText(requireContext(), "Connected to $name", Toast.LENGTH_SHORT).show()
        } else {
            ClientViewerActivity.start(requireContext(), url, name)
        }
    }
}
