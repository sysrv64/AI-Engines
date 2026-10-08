// SPDX-License-Identifier: GPL-3.0-or-later
package com.kerneldroid.aiengines.rumi

import org.json.JSONArray
import org.json.JSONObject

// --- JSON Schema builders ---
// Written out rather than hand-rolled strings: a typo in a schema is a silently
// wrong tool call, and the provider validates against exactly this object.
//
// Public, not internal: the editor's tool implementations live in the host app
// (`RumiTools`), and a second set of the same builders there would diverge from
// this one at the first format change.

fun str(description: String): JSONObject =
    JSONObject().put("type", "string").put("description", description)

fun num(description: String, min: Double? = null, max: Double? = null): JSONObject =
    JSONObject().put("type", "number").put("description", description).apply {
        min?.let { put("minimum", it) }
        max?.let { put("maximum", it) }
    }

fun bool(description: String): JSONObject =
    JSONObject().put("type", "boolean").put("description", description)

fun oneOf(description: String, vararg values: String): JSONObject {
    val list = JSONArray()
    values.forEach { list.put(it) }
    return JSONObject().put("type", "string").put("enum", list).put("description", description)
}

/** One property of a tool's argument object. */
class Prop(val name: String, val schema: JSONObject, val required: Boolean)

/** A property the model must send. */
fun req(name: String, schema: JSONObject) = Prop(name, schema, true)

/** A property the model may omit. */
fun opt(name: String, schema: JSONObject) = Prop(name, schema, false)

/**
 * An object schema. `additionalProperties` is false on purpose: a provider that
 * helpfully passes an unexpected key would otherwise get a silently ignored
 * argument instead of a clear rejection.
 */
fun obj(vararg props: Prop): JSONObject {
    val properties = JSONObject()
    val required = JSONArray()
    props.forEach { prop ->
        properties.put(prop.name, prop.schema)
        if (prop.required) required.put(prop.name)
    }
    return JSONObject()
        .put("type", "object")
        .put("properties", properties)
        .put("required", required)
        .put("additionalProperties", false)
}
