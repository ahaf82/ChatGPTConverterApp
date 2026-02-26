package com.converter.chatgpt

import android.accounts.Account
import android.content.Context
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.IOException

/**
 * Uploads HTML content to Google Drive as native Google Docs.
 * Google Drive converts text/html → application/vnd.google-apps.document automatically,
 * making the conversations readable and searchable in Gemini.
 *
 * All network failures use [withRetry] with exponential back-off.
 * [AppLogger] records HTTP codes and retry attempts — no user data is logged.
 */
class DriveUploader(context: Context, account: Account) {

    private val driveService: Drive

    companion object {
        private const val TAG = "DriveUploader"
    }

    init {
        AppLogger.d(TAG, "Initializing Drive client (account type: ${account.type})")
        val credential = GoogleAccountCredential.usingOAuth2(
            context, listOf(DriveScopes.DRIVE_FILE)
        ).apply {
            selectedAccount = account
        }

        driveService = Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName("ChatGPT to Gemini").build()
    }

    /**
     * Finds or creates a folder in Drive by name under the given parent (default: My Drive).
     */
    fun findOrCreateFolder(name: String, parentId: String = "root"): String {
        AppLogger.d(TAG, "findOrCreateFolder name=[redacted] parentId length=${parentId.length}")
        val query = "name='$name' and mimeType='application/vnd.google-apps.folder' " +
                "and '$parentId' in parents and trashed=false"

        return withRetry("findOrCreateFolder") {
            val result = driveService.files().list()
                .setQ(query)
                .setSpaces("drive")
                .setFields("files(id)")
                .execute()

            if (result.files.isNotEmpty()) {
                AppLogger.d(TAG, "Folder found (existing)")
                result.files[0].id
            } else {
                AppLogger.d(TAG, "Folder not found — creating")
                val folderMeta = File().apply {
                    this.name = name
                    mimeType  = "application/vnd.google-apps.folder"
                    parents   = listOf(parentId)
                }
                val id = driveService.files().create(folderMeta).setFields("id").execute().id
                AppLogger.i(TAG, "Folder created, id length=${id.length}")
                id
            }
        }
    }

    /**
     * Uploads one conversation as a Google Doc (HTML → Google Doc conversion).
     * Retries up to 3 times on transient 500/503/429 errors.
     *
     * @param title    The Google Doc title — NOT logged for privacy
     * @param html     Full HTML content — NOT logged for privacy
     * @param folderId Drive folder ID to upload into
     */
    fun uploadAsGoogleDoc(title: String, html: String, folderId: String): String {
        val htmlBytes = html.toByteArray(Charsets.UTF_8)
        AppLogger.d(TAG, "uploadAsGoogleDoc sizeBytes=${htmlBytes.size}")

        val meta = File().apply {
            name     = title
            mimeType = "application/vnd.google-apps.document"
            parents  = listOf(folderId)
        }

        return withRetry("uploadAsGoogleDoc") {
            val content = com.google.api.client.http.InputStreamContent(
                "text/html",
                ByteArrayInputStream(htmlBytes)
            ).apply { length = htmlBytes.size.toLong() }

            val id = driveService.files().create(meta, content)
                .setFields("id")
                .execute()
                .id
            AppLogger.d(TAG, "uploadAsGoogleDoc success, id length=${id.length}")
            id
        }
    }

    /**
     * Uploads a raw media file (video/image) to Drive.
     * Images are normally embedded in docs; this is only called for videos.
     */
    fun uploadMediaFile(localFile: java.io.File, folderId: String): String {
        val mimeType = when (localFile.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png"         -> "image/png"
            "gif"         -> "image/gif"
            "webp"        -> "image/webp"
            "mp4"         -> "video/mp4"
            "mov"         -> "video/quicktime"
            "webm"        -> "video/webm"
            else          -> "application/octet-stream"
        }
        AppLogger.d(TAG, "uploadMediaFile mimeType=$mimeType sizeBytes=${localFile.length()}")

        val meta = File().apply {
            name    = localFile.name
            parents = listOf(folderId)
        }

        return withRetry("uploadMediaFile") {
            val content = com.google.api.client.http.InputStreamContent(
                mimeType,
                FileInputStream(localFile)
            ).apply { length = localFile.length() }

            val id = driveService.files().create(meta, content)
                .setFields("id")
                .execute()
                .id
            AppLogger.d(TAG, "uploadMediaFile success, id length=${id.length}")
            id
        }
    }

    /**
     * Retries [action] up to [maxAttempts] times on transient network/server errors
     * (HTTP 500, 503, 429 or generic IOExceptions). Uses linear back-off between attempts.
     * Throws the last exception if all attempts fail.
     */
    private fun <T> withRetry(
        opName: String,
        maxAttempts: Int = 3,
        action: () -> T
    ): T {
        var lastException: Exception? = null
        for (attempt in 0 until maxAttempts) {
            try {
                return action()
            } catch (e: GoogleJsonResponseException) {
                if (e.statusCode == 500 || e.statusCode == 503 || e.statusCode == 429) {
                    lastException = e
                    val waitSec = (attempt + 1) * 3
                    AppLogger.w(TAG, "$opName: HTTP ${e.statusCode} on attempt ${attempt + 1}/$maxAttempts " +
                            "— retrying in ${waitSec}s")
                    if (attempt < maxAttempts - 1) Thread.sleep(waitSec * 1000L)
                } else {
                    AppLogger.e(TAG, "$opName: non-retryable HTTP ${e.statusCode} (${e.statusMessage})", e)
                    throw e
                }
            } catch (e: java.io.FileNotFoundException) {
                // A missing local file will never appear on retry — fail fast.
                AppLogger.e(TAG, "$opName: FileNotFoundException — file missing, not retrying", e)
                throw e
            } catch (e: IOException) {
                lastException = e
                val waitSec = (attempt + 1) * 3
                AppLogger.w(TAG, "$opName: IOException (${e.javaClass.simpleName}) on attempt " +
                        "${attempt + 1}/$maxAttempts — retrying in ${waitSec}s", e)
                if (attempt < maxAttempts - 1) Thread.sleep(waitSec * 1000L)
            }
        }
        AppLogger.e(TAG, "$opName: all $maxAttempts attempts exhausted", lastException)
        throw lastException!!
    }
}
