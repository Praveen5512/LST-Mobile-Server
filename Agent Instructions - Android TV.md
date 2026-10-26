# Project Instructions: Android TV Client (Local Media Player)

## 1. Your Role

Act as the lead Android TV software engineer responsible for architecting, implementing, and delivering a native **Android TV frontend client** application.

Your responsibility is to deliver a fully functional, high-performance, D-pad navigable television application—not merely a prototype, design concept, or partial code snippets.

Work incrementally. Validate technical assumptions early, strictly adhere to Android TV Leanback design principles, test each milestone thoroughly, and ensure flawless remote control (D-pad) user experience.

---

## 2. Product Objective & Core Architectural Principles

Build a dedicated Android TV / Google TV application that connects to the **Android Local Media Server** running on the local network (Wi-Fi or hotspot).

### Fundamental Architectural Rules:

1. **Strictly Pure Frontend (Zero Server Components)**:
   - The Android TV app is **exclusively a media consumer/client**.
   - It **must not** run an HTTP server, host services, or share local TV storage.
   - It reads content entirely from the server's network address over HTTP.
2. **Zero-Configuration Discovery (mDNS / NSD)**:
   - The TV app must automatically detect running servers on the local Wi-Fi / hotspot network using Android's native `NsdManager` (`_http._tcp.` with prefix `LocalMediaServer`).
   - If multiple servers exist, the user can select between them.
   - If network multicast is restricted by the router, provide an intuitive on-screen **Manual IP Connection** fallback.
3. **True 10-Foot Television User Experience**:
   - Designed specifically for viewing from 10 feet away on 1080p and 4K displays.
   - Fully controllable using a standard TV remote control (D-pad Directional Pad + OK/Select + Back + Play/Pause buttons).
   - **No touchscreen dependency**: `android.hardware.touchscreen` must be declared as non-required.
   - Clear visual focus indicators on every interactive element (scaling, high-contrast borders, subtle elevation).
4. **High-Performance Hardware-Accelerated Streaming**:
   - Direct streaming using **AndroidX Media3 (ExoPlayer)**.
   - Efficient HTTP 206 Byte-Range streaming for instant seeking on large multi-gigabyte MKV, MP4, and WebM files.
   - Playback resume position memory (remembers where the user left off).

---

## 3. Server API Specification (Client Consumer Contract)

The Android TV client consumes the REST API exposed by the phone's media server. All responses are JSON:

### A. Server Health & Share Listing
- **Endpoint**: `GET /api/shares`
- **Response**:
  ```json
  [
    {
      "id": "share_3126f9",
      "name": "Movies & Series",
      "uri": "content://com.android.externalstorage.documents/tree/primary%3AMovies",
      "isAccessible": true
    },
    {
      "id": "share_8a41c2",
      "name": "Lossless FLAC Music",
      "uri": "content://com.android.externalstorage.documents/tree/primary%3AMusic",
      "isAccessible": true
    }
  ]
  ```

### B. Directory Browsing & Media Listing
- **Endpoint**: `GET /api/media?shareId={shareId}&path={relativePath}`
- **Parameters**:
  - `shareId` (required): The ID of the target share.
  - `path` (optional, default empty for root): Relative folder path inside the share.
- **Response**:
  ```json
  {
    "shareId": "share_3126f9",
    "shareName": "Movies & Series",
    "currentPath": "Action",
    "items": [
      {
        "name": "SciFi",
        "path": "Action/SciFi",
        "isDirectory": true,
        "size": 0,
        "mimeType": "application/x-directory",
        "modified": 1729000000000
      },
      {
        "name": "Interstellar.2014.1080p.mkv",
        "path": "Action/Interstellar.2014.1080p.mkv",
        "isDirectory": false,
        "size": 4294967296,
        "mimeType": "video/x-matroska",
        "modified": 1729012345000
      }
    ]
  }
  ```

### C. Direct Byte-Range Media Streaming
- **Endpoint**: `GET /api/stream?shareId={shareId}&path={encodedPath}`
- **Headers Sent by Client**:
  - `Range: bytes={start}-{end}` (Standard HTTP range request handled natively by ExoPlayer `DefaultHttpDataSource`).
- **Response**:
  - `HTTP 206 Partial Content` with `Content-Range: bytes {start}-{end}/{total}` and `Content-Type: video/mp4`, `audio/flac`, `image/jpeg`, etc.
  - Supports $O(1)$ random access seeking.

---

## 4. Platform Requirements & Android TV Manifest

### A. Android SDK Versions
- `minSdkVersion`: 21 (Android 5.0 Lollipop) or 24 (recommended for Media3).
- `targetSdkVersion`: 34 (Android 14) or latest.
- `compileSdkVersion`: 34.

### B. Mandatory TV Manifest Attributes
In `AndroidManifest.xml`:
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- Permissions -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
    <uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />

    <!-- Leanback TV Feature Flags -->
    <uses-feature
        android:name="android.software.leanback"
        android:required="true" />
    <uses-feature
        android:name="android.hardware.touchscreen"
        android:required="false" />
    <uses-feature
        android:name="android.hardware.microphone"
        android:required="false" />

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:banner="@drawable/tv_banner"
        android:label="@string/app_name"
        android:theme="@style/Theme.Leanback"
        android:usesCleartextTraffic="true">

        <!-- TV Home Launcher Entry Point -->
        <activity
            android:name=".ui.MainActivity"
            android:banner="@drawable/tv_banner"
            android:exported="true"
            android:icon="@mipmap/ic_launcher"
            android:logo="@drawable/tv_banner"
            android:screenOrientation="landscape">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LEANBACK_LAUNCHER" />
            </intent-filter>
        </activity>

        <!-- Leanback Video Player Activity -->
        <activity
            android:name=".ui.VideoPlayerActivity"
            android:configChanges="orientation|screenSize|keyboardHidden|smallestScreenSize|screenLayout"
            android:exported="false"
            android:screenOrientation="landscape"
            android:theme="@style/Theme.Leanback.Fullscreen" />

    </application>
</manifest>
```

> [!IMPORTANT]
> The `android:banner` attribute (320x180 px drawable) is **mandatory** for Android TV apps. Without it, the app will not appear on the Google TV / Android TV home screen application row.

---

## 5. Technology Stack & Dependencies

Use standard, modern Kotlin and AndroidX libraries:

```kotlin
dependencies {
    // AndroidX Core & Lifecycle
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")

    // AndroidX Leanback for 10-foot UI
    implementation("androidx.leanback:leanback:1.0.0")
    implementation("androidx.leanback:leanback-preference:1.0.0")

    // AndroidX Media3 (ExoPlayer + Leanback integration)
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-ui-leanback:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")

    // Networking & Serialization
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Image loading with TV memory optimization
    implementation("io.coil-kt:coil:2.7.0")

    // Local Storage for Recent Servers & Resume Positions
    implementation("androidx.datastore:datastore-preferences:1.1.1")
}
```

---

## 6. Functional Architecture & Components

```
com.localserver.tv
├── data
│   ├── api
│   │   ├── MediaServerClient.kt        // OkHttp / Coroutines API consumer
│   │   └── ServerApiModels.kt          // ShareDto, MediaItemDto, MediaListDto
│   ├── discovery
│   │   └── TvNsdDiscoveryManager.kt    // mDNS scanning with Wifi MulticastLock
│   └── repository
│       ├── ServerPreferences.kt        // Last connected IP, recent history, playback bookmarks
│       └── MediaRepository.kt          // Cache & data aggregation
├── ui
│   ├── browse
│   │   ├── MainTvActivity.kt           // Root TV container with BackgroundManager
│   │   ├── MainTvFragment.kt           // Leanback BrowseSupportFragment
│   │   ├── CardPresenter.kt            // 10-foot media card view presenter
│   │   └── MediaItemActionHandler.kt   // Click / D-pad select dispatcher
│   ├── connect
│   │   ├── ServerSelectDialog.kt       // Pick discovered server or enter manual IP
│   │   └── ManualIpDialog.kt           // D-pad numeric PIN pad for IP:port entry
│   ├── player
│   │   ├── VideoPlayerActivity.kt      // Fullscreen Media3 player activity
│   │   ├── VideoPlaybackFragment.kt    // VideoSupportFragment with transport controls
│   │   └── AudioPlayerActivity.kt      // Fullscreen audio player with queue
│   └── photo
│       └── PhotoSlideshowActivity.kt   // 4K photo viewer with auto-timer
```

---

## 7. Detailed Feature Specifications

### Feature A: Network Discovery & Connection Handshake

1. **Auto-Discovery**:
   - On app launch, acquire a `WifiManager.MulticastLock` and call `NsdManager.discoverServices("_http._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)`.
   - Filter services matching `LocalMediaServer` or resolve any HTTP service advertising media endpoints.
   - For each found service, resolve the IP address and port, and test `GET /api/shares`.
2. **Server Selector Screen**:
   - If one server is found and marked "auto-connect", connect directly.
   - If multiple servers are detected, present horizontal cards with the device names (e.g., `Pixel 8 Pro`, `Galaxy S23`, `Desktop-PC`), IP address, and an "Online" green badge.
3. **Manual IP Connection Fallback**:
   - Provide an "Enter IP Address Manually" card.
   - Provide an on-screen D-pad keypad with numbers `0-9`, `.`, `:`, and `Backspace` so users never struggle with complex virtual software keyboards.
   - Validate format (`http://192.168.x.x:port`) and persist the last 5 successful connections in `DataStore`.

---

### Feature B: 10-Foot Browse Screen (`BrowseSupportFragment`)

1. **Navigation Headers (Left Rail)**:
   - **Recent / Continue Watching**: Videos with saved playback positions $> 0$.
   - **All Videos**: Aggregated video content from all active shares.
   - **Music & Albums**: Audio files grouped by album/folder.
   - **Photos & Wallpapers**: High-resolution image galleries.
   - **Folders / Shares**: Raw folder traversal preserving the filesystem tree.
   - **Switch Server / Settings**: Quick server switcher and cache cleaner.
2. **Dynamic Background Manager (`BackgroundManager`)**:
   - When the user focuses on any video or photo card, load a high-res preview or blurred album art as the full-screen television backdrop after a 300ms debounce.
3. **Card Presentation (`CardPresenter`)**:
   - Each card displays:
     - Media thumbnail (or high-contrast icon for video/music/folder).
     - File title (truncated with ellipsis if $> 30$ chars).
     - File size (e.g. `2.4 GB`) and duration / date.
     - Progress bar indicator for partially watched videos.
   - Focus animation: Scale up 1.08x with high-contrast accent outline when focused by D-pad.

---

### Feature C: Leanback Video Player with Media3 / ExoPlayer

1. **Playback Initialization**:
   - Construct `MediaItem` with the stream URL: `http://{serverIp}:{port}/api/stream?shareId={id}&path={encodedPath}`.
   - Attach `DefaultHttpDataSource.Factory()` with a 64KB read buffer.
2. **D-pad Remote Control Mappings**:
   - **Center / OK**: Toggle Play / Pause and show/hide the on-screen playback HUD.
   - **Right (Tap)**: Fast-forward $+10$ seconds with visual toast indicator.
   - **Right (Hold / Long Press)**: Fast-forward $+30$ seconds or $+60$ seconds.
   - **Left (Tap)**: Rewind $-10$ seconds.
   - **Left (Hold / Long Press)**: Rewind $-30$ seconds.
   - **Up**: Show top info card (Video Title, Resolution, Codec, Audio Track).
   - **Down**: Show subtitle / audio stream track selection dialog.
   - **Back**: Save current position to local `DataStore` and return to browse screen.
3. **Resume Playback Dialog**:
   - If a video was previously watched past 30 seconds and has at least 60 seconds remaining:
   - When clicked, display a prompt:
     - `[Resume from 42:15]` (default focused)
     - `[Start from Beginning]`
4. **Error Handling & Buffer Underrun**:
   - If Wi-Fi packets drop, show a sleek loading spinner.
   - If connection drops, show "Server Connection Lost. Retrying..." with exponential backoff before asking the user to reconnect.

---

### Feature D: Fullscreen Audio Player & Photo Slideshow

1. **Audio Player**:
   - Vinyl / Album art centered on screen with smooth rotating animation during playback.
   - Displays Track Title, Artist, Share Name, and Format (`FLAC 24-bit / 96kHz` or `MP3 320kbps`).
   - Playlist queue rail at the bottom: users can press D-pad Down to browse and jump to other tracks in the folder.
   - D-pad Left/Right jumps Previous/Next track.
2. **4K Photo Slideshow**:
   - Fullscreen picture viewer with 100% black letterboxing/pillarboxing.
   - Slideshow toggle: plays next photo every 5 seconds with a smooth 400ms crossfade.
   - D-pad Left/Right allows manual navigation.

---

## 8. Step-by-Step Implementation Milestones for Agents

Follow these milestones sequentially. **Do not begin a milestone until the preceding milestone is fully tested and verified.**

```
+---------------------------------------------------------------------------------+
| Milestone 1: TV Project Scaffolding, Leanback Manifest & Network Discovery     |
| - Android TV manifest with LEANBACK_LAUNCHER, banner, touchscreen=false.        |
| - NsdDiscoveryManager for TV scanning mDNS _http._tcp.                          |
| - Server selection & manual IP input dialog with D-pad pinpad.                  |
+---------------------------------------------------------------------------------+
                                       |
                                       v
+---------------------------------------------------------------------------------+
| Milestone 2: BrowseSupportFragment, Navigation Rails & API Client              |
| - MediaServerClient for /api/shares and /api/media.                             |
| - BrowseSupportFragment with RowsAdapter, ListRow, and CardPresenter.          |
| - BackgroundManager updating backdrop image on card focus.                      |
+---------------------------------------------------------------------------------+
                                       |
                                       v
+---------------------------------------------------------------------------------+
| Milestone 3: Media3 Leanback Video Player with Remote Control Seek & Resume    |
| - Fullscreen VideoPlayerActivity with Media3 ExoPlayer.                         |
| - D-pad seeking (+-10s, +-30s), Play/Pause toggle, and Transport HUD.          |
| - Playback resume position persistence in DataStore.                            |
+---------------------------------------------------------------------------------+
                                       |
                                       v
+---------------------------------------------------------------------------------+
| Milestone 4: Audio Player with Playlist Queue & 4K Photo Slideshow              |
| - Fullscreen audio playback with queue drawer and track switching.              |
| - 4K photo viewer with crossfade transitions and auto-slideshow timer.          |
+---------------------------------------------------------------------------------+
                                       |
                                       v
+---------------------------------------------------------------------------------+
| Milestone 5: TV Performance Tuning, Focus Polishing & Release Packaging         |
| - D-pad focus traversal audit (ensure no dead-ends where focus is lost).        |
| - Memory leak audit with Coil bitmap pooling on low-RAM TV chipsets.            |
| - Production release APK assembly & sideload verification.                     |
+---------------------------------------------------------------------------------+
```

---

## 9. Verification & Quality Checklist

Before completing any task, execute the following verification steps:

1. **D-Pad Focus Verification**:
   - Every clickable button, card, and list item must have an explicit `:focus` visual state.
   - Pressing `DPAD_UP`, `DPAD_DOWN`, `DPAD_LEFT`, `DPAD_RIGHT` from any screen element must always navigate to a logical neighbor. **Focus must never disappear.**
2. **No Touchscreen Dependency**:
   - Run `aapt dump badging <apk>` and confirm `uses-feature: name='android.hardware.touchscreen' required='false'`.
3. **Banner Asset**:
   - Confirm `android:banner` is present and renders properly at $320 \times 180$ dp.
4. **Byte-Range Seek Performance**:
   - Test seeking to 80% of a 2GB+ video file over local Wi-Fi. Video playback must resume in $< 800\text{ ms}$.
5. **Network Disconnect Recovery**:
   - Turn off server or disconnect Wi-Fi while browsing. App must display a polite TV dialog ("Server unreachable. Check Wi-Fi or restart server") without crashing.
