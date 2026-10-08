// SPDX-License-Identifier: GPL-3.0-or-later
package com.kerneldroid.aiengines.rumi

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Which wire protocol a model's SDK family uses. */
enum class RumiProtocol { Chat, Responses, Messages }

/** One tool the model may call. [schemaJson] is a JSON Schema object. */
data class RumiToolSpec(val name: String, val description: String, val schemaJson: String)

/** One tool call the model requested. [arguments] is raw JSON text. */
data class RumiToolCall(val id: String, val name: String, val arguments: String)

/**
 * One conversation message. Exactly one of [text]/[toolCalls]/[toolCallId] is
 * meaningful per role: a user or assistant turn carries [text] (and images), an
 * assistant turn may carry [toolCalls], and a tool turn carries [toolCallId]
 * plus the tool's [text] output.
 */
data class RumiMessage(
    val role: String,                 // "user" | "assistant" | "tool"
    val text: String? = null,
    val toolCalls: List<RumiToolCall> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
    /** Base64 PNG payloads (no `data:` prefix) to send as vision input. */
    val images: List<String> = emptyList(),
)

/** One incremental event decoded from a provider stream. */
sealed interface RumiEvent {
    data class Text(val delta: String) : RumiEvent
    data class Reasoning(val delta: String) : RumiEvent
    /** A tool call began; later [ToolArgs] events append its arguments. */
    data class ToolStart(val index: Int, val id: String, val name: String) : RumiEvent
    data class ToolArgs(val index: Int, val delta: String) : RumiEvent
    data class Finish(val reason: String?) : RumiEvent
    data class Failure(val message: String) : RumiEvent
}

/**
 * Serialises one turn in whichever of the three provider protocols the selected
 * model speaks, and decodes that provider's SSE payloads back into [RumiEvent]s.
 *
 * Everything here is pure string/JSON work: no I/O, no Android types, so the
 * protocol mapping can be exercised in plain JVM tests. Network transport lives
 * in [RumiHttp]; the two are joined by the session layer.
 */
object RumiWire {
    /**
     * Request body for one streaming turn.
     * [maxOutputTokens] is required by the Messages protocol and clamped elsewhere.
     */
    fun request(
        protocol: RumiProtocol,
        model: String,
        system: String,
        messages: List<RumiMessage>,
        tools: List<RumiToolSpec>,
        maxOutputTokens: Int = 8192,
        thinking: RumiThinkingChoice = RumiThinkingChoice.Auto,
    ): String = when (protocol) {
        RumiProtocol.Chat -> chatRequest(model, system, messages, tools, maxOutputTokens, thinking)
        RumiProtocol.Responses -> responsesRequest(model, system, messages, tools, maxOutputTokens, thinking)
        RumiProtocol.Messages -> messagesRequest(model, system, messages, tools, maxOutputTokens, thinking)
    }

    /**
     * Put the thinking-depth choice into the request body.
     *
     * Each protocol has its own parameter name and its own shape, and these are not
     * interchangeable: `reasoning_effort` as a string, `reasoning.effort`
     * as an object, `thinking.budget_tokens` as a number. Sending the wrong one is a
     * 400 from the provider out of nowhere, so the shape is chosen by protocol, not
     * guessed.
     *
     * `Auto` sends nothing: "as intended" is expressed by the absence of a
     * parameter, not by a value that has to be guessed.
     */
    private fun putThinking(
        root: JSONObject,
        protocol: RumiProtocol,
        choice: RumiThinkingChoice,
        maxOutputTokens: Int,
    ) {
        if (choice is RumiThinkingChoice.Auto) return
        when (protocol) {
            RumiProtocol.Chat -> {
                // The off switch is expressed by the absence of thinking: for a model
                // that always thinks, switching off is unreachable, and that is its
                // property, not ours.
                val effort = when (choice) {
                    is RumiThinkingChoice.Effort -> choice.value
                    RumiThinkingChoice.On -> "medium"
                    RumiThinkingChoice.Off -> "none"
                    is RumiThinkingChoice.Budget -> budgetEffort(choice.tokens)
                    RumiThinkingChoice.Auto -> return
                }
                root.put("reasoning_effort", effort)
            }
            RumiProtocol.Responses -> {
                val effort = when (choice) {
                    is RumiThinkingChoice.Effort -> choice.value
                    RumiThinkingChoice.On -> "medium"
                    RumiThinkingChoice.Off -> "none"
                    is RumiThinkingChoice.Budget -> budgetEffort(choice.tokens)
                    RumiThinkingChoice.Auto -> return
                }
                // `summary: auto` is asked for together with the level: without it the stream
                // brings reasoning for not all models, and the screen shows
                // emptiness where the model was thinking.
                root.put(
                    "reasoning",
                    JSONObject().put("effort", effort).put("summary", "auto"),
                )
            }
            RumiProtocol.Messages -> {
                when (choice) {
                    is RumiThinkingChoice.Budget -> root.put(
                        "thinking",
                        JSONObject()
                            .put("type", "enabled")
                            // The budget must be less than the response limit: a budget
                            // that is larger is a refusal, not "think properly".
                            .put(
                                "budget_tokens",
                                choice.tokens.coerceIn(BUDGET_MIN, (maxOutputTokens - 1).coerceAtLeast(BUDGET_MIN)),
                            ),
                    )
                    RumiThinkingChoice.Off -> root.put(
                        "thinking",
                        JSONObject().put("type", "disabled"),
                    )
                    else -> {
                        val effort = when (choice) {
                            is RumiThinkingChoice.Effort -> choice.value
                            RumiThinkingChoice.On -> "medium"
                            else -> return
                        }
                        root.put("output_config", JSONObject().put("effort", effort))
                    }
                }
            }
        }
    }

    /**
     * A budget in tokens, named as a level.
     *
     * Needed where the model accepts a budget but the protocol takes levels: Chat and
     * Responses have no field for a budget, and the only way to convey "how much"
     * is to pick the nearest step.
     */
    private fun budgetEffort(tokens: Int): String = when {
        tokens <= 4_096 -> "low"
        tokens <= 16_384 -> "medium"
        tokens <= 65_536 -> "high"
        else -> "xhigh"
    }

    private const val BUDGET_MIN = 1_024

    /** True when this payload terminates the stream. */
    fun isDone(protocol: RumiProtocol, payload: String): Boolean {
        // Chat is the only protocol with a bare sentinel rather than an envelope.
        if (protocol == RumiProtocol.Chat) return payload.trim() == "[DONE]"
        return try {
            val type = JSONObject(payload).optText("type").orEmpty()
            when (protocol) {
                RumiProtocol.Responses -> type == "response.completed" ||
                    type == "response.failed" ||
                    type == "response.incomplete" ||
                    type == "error"

                RumiProtocol.Messages -> type == "message_stop" || type == "error"
                RumiProtocol.Chat -> false
            }
        } catch (_: JSONException) {
            false
        }
    }

    /** Decode one SSE payload into events. Unknown or malformed payloads yield an empty list. */
    /**
     * Decode one stream frame.
     *
     * [reasoningField] is the name of the field in which this model names reasoning,
     * if the catalog knows it. Needed only by the Chat protocol: in Responses and
     * Messages reasoning arrives as events with their own type, while in Chat it is an
     * ordinary field inside `delta`, and the name differs between models.
     */
    fun decode(
        protocol: RumiProtocol,
        payload: String,
        reasoningField: String? = null,
    ): List<RumiEvent> = try {
        when (protocol) {
            RumiProtocol.Chat -> decodeChat(payload, reasoningField)
            RumiProtocol.Responses -> decodeResponses(payload)
            RumiProtocol.Messages -> decodeMessages(payload)
        }
    } catch (_: JSONException) {
        // A payload we cannot parse is not worth failing the whole turn over.
        emptyList()
    }

    // --- Request bodies ---

    private fun chatRequest(
        model: String,
        system: String,
        messages: List<RumiMessage>,
        tools: List<RumiToolSpec>,
        maxOutputTokens: Int,
        thinking: RumiThinkingChoice,
    ): String {
        val root = JSONObject()
        root.put("model", model)
        root.put("stream", true)
        val out = JSONArray()
        // A blank system prompt would only add noise, so it is omitted entirely.
        if (system.isNotEmpty()) {
            out.put(JSONObject().put("role", "system").put("content", system))
        }
        for (m in messages) {
            out.put(chatMessage(m))
        }
        root.put("messages", out)
        if (tools.isNotEmpty()) {
            val arr = JSONArray()
            for (t in tools) {
                arr.put(
                    JSONObject()
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject()
                                .put("name", t.name)
                                .put("description", t.description)
                                .put("parameters", objectOrEmpty(t.schemaJson)),
                        ),
                )
            }
            root.put("tools", arr)
        root.put("tool_choice", "auto")
        }
        root.put("max_tokens", maxOutputTokens)
        putThinking(root, RumiProtocol.Chat, thinking, maxOutputTokens)
        return root.toString()
    }

    private fun chatMessage(m: RumiMessage): JSONObject {
        val obj = JSONObject()
        when {
            m.toolCallId != null -> {
                obj.put("role", "tool")
                obj.put("tool_call_id", m.toolCallId)
                obj.put("content", m.text ?: "")
            }

            m.role == "assistant" -> {
                obj.put("role", "assistant")
                // OpenAI wants an explicit null when the turn is tool calls only.
                obj.put("content", if (m.text.isNullOrEmpty()) JSONObject.NULL else m.text)
                if (m.toolCalls.isNotEmpty()) {
                    val calls = JSONArray()
                    for (tc in m.toolCalls) {
                        calls.put(
                            JSONObject()
                                .put("id", tc.id)
                                .put("type", "function")
                                .put(
                                    "function",
                                    JSONObject()
                                        .put("name", tc.name)
                                        .put("arguments", tc.arguments),
                                ),
                        )
                    }
                    obj.put("tool_calls", calls)
                }
            }

            m.images.isEmpty() -> {
                obj.put("role", "user")
                obj.put("content", m.text ?: "")
            }

            else -> {
                // Vision input needs the content-parts form rather than a plain string.
                val content = JSONArray()
                if (!m.text.isNullOrEmpty()) {
                    content.put(JSONObject().put("type", "text").put("text", m.text))
                }
                for (image in m.images) {
                    content.put(
                        JSONObject()
                            .put("type", "image_url")
                            .put("image_url", JSONObject().put("url", pngDataUrl(image))),
                    )
                }
                obj.put("role", "user")
                obj.put("content", content)
            }
        }
        return obj
    }

    private fun responsesRequest(
        model: String,
        system: String,
        messages: List<RumiMessage>,
        tools: List<RumiToolSpec>,
        maxOutputTokens: Int,
        thinking: RumiThinkingChoice,
    ): String {
        val root = JSONObject()
        root.put("model", model)
        root.put("stream", true)
        root.put("instructions", system)
        val input = JSONArray()
        for (m in messages) {
            when {
                // A tool result is a bare item, not a message.
                m.toolCallId != null -> input.put(
                    JSONObject()
                        .put("type", "function_call_output")
                        .put("call_id", m.toolCallId)
                        .put("output", m.text ?: ""),
                )

                m.role == "assistant" -> {
                    if (m.toolCalls.isEmpty()) {
                        // Only a text-only assistant turn becomes a normal message.
                        input.put(
                            JSONObject()
                                .put("role", "assistant")
                                .put(
                                    "content",
                                    JSONArray().put(
                                        JSONObject()
                                            .put("type", "output_text")
                                            .put("text", m.text ?: ""),
                                    ),
                                ),
                        )
                    } else {
                        // One item per call; the Responses API has no role here.
                        for (tc in m.toolCalls) {
                            input.put(
                                JSONObject()
                                    .put("type", "function_call")
                                    .put("call_id", tc.id)
                                    .put("name", tc.name)
                                    .put("arguments", tc.arguments),
                            )
                        }
                    }
                }

                else -> {
                    val content = JSONArray()
                    if (!m.text.isNullOrEmpty()) {
                        content.put(JSONObject().put("type", "input_text").put("text", m.text))
                    }
                    for (image in m.images) {
                        content.put(
                            JSONObject()
                                .put("type", "input_image")
                                .put("image_url", pngDataUrl(image)),
                        )
                    }
                    // Never emit an empty content array; it is rejected upstream.
                    if (content.length() == 0) {
                        content.put(JSONObject().put("type", "input_text").put("text", ""))
                    }
                    input.put(JSONObject().put("role", "user").put("content", content))
                }
            }
        }
        root.put("input", input)
        if (tools.isNotEmpty()) {
            val arr = JSONArray()
            for (t in tools) {
                arr.put(
                    JSONObject()
                        .put("type", "function")
                        .put("name", t.name)
                        .put("description", t.description)
                        .put("parameters", objectOrEmpty(t.schemaJson))
                        .put("strict", false),
                )
            }
            root.put("tools", arr)
        }
        root.put("max_output_tokens", maxOutputTokens)
        root.put("store", false)
        putThinking(root, RumiProtocol.Responses, thinking, maxOutputTokens)
        return root.toString()
    }

    private fun messagesRequest(
        model: String,
        system: String,
        messages: List<RumiMessage>,
        tools: List<RumiToolSpec>,
        maxOutputTokens: Int,
        thinking: RumiThinkingChoice,
    ): String {
        val root = JSONObject()
        root.put("model", model)
        root.put("stream", true)
        // Anthropic rejects a zero or missing max_tokens, so clamp defensively.
        root.put("max_tokens", maxOf(1, maxOutputTokens))
        root.put("system", system)
        val out = JSONArray()
        for (m in messages) {
            out.put(messagesMessage(m))
        }
        root.put("messages", out)
        if (tools.isNotEmpty()) {
            val arr = JSONArray()
            for (t in tools) {
                arr.put(
                    JSONObject()
                        .put("name", t.name)
                        .put("description", t.description)
                        // Anthropic wants the schema parsed, not a JSON string.
                        .put("input_schema", objectOrEmpty(t.schemaJson)),
                )
            }
            root.put("tools", arr)
        }
        putThinking(root, RumiProtocol.Messages, thinking, maxOutputTokens)
        return root.toString()
    }

    private fun messagesMessage(m: RumiMessage): JSONObject {
        val content = JSONArray()
        // Anthropic carries tool results in a user-role message.
        val role = if (m.role == "assistant" && m.toolCallId == null) "assistant" else "user"
        when {
            m.toolCallId != null -> content.put(
                JSONObject()
                    .put("type", "tool_result")
                    .put("tool_use_id", m.toolCallId)
                    .put("content", m.text ?: ""),
            )

            role == "assistant" -> {
                // A blank text block is invalid, so only emit one when there is text.
                if (!m.text.isNullOrEmpty()) {
                    content.put(JSONObject().put("type", "text").put("text", m.text))
                }
                for (tc in m.toolCalls) {
                    content.put(
                        JSONObject()
                            .put("type", "tool_use")
                            .put("id", tc.id)
                            .put("name", tc.name)
                            // Unparseable arguments degrade to {} rather than breaking the turn.
                            .put("input", objectOrEmpty(tc.arguments)),
                    )
                }
            }

            else -> {
                if (!m.text.isNullOrEmpty()) {
                    content.put(JSONObject().put("type", "text").put("text", m.text))
                }
                for (image in m.images) {
                    content.put(
                        JSONObject()
                            .put("type", "image")
                            .put(
                                "source",
                                JSONObject()
                                    .put("type", "base64")
                                    .put("media_type", "image/png")
                                    .put("data", image),
                            ),
                    )
                }
            }
        }
        // The engine rejects empty content, so a bare turn still gets an empty text block.
        if (content.length() == 0) {
            content.put(JSONObject().put("type", "text").put("text", ""))
        }
        return JSONObject().put("role", role).put("content", content)
    }

    // --- Stream decoding ---

    private fun decodeChat(payload: String, reasoningField: String?): List<RumiEvent> {
        val root = JSONObject(payload)
        val choices = root.optJSONArray("choices") ?: return emptyList()
        val out = ArrayList<RumiEvent>()
        for (i in 0 until choices.length()) {
            val choice = choices.optJSONObject(i) ?: continue
            val delta = choice.optJSONObject("delta")
            if (delta != null) {
                val text = delta.optText("content")
                if (!text.isNullOrEmpty()) out.add(RumiEvent.Text(text))
                // Reasoning in the Chat protocol is an ordinary field next to the text, and
                // the name differs between models. The catalog is asked first; the list
                // below is a fallback for a model the catalog does not have yet.
                // Without this, a model that thinks would show only the answer,
                // and would look like a model that does not think.
                val reasoning = reasoningDelta(delta, reasoningField)
                if (!reasoning.isNullOrEmpty()) out.add(RumiEvent.Reasoning(reasoning))
                val calls = delta.optJSONArray("tool_calls")
                if (calls != null) {
                    for (j in 0 until calls.length()) {
                        val call = calls.optJSONObject(j) ?: continue
                        val index = call.optInt("index", 0)
                        val function = call.optJSONObject("function")
                        val id = call.optText("id")
                        val name = function?.optText("name")
                        // id/name arrive only on the first fragment for an index.
                        if (!id.isNullOrEmpty() || !name.isNullOrEmpty()) {
                            out.add(RumiEvent.ToolStart(index, id ?: "", name ?: ""))
                        }
                        val args = function?.optText("arguments")
                        if (!args.isNullOrEmpty()) out.add(RumiEvent.ToolArgs(index, args))
                    }
                }
            }
            val finish = choice.optText("finish_reason")
            if (finish != null) out.add(RumiEvent.Finish(finish))
        }
        return out
    }

    /**
     * Pull the reasoning out of `delta`.
     *
     * Besides a string there can be a `reasoning_details` array — a list of pieces with a
     * `text` field; it is parsed the same way, because it is the same
     * reasoning, just cut up.
     */
    private fun reasoningDelta(delta: JSONObject, known: String?): String? {
        val names = buildList {
            known?.let { add(it) }
            add("reasoning_content")
            add("reasoning")
        }
        for (name in names) {
            when (val value = delta.opt(name)) {
                is String -> if (value.isNotEmpty()) return value
                is JSONArray -> {
                    val parts = ArrayList<String>(value.length())
                    for (i in 0 until value.length()) {
                        val item = value.optJSONObject(i) ?: continue
                        item.optText("text")?.let { parts.add(it) }
                    }
                    if (parts.isNotEmpty()) return parts.joinToString("")
                }
                else -> Unit
            }
        }
        return null
    }

    private fun decodeResponses(payload: String): List<RumiEvent> {
        val root = JSONObject(payload)
        return when (root.optText("type").orEmpty()) {
            "response.output_text.delta" -> {
                val delta = root.optText("delta") ?: return emptyList()
                listOf(RumiEvent.Text(delta))
            }

            "response.reasoning_summary_text.delta" -> {
                val delta = root.optText("delta") ?: return emptyList()
                listOf(RumiEvent.Reasoning(delta))
            }

            "response.output_item.added" -> {
                val item = root.optJSONObject("item") ?: return emptyList()
                if (item.optText("type").orEmpty() != "function_call") return emptyList()
                listOf(
                    RumiEvent.ToolStart(
                        index = root.optInt("output_index", 0),
                        id = item.optText("call_id").orEmpty(),
                        name = item.optText("name").orEmpty(),
                    ),
                )
            }

            "response.function_call_arguments.delta" -> {
                val delta = root.optText("delta") ?: return emptyList()
                listOf(RumiEvent.ToolArgs(root.optInt("output_index", 0), delta))
            }

            "response.completed" -> listOf(RumiEvent.Finish("completed"))
            "response.incomplete" -> listOf(RumiEvent.Finish("incomplete"))
            "response.failed" -> listOf(RumiEvent.Failure(failureMessage(root, "response failed")))
            "error" -> listOf(RumiEvent.Failure(failureMessage(root, "error")))
            else -> emptyList()
        }
    }

    private fun decodeMessages(payload: String): List<RumiEvent> {
        val root = JSONObject(payload)
        return when (root.optText("type").orEmpty()) {
            "content_block_start" -> {
                val block = root.optJSONObject("content_block") ?: return emptyList()
                // A text block start carries nothing worth reporting.
                if (block.optText("type").orEmpty() != "tool_use") return emptyList()
                listOf(
                    RumiEvent.ToolStart(
                        index = root.optInt("index", 0),
                        id = block.optText("id").orEmpty(),
                        name = block.optText("name").orEmpty(),
                    ),
                )
            }

            "content_block_delta" -> {
                val index = root.optInt("index", 0)
                val delta = root.optJSONObject("delta") ?: return emptyList()
                when (delta.optText("type").orEmpty()) {
                    "text_delta" -> {
                        val text = delta.optText("text") ?: return emptyList()
                        listOf(RumiEvent.Text(text))
                    }

                    "input_json_delta" -> {
                        val partial = delta.optText("partial_json") ?: return emptyList()
                        listOf(RumiEvent.ToolArgs(index, partial))
                    }

                    "thinking_delta" -> {
                        val thinking = delta.optText("thinking") ?: return emptyList()
                        listOf(RumiEvent.Reasoning(thinking))
                    }

                    else -> emptyList()
                }
            }

            // The stop reason is the turn's outcome; message_stop is only the terminator.
            "message_delta" -> listOf(
                RumiEvent.Finish(root.optJSONObject("delta")?.optText("stop_reason")),
            )

            "message_stop" -> listOf(RumiEvent.Finish(null))
            "error" -> listOf(RumiEvent.Failure(failureMessage(root, "error")))
            else -> emptyList()
        }
    }

    // --- Small JSON helpers ---

    // Any tool schema or argument text that is not a JSON object degrades to {} so
    // one malformed field cannot make the whole request body unusable.
    private fun objectOrEmpty(text: String): JSONObject = try {
        JSONObject(text)
    } catch (_: JSONException) {
        JSONObject()
    }

    private fun failureMessage(root: JSONObject, fallback: String): String {
        val direct = root.optJSONObject("error")?.optText("message")
        if (!direct.isNullOrEmpty()) return direct
        val nested = root.optJSONObject("response")
            ?.optJSONObject("error")
            ?.optText("message")
        if (!nested.isNullOrEmpty()) return nested
        return fallback
    }

    // Providers take base64 PNGs as a data URL / raw payload; the caller passes the
    // bare base64 both ways, so the prefix is added only where a provider needs it.
    private fun pngDataUrl(base64: String): String = "data:image/png;base64,$base64"
}
