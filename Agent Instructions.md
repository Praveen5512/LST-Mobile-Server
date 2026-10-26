# Project Instructions: Android Local Media Server

## 1. Your Role

Act as the lead software engineer responsible for designing and implementing an Android application that turns an Android phone into a local-network file and media server.

Your responsibility is to deliver a working, tested application—not merely a design, prototype UI, or implementation proposal.

Work incrementally. Validate technical assumptions early, keep the architecture maintainable, and test each milestone before proceeding to the next.

## 2. Product Objective

Build an Android application that allows users to share files stored on their phone with other devices on the same local network.

Other devices must be able to access a browser-based interface using a URL such as:

`http://192.168.1.25:8080`

The application must support:

- Existing Wi-Fi networks.
- Android hotspot networks.
- Automatic network detection.
- User-configurable IP binding and TCP ports.
- Manually selected folders and a broader view of accessible internal storage.
- Browser-based file browsing and downloads.
- Direct video and audio streaming.
- Image previews.
- Multiple simultaneous clients.
- Background operation while the host app is closed or the screen is locked, subject to Android platform restrictions.
- QR codes and copyable connection URLs.
- Installation through a sideloadable APK.

The application must not require a cloud service, an internet connection for local sharing, or a separate client application on the receiving device.

## 3. Mandatory Technical Constraints

### Android application

Use Kotlin and Gradle for the native Android application.

Use a foreground service to run the server independently of the host UI. Display an ongoing notification while the service is active and provide an action to stop the server.

Respect Android permissions, foreground-service requirements, scoped storage, and background execution restrictions.

Do not assume that the server can enable the hotspot programmatically, access every filesystem location, or bind to any arbitrary IP address. Validate these assumptions on a physical Android device.

### User interface

Use React and TypeScript for the host and browser-based interfaces.

The host interface may run in an Android WebView. The client interface must be served by the local HTTP server and work in a normal browser.

Keep the host interface, client interface, HTTP server, networking, and file-access logic separate.

The server must continue running independently of WebView lifecycle events.

### HTTP server

Evaluate a lightweight embedded HTTP server compatible with Android, such as Ktor CIO or NanoHTTPD.

Before committing to the implementation, compare their suitability for:

- Streaming large files.
- HTTP byte-range requests.
- Concurrent client connections.
- Memory efficiency.
- Android lifecycle integration.
- Maintenance and dependency compatibility.

Select one implementation based on these requirements. Avoid building a custom TCP or HTTP stack unless a demonstrated limitation requires it.

## 4. Functional Requirements

### A. Server configuration

Implement the following capabilities:

1. Start, stop, and restart the server.
2. Display the current server status.
3. Display available local IP addresses and network interfaces.
4. Allow selection of a supported listening address.
5. Allow the user to configure the listening TCP port.
6. Detect port conflicts and invalid binding addresses.
7. Display accessible connection URLs.
8. Generate a QR code for the client URL.
9. Detect network changes and refresh the displayed addresses.
10. Explain connectivity failures with actionable error messages.

The interface must distinguish between the address the server is bound to and the addresses clients can actually use.

### B. File access

Implement two sharing modes:

1. User-selected folders obtained through Android's Storage Access Framework.
2. A broader storage browser limited to locations the application is authorized to access.

Preserve persisted folder permissions where supported.

The client interface must allow users to:

- Browse authorized directories.
- View file names, sizes, and relevant metadata.
- Open supported media.
- Download files.

Clients must not be able to upload, modify, rename, or delete shared files.

Never expose arbitrary filesystem paths or allow clients to escape authorized directory boundaries.

### C. Media streaming

Implement direct file delivery without mandatory transcoding.

Requirements:

- Correct MIME types.
- Correct content lengths.
- HTTP HEAD support where appropriate.
- HTTP Range requests.
- Correct `206 Partial Content` responses.
- Correct `Content-Range` and `Content-Length` headers.
- Support for seeking through compatible media.
- Streaming without loading an entire file into memory.
- Independent requests from multiple clients.
- Graceful handling of disconnects and interrupted transfers.

Use bounded buffering and concurrency. Do not read large files into memory merely to serve them.

If the browser does not support a particular media format, display a useful error instead of leaving the player in an indefinite loading state.

### D. Client web interface

Implement four main sections:

1. **Browse:** folder navigation, sorting, search, metadata, and downloads.
2. **Videos:** playback, seeking, fullscreen, and optional resume position.
3. **Music:** playback controls, queue, and playlists.
4. **Photos:** thumbnail grid, full-resolution viewing, and slideshow.

Use a responsive design suitable for desktop browsers, mobile browsers, tablets, and compatible television browsers.

The client must not need an account, login, installation, or internet connection.

### E. Security

Version 1 uses unauthenticated, read-only access on the local network.

Implement the following safeguards:

- Display a clear warning that reachable devices may access shared files.
- Restrict sharing to authorized directories.
- Validate file identifiers and request paths.
- Prevent directory traversal.
- Do not expose Android's privileged bridge to client-controlled web content.
- Do not enable router port forwarding.
- Avoid binding to unintended interfaces.
- Provide an immediate Stop Server action.
- Ensure stopped servers reject new requests.
- Avoid logging sensitive file contents.

Do not claim that the server is accessible only to trusted devices unless the implementation can actually enforce that boundary.

## 5. Required Architecture

Separate the implementation into logical modules. Names may be adjusted to fit the selected project structure.

- `ServerController`: starts and stops the HTTP listener.
- `ServerService`: manages the Android foreground service.
- `NetworkManager`: discovers interfaces and monitors connectivity.
- `ServerConfig`: manages port and binding configuration.
- `FileRepository`: exposes authorized directories and file metadata.
- `ShareRegistry`: manages authorized folder grants and share identifiers.
- `StreamingHandler`: handles file delivery, MIME types, and byte ranges.
- `RequestRouter`: routes API endpoints and static web assets.

Keep networking and file-access logic independent of React components.

Use opaque file identifiers or another safe mapping to authorized files rather than trusting arbitrary client-supplied filesystem paths.

## 6. Implementation Strategy

Follow these milestones in order.

### Milestone 1: Networking proof of concept

Build a minimal Android application that:

- Starts an embedded HTTP server.
- Allows configuration of the port.
- Displays usable local IP addresses.
- Serves a test HTML page and sample file.
- Works over existing Wi-Fi.
- Is tested over the Android hotspot.

Acceptance criteria:

A second device can open the server URL and retrieve the sample file over both network modes.

Do not proceed to full UI development until this milestone is verified.

### Milestone 2: File access and streaming

Implement folder selection, directory listing, downloads, MIME types, and HTTP byte-range support.

Test with large files, seeking, partial downloads, interrupted connections, and multiple clients.

Acceptance criteria:

Clients can browse authorized files, download them, and seek through supported video files without whole-file buffering.

### Milestone 3: Background service

Implement the foreground service and persistent notification.

Handle screen locking, app switching, network changes, port conflicts, and storage permissions being revoked.

Acceptance criteria:

The server remains available during the defined background and locked-screen tests. Any service termination or network interruption produces a clear recovery path.

### Milestone 4: Web interface

Build the React and TypeScript host interface and client application.

Implement the file browser, video player, music queue, photo gallery, connection URL, and QR code.

Acceptance criteria:

A second device can browse and play supported files through its browser without installing anything.

### Milestone 5: Performance and security

Test concurrent clients, memory usage, battery consumption, path traversal, permission revocation, and interrupted transfers.

Acceptance criteria:

The application meets the agreed performance targets and has no known critical file-access vulnerabilities.

### Milestone 6: APK release

Build and sign a sideloadable APK.

Test clean installation, startup, permissions, server operation, stopping the service, and upgrade behavior.

Provide a README containing setup instructions, supported Android versions, permissions, known limitations, and build instructions.

## 7. Engineering Standards

Follow these rules throughout implementation:

1. Inspect the existing repository before changing anything.
2. Reuse existing project conventions when they are appropriate.
3. Do not replace working code unnecessarily.
4. Do not implement all milestones in one large change.
5. Keep dependencies minimal and justify major additions.
6. Handle errors explicitly; do not silently swallow exceptions.
7. Keep configuration separate from application logic.
8. Write tests for request routing, byte ranges, path validation, and file access.
9. Use physical-device tests for hotspot connectivity and background behavior where emulators are insufficient.
10. Document platform limitations rather than implementing misleading workarounds.
11. Keep the application buildable after every milestone.
12. Do not report a feature as complete until its acceptance criteria have been verified.

If a technical obstacle appears, explain the limitation, investigate alternatives, and recommend the smallest change that preserves the product requirements.

## 8. Performance Targets

Use these initial targets as hypotheses to validate in a controlled environment:

- Typical directory listing latency: under 300 ms.
- Playback startup: under 2 seconds for supported media under defined test conditions.
- At least three simultaneous browsing or download clients.
- No whole-file buffering.
- A 30-minute locked-screen server test.
- Correct seeking and partial-content responses.
- No crashes or corrupted responses during the defined concurrency tests.

Record the test device, Android version, network conditions, file size, and test results. Do not claim these targets have been met without measurements.

## 9. First Action Required

Start by inspecting the current repository and determining whether an Android project already exists.

Then:

1. Report the existing project structure and build status.
2. Identify the smallest changes needed to implement Milestone 1.
3. Evaluate the embedded HTTP server options against the stated requirements.
4. Identify Android permissions and platform constraints.
5. Implement the networking proof of concept.
6. Build it and test the available network scenarios.
7. Report completed work, test evidence, limitations, and the next step.

Do not begin by creating the complete product UI. The first objective is to prove that the Android device can reliably serve a file to another device over both Wi-Fi and hotspot.

## 10. Definition of Success

The final product must allow a user to install the APK, select folders, configure a port, start the server, scan a QR code from another device, browse files, stream supported media, and download files.

The server must support multiple clients, remain operational in the background under supported Android conditions, and stop cleanly when requested.

Prioritize a reliable networking and streaming foundation over visual polish or feature count.