package com.converter.chatgpt

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Converts ChatGPT export JSON into structured HTML files suitable for
 * upload to Google Drive as native Google Docs.
 *
 * Image strategy (upload path):
 *   Images are NOT embedded as base64. Instead, they are uploaded to Drive as
 *   separate files and referenced via a styled clickable link in the document.
 *   Gemini can follow those Drive links directly when reading the document.
 *
 * Image strategy (save-locally path):
 *   Images are embedded as base64 data URIs (up to [MAX_IMAGE_BYTES] each).
 *   Pass an empty [driveFileIds] map to use this path.
 *
 * Videos are always referenced by a Drive link or a plain filename note.
 */
class ChatGPTConverter {

    data class ConversationFile(
        val filename: String,       // e.g. "2024-03-15_My Chat.html"
        val docTitle: String,       // e.g. "My Chat"
        val folderPath: String,     // e.g. "2024/03"
        val content: String         // full HTML content
    )

    private sealed class ContentPart {
        data class Text(val text: String) : ContentPart()
        data class Html(val html: String) : ContentPart()
    }

    private companion object {
        private const val TAG             = "ChatGPTConverter"
        private const val MAX_IMAGE_BYTES = 5 * 1024 * 1024   // 5 MB — only used for local save
        private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif")
        private val VIDEO_EXTS = setOf("mp4", "mov", "webm")
    }

    /**
     * Returns the number of top-level conversations in a JSON array string.
     * Uses JSONArray for accuracy — the character-scan alternative was found to
     * produce wildly wrong counts (e.g. 45,000 instead of 361) on real exports.
     * The JSONArray is discarded immediately after reading the length so the GC
     * can reclaim the memory before the next part file is processed.
     */
    fun countConversations(jsonString: String): Int = try {
        org.json.JSONArray(jsonString).length()
    } catch (_: Exception) { 0 }

    /**
     * Lazily yields one [ConversationFile] at a time to keep heap usage low.
     *
     * @param jsonString   Raw content of a conversations JSON file
     * @param mediaFiles   baseName → local File, used for base64 fallback (save path)
     * @param driveFileIds baseName → Drive file ID; when present for an asset,
     *                     a clickable Drive link is emitted instead of a base64 image.
     *                     Pass an empty map for the local-save path.
     */
    fun parseConversationsLazily(
        jsonString: String,
        mediaFiles: Map<String, File>   = emptyMap(),
        driveFileIds: Map<String, String> = emptyMap()
    ): Sequence<ConversationFile> = sequence {
        try {
            val array = JSONArray(jsonString)
            AppLogger.d(TAG, "parseConversationsLazily: ${array.length()} items, " +
                    "driveIds=${driveFileIds.size}")

            for (i in 0 until array.length()) {
                val conv = array.optJSONObject(i) ?: continue
                try {
                    val file = processConversation(conv, mediaFiles, driveFileIds)
                    if (file != null) yield(file)
                } catch (t: Throwable) {
                    AppLogger.w(TAG, "Skipping conversation[$i]: ${t.javaClass.simpleName}: " +
                            "${t.message?.take(120)}", t)
                }
            }
        } catch (t: Throwable) {
            AppLogger.e(TAG, "parseConversationsLazily top-level failure: ${t.javaClass.name}", t)
        }
    }

    /** Eager version — kept for the plain-JSON path and unit testing. */
    fun parseConversations(
        jsonString: String,
        mediaFiles: Map<String, File>     = emptyMap(),
        driveFileIds: Map<String, String> = emptyMap()
    ): List<ConversationFile> = parseConversationsLazily(jsonString, mediaFiles, driveFileIds).toList()

    private fun processConversation(
        conversation: JSONObject,
        mediaFiles: Map<String, File>,
        driveFileIds: Map<String, String>
    ): ConversationFile? {
        val title      = conversation.optString("title", "Untitled")
        val createTime = conversation.optLong("create_time", System.currentTimeMillis() / 1000)

        val date       = Date(createTime * 1000)
        val folderPath = SimpleDateFormat("yyyy/MM", Locale.US).format(date)
        val dateStr    = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(date)
        val datePrefix = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)

        val safeTitle = title.replace(Regex("[^a-zA-Z0-9 \\-_]"), "").trim().take(60)
        val filename  = "${datePrefix}_$safeTitle.html"

        val mapping = conversation.optJSONObject("mapping")
        var currentNodeId = conversation.optString("current_node", "")
        if (currentNodeId == "null") currentNodeId = ""
        val messages = mutableListOf<String>()

        while (currentNodeId.isNotEmpty() && mapping != null && mapping.has(currentNodeId)) {
            val node    = mapping.optJSONObject(currentNodeId)
            val message = node?.optJSONObject("message")

            if (message != null) {
                val role = message.optJSONObject("author")?.optString("role") ?: "unknown"

                if (role != "system") {
                    val contentObj  = message.optJSONObject("content")
                    val contentType = contentObj?.optString("content_type")
                    val parts       = contentObj?.optJSONArray("parts")
                    val contentParts = mutableListOf<ContentPart>()

                    when (contentType) {
                        "text" -> {
                            if (parts != null) {
                                val sb = StringBuilder()
                                for (j in 0 until parts.length()) sb.append(parts.optString(j))
                                contentParts.add(ContentPart.Text(sb.toString()))
                            }
                        }

                        "multimodal_text" -> {
                            if (parts != null) {
                                for (j in 0 until parts.length()) {
                                    val part = parts.optJSONObject(j)
                                    if (part != null) {
                                        when (part.optString("content_type")) {
                                            "text" -> {
                                                val t = part.optString("text", "")
                                                if (t.isNotEmpty()) contentParts.add(ContentPart.Text(t))
                                            }
                                            "image_asset_pointer" -> {
                                                contentParts.add(
                                                    resolveImagePart(part, mediaFiles, driveFileIds)
                                                )
                                            }
                                        }
                                    } else {
                                        val s = parts.optString(j)
                                        if (s.isNotEmpty()) contentParts.add(ContentPart.Text(s))
                                    }
                                }
                            }
                        }

                        else -> {
                            if (parts != null) {
                                val sb = StringBuilder()
                                for (j in 0 until parts.length()) {
                                    val p = parts.opt(j)
                                    if (p is String) sb.append(p)
                                }
                                contentParts.add(ContentPart.Text(sb.toString()))
                            }
                        }
                    }

                    // Attachments (PDFs, code files, …)
                    val attachments = message.optJSONObject("metadata")?.optJSONArray("attachments")
                    if (attachments != null) {
                        for (k in 0 until attachments.length()) {
                            val name = attachments.optJSONObject(k)?.optString("name") ?: continue
                            contentParts.add(ContentPart.Html(
                                "<br><em style=\"color:#5f6368;font-size:12px;\">" +
                                "[Attachment: ${escapeHtml(name)}]</em>"
                            ))
                        }
                    }

                    val htmlText = buildHtmlFromParts(contentParts)
                    if (htmlText.isNotBlank()) {
                        val isUser   = role == "user"
                        val roleName = if (isUser) "You" else "ChatGPT"
                        messages.add(buildMessageHtml(roleName, isUser, htmlText))
                    }
                }
            }

            currentNodeId = node?.optString("parent") ?: ""
            if (currentNodeId == "null") break
        }

        messages.reverse()
        if (messages.isEmpty()) return null

        val html = buildDocumentHtml(title, dateStr, messages)
        return ConversationFile(filename, title, folderPath, html)
    }

    /**
     * Resolves an image_asset_pointer.
     *
     * Priority order:
     * 1. Drive link  — if [driveFileIds] has an entry for this asset (upload path).
     *                  Emits a styled, clickable link that Gemini can follow.
     * 2. Base64 embed — if the local file is an image within [MAX_IMAGE_BYTES] (save path).
     * 3. Filename note — fallback when the file is missing, a video, or too large.
     */
    private fun resolveImagePart(
        part: JSONObject,
        mediaFiles: Map<String, File>,
        driveFileIds: Map<String, String>
    ): ContentPart {
        val assetPointer = part.optString("asset_pointer").removePrefix("file-service://")
        val fileId       = assetPointer.substringAfterLast("/")

        // Resolve the local media file (used for display name and base64 fallback)
        val mediaFile = mediaFiles[fileId]
            ?: mediaFiles.entries.firstOrNull { (key, _) ->
                key.startsWith(fileId) || fileId.startsWith(key)
            }?.value

        // Resolve the Drive ID (exact or prefix match)
        val driveId = driveFileIds[fileId]
            ?: driveFileIds.entries.firstOrNull { (key, _) ->
                key.startsWith(fileId) || fileId.startsWith(key)
            }?.value

        // ── 1. Drive link (upload path) ──────────────────────────────────────
        if (driveId != null) {
            val displayName = mediaFile?.name ?: fileId
            val ext         = mediaFile?.extension?.lowercase() ?: ""
            val icon        = when {
                ext in VIDEO_EXTS -> "🎬"
                ext in IMAGE_EXTS -> "🖼️"
                else              -> "📎"
            }
            val driveUrl = "https://drive.google.com/file/d/$driveId/view"
            return ContentPart.Html(
                """<br><a href="$driveUrl" """ +
                """style="display:inline-flex;align-items:center;gap:6px;padding:6px 12px;""" +
                """background:#e8f0fe;border-radius:6px;color:#1a73e8;text-decoration:none;""" +
                """font-size:13px;font-family:Arial,sans-serif;">""" +
                """$icon ${escapeHtml(displayName)}</a><br>"""
            )
        }

        // ── 2. Base64 embed (save-locally path) ──────────────────────────────
        if (mediaFile != null && mediaFile.extension.lowercase() in IMAGE_EXTS) {
            if (mediaFile.length() <= MAX_IMAGE_BYTES) {
                return try {
                    val mimeType = when (mediaFile.extension.lowercase()) {
                        "jpg", "jpeg" -> "image/jpeg"
                        "png"         -> "image/png"
                        "gif"         -> "image/gif"
                        "webp"        -> "image/webp"
                        else          -> "image/jpeg"
                    }
                    val b64 = Base64.encodeToString(mediaFile.readBytes(), Base64.NO_WRAP)
                    ContentPart.Html(
                        """<br><img src="data:$mimeType;base64,$b64" """ +
                        """style="max-width:100%;height:auto;display:block;""" +
                        """margin:8px auto;border-radius:4px;"><br>"""
                    )
                } catch (_: Exception) {
                    ContentPart.Html(
                        "<br><em style=\"color:#5f6368;font-size:12px;\">" +
                        "[Image: ${escapeHtml(fileId)}]</em><br>"
                    )
                }
            } else {
                return ContentPart.Html(
                    "<br><em style=\"color:#5f6368;font-size:12px;\">" +
                    "[Image too large to embed: ${escapeHtml(mediaFile.name)}]</em><br>"
                )
            }
        }

        // ── 3. Fallback note ─────────────────────────────────────────────────
        val label = when {
            mediaFile != null && mediaFile.extension.lowercase() in VIDEO_EXTS ->
                "[Video: ${escapeHtml(mediaFile.name)}]"
            mediaFile != null ->
                "[Media: ${escapeHtml(mediaFile.name)}]"
            else ->
                "[Image: ${escapeHtml(fileId)}]"
        }
        return ContentPart.Html(
            "<br><em style=\"color:#5f6368;font-size:12px;\">$label</em><br>"
        )
    }

    private fun buildHtmlFromParts(parts: List<ContentPart>): String =
        parts.joinToString("") { part ->
            when (part) {
                is ContentPart.Text -> escapeHtml(part.text)
                    .replace("\n\n", "</p><p>")
                    .replace("\n", "<br>")
                is ContentPart.Html -> part.html
            }
        }

    private fun buildMessageHtml(role: String, isUser: Boolean, htmlText: String): String {
        val bgColor     = if (isUser) "#e8f0fe" else "#f8f9fa"
        val borderColor = if (isUser) "#4285f4" else "#34a853"
        return """
            <div style="margin: 12px 0; padding: 12px 16px; background-color: $bgColor;
                        border-left: 4px solid $borderColor; border-radius: 4px;">
              <p style="margin: 0 0 6px 0; font-weight: bold; color: #333; font-size: 13px;">$role</p>
              <div style="margin: 0; color: #1a1a1a; line-height: 1.6;">$htmlText</div>
            </div>
        """.trimIndent()
    }

    private fun buildDocumentHtml(title: String, dateStr: String, messages: List<String>): String {
        val body = messages.joinToString("\n")
        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="UTF-8">
              <title>${escapeHtml(title)}</title>
              <style>
                body { font-family: 'Google Sans', Arial, sans-serif; max-width: 800px;
                       margin: 0 auto; padding: 24px; color: #202124; }
                h1   { font-size: 22px; color: #1a73e8; border-bottom: 2px solid #e8eaed;
                       padding-bottom: 8px; }
                .meta { font-size: 12px; color: #5f6368; margin-bottom: 24px; }
              </style>
            </head>
            <body>
              <h1>${escapeHtml(title)}</h1>
              <div class="meta">ChatGPT conversation &bull; $dateStr</div>
              $body
            </body>
            </html>
        """.trimIndent()
    }

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
