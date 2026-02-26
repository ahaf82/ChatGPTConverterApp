package com.converter.chatgpt

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Privacy-safe in-memory logger for developer diagnostics.
 *
 * PRIVACY RULES — enforced by convention, never violated:
 *   ✗ Never log conversation text, titles, or message content
 *   ✗ Never log user account names or email addresses
 *   ✗ Never log conversation file names (which may contain the title)
 *   ✓ Safe to log: exception types, HTTP status codes, byte counts,
 *                  item counts, timing, phase names, file sizes
 *
 * Logs are forwarded to Android logcat and kept in a bounded ring buffer
 * (max [MAX_ENTRIES]) so they can be copied from the UI when an error
 * occurs, without writing any persistent file to the device.
 */
object AppLogger {

    private const val MAX_ENTRIES = 300
    private const val APP_LOG_TAG = "ChatGPTConverter"

    private val buffer = mutableListOf<Entry>()
    private val lock   = Any()

    data class Entry(
        val ms:  Long,
        val lvl: Char,        // D I W E
        val tag: String,
        val msg: String,
        val err: Throwable? = null
    )

    fun d(tag: String, msg: String)                       = append('D', tag, msg, null)  { Log.d(tag, msg) }
    fun i(tag: String, msg: String)                       = append('I', tag, msg, null)  { Log.i(tag, msg) }
    fun w(tag: String, msg: String, t: Throwable? = null) = append('W', tag, msg, t)     { Log.w(tag, msg, t) }
    fun e(tag: String, msg: String, t: Throwable? = null) = append('E', tag, msg, t)     { Log.e(tag, msg, t) }

    private inline fun append(lvl: Char, tag: String, msg: String, err: Throwable?, logcat: () -> Unit) {
        logcat()
        synchronized(lock) {
            if (buffer.size >= MAX_ENTRIES) buffer.removeAt(0)
            buffer.add(Entry(System.currentTimeMillis(), lvl, tag, msg, err))
        }
    }

    /**
     * Returns a plain-text dump of recent log entries, safe to share as a bug report.
     * Contains NO user data — only technical diagnostics.
     */
    fun dump(maxEntries: Int = 150): String {
        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        val sb  = StringBuilder()
        sb.append("=== $APP_LOG_TAG debug log (last $maxEntries entries) ===\n")
        sb.append("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, ")
        sb.append("API ${android.os.Build.VERSION.SDK_INT}\n\n")

        synchronized(lock) {
            buffer.takeLast(maxEntries).forEach { e ->
                sb.append("${sdf.format(Date(e.ms))} ${e.lvl} [${e.tag}] ${e.msg}\n")
                e.err?.let { t -> appendThrowable(sb, t, depth = 0) }
            }
        }
        return sb.toString()
    }

    private fun appendThrowable(sb: StringBuilder, t: Throwable, depth: Int) {
        val indent = "  ".repeat(depth + 1)
        sb.append("${indent}${t.javaClass.name}: ${t.message?.take(300)}\n")
        t.stackTrace.take(if (depth == 0) 12 else 5).forEach { f ->
            sb.append("$indent  at $f\n")
        }
        t.cause?.let { cause ->
            if (depth < 3) {
                sb.append("${indent}Caused by:\n")
                appendThrowable(sb, cause, depth + 1)
            }
        }
    }

    fun clear() = synchronized(lock) { buffer.clear() }

    fun entryCount() = synchronized(lock) { buffer.size }
}
