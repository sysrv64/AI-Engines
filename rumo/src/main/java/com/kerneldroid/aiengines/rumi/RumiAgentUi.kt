// SPDX-License-Identifier: GPL-3.0-or-later
package com.kerneldroid.aiengines.rumi

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kerneldroid.aiengines.R
import org.json.JSONObject

/**
 * This conversation's sub-agents, available to the cards in the transcript.
 *
 * A CompositionLocal, not a parameter: the card sits five levels below the list, and
 * threading the registry through `TurnRow`, `AssistantTurn`, `ProcessBlock` and `PartRow`
 * would mean adding four parameters that none of them need. The local says exactly
 * what is the case: "this transcript has sub-agent cards".
 *
 * A regular one, not `static`: the list changes while work is running, and the cards
 * have to see that.
 */
internal val LocalAgents = compositionLocalOf<List<RumiSubagent>> { emptyList() }

/** Open a sub-agent's conversation by id. Stable: it is an action, not data. */
internal val LocalOpenAgent = staticCompositionLocalOf<(String) -> Unit> { {} }

/**
 * A sub-agent card.
 *
 * It answers three questions at once without requiring a tap: what the work is, how
 * it ended, and whether there is anything to look at. A tap opens its own
 * conversation — steps, calls and everything it said, not the last line of the
 * report.
 *
 * While it is running, the card is alive: an indicator instead of a checkmark. When
 * it has finished — the card stays, because it is part of the history: the work
 * happened and has to be remembered. The conversation is already closed by then and
 * cannot be opened: sub-agents live in the conversation's memory, not on disk. Lying
 * with a tap that does nothing is worse than saying there is nothing to look at.
 */
@Composable
internal fun AgentCard(part: RumiPart.Tool) {
    val open = LocalOpenAgent.current
    val agents = LocalAgents.current
    val agent = remember(part.agentId, agents) { agents.firstOrNull { it.id == part.agentId } }
    val args = remember(part.arguments) {
        runCatching { JSONObject(part.arguments) }.getOrNull()
    }

    val title = agent?.description
        ?: args?.optText("description")?.takeIf { it.isNotBlank() }
        ?: part.agentId.orEmpty()
    val role = agent?.role?.id
        ?: args?.optText("agent_name")?.takeIf { it.isNotBlank() }
        ?: RumiAgentRole.WORKER.id

    val face = agent?.let { rememberAgentFace(it) } ?: AgentFace(
        label = if (part.running) {
            stringResource(R.string.rumi_agent_working)
        } else {
            stringResource(R.string.rumi_agent_closed)
        },
        running = part.running,
        failed = !part.ok,
    )

    val openable = agent != null
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .then(
                    if (openable) {
                        Modifier.clickable { part.agentId?.let(open) }
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(
                        when {
                            face.failed -> MaterialTheme.colorScheme.errorContainer
                            else -> MaterialTheme.colorScheme.secondaryContainer
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = agentIcon(role),
                    contentDescription = null,
                    tint = when {
                        face.failed -> MaterialTheme.colorScheme.onErrorContainer
                        else -> MaterialTheme.colorScheme.onSecondaryContainer
                    },
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "$role · ${face.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (face.running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = if (face.failed) {
                        Icons.Rounded.ErrorOutline
                    } else {
                        Icons.Rounded.CheckCircle
                    },
                    contentDescription = null,
                    tint = if (face.failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (!openable && part.agentId != null) {
            Text(
                text = stringResource(R.string.rumi_agent_gone),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 14.dp, top = 4.dp),
            )
        }
    }
}

/** What is visible about a sub-agent from the outside right now. */
private data class AgentFace(val label: String, val running: Boolean, val failed: Boolean)

@Composable
private fun rememberAgentFace(agent: RumiSubagent): AgentFace {
    val status by agent.status.collectAsState()
    // The context is part of the key: a status word has to be recomputed when the
    // interface language changes, and the cached label would otherwise outlive it.
    val context = LocalContext.current
    return remember(status, context) {
        when (val s = status) {
            is RumiStatus.Running ->
                AgentFace(context.getString(R.string.rumi_agent_using, s.tool), true, false)
            RumiStatus.Streaming, RumiStatus.Waiting ->
                AgentFace(context.getString(R.string.rumi_agent_thinking), true, false)
            is RumiStatus.Failed ->
                AgentFace(context.getString(R.string.rumi_agent_failed), false, true)
            RumiStatus.Idle -> AgentFace(
                label = if (agent.busy) {
                    context.getString(R.string.rumi_agent_working)
                } else {
                    context.getString(R.string.rumi_agent_finished)
                },
                running = agent.busy,
                failed = false,
            )
        }
    }
}

private fun agentIcon(role: String): ImageVector = when (role) {
    RumiAgentRole.EXPLORE.id -> Icons.Rounded.Search
    RumiAgentRole.VERIFIER.id -> Icons.Rounded.Verified
    else -> Icons.Rounded.Bolt
}

/**
 * A sub-agent's conversation in full.
 *
 * This is a second chat, not a slide-out panel: a sub-agent does work that does not
 * fit in a row, and it has to be viewed the same way as your own conversation. Hence
 * the exit too — the same "back" gesture, not a close cross in the corner.
 *
 * There is deliberately no input field here. The sub-agent is talked to by whoever
 * launched it; what the user typed would arrive as a message from the parent and
 * would mix two roles in one conversation. Watching and stopping is what the user
 * can do with someone else's work.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AgentScreen(agent: RumiSubagent, onClose: () -> Unit) {
    val turns by agent.turns.collectAsState()
    val status by agent.status.collectAsState()
    val context = LocalContext.current
    val face = remember(status, context) {
        when (val s = status) {
            is RumiStatus.Running ->
                context.getString(R.string.rumi_agent_using, s.tool)
            RumiStatus.Streaming -> context.getString(R.string.rumi_agent_writing)
            RumiStatus.Waiting -> context.getString(R.string.rumi_agent_starting)
            is RumiStatus.Failed -> context.getString(R.string.rumi_agent_failed)
            RumiStatus.Idle -> if (agent.busy) {
                context.getString(R.string.rumi_agent_working)
            } else {
                context.getString(R.string.rumi_agent_finished)
            }
        }
    }
    val listState = rememberLazyListState()
    // We follow the tail as in the main transcript: you should watch what is
    // happening, not what happened.
    LaunchedEffect(turns.size, turns.lastOrNull()?.parts?.size) {
        if (turns.isNotEmpty()) listState.animateScrollToItem(turns.lastIndex)
    }

    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // The header row sits under the status bar.
                    //
                    // The main screen gets this from `Scaffold`, and there is none here:
                    // without the padding the conversation title lay right on the clock and
                    // the network icon. The padding is taken from the window, not as a number:
                    // it differs between devices with a notch and with an island.
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.rumi_agent_back),
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = agent.description,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${agent.role.id} · $face · ${agent.id}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                AnimatedVisibility(visible = agent.busy) {
                    FilledTonalButton(onClick = { agent.stop("stopped by the user") }) {
                        Icon(
                            imageVector = Icons.Rounded.PauseCircle,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.rumi_stop))
                    }
                }
            }
            if (turns.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.rumi_agent_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }
            LazyColumn(
                state = listState,
                // The system gesture bar at the bottom also belongs to the window, not to us:
                // without the padding the last line of the answer slides under it.
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.navigationBars),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = 40.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                itemsIndexed(items = turns, key = { _, t -> t.id }) { index, turn ->
                    TurnRow(turn, live = agent.busy && index == turns.lastIndex)
                }
            }
        }
    }
}
