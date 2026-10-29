package com.localserver

import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.webkit.MimeTypeMap
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.localserver.core.BreadcrumbSegment
import com.localserver.core.DownloadManager
import com.localserver.core.DownloadStatus
import com.localserver.core.DownloadTask
import com.localserver.core.ExplorerClient
import com.localserver.core.RemoteItem
import com.localserver.core.RemoteShare
import com.localserver.core.ServerConfig
import com.localserver.core.ServerService
import com.localserver.core.ServerState
import com.localserver.databinding.DialogAudioPlayerBinding
import com.localserver.databinding.DialogDownloadConfirmBinding
import com.localserver.databinding.DialogImagePreviewBinding
import com.localserver.databinding.DialogSwitchServerBinding
import com.localserver.databinding.DialogTextPreviewBinding
import com.localserver.databinding.DialogVideoPlayerBinding
import com.localserver.databinding.FragmentExplorerBinding
import com.localserver.databinding.ItemDownloadActiveBinding
import com.localserver.databinding.ItemDownloadCompletedBinding
import com.localserver.databinding.ItemExplorerFileBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

class ExplorerFragment : Fragment() {

    private var _binding: FragmentExplorerBinding? = null
    private val binding get() = _binding!!

    private lateinit var downloadManager: DownloadManager

    // Connected server state
    var connectedServerUrl: String = "http://localhost:8080"
        private set
    var connectedServerName: String = "Local Server (This Device)"
        private set

    // Current browsing state
    private var currentShareId: String? = null
    private var currentShareName: String? = null
    private var currentPath: String = ""
    private val breadcrumbs = mutableListOf<BreadcrumbSegment>()

    // Data lists
    private var allItems = listOf<RemoteItem>()
    private var displayedItems = listOf<RemoteItem>()
    private var allShares = listOf<RemoteShare>()
    private var isViewingRootShares = true

    // Active custom download destination picked by user
    private var customDownloadFolder: String? = null
    private var pendingDownloadFile: RemoteItem? = null
    private var pendingDownloadDialog: Dialog? = null
    private var pendingDownloadDialogBinding: DialogDownloadConfirmBinding? = null

    // View mode: Files vs Downloads
    private var isViewingDownloadsTab = false

    private val folderPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                // If user picked a folder via SAF, resolve or store
                val path = uri.path ?: uri.toString()
                customDownloadFolder = DownloadManager.getDefaultDownloadDir().absolutePath
                pendingDownloadDialogBinding?.tvDestinationPath?.text = path
                Toast.makeText(requireContext(), "Download folder selected", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentExplorerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        downloadManager = DownloadManager.getInstance(requireContext())

        setupRecyclerView()
        setupListeners()
        observeServerServiceState()
        observeDownloadsState()

        // Initialize connection to local server or saved default
        determineInitialServer()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupRecyclerView() {
        binding.recyclerFiles.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerFiles.adapter = FilesAdapter()
    }

    private fun setupListeners() {
        binding.btnSwitchServer.setOnClickListener {
            showSwitchServerDialog()
        }

        binding.btnToggleDownloads.setOnClickListener {
            toggleDownloadsView()
        }

        binding.btnRefreshFolder.setOnClickListener {
            refreshCurrentFolder()
        }

        binding.btnNavUp.setOnClickListener {
            navigateUp()
        }

        binding.btnRetryFiles.setOnClickListener {
            refreshCurrentFolder()
        }

        binding.btnConnectServer.setOnClickListener {
            showSwitchServerDialog()
        }

        binding.btnStartLocalServer.setOnClickListener {
            val port = ServerConfig.getSavedPort(requireContext())
            val name = ServerConfig.getSavedServerName(requireContext())
            ServerService.startServer(requireContext(), port, serverName = name)
            Toast.makeText(requireContext(), "Starting local server...", Toast.LENGTH_SHORT).show()
            viewLifecycleOwner.lifecycleScope.launch {
                delay(1200)
                connectToServer("http://localhost:$port", "Local Server (This Device)")
            }
        }

        binding.btnClearCompletedDownloads.setOnClickListener {
            downloadManager.clearAllCompletedTasks()
            Toast.makeText(requireContext(), "Download history cleared", Toast.LENGTH_SHORT).show()
        }

        binding.etSearchFiles.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterFiles(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun determineInitialServer() {
        val serverState = ServerService.serviceState.value
        if (serverState is ServerState.Running) {
            val port = serverState.config.port
            connectToServer("http://localhost:$port", "Local Server (This Device)")
        } else {
            // Check if any server was recently discovered
            val discovered = ServerService.nsdDiscoveryManager.discoveredServers.value
            if (discovered.isNotEmpty()) {
                val first = discovered.first()
                connectToServer(first.url, first.serviceName)
            } else {
                connectToServer("http://localhost:8080", "Local Server (This Device)")
            }
        }
    }

    fun connectToServer(url: String, name: String) {
        val cleanUrl = url.trimEnd('/')
        connectedServerUrl = cleanUrl
        connectedServerName = name

        binding.tvConnectedServerName.text = name
        binding.tvConnectedServerUrl.text = cleanUrl
        binding.tvConnectedServerStatus.text = "Connecting..."
        binding.tvConnectedServerStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.accent))
        binding.tvConnectedServerStatus.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.surface_highlight)

        // Reset breadcrumbs to root
        currentShareId = null
        currentShareName = null
        currentPath = ""
        breadcrumbs.clear()
        breadcrumbs.add(BreadcrumbSegment("🏠 Root (Shares)", null, ""))

        updateBreadcrumbUI()
        loadShares()
    }

    private fun observeServerServiceState() {
        viewLifecycleOwner.lifecycleScope.launch {
            ServerService.serviceState.collect { state ->
                if (connectedServerUrl.contains("localhost") || connectedServerUrl.contains("127.0.0.1")) {
                    if (state is ServerState.Running) {
                        val port = state.config.port
                        connectedServerUrl = "http://localhost:$port"
                        binding.tvConnectedServerUrl.text = connectedServerUrl
                        binding.tvConnectedServerStatus.text = "Connected"
                        binding.tvConnectedServerStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_green))
                        binding.tvConnectedServerStatus.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.status_green_bg)
                        if (binding.layoutNoServerConnected.visibility == View.VISIBLE) {
                            loadShares()
                        }
                    } else if (state is ServerState.Stopped) {
                        binding.tvConnectedServerStatus.text = "Offline"
                        binding.tvConnectedServerStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_red))
                        binding.tvConnectedServerStatus.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.status_red_bg)
                        if (isViewingRootShares) {
                            binding.progressLoadingFiles.visibility = View.GONE
                            binding.recyclerFiles.visibility = View.GONE
                            binding.layoutEmptyFolder.visibility = View.GONE
                            binding.layoutErrorFiles.visibility = View.GONE
                            binding.layoutNoServerConnected.visibility = View.VISIBLE
                            binding.btnStartLocalServer.visibility = View.VISIBLE
                        }
                    }
                }
            }
        }
    }

    private fun observeDownloadsState() {
        viewLifecycleOwner.lifecycleScope.launch {
            downloadManager.tasks.collect { tasks ->
                val active = tasks.filter {
                    it.status == DownloadStatus.DOWNLOADING ||
                    it.status == DownloadStatus.QUEUED ||
                    it.status == DownloadStatus.PAUSED ||
                    it.status == DownloadStatus.FAILED
                }
                val completed = tasks.filter { it.status == DownloadStatus.COMPLETED }

                // Update Downloads button badge
                if (active.isNotEmpty()) {
                    binding.btnToggleDownloads.text = "📥 Downloads (${active.size})"
                    binding.btnToggleDownloads.setTextColor(ContextCompat.getColor(requireContext(), R.color.accent))
                } else {
                    binding.btnToggleDownloads.text = if (isViewingDownloadsTab) "📁 Browse Files" else "📥 Downloads"
                    binding.btnToggleDownloads.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                }

                renderDownloadsUI(active, completed)
            }
        }
    }

    private fun toggleDownloadsView() {
        isViewingDownloadsTab = !isViewingDownloadsTab
        if (isViewingDownloadsTab) {
            binding.layoutFilesBrowser.visibility = View.GONE
            binding.layoutDownloadsView.visibility = View.VISIBLE
            binding.btnToggleDownloads.text = "📁 Browse Files"
        } else {
            binding.layoutDownloadsView.visibility = View.GONE
            binding.layoutFilesBrowser.visibility = View.VISIBLE
            val activeCount = downloadManager.tasks.value.count { !it.isFinished }
            binding.btnToggleDownloads.text = if (activeCount > 0) "📥 Downloads ($activeCount)" else "📥 Downloads"
        }
    }

    // =========================================================================
    // BROWSING & NAVIGATION
    // =========================================================================

    private fun loadShares() {
        isViewingRootShares = true
        binding.progressLoadingFiles.visibility = View.VISIBLE
        binding.layoutEmptyFolder.visibility = View.GONE
        binding.layoutErrorFiles.visibility = View.GONE
        binding.layoutNoServerConnected.visibility = View.GONE
        binding.btnNavUp.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            val isOnline = ExplorerClient.checkServerStatus(connectedServerUrl)
            if (!isOnline) {
                binding.progressLoadingFiles.visibility = View.GONE
                binding.recyclerFiles.visibility = View.GONE
                binding.layoutEmptyFolder.visibility = View.GONE
                binding.layoutErrorFiles.visibility = View.GONE
                binding.layoutNoServerConnected.visibility = View.VISIBLE

                val isLocalRunning = ServerService.serviceState.value is ServerState.Running
                binding.btnStartLocalServer.visibility = if (!isLocalRunning && (connectedServerUrl.contains("localhost") || connectedServerUrl.contains("127.0.0.1"))) View.VISIBLE else View.GONE

                binding.tvConnectedServerStatus.text = "Offline"
                binding.tvConnectedServerStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_red))
                binding.tvConnectedServerStatus.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.status_red_bg)
                return@launch
            }

            binding.layoutNoServerConnected.visibility = View.GONE
            binding.recyclerFiles.visibility = View.VISIBLE
            binding.tvConnectedServerStatus.text = "Connected"
            binding.tvConnectedServerStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_green))
            binding.tvConnectedServerStatus.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.status_green_bg)

            val result = ExplorerClient.fetchShares(connectedServerUrl)
            binding.progressLoadingFiles.visibility = View.GONE

            result.onSuccess { shares ->
                allShares = shares
                if (shares.isEmpty()) {
                    binding.layoutEmptyFolder.visibility = View.VISIBLE
                    binding.tvEmptyMessage.text = "No shared folders on this server yet.\nAdd folders in Settings to share files."
                    allItems = emptyList()
                    displayedItems = emptyList()
                    binding.recyclerFiles.adapter?.notifyDataSetChanged()
                } else {
                    binding.layoutEmptyFolder.visibility = View.GONE
                    // Convert shares into virtual directory items
                    allItems = shares.map { share ->
                        RemoteItem(
                            id = share.id,
                            name = share.name,
                            isDirectory = true,
                            size = 0L,
                            lastModified = 0L,
                            mimeType = "directory",
                            relativePath = "",
                            streamUrl = "",
                            downloadUrl = ""
                        )
                    }
                    displayedItems = allItems
                    binding.recyclerFiles.adapter?.notifyDataSetChanged()
                }
            }.onFailure { err ->
                binding.layoutErrorFiles.visibility = View.VISIBLE
                binding.tvFilesErrorMessage.text = "Failed to list shares: ${err.message}"
            }
        }
    }

    fun browseDirectory(shareId: String, shareName: String, path: String) {
        isViewingRootShares = false
        currentShareId = shareId
        currentShareName = shareName
        currentPath = path

        binding.progressLoadingFiles.visibility = View.VISIBLE
        binding.layoutEmptyFolder.visibility = View.GONE
        binding.layoutErrorFiles.visibility = View.GONE
        binding.layoutNoServerConnected.visibility = View.GONE
        binding.btnNavUp.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            val isOnline = ExplorerClient.checkServerStatus(connectedServerUrl)
            if (!isOnline) {
                binding.progressLoadingFiles.visibility = View.GONE
                binding.recyclerFiles.visibility = View.GONE
                binding.layoutEmptyFolder.visibility = View.GONE
                binding.layoutErrorFiles.visibility = View.GONE
                binding.layoutNoServerConnected.visibility = View.VISIBLE
                binding.tvConnectedServerStatus.text = "Offline"
                binding.tvConnectedServerStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_red))
                binding.tvConnectedServerStatus.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.status_red_bg)
                return@launch
            }

            val result = ExplorerClient.browseDirectory(connectedServerUrl, shareId, path)
            binding.progressLoadingFiles.visibility = View.GONE
            binding.recyclerFiles.visibility = View.VISIBLE

            result.onSuccess { items ->
                allItems = items
                displayedItems = items
                if (items.isEmpty()) {
                    binding.layoutEmptyFolder.visibility = View.VISIBLE
                    binding.tvEmptyMessage.text = getString(R.string.folder_empty)
                } else {
                    binding.layoutEmptyFolder.visibility = View.GONE
                }
                binding.recyclerFiles.adapter?.notifyDataSetChanged()
            }.onFailure { err ->
                binding.layoutErrorFiles.visibility = View.VISIBLE
                binding.tvFilesErrorMessage.text = "Failed to open folder: ${err.message}"
            }
        }
    }

    private fun refreshCurrentFolder() {
        val shareId = currentShareId
        if (shareId == null || isViewingRootShares) {
            loadShares()
        } else {
            browseDirectory(shareId, currentShareName ?: "Folder", currentPath)
        }
    }

    private fun navigateUp() {
        if (isViewingRootShares) return

        val path = currentPath
        val shareId = currentShareId ?: return

        if (path.isEmpty()) {
            // Return to root shares
            currentShareId = null
            currentShareName = null
            breadcrumbs.clear()
            breadcrumbs.add(BreadcrumbSegment("🏠 Root (Shares)", null, ""))
            updateBreadcrumbUI()
            loadShares()
        } else {
            // Go to parent directory
            val parentPath = if (path.contains("/")) path.substringBeforeLast("/") else ""
            if (breadcrumbs.size > 2) {
                breadcrumbs.removeAt(breadcrumbs.size - 1)
            }
            updateBreadcrumbUI()
            browseDirectory(shareId, currentShareName ?: "Folder", parentPath)
        }
    }

    private fun onBreadcrumbClicked(segment: BreadcrumbSegment, index: Int) {
        if (segment.shareId == null) {
            // Root
            currentShareId = null
            currentShareName = null
            currentPath = ""
            breadcrumbs.clear()
            breadcrumbs.add(BreadcrumbSegment("🏠 Root (Shares)", null, ""))
            updateBreadcrumbUI()
            loadShares()
        } else {
            // Trim breadcrumbs to this segment
            while (breadcrumbs.size > index + 1) {
                breadcrumbs.removeAt(breadcrumbs.size - 1)
            }
            updateBreadcrumbUI()
            browseDirectory(segment.shareId, segment.title, segment.path)
        }
    }

    private fun updateBreadcrumbUI() {
        binding.layoutBreadcrumbs.removeAllViews()

        for (i in breadcrumbs.indices) {
            val segment = breadcrumbs[i]

            val crumbView = TextView(requireContext()).apply {
                text = segment.title
                textSize = 13f
                setPadding(16, 8, 16, 8)
                if (i == breadcrumbs.size - 1) {
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.accent))
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                } else {
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                    setBackgroundResource(R.drawable.bg_breadcrumb)
                }
                setOnClickListener {
                    onBreadcrumbClicked(segment, i)
                }
            }

            binding.layoutBreadcrumbs.addView(crumbView)

            if (i < breadcrumbs.size - 1) {
                val sep = TextView(requireContext()).apply {
                    text = " > "
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.text_muted))
                    textSize = 12f
                }
                binding.layoutBreadcrumbs.addView(sep)
            }
        }

        binding.scrollBreadcrumbs.post {
            binding.scrollBreadcrumbs.fullScroll(View.FOCUS_RIGHT)
        }
    }

    private fun filterFiles(query: String) {
        val q = query.trim().lowercase()
        displayedItems = if (q.isEmpty()) {
            allItems
        } else {
            allItems.filter { it.name.lowercase().contains(q) }
        }
        binding.recyclerFiles.adapter?.notifyDataSetChanged()
    }

    // =========================================================================
    // RECYCLER VIEW ADAPTER
    // =========================================================================

    private inner class FilesAdapter : RecyclerView.Adapter<FilesAdapter.FileViewHolder>() {

        inner class FileViewHolder(val binding: ItemExplorerFileBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
            val b = ItemExplorerFileBinding.inflate(layoutInflater, parent, false)
            return FileViewHolder(b)
        }

        override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
            val item = displayedItems[position]
            val b = holder.binding

            b.tvItemName.text = item.name

            if (item.isDirectory) {
                b.ivItemIcon.setImageResource(R.drawable.ic_folder)
                b.tvItemDetails.text = if (isViewingRootShares) "Shared Directory Root" else "Folder"
                b.btnItemDownload.visibility = View.GONE
            } else {
                b.btnItemDownload.visibility = View.VISIBLE
                val sizeStr = DownloadManager.formatBytes(item.size)
                b.tvItemDetails.text = sizeStr

                when {
                    item.isVideo -> b.ivItemIcon.setImageResource(R.drawable.ic_file_video)
                    item.isAudio -> b.ivItemIcon.setImageResource(R.drawable.ic_file_audio)
                    item.isImage -> b.ivItemIcon.setImageResource(R.drawable.ic_file_image)
                    else -> b.ivItemIcon.setImageResource(R.drawable.ic_file_doc)
                }
            }

            // Click listener
            b.root.setOnClickListener {
                if (item.isDirectory) {
                    if (isViewingRootShares) {
                        // Opened a root share
                        breadcrumbs.add(BreadcrumbSegment(item.name, item.id, ""))
                        updateBreadcrumbUI()
                        browseDirectory(item.id, item.name, "")
                    } else {
                        // Subdirectory
                        val nextPath = if (currentPath.isEmpty()) item.name else "$currentPath/${item.name}"
                        breadcrumbs.add(BreadcrumbSegment(item.name, currentShareId, nextPath))
                        updateBreadcrumbUI()
                        browseDirectory(currentShareId ?: "", currentShareName ?: "Folder", nextPath)
                    }
                } else {
                    // Preview or stream supported media file
                    handleFileClick(item)
                }
            }

            // Download icon click
            b.btnItemDownload.setOnClickListener {
                showDownloadConfirmDialog(item)
            }
        }

        override fun getItemCount(): Int = displayedItems.size
    }

    // =========================================================================
    // IN-APP PREVIEWS & MEDIA STREAMING
    // =========================================================================

    private fun handleFileClick(item: RemoteItem) {
        if (item.isAudio) {
            val useExternal = ServerConfig.isExternalAudioPlayerEnabled(requireContext())
            if (useExternal) {
                openInExternalApp(item)
            } else {
                showAudioPlayerDialog(item)
            }
        } else {
            // No media should be opened in the inbuilt player except music.
            // Other than audio, every format should open in another available apps.
            openInExternalApp(item)
        }
    }

    private fun openInExternalApp(item: RemoteItem) {
        val streamUrl = item.streamUrl.ifEmpty {
            ExplorerClient.resolveUrl(connectedServerUrl, "/api/stream?shareId=${currentShareId}&path=${item.relativePath}")
        }
        val mimeType = resolveMimeType(item)

        try {
            val uri = Uri.parse(streamUrl)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, "Open with")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(chooser)
        } catch (e: ActivityNotFoundException) {
            try {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(streamUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(Intent.createChooser(fallbackIntent, "Open with"))
            } catch (e2: Exception) {
                Toast.makeText(
                    requireContext(),
                    "No app found to open ${item.name}. Tap download to save the file.",
                    Toast.LENGTH_LONG
                ).show()
                showDownloadConfirmDialog(item)
            }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Unable to open file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resolveMimeType(item: RemoteItem): String {
        if (item.mimeType.isNotEmpty() && item.mimeType != "application/octet-stream" && item.mimeType != "directory") {
            return item.mimeType
        }
        val ext = item.name.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty()) {
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            if (!mime.isNullOrEmpty()) return mime
        }
        return when {
            item.isVideo -> "video/*"
            item.isAudio -> "audio/*"
            item.isImage -> "image/*"
            item.isText -> "text/plain"
            item.name.endsWith(".pdf", ignoreCase = true) -> "application/pdf"
            item.name.endsWith(".apk", ignoreCase = true) -> "application/vnd.android.package-archive"
            item.name.endsWith(".zip", ignoreCase = true) -> "application/zip"
            else -> "*/*"
        }
    }

    private fun showVideoPlayerDialog(item: RemoteItem) {
        val streamUrl = item.streamUrl.ifEmpty {
            ExplorerClient.resolveUrl(connectedServerUrl, "/api/stream?shareId=${currentShareId}&path=${item.relativePath}")
        }

        val dialog = Dialog(requireContext(), R.style.Theme_LocalMediaServer)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val dBinding = DialogVideoPlayerBinding.inflate(layoutInflater)
        dialog.setContentView(dBinding.root)

        dBinding.tvVideoTitle.text = item.name
        val videoView = dBinding.videoView
        val uri = Uri.parse(streamUrl)
        videoView.setVideoURI(uri)

        videoView.setOnPreparedListener { mp ->
            dBinding.progressVideoBuffering.visibility = View.GONE
            val duration = mp.duration
            dBinding.seekBarVideo.max = duration
            dBinding.tvVideoDuration.text = formatDuration(duration)
            videoView.start()
            dBinding.btnVideoPlayPause.setImageResource(R.drawable.ic_pause)

            // Progress tracking loop
            viewLifecycleOwner.lifecycleScope.launch {
                while (dialog.isShowing && videoView.isPlaying) {
                    val current = videoView.currentPosition
                    dBinding.seekBarVideo.progress = current
                    dBinding.tvVideoTime.text = formatDuration(current)
                    delay(500)
                }
            }
        }

        videoView.setOnErrorListener { _, what, extra ->
            dBinding.progressVideoBuffering.visibility = View.GONE
            Toast.makeText(requireContext(), "Streaming error ($what, $extra)", Toast.LENGTH_SHORT).show()
            true
        }

        dBinding.btnVideoPlayPause.setOnClickListener {
            if (videoView.isPlaying) {
                videoView.pause()
                dBinding.btnVideoPlayPause.setImageResource(R.drawable.ic_play)
            } else {
                videoView.start()
                dBinding.btnVideoPlayPause.setImageResource(R.drawable.ic_pause)
            }
        }

        dBinding.seekBarVideo.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    videoView.seekTo(progress)
                    dBinding.tvVideoTime.text = formatDuration(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        dBinding.btnCloseVideo.setOnClickListener {
            videoView.stopPlayback()
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            videoView.stopPlayback()
        }

        dialog.show()
    }

    private fun showAudioPlayerDialog(item: RemoteItem) {
        val streamUrl = item.streamUrl.ifEmpty {
            ExplorerClient.resolveUrl(connectedServerUrl, "/api/stream?shareId=${currentShareId}&path=${item.relativePath}")
        }

        val dialog = Dialog(requireContext(), R.style.Theme_LocalMediaServer)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val dBinding = DialogAudioPlayerBinding.inflate(layoutInflater)
        dialog.setContentView(dBinding.root)

        dBinding.tvAudioTitle.text = item.name

        val mediaPlayer = MediaPlayer()
        try {
            mediaPlayer.setDataSource(streamUrl)
            mediaPlayer.prepareAsync()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Cannot stream audio: ${e.message}", Toast.LENGTH_SHORT).show()
            return
        }

        mediaPlayer.setOnPreparedListener { mp ->
            val duration = mp.duration
            dBinding.seekBarAudio.max = duration
            dBinding.tvAudioDuration.text = formatDuration(duration)
            mp.start()
            dBinding.btnAudioPlayPause.setIconResource(R.drawable.ic_pause)

            viewLifecycleOwner.lifecycleScope.launch {
                while (dialog.isShowing && mp.isPlaying) {
                    val current = mp.currentPosition
                    dBinding.seekBarAudio.progress = current
                    dBinding.tvAudioCurrentTime.text = formatDuration(current)
                    delay(500)
                }
            }
        }

        dBinding.btnAudioPlayPause.setOnClickListener {
            if (mediaPlayer.isPlaying) {
                mediaPlayer.pause()
                dBinding.btnAudioPlayPause.setIconResource(R.drawable.ic_play)
            } else {
                mediaPlayer.start()
                dBinding.btnAudioPlayPause.setIconResource(R.drawable.ic_pause)
            }
        }

        dBinding.seekBarAudio.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    mediaPlayer.seekTo(progress)
                    dBinding.tvAudioCurrentTime.text = formatDuration(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        dBinding.btnAudioOpenExternal.setOnClickListener {
            if (mediaPlayer.isPlaying) {
                mediaPlayer.pause()
                dBinding.btnAudioPlayPause.setIconResource(R.drawable.ic_play)
            }
            openInExternalApp(item)
        }

        dBinding.btnCloseAudio.setOnClickListener {
            mediaPlayer.release()
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            mediaPlayer.release()
        }

        dialog.show()
    }

    private fun showImagePreviewDialog(item: RemoteItem) {
        val streamUrl = item.streamUrl.ifEmpty {
            ExplorerClient.resolveUrl(connectedServerUrl, "/api/stream?shareId=${currentShareId}&path=${item.relativePath}")
        }

        val dialog = Dialog(requireContext(), R.style.Theme_LocalMediaServer)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val dBinding = DialogImagePreviewBinding.inflate(layoutInflater)
        dialog.setContentView(dBinding.root)

        dBinding.tvImageTitle.text = item.name

        dBinding.btnCloseImage.setOnClickListener {
            dialog.dismiss()
        }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = URL(streamUrl)
                val conn = url.openConnection()
                conn.connectTimeout = 5000
                conn.readTimeout = 10000
                val stream = conn.getInputStream()
                val bitmap = BitmapFactory.decodeStream(stream)
                stream.close()

                withContext(Dispatchers.Main) {
                    dBinding.progressImageLoading.visibility = View.GONE
                    if (bitmap != null) {
                        dBinding.ivPreview.setImageBitmap(bitmap)
                    } else {
                        Toast.makeText(requireContext(), "Failed to decode image", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    dBinding.progressImageLoading.visibility = View.GONE
                    Toast.makeText(requireContext(), "Error loading image: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        dialog.show()
    }

    private fun showTextPreviewDialog(item: RemoteItem) {
        val streamUrl = item.streamUrl.ifEmpty {
            ExplorerClient.resolveUrl(connectedServerUrl, "/api/stream?shareId=${currentShareId}&path=${item.relativePath}")
        }

        val dialog = Dialog(requireContext(), R.style.Theme_LocalMediaServer)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val dBinding = DialogTextPreviewBinding.inflate(layoutInflater)
        dialog.setContentView(dBinding.root)

        dBinding.tvTextTitle.text = item.name
        dBinding.btnCloseText.setOnClickListener { dialog.dismiss() }

        viewLifecycleOwner.lifecycleScope.launch {
            val res = ExplorerClient.fetchTextContent(streamUrl)
            dBinding.progressTextLoading.visibility = View.GONE
            res.onSuccess { text ->
                dBinding.tvTextContent.text = text
            }.onFailure { err ->
                dBinding.tvTextContent.text = "Error reading file: ${err.message}"
            }
        }

        dialog.show()
    }

    private fun formatDuration(millis: Int): String {
        val seconds = (millis / 1000) % 60
        val minutes = (millis / (1000 * 60)) % 60
        val hours = millis / (1000 * 60 * 60)
        return if (hours > 0) {
            String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    // =========================================================================
    // DOWNLOAD CONFIRMATION DIALOG & DOWNLOAD EXECUTION
    // =========================================================================

    private fun showDownloadConfirmDialog(item: RemoteItem) {
        val downloadUrl = item.downloadUrl.ifEmpty {
            ExplorerClient.resolveUrl(connectedServerUrl, "/api/shares/${currentShareId}/files/${item.id}/download")
        }

        pendingDownloadFile = item

        val dialog = Dialog(requireContext(), R.style.ThemeOverlay_App_MaterialAlertDialog)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val dBinding = DialogDownloadConfirmBinding.inflate(layoutInflater)
        dialog.setContentView(dBinding.root)

        pendingDownloadDialog = dialog
        pendingDownloadDialogBinding = dBinding

        dBinding.tvFileName.text = item.name
        dBinding.tvFileSize.text = "Original Size: ${DownloadManager.formatBytes(item.size)}"

        val destPath = customDownloadFolder ?: DownloadManager.getDefaultDownloadDir().absolutePath
        dBinding.tvDestinationPath.text = destPath

        dBinding.btnChangeDestination.setOnClickListener {
            folderPickerLauncher.launch(null)
        }

        dBinding.btnCancelDownload.setOnClickListener {
            dialog.dismiss()
        }

        dBinding.btnConfirmDownload.setOnClickListener {
            val keepScreenOn = dBinding.cbKeepScreenOn.isChecked
            val chosenDest = customDownloadFolder ?: DownloadManager.getDefaultDownloadDir().absolutePath

            downloadManager.enqueueDownload(
                fileName = item.name,
                fileUrl = downloadUrl,
                totalBytes = item.size,
                destinationFolder = chosenDest,
                keepScreenOn = keepScreenOn
            )

            dialog.dismiss()
            Toast.makeText(requireContext(), "Download started for ${item.name}", Toast.LENGTH_SHORT).show()

            // Automatically switch to downloads tab so user sees progress!
            if (!isViewingDownloadsTab) {
                toggleDownloadsView()
            }
        }

        dialog.show()
    }

    // =========================================================================
    // DOWNLOADS VIEW RENDERING
    // =========================================================================

    private fun renderDownloadsUI(active: List<DownloadTask>, completed: List<DownloadTask>) {
        if (_binding == null) return

        // 1. Active downloads
        if (active.isEmpty()) {
            binding.layoutActiveDownloadsContainer.removeAllViews()
            binding.tvNoActiveDownloads.visibility = View.VISIBLE
        } else {
            binding.tvNoActiveDownloads.visibility = View.GONE

            // Remove any views no longer in active downloads
            val activeIds = active.map { it.id }.toSet()
            val toRemove = mutableListOf<View>()
            for (i in 0 until binding.layoutActiveDownloadsContainer.childCount) {
                val child = binding.layoutActiveDownloadsContainer.getChildAt(i)
                val taskId = child.tag as? String
                if (taskId == null || taskId !in activeIds) {
                    toRemove.add(child)
                }
            }
            toRemove.forEach { binding.layoutActiveDownloadsContainer.removeView(it) }

            // Add or update views for each active task
            for (task in active) {
                var child = binding.layoutActiveDownloadsContainer.findViewWithTag<View>(task.id)
                val itemBinding: ItemDownloadActiveBinding
                if (child == null) {
                    itemBinding = ItemDownloadActiveBinding.inflate(layoutInflater, binding.layoutActiveDownloadsContainer, false)
                    child = itemBinding.root
                    child.tag = task.id
                    binding.layoutActiveDownloadsContainer.addView(child)
                } else {
                    itemBinding = ItemDownloadActiveBinding.bind(child)
                }

                itemBinding.tvActiveFileName.text = task.fileName

                val percent = task.progressPercent
                itemBinding.progressBarDownload.progress = percent

                val speedStr = DownloadManager.formatBytes(task.speedBytesPerSec) + "/s"
                val bytesStr = if (task.totalBytes > 0) {
                    "${DownloadManager.formatBytes(task.downloadedBytes)} / ${DownloadManager.formatBytes(task.totalBytes)} ($percent%)"
                } else {
                    "${DownloadManager.formatBytes(task.downloadedBytes)} downloaded"
                }
                itemBinding.tvActiveBytes.text = bytesStr

                itemBinding.tvActiveSpeed.text = when (task.status) {
                    DownloadStatus.PAUSED -> "Paused"
                    DownloadStatus.QUEUED -> "Queued"
                    DownloadStatus.FAILED -> "Failed: ${task.error ?: "Error"}"
                    else -> speedStr
                }

                itemBinding.btnActivePauseResume.setImageResource(
                    if (task.status == DownloadStatus.PAUSED || task.status == DownloadStatus.FAILED) R.drawable.ic_play else R.drawable.ic_pause
                )

                itemBinding.btnActivePauseResume.setOnClickListener {
                    val current = downloadManager.getTask(task.id) ?: return@setOnClickListener
                    if (current.status == DownloadStatus.PAUSED || current.status == DownloadStatus.FAILED) {
                        downloadManager.resumeDownload(task.id)
                    } else {
                        downloadManager.pauseDownload(task.id)
                    }
                }

                itemBinding.btnActiveCancel.setOnClickListener {
                    downloadManager.cancelDownload(task.id)
                }
            }
        }

        // 2. Completed downloads
        if (completed.isEmpty()) {
            binding.layoutCompletedDownloadsContainer.removeAllViews()
            binding.tvNoCompletedDownloads.visibility = View.VISIBLE
        } else {
            binding.tvNoCompletedDownloads.visibility = View.GONE

            // Remove any views no longer in completed
            val completedIds = completed.map { it.id }.toSet()
            val toRemove = mutableListOf<View>()
            for (i in 0 until binding.layoutCompletedDownloadsContainer.childCount) {
                val child = binding.layoutCompletedDownloadsContainer.getChildAt(i)
                val taskId = child.tag as? String
                if (taskId == null || taskId !in completedIds) {
                    toRemove.add(child)
                }
            }
            toRemove.forEach { binding.layoutCompletedDownloadsContainer.removeView(it) }

            for (task in completed) {
                var child = binding.layoutCompletedDownloadsContainer.findViewWithTag<View>(task.id)
                if (child == null) {
                    val itemBinding = ItemDownloadCompletedBinding.inflate(layoutInflater, binding.layoutCompletedDownloadsContainer, false)
                    child = itemBinding.root
                    child.tag = task.id

                    itemBinding.tvCompletedFileName.text = task.fileName
                    itemBinding.tvCompletedDetails.text = "${DownloadManager.formatBytes(task.totalBytes)} • Download Complete"

                    itemBinding.btnCompletedOpen.setOnClickListener {
                        val intent = downloadManager.createOpenFileIntent(task)
                        if (intent != null) {
                            try {
                                startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(requireContext(), "No app found to open this file", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(requireContext(), "File not found on device", Toast.LENGTH_SHORT).show()
                        }
                    }

                    itemBinding.btnCompletedDelete.setOnClickListener {
                        task.localFilePath?.let { File(it).delete() }
                        downloadManager.removeCompletedTask(task.id)
                    }

                    binding.layoutCompletedDownloadsContainer.addView(child)
                } else {
                    val itemBinding = ItemDownloadCompletedBinding.bind(child)
                    itemBinding.tvCompletedFileName.text = task.fileName
                    itemBinding.tvCompletedDetails.text = "${DownloadManager.formatBytes(task.totalBytes)} • Download Complete"
                }
            }
        }
    }

    // =========================================================================
    // SWITCH SERVER DIALOG
    // =========================================================================

    private fun showSwitchServerDialog() {
        val dialog = Dialog(requireContext(), R.style.ThemeOverlay_App_MaterialAlertDialog)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val dBinding = DialogSwitchServerBinding.inflate(layoutInflater)
        dialog.setContentView(dBinding.root)

        val container = dBinding.layoutServerOptionsContainer

        // Option 1: Local server option
        val localState = ServerService.serviceState.value
        val localCard = MaterialCardView(requireContext()).apply {
            setCardBackgroundColor(ContextCompat.getColor(context, R.color.surface_card))
            strokeColor = ContextCompat.getColor(context, R.color.surface_border)
            strokeWidth = 1
            radius = 12f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 10) }
        }

        val localInner = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 12)
        }

        val localTitle = TextView(requireContext()).apply {
            text = "🏠 Local Server (This Device)"
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        val localSubtitle = TextView(requireContext()).apply {
            val port = if (localState is ServerState.Running) localState.config.port else ServerConfig.DEFAULT_PORT
            text = "http://localhost:$port (${if (localState is ServerState.Running) "Running" else "Stopped"})"
            setTextColor(ContextCompat.getColor(context, R.color.accent))
            textSize = 12f
        }

        localInner.addView(localTitle)
        localInner.addView(localSubtitle)
        localCard.addView(localInner)
        localCard.setOnClickListener {
            val port = if (localState is ServerState.Running) localState.config.port else ServerConfig.DEFAULT_PORT
            connectToServer("http://localhost:$port", "Local Server (This Device)")
            dialog.dismiss()
        }
        container.addView(localCard)

        // Option 2: Discovered servers on network
        val discovered = ServerService.nsdDiscoveryManager.discoveredServers.value
        for (server in discovered) {
            val sCard = MaterialCardView(requireContext()).apply {
                setCardBackgroundColor(ContextCompat.getColor(context, R.color.surface_card))
                strokeColor = ContextCompat.getColor(context, R.color.surface_border)
                strokeWidth = 1
                radius = 12f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, 10) }
            }

            val sInner = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 12, 16, 12)
            }

            val sTitle = TextView(requireContext()).apply {
                text = "🖥️ ${server.serviceName}"
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                textSize = 14f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }

            val sSubtitle = TextView(requireContext()).apply {
                text = "${server.url} (${server.status.name})"
                setTextColor(ContextCompat.getColor(context, R.color.accent))
                textSize = 12f
            }

            sInner.addView(sTitle)
            sInner.addView(sSubtitle)
            sCard.addView(sInner)
            sCard.setOnClickListener {
                connectToServer(server.url, server.serviceName)
                dialog.dismiss()
            }
            container.addView(sCard)
        }

        dBinding.btnCloseSwitchServer.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }
}
