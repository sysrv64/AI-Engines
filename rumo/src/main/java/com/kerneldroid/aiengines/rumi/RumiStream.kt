// SPDX-License-Identifier: GPL-3.0-or-later
package com.kerneldroid.aiengines.rumi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * One streaming request to the model — the thing the main conversation and a
 * sub-agent have in common.
 *
 * Extracted from [RumiSession] for the sake of sub-agents: they have the same
 * protocol, the same SSE parsing and the same tool-call assembly, but no chat
 * history, no header and no snapshot budget. A copy of this loop would drift
 * from the original at the first protocol change, so there is one of it.
 *
 * The differences that are not here and should not be stay with the caller:
 * what exactly goes into `system`, which history is sent, how often the screen
 * is refreshed. All of that is parameters, not branches inside.
 */
internal object RumiStream {

    /** One tool call assembled from the argument deltas. */
    data class Call(val id: String, val name: String, val arguments: String)

    /** What the model said and asked for in one request. */
    data class Answer(
        val text: String,
        val reasoning: String,
        val calls: List<Call>,
        /** Provider error if the request failed. */
        val failure: String?,
    )

    /**
     * Send a request and read the stream to the end.
     *
     * [onProgress] is called during parsing, not only at the end: the caller
     * decides for itself how often to turn that into a screen refresh. [force] is
     * "this event is worth showing at once", not "wait for your interval": the
     * start of a tool call and an error matter more than the next chunk of text.
     *
     * [cancelled] is polled between stream lines: a stop must halt reading, not
     * only hide the result.
     */
    suspend fun once(
        url: String,
        protocol: RumiProtocol,
        modelId: String,
        credential: String,
        sessionId: String,
        /**
         * Who answers. The authorisation headers, API versions and the session
         * flag are properties of the provider, not constants: for Anthropic the
         * key sits in `x-api-key` and `anthropic-version` is mandatory next to
         * it, while OpenCode gateways use `Bearer` and `x-opencode-session`, and
         * sending them crosswise means a refusal out of nowhere.
         */
        provider: RumiProvider,
        system: String,
        messages: List<RumiMessage>,
        tools: List<RumiToolSpec>,
        maxOutputTokens: Int,
        /** The reasoning depth the user picked that suits this model. */
        thinking: RumiThinkingChoice,
        /** The name of the field in which the model states its reasoning; `null` means unknown. */
        reasoningField: String?,
        cancelled: () -> Boolean,
        onProgress: (answer: Answer, force: Boolean) -> Unit,
    ): Answer {
        val request = RumiWire.request(
            protocol = protocol,
            model = modelId,
            system = system,
            messages = messages,
            tools = tools,
            maxOutputTokens = maxOutputTokens,
            thinking = thinking,
        )
        val headers = buildMap {
            putAll(RumiProviders.authHeaders(provider, credential))
            put("content-type", "application/json")
            put("accept", "text/event-stream")
            // The client always names itself: the gateways ask for it too, and
            // it is generally the polite form of addressing someone else's API.
            put("user-agent", USER_AGENT)
            putAll(provider.extraHeaders)
            // The session identifier is only sent where it is expected: a
            // foreign service does not need it, and an extra header is one more
            // reason for a refusal that then has to be hunted for in someone
            // else's documentation.
            if (provider.sessionHeader) put("x-opencode-session", sessionId)
        }

        val text = StringBuilder()
        val reasoning = StringBuilder()
        val calls = LinkedHashMap<Int, CallBuilder>()
        var failure: String? = null

        fun snapshot() = Answer(
            text = text.toString(),
            reasoning = reasoning.toString(),
            calls = calls.entries
                .filter { it.value.name.isNotEmpty() }
                .map { (index, builder) ->
                    Call(
                        // Providers that do not send a call identifier must
                        // still get distinguishable ones: the result is matched
                        // by it.
                        id = builder.id.ifEmpty { "call-$index" },
                        name = builder.name,
                        arguments = builder.arguments.toString().ifBlank { "{}" },
                    )
                },
            failure = failure,
        )

        val reply = withContext(Dispatchers.IO) {
            RumiHttp.postSse(
                url = url,
                headers = headers,
                body = request,
                stop = cancelled,
            ) { payload ->
                if (RumiWire.isDone(protocol, payload)) return@postSse
                RumiWire.decode(protocol, payload, reasoningField).forEach { event ->
                    when (event) {
                        is RumiEvent.Text -> {
                            text.append(event.delta)
                            onProgress(snapshot(), false)
                        }
                        is RumiEvent.Reasoning -> {
                            reasoning.append(event.delta)
                            onProgress(snapshot(), false)
                        }
                        is RumiEvent.ToolStart -> {
                            val builder = calls.getOrPut(event.index) { CallBuilder() }
                            if (event.id.isNotEmpty()) builder.id = event.id
                            if (event.name.isNotEmpty()) builder.name = event.name
                            onProgress(snapshot(), true)
                        }
                        is RumiEvent.ToolArgs -> {
                            calls.getOrPut(event.index) { CallBuilder() }
                                .arguments.append(event.delta)
                            onProgress(snapshot(), false)
                        }
                        is RumiEvent.Finish -> Unit
                        is RumiEvent.Failure -> {
                            failure = event.message
                            onProgress(snapshot(), true)
                        }
                    }
                }
            }
        }

        if (!reply.ok) {
            // Text that arrived before the break is not thrown away: it has
            // already been shown, and the model did say it.
            failure = httpReason(reply.status, reply.body)
        }
        val answer = snapshot()
        onProgress(answer, true)
        return answer
    }

    /** Accumulator of one call while its arguments arrive in parts. */
    private class CallBuilder {
        var id: String = ""
        var name: String = ""
        val arguments = StringBuilder()
    }

    /**
     * What to say about an unsuccessful provider response.
     *
     * The codes are spelled out by meaning rather than collapsed to "HTTP 4xx":
     * "the key was rejected" and "the credits ran out" require different actions
     * from the user, and exactly the one that is needed must be named.
     */
    internal fun httpReason(status: Int, body: String): String {
        val detail = extractError(body)
        val hint = when (status) {
            401, 403 ->
                " The credential was refused: sign in again, or paste an API key, " +
                    "in Settings → Rumi."
            402 -> " The account is out of credit for this model."
            404 -> " The model may not be available to this account."
            429 -> " Rate limited; try again shortly."
            else -> ""
        }
        return "The provider answered $status${if (detail.isEmpty()) "" else ": $detail"}.$hint".trim()
    }

    /** Pull a readable message out of any of the three error shapes. */
    private fun extractError(body: String): String {
        if (body.isBlank()) return ""
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return shorten(body)
        root.optJSONObject("error")?.let { error ->
            error.optText("message")?.let { return shorten(it) }
        }
        val direct = root.optText("error")
        if (direct != null && direct != "true") return shorten(direct)
        root.optText("error_description")?.let { return shorten(it) }
        root.optText("message")?.let { return shorten(it) }
        return ""
    }

    private fun shorten(text: String): String =
        text.replace('\n', ' ').let { if (it.length <= 300) it else it.take(300) + "…" }

    private const val USER_AGENT = "rumo/0.1 (Android; Rumi)"
}
