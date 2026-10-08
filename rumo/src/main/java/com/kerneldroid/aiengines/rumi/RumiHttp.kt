// SPDX-License-Identifier: GPL-3.0-or-later
package com.kerneldroid.aiengines.rumi

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * The bottom layer of Rumo's network access: plain HTTP plus the Server-Sent
 * Events framing the OpenAI and Anthropic streaming endpoints speak.
 *
 * It exists so neither the assistant nor the shop pulls in an HTTP client
 * dependency and stays unit-testable on a plain JVM (no Android classes are
 * referenced here), and so request/stream handling lives in one place instead
 * of once per caller.
 *
 * Nothing in this object suspends: every call blocks on network I/O, so it must
 * be invoked from a background thread — normally wrapped by the caller in
 * withContext(Dispatchers.IO). Calling it on the main thread would freeze the UI.
 */
object RumiHttp {
    /** A finished response: status code plus the whole body (or the error body). */
    data class Reply(val status: Int, val body: String) {
        val ok: Boolean get() = status in 200..299
    }

    /**
     * A finished binary response.
     *
     * [tooLarge] means the body ran past the caller's cap and was abandoned
     * mid-stream: [bytes] is empty and [status] is the response's own, because
     * the server answered fine — it is the caller that refused the size. A
     * shop that silently truncated a font would hand the engine a corrupt face,
     * so "refused" is a separate state from "received".
     */
    data class BytesReply(
        val status: Int,
        val bytes: ByteArray,
        val tooLarge: Boolean = false,
    ) {
        val ok: Boolean get() = status in 200..299

        // ByteArray has identity equality; the generated one would make two
        // equal replies compare unequal, which is a trap for a caller that
        // caches by value.
        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is BytesReply &&
                    status == other.status &&
                    tooLarge == other.tooLarge &&
                    bytes.contentEquals(other.bytes))

        override fun hashCode(): Int =
            (status * 31 + bytes.contentHashCode()) * 31 + tooLarge.hashCode()
    }

    /** One `data:` payload from an SSE stream. */
    fun interface OnEvent { fun data(payload: String) }

    private const val USER_AGENT = "Rumo/1.0 (Android)"
    private const val CONNECT_TIMEOUT_MS = 20_000

    // Reads a body as UTF-8 and never returns null: a missing error stream (some
    // providers close without one) becomes an empty body rather than a crash.
    private fun readAll(stream: InputStream?): String {
        if (stream == null) return ""
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
    }

    // Opens the connection without applying any request headers: callers add their
    // own after this, so the protocol-specific Content-Type/Accept can be set first
    // and a caller-supplied header can still override it.
    private fun open(
        url: String,
        method: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.doInput = true
        connection.useCaches = false
        connection.setRequestProperty("User-Agent", USER_AGENT)
        return connection
    }

    private fun configure(
        connection: HttpURLConnection,
        contentType: String?,
        accept: String,
        headers: Map<String, String>,
    ) {
        if (contentType != null) connection.setRequestProperty("Content-Type", contentType)
        connection.setRequestProperty("Accept", accept)
        for ((name, value) in headers) connection.setRequestProperty(name, value)
    }

    private fun bodyStream(connection: HttpURLConnection, status: Int): InputStream? =
        if (status in 200..299) connection.inputStream else connection.errorStream

    /**
     * GET [url] and read the whole body. Blocking, so call it off the main thread.
     *
     * A non-2xx status is returned rather than thrown, so the caller can show the
     * provider's own error text instead of a generic failure. Only a transport
     * failure (IOException) propagates.
     */
    fun getJson(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Int = 20_000,
        accept: String = "application/json",
    ): Reply {
        val connection = open(url, "GET", timeoutMs, timeoutMs)
        try {
            configure(connection, null, accept, headers)
            val status = connection.responseCode
            return Reply(status, readAll(bodyStream(connection, status)))
        } finally {
            connection.disconnect()
        }
    }

    /**
     * GET [url] and read the whole body as bytes. Blocking, so call it off the
     * main thread.
     *
     * [limitBytes] > 0 caps the download: past it the stream is abandoned and
     * [BytesReply.tooLarge] is set instead of returning a half file. A caller
     * that would rather waste bandwidth than show a "too big" state passes 0.
     *
     * The body is read in chunks rather than with `readBytes()` so the cap can
     * stop the transfer mid-flight and so a caller can report progress on a
     * large asset.
     */
    fun getBytes(
        url: String,
        headers: Map<String, String>,
        limitBytes: Long = 0L,
        timeoutMs: Int = 60_000,
        accept: String = "*/*",
        onProgress: ((read: Long, total: Long) -> Unit)? = null,
    ): BytesReply {
        val connection = open(url, "GET", timeoutMs, timeoutMs)
        try {
            configure(connection, null, accept, headers)
            val status = connection.responseCode
            val stream = bodyStream(connection, status)
                ?: return BytesReply(status, ByteArray(0))
            val total = connection.contentLengthLong
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            stream.use {
                while (true) {
                    val read = it.read(buffer)
                    if (read <= 0) break
                    if (limitBytes > 0 && out.size().toLong() + read > limitBytes) {
                        return BytesReply(status, ByteArray(0), tooLarge = true)
                    }
                    out.write(buffer, 0, read)
                    onProgress?.invoke(out.size().toLong(), total)
                }
            }
            return BytesReply(status, out.toByteArray())
        } finally {
            connection.disconnect()
        }
    }

    /**
     * POST [body] as JSON and read the whole reply. Blocking, so call it off the
     * main thread.
     *
     * As with [getJson], a non-2xx status is returned whole instead of thrown, and
     * only an IOException propagates.
     */
    fun postJson(
        url: String,
        headers: Map<String, String>,
        body: String,
        timeoutMs: Int = 60_000,
    ): Reply {
        val connection = open(url, "POST", timeoutMs, timeoutMs)
        try {
            configure(connection, "application/json; charset=utf-8", "application/json", headers)
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            return Reply(status, readAll(bodyStream(connection, status)))
        } finally {
            connection.disconnect()
        }
    }

    /**
     * POST [body] as JSON and read the whole reply **as bytes**. Blocking, so
     * call it off the main thread.
     *
     * A separate entry point rather than [postJson] with the body read into a string: for TTS
     * endpoints the response body is the sound itself, and reading it as UTF-8 means
     * irreversibly corrupting every byte above `0x7f`. Services still send an error
     * as text, so on a non-2xx the caller is free to decode the
     * bytes itself.
     *
     * [limitBytes] > 0 behaves as in [getBytes]: overflow is
     * [BytesReply.tooLarge], not half a file.
     */
    fun postBytes(
        url: String,
        headers: Map<String, String>,
        body: String,
        limitBytes: Long = 0L,
        timeoutMs: Int = 300_000,
        accept: String = "*/*",
    ): BytesReply {
        val connection = open(url, "POST", CONNECT_TIMEOUT_MS, timeoutMs)
        try {
            configure(connection, "application/json; charset=utf-8", accept, headers)
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = bodyStream(connection, status)
                ?: return BytesReply(status, ByteArray(0))
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            stream.use {
                while (true) {
                    val read = it.read(buffer)
                    if (read <= 0) break
                    if (limitBytes > 0 && out.size().toLong() + read > limitBytes) {
                        return BytesReply(status, ByteArray(0), tooLarge = true)
                    }
                    out.write(buffer, 0, read)
                }
            }
            return BytesReply(status, out.toByteArray())
        } finally {
            connection.disconnect()
        }
    }

    /**
     * POST a JSON body and stream the Server-Sent Events reply.
     * Blocking, so call it off the main thread.
     *
     * Returns the terminal status; a non-2xx reply is returned whole (not streamed)
     * so the caller can display the provider's error message. [stop] is polled
     * before each read; returning true aborts the connection, which closes the
     * stream and yields an empty body with the 2xx status — the caller should treat
     * an empty body as an interrupted turn. A blocked read is bounded by
     * [readTimeoutMs], so [stop] cannot cut a read short mid-flight.
     *
     * An IOException is deliberately propagated: the caller owns error reporting,
     * and swallowing it here would silently truncate an assistant reply.
     */
    fun postSse(
        url: String,
        headers: Map<String, String>,
        body: String,
        readTimeoutMs: Int = 300_000,
        stop: () -> Boolean = { false },
        onEvent: OnEvent,
    ): Reply {
        val connection = open(url, "POST", CONNECT_TIMEOUT_MS, readTimeoutMs)
        try {
            configure(
                connection,
                "application/json; charset=utf-8",
                "text/event-stream",
                headers,
            )
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) {
                // Not an event stream: read it whole so the provider's error survives.
                return Reply(status, readAll(connection.errorStream))
            }
            BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
                val data = StringBuilder()
                var hasData = false
                while (true) {
                    if (stop()) break
                    val line = reader.readLine() ?: break
                    when {
                        // A blank line terminates the event; several `data:` lines in
                        // one block are one payload joined with newlines.
                        line.isBlank() -> {
                            if (hasData) {
                                onEvent.data(data.toString())
                                data.setLength(0)
                                hasData = false
                            }
                        }
                        line.startsWith("data:") -> {
                            if (hasData) data.append('\n')
                            data.append(line.substring("data:".length).removePrefix(" "))
                            hasData = true
                        }
                        // `:` comments are keep-alives; event:/id:/retry: fields do not
                        // matter to a client that only consumes payloads.
                        else -> Unit
                    }
                }
                // No trailing blank line: flush the block so the final event is not lost.
                if (hasData) onEvent.data(data.toString())
            }
            return Reply(status, "")
        } finally {
            connection.disconnect()
        }
    }
}
