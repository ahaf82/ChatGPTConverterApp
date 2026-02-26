package com.converter.chatgpt

import android.accounts.Account
import java.io.File

/**
 * Singleton that holds the raw export data between the ZIP-scan step and the
 * upload/save step. Keeping only file references (not in-memory strings) here
 * minimises heap pressure while the user decides what to do.
 *
 * [pendingJsonFiles] is a list because newer ChatGPT exports split conversations
 * across multiple numbered files (conversations-000.json, conversations-001.json, …).
 */
object UploadState {

    @Volatile var pendingJsonFiles:  List<File>        = emptyList()
    @Volatile var pendingMediaMap:   Map<String, File> = emptyMap()
    @Volatile var pendingConvCount:  Int               = 0
    @Volatile var pendingVideoFiles: List<File>        = emptyList()
    @Volatile var googleAccount:     Account?          = null

    val isReady: Boolean get() = pendingConvCount > 0 && pendingJsonFiles.isNotEmpty()

    fun clearData() {
        pendingJsonFiles.forEach { try { it.delete() } catch (_: Exception) {} }
        pendingJsonFiles  = emptyList()
        pendingMediaMap   = emptyMap()
        pendingConvCount  = 0
        pendingVideoFiles = emptyList()
    }
}
