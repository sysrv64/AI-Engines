// SPDX-License-Identifier: GPL-3.0-or-later
package com.kerneldroid.aiengines.rumi

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import com.kerneldroid.aiengines.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.StringReader

/**
 * One model the gateway offers, with the metadata needed to talk to it.
 *
 * Nothing here is hardcoded per model: the id list comes from the gateway every
 * time it is refreshed, and the capabilities come from the shared model
 * catalogue. A model released tomorrow therefore appears without an app update.
 */
data class RumiModel(
    val id: String,
    val name: String,
    /** Which request/stream shape the model's SDK family speaks. */
    val protocol: RumiProtocol,
    /** Whether image input is accepted. Drives whether snapshots are sent. */
    val vision: Boolean,
    /** Whether function calling is supported. Without it Rumi cannot act. */
    val tools: Boolean,
    /** Whether the model reports a reasoning stream. */
    val reasoning: Boolean,
    val contextTokens: Int,
    /** True when the catalogue lists the model as free to use. */
    val free: Boolean = false,
    /**
     * What controls the reasoning depth for this model.
     *
     * It comes from the catalogue, not from a table in the app: each model has
     * its own set of levels — one has `low..max`, another only `high..max`, a
     * third a toggle — and a model released tomorrow brings its own.
     */
    val thinking: RumiThinking = RumiThinking.None,
    /**
     * The stream field in which the model states its reasoning.
     *
     * `null` means it does not state it, and that is not the same as "there is no
     * reasoning": a model without this field may have reasoning, but it does not
     * arrive as a separate line. It is asked of the catalogue, because guessing
     * the field name from the protocol is guessing blind, and an error here looks
     * like "the model does not think".
     */
    val reasoningField: String? = null,
) {
    /**
     * Shown next to the model in the picker.
     *
     * Takes a context because the tags are words the user reads, and the model
     * object is built before there is any language to read them in. The list is
     * joined rather than formatted from one pattern: the tags are independent of
     * each other, and their number differs from model to model.
     */
    fun summary(context: Context): String = buildList {
        add(protocolLabel)
        if (vision) add(context.getString(R.string.rumi_model_vision))
        if (!tools) add(context.getString(R.string.rumi_model_tag_no_tools))
        if (reasoning) add(context.getString(R.string.rumi_model_reasoning))
        if (contextTokens > 0) {
            add(context.getString(R.string.rumi_model_ctx, contextTokens / 1000))
        }
        if (free) add(context.getString(R.string.rumi_model_free))
    }.joinToString(" · ")

    val protocolLabel: String
        get() = when (protocol) {
            RumiProtocol.Chat -> "chat"
            RumiProtocol.Responses -> "responses"
            RumiProtocol.Messages -> "messages"
        }
}

/**
 * What controls the reasoning depth for a model.
 *
 * The catalogue describes it as a list of options rather than one value: a model
 * may have a toggle, a set of levels and a token budget at once, and those are
 * not mutually exclusive. The app takes the **first expressive** option — levels
 * if present, otherwise the toggle, otherwise the budget — because showing three
 * switches where the user needs one means shifting the format choice onto them.
 */
sealed interface RumiThinking {

    /** There is nothing to choose: the model thinks the way it is meant to, or does not think at all. */
    data object None : RumiThinking

    /** Discrete levels, as the model itself names them: `low`, `high`, `max`. */
    data class Effort(val values: List<String>) : RumiThinking

    /** On or off, with no intermediate steps. */
    data object Toggle : RumiThinking

    /** A budget in tokens: how much the model is allowed to spend on reasoning. */
    data class Budget(val min: Int, val max: Int) : RumiThinking

    companion object {
        /**
         * Assemble from the catalogue's `reasoning_options`.
         *
         * The preference order is levels, toggle, budget. It is chosen because
         * levels are expressed as buttons, while a budget is a number the user has
         * nothing to compare: "16384" tells them nothing, whereas "high" does.
         */
        fun from(options: List<Pair<String, Any?>>): RumiThinking {
            options.firstOrNull { it.first == "effort" }?.let { (_, payload) ->
                val values = (payload as? List<*>)?.filterIsInstance<String>().orEmpty()
                if (values.isNotEmpty()) return Effort(values)
            }
            if (options.any { it.first == "toggle" }) return Toggle
            options.firstOrNull { it.first == "budget_tokens" }?.let { (_, payload) ->
                val bounds = payload as? IntArray ?: return@let
                return Budget(min = bounds[0], max = bounds[1])
            }
            return None
        }
    }
}

/**
 * The user's choice.
 *
 * `Auto` means sending nothing and leaving the decision to the provider. That is
 * not the same as `Off`: "send nothing" means "as intended", while switching off
 * means "do not think", and for a model that always thinks the latter may be
 * unreachable.
 */
sealed interface RumiThinkingChoice {

    data object Auto : RumiThinkingChoice

    data object Off : RumiThinkingChoice

    data object On : RumiThinkingChoice

    data class Effort(val value: String) : RumiThinkingChoice

    data class Budget(val tokens: Int) : RumiThinkingChoice

    /** How this is stored in settings: one human-readable line in a file. */
    fun tag(): String = when (this) {
        Auto -> ""
        Off -> "off"
        On -> "on"
        is Effort -> "effort:$value"
        is Budget -> "budget:$tokens"
    }

    companion object {
        fun parse(tag: String): RumiThinkingChoice = when {
            tag.isEmpty() -> Auto
            tag == "off" -> Off
            tag == "on" -> On
            tag.startsWith("effort:") -> Effort(tag.removePrefix("effort:"))
            tag.startsWith("budget:") -> {
                tag.removePrefix("budget:").toIntOrNull()?.let { Budget(it) } ?: Auto
            }
            else -> Auto
        }

        /**
         * Does the choice suit this model.
         *
         * The choice outlives the model: the user sets `xhigh`, then switches to a
         * model that has no such level. Sending it anyway is a 400 from the
         * provider out of nowhere, so an unsuitable choice reads as `Auto`.
         */
        fun fits(choice: RumiThinkingChoice, thinking: RumiThinking): Boolean = when (thinking) {
            RumiThinking.None -> choice is Auto
            is RumiThinking.Effort -> when (choice) {
                is Auto -> true
                is Effort -> choice.value in thinking.values
                else -> false
            }
            RumiThinking.Toggle -> choice is Auto || choice is On || choice is Off
            is RumiThinking.Budget -> when (choice) {
                is Auto -> true
                is Budget -> choice.tokens in thinking.min..thinking.max
                else -> false
            }
        }
    }
}

/**
 * The model catalogue: a live id list from the gateway, joined with the shared
 * capability catalogue.
 *
 * The join is what keeps the app from shipping a table of model names that goes
 * stale. Two requests are involved:
 *
 *  - `GET {endpoint}/models` — the ids the account can actually use right now.
 *  - `GET https://models.dev/api.json` — per-model capabilities and the SDK
 *    family that decides which wire protocol to speak.
 *
 * The second document is several megabytes and describes every provider, so it
 * is streamed with [JsonReader] and only the gateway's own subtree is kept; the
 * extracted subset is then cached on disk, which is why the first refresh is
 * the only expensive one.
 */
object RumiModels {
    /** How long a cached catalogue is trusted before a refresh is attempted. */
    private const val CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000

    private const val CACHE_FILE = "rumi_models.json"

    /**
     * What went wrong, or what had to be assumed, when the list was built.
     *
     * Structured rather than a finished sentence, because the catalogue is read
     * on a worker with the raw `applicationContext` — the one `MainActivity`
     * passes to the controller — and a sentence built there would come out in
     * the *phone's* language while the interface was in the chosen one. The
     * sheet resolves these to resources at the point of display, where the
     * localised context is the one Compose is using.
     */
    sealed interface CatalogueNote {
        /** The live refresh failed and a cached copy was served. */
        data class RefreshFailed(val reason: String) : CatalogueNote

        /** Nothing was cached either, so there is no list at all. */
        data class LoadFailed(val reason: String) : CatalogueNote

        /** The provider exposes no model listing; the user types an id. */
        data object NoModelList : CatalogueNote

        /** The provider is not in the shared catalogue, so capabilities were assumed. */
        data class NotInCatalogue(val count: Int) : CatalogueNote

        /** The catalogue lookup failed for some other reason. */
        data class CapabilitiesFailed(val count: Int, val reason: String) : CatalogueNote
    }

    data class Catalogue(
        val models: List<RumiModel>,
        val fetchedAt: Long,
        /** True when the live refresh failed and a cached copy was served. */
        val stale: Boolean,
        /** What went wrong, or what was assumed; resolved to text by the caller. */
        val note: CatalogueNote? = null,
    ) {
        fun byId(id: String): RumiModel? = models.firstOrNull { it.id == id }
    }

    /**
     * Raised when the provider has no entry in the shared catalogue.
     *
     * A type rather than a message to match on: the caller has to tell this case
     * apart from a genuine lookup failure to word the note correctly, and
     * matching on the text of an exception is the kind of coupling that breaks
     * when someone rewords it.
     */
    private class NotInCatalogue : IllegalStateException()

    /**
     * The protocol an SDK family speaks. Anything unknown follows the provider.
     *
     * `@ai-sdk/openai-compatible` is the default family for most gateways, and for
     * a family this build has not heard of, the safest thing is to take the
     * provider's own protocol: for Anthropic that is `Messages`, and assuming
     * `Chat` there would mean sending a request of the wrong shape.
     */
    fun protocolFor(npm: String?, provider: RumiProvider): RumiProtocol = when (npm) {
        "@ai-sdk/anthropic" -> RumiProtocol.Messages
        "@ai-sdk/openai" -> RumiProtocol.Responses
        else -> provider.protocol
    }

    /**
     * Load the catalogue. Never runs on the main thread.
     *
     * [force] skips the cache and always asks the network, for the menu's
     * refresh action. A failed refresh falls back to the cache rather than
     * emptying the list, and says so through [Catalogue.note].
     */
    fun load(context: Context, force: Boolean = false): Catalogue {
        val providerId = RumiSettings.state.value.providerId
        val cached = readCache(context, providerId)
        if (!force && cached != null && System.currentTimeMillis() - cached.fetchedAt < CACHE_TTL_MS) {
            return cached.copy(stale = false, note = null)
        }
        val live = runCatching { refresh(context, providerId) }
        return live.getOrElse { error ->
            val reason = error.message ?: error::class.simpleName ?: "unknown error"
            if (cached != null) {
                cached.copy(stale = true, note = CatalogueNote.RefreshFailed(reason))
            } else {
                Catalogue(emptyList(), 0L, stale = true, note = CatalogueNote.LoadFailed(reason))
            }
        }
    }

    /** The network half of [load]: ids from the provider, capabilities from the catalogue. */
    private fun refresh(context: Context, providerId: String): Catalogue {
        val settings = RumiSettings.state.value
        val provider = settings.provider

        // There is nothing to ask of a provider without a model list, and the
        // user names the model. An empty response here is not a breakage: it is
        // "not entered yet", and inventing a list in its place would mean
        // offering someone else's models.
        if (provider.models == RumiModelList.Manual) {
            val typed = settings.modelId
            return Catalogue(
                models = if (typed.isEmpty()) emptyList() else listOf(merge(typed, null, provider)),
                fetchedAt = System.currentTimeMillis(),
                stale = false,
                note = if (typed.isEmpty()) CatalogueNote.NoModelList else null,
            )
        }

        val headers = buildMap {
            put("accept", "application/json")
            putAll(provider.extraHeaders)
            settings.credential()?.let { putAll(RumiProviders.authHeaders(provider, it)) }
        }
        val ids = RumiHttp.getJson("${settings.endpoint}${provider.modelsPath}", headers)
        if (!ids.ok) {
            throw IllegalStateException("provider answered ${ids.status}: ${shorten(ids.body)}")
        }
        val listed = parseIds(ids.body)

        // A catalogue failure is not fatal: the ids alone are still usable, with
        // capabilities assumed. The note says which. That keeps a fresh install
        // on a bad network from showing an empty model picker.
        val lookup = runCatching { fetchCatalogue(provider) }
        val known: Map<String, Meta> = lookup.getOrNull() ?: emptyMap()
        // One assignment each: Kotlin will not accept a `val` written from
        // several branches of a try/catch, and the failure it reports is about
        // reassignment rather than about the branch it could not prove.
        val note: CatalogueNote? = lookup.exceptionOrNull()?.let { error ->
            when (error) {
                is NotInCatalogue -> CatalogueNote.NotInCatalogue(listed.size)
                else -> CatalogueNote.CapabilitiesFailed(
                    listed.size,
                    error.message ?: "unknown error",
                )
            }
        }

        val models = listed.map { id -> merge(id, known[id], provider) }
        val catalogue = Catalogue(models, System.currentTimeMillis(), stale = false, note = note)
        runCatching { writeCache(context, catalogue, providerId) }
        return catalogue
    }

    /** Build one model from the provider id and whatever the catalogue knows. */
    private fun merge(id: String, meta: Meta?, provider: RumiProvider): RumiModel {
        if (meta == null) {
            // The provider offers it and the catalogue does not describe it yet:
            // a model newer than the catalogue. Assume the conservative shape
            // rather than hiding it, and let the picker's summary show what was
            // assumed only through the protocol label.
            return RumiModel(
                id = id,
                name = id,
                protocol = provider.protocol,
                vision = false,
                tools = true,
                reasoning = false,
                contextTokens = 0,
            )
        }
        return RumiModel(
            id = id,
            name = meta.name.ifBlank { id },
            protocol = protocolFor(meta.npm, provider),
            vision = meta.attachment && meta.inputs.contains("image"),
            tools = meta.tools,
            reasoning = meta.reasoning,
            contextTokens = meta.context,
            free = meta.free,
            thinking = meta.thinking,
            reasoningField = meta.reasoningField,
        )
    }

    private fun parseIds(body: String): List<String> {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val data = root.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<String>(data.length())
        for (i in 0 until data.length()) {
            val id = data.optJSONObject(i)?.optText("id") ?: ""
            if (id.isNotEmpty()) out.add(id)
        }
        return out
    }

    /** What the shared catalogue knows about one model id. */
    private data class Meta(
        val name: String,
        val attachment: Boolean,
        val reasoning: Boolean,
        val tools: Boolean,
        val inputs: List<String>,
        val context: Int,
        val npm: String,
        val free: Boolean,
        val thinking: RumiThinking,
        val reasoningField: String?,
    )

    /**
     * Read the gateway's subtree out of the shared catalogue.
     *
     * [JsonReader] is used rather than `JSONObject` on purpose: the document
     * describes a few hundred providers and building every one of them as an
     * object graph costs tens of megabytes on a device that has little to
     * spare, while only one subtree is ever read.
     */
    private fun fetchCatalogue(provider: RumiProvider): Map<String, Meta> {
        // There is nothing to ask of a provider that is not in the catalogue
        // (a custom server, a custom proxy). This is not a network error, and
        // calling it network words would send the user off to fix the internet.
        if (provider.capabilityProvider.isEmpty()) throw NotInCatalogue()
        // Without `authorization`: this is a third-party domain, and the key is
        // the provider's credentials. Sending it here is neither needed nor
        // allowed — the key leaked to `models.dev` on every catalogue refresh.
        val reply = RumiHttp.getJson(
            RumiSettings.MODELS_DEV,
            mapOf("accept" to "application/json"),
            timeoutMs = 60_000,
        )
        if (!reply.ok) throw IllegalStateException("catalogue answered ${reply.status}")
        return extractProvider(StringReader(reply.body), provider.capabilityProvider)
    }

    private fun extractProvider(reader: StringReader, provider: String): Map<String, Meta> {
        val json = JsonReader(reader)
        json.isLenient = true
        try {
            json.beginObject()
            while (json.hasNext()) {
                if (json.nextName() != provider) {
                    json.skipValue()
                    continue
                }
                json.beginObject()
                var npm = ""
                var models: Map<String, Meta> = emptyMap()
                while (json.hasNext()) {
                    when (json.nextName()) {
                        "npm" -> npm = json.nextTextOrNull().orEmpty()
                        "models" -> models = readModels(json, npm)
                        else -> json.skipValue()
                    }
                }
                json.endObject()
                // The provider's own npm is the default for its models; a
                // per-model override is read inside readModels.
                return if (npm.isEmpty()) {
                    models
                } else {
                    models.mapValues { (_, m) -> m.copy(npm = m.npm.ifEmpty { npm }) }
                }
            }
            return emptyMap()
        } finally {
            runCatching { json.close() }
        }
    }

    private fun readModels(json: JsonReader, providerNpm: String): Map<String, Meta> {
        val out = HashMap<String, Meta>()
        json.beginObject()
        while (json.hasNext()) {
            val id = json.nextName()
            out[id] = readModel(json, providerNpm)
        }
        json.endObject()
        return out
    }

    private fun readModel(json: JsonReader, providerNpm: String): Meta {
        var name = ""
        var attachment = false
        var reasoning = false
        var tools = false
        var context = 0
        var npm = providerNpm
        var free = false
        var thinking: RumiThinking = RumiThinking.None
        var reasoningField: String? = null
        val inputs = mutableListOf<String>()
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "name" -> name = json.nextTextOrNull().orEmpty()
                "attachment" -> attachment = nextBool(json)
                "reasoning" -> reasoning = nextBool(json)
                "tool_call" -> tools = nextBool(json)
                "reasoning_options" -> thinking = RumiThinking.from(readReasoningOptions(json))
                "interleaved" -> reasoningField = readReasoningField(json)
                "modalities" -> {
                    json.beginObject()
                    while (json.hasNext()) {
                        if (json.nextName() == "input") {
                            json.beginArray()
                            while (json.hasNext()) {
                                json.nextTextOrNull()?.let { inputs.add(it) }
                            }
                            json.endArray()
                        } else {
                            json.skipValue()
                        }
                    }
                    json.endObject()
                }
                "limit" -> {
                    json.beginObject()
                    while (json.hasNext()) {
                        if (json.nextName() == "context") {
                            context = nextInt(json)
                        } else {
                            json.skipValue()
                        }
                    }
                    json.endObject()
                }
                "provider" -> {
                    json.beginObject()
                    while (json.hasNext()) {
                        if (json.nextName() == "npm") {
                            npm = json.nextTextOrNull().orEmpty()
                        } else {
                            json.skipValue()
                        }
                    }
                    json.endObject()
                }
                "cost" -> {
                    json.beginObject()
                    var inCost = -1.0
                    var outCost = -1.0
                    while (json.hasNext()) {
                        when (json.nextName()) {
                            "input" -> inCost = nextDouble(json)
                            "output" -> outCost = nextDouble(json)
                            else -> json.skipValue()
                        }
                    }
                    json.endObject()
                    free = inCost == 0.0 && outCost == 0.0
                }
                else -> json.skipValue()
            }
        }
        json.endObject()
        return Meta(name, attachment, reasoning, tools, inputs, context, npm, free, thinking, reasoningField)
    }

    /**
     * `reasoning_options` — a list of reasoning-control options.
     *
     * Each option is an object with a `type` and, for levels, a `values` list;
     * for the budget, bounds. An unknown type is skipped rather than turned into
     * "there is no choice": the catalogue may add a fourth kind, and an app that
     * tripped over it would lose the options it does understand too.
     */
    private fun readReasoningOptions(json: JsonReader): List<Pair<String, Any?>> {
        val out = mutableListOf<Pair<String, Any?>>()
        json.beginArray()
        while (json.hasNext()) {
            if (json.peek() != JsonToken.BEGIN_OBJECT) {
                json.skipValue()
                continue
            }
            var type = ""
            var values: List<String>? = null
            var min = 0
            var max = 0
            json.beginObject()
            while (json.hasNext()) {
                when (json.nextName()) {
                    "type" -> type = json.nextTextOrNull().orEmpty()
                    "values" -> {
                        val list = mutableListOf<String>()
                        json.beginArray()
                        while (json.hasNext()) json.nextTextOrNull()?.let { list.add(it) }
                        json.endArray()
                        values = list
                    }
                    "min" -> min = nextInt(json)
                    "max" -> max = nextInt(json)
                    else -> json.skipValue()
                }
            }
            json.endObject()
            when (type) {
                "effort" -> out.add("effort" to (values ?: emptyList<String>()))
                "toggle" -> out.add("toggle" to null)
                "budget_tokens" -> out.add("budget_tokens" to intArrayOf(min, max))
                else -> Unit
            }
        }
        json.endArray()
        return out
    }

    /**
     * `interleaved` — the name of the field in which the reasoning arrives.
     *
     * It is sometimes `true` (the model interleaves reasoning with the answer but
     * does not name the field) and sometimes an object with a `field`. The second
     * is what is needed: the first gives no name, and a name without a field does
     * not exist.
     */
    private fun readReasoningField(json: JsonReader): String? {
        if (json.peek() != JsonToken.BEGIN_OBJECT) {
            json.skipValue()
            return null
        }
        var field: String? = null
        json.beginObject()
        while (json.hasNext()) {
            if (json.nextName() == "field") {
                field = json.nextTextOrNull()?.takeIf { it.isNotEmpty() }
            } else {
                json.skipValue()
            }
        }
        json.endObject()
        return field
    }

    private fun nextBool(json: JsonReader): Boolean =
        if (json.peek() == JsonToken.NULL) {
            json.nextNull()
            false
        } else {
            json.nextBoolean()
        }

    private fun nextInt(json: JsonReader): Int =
        if (json.peek() == JsonToken.NULL) {
            json.nextNull()
            0
        } else {
            runCatching { json.nextInt() }.getOrElse { json.nextDouble().toInt() }
        }

    private fun nextDouble(json: JsonReader): Double =
        if (json.peek() == JsonToken.NULL) {
            json.nextNull()
            -1.0
        } else {
            runCatching { json.nextDouble() }.getOrElse { -1.0 }
        }

    private fun cacheFile(context: Context) = File(context.filesDir, CACHE_FILE)

    /**
     * Read the cache if it is from the same provider.
     *
     * There is one cache per app, but several providers: the model list of one
     * gateway shown as another's list means models it does not have, and a
     * refusal on every selection. So the provider is written inside the cache and
     * a mismatch reads as "no cache".
     */
    private fun readCache(context: Context, providerId: String): Catalogue? = runCatching {
        val file = cacheFile(context)
        if (!file.isFile) return null
        val root = JSONObject(file.readText())
        if (root.optTextOr("provider", "") != providerId) return null
        val array = root.optJSONArray("models") ?: return null
        val models = ArrayList<RumiModel>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val id = o.optText("id").orEmpty()
            if (id.isEmpty()) continue
            models.add(
                RumiModel(
                    id = id,
                    name = o.optTextOr("name", id),
                    protocol = runCatching {
                        RumiProtocol.valueOf(o.optTextOr("protocol", RumiProtocol.Chat.name))
                    }.getOrDefault(RumiProtocol.Chat),
                    vision = o.optBoolean("vision", false),
                    tools = o.optBoolean("tools", true),
                    reasoning = o.optBoolean("reasoning", false),
                    contextTokens = o.optInt("context", 0),
                    free = o.optBoolean("free", false),
                ),
            )
        }
        if (models.isEmpty()) return null
        Catalogue(models, root.optLong("fetchedAt", 0L), stale = false)
    }.getOrNull()

    private fun writeCache(context: Context, catalogue: Catalogue, providerId: String) {
        val array = JSONArray()
        catalogue.models.forEach { m ->
            array.put(
                JSONObject()
                    .put("id", m.id)
                    .put("name", m.name)
                    .put("protocol", m.protocol.name)
                    .put("vision", m.vision)
                    .put("tools", m.tools)
                    .put("reasoning", m.reasoning)
                    .put("context", m.contextTokens)
                    .put("free", m.free),
            )
        }
        val root = JSONObject()
            .put("fetchedAt", catalogue.fetchedAt)
            .put("provider", providerId)
            .put("models", array)
        cacheFile(context).writeText(root.toString())
    }

    private fun shorten(text: String, limit: Int = 200): String =
        text.replace('\n', ' ').let { if (it.length <= limit) it else it.take(limit) + "…" }
}
