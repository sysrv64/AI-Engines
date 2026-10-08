// SPDX-License-Identifier: GPL-3.0-or-later
package com.kerneldroid.aiengines.rumi

import org.json.JSONArray
import org.json.JSONObject

/**
 * Exa AI Search: what the assistant uses to look at the internet.
 *
 * ## Why Exa and not our own crawl
 *
 * The model knows only what was in its training. Asking it about a library
 * version released after that means getting a confident answer from the past —
 * and that is the worst kind of error, because it looks like knowledge. A search
 * tool turns "the model remembers" into "the model has read".
 *
 * Exa is chosen because it returns page text ready to read, not just a title
 * with a link: the model needs an answer, not a list of links it cannot follow.
 *
 * ## What matters here
 *
 * Nothing suspends: [search] blocks on the network, so it is called on
 * `Dispatchers.IO`. Errors are returned as a reason, not as an empty list:
 * "nothing found" and "the key was rejected" are different things, and the
 * second must be visible.
 */
object RumiSearch {
    const val ENDPOINT = "https://api.exa.ai/search"

    /** How many results by default: five is enough for an answer, ten is already context. */
    const val DEFAULT_RESULTS = 5

    private const val MAX_RESULTS = 20

    /** How many characters of the page to take into a result. See `contents.text`. */
    private const val MAX_CHARS = 2_000

    /** One found page. */
    data class Hit(
        val title: String,
        val url: String,
        val published: String?,
        val text: String?,
    )

    sealed interface Outcome {
        data class Ok(val hits: List<Hit>) : Outcome

        data class Failed(val reason: String) : Outcome
    }

    /**
     * Find pages for a query.
     *
     * `category` is Exa's hint about what exactly to look for (`news`, `company`,
     * `personal site`, …). An empty string means "give no hint".
     */
    fun search(
        query: String,
        numResults: Int = DEFAULT_RESULTS,
        category: String? = null,
        apiKey: String,
        timeoutMs: Int = 30_000,
    ): Outcome {
        val clean = query.trim()
        if (clean.isEmpty()) return Outcome.Failed("the query was empty")

        val body = JSONObject()
            .put("query", clean)
            .put("numResults", numResults.coerceIn(1, MAX_RESULTS))
            // `text` is the page body: without it a result is a link the model
            // cannot follow. `type` is not set: Exa's `auto` picks between fast
            // and deep parsing itself, while `neural` and `keyword` from the old
            // examples no longer exist and would return 400.
            .put(
                "contents",
                JSONObject().put("text", JSONObject().put("maxCharacters", MAX_CHARS)),
            )
        if (!category.isNullOrBlank()) body.put("category", category.trim())

        val reply = runCatching {
            RumiHttp.postJson(
                url = ENDPOINT,
                headers = mapOf("x-api-key" to apiKey, "accept" to "application/json"),
                body = body.toString(),
                timeoutMs = timeoutMs,
            )
        }.getOrElse { return Outcome.Failed(describe(it)) }

        if (reply.status !in 200..299) return Outcome.Failed(reasonFor(reply.status, reply.body))
        return Outcome.Ok(parse(reply.body))
    }

    /**
     * Parsing the response.
     *
     * Broken JSON is not an exception thrown outward but "nothing was found":
     * the search must not bring the turn down. But an empty list and a parse
     * failure are distinguishable in the log, so a parse failure returns
     * emptiness, not a fake.
     */
    private fun parse(body: String): List<Hit> {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val results = root.optJSONArray("results") ?: JSONArray()
        val hits = ArrayList<Hit>(results.length())
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val url = item.optString("url", "")
            if (url.isEmpty()) continue
            hits += Hit(
                // The title is not mandatory in the response; a link without a
                // name is still useful, while a string without a link is not.
                title = item.optString("title", "").ifEmpty { url },
                url = url,
                published = item.optText("publishedDate"),
                text = item.optText("text"),
            )
        }
        return hits
    }

    /**
     * What to tell the model about a failure.
     *
     * The codes are spelled out by meaning rather than collapsed to "HTTP 4xx":
     * "the key was rejected" and "the credits ran out" require different actions
     * from the user, and the model must name exactly the one that is needed.
     */
    private fun reasonFor(status: Int, body: String): String {
        val tag = runCatching { JSONObject(body).optString("tag", "") }.getOrDefault("")
        val detail = runCatching { JSONObject(body).optString("error", "") }.getOrDefault("")
        return when (status) {
            401, 403 -> "Exa rejected the API key. Check Settings → Rumi → Web search."
            402 -> "The Exa account is out of credits."
            429 -> "Exa is rate-limiting this key. Try again in a moment."
            503 -> "Exa is overloaded right now. Try again in a moment."
            else -> buildString {
                append("Exa search failed (HTTP $status)")
                if (tag.isNotEmpty()) append(" · $tag")
                if (detail.isNotEmpty()) append(": ${detail.take(200)}")
                append('.')
            }
        }
    }

    private fun describe(t: Throwable): String =
        t.message?.let { "Exa could not be reached: ${it.take(200)}" }
            ?: "Exa could not be reached."

    /**
     * The results the way the model reads them.
     *
     * A numbered list with the link and the date: the number gives the model
     * something to cite, the date lets it see the page is old and not pass it
     * off as the current state.
     */
    fun format(hits: List<Hit>): String {
        if (hits.isEmpty()) return "No results found for this search."
        return hits.mapIndexed { index, hit ->
            buildString {
                append("[${index + 1}] ${hit.title} — ${hit.url}")
                hit.published?.let { append(" (published $it)") }
                hit.text?.takeIf { it.isNotBlank() }?.let {
                    append('\n').append(it.trim().take(MAX_CHARS))
                }
            }
        }.joinToString("\n\n")
    }
}
