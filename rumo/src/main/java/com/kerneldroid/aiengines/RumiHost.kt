// SPDX-License-Identifier: GPL-3.0-or-later
package com.kerneldroid.aiengines

import android.content.Context
import com.kerneldroid.aiengines.rumi.RumiAgentRegistry
import com.kerneldroid.aiengines.rumi.RumiToolbox

/**
 * The project as the system prompt describes it.
 *
 * A snapshot, not the editor: the module must be able to say what is on the canvas
 * without naming `EditorState`, and the prompt only ever needs these seven facts.
 * The host reads it fresh on every access, exactly as the prompt used to read the
 * state flows, so a turn always describes the project as it is at that moment.
 */
data class RumiProject(
    val name: String,
    val layerCount: Int,
    val durationMs: Long,
    val playheadMs: Long,
    val canvasWidth: Int,
    val canvasHeight: Int,
    val backgroundArgb: Long,
)

/**
 * One piece of work the host keeps alive while it runs.
 *
 * The module needs supervision a plain coroutine does not give it — a model answer
 * takes minutes, and leaving the app must not cut it off — but how that is done
 * (here: a foreground service) belongs to the host.
 */
interface RumiWork {
    /** What is happening right now, in words. */
    fun detail(text: String)

    /** The work ended — in any way. Idempotent. */
    fun close()
}

/**
 * What the assistant needs from whatever hosts it.
 *
 * This is the whole surface the module can reach the app through. It is not the
 * editor's tool host (`RumiToolHost`, which names `EditorState`): it is the
 * narrower contract the conversation, the agent registry and the assistant screen
 * are written against, so that they compile without the app on the classpath.
 */
interface RumiHost {
    val context: Context

    /**
     * A tool surface for one conversation.
     *
     * [agents] is the registry that makes the `task*` tools exist, or `null` for a
     * conversation that must not spawn sub-agents; [only] restricts by tool name and
     * [mayMutate] closes off edits, both as an agent role needs. The app builds it
     * over its own `RumiTools`, which is where the editor tools live.
     */
    fun toolSet(
        agents: RumiAgentRegistry?,
        only: Set<String>?,
        mayMutate: Boolean,
    ): RumiToolbox

    /** Move around the app: `open_editor`, `open_panel` with [panel]. A short acknowledgement returns. */
    fun navigate(action: String, panel: String?): String

    /** True when the app may read the references the user dropped in the project folder. */
    fun mediaAccessGranted(): Boolean

    /**
     * Ask for that access. Returns what the model should say about it — the
     * dialog is the user's, and a tool cannot wait for a human.
     */
    fun requestMediaAccess(): String

    /** The permissions still missing before media access can be granted. */
    fun missingMediaPermissions(): List<String>

    /**
     * The state of the access dialog, which the screen raises and the host's
     * [requestMediaAccess] arms.
     *
     * It lives here rather than in the controller because the tool that arms it
     * (`requestMediaAccess`) is on this side: the flags and the call that sets
     * them must not be separated by a boundary.
     */
    val wantMediaAccess: Boolean

    /** Take the access request, if there is one. True exactly once per request. */
    fun consumeMediaAccessRequest(): Boolean

    /** Access was granted and the conversation should resume on its own. */
    val resumeAfterMediaAccess: Boolean

    /**
     * The user's answer to the access dialog.
     *
     * Whether access was granted is asked of the platform when it matters; what
     * matters here is only that the user **answered** the question the tool asked.
     */
    fun mediaAccessAnswered(granted: Boolean)

    /** Take the "access granted, continue" — exactly once. */
    fun consumeResume(): Boolean

    /** The project as the system prompt describes it, read fresh on every access. */
    val project: RumiProject

    /** Put this conversation's turn under the host's work supervision. */
    fun startChatWork(label: String): RumiWork
}
