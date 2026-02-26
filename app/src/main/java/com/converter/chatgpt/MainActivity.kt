package com.converter.chatgpt

import android.Manifest
import android.accounts.Account
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var selectFileButton:  MaterialButton
    private lateinit var mediaCard:         MaterialCardView
    private lateinit var mediaPhaseLabel:   TextView
    private lateinit var mediaProgressBar:  LinearProgressIndicator
    private lateinit var mediaProgressCount: TextView
    private lateinit var progressCard:     MaterialCardView
    private lateinit var phaseLabel:       TextView
    private lateinit var progressBar:      LinearProgressIndicator
    private lateinit var progressCount:    TextView
    private lateinit var timerText:        TextView
    private lateinit var statusText:       TextView
    private lateinit var actionButtons:    LinearLayout
    private lateinit var btnSave:          MaterialButton
    private lateinit var btnUpload:        MaterialButton

    private var timerJob: Job? = null

    // ZIP picker
    private val pickZip = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { startScan(it) }
    }

    // Google Sign-In
    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(Exception::class.java)
            account?.account?.let { startUpload(it) }
        } catch (e: Exception) {
            showToast("Sign-in failed: ${e.message}")
            setButtonsEnabled(true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        selectFileButton  = findViewById(R.id.selectFileButton)
        mediaCard         = findViewById(R.id.mediaCard)
        mediaPhaseLabel   = findViewById(R.id.mediaPhaseLabel)
        mediaProgressBar  = findViewById(R.id.mediaProgressBar)
        mediaProgressCount = findViewById(R.id.mediaProgressCount)
        progressCard      = findViewById(R.id.progressCard)
        phaseLabel        = findViewById(R.id.phaseLabel)
        progressBar       = findViewById(R.id.progressBar)
        progressCount     = findViewById(R.id.progressCount)
        timerText         = findViewById(R.id.timerText)
        statusText        = findViewById(R.id.statusText)
        actionButtons     = findViewById(R.id.actionButtonsLayout)
        btnSave           = findViewById(R.id.btnSaveToDownloads)
        btnUpload         = findViewById(R.id.btnShareToDrive)

        selectFileButton.setOnClickListener { pickZip.launch("application/zip") }
        btnSave.setOnClickListener          { startSave() }
        btnUpload.setOnClickListener        { requestSignInThenUpload() }

        // Request POST_NOTIFICATIONS permission (Android 13+).
        // Without it the foreground service notification is never shown, and the OS
        // is free to kill the service when the app moves to the background.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }

        // Push the action-button bar above the system navigation bar.
        // Material3/NoActionBar themes run edge-to-edge, so without this the
        // nav bar overlaps and hides the buttons.
        val pad = (12 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(actionButtons) { v, windowInsets ->
            val nav = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(pad, pad, pad, nav.bottom + pad)
            windowInsets
        }

        // Observe progress from the service (survives backgrounding)
        lifecycleScope.launch {
            ProgressState.progress.collectLatest { p -> applyProgress(p) }
        }

        // Handle share-to-app intent
        if (intent?.action == Intent.ACTION_SEND) {
            val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else
                @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            uri?.let { startScan(it) }
        }

        // Restore UI if a previous session's result is still in ProgressState
        applyProgress(ProgressState.progress.value)
    }

    // ── Progress observer ─────────────────────────────────────────────────────

    private fun applyProgress(p: ProgressState.Progress) {
        // Update the media card whenever we have media totals
        applyMediaCard(p)

        when (p.phase) {
            ProgressState.Phase.IDLE -> {
                progressCard.visibility  = View.GONE
                mediaCard.visibility     = View.GONE
                actionButtons.visibility = View.GONE
                statusText.setOnLongClickListener(null)
                stopTimer()
            }

            ProgressState.Phase.SCANNING -> {
                showProgressCard(
                    title = "📂 Scanning ZIP…",
                    indeterminate = (p.total == 0),
                    current = p.current, total = p.total,
                    message = p.message
                )
                startTimerIfNeeded()
                actionButtons.visibility = View.GONE
                setButtonsEnabled(false)
            }

            ProgressState.Phase.UPLOADING -> {
                showProgressCard(
                    title = "💬 Uploading conversations…",
                    indeterminate = (p.mediaCurrent < p.mediaTotal), // indeterminate while media uploads
                    current = p.current, total = p.total,
                    message = p.message
                )
                startTimerIfNeeded()
                actionButtons.visibility = View.GONE
                setButtonsEnabled(false)
            }

            ProgressState.Phase.SAVING -> {
                showProgressCard(
                    title = "💾 Saving to Downloads…",
                    indeterminate = false,
                    current = p.current, total = p.total,
                    message = p.message
                )
                startTimerIfNeeded()
                actionButtons.visibility = View.GONE
                setButtonsEnabled(false)
            }

            ProgressState.Phase.DONE -> {
                stopTimer()
                if (p.afterScan) {
                    showProgressCard(
                        title = "✅ Ready",
                        indeterminate = false,
                        current = p.current, total = p.total,
                        message = p.message
                    )
                    actionButtons.visibility = View.VISIBLE
                    setButtonsEnabled(true)
                } else {
                    showProgressCard(
                        title = "✅ Complete",
                        indeterminate = false,
                        current = p.current, total = p.total,
                        message = p.message
                    )
                    actionButtons.visibility = View.GONE
                    setButtonsEnabled(true)
                    showToast("Done!")
                }
            }

            ProgressState.Phase.ERROR -> {
                stopTimer()
                val hint = if (p.debugInfo.isNotEmpty()) "\n\n(Long-press for debug info)" else ""
                showProgressCard(
                    title = "⚠️ Error",
                    indeterminate = false,
                    current = 0, total = 0,
                    message = p.errorMessage + hint
                )
                phaseLabel.setTextColor(getColor(android.R.color.holo_red_dark))
                actionButtons.visibility = View.GONE
                setButtonsEnabled(true)

                statusText.setOnLongClickListener {
                    val clip = buildString {
                        append("=== Error ===\n${p.errorMessage}\n\n")
                        if (p.debugInfo.isNotEmpty()) append("=== Stack ===\n${p.debugInfo}\n\n")
                        append(AppLogger.dump())
                    }
                    val cm = getSystemService(ClipboardManager::class.java)
                    cm.setPrimaryClip(ClipData.newPlainText("Debug log", clip))
                    showToast("Debug info copied to clipboard")
                    true
                }
            }
        }
    }

    /** Shows or updates the media upload progress card. Hidden when there are no media files. */
    private fun applyMediaCard(p: ProgressState.Progress) {
        if (p.mediaTotal <= 0) {
            mediaCard.visibility = View.GONE
            return
        }
        mediaCard.visibility = View.VISIBLE
        val done = p.mediaCurrent >= p.mediaTotal
        mediaPhaseLabel.text = if (done) "✅ Media uploaded" else "🖼️ Uploading media…"
        mediaPhaseLabel.setTextColor(
            getColor(if (done) android.R.color.holo_green_dark else android.R.color.holo_blue_dark)
        )
        if (done) {
            mediaProgressBar.isIndeterminate = false
            mediaProgressBar.max      = p.mediaTotal
            mediaProgressBar.progress = p.mediaTotal
        } else {
            mediaProgressBar.isIndeterminate = p.mediaCurrent == 0
            if (p.mediaCurrent > 0) {
                mediaProgressBar.isIndeterminate = false
                mediaProgressBar.max      = p.mediaTotal
                mediaProgressBar.progress = p.mediaCurrent
            }
        }
        val pct = if (p.mediaTotal > 0) p.mediaCurrent * 100 / p.mediaTotal else 0
        mediaProgressCount.text = "${p.mediaCurrent} / ${p.mediaTotal}  ($pct%)"
    }

    private fun showProgressCard(
        title: String,
        indeterminate: Boolean,
        current: Int,
        total: Int,
        message: String
    ) {
        progressCard.visibility = View.VISIBLE
        phaseLabel.text         = title
        phaseLabel.setTextColor(getColor(android.R.color.holo_blue_dark))
        statusText.text         = message

        if (indeterminate || total <= 0) {
            progressBar.isIndeterminate = true
            progressCount.text = if (total > 0) "$current / $total" else "…"
        } else {
            progressBar.isIndeterminate = false
            progressBar.max             = total
            progressBar.progress        = current
            val pct = (current * 100 / total)
            progressCount.text = "$current / $total  ($pct%)"
        }
    }

    // ── Timer ─────────────────────────────────────────────────────────────────

    private fun startTimerIfNeeded() {
        if (timerJob?.isActive == true) return
        // Read start time from ProgressState (set by the service), not from a local field.
        // This ensures the timer shows the correct total elapsed time even after the Activity
        // was paused/resumed, which would have cancelled the previous timerJob.
        timerJob = lifecycleScope.launch {
            while (true) {
                val elapsed = (System.currentTimeMillis() - ProgressState.operationStartMs) / 1000
                val mins    = elapsed / 60
                val secs    = elapsed % 60
                timerText.text = "⏱ %02d:%02d".format(mins, secs)
                delay(1000)
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    // ── Scan ZIP ──────────────────────────────────────────────────────────────

    private fun startScan(uri: Uri) {
        ProgressState.reset()
        UploadState.clearData()
        setButtonsEnabled(false)

        val intent = Intent(this, UploadService::class.java).apply {
            action = UploadService.ACTION_SCAN_ZIP
            putExtra(UploadService.EXTRA_ZIP_URI, uri)
        }
        startService(intent)
    }

    // ── Upload to Google Drive ────────────────────────────────────────────────

    private fun requestSignInThenUpload() {
        if (!UploadState.isReady) return
        setButtonsEnabled(false)

        val existing = GoogleSignIn.getLastSignedInAccount(this)
        if (existing?.account != null &&
            GoogleSignIn.hasPermissions(existing, Scope(DriveScopes.DRIVE_FILE))) {
            startUpload(existing.account!!)
            return
        }

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_FILE))
            .build()
        signInLauncher.launch(GoogleSignIn.getClient(this, gso).signInIntent)
    }

    private fun startUpload(account: Account) {
        UploadState.googleAccount = account
        val intent = Intent(this, UploadService::class.java).apply {
            action = UploadService.ACTION_UPLOAD
        }
        startService(intent)
    }

    // ── Save to Downloads ─────────────────────────────────────────────────────

    private fun startSave() {
        if (!UploadState.isReady) return
        setButtonsEnabled(false)
        val intent = Intent(this, UploadService::class.java).apply {
            action = UploadService.ACTION_SAVE
        }
        startService(intent)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun setButtonsEnabled(enabled: Boolean) {
        selectFileButton.isEnabled = enabled
        btnSave.isEnabled          = enabled
        btnUpload.isEnabled        = enabled
    }

    private fun showToast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
