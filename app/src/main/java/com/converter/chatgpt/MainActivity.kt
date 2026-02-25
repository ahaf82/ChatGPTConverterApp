package com.converter.chatgpt

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
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var selectFileButton: Button
    private lateinit var selectJsonButton: Button
    private lateinit var actionButtonsLayout: LinearLayout
    private lateinit var btnSaveToDownloads: Button
    private lateinit var btnShareToDrive: Button

    private var pendingFiles: List<ChatGPTConverter.ConversationFile> = emptyList()
    private var extractedMediaFiles: MutableList<File> = mutableListOf()

    private val pickZipLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { processZipFile(it) }
    }

    private val pickJsonLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { processJsonFile(it) }
    }

    private val pickFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let { saveFilesToFolder(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        selectFileButton = findViewById(R.id.selectFileButton)
        selectJsonButton = findViewById(R.id.selectJsonButton)
        actionButtonsLayout = findViewById(R.id.actionButtonsLayout)
        btnSaveToDownloads = findViewById(R.id.btnSaveToDownloads)
        btnShareToDrive = findViewById(R.id.btnShareToDrive)

        selectFileButton.setOnClickListener {
            pickZipLauncher.launch("application/zip")
        }

        selectJsonButton.setOnClickListener {
            pickJsonLauncher.launch("application/json")
        }

        btnSaveToDownloads.setOnClickListener {
            saveToDownloads()
        }

        btnShareToDrive.setOnClickListener {
            shareFilesToDrive()
        }

        if (intent?.action == Intent.ACTION_SEND) {
            val streamUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            streamUri?.let { uri ->
                when {
                    uri.toString().contains("json") -> processJsonFile(uri)
                    else -> processZipFile(uri)
                }
            }
        }
    }

    private fun processZipFile(uri: Uri) {
        statusText.text = "Processing..."
        setButtonsEnabled(false)
        extractedMediaFiles.clear()

        lifecycleScope.launch(Dispatchers.IO) {
            var tempFile: File? = null
            try {
                // Copy URI to temp file first - more reliable than streaming from content providers
                tempFile = File.createTempFile("chatgpt_export", ".zip", cacheDir)
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                } ?: throw IllegalStateException("Could not read file")

                ZipFile(tempFile).use { zip ->
                    // 1. Find and parse conversations.json
                    val jsonEntry = zip.entries().toList().find { it.name.endsWith("conversations.json") }
                        ?: throw IllegalStateException("conversations.json not found in ZIP")

                    val jsonString = zip.getInputStream(jsonEntry).bufferedReader().readText()
                    val converter = ChatGPTConverter()
                    val files = converter.parseConversations(jsonString)

                    // 2. Extract images/videos
                    val mediaDir = File(cacheDir, "extracted_media")
                    mediaDir.deleteRecursively()
                    mediaDir.mkdirs()

                    val mediaExtensions = listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".mp4")
                    var mediaCount = 0
                    
                    zip.entries().asSequence().forEach { entry ->
                        if (!entry.isDirectory && mediaExtensions.any { entry.name.endsWith(it, ignoreCase = true) }) {
                            val fileName = File(entry.name).name // Flatten path
                            val destFile = File(mediaDir, fileName)
                            zip.getInputStream(entry).use { input ->
                                FileOutputStream(destFile).use { output ->
                                    input.copyTo(output)
                                }
                            }
                            extractedMediaFiles.add(destFile)
                            mediaCount++
                        }
                    }

                    withContext(Dispatchers.Main) {
                        pendingFiles = files
                        setButtonsEnabled(true)
                        actionButtonsLayout.visibility = View.VISIBLE
                        statusText.text = "Found ${files.size} conversations and $mediaCount media files.\n\nChoose an option below."
                    }
                }
            } catch (e: java.util.zip.ZipException) {
                withContext(Dispatchers.Main) {
                    setButtonsEnabled(true)
                    statusText.text = "ZIP not supported.\n\nTry selecting conversations.json directly."
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    setButtonsEnabled(true)
                    statusText.text = "Error: ${e.message}"
                }
            } finally {
                tempFile?.delete()
            }
        }
    }

    private fun processJsonFile(uri: Uri) {
        statusText.text = "Processing..."
        setButtonsEnabled(false)
        extractedMediaFiles.clear()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val jsonString = contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                    ?: throw IllegalStateException("Could not read file")

                val converter = ChatGPTConverter()
                val files = converter.parseConversations(jsonString)

                withContext(Dispatchers.Main) {
                    pendingFiles = files
                    setButtonsEnabled(true)
                    actionButtonsLayout.visibility = View.VISIBLE
                    statusText.text = "Found ${files.size} conversations.\n\nChoose an option below."
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    setButtonsEnabled(true)
                    statusText.text = "Error: ${e.message}\n\nCheck if valid JSON."
                }
            }
        }
    }

    private fun saveFilesToFolder(rootUri: Uri) {
        if (pendingFiles.isEmpty()) return

        statusText.text = "Saving files..."
        setButtonsEnabled(false)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val rootDir = DocumentFile.fromTreeUri(this@MainActivity, rootUri)
                if (rootDir == null || !rootDir.exists()) {
                    withContext(Dispatchers.Main) {
                        statusText.text = "Error: Could not access folder."
                        setButtonsEnabled(true)
                    }
                    return@launch
                }

                var savedCount = 0
                
                // Save Markdown files
                for (file in pendingFiles) {
                    val newFile = rootDir.createFile("text/markdown", file.filename)
                    if (newFile != null && newFile.uri != null) {
                        contentResolver.openOutputStream(newFile.uri)?.use { output ->
                            output.write(file.content.toByteArray(Charsets.UTF_8))
                        }
                        savedCount++
                    }
                }
                
                // Save Media files
                for (media in extractedMediaFiles) {
                    // Mime type guess
                    val mimeType = when (media.extension.lowercase()) {
                        "jpg", "jpeg" -> "image/jpeg"
                        "png" -> "image/png"
                        "webp" -> "image/webp"
                        "mp4" -> "video/mp4"
                        else -> "application/octet-stream"
                    }
                    
                    val newFile = rootDir.createFile(mimeType, media.name)
                    if (newFile != null && newFile.uri != null) {
                        contentResolver.openOutputStream(newFile.uri)?.use { output ->
                            media.inputStream().use { input ->
                                input.copyTo(output)
                            }
                        }
                        savedCount++
                    }
                }

                withContext(Dispatchers.Main) {
                    statusText.text = "Done! Saved $savedCount files to your folder."
                    Toast.makeText(this@MainActivity, "Saved!", Toast.LENGTH_LONG).show()
                    setButtonsEnabled(true)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText.text = "Save error: ${e.message}"
                    setButtonsEnabled(true)
                }
            }
        }
    }

    private fun saveToDownloads() {
        if (pendingFiles.isEmpty()) return

        statusText.text = "Saving to Downloads/ChatGPT_Archive..."
        setButtonsEnabled(false)

        lifecycleScope.launch(Dispatchers.IO) {
            var savedCount = 0
            try {
                val folderName = "ChatGPT_Archive"

                // Save Markdown
                for (file in pendingFiles) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, file.filename)
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/markdown")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$folderName")
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                    }

                    val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                         contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    } else {
                         null
                    }

                    if (uri != null) {
                        contentResolver.openOutputStream(uri)?.use { output ->
                            output.write(file.content.toByteArray(Charsets.UTF_8))
                        }
                        
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            values.clear()
                            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                            contentResolver.update(uri, values, null, null)
                        }
                        savedCount++
                    }
                }
                
                // Save Media
                for (media in extractedMediaFiles) {
                    val mimeType = when (media.extension.lowercase()) {
                        "jpg", "jpeg" -> "image/jpeg"
                        "png" -> "image/png"
                        "mp4" -> "video/mp4"
                        else -> "image/jpeg"
                    }
                    
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, media.name)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$folderName")
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                    }
                    
                    val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                         contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    } else { null } // Skip older android for brevity in this snippet

                    if (uri != null) {
                        contentResolver.openOutputStream(uri)?.use { output ->
                            media.inputStream().use { input -> input.copyTo(output) }
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            values.clear()
                            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                            contentResolver.update(uri, values, null, null)
                        }
                        savedCount++
                    }
                }

                withContext(Dispatchers.Main) {
                    statusText.text = "Done! Saved $savedCount files."
                    Toast.makeText(this@MainActivity, "Saved to Downloads!", Toast.LENGTH_LONG).show()
                    setButtonsEnabled(true)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText.text = "Save error: ${e.message}"
                    setButtonsEnabled(true)
                }
            }
        }
    }

    private fun shareFilesToDrive() {
        if (pendingFiles.isEmpty()) return
        statusText.text = "Preparing files for Drive..."
        setButtonsEnabled(false)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. Create a shared folder in cache
                val sharedDir = File(cacheDir, "shared_chats")
                sharedDir.mkdirs()
                sharedDir.listFiles()?.forEach { it.delete() } // Clean up

                val uris = ArrayList<Uri>()
                var count = 0

                // Add Markdown files (Limit 100)
                val filesToShare = pendingFiles.take(100) 
                for (file in filesToShare) {
                    val tempFile = File(sharedDir, file.filename)
                    FileOutputStream(tempFile).use { 
                        it.write(file.content.toByteArray(Charsets.UTF_8)) 
                    }
                    val uri = FileProvider.getUriForFile(this@MainActivity, "${packageName}.fileprovider", tempFile)
                    uris.add(uri)
                    count++
                }
                
                // Add Media files (Limit 50 to avoid crash)
                val mediaToShare = extractedMediaFiles.take(50)
                for (media in mediaToShare) {
                    val tempFile = File(sharedDir, media.name)
                    media.copyTo(tempFile, overwrite = true)
                    val uri = FileProvider.getUriForFile(this@MainActivity, "${packageName}.fileprovider", tempFile)
                    uris.add(uri)
                    count++
                }

                withContext(Dispatchers.Main) {
                    val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = "*/*" // Mixed content
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    
                    // Show "Upload to Drive" in share sheet
                    startActivity(Intent.createChooser(shareIntent, "Upload to Drive"))
                    
                    statusText.text = "Ready! Select 'Drive' to upload $count files."
                    if (pendingFiles.size > 100 || extractedMediaFiles.size > 50) {
                        Toast.makeText(this@MainActivity, "Shared first batch of files.", Toast.LENGTH_LONG).show()
                    }
                    setButtonsEnabled(true)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText.text = "Share error: ${e.message}"
                    setButtonsEnabled(true)
                }
            }
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        selectFileButton.isEnabled = enabled
        selectJsonButton.isEnabled = enabled
        btnSaveToDownloads.isEnabled = enabled
        btnShareToDrive.isEnabled = enabled
    }
}
