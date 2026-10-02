package com.weekd.miracastreceiver.web

import android.util.Log
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small in-memory ring buffer exposed by the local WebUI diagnostics page. */
object WebLogBuffer {

    private const val MAX_ENTRIES = 600
    private val entries = ArrayDeque<Entry>(MAX_ENTRIES)
    private val lock = Any()

    data class Entry(
        val timestampMs: Long,
        val level: String,
        val tag: String,
        val message: String
    )

    val timberTree: Timber.Tree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            val level = when (priority) {
                Log.VERBOSE -> "V"
                Log.DEBUG -> "D"
                Log.INFO -> "I"
                Log.WARN -> "W"
                Log.ERROR -> "E"
                Log.ASSERT -> "A"
                else -> priority.toString()
            }
            val fullMessage = if (t == null) message else "$message\n${Log.getStackTraceString(t)}"
            synchronized(lock) {
                while (entries.size >= MAX_ENTRIES) entries.removeFirst()
                entries.addLast(
                    Entry(
                        timestampMs = System.currentTimeMillis(),
                        level = level,
                        tag = tag.orEmpty(),
                        message = fullMessage.take(12_000)
                    )
                )
            }
        }
    }

    fun snapshot(limit: Int = 250): List<Entry> = synchronized(lock) {
        entries.takeLast(limit.coerceIn(1, MAX_ENTRIES)).toList()
    }

    fun clear() = synchronized(lock) { entries.clear() }

    fun formatTimestamp(timestampMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(timestampMs))
}
