package com.localserver

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.localserver.core.DownloadManager
import com.localserver.core.DownloadStatus
import com.localserver.core.ServerService
import com.localserver.core.ServerState
import com.localserver.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var explorerFragment: ExplorerFragment? = null
    private var pendingConnectUrl: String? = null
    private var pendingConnectName: String? = null

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize singletons so discovery and server work across all screens
        ServerService.initSingletons(this)

        requestNotificationPermissionIfNeeded()
        setupViewPagerAndNavigation()
        setupHeaderStatusBadge()
        observeServerState()
        observeDownloadsKeepScreenOn()
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

    private fun setupViewPagerAndNavigation() {
        binding.viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = 3

            override fun createFragment(position: Int): Fragment {
                return when (position) {
                    0 -> ExplorerFragment().also {
                        explorerFragment = it
                        // Apply pending connection if queued
                        val url = pendingConnectUrl
                        val name = pendingConnectName
                        if (url != null && name != null) {
                            it.connectToServer(url, name)
                            pendingConnectUrl = null
                            pendingConnectName = null
                        }
                    }
                    1 -> DiscoverFragment()
                    2 -> SettingsFragment()
                    else -> ExplorerFragment()
                }
            }
        }

        // Disable user swipe to prevent conflicts with breadcrumbs or player seekbars
        binding.viewPager.isUserInputEnabled = false

        // Bottom Navigation item selection
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_explorer -> {
                    binding.viewPager.setCurrentItem(0, false)
                    binding.tvCurrentScreenSubtitle.text = getString(R.string.explorer_title)
                    true
                }
                R.id.nav_discover -> {
                    binding.viewPager.setCurrentItem(1, false)
                    binding.tvCurrentScreenSubtitle.text = getString(R.string.discover_title)
                    true
                }
                R.id.nav_settings -> {
                    binding.viewPager.setCurrentItem(2, false)
                    binding.tvCurrentScreenSubtitle.text = getString(R.string.settings_title)
                    true
                }
                else -> false
            }
        }

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                when (position) {
                    0 -> {
                        binding.bottomNav.selectedItemId = R.id.nav_explorer
                        binding.tvCurrentScreenSubtitle.text = getString(R.string.explorer_title)
                    }
                    1 -> {
                        binding.bottomNav.selectedItemId = R.id.nav_discover
                        binding.tvCurrentScreenSubtitle.text = getString(R.string.discover_title)
                    }
                    2 -> {
                        binding.bottomNav.selectedItemId = R.id.nav_settings
                        binding.tvCurrentScreenSubtitle.text = getString(R.string.settings_title)
                    }
                }
            }
        })
    }

    private fun setupHeaderStatusBadge() {
        // Tapping the status pill in the top header quickly navigates to Settings
        binding.cardHeaderServerStatus.setOnClickListener {
            binding.viewPager.setCurrentItem(2, false)
        }
    }

    private fun observeServerState() {
        lifecycleScope.launch {
            ServerService.serviceState.collect { state ->
                renderHeaderStatus(state)
            }
        }
    }

    private fun renderHeaderStatus(state: ServerState) {
        when (state) {
            is ServerState.Running -> {
                binding.viewHeaderStatusDot.backgroundTintList =
                    ContextCompat.getColorStateList(this, R.color.status_green)
                binding.tvHeaderStatusText.text = "Server :${state.config.port}"
                binding.tvHeaderStatusText.setTextColor(ContextCompat.getColor(this, R.color.status_green))
            }
            is ServerState.Starting -> {
                binding.viewHeaderStatusDot.backgroundTintList =
                    ContextCompat.getColorStateList(this, R.color.status_amber)
                binding.tvHeaderStatusText.text = "Starting..."
                binding.tvHeaderStatusText.setTextColor(ContextCompat.getColor(this, R.color.status_amber))
            }
            is ServerState.Stopped -> {
                binding.viewHeaderStatusDot.backgroundTintList =
                    ContextCompat.getColorStateList(this, R.color.status_red)
                binding.tvHeaderStatusText.text = "Server Stopped"
                binding.tvHeaderStatusText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            }
            is ServerState.Error -> {
                binding.viewHeaderStatusDot.backgroundTintList =
                    ContextCompat.getColorStateList(this, R.color.status_red)
                binding.tvHeaderStatusText.text = "Server Error"
                binding.tvHeaderStatusText.setTextColor(ContextCompat.getColor(this, R.color.status_red))
            }
        }
    }

    private fun observeDownloadsKeepScreenOn() {
        lifecycleScope.launch {
            DownloadManager.getInstance(this@MainActivity).tasks.collect { tasks ->
                val keepScreen = tasks.any { it.status == DownloadStatus.DOWNLOADING && it.keepScreenOn }
                if (keepScreen) {
                    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
    }

    /**
     * Navigates to the Explorer screen and instructs it to connect to the given server.
     * Called when tapping "Open Client" on a discovered or manual server in DiscoverFragment.
     */
    fun navigateToExplorer(serverUrl: String, serverName: String) {
        binding.viewPager.setCurrentItem(0, false)
        val explorer = explorerFragment
        if (explorer != null && explorer.isAdded) {
            explorer.connectToServer(serverUrl, serverName)
        } else {
            pendingConnectUrl = serverUrl
            pendingConnectName = serverName
        }
    }
}
