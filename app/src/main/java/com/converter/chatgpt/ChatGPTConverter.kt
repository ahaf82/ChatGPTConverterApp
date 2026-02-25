package com.converter.chatgpt

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Handles reading the ChatGPT export JSON and converting it to structured Markdown files.
 */
class ChatGPTConverter {

    data class ConversationFile(
        val filename: String,
        val folderPath: String,
        val content: String
    )

    fun parseConversations(jsonString: String): List<ConversationFile> {
        val files = mutableListOf<ConversationFile>()

        try {
            val conversationsArray = JSONArray(jsonString)

            for (i in 0 until conversationsArray.length()) {
                val conv = conversationsArray.optJSONObject(i) ?: continue
                val file = processConversation(conv)
                if (file != null) {
                    files.add(file)
                }
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

        val safeTitle = title.replace(Regex("[^a-zA-Z0-9 \\-_]"), "").trim().take(50)
        val filename = "${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)}_$safeTitle.md"

        val mapping = conversation.optJSONObject("mapping")
        var currentNodeId = conversation.optString("current_node")

        val messages = mutableListOf<String>()

        while (currentNodeId.isNotEmpty() && mapping != null && mapping.has(currentNodeId)) {
            val node = mapping.optJSONObject(currentNodeId)
            val message = node?.optJSONObject("message")

            if (message != null) {
                val author = message.optJSONObject("author")
                val role = author?.optString("role") ?: "unknown"

                if (role != "system") {
                    val contentObj = message.optJSONObject("content")
                    val contentType = contentObj?.optString("content_type")
                    val parts = contentObj?.optJSONArray("parts")

                    var textBuilder = StringBuilder()
                    
                    if (contentType == "text" && parts != null) {
                        for (j in 0 until parts.length()) {
                            textBuilder.append(parts.optString(j))
                        }
                    } else if (contentType == "multimodal_text" && parts != null) {
                        for (j in 0 until parts.length()) {
                            val part = parts.optJSONObject(j) ?: continue
                            val type = part.optString("content_type")
                            if (type == "text") {
                                textBuilder.append(part.optString("text", "")) // Sometimes it's a string, sometimes object? 
                                // Standard multimodal part: { "content_type": "text", "text": "..." } or simple string in parts array?
                                // Actually in multimodal_text, parts is array of objects.
                            } else if (type == "image_asset_pointer") {
                                val assetPointer = part.optString("asset_pointer").replace("file-service://", "")
                                // We assume the filename might be related to asset pointer or we just link it
                                // If the ZIP contains the file, we usually strip the path
                                val fileName = assetPointer.substringAfterLast("/")
                                textBuilder.append("\n\n![Image]($fileName)\n\n")
                            }
                        }
                    } else {
                        // Fallback for simple parts array (sometimes mixed)
                        if (parts != null) {
                             for (j in 0 until parts.length()) {
                                 val p = parts.opt(j)
                                 if (p is String) textBuilder.append(p)
                                 else if (p is JSONObject) {
                                     // Handle object part
                                     if (p.optString("content_type") == "image_asset_pointer") {
                                         textBuilder.append("\n\n![Image](Image)\n\n")
                                     }
                                 }
                             }
                        }
                    }
                    
                    // Check for attachments metadata (uploaded files)
                    val metadata = message.optJSONObject("metadata")
                    val attachments = metadata?.optJSONArray("attachments")
                    if (attachments != null) {
                        for (k in 0 until attachments.length()) {
                            val att = attachments.optJSONObject(k)
                            val name = att?.optString("name") ?: "Attachment"
                            textBuilder.append("\n\n[Attachment: $name]($name)\n")
                        }
                    }

                    if (textBuilder.isNotEmpty()) {
                        val roleName = role.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
                        messages.add("**$roleName**:\n$textBuilder\n\n---\n\n")
                    }
                }
            }

            currentNodeId = node?.optString("parent") ?: ""
            if (currentNodeId == "null") break
        }

        messages.reverse()

        val sb = StringBuilder()
        sb.append("# $title\n\n")
        sb.append("**Date:** ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(date)}\n\n")
        messages.forEach { sb.append(it) }

        return ConversationFile(filename, folderPath, sb.toString())
    }
}
