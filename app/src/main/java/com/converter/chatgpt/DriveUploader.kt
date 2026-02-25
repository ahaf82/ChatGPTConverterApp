package com.converter.chatgpt

import android.accounts.Account
import android.content.Context
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import java.io.ByteArrayInputStream
import java.io.FileInputStream

/**
 * Uploads HTML content to Google Drive as native Google Docs.
 * Google Drive automatically converts text/html → application/vnd.google-apps.document
 * which creates a real Google Doc — readable and searchable in Gemini.
 */
class DriveUploader(context: Context, account: Account) {

    private val driveService: Drive

    init {
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
        val query = "name='$name' and mimeType='application/vnd.google-apps.folder' " +
                "and '$parentId' in parents and trashed=false"

        val result = driveService.files().list()
            .setQ(query)
            .setSpaces("drive")
            .setFields("files(id)")
            .execute()

        return if (result.files.isNotEmpty()) {
            result.files[0].id
        } else {
            val folderMeta = File().apply {
                this.name = name
                mimeType = "application/vnd.google-apps.folder"
                parents = listOf(parentId)
            }
            driveService.files().create(folderMeta).setFields("id").execute().id
        }
    }

    /**
     * Uploads one conversation as a Google Doc.
     * Google Drive converts text/html → Google Doc automatically.
     *
     * @param title   The Google Doc title (e.g. "2024-03-15 My Chat")
     * @param html    Full HTML content of the conversation
     * @param folderId Drive folder ID to upload into
     */
    fun uploadAsGoogleDoc(title: String, html: String, folderId: String): String {
        val meta = File().apply {
            name = title
            mimeType = "application/vnd.google-apps.document"
            parents = listOf(folderId)
        }

        val htmlBytes = html.toByteArray(Charsets.UTF_8)
        val content = com.google.api.client.http.InputStreamContent(
            "text/html",
            ByteArrayInputStream(htmlBytes)
        ).apply { length = htmlBytes.size.toLong() }

        return driveService.files().create(meta, content)
            .setFields("id")
            .execute()
            .id
    }

    fun uploadMediaFile(localFile: java.io.File, folderId: String): String {
        val mimeType = when (localFile.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png"         -> "image/png"
            "gif"         -> "image/gif"
            "webp"        -> "image/webp"
            "mp4"         -> "video/mp4"
            else          -> "application/octet-stream"
        }

        val meta = File().apply {
            name = localFile.name
            parents = listOf(folderId)
        }

        val content = com.google.api.client.http.InputStreamContent(
            mimeType,
            FileInputStream(localFile)
        ).apply { length = localFile.length() }

        return driveService.files().create(meta, content)
            .setFields("id")
            .execute()
            .id
    }
}
