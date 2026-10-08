// SPDX-License-Identifier: GPL-3.0-or-later
package com.kerneldroid.aiengines

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The module's own journal: the reason a chat file would not load, the reason a
 * provider call failed, the reason a turn died.
 *
 * It is a copy of the host app's `com.kerneldroid.rumo.data.AppLog`, not a facade
 * over it: a library that reached into its host's log would not build on its own,
 * and the host would have to keep an entry point alive for a consumer it does not
 * know about. The cost of the copy is real and worth naming — these entries do not
 * appear in the host's exported log report — and the alternative (an injected
 * sink the host points at its own log) is a deliberate future change rather than
 * something to smuggle in during a move.
 *
 * Properties, kept from the original: thread-safe (synchronized), bounded
 * ([MAX_ENTRIES], old entries evicted), one entry = one line, never throws.
 */
object AppLog {
    enum class Level { INFO, WARN, ERROR }

    data class Entry(
        val seq: Long,
        val timestampMs: Long,
        val level: Level,
        val tag: String,
        val message: String,
    )

    /** Log ceiling: old entries are evicted, memory does not grow. */
    const val MAX_ENTRIES = 400

    private val lock = Any()
    private val entries = ArrayDeque<Entry>()
    private var seq = 0L

    // SimpleDateFormat is not thread-safe: we create one per render (the log
    // is read rarely, this is not a hot path).
    private fun timestamp(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).format(Date(ms))

    /**
     * One entry — one line: any newline (CR/LF/CRLF, including consecutive
     * ones) turns into the literal `\n`, so that the log stays greppable and a
     * single entry does not sprawl across several lines.
     */
    fun log(level: Level, tag: String, message: String) {
        try {
            val entry = Entry(
                seq = 0L,
                timestampMs = System.currentTimeMillis(),
                level = level,
                tag = tag,
                message = message.replace(Regex("[\\r\\n]+"), "\\\\n"),
            )
            synchronized(lock) {
                seq += 1
                entries.addLast(entry.copy(seq = seq))
                while (entries.size > MAX_ENTRIES) entries.removeFirst()
            }
        } catch (_: Throwable) {
            // The log must never break the calling code.
        }
    }

    fun info(tag: String, message: String) = log(Level.INFO, tag, message)

    fun warn(tag: String, message: String) = log(Level.WARN, tag, message)

    fun error(tag: String, message: String) = log(Level.ERROR, tag, message)

    /** Exception string for catch sites: class + message on one line. */
    fun describe(t: Throwable): String {
        val msg = t.message?.takeIf { it.isNotEmpty() } ?: "no message"
        return "${t.javaClass.name}: $msg"
    }

    fun error(tag: String, prefix: String, t: Throwable) =
        error(tag, "$prefix: ${describe(t)}")

    /** Copy of the log in order of appearance (oldest first). */
    fun snapshot(): List<Entry> = synchronized(lock) { entries.toList() }

    /** The whole log as text: `timestamp LEVEL tag: message`, one line per entry. */
    fun text(): String {
        val copy = snapshot()
        if (copy.isEmpty()) return "(empty)\n"
        val sb = StringBuilder(copy.size * 64)
        for (e in copy) {
            sb.append(timestamp(e.timestampMs))
                .append(' ').append(e.level.name)
                .append(' ').append(e.tag)
                .append(": ").append(e.message)
                .append('\n')
        }
        return sb.toString()
    }

    fun clear() {
        try {
            synchronized(lock) { entries.clear() }
        } catch (_: Throwable) {
        }
    }
}
