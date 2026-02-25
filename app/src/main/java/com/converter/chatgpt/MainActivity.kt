package com.converter.chatgpt

import android.accounts.Account
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var selectFileButton: Button
    private lateinit var actionButtonsLayout: LinearLayout
    private lateinit var btnSaveToDownloads: Button
    private lateinit var btnUploadToDrive: Button

    private var pendingFiles: List<ChatGPTConverter.ConversationFile> = emptyList()
    private var extractedMediaFiles: MutableList<File> = mutableListOf()

    // ZIP picker
    private val pickZip = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { processZip(it) }
    }
    // Google Sign-In result
    private val signInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(Exception::class.java)
            account?.account?.let { uploadToDriveWithAccount(it) }
        } catch (e: Exception) {
            statusText.text = "Sign-in failed: ${e.message}"
            setButtonsEnabled(true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText        = findViewById(R.id.statusText)
        selectFileButton  = findViewById(R.id.selectFileButton)
        actionButtonsLayout = findViewById(R.id.actionButtonsLayout)
        btnSaveToDownloads  = findViewById(R.id.btnSaveToDownloads)
        btnUploadToDrive    = findViewById(R.id.btnShareToDrive)

        selectFileButton.setOnClickListener { pickZip.launch("application/zip") }
        btnSaveToDownloads.setOnClickListener { saveToDownloads() }
        btnUploadToDrive.setOnClickListener   { startDriveSignIn() }

        // Handle share-to-app intent
        if (intent?.action == Intent.ACTION_SEND) {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            uri?.let { processZip(it) }
        }
    }

    // ── Processing ────────────────────────────────────────────────────────────

    private fun processZip(uri: Uri) {
        statusText.text = "Processing ZIP…"
        setButtonsEnabled(false)
        extractedMediaFiles.clear()

        lifecycleScope.launch(Dispatchers.IO) {
            var tmpZip: File? = null
            try {
                tmpZip = File.createTempFile("chatgpt_export", ".zip", cacheDir)
                contentResolver.openInputStream(uri)?.use { it.copyTo(FileOutputStream(tmpZip)) }

                ZipFile(tmpZip).use { zip ->
                    // Parse conversations
                    val jsonEntry = zip.entries().toList()
                        .find { it.name.endsWith("conversations.json") }
                        ?: throw Exception("conversations.json not found in ZIP")

                    val json = zip.getInputStream(jsonEntry).bufferedReader().readText()
                    val files = ChatGPTConverter().parseConversations(json)

                    // Extract media
                    val mediaDir = File(cacheDir, "extracted_media").also {
                        it.deleteRecursively(); it.mkdirs()
                    }
                    val mediaExt = listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".mp4")
                    var mediaCount = 0

                    zip.entries().asSequence().forEach { entry ->
                        if (!entry.isDirectory && mediaExt.any { entry.name.endsWith(it, true) }) {
                            val dest = File(mediaDir, File(entry.name).name)
                            zip.getInputStream(entry).use { it.copyTo(FileOutputStream(dest)) }
                            extractedMediaFiles.add(dest)
                            mediaCount++
                        }
                    }

                    withContext(Dispatchers.Main) {
                        pendingFiles = files
                        setButtonsEnabled(true)
                        actionButtonsLayout.visibility = View.VISIBLE
                        statusText.text = "Found ${files.size} conversations" +
                                (if (mediaCount > 0) " + $mediaCount media files" else "") +
                                ".\n\nChoose an option below."
                    }
                }
            } catch (e: java.util.zip.ZipException) {
                showError("Invalid ZIP — try selecting conversations.json directly.")
            } catch (e: Exception) {
                showError("Error: ${e.message}")
            } finally {
                tmpZip?.delete()
            }
        }
    }

    private fun processJson(uri: Uri) {
        statusText.text = "Processing JSON…"
        setButtonsEnabled(false)
        extractedMediaFiles.clear()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val json = contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                    ?: throw Exception("Could not read file")
                val files = ChatGPTConverter().parseConversations(json)
                withContext(Dispatchers.Main) {
                    pendingFiles = files
                    setButtonsEnabled(true)
                    actionButtonsLayout.visibility = View.VISIBLE
                    statusText.text = "Found ${files.size} conversations.\n\nChoose an option below."
                }
            } catch (e: Exception) {
                showError("Error: ${e.message}")
            }
        }
    }

    // ── Upload to Google Drive as Google Docs ─────────────────────────────────

    private fun startDriveSignIn() {
        if (pendingFiles.isEmpty()) return

        // Check if already signed in
        val existing = GoogleSignIn.getLastSignedInAccount(this)
        if (existing?.account != null &&
            GoogleSignIn.hasPermissions(existing, Scope(DriveScopes.DRIVE_FILE))) {
            uploadToDriveWithAccount(existing.account!!)
            return
        }

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_FILE))
            .build()

        val client = GoogleSignIn.getClient(this, gso)
        signInLauncher.launch(client.signInIntent)
    }

    private fun uploadToDriveWithAccount(account: Account) {
        statusText.text = "Uploading to Google Drive…"
        setButtonsEnabled(false)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val uploader = DriveUploader(this@MainActivity, account)

                // Create root folder: ChatGPT Archive
                val rootFolderId = uploader.findOrCreateFolder("ChatGPT Archive")

                // Upload conversations as Google Docs
                var uploaded = 0
                for ((index, file) in pendingFiles.withIndex()) {
                    val docTitle = file.docTitle.ifBlank { file.filename.removeSuffix(".html") }
                    uploader.uploadAsGoogleDoc(docTitle, file.content, rootFolderId)
                    uploaded++

                    if (index % 5 == 0) {
                        val msg = "Uploading docs… $uploaded / ${pendingFiles.size}"
                        withContext(Dispatchers.Main) { statusText.text = msg }
                    }
                }

                // Upload media files into a subfolder
                var uploadedMedia = 0
                if (extractedMediaFiles.isNotEmpty()) {
                    val mediaFolderId = uploader.findOrCreateFolder("Media", rootFolderId)
                    for ((index, mediaFile) in extractedMediaFiles.withIndex()) {
                        try {
                            uploader.uploadMediaFile(mediaFile, mediaFolderId)
                            uploadedMedia++
                        } catch (_: Exception) { }

                        if (index % 10 == 0) {
                            val msg = "Uploading media… $uploadedMedia / ${extractedMediaFiles.size}"
                            withContext(Dispatchers.Main) { statusText.text = msg }
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    val mediaMsg = if (uploadedMedia > 0) " + $uploadedMedia media files" else ""
                    statusText.text = "Done! Created $uploaded Google Docs$mediaMsg in 'ChatGPT Archive'."
                    Toast.makeText(this@MainActivity, "Uploaded to Drive!", Toast.LENGTH_LONG).show()
                    setButtonsEnabled(true)
                }
            } catch (e: Exception) {
                showError("Upload error: ${e.message}")
            }
        }
    }

    // ── Save to Downloads (HTML files) ────────────────────────────────────────

    private fun saveToDownloads() {
        if (pendingFiles.isEmpty()) return
        statusText.text = "Saving to Downloads/ChatGPT_Archive…"
        setButtonsEnabled(false)

        lifecycleScope.launch(Dispatchers.IO) {
            var saved = 0
            try {
                for (file in pendingFiles) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, file.filename)
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/html")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.MediaColumns.RELATIVE_PATH,
                                "${Environment.DIRECTORY_DOWNLOADS}/ChatGPT_Archive")
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                    }
                    val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                        contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    else null

                    if (uri != null) {
                        contentResolver.openOutputStream(uri)?.use {
                            it.write(file.content.toByteArray(Charsets.UTF_8))
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            values.clear()
                            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                            contentResolver.update(uri, values, null, null)
                        }
                        saved++
                    }
                    if (saved % 10 == 0) withContext(Dispatchers.Main) {
                        statusText.text = "Saving… $saved / ${pendingFiles.size}"
                    }
                }
                withContext(Dispatchers.Main) {
                    statusText.text = "Saved $saved HTML files to Downloads/ChatGPT_Archive."
                    Toast.makeText(this@MainActivity, "Saved!", Toast.LENGTH_SHORT).show()
                    setButtonsEnabled(true)
                }
            } catch (e: Exception) {
                showError("Save error: ${e.message}")
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun showError(msg: String) = withContext(Dispatchers.Main) {
        statusText.text = msg
        setButtonsEnabled(true)
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        selectFileButton.isEnabled   = enabled
        btnSaveToDownloads.isEnabled = enabled
        btnUploadToDrive.isEnabled   = enabled
    }
}
