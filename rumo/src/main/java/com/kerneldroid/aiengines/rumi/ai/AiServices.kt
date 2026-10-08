// SPDX-License-Identifier: GPL-3.0-or-later
package com.kerneldroid.aiengines.rumi.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import com.kerneldroid.aiengines.R
import com.kerneldroid.aiengines.rumi.RumiAuth
import com.kerneldroid.aiengines.rumi.RumiProtocol
import com.kerneldroid.aiengines.rumi.RumiProvider
import com.kerneldroid.aiengines.rumi.RumiProviders
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * What a service can create.
 *
 * A kind is not a label but a set of tools: each kind has its own tool and
 * its own result shape (sound — an audio layer, a picture — a media layer, video —
 * a media layer with a decoder). So a kind is one per service, rather than a "can do both"
 * flag: a service that can do two kinds is two records, and the model chooses
 * between them by name.
 */
enum class AiKind(val id: String) {
    Speech("speech"),
    Sound("sound"),
    Image("image"),
    Video("video"),
    ;

    /** The lowercase form for arguments and settings; an unknown one is not a kind. */
    companion object {
        fun parse(id: String): AiKind? = entries.firstOrNull { it.id == id.trim().lowercase() }
    }
}

/**
 * The request shape, not "whose service this is".
 *
 * Two TTS vendors share one shape, and telling them apart by name would mean
 * writing two copies of one request that would diverge on the first format
 * change. An adapter describes exactly what has to be parsed and assembled:
 * Interactions for Gemini, `images/generations` for OpenAI-compatible ones and so on.
 */
enum class AiAdapter(val id: String) {
    /** Gemini `POST /v1beta/interactions`, the audio arrives base64 in `steps`. */
    GeminiSpeech("gemini-speech"),

    /** Gemini `POST /v1beta/interactions`, the picture arrives base64 in `steps`. */
    GeminiImage("gemini-image"),

    /** Gemini `predictLongRunning` + polling the operation, output is a link. */
    GeminiVideo("gemini-video"),

    /** BytePlus ModelArk: a job + polling by `status`, output is a link. */
    SeedanceVideo("seedance-video"),

    /** `POST {base}/audio/speech`, the response body is the sound itself. */
    OpenAiSpeech("openai-speech"),

    /** `POST {base}/images/generations`, the picture is in `data[0].b64_json`. */
    OpenAiImage("openai-image"),
    ;

    companion object {
        fun parse(id: String): AiAdapter? = entries.firstOrNull { it.id == id.trim().lowercase() }
    }
}

/**
 * One service: where to call and how.
 *
 * [auth] and [authHeader] are here not for show: the header derivation is reused
 * from [RumiProviders.authHeaders], so that services and models do not have two
 * slightly different answers to "how to put the key into the request". For Gemini it is a
 * separate `x-goog-api-key`, not `Authorization`, and that is a property of the service, not
 * a constant of the code.
 */
data class AiService(
    val id: String,
    val kind: AiKind,
    val label: String,
    val adapter: AiAdapter,
    val baseUrl: String,
    val model: String,
    val auth: RumiAuth = RumiAuth.Bearer,
    val authHeader: String = "",
    /** Created by the user: it can be edited and deleted. */
    val custom: Boolean = false,
    /** How this service differs from the others — one line for the settings. */
    val note: String = "",
    /**
     * [note] as a resource, for the services that ship with the app.
     *
     * A built-in's line is written once, at class-load time, so a string captured
     * there would keep whatever language the process started in. Zero means the
     * service is the user's own: then [note] is the only text there is, and it is
     * shown exactly as it was written.
     */
    @StringRes val noteRes: Int = 0,
) {
    /**
     * A provider — only to reuse the header derivation.
     *
     * The chat protocol here is fictitious: generation services do not have one, and the value
     * is never sent anywhere. That way [RumiProviders.authHeaders] stays
     * the only place that knows that `Bearer` and `x-api-key` put the
     * key differently.
     */
    fun authHost(): RumiProvider = RumiProvider(
        id = id,
        label = label,
        baseUrl = baseUrl,
        protocol = RumiProtocol.Chat,
        auth = auth,
        authHeader = authHeader,
    )
}

/**
 * A service together with the user's settings.
 *
 * The service definition (address, model, adapter) is separated from the settings (key,
 * switch, overrides) for exactly the same reason as with model providers: a built-in
 * address is a fact about someone else's service, and editing it in place
 * would turn "reset" into a nonexistent button. The override therefore
 * sits next to the key, not in the definition itself.
 */
data class AiEntry(
    val service: AiService,
    val enabled: Boolean = false,
    val apiKey: String = "",
    val modelOverride: String = "",
    val baseUrlOverride: String = "",
) {
    val id: String get() = service.id
    val kind: AiKind get() = service.kind
    val label: String get() = service.label
    val custom: Boolean get() = service.custom

    /** What will go into the request: the override or what the definition sets. */
    val model: String get() = modelOverride.ifEmpty { service.model }
    val baseUrl: String get() = baseUrlOverride.ifEmpty { service.baseUrl }.trimEnd('/')

    /**
     * The service is actually ready.
     *
     * Three conditions at once: the switch says the service is permitted, the key — that
     * it is possible, the address with the model — that there is somewhere and something to send
     * the request with. A tool that always refuses is worse than an absent one: the model
     * spends a turn on it and promises the user something they do not have (the same
     * device as with `searchReady`).
     */
    val ready: Boolean
        get() = enabled && apiKey.isNotEmpty() && baseUrl.isNotEmpty() && model.isNotEmpty()
}

/**
 * Built-in presets.
 *
 * Only what is confirmed by primary sources (`docs/20`, `docs/21`), with
 * exact model ids. There is no sound preset, and that is not a gap: a compatible request
 * shape for sound effects that could be filled in from a
 * mobile app does not exist at the known services. Inventing a preset would mean
 * selling a button that always refuses, — so there will be no `sound` kind in the list
 * until a shape appears, and the settings say so outright.
 */
object AiCatalog {

    val builtIn: List<AiService> = listOf(
        AiService(
            id = "gemini-speech",
            kind = AiKind.Speech,
            label = "Google Gemini TTS",
            adapter = AiAdapter.GeminiSpeech,
            baseUrl = "https://generativelanguage.googleapis.com",
            model = "gemini-3.8-flash-tts",
            // The Gemini key sits in a separate header, not in Authorization.
            auth = RumiAuth.Header,
            authHeader = "x-goog-api-key",
            noteRes = R.string.rumi_ai_note_gemini_speech,
        ),
        AiService(
            id = "openai-speech",
            kind = AiKind.Speech,
            label = "OpenAI TTS",
            adapter = AiAdapter.OpenAiSpeech,
            baseUrl = "https://api.openai.com/v1",
            model = "gpt-4o-mini-tts",
            noteRes = R.string.rumi_ai_note_openai_speech,
        ),
        AiService(
            id = "gemini-image",
            kind = AiKind.Image,
            label = "Google Gemini image",
            adapter = AiAdapter.GeminiImage,
            baseUrl = "https://generativelanguage.googleapis.com",
            model = "gemini-nano-banana-2.1",
            auth = RumiAuth.Header,
            authHeader = "x-goog-api-key",
            noteRes = R.string.rumi_ai_note_gemini_image,
        ),
        AiService(
            id = "openai-image",
            kind = AiKind.Image,
            label = "OpenAI image",
            adapter = AiAdapter.OpenAiImage,
            baseUrl = "https://api.openai.com/v1",
            model = "gpt-image-1",
            noteRes = R.string.rumi_ai_note_openai_image,
        ),
        AiService(
            id = "gemini-video",
            kind = AiKind.Video,
            label = "Google Veo",
            adapter = AiAdapter.GeminiVideo,
            baseUrl = "https://generativelanguage.googleapis.com",
            model = "veo-3.1-fast-generate-preview",
            auth = RumiAuth.Header,
            authHeader = "x-goog-api-key",
            noteRes = R.string.rumi_ai_note_veo,
        ),
        AiService(
            id = "seedance-video",
            kind = AiKind.Video,
            label = "BytePlus Seedance",
            adapter = AiAdapter.SeedanceVideo,
            baseUrl = "https://ark.ap-southeast.bytepluses.com/api/v3",
            model = "dreamina-seedance-2-5-260628",
            // The provider describes only this method, hence Bearer.
            auth = RumiAuth.Bearer,
            noteRes = R.string.rumi_ai_note_seedance,
        ),
    )

    /**
     * The adapter for a custom service — or null if such a kind cannot be created.
     *
     * Custom services are supported only where a compatible request shape is
     * documented: speech (`POST {base}/audio/speech`) and pictures
     * (`POST {base}/images/generations`). For video and sound there is nothing to fill in —
     * offering "a custom service" would mean offering a shape that at any
     * service would refuse in its own way.
     */
    fun customAdapterFor(kind: AiKind): AiAdapter? = when (kind) {
        AiKind.Speech -> AiAdapter.OpenAiSpeech
        AiKind.Image -> AiAdapter.OpenAiImage
        AiKind.Sound, AiKind.Video -> null
    }

    /** The kinds for which the user can create a custom service. */
    val customKinds: List<AiKind> get() = AiKind.entries.filter { customAdapterFor(it) != null }
}

/**
 * The catalog of generation services: built-in presets plus custom ones, with keys and
 * switches — one store for the whole app.
 *
 * Same shape as [com.kerneldroid.aiengines.rumi.RumiSettings]: SharedPreferences
 * plus a StateFlow fed by a listener, so the settings screen and the tools
 * cannot drift apart. The keys sit in a `service → key` map, and the switches in a
 * separate map: a key left over from last time must not enable the
 * service by itself, otherwise "I just saved a key" would spend money.
 *
 * Nothing here touches the network.
 */
object AiServices {
    private const val PREFS = "rumo_ai_services"
    private const val KEY_CUSTOM = "custom_services"
    private const val KEY_KEYS = "keys"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_MODELS = "models"
    private const val KEY_URLS = "base_urls"

    data class State(val entries: List<AiEntry> = emptyList()) {
        fun ofKind(kind: AiKind): List<AiEntry> = entries.filter { it.kind == kind }

        /** The ready services of a kind — what can be offered to the model at all. */
        fun readyOf(kind: AiKind): List<AiEntry> = entries.filter { it.kind == kind && it.ready }

        fun entry(id: String): AiEntry? = entries.firstOrNull { it.id == id }

        val anyReady: Boolean get() = entries.any { it.ready }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var prefs: SharedPreferences? = null

    private val listener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> reload() }

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        p.registerOnSharedPreferenceChangeListener(listener)
        reload()
    }

    private fun reload() {
        val p = prefs ?: return
        val custom = parseCustom(p.getString(KEY_CUSTOM, "") ?: "")
        val keys = readMap(p, KEY_KEYS)
        val enabled = readMap(p, KEY_ENABLED)
        val models = readMap(p, KEY_MODELS)
        val urls = readMap(p, KEY_URLS)
        _state.value = State(
            // Built-in ones come first and in declaration order: the list in
            // the settings must read the same both ways, not get
            // shuffled by editing someone else's service.
            entries = (AiCatalog.builtIn + custom).map { service ->
                AiEntry(
                    service = service,
                    enabled = enabled[service.id] == "1",
                    apiKey = keys[service.id] ?: "",
                    modelOverride = models[service.id] ?: "",
                    baseUrlOverride = urls[service.id] ?: "",
                )
            },
        )
    }

    /** Read `{"service":"value"}`; a corrupt entry reads as empty. */
    private fun readMap(p: SharedPreferences, key: String): Map<String, String> {
        val raw = p.getString(key, "") ?: return emptyMap()
        if (raw.isBlank()) return emptyMap()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val out = HashMap<String, String>()
        for (name in root.keys()) {
            val value = root.optString(name, "")
            if (value.isNotEmpty()) out[name] = value
        }
        return out
    }

    private fun writeMap(key: String, name: String, value: String) {
        val p = prefs ?: return
        val map = readMap(p, key).toMutableMap()
        if (value.isEmpty()) map.remove(name) else map[name] = value
        p.edit().putString(key, JSONObject(map as Map<*, *>).toString()).apply()
    }

    /**
     * Parse the custom services.
     *
     * A corrupt entry brings down only itself: a service that failed to be read is
     * a lost row, not broken settings. An unknown kind is
     * skipped too: the setting may have been written by another build.
     */
    private fun parseCustom(raw: String): List<AiService> {
        if (raw.isBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<AiService>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            val kind = AiKind.parse(o.optString("kind", "")) ?: continue
            val adapter = AiCatalog.customAdapterFor(kind) ?: continue
            if (id.isEmpty()) continue
            out += AiService(
                id = id,
                kind = kind,
                label = o.optString("label", "").ifBlank { id },
                adapter = adapter,
                baseUrl = o.optString("baseUrl", ""),
                model = o.optString("model", ""),
                auth = RumiAuth.parse(o.optString("auth", "")),
                authHeader = o.optString("authHeader", ""),
                custom = true,
                note = o.optString("note", ""),
            )
        }
        return out
    }

    private fun encodeCustom(list: List<AiService>): String {
        val array = JSONArray()
        for (s in list) {
            array.put(
                JSONObject()
                    .put("id", s.id)
                    .put("kind", s.kind.id)
                    .put("label", s.label)
                    .put("baseUrl", s.baseUrl)
                    .put("model", s.model)
                    .put("auth", s.auth.id)
                    .put("authHeader", s.authHeader)
                    .put("note", s.note),
            )
        }
        return array.toString()
    }

    /** Only custom services — the definitions: they are edited whole, as with providers. */
    private fun customDefs(): List<AiService> = _state.value.entries.map { it.service }.filter { it.custom }

    fun setEnabled(id: String, on: Boolean) {
        writeMap(KEY_ENABLED, id, if (on) "1" else "")
    }

    fun setApiKey(id: String, key: String) {
        writeMap(KEY_KEYS, id, key.trim())
    }

    fun setModel(id: String, model: String) {
        writeMap(KEY_MODELS, id, model.trim())
    }

    fun setBaseUrl(id: String, url: String) {
        writeMap(KEY_URLS, id, url.trim().trimEnd('/'))
    }

    /**
     * Create a custom service. Returns its id, or null if the kind cannot be created.
     *
     * The kind is checked here, not only in the UI: a setting can be
     * written without going through the screen too, and a sound service without an adapter would look
     * working in the list while never becoming ready.
     */
    fun addCustom(
        kind: AiKind,
        label: String,
        baseUrl: String,
        model: String,
        auth: RumiAuth,
        authHeader: String,
    ): String? {
        val adapter = AiCatalog.customAdapterFor(kind) ?: return null
        val id = "custom-${UUID.randomUUID().toString().take(8)}"
        val service = AiService(
            id = id,
            kind = kind,
            label = label.trim().ifBlank { "Custom ${kind.id}" },
            adapter = adapter,
            baseUrl = baseUrl.trim().trimEnd('/'),
            model = model.trim(),
            auth = auth,
            authHeader = authHeader.trim(),
            custom = true,
            note = "A service you added. Its address and model are yours to keep in " +
                "step with the server.",
        )
        prefs?.edit()?.putString(KEY_CUSTOM, encodeCustom(customDefs() + service))?.apply()
        return id
    }

    /** Remove a custom service together with its key, otherwise the key would be left orphaned. */
    fun removeCustom(id: String) {
        prefs?.edit()?.putString(KEY_CUSTOM, encodeCustom(customDefs().filter { it.id != id }))?.apply()
        writeMap(KEY_KEYS, id, "")
        writeMap(KEY_ENABLED, id, "")
        writeMap(KEY_MODELS, id, "")
        writeMap(KEY_URLS, id, "")
    }
}
