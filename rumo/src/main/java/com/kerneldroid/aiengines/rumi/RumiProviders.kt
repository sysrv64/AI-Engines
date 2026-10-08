// SPDX-License-Identifier: GPL-3.0-or-later
package com.kerneldroid.aiengines.rumi

import androidx.annotation.StringRes
import com.kerneldroid.aiengines.R

/**
 * Who exactly answers a request and how to talk to them.
 *
 * ## Why this is a description and not a branch in the code
 *
 * The address and the auth method used to be hardwired for one gateway: `Bearer` in
 * the header, `/models` for the list, `x-opencode-session` for the session. Changing
 * the provider meant editing three places in different files and not forgetting a fourth.
 * Here those four things sit side by side, because they are one thing: an address without an auth
 * method is useless, and a protocol without an address has nowhere to be sent.
 *
 * ## Why OpenAI compatibility and not each one's native protocol
 *
 * Gemini has a native `generateContent` with its own request shape, Anthropic has
 * its own `/v1/messages`, and both are already supported ([RumiProtocol]). But the list
 * of providers does not come down to three: any gateway and any local server today
 * speaks chat-completions. So "a custom service" is first of all
 * an OpenAI-compatible address, and native protocols are for where they give something
 * important (Anthropic — reasoning and tool calls in its own shape).
 *
 * Gemini is connected through its own OpenAI-compatible entry point
 * (`/v1beta/openai`), not through `generateContent`: that is Google's documented
 * way of talking to Gemini with OpenAI clients, and it does not require a fourth
 * stream parser for the same result.
 */
enum class RumiAuth(val id: String) {
    /** `authorization: Bearer <key>` — the form of OpenAI and most gateways. */
    Bearer("bearer"),

    /** `x-api-key: <key>` — the Anthropic form. */
    XApiKey("x-api-key"),

    /** A custom header: each service may name it its own way. */
    Header("header"),
    ;

    companion object {
        fun parse(id: String): RumiAuth = entries.firstOrNull { it.id == id } ?: Bearer
    }
}

/**
 * How to get the list of models from a provider.
 *
 * `Manual` is not "we cannot do it" but "this address has no such entry point": a local
 * server or a homemade proxy often serves a single model, and there is no point asking for it
 * as a list. Then the model is typed in by hand, and that is not a breakage but a
 * setting.
 */
enum class RumiModelList(val id: String) {
    /** `GET {base}/models` → `{"data":[{"id":…}]}` — the OpenAI form. */
    OpenAi("openai"),

    /** There is nothing to ask: the user names the model. */
    Manual("manual"),
    ;

    companion object {
        fun parse(id: String): RumiModelList = entries.firstOrNull { it.id == id } ?: OpenAi
    }
}

/**
 * One provider in full.
 *
 * [capabilityProvider] is the key under which this provider sits in the shared
 * `models.dev` catalog. An empty string means "there is no one to ask": then
 * the models are treated as ordinary text ones rather than disappearing from the list. The catalog is a
 * convenience, not a condition of operation, and its absence should not look like an
 * absence of models.
 */
data class RumiProvider(
    val id: String,
    val label: String,
    val baseUrl: String,
    /** The protocol for models the catalog knows nothing about. */
    val protocol: RumiProtocol,
    val auth: RumiAuth,
    /** The header name for [RumiAuth.Header]; otherwise unused. */
    val authHeader: String = "",
    /** Headers the provider always requires (for example, the API version). */
    val extraHeaders: Map<String, String> = emptyMap(),
    val modelsPath: String = "/models",
    val models: RumiModelList = RumiModelList.OpenAi,
    val capabilityProvider: String = "",
    /**
     * The OpenCode gateway asks the client to keep one stable session identifier
     * and identify itself. That is its requirement, not a general one, so it lives here rather than
     * in the request-sending code.
     */
    val sessionHeader: Boolean = false,
    /** How this provider differs from the others — one line for the settings. */
    val note: String = "",
    /**
     * [note] as a resource, for the providers that ship with the app.
     *
     * A built-in's line is written once, at class-load time, so a string captured
     * there would keep whatever language the process started in. Zero means the
     * provider is the user's own: then [note] is the only text there is, and it is
     * shown exactly as it was written.
     */
    @StringRes val noteRes: Int = 0,
    /** Created by the user rather than built in: it can be edited and deleted. */
    val custom: Boolean = false,
)

/**
 * The list of built-in providers and the resolution of user ones.
 *
 * Built-in ones are not editable: their addresses and headers are facts about others'
 * services, and editing them in place would turn "reset" into a nonexistent
 * button. Anyone who needs a different address creates their own, and then it is visible that it is theirs.
 */
object RumiProviders {

    const val DEFAULT_ID = "opencode-go"

    val builtIn: List<RumiProvider> = listOf(
        RumiProvider(
            id = "opencode-go",
            label = "OpenCode Go",
            baseUrl = "https://opencode.ai/zen/go/v1",
            protocol = RumiProtocol.Chat,
            auth = RumiAuth.Bearer,
            capabilityProvider = "opencode-go",
            sessionHeader = true,
            noteRes = R.string.rumi_provider_note_opencode_go,
        ),
        RumiProvider(
            id = "opencode-zen",
            label = "OpenCode Zen",
            baseUrl = "https://opencode.ai/zen/v1",
            protocol = RumiProtocol.Chat,
            auth = RumiAuth.Bearer,
            // In the shared catalog the gateway sits under the key `opencode` — `opencode-zen`
            // is not there. Verified against `models.dev/api.json` itself: under `opencode`
            // there are 118 models, under `opencode-go` — 34, and these are two different subtrees.
            capabilityProvider = "opencode",
            sessionHeader = true,
            noteRes = R.string.rumi_provider_note_opencode_zen,
        ),
        RumiProvider(
            id = "gemini",
            label = "Google Gemini",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            protocol = RumiProtocol.Chat,
            auth = RumiAuth.Bearer,
            capabilityProvider = "google",
            noteRes = R.string.rumi_provider_note_gemini,
        ),
        RumiProvider(
            id = "anthropic",
            label = "Anthropic",
            baseUrl = "https://api.anthropic.com/v1",
            protocol = RumiProtocol.Messages,
            auth = RumiAuth.XApiKey,
            // The API version is mandatory and versioned by date: without it — a refusal,
            // not "take the latest".
            extraHeaders = mapOf("anthropic-version" to "2023-06-01"),
            capabilityProvider = "anthropic",
            noteRes = R.string.rumi_provider_note_anthropic,
        ),
        RumiProvider(
            id = "openai",
            label = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            protocol = RumiProtocol.Chat,
            auth = RumiAuth.Bearer,
            capabilityProvider = "openai",
            noteRes = R.string.rumi_provider_note_openai,
        ),
        RumiProvider(
            id = "custom",
            label = "Custom",
            baseUrl = "",
            protocol = RumiProtocol.Chat,
            auth = RumiAuth.Bearer,
            capabilityProvider = "",
            custom = true,
            noteRes = R.string.rumi_provider_note_custom,
        ),
    )

    fun byId(id: String): RumiProvider? = builtIn.firstOrNull { it.id == id }

    /**
     * Find a provider by identifier, including user ones.
     *
     * An unknown identifier (the setting was written by another build) reads as
     * "default" rather than as emptiness: an empty address would look like a broken
     * app rather than a setting that needs to be re-chosen.
     */
    fun resolve(id: String, custom: List<RumiProvider>): RumiProvider =
        custom.firstOrNull { it.id == id }
            ?: byId(id)
            ?: byId(DEFAULT_ID)!!

    /** The auth headers for this provider. */
    fun authHeaders(provider: RumiProvider, credential: String): Map<String, String> =
        when (provider.auth) {
            RumiAuth.Bearer -> mapOf("authorization" to "Bearer $credential")
            RumiAuth.XApiKey -> mapOf("x-api-key" to credential)
            RumiAuth.Header -> mapOf(provider.authHeader.ifBlank { "authorization" } to credential)
        }
}
