# ChatGPT → Gemini

Android app that converts a ChatGPT data export (ZIP) into Google Docs stored in your Google Drive, so Gemini can read and reference your conversation history.

---

## Download & Install

> **No app store required.** The APK is distributed directly via GitHub Releases.

### Step 1 — Allow installation from unknown sources

Android blocks APKs from outside the Play Store by default. You only need to do this once:

- **Android 8+**: Go to **Settings → Apps → Special app access → Install unknown apps**, then allow your browser or file manager.
- **Android 7**: Go to **Settings → Security → Unknown sources** and toggle it on.

### Step 2 — Download the APK

1. Go to the [**Releases page**](https://github.com/ahaf82/ChatGPTConverterApp/releases).
2. Open the latest release.
3. Under **Assets**, tap **`ChatGPTConverterApp.apk`** to download it.

### Step 3 — Install

Open the downloaded file (usually in your **Downloads** folder or notification shade) and tap **Install**.

### Step 4 — Done

Open the app. No account setup is required upfront — Google Sign-In is only triggered when you tap **UPLOAD TO DRIVE**.

> **Why is Android warning me?** Because the app is not distributed through the Play Store, Android shows a security warning. The full source code is available in this repository so you can verify exactly what the app does before installing.

---

## Features

- **Handles both ChatGPT export formats**
  - Old format: single `conversations.json`
  - New format: split `conversations-000.json`, `conversations-001.json`, …
- **Media linked via Drive** — images and videos are uploaded to a `Media` subfolder and linked from each document. No base64 embedding means no out-of-memory crashes on large exports.
- **Save locally** — alternatively exports all conversations as HTML files to `Downloads/ChatGPT_Archive/`.
- **Background-safe** — runs as a foreground service so the OS does not kill the upload when you switch apps.
- **Dual progress cards** — one card tracks media file upload, a second tracks conversation upload, each with their own progress bar.
- **Privacy-safe crash logging** — in-memory ring buffer (no persistent log files). Long-press the error message to copy a debug dump to clipboard. No conversation content, titles, or account names are ever logged.
- **Robust error handling**
  - Catches `OutOfMemoryError` (not just `Exception`) — one large conversation never stops the whole batch.
  - Prevents two concurrent upload operations from running simultaneously.
  - `FileNotFoundException` fails immediately without burning 3 × 18 s retry cycles.
  - Per-conversation and per-file error recovery — failures are counted and skipped, the rest continue.

---

## How to Use

### 1 — Export from ChatGPT

Go to **ChatGPT → Settings → Data Controls → Export Data**. ChatGPT sends an email with a download link. Save the `.zip` file to your phone.

### 2 — Open the app

Tap **SELECT CHATGPT EXPORT (ZIP)** and pick the file, or share the ZIP directly to the app from your Files app.

The app scans the ZIP, extracts media, and shows the conversation count.

### 3 — Choose an action

| Button | What it does |
|--------|-------------|
| **UPLOAD TO DRIVE** | Signs in to Google, uploads all media to `ChatGPT Archive/Media/` in Drive, then creates one Google Doc per conversation with Drive links to the media. |
| **SAVE LOCALLY** | Saves all conversations as standalone HTML files (with base64-embedded images) to `Downloads/ChatGPT_Archive/`. |

### 4 — Wait for completion

Both operations run in the background. The notification keeps the service alive even when the app is not visible. The progress cards update in real time when you return to the app.

---

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | Upload to Google Drive |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` | Keep the upload alive when the app is in the background |
| `POST_NOTIFICATIONS` | Show the upload progress notification (required on Android 13+) |

---

## Architecture

| File | Role |
|---|---|
| `MainActivity.kt` | UI, Google Sign-In, starts the service |
| `UploadService.kt` | Foreground service — ZIP scan, media pre-upload, conversation upload, local save |
| `ChatGPTConverter.kt` | Parses `conversations.json` lazily; emits one `ConversationFile` at a time to keep heap usage low |
| `DriveUploader.kt` | Google Drive API wrapper with exponential back-off retry (HTTP 500/503/429, `IOException`) |
| `ProgressState.kt` | Singleton `StateFlow` — bridges progress updates from the service to the Activity |
| `UploadState.kt` | Singleton that holds raw export data (file references, media map) between the scan and upload steps |
| `AppLogger.kt` | In-memory ring-buffer logger (max 300 entries). Privacy-safe: never logs conversation content |

---

## Building

### Run from source (development)

1. Open the project in **Android Studio** (Electric Eel or newer).
2. Connect your Android device (USB debugging enabled) or start an emulator.
3. Click **Run ▶**.

### Build a signed release APK (for distribution)

1. In Android Studio go to **Build → Generate Signed Bundle / APK**.
2. Choose **APK**, then select or create a keystore.
3. Select the **release** build variant and click **Finish**.
4. The APK is written to `app/release/app-release.apk`.
5. Upload it as an asset to a new GitHub Release (`git tag v1.x && git push --tags`, then create the release on GitHub).

**Minimum requirements:**

| | |
|---|---|
| Android version | 7.0 Nougat (API 24) or higher |
| Target SDK | 35 (Android 15) |
| Language | Kotlin, coroutines |
| Build tool | Gradle with Kotlin DSL |

**Key dependencies:**

- `com.google.android.gms:play-services-auth` — Google Sign-In
- `com.google.api-client:google-api-client-android` — Google API client
- `com.google.apis:google-api-services-drive:v3` — Drive API
- `com.google.android.material:material` — Material Design 3 components

---

## Google Drive output structure

```
My Drive/
└── ChatGPT Archive/
    ├── 2024-03-15_My first chat.gdoc
    ├── 2024-03-16_Another chat.gdoc
    ├── …
    └── Media/
        ├── file_abc123.png
        ├── file_def456.jpg
        └── …
```

Each Google Doc contains the full conversation thread. Images and videos appear as styled clickable links that open the corresponding file in the `Media` folder — so Gemini can follow the link when reading the document.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Count jumps to 40,000+ | Stale scan from an older app version | Tap **SELECT CHATGPT EXPORT (ZIP)** again to rescan |
| "No conversation JSON files found" | ZIP is not a ChatGPT export, or uses an unknown structure | Long-press the error to copy debug info; it lists all JSON files found |
| Upload stops after N documents | Out of memory on a very large export | Documents are now skipped individually rather than stopping the batch; check the skipped count in the completion message |
| FileNotFoundException on media | Two uploads ran at the same time and the first deleted the cache | Only one operation runs at a time now; re-scan and retry |
| 0 conversations uploaded | Conversations in this ZIP have empty `mapping` fields (summary-only export) | This is a ChatGPT export format limitation; the full conversation data may be in the larger numbered files |
