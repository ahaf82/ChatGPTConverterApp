package com.converter.chatgpt

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Converts ChatGPT export JSON into structured HTML files suitable for
 * upload to Google Drive as native Google Docs.
 */
class ChatGPTConverter {

    data class ConversationFile(
        val filename: String,       // e.g. "2024-03-15_My Chat.html"
        val docTitle: String,       // e.g. "My Chat"
        val folderPath: String,     // e.g. "2024/03"
        val content: String         // full HTML content
    )

    fun parseConversations(jsonString: String): List<ConversationFile> {
        val files = mutableListOf<ConversationFile>()

        try {
            val conversationsArray = JSONArray(jsonString)
            for (i in 0 until conversationsArray.length()) {
                val conv = conversationsArray.optJSONObject(i) ?: continue
                val file = processConversation(conv)
                if (file != null) files.add(file)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return files
    }

    private fun processConversation(conversation: JSONObject): ConversationFile? {
        val title = conversation.optString("title", "Untitled")
        val createTime = conversation.optLong("create_time", System.currentTimeMillis() / 1000)

        val date = Date(createTime * 1000)
        val folderPath = SimpleDateFormat("yyyy/MM", Locale.US).format(date)
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(date)
        val datePrefix = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)

        val safeTitle = title.replace(Regex("[^a-zA-Z0-9 \\-_]"), "").trim().take(60)
        val filename = "${datePrefix}_$safeTitle.html"

        // Extract messages
        val mapping = conversation.optJSONObject("mapping")
        var currentNodeId = conversation.optString("current_node")
        val messages = mutableListOf<String>()

        while (currentNodeId.isNotEmpty() && mapping != null && mapping.has(currentNodeId)) {
            val node = mapping.optJSONObject(currentNodeId)
            val message = node?.optJSONObject("message")

            if (message != null) {
                val role = message.optJSONObject("author")?.optString("role") ?: "unknown"

                if (role != "system") {
                    val contentObj = message.optJSONObject("content")
                    val contentType = contentObj?.optString("content_type")
                    val parts = contentObj?.optJSONArray("parts")
                    val textBuilder = StringBuilder()

                    when (contentType) {
                        "text" -> {
                            if (parts != null) {
                                for (j in 0 until parts.length()) {
                                    textBuilder.append(parts.optString(j))
                                }
                            }
                        }
                        "multimodal_text" -> {
                            if (parts != null) {
                                for (j in 0 until parts.length()) {
                                    val part = parts.optJSONObject(j) ?: continue
                                    when (part.optString("content_type")) {
                                        "text" -> textBuilder.append(part.optString("text", ""))
                                        "image_asset_pointer" -> {
                                            val assetPointer = part.optString("asset_pointer").replace("file-service://", "")
                                            val fileName = assetPointer.substringAfterLast("/")
                                            textBuilder.append("\n[Image: $fileName]\n")
                                        }
                                    }
                                }
                            }
                        }
                        else -> {
                            if (parts != null) {
                                for (j in 0 until parts.length()) {
                                    val p = parts.opt(j)
                                    if (p is String) textBuilder.append(p)
                                }
                            }
                        }
                    }

                    // Attachments
                    val attachments = message.optJSONObject("metadata")?.optJSONArray("attachments")
                    if (attachments != null) {
                        for (k in 0 until attachments.length()) {
                            val name = attachments.optJSONObject(k)?.optString("name") ?: continue
                            textBuilder.append("\n[Attachment: $name]")
                        }
                    }

                    val text = textBuilder.toString().trim()
                    if (text.isNotEmpty()) {
                        val isUser = role == "user"
                        val roleName = if (isUser) "You" else "ChatGPT"
                        val htmlText = escapeHtml(text)
                            .replace("\n\n", "</p><p>")
                            .replace("\n", "<br>")
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

    private fun buildMessageHtml(role: String, isUser: Boolean, htmlText: String): String {
        val bgColor = if (isUser) "#e8f0fe" else "#f8f9fa"
        val borderColor = if (isUser) "#4285f4" else "#34a853"
        return """
            <div style="margin: 12px 0; padding: 12px 16px; background-color: $bgColor; 
                        border-left: 4px solid $borderColor; border-radius: 4px;">
              <p style="margin: 0 0 6px 0; font-weight: bold; color: #333; font-size: 13px;">$role</p>
              <p style="margin: 0; color: #1a1a1a; line-height: 1.6;">$htmlText</p>
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
