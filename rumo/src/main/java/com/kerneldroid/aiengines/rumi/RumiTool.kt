// SPDX-License-Identifier: GPL-3.0-or-later
package com.kerneldroid.aiengines.rumi

/** What one tool call produced. */
data class RumiToolOutcome(
    /** Text handed back to the model. */
    val text: String,
    /** A picture the model may look at when it accepts images. */
    val png: ByteArray? = null,
    /** Where the picture was written, in Download/Rumo. */
    val path: String? = null,
    /** One line for the collapsed tool row in the transcript. */
    val summary: String? = null,
    val ok: Boolean = true,
    /**
     * The sub-agent this call created or addressed.
     *
     * Needed by the transcript so that the call row becomes a card that opens the
     * sub-agent's conversation: this identifier cannot be obtained from the call
     * arguments — it appears at spawn time.
     */
    val agentId: String? = null,
)

/**
 * The tools one conversation may call: the schemas handed to the provider and the
 * entry point that runs a call.
 *
 * The conversation loop names this and not the editor's `RumiTools`, which is
 * what lets the loop live in the module while the tool implementations stay in
 * the app. A sub-agent role gets a restricted instance from the host
 * ([com.kerneldroid.aiengines.RumiHost.toolSet]) instead of a second loop.
 */
interface RumiToolbox {
    /** The schemas to send, already filtered by what this conversation may call. */
    val activeSpecs: List<RumiToolSpec>

    suspend fun call(name: String, argumentsJson: String, callId: String = ""): RumiToolOutcome

    /** The sub-agent a call started, if any — see [RumiToolOutcome.agentId]. */
    fun agentIdForCall(callId: String): String?
}
