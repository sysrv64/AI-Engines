// SPDX-License-Identifier: GPL-3.0-or-later
package com.kerneldroid.aiengines.rumi

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Persisted Rumi state: which provider answers, the keys, the chosen model and
 * the session id the provider sees.
 *
 * ## Why only a key
 *
 * There used to be two ways to sign in here: a key and signing into an account through the
 * console via the device-code flow. The second is gone, and that is not simplification for its own sake.
 *
 * The device flow is the path of a **first-party CLI**: the app
 * presented itself to the console with its public `client_id` (`opencode-cli`), that is,
 * impersonated the official client, and received an account token with it.
 * OpenCode's terms of use prohibit "deceptive" requests to the service, and the
 * documented way for a third-party client is one — a key: "Your
 * ability to access Third Party Models is contingent on you having API keys".
 * Substituting someone else's client_id is impersonation, not authorisation.
 *
 * ## Why the keys are stored as a batch, not one by one
 *
 * There are several providers now, and each has its own key. A single slot would mean
 * that switching provider wipes the previous key — that is, a choice between
 * two services would cost re-entering the key. So the keys are stored
 * as a map `provider → key`, and [State.apiKey] is the active one's.
 *
 * ## Why the address is separate from the provider
 *
 * A provider is "how to talk" (headers, protocol, list shape), and
 * an address is "where to". The built-in ones have a known address, but a user may have
 * their own proxy in front of the same service, and there is no reason to forbid that: an empty field
 * means "the provider's address".
 *
 * Same shape as [com.kerneldroid.rumo.ui.SettingsRepo]: SharedPreferences plus a
 * StateFlow fed by a listener, so the assistant screen and the settings screen
 * cannot drift apart. Nothing here touches the network.
 */
object RumiSettings {
    /** Where the model catalogue is described (capabilities, SDK family). */
    const val MODELS_DEV = "https://models.dev/api.json"

    private const val PREFS = "rumo_rumi"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_KEYS = "api_keys"
    private const val KEY_ENDPOINTS = "endpoints"
    private const val KEY_CUSTOM = "custom_providers"
    private const val KEY_MODEL = "model"
    private const val KEY_SESSION = "session"
    private const val KEY_IMAGE_BUDGET = "image_budget"
    private const val KEY_SEARCH_ON = "search_enabled"
    private const val KEY_EXA_KEY = "exa_key"
    private const val KEY_THINKING = "thinking"

    data class State(
        /** Which provider is selected. */
        val providerId: String = RumiProviders.DEFAULT_ID,
        /** Providers created by the user. */
        val customProviders: List<RumiProvider> = emptyList(),
        /** The active provider's key; empty means it was never entered. */
        val apiKey: String = "",
        /** The active provider's address, if it is overridden. */
        val endpointOverride: String = "",
        val modelId: String = "",
        val sessionId: String = "",
        /** Upper bound on snapshot images kept in one conversation. */
        val imageBudget: Int = 4,
        /**
         * Web search is on. A separate switch rather than "there is a key — so it is
         * on": the key may be sitting in the settings from last time, and the user does not
         * intend to spend it right now.
         */
        val searchEnabled: Boolean = false,
        /** The Exa key. Empty means no search, and the model's tool is not shown. */
        val exaKey: String = "",
        /**
         * The thinking depth chosen by the user.
         *
         * One string for all models rather than one string per model: the set of levels differs between
         * models, and remembering "xhigh" for a model that does not
         * accept it would mean storing a setting that cannot be applied.
         * An unsuitable choice reads as "as intended" — see
         * [RumiThinkingChoice.fits].
         */
        val thinking: RumiThinkingChoice = RumiThinkingChoice.Auto,
    ) {
        /** The provider resolved from the setting. */
        val provider: RumiProvider
            get() = RumiProviders.resolve(providerId, customProviders)

        /** Where to send the request: the override or the provider's address. */
        val endpoint: String get() = endpointOverride.ifEmpty { provider.baseUrl }

        /** True when a credential is present. */
        val configured: Boolean get() = apiKey.isNotEmpty()

        /** The key to send, or null when the user has not pasted one yet. */
        fun credential(): String? = apiKey.ifEmpty { null }

        /**
         * Search is actually available.
         *
         * Both conditions at once: the switch says search is permitted, the key —
         * that it is possible. A tool that always refuses is worse than an
         * absent one: the model spends a turn on it and tells the user about
         * something that does not exist.
         */
        val searchReady: Boolean get() = searchEnabled && exaKey.isNotEmpty()
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
        if (p.getString(KEY_SESSION, "").isNullOrEmpty()) {
            // The provider wants one stable id per client install so it can see
            // a conversation as one user, so it is minted once and kept.
            p.edit().putString(KEY_SESSION, UUID.randomUUID().toString()).apply()
        }
        reload()
    }

    private fun reload() {
        val p = prefs ?: return
        val custom = parseProviders(p.getString(KEY_CUSTOM, "") ?: "")
        val providerId = p.getString(KEY_PROVIDER, RumiProviders.DEFAULT_ID)
            ?: RumiProviders.DEFAULT_ID
        _state.value = State(
            providerId = providerId,
            customProviders = custom,
            apiKey = readMap(p, KEY_KEYS)[providerId] ?: "",
            endpointOverride = readMap(p, KEY_ENDPOINTS)[providerId] ?: "",
            modelId = p.getString(KEY_MODEL, "") ?: "",
            sessionId = p.getString(KEY_SESSION, "") ?: "",
            imageBudget = p.getInt(KEY_IMAGE_BUDGET, 4),
            searchEnabled = p.getBoolean(KEY_SEARCH_ON, false),
            exaKey = p.getString(KEY_EXA_KEY, "") ?: "",
            thinking = RumiThinkingChoice.parse(p.getString(KEY_THINKING, "") ?: ""),
        )
    }

    /** Read `{"provider":"value"}`; a corrupt entry reads as empty. */
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
     * Parse the user's providers.
     *
     * A corrupt entry does not bring down the settings: a provider that failed to
     * be read is a lost row, not a broken app.
     */
    private fun parseProviders(raw: String): List<RumiProvider> {
        if (raw.isBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<RumiProvider>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            val baseUrl = o.optString("baseUrl", "")
            if (id.isEmpty() || baseUrl.isEmpty()) continue
            val headers = HashMap<String, String>()
            o.optJSONObject("headers")?.let { h ->
                for (name in h.keys()) headers[name] = h.optString(name, "")
            }
            out += RumiProvider(
                id = id,
                label = o.optString("label", "").ifBlank { id },
                baseUrl = baseUrl,
                protocol = parseProtocol(o.optString("protocol", "")),
                auth = RumiAuth.parse(o.optString("auth", "")),
                authHeader = o.optString("authHeader", ""),
                extraHeaders = headers,
                modelsPath = o.optString("modelsPath", "/models").ifBlank { "/models" },
                models = RumiModelList.parse(o.optString("models", "")),
                capabilityProvider = o.optString("capabilityProvider", ""),
                sessionHeader = o.optBoolean("sessionHeader", false),
                note = o.optString("note", ""),
                custom = true,
            )
        }
        return out
    }

    private fun parseProtocol(id: String): RumiProtocol = when (id) {
        "responses" -> RumiProtocol.Responses
        "messages" -> RumiProtocol.Messages
        else -> RumiProtocol.Chat
    }

    private fun encodeProviders(list: List<RumiProvider>): String {
        val array = JSONArray()
        for (p in list) {
            array.put(
                JSONObject()
                    .put("id", p.id)
                    .put("label", p.label)
                    .put("baseUrl", p.baseUrl)
                    .put(
                        "protocol",
                        when (p.protocol) {
                            RumiProtocol.Chat -> "chat"
                            RumiProtocol.Responses -> "responses"
                            RumiProtocol.Messages -> "messages"
                        },
                    )
                    .put("auth", p.auth.id)
                    .put("authHeader", p.authHeader)
                    .put("headers", JSONObject(p.extraHeaders as Map<*, *>))
                    .put("modelsPath", p.modelsPath)
                    .put("models", p.models.id)
                    .put("capabilityProvider", p.capabilityProvider)
                    .put("sessionHeader", p.sessionHeader)
                    .put("note", p.note),
            )
        }
        return array.toString()
    }

    fun setProviderId(id: String) {
        prefs?.edit()?.putString(KEY_PROVIDER, id)?.apply()
    }

    /** The active provider's key: written into its own slot. */
    fun setApiKey(key: String) {
        writeMap(KEY_KEYS, _state.value.providerId, key.trim())
    }

    fun setEndpointOverride(url: String) {
        writeMap(KEY_ENDPOINTS, _state.value.providerId, url.trim().trimEnd('/'))
    }

    fun setCustomProviders(list: List<RumiProvider>) {
        prefs?.edit()?.putString(KEY_CUSTOM, encodeProviders(list))?.apply()
    }

    fun setModelId(id: String) {
        prefs?.edit()?.putString(KEY_MODEL, id)?.apply()
    }

    fun setImageBudget(n: Int) {
        prefs?.edit()?.putInt(KEY_IMAGE_BUDGET, n.coerceIn(0, 12))?.apply()
    }

    fun setSearchEnabled(on: Boolean) {
        prefs?.edit()?.putBoolean(KEY_SEARCH_ON, on)?.apply()
    }

    fun setExaKey(key: String) {
        prefs?.edit()?.putString(KEY_EXA_KEY, key.trim())?.apply()
    }

    fun setThinking(choice: RumiThinkingChoice) {
        prefs?.edit()?.putString(KEY_THINKING, choice.tag())?.apply()
    }
}
