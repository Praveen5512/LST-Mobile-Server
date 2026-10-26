package com.localserver

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.localserver.databinding.ActivityClientViewerBinding

class ClientViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityClientViewerBinding
    private var serverUrl: String = ""
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    companion object {
        const val EXTRA_SERVER_URL = "extra_server_url"
        const val EXTRA_SERVER_NAME = "extra_server_name"

        fun start(context: Context, serverUrl: String, serverName: String? = null) {
            val intent = Intent(context, ClientViewerActivity::class.java).apply {
                putExtra(EXTRA_SERVER_URL, serverUrl)
                putExtra(EXTRA_SERVER_NAME, serverName)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityClientViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        serverUrl = intent.getStringExtra(EXTRA_SERVER_URL) ?: "http://localhost:8080"
        val serverName = intent.getStringExtra(EXTRA_SERVER_NAME) ?: "Local Media Client"

        setupToolbar(serverName)
        setupWebView()
        setupBackHandler()

        binding.btnRetry.setOnClickListener {
            binding.layoutError.visibility = View.GONE
            binding.webView.visibility = View.VISIBLE
            binding.webView.loadUrl(serverUrl)
        }

        binding.webView.loadUrl(serverUrl)
    }

    private fun setupToolbar(name: String) {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
            title = name
            subtitle = serverUrl
        }
        binding.toolbar.setNavigationOnClickListener {
            handleBackPress()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.webView
        val settings = webView.settings

        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            displayZoomControls = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                binding.progressBar.visibility = View.VISIBLE
                binding.layoutError.visibility = View.GONE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.progressBar.visibility = View.GONE
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                // Only show full error screen if the main frame failed
                if (request?.isForMainFrame == true) {
                    binding.progressBar.visibility = View.GONE
                    binding.webView.visibility = View.GONE
                    binding.layoutError.visibility = View.VISIBLE
                    binding.tvErrorMessage.text = "Failed to connect to $serverUrl.\nVerify that the server is online and reachable."
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progressBar.progress = newProgress
                if (newProgress >= 100) {
                    binding.progressBar.visibility = View.GONE
                } else {
                    binding.progressBar.visibility = View.VISIBLE
                }
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }

                customView = view
                customViewCallback = callback

                binding.appBarLayout.visibility = View.GONE
                binding.mainContentFrame.visibility = View.GONE
                binding.fullscreenContainer.apply {
                    visibility = View.VISIBLE
                    addView(
                        view,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                }

                setFullscreen(true)
            }

            override fun onHideCustomView() {
                if (customView == null) return

                binding.fullscreenContainer.apply {
                    removeAllViews()
                    visibility = View.GONE
                }
                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null

                binding.appBarLayout.visibility = View.VISIBLE
                binding.mainContentFrame.visibility = View.VISIBLE

                setFullscreen(false)
            }
        }
    }

    private fun setFullscreen(enabled: Boolean) {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        if (enabled) {
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
            windowInsetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackPress()
            }
        })
    }

    private fun handleBackPress() {
        if (customView != null) {
            binding.webView.webChromeClient?.onHideCustomView()
        } else if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            finish()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_client_viewer, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                handleBackPress()
                true
            }
            R.id.action_refresh -> {
                binding.layoutError.visibility = View.GONE
                binding.webView.visibility = View.VISIBLE
                binding.webView.reload()
                true
            }
            R.id.action_external_browser -> {
                try {
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(serverUrl))
                    startActivity(browserIntent)
                } catch (e: Exception) {
                    // Ignore or show toast
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
    }

    override fun onPause() {
        binding.webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        binding.webView.destroy()
        super.onDestroy()
    }
}
