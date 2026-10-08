// SPDX-License-Identifier: GPL-3.0-or-later
package com.kerneldroid.aiengines.rumi

import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reading optional strings out of JSON without meeting the word "null".
 *
 * `JSONObject.optString(name, fallback)` is not the safe read it looks like on
 * Android: it stringifies the value, and a stored JSON `null` stringifies to the
 * text `"null"` rather than falling back. So a conversation whose tool results
 * and message bodies were written as `null` came back with the literal word
 * "null" in every one of those places — which is exactly what a reopened chat
 * looked like.
 *
 * This checks for absence and for the null sentinel *before* reading, so the
 * result does not depend on how the platform stringifies the sentinel, and an
 * empty string counts as absent because every caller here means "nothing to
 * show" by it.
 */
fun JSONObject.optText(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key, "").ifEmpty { null }
}

/** The same read, for an array element that may still be null. */
fun JSONArray.optTextOr(index: Int, fallback: String): String {
    if (index < 0 || index >= length() || isNull(index)) return fallback
    return optString(index, "").ifEmpty { fallback }
}

/** The same read, for a field that is not optional but may still be null. */
fun JSONObject.optTextOr(key: String, fallback: String): String =
    optText(key) ?: fallback

/**
 * `JsonReader.nextString()` throws on a JSON `null`, so a single null field in a
 * document this app does not control would abandon the whole parse. Reading the
 * token first keeps one bad field from costing every good one.
 */
internal fun JsonReader.nextTextOrNull(): String? =
    if (peek() == JsonToken.NULL) {
        nextNull()
        null
    } else {
        runCatching { nextString() }.getOrNull()
    }
