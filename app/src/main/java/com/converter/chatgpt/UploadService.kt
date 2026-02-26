package com.converter.chatgpt

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * Foreground service that keeps upload/processing alive when the app moves to the background.
 *
 * Crash-safety notes:
 * - [isOperationRunning] prevents two concurrent instances from running simultaneously.
 *   If the user presses Upload while an upload is already in progress, the second request
 *   is silently dropped. This avoids the race where the first instance clears the cache
 *   while the second is still reading from it.
 * - All coroutine bodies catch [Throwable] (not just Exception) so OutOfMemoryError and
 *   other JVM Errors are handled gracefully and reported in the UI.
 * - [FileNotFoundException] in [withRetry] is treated as permanent (not retried), saving
 *   the 3×18s retry penalty for files that will never appear.
 * - Individual document/file failures are caught and skipped; one bad item never stops
 *   the whole batch.
 * - [AppLogger] records all significant events. NO user data (content, titles, account
 *   names) is ever written to the log.
 */
class UploadService : Service() {

    companion object {
        const val ACTION_SCAN_ZIP = "com.converter.chatgpt.SCAN_ZIP"
        const val ACTION_UPLOAD   = "com.converter.chatgpt.UPLOAD"
        const val ACTION_SAVE     = "com.converter.chatgpt.SAVE"
        const val ACTION_STOP     = "com.converter.chatgpt.STOP"
        const val EXTRA_ZIP_URI   = "extra_zip_uri"

        private const val CHANNEL_ID = "upload_progress"
        private const val NOTIF_ID   = 1001
        private const val CHUNK_SIZE = 10

        private const val TAG = "UploadService"

        /**
         * Guards against two concurrent operations (e.g. user double-taps Upload).
         * The first instance sets this true; the second sees it and drops the request.
         * Cleared in the finally block so a fresh operation can start after completion.
         */
        @Volatile private var isOperationRunning = false
    }

    /** Catches any Throwable that escapes a launch {} and maps it to a visible ERROR state. */
    private val exceptionHandler = CoroutineExceptionHandler { _, t ->
        AppLogger.e(TAG, "Uncaught coroutine exception: ${t.javaClass.name}", t)
        isOperationRunning = false
        ProgressState.update(ProgressState.Progress(
            phase        = ProgressState.Phase.ERROR,
            errorMessage = userMessage(t),
            debugInfo    = debugInfo(t)
        ))
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        AppLogger.i(TAG, "Service created")
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: "(null)"
        AppLogger.i(TAG, "onStartCommand action=$action")
        when (intent?.action) {
            ACTION_SCAN_ZIP -> {
                val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(EXTRA_ZIP_URI, Uri::class.java)
                else
                    @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_ZIP_URI)
                if (uri != null) doScanZip(uri)
                else {
                    AppLogger.e(TAG, "ACTION_SCAN_ZIP received but URI is null")
                    stopSelf()
                }
            }
            ACTION_UPLOAD -> doUpload()
            ACTION_SAVE   -> doSave()
            ACTION_STOP   -> {
                AppLogger.i(TAG, "Stop requested by user")
                isOperationRunning = false
                serviceScope.coroutineContext.cancelChildren()
                stopSelf()
            }
            else -> {
                AppLogger.w(TAG, "Unknown action '$action' — stopping")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        AppLogger.i(TAG, "Service destroyed (log entries: ${AppLogger.entryCount()})")
        super.onDestroy()
    }

    // ── Phase 1: Scan ZIP ─────────────────────────────────────────────────────

    private fun doScanZip(uri: Uri) {
        if (isOperationRunning) {
            AppLogger.w(TAG, "doScanZip skipped — another operation is already running")
            stopSelf(); return
        }
        isOperationRunning = true
        startForegroundCompat("Scanning ZIP…")
        ProgressState.operationStartMs = System.currentTimeMillis()
        ProgressState.update(ProgressState.Progress(
            phase = ProgressState.Phase.SCANNING, message = "Reading ZIP file…"
        ))
        AppLogger.i(TAG, "doScanZip started")

        serviceScope.launch {
            var tmpZip: File? = null
            try {
                tmpZip = File.createTempFile("chatgpt_export", ".zip", cacheDir)
                val copyStart = System.currentTimeMillis()
                contentResolver.openInputStream(uri)?.use { it.copyTo(FileOutputStream(tmpZip)) }
                AppLogger.i(TAG, "ZIP copied in ${System.currentTimeMillis() - copyStart}ms, size=${tmpZip.length()}B")

                ZipFile(tmpZip).use { zip ->
                    val allEntries = zip.entries().toList()
                    AppLogger.i(TAG, "ZIP has ${allEntries.size} entries total")

                    // ── Extract media files ───────────────────────────────
                    val mediaDir = File(cacheDir, "extracted_media").also {
                        it.deleteRecursively(); it.mkdirs()
                    }
                    val imageExts = listOf(".jpg", ".jpeg", ".png", ".webp", ".gif")
                    val videoExts = listOf(".mp4", ".mov", ".webm")
                    val mediaExts = imageExts + videoExts

                    val allMedia  = mutableListOf<File>()
                    val videoOnly = mutableListOf<File>()
                    val mediaEntries = allEntries.filter { e ->
                        !e.isDirectory && mediaExts.any { e.name.endsWith(it, true) }
                    }
                    AppLogger.i(TAG, "Media entries to extract: ${mediaEntries.size}")

                    for ((i, entry) in mediaEntries.withIndex()) {
                        try {
                            val dest = File(mediaDir, File(entry.name).name)
                            zip.getInputStream(entry).use { it.copyTo(FileOutputStream(dest)) }
                            allMedia.add(dest)
                            if (videoExts.any { entry.name.endsWith(it, true) }) videoOnly.add(dest)
                        } catch (t: Throwable) {
                            AppLogger.w(TAG, "Failed to extract media entry #$i (${t.javaClass.simpleName})", t)
                        }

                        if (i % 20 == 0) {
                            ProgressState.update(ProgressState.Progress(
                                phase   = ProgressState.Phase.SCANNING,
                                current = i + 1, total = mediaEntries.size,
                                message = "Extracting media ${i + 1} / ${mediaEntries.size}…"
                            ))
                            updateNotif("Extracting media ${i + 1} / ${mediaEntries.size}…")
                        }
                    }
                    AppLogger.i(TAG, "Extracted ${allMedia.size} media files (${videoOnly.size} videos)")

                    // ── Find conversation JSON files ───────────────────────
                    val conversationEntries = run {
                        val exact = allEntries.filter { !it.isDirectory &&
                                it.name.endsWith("conversations.json") }
                        if (exact.isNotEmpty()) {
                            AppLogger.i(TAG, "Found ${exact.size} conversations.json (old format)")
                            return@run exact.sortedBy { it.name }
                        }
                        val numbered = allEntries.filter { !it.isDirectory &&
                                Regex(""".*conversations-\d+\.json$""", RegexOption.IGNORE_CASE)
                                    .matches(it.name) }
                        if (numbered.isNotEmpty()) {
                            AppLogger.i(TAG, "Found ${numbered.size} numbered conversation files (new format)")
                            return@run numbered.sortedBy { it.name }
                        }
                        val fuzzy = allEntries.filter { !it.isDirectory &&
                                it.name.contains("conversations", ignoreCase = true) &&
                                it.name.endsWith(".json", ignoreCase = true) }
                        if (fuzzy.isNotEmpty()) {
                            AppLogger.w(TAG, "Fuzzy match: ${fuzzy.size} JSON file(s) with 'conversations'")
                            return@run fuzzy.sortedBy { it.name }
                        }
                        val allJsonNames = allEntries
                            .filter { !it.isDirectory && it.name.endsWith(".json", ignoreCase = true) }
                            .map { it.name }
                        AppLogger.e(TAG, "No conversation JSON found. JSON files: $allJsonNames")
                        val desc = if (allJsonNames.isNotEmpty())
                            "\nJSON files in ZIP:\n" + allJsonNames.joinToString("\n") { "  • $it" }
                        else "\nNo JSON files found at all."
                        throw Exception("No conversation JSON files found in ZIP.$desc")
                    }

                    ProgressState.update(ProgressState.Progress(
                        phase = ProgressState.Phase.SCANNING,
                        message = "Counting conversations in ${conversationEntries.size} file(s)…"
                    ))

                    // ── Save each part and count ──────────────────────────
                    val jsonFiles      = mutableListOf<File>()
                    var totalConvCount = 0

                    for ((idx, entry) in conversationEntries.withIndex()) {
                        try {
                            val partFile = File(cacheDir, "conversations_part_$idx.json")
                            zip.getInputStream(entry).use { it.copyTo(FileOutputStream(partFile)) }
                            AppLogger.d(TAG, "Part $idx: saved ${partFile.length()}B")
                            val count = ChatGPTConverter().countConversations(partFile.readText())
                            AppLogger.i(TAG, "Part $idx: $count conversations")
                            totalConvCount += count
                            jsonFiles.add(partFile)
                            System.gc()
                        } catch (t: Throwable) {
                            AppLogger.e(TAG, "Failed to process part #$idx (${t.javaClass.name})", t)
                        }
                    }

                    if (jsonFiles.isEmpty()) {
                        throw Exception("All conversation files failed to load " +
                                "(${conversationEntries.size} attempted).")
                    }

                    UploadState.pendingJsonFiles  = jsonFiles
                    UploadState.pendingMediaMap   = allMedia.associateBy { it.nameWithoutExtension }
                    UploadState.pendingConvCount  = totalConvCount
                    UploadState.pendingVideoFiles = videoOnly

                    AppLogger.i(TAG, "Scan complete: $totalConvCount conversations, ${allMedia.size} media")
                    val mediaNote = if (allMedia.isNotEmpty()) " + ${allMedia.size} media" else ""
                    ProgressState.update(ProgressState.Progress(
                        phase      = ProgressState.Phase.DONE,
                        current    = totalConvCount, total = totalConvCount,
                        mediaTotal = allMedia.size, mediaCurrent = 0,
                        message    = "Found $totalConvCount conversations$mediaNote. Choose an action below.",
                        afterScan  = true
                    ))
                }
            } catch (e: java.util.zip.ZipException) {
                AppLogger.e(TAG, "ZIP format error", e)
                ProgressState.update(ProgressState.Progress(
                    phase        = ProgressState.Phase.ERROR,
                    errorMessage = "Invalid ZIP file. Please select a valid ChatGPT export.",
                    debugInfo    = debugInfo(e)
                ))
            } catch (t: Throwable) {
                AppLogger.e(TAG, "Scan failed: ${t.javaClass.name}", t)
                ProgressState.update(ProgressState.Progress(
                    phase        = ProgressState.Phase.ERROR,
                    errorMessage = userMessage(t),
                    debugInfo    = debugInfo(t)
                ))
            } finally {
                tmpZip?.delete()
                isOperationRunning = false
                AppLogger.i(TAG, "doScanZip finally — tmp ZIP deleted, stopping service")
                stopSelf()
            }
        }
    }

    // ── Phase 2a: Upload to Google Drive ──────────────────────────────────────

    private fun doUpload() {
        if (isOperationRunning) {
            AppLogger.w(TAG, "doUpload skipped — another operation is already running")
            stopSelf(); return
        }
        isOperationRunning = true
        startForegroundCompat("Uploading to Google Drive…")
        ProgressState.operationStartMs = System.currentTimeMillis()
        AppLogger.i(TAG, "doUpload started")

        serviceScope.launch {
            try {
                val account = UploadState.googleAccount
                    ?: throw IllegalStateException("Not signed in to Google")
                val jsonFiles = UploadState.pendingJsonFiles.takeIf { it.isNotEmpty() }
                    ?: throw IllegalStateException("No conversations loaded — please scan a ZIP first")
                val mediaMap = UploadState.pendingMediaMap
                val total    = UploadState.pendingConvCount

                AppLogger.i(TAG, "Upload: convTotal=$total parts=${jsonFiles.size} media=${mediaMap.size}")

                val uploader  = DriveUploader(this@UploadService, account)
                val converter = ChatGPTConverter()

                AppLogger.d(TAG, "Creating/finding Drive folder 'ChatGPT Archive'")
                val rootId = uploader.findOrCreateFolder("ChatGPT Archive")
                AppLogger.i(TAG, "Drive root folder ready")

                // ── Step 1: Pre-upload all media files ────────────────────
                val driveFileIds = mutableMapOf<String, String>()
                if (mediaMap.isNotEmpty()) {
                    AppLogger.i(TAG, "Pre-uploading ${mediaMap.size} media file(s)")
                    val mediaFolderId = uploader.findOrCreateFolder("Media", rootId)
                    var mediaUploaded = 0
                    var mediaFailed   = 0

                    // Record start time for the media card's own timer
                    ProgressState.mediaStartMs = System.currentTimeMillis()

                    // Emit immediately so the media card appears; message is intentionally
                    // empty here — it belongs to the conversations card, not the media card.
                    ProgressState.update(ProgressState.Progress(
                        phase        = ProgressState.Phase.UPLOADING,
                        current      = 0, total = total,
                        mediaCurrent = 0, mediaTotal = mediaMap.size,
                        message      = ""
                    ))
                    updateNotif("Uploading media 0 / ${mediaMap.size}…")

                    for ((baseName, localFile) in mediaMap) {
                        // Skip files that don't exist on disk (e.g. name clash overwrote them)
                        if (!localFile.exists() || localFile.length() == 0L) {
                            AppLogger.w(TAG, "Skipping missing/empty media file (exists=${localFile.exists()} size=${localFile.length()})")
                            mediaFailed++
                        } else {
                            try {
                                val driveId = uploader.uploadMediaFile(localFile, mediaFolderId)
                                driveFileIds[baseName] = driveId
                                mediaUploaded++
                            } catch (t: Throwable) {
                                mediaFailed++
                                AppLogger.w(TAG, "Media upload failed #${mediaUploaded + mediaFailed} " +
                                        "(${t.javaClass.simpleName})", t)
                            }
                        }

                        val done = mediaUploaded + mediaFailed
                        if (done % 5 == 0 || done == mediaMap.size) {
                            ProgressState.update(ProgressState.Progress(
                                phase        = ProgressState.Phase.UPLOADING,
                                current      = 0, total = total,
                                mediaCurrent = done, mediaTotal = mediaMap.size,
                                message      = ""
                            ))
                            updateNotif("Uploading media $done / ${mediaMap.size}…")
                        }
                    }
                    AppLogger.i(TAG, "Media pre-upload done: $mediaUploaded ok, $mediaFailed skipped, " +
                            "${driveFileIds.size} IDs mapped")
                }

                // ── Step 2: Upload conversation documents ─────────────────
                var uploaded = 0
                var skipped  = 0

                for ((partIdx, jsonFile) in jsonFiles.withIndex()) {
                    AppLogger.i(TAG, "Processing part ${partIdx + 1}/${jsonFiles.size} (${jsonFile.length()}B)")

                    if (!jsonFile.exists()) {
                        AppLogger.e(TAG, "Part file $partIdx missing from disk — skipping")
                        continue
                    }

                    val json = try {
                        jsonFile.readText()
                    } catch (t: Throwable) {
                        AppLogger.e(TAG, "Cannot read part $partIdx (${t.javaClass.simpleName}), skipping", t)
                        continue
                    }

                    var chunkCount   = 0
                    var partUploaded = 0

                    for (file in converter.parseConversationsLazily(json, mediaMap, driveFileIds)) {
                        val title = file.docTitle.ifBlank { file.filename.removeSuffix(".html") }
                        try {
                            uploader.uploadAsGoogleDoc(title, file.content, rootId)
                            uploaded++
                            partUploaded++
                        } catch (t: Throwable) {
                            skipped++
                            AppLogger.w(TAG, "Doc skipped (${t.javaClass.simpleName}): " +
                                    "${t.message?.take(120)}", t)
                        }

                        chunkCount++
                        val skipNote = if (skipped > 0) " ($skipped skipped)" else ""
                        ProgressState.update(ProgressState.Progress(
                            phase        = ProgressState.Phase.UPLOADING,
                            current      = uploaded + skipped, total = total,
                            mediaCurrent = driveFileIds.size, mediaTotal = mediaMap.size,
                            message      = "Uploaded $uploaded / $total$skipNote"
                        ))
                        if ((uploaded + skipped) % 5 == 0)
                            updateNotif("Uploading ${uploaded + skipped} / $total…")

                        if (chunkCount >= CHUNK_SIZE) {
                            chunkCount = 0
                            System.gc()
                            delay(200)
                        }
                    }

                    AppLogger.i(TAG, "Part $partIdx done: $partUploaded uploaded")
                    System.gc()
                }

                val elapsedSec = (System.currentTimeMillis() - ProgressState.operationStartMs) / 1000
                AppLogger.i(TAG, "Upload complete: docs=$uploaded skipped=$skipped " +
                        "media=${driveFileIds.size} elapsed=${elapsedSec}s")

                clearCache()
                val skipNote  = if (skipped > 0) " ($skipped skipped)" else ""
                val mediaNote = if (driveFileIds.isNotEmpty()) " + ${driveFileIds.size} media" else ""
                ProgressState.update(ProgressState.Progress(
                    phase        = ProgressState.Phase.DONE,
                    current      = uploaded, total = total,
                    mediaCurrent = driveFileIds.size, mediaTotal = mediaMap.size,
                    message      = "Done! $uploaded docs$skipNote$mediaNote in 'ChatGPT Archive'.\n" +
                                   "Images and videos are linked from the documents.",
                    afterScan    = false
                ))
            } catch (t: Throwable) {
                AppLogger.e(TAG, "doUpload failed: ${t.javaClass.name}", t)
                ProgressState.update(ProgressState.Progress(
                    phase        = ProgressState.Phase.ERROR,
                    errorMessage = "Upload error: ${userMessage(t)}",
                    debugInfo    = debugInfo(t)
                ))
            } finally {
                isOperationRunning = false
                AppLogger.i(TAG, "doUpload finally — stopping service")
                stopSelf()
            }
        }
    }

    // ── Phase 2b: Save to Downloads ───────────────────────────────────────────

    private fun doSave() {
        if (isOperationRunning) {
            AppLogger.w(TAG, "doSave skipped — another operation is already running")
            stopSelf(); return
        }
        isOperationRunning = true
        startForegroundCompat("Saving to Downloads…")
        ProgressState.operationStartMs = System.currentTimeMillis()
        AppLogger.i(TAG, "doSave started")

        serviceScope.launch {
            try {
                val jsonFiles = UploadState.pendingJsonFiles.takeIf { it.isNotEmpty() }
                    ?: throw IllegalStateException("No conversations loaded — please scan a ZIP first")
                val mediaMap = UploadState.pendingMediaMap
                val total    = UploadState.pendingConvCount

                AppLogger.i(TAG, "Save: total=$total parts=${jsonFiles.size}")

                var saved = 0

                for ((partIdx, jsonFile) in jsonFiles.withIndex()) {
                    AppLogger.i(TAG, "Saving part ${partIdx + 1}/${jsonFiles.size} (${jsonFile.length()}B)")

                    if (!jsonFile.exists()) {
                        AppLogger.e(TAG, "Part file $partIdx missing from disk — skipping")
                        continue
                    }

                    val json = try {
                        jsonFile.readText()
                    } catch (t: Throwable) {
                        AppLogger.e(TAG, "Cannot read part $partIdx (${t.javaClass.simpleName}), skipping", t)
                        continue
                    }

                    var chunkCount = 0
                    var partSaved  = 0

                    for (file in ChatGPTConverter().parseConversationsLazily(json, mediaMap)) {
                        try {
                            val values = ContentValues().apply {
                                put(MediaStore.MediaColumns.DISPLAY_NAME, file.filename)
                                put(MediaStore.MediaColumns.MIME_TYPE, "text/html")
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    put(MediaStore.MediaColumns.RELATIVE_PATH,
                                        "${Environment.DIRECTORY_DOWNLOADS}/ChatGPT_Archive")
                                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                                }
                            }
                            val fileUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                                contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                            else null

                            if (fileUri != null) {
                                contentResolver.openOutputStream(fileUri)?.use {
                                    it.write(file.content.toByteArray(Charsets.UTF_8))
                                }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    values.clear()
                                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                                    contentResolver.update(fileUri, values, null, null)
                                }
                                saved++
                                partSaved++
                            } else {
                                AppLogger.w(TAG, "MediaStore insert returned null URI, skipping")
                            }
                        } catch (t: Throwable) {
                            AppLogger.w(TAG, "Failed to save one file (${t.javaClass.simpleName})", t)
                        }

                        chunkCount++
                        ProgressState.update(ProgressState.Progress(
                            phase   = ProgressState.Phase.SAVING,
                            current = saved, total = total,
                            message = "Saving file $saved / $total…"
                        ))
                        if (saved % 10 == 0) updateNotif("Saving $saved / $total…")

                        if (chunkCount >= CHUNK_SIZE) {
                            chunkCount = 0
                            System.gc()
                            delay(100)
                        }
                    }

                    AppLogger.i(TAG, "Part $partIdx done: $partSaved saved")
                    System.gc()
                }

                val elapsedSec = (System.currentTimeMillis() - ProgressState.operationStartMs) / 1000
                AppLogger.i(TAG, "Save complete: saved=$saved elapsed=${elapsedSec}s")

                clearCache()
                ProgressState.update(ProgressState.Progress(
                    phase     = ProgressState.Phase.DONE,
                    current   = saved, total = total,
                    message   = "Saved $saved HTML files to Downloads/ChatGPT_Archive.",
                    afterScan = false
                ))
            } catch (t: Throwable) {
                AppLogger.e(TAG, "doSave failed: ${t.javaClass.name}", t)
                ProgressState.update(ProgressState.Progress(
                    phase        = ProgressState.Phase.ERROR,
                    errorMessage = "Save error: ${userMessage(t)}",
                    debugInfo    = debugInfo(t)
                ))
            } finally {
                isOperationRunning = false
                AppLogger.i(TAG, "doSave finally — stopping service")
                stopSelf()
            }
        }
    }

    // ── Error message helpers ─────────────────────────────────────────────────

    private fun userMessage(t: Throwable): String = when (t) {
        is OutOfMemoryError ->
            "Out of memory — the export is too large for this device's RAM. Try a smaller ZIP."
        is java.net.UnknownHostException ->
            "No internet connection. Check your network and retry."
        is java.net.SocketTimeoutException ->
            "Connection timed out. Check your network and retry."
        is SecurityException ->
            "Permission denied (${t.javaClass.simpleName})."
        is IllegalStateException ->
            t.message ?: t.javaClass.simpleName
        is com.google.api.client.googleapis.json.GoogleJsonResponseException ->
            "Google API error ${t.statusCode}: ${t.statusMessage}"
        else ->
            "${t.javaClass.simpleName}: ${t.message?.take(200) ?: "(no message)"}"
    }

    private fun debugInfo(t: Throwable): String {
        val sb = StringBuilder()
        sb.append("${t.javaClass.name}: ${t.message?.take(300)}\n")
        t.stackTrace.take(10).forEach { sb.append("  at $it\n") }
        var cause = t.cause; var depth = 0
        while (cause != null && depth < 3) {
            sb.append("Caused by ${cause.javaClass.name}: ${cause.message?.take(200)}\n")
            cause.stackTrace.take(5).forEach { sb.append("  at $it\n") }
            cause = cause.cause; depth++
        }
        sb.append("\n--- Recent log ---\n")
        sb.append(AppLogger.dump(40))
        return sb.toString()
    }

    // ── Service helpers ───────────────────────────────────────────────────────

    private fun startForegroundCompat(text: String) {
        val notif = buildNotif(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun clearCache() {
        try { File(cacheDir, "extracted_media").deleteRecursively() } catch (_: Exception) {}
        UploadState.clearData()
        AppLogger.d(TAG, "Cache cleared")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                CHANNEL_ID, "Upload Progress", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows ChatGPT → Gemini conversion progress"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
        }
    }

    private fun buildNotif(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ChatGPT → Gemini")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    private fun updateNotif(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotif(text))
    }
}
