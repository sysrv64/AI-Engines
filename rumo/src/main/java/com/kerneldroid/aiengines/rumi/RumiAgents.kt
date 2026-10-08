// SPDX-License-Identifier: GPL-3.0-or-later
package com.kerneldroid.aiengines.rumi

import com.kerneldroid.aiengines.RumiHost

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/**
 * Tools that only look.
 *
 * The criterion is whether a tool changes the project or the editor state, not
 * whether it writes anything to disk at all: `snapshot` saves a picture but changes
 * nothing, and it is exactly what you check the result with. Whereas `seek` and
 * `select_layer` do not change the project but do change the editor state — and
 * therefore do not belong here: a sub-agent yanking the playhead looks to the user
 * like spontaneous movement.
 *
 * `media` does not belong here in full: it has `add`, and a role allowed to read the
 * pool must not refill it by oversight. The pool can be read by `get_media_pool`, and
 * that is enough.
 */
private val READ_ONLY_TOOLS: Set<String> = setOf(
    "project_state",
    "get_media_pool",
    "snapshot",
    "filmstrip",
    "compare_effects",
    "analyze",
    "analyze_audio_stream",
    "fonts",
    "svg_rasterize",
    "web_search",
    // `media` in full, not just `list`: looking at a reference before it is placed is
    // exactly how you check. Addition inside the tool is closed off separately
    // (`mayMutate`), because a restriction by action names does not express it.
    "media",
)

/**
 * A sub-agent role.
 *
 * A role is not a label but a different tool set. The difference between "look and
 * report" and "do" has to lie in what the model can call, not in what it is told about
 * it: a tool that does not exist cannot be called by accident.
 *
 * The names match the ones mcode knows (`explore`, `worker`, `verifier`), so the habit
 * carries over.
 */
enum class RumiAgentRole(
    val id: String,
    /** One line for the card and for the list in `task_query`. */
    val summary: String,
    /** `null` means the full tool set. */
    internal val allow: Set<String>?,
    /** What is appended to the system prompt. */
    internal val brief: String,
) {
    EXPLORE(
        id = "explore",
        summary = "Looks and reports; changes nothing.",
        allow = READ_ONLY_TOOLS,
        brief = """
            Your job is to look, not to change. Read the project, render what you need,
            and report what is actually there.

            The tools that change the project are deliberately not in your list. Do not
            describe a change you would make as though you had made it, and do not say
            that something was done: you cannot do it. Say what you found, with the
            numbers and names you saw.
        """.trimIndent(),
    ),
    WORKER(
        id = "worker",
        summary = "Does the work with every tool.",
        allow = null,
        brief = """
            Your job is to do the work, not to describe it. You have every tool the main
            assistant has.

            Make the change the job asks for, then check it — render a snapshot or a
            filmstrip if the result is something you can look at — and report what you
            actually changed: which layers, which numbers. Say plainly what you did not
            do, and never claim a result you did not see.
        """.trimIndent(),
    ),
    VERIFIER(
        id = "verifier",
        summary = "Checks someone else's work; changes nothing.",
        allow = READ_ONLY_TOOLS,
        brief = """
            Your job is to check work someone else did, and to be able to disagree with
            it.

            You cannot change the project — that is deliberate, so that your report cannot
            become the thing it reports on. Read, render, compare, and say what you found:
            what is right, what is wrong, and what you could not tell from what you were
            given. If everything is right, say that plainly instead of inventing a
            problem, and say what you actually looked at.
        """.trimIndent(),
    ),
    ;

    /** Looks and does not change. That entails both the tool set and the ban on edits. */
    val readOnly: Boolean get() = allow != null

    companion object {
        fun of(name: String): RumiAgentRole? =
            entries.firstOrNull { it.id == name.trim().lowercase() }
    }
}

/**
 * A sub-agent: a second conversation with the same project and the same tools.
 *
 * This is not a second loop next to [RumiSession] but the very same session with a
 * different brief, a different tool set and no entry in the chat list. A copy of the
 * loop would diverge from the original at the first protocol change, and the sub-agent
 * would start answering by the old rules — unnoticed, because its answer is read not by
 * the user but by the model.
 *
 * It lives on its own `scope`: stopping the main conversation need not kill the work
 * it itself launched — while `task_stop` stops exactly this one.
 */
class RumiSubagent internal constructor(
    /** The id that the tools and the card in the transcript address it by. */
    val id: String,
    /** A short name for the card. */
    val description: String,
    val role: RumiAgentRole,
    host: RumiHost,
    /** The conversation it belongs to: it is saved by it. */
    private val owner: String?,
    /** Where the model comes from; queried at the start of every turn. */
    modelSource: () -> RumiModel?,
    /**
     * The conversation from disk, if the sub-agent is not running now but was read back.
     *
     * A restored sub-agent is not a museum piece: it can continue work, and `task_append`
     * to it means the same as to a live one — "here is more, taking into account
     * everything you already know".
     */
    restoredChat: RumiChat? = null,
) {
    private val session = RumiSession(
        host = host,
        // A sub-agent does not spawn grandchildren: an unbounded tree stops being
        // surveyable, and the user cannot keep track of it. `null` for the registry
        // removes the `task*` tools from its set entirely.
        tools = host.toolSet(null, role.allow, !role.readOnly),
        agentOwner = owner,
        agentRole = role.id,
        brief = role.brief,
        modelSource = modelSource,
        restored = restoredChat,
        // In the service notification the sub-agent goes by its own name: background
        // work outlives the turn that launched it, and "Rumi" there would be a lie about
        // whose work it is.
        workLabel = description,
    )

    init {
        // A fresh sub-agent is named by the registry, not by a generator: it is also the
        // file name by which the card finds the conversation after a restart.
        if (restoredChat == null) session.adoptChat(id, description)
    }

    /** Its own transcript — what you see if you open the card. */
    val turns: StateFlow<List<RumiTurnUi>> = session.turns

    val status: StateFlow<RumiStatus> = session.status

    /** Whether work is running right now. */
    val busy: Boolean get() = session.busy

    private var stopReason: String? = null

    /** Whether it answered, rather than failed or being stopped. */
    val succeeded: Boolean
        get() = stopReason == null && session.status.value !is RumiStatus.Failed

    fun start(prompt: String) = session.send(prompt)

    /**
     * Deliver one more message to it.
     *
     * To a busy one — into the queue until the end of the current turn, not as an
     * interruption: a message lost to an inconvenient moment is a message that never was.
     */
    fun append(content: String) = session.resume(content)

    fun stop(reason: String?) {
        stopReason = reason?.takeIf { it.isNotBlank() } ?: "stopped"
        session.stop()
    }

    /** Wait for the work to end: the current turn and all continuations after it. */
    suspend fun await() = session.await()

    /**
     * What it answered.
     *
     * Handed to the model as the call result, so it is JSON: the main agent has to get
     * both the text and the state to tell "done" from "still working" without parsing
     * prose.
     */
    fun report(): String {
        val state = when {
            stopReason != null -> "stopped"
            session.status.value is RumiStatus.Failed -> "failed"
            busy -> "running"
            else -> "finished"
        }
        val body = JSONObject()
            .put("ok", succeeded)
            .put("task_id", id)
            .put("agent", role.id)
            .put("status", state)
        val text = finalText()
        if (text.isNotEmpty()) body.put("result", text)
        (session.status.value as? RumiStatus.Failed)?.let { body.put("error", it.reason) }
        stopReason?.let { body.put("stop_reason", it) }
        if (text.isEmpty() && busy) {
            body.put(
                "note",
                "It has not answered yet. Read it again later; do not guess what it " +
                    "will say.",
            )
        }
        return body.toString()
    }

    /** One line for the list in `task_query`. */
    fun describe(): String {
        val state = when {
            stopReason != null -> "stopped"
            session.status.value is RumiStatus.Failed -> "failed"
            busy -> "running"
            else -> "finished"
        }
        val text = finalText().replace('\n', ' ')
        return "$id · ${role.id} · $state · $description" +
            (if (text.isEmpty()) "" else " · ${text.take(160)}")
    }

    /** The last thing it said. Empty while it has not said anything. */
    fun finalText(): String {
        val turn = turns.value.lastOrNull { it.role == "assistant" } ?: return ""
        val said = turn.parts.filterIsInstance<RumiPart.Text>().joinToString("\n\n") { it.text }
        if (said.isNotBlank()) return said
        // A provider error lives in the `error` field, not in the text: without it
        // report() would return "finished" without a word about what happened.
        return turn.error ?: ""
    }

    /** Save the conversation right now. Called when the app goes to the background. */
    fun persistNow() = session.persistNow()

    fun close() = session.close()
}

/**
 * The sub-agents of one conversation.
 *
 * One registry per main session, not the other way round: the session does not know
 * about sub-agents and must not — they are launched by tools, and the tools receive the
 * registry from outside. This breaks the cycle "session → tools → registry → session".
 */
class RumiAgentRegistry internal constructor(private val host: RumiHost) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _tasks = MutableStateFlow<List<RumiSubagent>>(emptyList())

    /** All sub-agents of the current conversation, in launch order. */
    val tasks: StateFlow<List<RumiSubagent>> = _tasks.asStateFlow()

    /**
     * Where the model comes from.
     *
     * Set by the owner after the session is built: a sub-agent has no model catalogue
     * of its own, and has no one to ask.
     */
    internal var modelSource: (() -> RumiModel?)? = null

    /**
     * Where the current conversation comes from.
     *
     * The registry watches the conversation change itself rather than waiting to be
     * called: sub-agents belong to a conversation, and switching chats without a reload
     * would show one conversation's work in another.
     */
    internal var chatSource: (() -> StateFlow<String?>)? = null

    /** The conversation whose sub-agents are currently listed. */
    private var owner: String? = null

    /** Start following the conversation. Called by the owner after the session is built. */
    fun start() {
        val source = chatSource ?: return
        scope.launch {
            source().collect { chatId ->
                if (chatId == owner) return@collect
                owner = chatId
                _tasks.value = if (chatId == null) {
                    emptyList()
                } else {
                    withContext(Dispatchers.IO) { load(chatId) }
                }
                byCall.clear()
            }
        }
    }

    /**
     * The tool call with which the sub-agent was launched.
     *
     * The transcript shows the card from the first second, not the last: the call row
     * knows only its own `callId`, and without this link the card would appear only when
     * the work had already ended — that is, when there is nothing to look at.
     */
    private val byCall = mutableMapOf<String, RumiSubagent>()

    fun spawn(
        description: String,
        prompt: String,
        role: RumiAgentRole,
        callId: String = "",
    ): RumiSubagent {
        val agent = RumiSubagent(
            id = newId(),
            description = description,
            role = role,
            host = host,
            owner = owner,
            modelSource = { modelSource?.invoke() },
        )
        _tasks.value = _tasks.value + agent
        if (callId.isNotEmpty()) byCall[callId] = agent
        agent.start(prompt)
        return agent
    }

    fun agentForCall(callId: String): RumiSubagent? = byCall[callId]

    fun list(): List<RumiSubagent> = _tasks.value

    fun find(id: String): RumiSubagent? = _tasks.value.firstOrNull { it.id == id }

    /**
     * Read a conversation's sub-agents from disk.
     *
     * The role comes from the file: roles have different tool sets, and a sub-agent
     * restored as "something" is not restored. A file without a role is skipped — just
     * like a corrupt one: showing work that cannot be continued is better than giving it
     * rights it never had.
     */
    private suspend fun load(chatId: String): List<RumiSubagent> =
        RumiChatStore.loadAgents(host.context, chatId).mapNotNull { stored ->
            val role = RumiAgentRole.of(stored.role) ?: return@mapNotNull null
            RumiSubagent(
                id = stored.chat.id,
                description = stored.chat.title,
                role = role,
                host = host,
                owner = chatId,
                modelSource = { modelSource?.invoke() },
                restoredChat = stored.chat,
            )
        }

    /** Save everyone currently listed. Called when the app goes to the background. */
    fun persistAll() {
        _tasks.value.forEach { it.persistNow() }
    }

    /** Stop everything. Called when the owner closes. */
    fun close() {
        _tasks.value.forEach { it.close() }
        _tasks.value = emptyList()
        byCall.clear()
        scope.cancel()
    }

    private fun newId(): String =
        "bg_" + UUID.randomUUID().toString().take(8)
}
