// SPDX-License-Identifier: GPL-3.0-or-later
package com.kerneldroid.aiengines.rumi

import com.kerneldroid.aiengines.AppLog
import com.kerneldroid.aiengines.RumiHost
import com.kerneldroid.aiengines.RumiWork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Base64
import java.util.UUID

/** One rendered part of a turn. */
sealed interface RumiPart {
    data class Text(val text: String) : RumiPart

    data class Reasoning(val text: String) : RumiPart

    /** A tool call, with its arguments and, once it has run, its answer. */
    data class Tool(
        val callId: String,
        val name: String,
        val arguments: String,
        val result: String? = null,
        val ok: Boolean = true,
        val summary: String? = null,
        val imagePath: String? = null,
        val image: ByteArray? = null,
        val running: Boolean = true,
        /**
         * The sub-agent this call started or addressed.
         *
         * It cannot be obtained from the call's arguments: the identifier appears at the moment
         * of the start, not in what the model wrote. Without it the card does not know
         * whose conversation to open.
         */
        val agentId: String? = null,
    ) : RumiPart

    /** Something the assistant layer itself wants to say: a retry, a switch. */
    data class Note(val text: String) : RumiPart
}

/** One message in the transcript. */
data class RumiTurnUi(
    val id: String,
    val role: String,
    val parts: List<RumiPart> = emptyList(),
    val error: String? = null,
)

/** What the assistant is doing right now. */
sealed interface RumiStatus {
    data object Idle : RumiStatus

    data object Waiting : RumiStatus

    data object Streaming : RumiStatus

    data class Running(val tool: String) : RumiStatus

    data class Failed(val reason: String) : RumiStatus
}

/**
 * The conversation: history, provider calls, and the tool loop that turns a
 * model's request into an edit and the edit's result back into the model's
 * context.
 *
 * One turn is: send the history, stream the answer, run every tool the model
 * asked for, append each result, and repeat until it stops asking for tools.
 * The loop is bounded — a model that keeps calling tools without converging is
 * stopped and told so, rather than being allowed to spin.
 *
 * A sub-agent is the same class, not a second loop next to it: it has the same
 * protocol, the same decoding and the same tools; only the brief, the set of
 * tools and the fact that it does not appear in the chat list differ. A copy of the loop would
 * diverge from the original on the very first protocol change, and the sub-agent would start replying
 * by the old rules.
 */
class RumiSession(
    private val host: RumiHost,
    /**
     * The tools this conversation may call: the app's full set for the main
     * conversation, a restricted set for a sub-agent role. It is passed in rather
     * than built here because the implementations live in the app.
     */
    private val tools: RumiToolbox,
    /**
     * The conversation this one belongs to, if it is a sub-agent.
     *
     * A sub-agent has no row of its own in the drawer: it is part of someone else's turn, not a
     * conversation the user opens themselves. An entry in the common list would mean that
     * after a couple of runs the drawer is full of jobs nobody asked for. So it
     * is saved into its conversation's folder, not into the common directory.
     *
     * `null` means an ordinary conversation, which is written into the chat list.
     */
    private val agentOwner: String? = null,
    /**
     * The sub-agent role it worked as.
     *
     * Written next to the conversation: the set of tools differs between roles, and
     * a restored sub-agent without a role could not continue its work — you cannot restore
     * it as "someone".
     */
    private val agentRole: String = "",
    /**
     * What this conversation does, if it is a sub-agent.
     *
     * It is appended to the system prompt rather than replacing it: a sub-agent
     * works with the same project and the same tools, and it must know
     * the same things about them. An empty string means ordinary Rumi.
     */
    private val brief: String = "",
    /**
     * Where the model comes from when there is no catalog of its own.
     *
     * A sub-agent has no catalog, and it has no one to ask. It is asked at the
     * start of every turn rather than at construction: a sub-agent restored from disk
     * is built before the catalog loads, and a model fixed at construction
     * would turn out empty — it could not continue its work.
     */
    private val modelSource: (() -> RumiModel?)? = null,
    /**
     * A conversation resumed from disk.
     *
     * The transcript and the model history are restored together: restoring only the
     * transcript would mean showing a conversation the model does not remember, and
     * the next question would get an answer from an assistant hearing about its
     * past work for the first time.
     */
    private val restored: RumiChat? = null,
    /**
     * What to call this job in the service notification.
     *
     * For the main conversation it is "Rumi", for a sub-agent — its own name:
     * a sub-agent in the background outlives the turn that started it, and the
     * notification must show whose job this is, not just that "something is
     * going on".
     */
    private val workLabel: String = "Rumi",
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _turns = MutableStateFlow<List<RumiTurnUi>>(emptyList())
    val turns: StateFlow<List<RumiTurnUi>> = _turns.asStateFlow()

    private val _status = MutableStateFlow<RumiStatus>(RumiStatus.Idle)
    val status: StateFlow<RumiStatus> = _status.asStateFlow()

    private val _catalogue =
        MutableStateFlow<RumiModels.Catalogue?>(null)
    val catalogue: StateFlow<RumiModels.Catalogue?> = _catalogue.asStateFlow()

    /** Set while the model list is being refreshed. */
    private val _loadingModels = MutableStateFlow(false)
    val loadingModels: StateFlow<Boolean> = _loadingModels.asStateFlow()

    private var job: Job? = null

    /**
     * A continuation that arrived while a turn was still running, waiting for it
     * to end — the read-media answer, typically.
     */
    private var queuedResume: String? = null

    @Volatile
    private var cancelled = false

    /** When the turn was last saved by itself. See [autosave]. */
    private var lastAutosave = 0L

    /** The wire history. Kept separately from the transcript: they differ. */
    private val history = mutableListOf<RumiMessage>()

    /** Stored conversations, newest first. */
    private val _chats = MutableStateFlow<List<RumiChatSummary>>(emptyList())
    val chats: StateFlow<List<RumiChatSummary>> = _chats.asStateFlow()

    /** The conversation being added to, or null before the first message. */
    private val _chatId = MutableStateFlow<String?>(null)
    val chatId: StateFlow<String?> = _chatId.asStateFlow()

    private val _chatTitle = MutableStateFlow("New chat")
    val chatTitle: StateFlow<String> = _chatTitle.asStateFlow()

    /** When the open conversation started; kept so a save does not move it. */
    private var chatCreatedAt: Long = 0L

    /** Reload the history list from disk. */
    fun refreshChats() {
        scope.launch {
            _chats.value = withContext(Dispatchers.IO) { RumiChatStore.list(host.context) }
        }
    }

    /**
     * Start a new conversation, keeping the current one on disk.
     *
     * The empty state is not stored: a chat exists once it has something to say,
     * so opening the history of someone who never sent a message shows nothing
     * rather than a row of blanks.
     */
    fun newChat() {
        stop()
        val previous = _chatId.value
        val turns = _turns.value
        if (previous != null && turns.isNotEmpty()) {
            val pending = snapshot(previous, _chatTitle.value)
            scope.launch { withContext(Dispatchers.IO) { RumiChatStore.save(host.context, pending) } }
        }
        history.clear()
        _turns.value = emptyList()
        _chatId.value = null
        _chatTitle.value = "New chat"
        chatCreatedAt = 0L
        _status.value = RumiStatus.Idle
        refreshChats()
    }

    /** Switch to a stored conversation. */
    fun openChat(id: String) {
        if (id == _chatId.value) return
        stop()
        val previous = _chatId.value
        val turns = _turns.value
        if (previous != null && turns.isNotEmpty()) {
            val pending = snapshot(previous, _chatTitle.value)
            scope.launch { withContext(Dispatchers.IO) { RumiChatStore.save(host.context, pending) } }
        }
        scope.launch {
            val chat = withContext(Dispatchers.IO) { RumiChatStore.load(host.context, id) }
            if (chat == null) {
                _turns.value = _turns.value + errorTurn("That conversation could not be opened.")
                return@launch
            }
            history.clear()
            history.addAll(chat.messages)
            _turns.value = chat.turns
            _chatId.value = chat.id
            _chatTitle.value = chat.title
            chatCreatedAt = chat.createdAt
            if (chat.modelId.isNotEmpty() && RumiSettings.state.value.modelId.isEmpty()) {
                RumiSettings.setModelId(chat.modelId)
            }
            _status.value = RumiStatus.Idle
        }
    }

    /** Forget a conversation. The one on screen is replaced by a fresh one. */
    /**
     * Rename a conversation.
     *
     * The name is changed both in the file and in the open conversation: a conversation renamed in the
     * list and left with the old name on screen is two names for one
     * conversation, and both look real.
     */
    fun renameChat(id: String, title: String) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        if (id == _chatId.value) _chatTitle.value = clean
        scope.launch {
            val existing = withContext(Dispatchers.IO) { RumiChatStore.load(host.context, id) }
            if (existing == null) {
                // There is no file — so the conversation is not saved yet (an empty chat is not
                // stored). The name is remembered above anyway and will go into the file together
                // with the first message.
                refreshChats()
                return@launch
            }
            withContext(Dispatchers.IO) {
                RumiChatStore.save(host.context, existing.copy(title = clean))
            }
            refreshChats()
        }
    }

    fun deleteChat(id: String) {
        scope.launch {
            withContext(Dispatchers.IO) {
                RumiChatStore.delete(host.context, id)
                // Sub-agents go together with their conversation: a folder without a
                // parent is jobs there is no one left to show.
                RumiChatStore.deleteAgents(host.context, id)
            }
            if (id == _chatId.value) {
                // The open conversation is dropped from memory rather than handed
                // to `newChat`: that writes the current conversation out first,
                // which would put the file we just unlinked straight back.
                stop()
                history.clear()
                _turns.value = emptyList()
                _chatId.value = null
                _chatTitle.value = "New chat"
                chatCreatedAt = 0L
                _status.value = RumiStatus.Idle
            }
            refreshChats()
        }
    }

    /** The chat as it stands, ready to be written. */
    private fun snapshot(id: String, title: String): RumiChat {
        val now = System.currentTimeMillis()
        return RumiChat(
            id = id,
            title = title,
            createdAt = if (chatCreatedAt > 0L) chatCreatedAt else now,
            updatedAt = now,
            modelId = model?.id ?: RumiSettings.state.value.modelId,
            turns = _turns.value,
            messages = history.toList(),
        )
    }

    /** Write the conversation. Cheap enough to call after every turn. */
    private fun persist() {
        val id = _chatId.value ?: return
        if (_turns.value.isEmpty()) return
        val chat = snapshot(id, _chatTitle.value)
        val owner = agentOwner
        scope.launch {
            if (owner != null) {
                withContext(Dispatchers.IO) {
                    RumiChatStore.saveAgent(host.context, owner, chat, agentRole)
                }
            } else {
                withContext(Dispatchers.IO) { RumiChatStore.save(host.context, chat) }
                _chats.value = withContext(Dispatchers.IO) { RumiChatStore.list(host.context) }
            }
        }
    }

    /**
     * Save the conversation right now, without waiting for the turn to end.
     *
     * Needed where the app leaves the screen: a turn can run for minutes, and
     * a conversation written only on its completion would lose everything that had
     * appeared, if the system killed the app or the user left the
     * tab and the process died in the background.
     */
    fun persistNow() = persist()

    /**
     * Autosave while the stream is running.
     *
     * Not on every chunk: a file write per word is a disk churning
     * hundreds of times per answer. Once every [AUTOSAVE_MS] is enough to lose
     * no more than a few seconds on a crash, and is unnoticed in cost.
     */
    private fun autosave() {
        val now = System.currentTimeMillis()
        if (now - lastAutosave < AUTOSAVE_MS) return
        lastAutosave = now
        persist()
    }

    /**
     * This session's job while the turn is running.
     *
     * One's own per session, not shared: a sub-agent in the background keeps
     * working when the turn that started it has ended, and a shared entry would cancel
     * the notification exactly when it is still needed.
     */
    private var work: RumiWork? = null

    init {
        // The detail in the notification is the same as the status line on the screen:
        // "is using snapshot" says the work is going on, and "is thinking" — that it has not
        // stalled.
        scope.launch {
            _status.collect { status -> work?.detail(workDetail(status)) }
        }
        restored?.let { chat ->
            _chatId.value = chat.id
            _chatTitle.value = chat.title
            _turns.value = chat.turns
            history.addAll(chat.messages)
            chatCreatedAt = chat.createdAt
        }
    }

    /**
     * Name the conversation from outside.
     *
     * For a sub-agent the identifier is given by the registry, not by a generator: the card in
     * the transcript finds the conversation by it, and it is also the file name on disk. Were they
     * to diverge, the card after a restart would open the wrong conversation.
     */
    internal fun adoptChat(id: String, title: String) {
        if (_chatId.value != null) return
        _chatId.value = id
        _chatTitle.value = title
        chatCreatedAt = System.currentTimeMillis()
    }

    /** The model the settings point at, or the first usable one. */
    val model: RumiModel?
        get() {
            modelSource?.invoke()?.let { return it }
            val list = _catalogue.value?.models.orEmpty()
            val wanted = RumiSettings.state.value.modelId
            return list.firstOrNull { it.id == wanted }
                ?: list.firstOrNull { it.tools }
                ?: list.firstOrNull()
        }

    /** True when the assistant can actually work: a model and a credential. */
    val ready: Boolean
        get() = model != null && RumiSettings.state.value.configured

    fun refreshModels(force: Boolean = false) {
        if (_loadingModels.value) return
        _loadingModels.value = true
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                RumiModels.load(host.context, force)
            }
            _catalogue.value = loaded
            _loadingModels.value = false
            val wanted = RumiSettings.state.value.modelId
            if (wanted.isEmpty() || loaded.models.none { it.id == wanted }) {
                // Nothing chosen yet, or the chosen model is gone from the
                // gateway: fall back to one that can actually call tools.
                val pick = loaded.models.firstOrNull { it.tools } ?: loaded.models.firstOrNull()
                if (pick != null) RumiSettings.setModelId(pick.id)
            }
        }
    }

    /** Send one user message and run the loop until the model stops asking. */
    fun send(text: String) = startTurn(text.trim(), asUser = true)

    /**
     * Carry on after something outside the chat unblocked the turn.
     *
     * The read-media dialog the assistant raises is the case this exists for: the
     * model's request has to reach it as a message once the user has answered, but
     * the transcript must not claim the user typed it — so the text goes into the
     * model's history and a note, not a bubble, goes onto the screen.
     */
    fun resume(text: String) = startTurn(text.trim(), asUser = false)

    private fun startTurn(clean: String, asUser: Boolean) {
        if (clean.isEmpty()) return
        if (job?.isActive == true) {
            // A permission answer usually arrives while the model is still
            // finishing the turn that asked for it, so the text is held for the
            // end of that turn instead of being dropped: an assistant that only
            // continues when the timing happens to be right is not one that
            // finishes the job. Typed messages are still refused while busy —
            // the composer is what stops those, not this.
            if (!asUser) queuedResume = clean
            return
        }
        val chosen = model
        val current = RumiSettings.state.value
        val credential = current.credential()
        if (chosen == null) {
            _turns.value = _turns.value + errorTurn("No model is available. Open the model menu and refresh.")
            return
        }
        if (credential == null) {
            _turns.value = _turns.value +
                errorTurn("Rumi has no API key. Open Settings → Rumi and paste one.")
            return
        }
        cancelled = false
        if (_chatId.value == null) {
            _chatId.value = RumiChatStore.newId()
            _chatTitle.value = RumiChatStore.titleFrom(clean)
            chatCreatedAt = System.currentTimeMillis()
        }
        _turns.value = _turns.value + if (asUser) {
            RumiTurnUi(id = newId(), role = "user", parts = listOf(RumiPart.Text(clean)))
        } else {
            RumiTurnUi(id = newId(), role = "assistant", parts = listOf(RumiPart.Note(clean)))
        }
        // Saved immediately, not only at the end of the turn: the user's message is
        // what they wrote, and the answer to it may take minutes. A crash
        // in the middle of the answer must not take the question with it.
        lastAutosave = System.currentTimeMillis()
        persist()
        // The turn is placed under the service's supervision before the first request, not after: the request
        // takes minutes, and all that time leaving the app must not kill it.
        work = host.startChatWork(workLabel)
        job = scope.launch {
            try {
                // The key does not expire, so there is nothing to renew: this used to be
                // where the console refresh token was exchanged.
                runTurn(chosen, credential, clean)
            } catch (t: Throwable) {
                if (!cancelled) {
                    AppLog.error(TAG, "turn failed: ${AppLog.describe(t)}")
                    _turns.value = _turns.value + errorTurn(describe(t))
                }
            } finally {
                // A failure status is what the screen shows; clearing it here
                // would erase the reason before it can be read.
                if (_status.value !is RumiStatus.Failed) _status.value = RumiStatus.Idle
                job = null
                persist()
                // Cleared before the continuation, not after: the continuation takes the work
                // over again, and clearing after it would cancel the new one.
                work?.close()
                work = null
                val held = queuedResume
                queuedResume = null
                // A held continuation runs only when the turn ended on its own:
                // after a stop the user asked for nothing to continue.
                if (held != null && !cancelled) resume(held)
            }
        }
    }

    /** Whether the turn is running right now. */
    val busy: Boolean get() = job?.isActive == true

    /**
     * Wait for the work to end.
     *
     * A loop, not a single `join`: a turn that received a continuation ends by
     * starting the next one, and a wait that released after the first would return
     * control in the middle of the work. Needed by a foreground sub-agent, where
     * the main conversation waits for its answer.
     */
    suspend fun await() {
        while (true) {
            val running = job ?: return
            running.join()
        }
    }

    /** Stop the current turn. The in-flight request is abandoned. */
    fun stop() {
        cancelled = true
        job?.cancel()
        job = null
        queuedResume = null
        _status.value = RumiStatus.Idle
        // A stop by the user is also the end of the work: a leftover entry would keep
        // the notification and the process alive until the app restarts.
        work?.close()
        work = null
    }

    /** Clear the screen and start a new conversation. */
    fun clear() = newChat()

    fun close() {
        stop()
        scope.cancel()
    }

    // --- The loop ---

    private suspend fun runTurn(model: RumiModel, credential: String, userText: String) {
        history += RumiMessage(role = "user", text = userText)
        // No round limit: the loop ends when the model stops asking for tools,
        // or when the user stops it. A fixed count was worse than useless — it
        // cut off work that was going fine at an arbitrary point, and the only
        // thing it protected against is a model that never converges, which the
        // stop button already covers.
        while (!cancelled) {
            val answer = streamOnce(model, credential)
            if (answer.failed != null) {
                _status.value = RumiStatus.Failed(answer.failed)
                return
            }
            // The assistant's own text was appended to its turn while streaming;
            // the transcript entry is created by streamOnce.
            if (answer.toolCalls.isEmpty()) {
                history += RumiMessage(
                    role = "assistant",
                    text = answer.text.ifBlank { null },
                )
                return
            }
            history += RumiMessage(
                role = "assistant",
                text = answer.text.ifBlank { null },
                toolCalls = answer.toolCalls,
            )
            for (call in answer.toolCalls) {
                if (cancelled) return
                _status.value = RumiStatus.Running(call.name)
                val outcome = tools.call(call.name, call.arguments, call.id)
                updateToolPart(answer.turnId, call.id, outcome)
                history += RumiMessage(
                    role = "tool",
                    toolCallId = call.id,
                    toolName = call.name,
                    text = outcome.text,
                    // A picture is only attached when the model says it takes
                    // images; sending one to a text-only model is a hard error
                    // on some providers.
                    images = if (model.vision && outcome.png != null) {
                        listOf(Base64.getEncoder().encodeToString(outcome.png))
                    } else {
                        emptyList()
                    },
                )
            }
            _status.value = RumiStatus.Streaming
        }
    }

    /** One streaming request. Returns what the model said and asked for. */
    private suspend fun streamOnce(model: RumiModel, credential: String): Answer {
        val turnId = newId()
        _turns.value = _turns.value + RumiTurnUi(turnId, "assistant")
        _status.value = RumiStatus.Streaming

        val settings = RumiSettings.state.value
        var lastPush = 0L
        val answer = RumiStream.once(
            url = endpoint(settings.endpoint, model.protocol),
            protocol = model.protocol,
            modelId = model.id,
            credential = credential,
            sessionId = settings.sessionId,
            provider = settings.provider,
            system = systemPrompt(model),
            messages = trimmed(model),
            tools = if (model.tools) tools.activeSpecs else emptyList(),
            maxOutputTokens = outputBudget(model),
            // The choice outlives the model, so it is checked before sending:
            // a level this model does not have is a 400 from the provider out of
            // nowhere, not "the user wanted deeper".
            thinking = settings.thinking.takeIf { RumiThinkingChoice.fits(it, model.thinking) }
                ?: RumiThinkingChoice.Auto,
            reasoningField = model.reasoningField,
            cancelled = { cancelled },
        ) { progress, force ->
            // The transcript is rebuilt from the whole answer on every push, so
            // it is not rebuilt on every token: 60ms is below the threshold at
            // which a reader can tell, and it is what keeps a long answer from
            // recomposing the list hundreds of times.
            val now = System.currentTimeMillis()
            if (!force && now - lastPush < PUSH_INTERVAL_MS) return@once
            lastPush = now
            publish(turnId, progress.text, progress.reasoning, progress.calls, progress.failure)
        }
        publish(turnId, answer.text, answer.reasoning, answer.calls, answer.failure)
        return Answer(
            turnId = turnId,
            text = answer.text,
            toolCalls = answer.calls.map {
                RumiToolCall(id = it.id, name = it.name, arguments = it.arguments)
            },
            failed = answer.failure,
        )
    }

    /**
     * The history as the provider sees it, with old pictures dropped.
     *
     * Vision input is charged by the token and a long conversation would
     * otherwise carry every snapshot ever taken; the text of a tool result stays
     * either way so the model keeps the finding and loses only the pixels.
     */
    private fun trimmed(model: RumiModel): List<RumiMessage> {
        val budget = RumiSettings.state.value.imageBudget
        if (budget <= 0) return history.map { if (it.images.isEmpty()) it else it.copy(images = emptyList()) }
        var left = budget
        val out = ArrayList<RumiMessage>(history.size)
        for (message in history.asReversed()) {
            if (message.images.isEmpty()) {
                out += message
                continue
            }
            val keep = minOf(left, message.images.size)
            left -= keep
            out += if (keep == message.images.size) {
                message
            } else if (keep == 0) {
                message.copy(images = emptyList())
            } else {
                message.copy(images = message.images.takeLast(keep))
            }
        }
        return out.asReversed()
    }

    private fun publish(
        turnId: String,
        text: String,
        reasoning: String,
        calls: List<RumiStream.Call>,
        failure: String?,
    ) {
        val parts = mutableListOf<RumiPart>()
        if (reasoning.isNotBlank()) parts += RumiPart.Reasoning(reasoning)
        if (text.isNotBlank()) parts += RumiPart.Text(text)
        calls.forEach { call ->
            parts += RumiPart.Tool(
                callId = call.id,
                name = call.name,
                arguments = call.arguments,
                running = true,
                // The sub-agent, if this call started one. It is asked of the
                // tools, because it appears at the moment of the start, not in
                // what the model wrote.
                agentId = tools.agentIdForCall(call.id),
            )
        }
        replaceTurn(turnId) { current ->
            // A picture part already produced for a tool of this turn survives a
            // re-publish: the transcript must not lose what it already showed.
            val kept = current.parts.filterIsInstance<RumiPart.Tool>().filter { it.result != null }
            val merged = parts.map { part ->
                if (part is RumiPart.Tool) {
                    kept.firstOrNull { it.callId == part.callId } ?: part
                } else {
                    part
                }
            }
            current.copy(parts = merged, error = failure)
        }
        autosave()
    }

    private fun updateToolPart(turnId: String, callId: String, outcome: RumiToolOutcome) {
        replaceTurn(turnId) { current ->
            // Matched by id when the provider sent one; a provider that sends
            // none leaves the parts anonymous, so the first still-running part
            // takes the result instead of nothing being filled in at all.
            var filled = false
            current.copy(
                parts = current.parts.map { part ->
                    if (!filled && part is RumiPart.Tool && (part.callId == callId || part.running)) {
                        filled = true
                        part.copy(
                            result = outcome.text,
                            ok = outcome.ok,
                            summary = outcome.summary,
                            imagePath = outcome.path,
                            image = outcome.png,
                            running = false,
                            agentId = outcome.agentId,
                        )
                    } else {
                        part
                    }
                },
            )
        }
    }

    private fun replaceTurn(turnId: String, edit: (RumiTurnUi) -> RumiTurnUi) {
        _turns.value = _turns.value.map { if (it.id == turnId) edit(it) else it }
    }

    private fun errorTurn(reason: String): RumiTurnUi =
        RumiTurnUi(id = newId(), role = "assistant", error = reason)

    /** Where a model's family expects to be called. */
    private fun endpoint(base: String, protocol: RumiProtocol): String = when (protocol) {
        RumiProtocol.Chat -> "$base/chat/completions"
        RumiProtocol.Responses -> "$base/responses"
        RumiProtocol.Messages -> "$base/messages"
    }

    private fun outputBudget(model: RumiModel): Int {
        // The Messages protocol requires a bound; a catalogue that reports a
        // large output limit is still capped so one turn cannot run away.
        val declared = if (model.contextTokens > 0) model.contextTokens / 4 else 0
        return declared.coerceIn(1_024, 32_768)
    }

    private fun systemPrompt(model: RumiModel): String {
        val project = host.project
        return """
            You are Rumi, the assistant built into Rumo, a video editor. You work
            only through the tools you are given: nothing you write changes the
            project unless a tool does it.

            The project right now: "${project.name}", ${project.layerCount}
            layer(s), ${project.durationMs}ms long, playhead at
            ${project.playheadMs}ms. The canvas is
            ${project.canvasWidth}x${project.canvasHeight} pixels — the frame
            everything is composed in, and the project's own size, not a constant of
            the app — and the preview scales it to the screen. Its background is
            #${"%08X".format(project.backgroundArgb.toInt())}. You are talking to
            ${model.name}.

            How to work:
            - Start by calling project_state when you need to know what is there,
              and straight after any change you did not make yourself.
            - Look at the result. snapshot renders the current frame and shows it
              to you; filmstrip renders a range as a contact sheet and can also
              write a short clip. Use them instead of assuming an edit worked.
            - effects(action=list) gives the whole effect catalogue with every
              parameter's range. compare_effects renders the same frame with two
              candidate effects and reports how much each changes the picture, so
              you can choose on evidence.
            - You can write your own WGSL effect with define_effect. The engine
              parses and validates the module first and matches `struct Params`
              field by field against the parameters you declare, then renders a
              probe frame to confirm the effect actually changes the picture. Only
              then can it be used, by the id you gave it, through effects(add).
            - Keep answers short and concrete. Name the layers you touched. If a
              tool refuses, say what it refused and why rather than retrying the
              same call.
            - The project's material lives in its own folder, `Download/<project
              name>/`, which exists from the moment the project does: that is
              where the user drops pictures, video and sound. media(action=list)
              lists what is there and media(action=add) puts one of them on the
              timeline. When the app has not been allowed to read the user's own
              files, that call asks for the permission itself — the dialog is the
              user's to answer and the conversation continues once they do. Never
              tell the user to grant a permission by hand, and never guess a file
              name: if the list is empty while access is missing, say so and wait.
            - You can build a project from nothing: `project` with action=new starts
              an empty one, and the layers it comes with are the app's defaults,
              which you can reshape or remove. If the open project has anything to
              lose it is saved first, so starting a new one never destroys work —
              the answer says which file the old one went to.
            - `remove_layer` deletes a layer, effects and keyframes with it. The
              user can undo that themselves, but you cannot undo it for them, so
              remove only what was asked for.
            - Every layer has a startMs: it is in frame from startMs up to, but not
              including, startMs + durationMs, and the project's length is the
              largest of those two added together. A layer whose startMs is past
              the playhead is not drawn at all, so setting a long duration and
              leaving startMs at 0 is not the same as starting it later. Every tool
              that adds a layer takes startMs, and update_layer moves an existing
              one. project_state reports both numbers for every layer.
            - `keys` animates one property of a layer, not just rotation any more.
              Its property selector takes rotation (the default), scale,
              position_x, position_y or alpha; use degrees for rotation and value
              for the rest. A track with no keys is not animated and the layer's own
              value applies everywhere, so keying a property to a constant is the
              same as leaving it alone.
            - Nothing you do is stored until it is saved. Call project with
              action=save when the work the user asked for is done, so it appears
              in their project list and can be opened; say the file name in your
              answer. Until then it exists only in the open editor session.
            - Do not claim something looks a particular way unless a snapshot or
              filmstrip showed it to you.
        """.trimIndent() + briefSection()
    }

    /**
     * The sub-agent's role in the system prompt.
     *
     * It is appended to the shared text rather than replacing it: a sub-agent works with the
     * same project and the same tools, and it needs the same rules about them.
     * It is said separately that it is not alone: without that a sub-agent either tries
     * to talk to a user it does not see, or decides the project is
     * its own.
     */
    private fun briefSection(): String {
        if (brief.isEmpty()) return ""
        return """

            ---

            You are a sub-agent. Another assistant started you to do one job and is
            waiting for your answer.

            $brief

            Answer with what you found or what you did, and nothing else. Nobody
            reads your working notes, and the assistant that started you cannot see
            the project changing while you work: it only gets your final text. Do
            not address the user — they are not in this conversation — and do not
            ask questions: if something is missing, say what is missing and stop.
        """.trimIndent()
    }

    private fun newId(): String = UUID.randomUUID().toString()

    /** What to write in the notification about the current state. */
    private fun workDetail(status: RumiStatus): String = when (status) {
        RumiStatus.Waiting -> "waiting for the provider"
        RumiStatus.Streaming -> "writing"
        is RumiStatus.Running -> "using ${status.tool}"
        is RumiStatus.Failed -> "failed"
        RumiStatus.Idle -> "finishing"
    }

    private fun describe(t: Throwable): String =
        t.message?.let { "$it" } ?: (t::class.simpleName ?: "unknown failure")

    private data class Answer(
        val turnId: String,
        val text: String,
        val toolCalls: List<RumiToolCall>,
        val failed: String?,
    )

    private companion object {
        const val TAG = "rumi"
        const val PUSH_INTERVAL_MS = 60L

        /** How often the turn is saved while it runs. See [autosave]. */
        const val AUTOSAVE_MS = 1_500L
    }
}
