package com.converter.chatgpt

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton StateFlow for communicating progress from UploadService → MainActivity.
 * Because both run in the same process, no IPC is needed.
 */
object ProgressState {

    enum class Phase { IDLE, SCANNING, UPLOADING, SAVING, DONE, ERROR }

    /**
     * Wall-clock time (ms) when the current operation started.
     * Stored here — not in the Activity — so the timer survives foreground/background
     * transitions. Set by the service at the start of each operation.
     */
    @Volatile var operationStartMs: Long = 0L

    /**
     * Wall-clock time (ms) when the media pre-upload step started.
     * Used to drive a dedicated timer in the media progress card.
     * 0 means media upload has not started yet.
     */
    @Volatile var mediaStartMs: Long = 0L

    data class Progress(
        val phase: Phase = Phase.IDLE,

        // ── Conversation progress ─────────────────────────────
        val current: Int = 0,
        val total: Int = 0,

        // ── Media pre-upload progress (upload path only) ──────
        // Both are 0 when there are no media files or during the save path.
        val mediaCurrent: Int = 0,
        val mediaTotal:   Int = 0,

        val message: String = "",
        val errorMessage: String = "",

        /** true = this DONE signals a completed ZIP scan (show action buttons).
         *  false = this DONE signals a completed upload or save operation. */
        val afterScan: Boolean = false,

        /**
         * Technical details for developers — exception type, HTTP code, stack excerpt.
         * Only populated in Phase.ERROR. Never contains user data.
         * Shown in the UI only when the user explicitly asks (long-press on error text).
         */
        val debugInfo: String = ""
    )

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    fun update(p: Progress) { _progress.value = p }
    fun reset()              { _progress.value = Progress() }
}
