// SPDX-License-Identifier: GPL-3.0-or-later
package com.kerneldroid.aiengines.rumi

import android.content.Context
import com.kerneldroid.aiengines.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** One conversation, as the history list needs it. */
data class RumiChatSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val turns: Int,
    val modelId: String,
) {
    /** Which history group this belongs to, in the order the list shows them. */
    fun group(now: Long = System.currentTimeMillis()): String {
        val startOfToday = now - (now % DAY_MS)
        return when {
            updatedAt >= startOfToday -> GROUP_TODAY
            updatedAt >= startOfToday - DAY_MS -> GROUP_YESTERDAY
            else -> GROUP_OLDER
        }
    }

    companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val GROUP_TODAY = "Today"
        const val GROUP_YESTERDAY = "Yesterday"
        const val GROUP_OLDER = "Older"

        /** The three groups, in order. Ported from the desktop client, which
         * groups a session list exactly this way. */
        val GROUPS = listOf(GROUP_TODAY, GROUP_YESTERDAY, GROUP_OLDER)
    }
}

/**
 * A stored conversation: the transcript the screen renders, and the provider
 * history the next message continues from.
 *
 * Both are kept because they are not the same thing. The transcript carries what
 * the screen shows — tool arguments, results, notes, errors — while the provider
 * only ever sees roles, text and tool calls. Rebuilding one from the other would
 * lose either the rendering or the model's memory of what it did.
 */
data class RumiChat(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val modelId: String,
    val turns: List<RumiTurnUi>,
    val messages: List<RumiMessage>,
)

/**
 * Conversations on disk, one file each.
 *
 * One file per chat rather than one index: a new chat is a new file, deleting one
 * is unlinking one, and there is no index to keep in step with reality — the same
 * reason `ProjectStore` lists a directory instead of maintaining a catalogue.
 * Nothing here is written on the main thread.
 *
 * Pictures are not stored. A snapshot's bytes are a hundred kilobytes each and
 * the model only ever sees a given picture once; what is kept is the path the
 * picture was saved to, so the transcript still says where it went.
 */
object RumiChatStore {
    private const val TAG = "rumi"
    private const val DIR = "rumi/chats"
    private const val MAX_CHATS = 200
    private const val TITLE_LEN = 60

    /** A title from the first thing the user said. */
    fun titleFrom(text: String): String {
        val line = text.trim().lineSequence().firstOrNull().orEmpty()
            .replace(Regex("\\s+"), " ")
            .trim()
        return when {
            line.isEmpty() -> "New chat"
            line.length <= TITLE_LEN -> line
            else -> line.take(TITLE_LEN).trimEnd() + "…"
        }
    }

    private fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    private fun file(context: Context, id: String): File {
        // An id from a file name is untrusted input; keep only the leaf.
        val leaf = id.substringAfterLast('/').substringAfterLast('\\').ifEmpty { "unknown" }
        return File(dir(context), "$leaf.json")
    }

    suspend fun list(context: Context): List<RumiChatSummary> = withContext(Dispatchers.IO) {
        dir(context).listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.mapNotNull { readSummary(it) }
            ?.sortedByDescending { it.updatedAt }
            ?.take(MAX_CHATS)
            ?: emptyList()
    }

    private fun readSummary(file: File): RumiChatSummary? = runCatching {
        val root = JSONObject(file.readText())
        val id = root.optString("id", "").ifEmpty { file.name.removeSuffix(".json") }
        RumiChatSummary(
            id = id,
            title = root.optString("title", "").ifEmpty { "Untitled" },
            updatedAt = root.optLong("updatedAt", file.lastModified()),
            turns = root.optJSONArray("turns")?.length() ?: 0,
            modelId = root.optString("modelId", ""),
        )
    }.getOrElse {
        AppLog.warn(TAG, "chat ${file.name} unreadable: ${it.message}")
        null
    }

    suspend fun save(context: Context, chat: RumiChat): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            // Written beside the target and renamed over it: a save cut short —
            // the app going away mid-write — would otherwise leave a truncated
            // JSON, and a conversation that cannot be parsed is a conversation
            // that is gone. The temporary file does not end in `.json`, so the
            // listing never picks one up.
            val target = file(context, chat.id)
            val temp = File(target.parentFile, target.name + ".tmp")
            temp.writeText(encode(chat).toString())
            temp.renameTo(target)
            true
        }.getOrElse {
            AppLog.error(TAG, "chat save ${chat.id} failed: ${it.message}")
            false
        }
    }

    suspend fun load(context: Context, id: String): RumiChat? = withContext(Dispatchers.IO) {
        val f = file(context, id)
        if (!f.isFile) {
            AppLog.warn(TAG, "chat $id: no such file")
            return@withContext null
        }
        runCatching { decode(JSONObject(f.readText())) }.getOrElse {
            AppLog.error(TAG, "chat load $id failed: ${it.message}")
            null
        }
    }

    suspend fun delete(context: Context, id: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { file(context, id).delete() }.getOrDefault(false)
    }

    fun newId(): String = UUID.randomUUID().toString()

    // --- Sub-agent conversations ---

    /**
     * A sub-agent on disk: its conversation and the role it worked under.
     *
     * The role sits beside the conversation, not inside it: `RumiChat` is about
     * the conversation, and knowing which tools it was conducted with does not
     * belong to the conversation. A restored sub-agent without a role could not
     * continue its work: the roles have different tool sets, and it cannot be
     * restored as "just some agent".
     */
    data class StoredAgent(val chat: RumiChat, val role: String)

    /**
     * The sub-agent folder of one conversation.
     *
     * Nested, rather than a shared directory with an "owner" field: deleting a
     * conversation takes its sub-agents with it, instead of leaving orphans that
     * nobody can show.
     */
    private fun agentDir(context: Context, chatId: String): File {
        val leaf = chatId.substringAfterLast('/').substringAfterLast('\\').ifEmpty { "unknown" }
        return File(dir(context), "agents/$leaf").apply { mkdirs() }
    }

    suspend fun saveAgent(
        context: Context,
        chatId: String,
        chat: RumiChat,
        role: String,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val target = File(agentDir(context, chatId), "${chat.id}.json")
            val temp = File(target.parentFile, target.name + ".tmp")
            temp.writeText(encode(chat).put("agentRole", role).toString())
            temp.renameTo(target)
            true
        }.getOrElse {
            AppLog.error(TAG, "agent save ${chat.id} failed: ${it.message}")
            false
        }
    }

    suspend fun loadAgents(context: Context, chatId: String): List<StoredAgent> =
        withContext(Dispatchers.IO) {
            val files = agentDir(context, chatId)
                .listFiles { f -> f.isFile && f.name.endsWith(".json") }
                ?: return@withContext emptyList()
            files.mapNotNull { f ->
                runCatching {
                    val root = JSONObject(f.readText())
                    val chat = decode(root)
                    // Without a role the sub-agent cannot be restored: there is
                    // no telling what it may work with. A file without one is
                    // skipped, not turned into "worker" — a guess here would give
                    // a sub-agent permissions it never had.
                    val role = root.optString("agentRole", "")
                    if (role.isEmpty()) null else StoredAgent(chat, role)
                }.getOrElse {
                    AppLog.error(TAG, "agent load ${f.name} failed: ${it.message}")
                    null
                }
            }.sortedBy { it.chat.createdAt }
        }

    suspend fun deleteAgents(context: Context, chatId: String) = withContext(Dispatchers.IO) {
        agentDir(context, chatId).deleteRecursively()
        Unit
    }

    // --- JSON ---

    private fun encode(chat: RumiChat): JSONObject {
        val turns = JSONArray()
        chat.turns.forEach { turns.put(encodeTurn(it)) }
        val messages = JSONArray()
        chat.messages.forEach { messages.put(encodeMessage(it)) }
        return JSONObject()
            .put("id", chat.id)
            .put("title", chat.title)
            .put("createdAt", chat.createdAt)
            .put("updatedAt", chat.updatedAt)
            .put("modelId", chat.modelId)
            .put("turns", turns)
            .put("messages", messages)
    }

    private fun decode(root: JSONObject): RumiChat {
        val turns = ArrayList<RumiTurnUi>()
        root.optJSONArray("turns")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { turns += decodeTurn(it) }
            }
        }
        val messages = ArrayList<RumiMessage>()
        root.optJSONArray("messages")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { messages += decodeMessage(it) }
            }
        }
        return RumiChat(
            id = root.optString("id", ""),
            title = root.optString("title", "Untitled"),
            createdAt = root.optLong("createdAt", 0L),
            updatedAt = root.optLong("updatedAt", 0L),
            modelId = root.optString("modelId", ""),
            turns = turns,
            messages = messages,
        )
    }

    private fun encodeTurn(turn: RumiTurnUi): JSONObject {
        val parts = JSONArray()
        turn.parts.forEach { part ->
            parts.put(
                when (part) {
                    is RumiPart.Text -> JSONObject().put("p", "text").put("text", part.text)
                    is RumiPart.Reasoning ->
                        JSONObject().put("p", "reasoning").put("text", part.text)
                    is RumiPart.Note -> JSONObject().put("p", "note").put("text", part.text)
                    is RumiPart.Tool -> JSONObject()
                        .put("p", "tool")
                        .put("callId", part.callId)
                        .put("name", part.name)
                        .put("args", part.arguments)
                        .put("ok", part.ok)
                        // Absent rather than present-and-null: a stored JSON null
                        // reads back as the *text* "null" through `optString` on
                        // Android, which is how half a reloaded transcript turned
                        // into the word "null". Leaving the key out makes the two
                        // cases impossible to confuse.
                        .apply { part.result?.let { put("result", it) } }
                        .apply { part.summary?.let { put("summary", it) } }
                        .apply { part.imagePath?.let { put("imagePath", it) } }
                        // The sub-agent id survives a restart: the card in a saved
                        // conversation stays tappable, and its conversation opens
                        // exactly like a fresh one.
                        .apply { part.agentId?.let { put("agentId", it) } }
                },
            )
        }
        return JSONObject()
            .put("id", turn.id)
            .put("role", turn.role)
            .put("parts", parts)
            .apply { turn.error?.let { put("error", it) } }
    }

    private fun decodeTurn(o: JSONObject): RumiTurnUi {
        val parts = ArrayList<RumiPart>()
        o.optJSONArray("parts")?.let { arr ->
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                when (p.optString("p", "")) {
                    "text" -> parts += RumiPart.Text(p.optString("text", ""))
                    "reasoning" -> parts += RumiPart.Reasoning(p.optString("text", ""))
                    "note" -> parts += RumiPart.Note(p.optString("text", ""))
                    "tool" -> parts += RumiPart.Tool(
                        callId = p.optString("callId", ""),
                        name = p.optString("name", ""),
                        arguments = p.optString("args", ""),
                        result = p.optText("result"),
                        ok = p.optBoolean("ok", true),
                        summary = p.optText("summary"),
                        imagePath = p.optText("imagePath"),
                        running = false,
                        agentId = p.optText("agentId"),
                    )
                }
            }
        }
        return RumiTurnUi(
            id = o.optString("id", ""),
            role = o.optString("role", "assistant"),
            parts = parts,
            error = o.optText("error"),
        )
    }

    private fun encodeMessage(message: RumiMessage): JSONObject {
        val calls = JSONArray()
        message.toolCalls.forEach { call ->
            calls.put(
                JSONObject()
                    .put("id", call.id)
                    .put("name", call.name)
                    .put("arguments", call.arguments),
            )
        }
        return JSONObject()
            .put("role", message.role)
            .put("toolCalls", calls)
            .apply { message.text?.let { put("text", it) } }
            .apply { message.toolCallId?.let { put("toolCallId", it) } }
            .apply { message.toolName?.let { put("toolName", it) } }
    }

    private fun decodeMessage(o: JSONObject): RumiMessage {
        val calls = ArrayList<RumiToolCall>()
        o.optJSONArray("toolCalls")?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                calls += RumiToolCall(
                    id = c.optString("id", ""),
                    name = c.optString("name", ""),
                    arguments = c.optString("arguments", "{}"),
                )
            }
        }
        return RumiMessage(
            role = o.optString("role", "user"),
            text = o.optText("text"),
            toolCalls = calls,
            toolCallId = o.optText("toolCallId"),
            toolName = o.optText("toolName"),
        )
    }

}
