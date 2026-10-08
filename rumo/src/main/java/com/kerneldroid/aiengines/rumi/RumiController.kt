// SPDX-License-Identifier: GPL-3.0-or-later
package com.kerneldroid.aiengines.rumi

import com.kerneldroid.aiengines.RumiHost

/**
 * The owner of the conversation: the session and what it can do with the app.
 *
 * ## Why not in the screen
 *
 * `RumiSession` used to be created inside `RumiScreen` and closed in
 * `onDispose`. `NavHost` destroys the screen when leaving a tab, so going
 * to "Editor" and back killed the conversation: the history disappeared and the turn that
 * was in progress was cut off. A screen is a view, not an owner: it comes and goes, while the
 * conversation must survive both a tab change and a recomposition.
 *
 * So the owner lives at the navigation level, next to the dock, and survives a
 * route change. Its lifetime is like the editor state's: as long as the activity lives.
 * This is deliberate: the in-memory project also does not survive a screen rotation, and
 * a conversation left without a project would be a conversation about nothing.
 *
 * ## What the host does here
 *
 * [RumiHost] is the only surface the model can reach the app through, and it is
 * assembled by the app rather than here: the tools and the editor state it drives
 * live in the app, and this class only owns the conversation. The permission dialog
 * is raised by the screen, but the **state** "the assistant asked for access" lives
 * in the host, so it survives recomposition and is not lost if the screen
 * was rebuilt at that moment.
 */
class RumiController(
    /** The app's side of the assistant: navigation, media access, project, work. */
    val host: RumiHost,
) {
    /**
     * The sub-agents of this conversation.
     *
     * The registry is assembled before the session, because the session receives it through
     * the tools; and it asks the session for the model — and that is the only link
     * that could not have been turned around differently. Hence [RumiAgentRegistry.modelSource],
     * which is set below: the cycle "session → tools → registry → session"
     * is broken with a single assignment instead of a flag in the constructor.
     */
    val agents = RumiAgentRegistry(host)

    /** The conversation. One per app: the tab shows it rather than owning it. */
    val session = RumiSession(host, host.toolSet(agents, null, true))

    init {
        agents.modelSource = { session.model }
        agents.chatSource = { session.chatId }
        // The registry watches for a conversation change itself: sub-agents belong to the conversation,
        // and switching the chat without a reload would show one
        // conversation's jobs in another.
        agents.start()
    }

    /**
     * Open the editor on the current project.
     *
     * `keep = true` — both for the model and for the menu item. Without it the editor treats the
     * route as "start over" and calls `newProject`, which rebuilds
     * the layer list: a request to Rumi to build something followed by "opened the editor"
     * showed an empty "New Project 1", and the assistant's work disappeared. The in-memory
     * project is its only copy until it is saved. The host's `navigate("open_editor")`
     * carries that decision.
     */
    fun openEditor() {
        host.navigate("open_editor", null)
    }

    /** Save everything not yet written. Called when the app goes to the background. */
    fun persistNow() {
        session.persistNow()
        agents.persistAll()
    }

    fun close() {
        agents.close()
        session.close()
    }
}
