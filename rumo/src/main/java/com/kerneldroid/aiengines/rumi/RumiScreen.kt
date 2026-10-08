// SPDX-License-Identifier: GPL-3.0-or-later
package com.kerneldroid.aiengines.rumi

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CompareArrows
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import com.kerneldroid.aiengines.R
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import com.kerneldroid.aiengines.ui.AiMenuAction
import com.kerneldroid.aiengines.ui.ProvideAiHoldDuration
import com.kerneldroid.aiengines.ui.AiMenuIcons
import com.kerneldroid.aiengines.ui.AiDockContentInset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Rumi: the assistant tab.
 *
 * The conversation is a view over [RumiSession]; the screen owns no model state
 * of its own, only what the user is typing and which sheet is open. Everything
 * that changes the project does so through the editor state the assistant was
 * handed, so the tab and the editor screen can never disagree about the
 * project — switching tabs is switching between the request and its result.
 *
 * The surface is deliberately quiet: tone-lifted containers, hairline
 * separators, small type, and motion that comes from the theme's motion scheme
 * rather than from local durations.
 */
@OptIn(ExperimentalMaterial3Api::class)
/**
 * How long after entering the tab the dock slides down.
 *
 * Two and a half seconds: the finger has just hit the panel, and an instant
 * disappearance would read as a miss. In that time it is visible that the
 * transition happened, and the panel leaves calmly.
 */
private const val DOCK_AUTO_HIDE_MS = 2_500L

@Composable
fun RumiScreen(
    /**
     * The owner of the conversation. The screen neither creates nor closes it:
     * `NavHost` destroys the screen on leaving the tab, and formerly that killed
     * the conversation along with it — the history vanished and the running turn
     * was cut off.
     */
    controller: RumiController,
    onOpenSettings: () -> Unit,
    /**
     * Whether the navigation dock is hidden. The state is owned by
     * `MainActivity`: the dock is drawn there, while this tab asks for the
     * hiding.
     */
    dockHidden: Boolean = false,
    onDockHidden: (Boolean) -> Unit = {},
) {

    // Read access to the shared storage is asked for on demand and never at
    // startup: the app needs it for exactly one thing — seeing the references the
    // user dropped into the project's folder — so the dialog belongs to the
    // moment the assistant asks for the listing. The tool raises it (see
    // RumiHost.requestMediaAccess); the answer comes back here, and when
    // access was just granted the conversation picks itself up again, because
    // making the user type "ok" after a dialog is the difference between an
    // assistant that fixes the folder and one that only says who should.
    //
    // The flags live in the host, not here: the tool sets them and the
    // screen comes and goes, so a rebuild of the screen must not mean "the
    // assistant has stopped waiting for an answer".
    val mediaAccessLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        controller.host.mediaAccessAnswered(result.values.any { it })
    }
    LaunchedEffect(controller.host.wantMediaAccess) {
        if (!controller.host.consumeMediaAccessRequest()) return@LaunchedEffect
        val missing = controller.host.missingMediaPermissions()
        if (missing.isNotEmpty()) mediaAccessLauncher.launch(missing.toTypedArray())
    }

    val session = controller.session
    LaunchedEffect(session) {
        session.refreshModels()
        // The stored conversations are a separate store from the model catalogue,
        // so they are read on entry too: a chat from an earlier run is there to
        // reopen without the user having to ask for it.
        session.refreshChats()
    }

    // The assistant asked to see the folder and the user answered the dialog, so
    // the turn carries on by itself: the text goes to the model, a note goes on
    // the transcript, and the user does not have to type "ok" to unstick a
    // question they just answered.
    LaunchedEffect(controller.host.resumeAfterMediaAccess) {
        if (!controller.host.consumeResume()) return@LaunchedEffect
        session.resume(
            "Read-media access is now granted. Call media(action=list) and carry on with " +
                "what you were doing.",
        )
    }

    val turns by session.turns.collectAsState()
    val status by session.status.collectAsState()
    val catalogue by session.catalogue.collectAsState()
    val loadingModels by session.loadingModels.collectAsState()
    val chats by session.chats.collectAsState()
    val currentChatId by session.chatId.collectAsState()
    val chatTitle by session.chatTitle.collectAsState()
    val settings by RumiSettings.state.collectAsState()
    val agents by controller.agents.tasks.collectAsState()
    // Read through the collected flows above, so the screen redraws when either
    // the catalogue or the chosen model id changes.
    val model = session.model

    var composer by remember { mutableStateOf("") }
    var showModels by remember { mutableStateOf(false) }
    // The open sub-agent conversation. Lives in the screen, not the controller:
    // leaving the tab returns to your own conversation, and that is the right
    // behaviour — a sub-agent is not the place a user would leave themselves,
    // coming back an hour later.
    var openAgentId by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    // The drawer opens and closes on its own animation; a scope is all the screen
    // needs to drive it, and nothing here re-implements that motion.
    val drawerScope = rememberCoroutineScope()
    // A conversation that is both unnamed and empty is the state a new chat would
    // produce, so the action is offered only when it would actually change
    // something. The title is how the session spells that state.
    val canStartNewChat = turns.isNotEmpty() || chatTitle != "New chat"

    val busy = status is RumiStatus.Waiting ||
        status is RumiStatus.Streaming ||
        status is RumiStatus.Running
    val statusLabel = when (val current = status) {
        is RumiStatus.Running -> stringResource(R.string.rumi_status_running, current.tool)
        RumiStatus.Waiting -> stringResource(R.string.rumi_status_waiting)
        else -> null
    }

    // The dock leaves on its own a couple of seconds after entering the tab.
    //
    // A conversation is reading and typing, and the tab bar here only takes height
    // away from the input field: leaving the tab can also be done with a gesture.
    // The hiding is delayed, not instant, because right after the transition the
    // dock is still needed — the finger has just hit it, and an instant
    // disappearance reads as a miss.
    //
    // Bringing it back is one "back" gesture; a second press leaves the tab, like
    // an ordinary back. This is not an interception: the handler is enabled
    // exactly when the dock is hidden, so the first press breaks nothing and only
    // brings the panel back.
    LaunchedEffect(Unit) {
        delay(DOCK_AUTO_HIDE_MS)
        onDockHidden(true)
    }
    // "Back" closes the open sub-agent conversation rather than bringing the dock
    // back: a screen over a screen reads as nesting, and the gesture must exit one
    // level, not two. So the two handlers do not compete — they are enabled in
    // turn.
    val openAgent = agents.firstOrNull { it.id == openAgentId }
    BackHandler(enabled = openAgent != null) { openAgentId = null }
    BackHandler(enabled = dockHidden && openAgent == null) { onDockHidden(false) }

    val listState = rememberLazyListState()
    // True while the newest turn is in view. Used before new content arrives, so
    // a user who scrolled up is never yanked back to the bottom.
    val atBottom by remember { derivedStateOf { !listState.canScrollForward } }
    // Streaming keeps the same turn, so the turn count alone would not follow
    // text that is still arriving; this is a cheap stand-in for "the last turn
    // grew since the last frame".
    val growth = turns.lastOrNull()?.let { turn ->
        turn.parts.sumOf { part -> partGrowthKey(part) }
    } ?: 0
    LaunchedEffect(turns.size, growth, statusLabel) {
        val follow = atBottom
        // Let the frame that added the content lay out first, then scroll; the
        // decision to follow was already taken from the pre-growth viewport.
        withFrameNanos { }
        val target = listState.layoutInfo.totalItemsCount - 1
        if (follow && target >= 0) {
            listState.animateScrollToItem(target)
        }
    }

    val stateWord = when {
        !settings.configured -> stringResource(R.string.rumi_state_not_signed_in)
        model == null -> null // the name line already says there is no model
        !model.tools -> stringResource(R.string.rumi_state_no_tools)
        else -> stringResource(R.string.rumi_state_ready)
    }
    val modelLabel = model?.name ?: stringResource(R.string.rumi_state_no_model)
    val subtitle = if (stateWord == null) modelLabel else "$modelLabel · $stateWord"

    // The history drawer wraps the whole screen, top bar included: the chat list
    // is a way to leave the conversation, so it should be reachable from
    // anywhere on it, and the bar is part of what it slides over.
    CompositionLocalProvider(
        LocalAgents provides agents,
        LocalOpenAgent provides { id -> openAgentId = id },
    ) {
        // The sub-agent conversation is drawn over, not instead: the list under it
        // stays composed, and going back does not reset the scroll to the start.
        Box(modifier = Modifier.fillMaxSize()) {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet {
                        ChatHistoryDrawer(
                            chats = chats,
                            currentId = currentChatId,
                            canStartNew = canStartNewChat,
                            onNewChat = {
                                session.newChat()
                                drawerScope.launch { drawerState.close() }
                            },
                            onOpen = { id ->
                                session.openChat(id)
                                drawerScope.launch { drawerState.close() }
                            },
                            onDelete = { id -> session.deleteChat(id) },
                            onRename = { id, title -> session.renameChat(id, title) },
                        )
                    }
                },
            ) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            navigationIcon = {
                                IconButton(onClick = { drawerScope.launch { drawerState.open() } }) {
                                    Icon(
                                        Icons.Rounded.Menu,
                                        contentDescription = stringResource(R.string.rumi_conversations),
                                    )
                                }
                            },
                            title = {
                                Column {
                                    Text(
                                        // The bar takes the conversation's name once it has
                                        // one; until then it is simply the assistant.
                                        text = if (chatTitle == "New chat") "Rumi" else chatTitle,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            },
                            actions = {
                                IconButton(onClick = { showModels = true }) {
                                    Icon(
                                        Icons.Rounded.AutoAwesome,
                                        contentDescription = stringResource(R.string.rumi_choose_model),
                                    )
                                }
                                Box {
                                    IconButton(onClick = { menuOpen = true }) {
                                        Icon(
                                            Icons.Rounded.MoreVert,
                                            contentDescription = stringResource(R.string.rumi_more),
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = menuOpen,
                                        onDismissRequest = { menuOpen = false },
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.rumi_open_in_editor)) },
                                            onClick = {
                                                menuOpen = false
                                                // keep = true: the project in memory is the
                                                // only copy of the assistant's work.
                                                controller.openEditor()
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.rumi_new_chat)) },
                                            onClick = {
                                                menuOpen = false
                                                session.newChat()
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.rumi_refresh_models)) },
                                            onClick = {
                                                menuOpen = false
                                                session.refreshModels(force = true)
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    if (settings.configured) {
                                                        stringResource(R.string.rumi_settings)
                                                    } else {
                                                        stringResource(R.string.rumi_sign_in)
                                                    },
                                                )
                                            },
                                            onClick = {
                                                menuOpen = false
                                                onOpenSettings()
                                            },
                                        )
                                    }
                                }
                            },
                        )
                    },
                    bottomBar = {
                        // The composer and the bar share one column, so the keyboard lifts
                        // both and the field is never typed into from behind the IME.
                        //
                        // The bottom allowance is for the navigation dock: it is now an
                        // overlay over the whole app (see `MainActivity`), and without the
                        // allowance it would cover the input field.
                        // The inset under the dock is animated together with its departure:
                        // when the panel has left, the input field drops to the bottom edge —
                        // closer to the finger and without an empty strip in place of the
                        // dock. The navigation-bar inset remains: the field must not go under
                        // the system-navigation gesture.
                        val navBarInset = WindowInsets.navigationBars
                            .asPaddingValues()
                            .calculateBottomPadding()
                        val bottomInset by animateDpAsState(
                            targetValue = if (dockHidden) navBarInset + 8.dp else AiDockContentInset,
                            animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                            label = "composerDockInset",
                        )
                        Column(modifier = Modifier.imePadding().padding(bottom = bottomInset)) {
                            if (settings.configured) {
                                ComposerDock(
                                    value = composer,
                                    onValueChange = { composer = it },
                                    meta = composerMeta(model, LocalContext.current),
                                    busy = busy,
                                    sendEnabled = composer.isNotBlank(),
                                    onSend = {
                                        val text = composer
                                        composer = ""
                                        session.send(text)
                                    },
                                    onStop = { session.stop() },
                                    onOpenModels = { showModels = true },
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                )
                            } else {
                                SignInCard(
                                    onOpenSettings = onOpenSettings,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                )
                            }
                        }
                    },
                ) { padding ->
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (turns.isEmpty()) {
                            item(key = "empty") {
                                EmptyTranscript(onPick = { session.send(it) })
                            }
                        } else {
                            itemsIndexed(items = turns, key = { _, t -> t.id }) { index, turn ->
                                // New content arrives with a short fade and a small upward
                                // slide; the same scheme drives both, so a turn landing
                                // while another is streaming does not read as a jump cut.
                                val motion = MaterialTheme.motionScheme
                                val visible = remember(turn.id) {
                                    MutableTransitionState(false).apply { targetState = true }
                                }
                                AnimatedVisibility(
                                    visibleState = visible,
                                    enter = fadeIn(animationSpec = motion.fastEffectsSpec<Float>()) +
                                        slideInVertically(
                                            animationSpec = motion.defaultSpatialSpec<IntOffset>(),
                                        ) { height -> height / 8 },
                                ) {
                                    // "Live" is only the last turn, while the status is busy:
                                    // earlier turns have already finished their process.
                                    TurnRow(turn, live = busy && index == turns.lastIndex)
                                }
                            }
                        }
                        if (statusLabel != null) {
                            item(key = "status") {
                                StatusRow(statusLabel)
                            }
                        }
                    }
                }
            }

            if (openAgent != null) {
                AgentScreen(agent = openAgent, onClose = { openAgentId = null })
            }
        }
    }

    if (showModels) {
        ModelSheet(
            catalogue = catalogue,
            loading = loadingModels,
            selectedId = model?.id,
            thinking = settings.thinking,
            onPickThinking = { RumiSettings.setThinking(it) },
            onPick = { RumiSettings.setModelId(it.id) },
            onRefresh = { session.refreshModels(force = true) },
            onDismiss = { showModels = false },
        )
    }
}

// --- Conversation history ---

/**
 * The stored conversations, as the drawer shows them.
 *
 * They are grouped the way a session list is normally grouped — today, then
 * yesterday, then everything else — so a long history reads as "recent" rather
 * than as one flat run of titles. The confirm dialog lives here rather than on
 * the screen because it only ever concerns this list.
 */
@Composable
private fun ChatHistoryDrawer(
    chats: List<RumiChatSummary>,
    currentId: String?,
    canStartNew: Boolean,
    onNewChat: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String, String) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<RumiChatSummary?>(null) }
    var pendingRename by remember { mutableStateOf<RumiChatSummary?>(null) }
    var renameText by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxHeight()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Rumi",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = canStartNew, onClick = onNewChat)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    // Dimming, rather than hiding, says the action exists but is
                    // not yet meaningful: a first message has to be sent first.
                    .alpha(if (canStartNew) 1f else 0.38f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = stringResource(R.string.rumi_new_chat),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        // The hold duration is named here, not in the gesture: `combinedClickable`
        // reads it from `ViewConfiguration`, and overriding it is exactly the way
        // to ask for three seconds instead of the system's half a second.
        ProvideAiHoldDuration {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            if (chats.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(R.string.rumi_chats_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                    )
                }
            }
            // The store hands the chats over newest first, and the groups keep
            // that order inside each of them; an empty group is not a heading.
            RumiChatSummary.GROUPS.forEach { group ->
                val members = chats.filter { it.group() == group }
                if (members.isEmpty()) return@forEach
                item(key = "group-$group") {
                    Text(
                        text = group,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(
                            start = 28.dp,
                            end = 28.dp,
                            top = 12.dp,
                            bottom = 4.dp,
                        ),
                    )
                }
                items(items = members, key = { it.id }) { chat ->
                    ChatRow(
                        chat = chat,
                        current = chat.id == currentId,
                        onOpen = { onOpen(chat.id) },
                        onRename = {
                            pendingRename = chat
                            renameText = chat.title
                        },
                        onDelete = { pendingDelete = chat },
                    )
                }
            }
        }
        }
    }

    pendingRename?.let { chat ->
        AlertDialog(
            onDismissRequest = { pendingRename = null },
            title = { Text(stringResource(R.string.rumi_rename_title)) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text(stringResource(R.string.rumi_field_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = pendingRename
                        pendingRename = null
                        val clean = renameText.trim()
                        if (target != null && clean.isNotEmpty()) onRename(target.id, clean)
                    },
                ) {
                    Text(stringResource(R.string.rumi_rename))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRename = null }) {
                    Text(stringResource(R.string.rumi_cancel))
                }
            },
        )
    }

    val doomed = pendingDelete
    if (doomed != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.rumi_delete_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = doomed.title, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = stringResource(R.string.rumi_delete_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        onDelete(doomed.id)
                    },
                ) {
                    Text(stringResource(R.string.rumi_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.rumi_cancel))
                }
            },
        )
    }
}

/**
 * One saved conversation: the name, what is in it, and what can be done with it.
 *
 * There are two actions — rename and delete — and both are hidden behind a hold.
 * The delete icon hung on every row and made the irreversible action the most
 * accessible in the list; a three-second hold makes it deliberate, and renaming
 * gets the place it did not have.
 *
 * An open conversation has a menu too: renaming it is ordinary, but deleting an
 * open one is not allowed, and the row stays honestly silent about it: deleting
 * what is on screen right now means leaving the user in a conversation that no
 * longer exists.
 */
@Composable
private fun ChatRow(
    chat: RumiChatSummary,
    current: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    // Only a chat from today is identified by its clock time; an older one needs
    // its date, since "14:05" alone would not say which day it was.
    //
    // The locale comes from the localised context, not `Locale.getDefault()`:
    // the phone's language is not necessarily the one chosen in the app, and a
    // date is the kind of text where the mismatch shows — "12 сент. 2026" next
    // to an English interface, or English month names next to a Russian one.
    val stampContext = LocalContext.current
    val stampLocale = stampContext.resources.configuration.locales[0]
    val stamp = remember(chat.updatedAt, stampLocale) {
        val pattern = if (chat.group() == RumiChatSummary.GROUP_TODAY) "HH:mm" else "d MMM yyyy"
        SimpleDateFormat(pattern, stampLocale).format(Date(chat.updatedAt))
    }
    var menuOpen by remember { mutableStateOf(false) }
    Box {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = onOpen,
                onLongClick = { menuOpen = true },
            )
            .padding(start = 28.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = chat.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (current) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // One resource per count, not a glued "message(s)": Russian needs a
                // third form, and "1 message" is not "messages" with a number in front.
                text = pluralStringResource(
                    R.plurals.rumi_chat_messages,
                    chat.turns,
                    chat.turns,
                    stamp,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (current) {
            // The open conversation is marked in the same tail of the row where
            // the delete icon used to sit: two states must not stand next to each
            // other.
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = stringResource(R.string.rumi_current_conversation),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
        DropdownMenuPopup(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 1)) {
                // Read before `buildList`: the list builder is not a composable scope,
                // so the menu labels cannot be resolved inside it.
                val renameLabel = stringResource(R.string.rumi_rename)
                val deleteLabel = stringResource(R.string.rumi_delete)
                val actions = buildList {
                    add(AiMenuAction(renameLabel, AiMenuIcons.Edit) {
                        menuOpen = false
                        onRename()
                    })
                    // We do not delete an open conversation: deleting what is on
                    // screen right now means leaving the user in a conversation
                    // that no longer exists.
                    if (!current) {
                        add(AiMenuAction(deleteLabel, AiMenuIcons.Delete) {
                            menuOpen = false
                            onDelete()
                        })
                    }
                }
                actions.forEachIndexed { index, action ->
                    DropdownMenuItem(
                        onClick = action.onClick,
                        text = { Text(action.label) },
                        leadingIcon = { Icon(action.icon, contentDescription = null) },
                        shape = MenuDefaults.itemShape(index, actions.size).shape,
                    )
                }
            }
        }
    }
}

// --- The transcript ---

@Composable
internal fun TurnRow(turn: RumiTurnUi, live: Boolean = false) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (turn.role == "user") {
            UserTurn(
                turn.parts.filterIsInstance<RumiPart.Text>()
                    .joinToString("\n") { it.text },
            )
        } else {
            AssistantTurn(turn, live)
        }
        if (turn.error != null) {
            Spacer(modifier = Modifier.size(8.dp))
            ErrorCard(turn.error)
        }
    }
}

/**
 * The assistant's answer: first **how** it was obtained, then what was obtained.
 *
 * Reasoning and tool calls used to go into the transcript alongside the answer:
 * on a long edit that is a dozen "called tool" lines, among which the answer
 * itself got lost. Here they are assembled into one block — collapsed once the
 * answer is there, expanded while it is still running. This is a device from
 * EchoFlow (`AssistantAnswerBody`): the process yields to the answer instead of
 * competing with it.
 */
@Composable
private fun AssistantTurn(turn: RumiTurnUi, live: Boolean) {
    // Sub-agents are pulled out of the process to the outside.
    //
    // The collapsed process block is the right place for steps it is enough to
    // know happened. A sub-agent is not such a step: it works for minutes, and
    // the user must see that the work is going on and have something to open it
    // with. Inside the collapsed block the card would be hidden exactly when it is
    // needed.
    val cards = turn.parts.filterIsInstance<RumiPart.Tool>().filter { it.agentId != null }
    val process = turn.parts.filter { part ->
        (part is RumiPart.Reasoning || part is RumiPart.Tool) && part !in cards
    }
    val answer = turn.parts.filter { it is RumiPart.Text || it is RumiPart.Note }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (process.isNotEmpty()) {
            ProcessBlock(
                turnId = turn.id,
                parts = process,
                // Expanded while there is no answer yet: at that time the process
                // is all that is happening. As soon as text starts, it goes into
                // the collapsed view, because it is the answer that must be read.
                live = live && answer.isEmpty(),
            )
        }
        cards.forEach { AgentCard(it) }
        answer.forEach { PartRow(it) }
    }
}

/**
 * One block for the whole process of a turn: a header with a summary and
 * expandable steps.
 *
 * The header answers the question "what is it doing and how much already"
 * without forcing you to read the steps: while running — an indicator and the
 * step count, afterwards — a tick and the count. The steps inside are the same as
 * were in the transcript ([PartRow]), so nothing is lost and the history looks the
 * same as a live turn.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProcessBlock(turnId: String, parts: List<RumiPart>, live: Boolean) {
    // The expansion follows the turn's state, but the user can override it:
    // `userChoice` remembers exactly their decision, not the current view,
    // otherwise automatic collapsing would wipe an open block.
    var userChoice by remember(turnId) { mutableStateOf<Boolean?>(null) }
    val expanded = userChoice ?: live
    val tools = parts.count { it is RumiPart.Tool }
    // The count is one resource of its own and the header wraps it: Russian does not
    // put the noun and the number together the way English does.
    val steps = pluralStringResource(R.plurals.rumi_process_steps, tools, tools)
    val label = when {
        live && tools == 0 -> stringResource(R.string.rumi_process_thinking)
        live -> stringResource(R.string.rumi_process_working, steps)
        tools == 0 -> stringResource(R.string.rumi_process_done)
        else -> steps
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { userChoice = !expanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (live) {
                    // The loading indicator from Expressive, not blinking text:
                    // blinking is motion plus reduced contrast on text, that is, the
                    // worst way to say "work is going on".
                    ContainedLoadingIndicator(modifier = Modifier.size(20.dp))
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Chevron(expanded = expanded)
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) +
                    fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec<Float>()),
                exit = shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) +
                    fadeOut(MaterialTheme.motionScheme.fastEffectsSpec<Float>()),
            ) {
                Column(
                    modifier = Modifier.padding(
                        start = 12.dp,
                        end = 12.dp,
                        bottom = 12.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    parts.forEach { part -> PartRow(part) }
                }
            }
        }
    }
}

@Composable
private fun PartRow(part: RumiPart) {
    when (part) {
        // Markup — only here.
        //
        // The model writes Markdown, and before this it arrived as is: headings
        // showed as hashes, lists as asterisks, tables as vertical bars. Reasoning,
        // notes and tool bodies stay ordinary `Text`: there is no markup in them,
        // and Markdown would only have hurt there — a `*` in a glob pattern or an
        // `_` in a file name would become italics.
        is RumiPart.Text -> MarkdownText(part.text)
        is RumiPart.Reasoning -> ReasoningBlock(part.text)
        is RumiPart.Tool -> ToolPartRow(part)
        is RumiPart.Note -> NoteLine(part.text)
    }
}

/** The user's own words: a bubble against the sender's edge of the column. */
@Composable
private fun UserTurn(text: String) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // The bubble wraps its text but never grows past 85% of the column, so a
        // long instruction still reads as a message rather than a page.
        val bubbleMax = maxWidth * 0.85f
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomEnd = 4.dp,
                    bottomStart = 16.dp,
                ),
                modifier = Modifier.widthIn(max = bubbleMax),
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** Assistant prose, rendered as the Markdown the model actually wrote. */
@Composable
private fun MarkdownText(text: String) {
    Markdown(
        content = text,
        // The library's default is `Modifier.fillMaxSize()`: inside a `LazyColumn`
        // that would mean every answer asks for the whole screen.
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Reasoning is kept, but folded away: it is the model's working, not the answer. */
@Composable
private fun ReasoningBlock(text: String) {
    var expanded by remember { mutableStateOf(false) }
    val motion = MaterialTheme.motionScheme
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.rumi_reasoning),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.size(4.dp))
            Chevron(expanded = expanded)
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(
                animationSpec = motion.defaultSpatialSpec<IntSize>(),
                expandFrom = Alignment.Top,
            ),
            exit = shrinkVertically(
                animationSpec = motion.defaultSpatialSpec<IntSize>(),
                shrinkTowards = Alignment.Top,
            ),
        ) {
            Text(
                text = text.trim(),
                style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One tool call: the compact row, and its arguments and answer when opened.
 *
 * The name pulses while the call is in flight, which is the only signal on this
 * screen that something is happening off the transcript's end.
 */
@Composable
private fun ToolPartRow(part: RumiPart.Tool) {
    var expanded by remember(part.callId) { mutableStateOf(false) }
    val motion = MaterialTheme.motionScheme
    val failed = !part.ok
    // An idle row must not hold an animation loop open, so the pulse only exists
    // while the call is actually running.
    val nameAlpha = if (part.running) rememberShimmerAlpha() else 1f
    val subtitle = remember(part.summary, part.arguments) { toolSubtitle(part) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = toolIcon(part.name),
                contentDescription = null,
                tint = if (failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = part.name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                modifier = Modifier.alpha(nameAlpha),
            )
            if (subtitle.isNotEmpty()) {
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
            if (part.running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Chevron(expanded = expanded)
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(
                animationSpec = motion.defaultSpatialSpec<IntSize>(),
                expandFrom = Alignment.Top,
            ),
            exit = shrinkVertically(
                animationSpec = motion.defaultSpatialSpec<IntSize>(),
                shrinkTowards = Alignment.Top,
            ),
        ) {
            ToolBody(part, failed)
        }
    }
}

@Composable
private fun ToolBody(part: RumiPart.Tool, failed: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (failed) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                },
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
        Text(
            text = prettyJson(part.arguments),
            style = mono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (part.result != null) {
            Spacer(modifier = Modifier.size(4.dp))
            Text(
                text = part.result,
                style = mono,
                color = if (failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
        ToolImage(part)
    }
}

/** A rendered frame or contact sheet, decoded once per byte array. */
@Composable
private fun ToolImage(part: RumiPart.Tool) {
    val bytes = part.image
    if (bytes != null) {
        val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = part.imagePath ?: stringResource(R.string.rumi_tool_image),
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp)),
            )
        }
    }
    if (part.imagePath != null) {
        Text(
            text = part.imagePath,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun NoteLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ErrorCard(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Rounded.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

/**
 * What is happening right now, while there is no turn yet.
 *
 * It used to be a line of blinking text. Blinking is motion plus reduced
 * contrast on text, and it was also the only "work is going on" signal; the
 * loading indicator says the same thing without touching readability, and it is
 * an Expressive component rather than a hand-made alpha.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun StatusRow(label: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContainedLoadingIndicator(modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyTranscript(onPick: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 40.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Rounded.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(32.dp),
        )
        Spacer(modifier = Modifier.size(12.dp))
        Text(
            text = stringResource(R.string.rumi_empty_transcript),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.size(20.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OpeningPrompts.forEach { promptRes ->
                val prompt = stringResource(promptRes)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onPick(prompt) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = prompt,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

// --- The composer ---

@Composable
private fun ComposerDock(
    value: String,
    onValueChange: (String) -> Unit,
    meta: String,
    busy: Boolean,
    sendEnabled: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onOpenModels: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The shape comes from the theme scale (`extraLarge`), not from a number of
    // its own: Expressive keeps large radii, and the input field is the first
    // place where that shows. A stadium (`CircleShape`) does not suit a multiline
    // field: a capsule has semicircular ends, and on five lines they read as a
    // mistake.
    val shape = MaterialTheme.shapes.extraLarge
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 6.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            maxLines = 6,
            // Enter inserts a newline and the button alone sends, so a
            // half-written instruction never leaves by accident.
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { innerTextField ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            text = stringResource(R.string.rumi_composer_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    innerTextField()
                }
            },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The model is named in the same place you type, and it can be
            // changed from there too. Both references keep the model choice in the
            // input field: a model not stated next to the text is a model that has
            // been forgotten.
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = CircleShape,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onOpenModels),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 190.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            // The motion specs are read here, not inside `transitionSpec`:
            // that lambda is not `@Composable`, so `MaterialTheme` is unavailable
            // from it.
            val actionIn = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec<Float>()) +
                scaleIn(initialScale = 0.7f)
            val actionOut = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec<Float>())
            FilledIconButton(
                onClick = { if (busy) onStop() else onSend() },
                enabled = busy || sendEnabled,
            ) {
                // Send and stop are one place; only the glyph changes. The
                // transition between them is animated: an instant icon swap in an
                // already pressed button reads as a miss.
                AnimatedContent(
                    targetState = busy,
                    transitionSpec = { actionIn.togetherWith(actionOut) },
                    label = "composerAction",
                ) { stopping ->
                    Icon(
                        imageVector = if (stopping) Icons.Rounded.Stop else Icons.Rounded.ArrowUpward,
                        contentDescription = if (stopping) {
                            stringResource(R.string.rumi_stop)
                        } else {
                            stringResource(R.string.rumi_send)
                        },
                    )
                }
            }
        }
    }
}

/**
 * Without a credential there is nothing to send to, so the composer is replaced
 * rather than left as a field that swallows what is typed into it.
 */
@Composable
private fun SignInCard(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.rumi_sign_in_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.size(12.dp))
        Button(onClick = onOpenSettings) {
            Text(stringResource(R.string.rumi_sign_in))
        }
    }
}

// --- The model sheet ---

/**
 * Reasoning depth: linked buttons above the model list.
 *
 * The button set is not ours but the model's: it comes from the catalogue's own
 * `reasoning_options`, so a model with `low..max` has five of them plus "Auto", a
 * model with a toggle has three, and a model with no choice has no buttons at
 * all. A list of levels of our own would diverge from the provider at the first
 * new model, and it would diverge silently: the user would see a choice the model
 * does not accept.
 *
 * "Auto" is not decoration and not an empty item: it means "send nothing", that
 * is, "as intended". This differs from "Off": switching off asks it not to think,
 * and a model that always thinks may not have such a request.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ThinkingSwitcher(
    model: RumiModel,
    choice: RumiThinkingChoice,
    onPick: (RumiThinkingChoice) -> Unit,
) {
    val options = thinkingButtons(model.thinking, LocalContext.current)
    if (options.isEmpty()) return
    // The choice outlives the model, so what must be shown is what will be sent:
    // a level this model does not have reads as "Auto".
    val shown = if (RumiThinkingChoice.fits(choice, model.thinking)) choice else RumiThinkingChoice.Auto
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.rumi_thinking),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
        )
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                // Scrolling, not squeezing: some models have six levels plus
                // "Auto", and buttons squeezed into the screen stop being readable.
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            options.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = shown == value,
                    onClick = { onPick(value) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                ) {
                    Text(label)
                }
            }
        }
    }
}

/**
 * The buttons for the kind of choice the model has.
 *
 * An empty list is not an error: for seven models the catalogue marks reasoning
 * as "present" but does not say what controls it. Showing buttons there would mean
 * offering a choice about which nothing is known.
 */
private fun thinkingButtons(
    thinking: RumiThinking,
    context: Context,
): List<Pair<RumiThinkingChoice, String>> =
    when (thinking) {
        RumiThinking.None -> emptyList()
        is RumiThinking.Effort ->
            listOf(RumiThinkingChoice.Auto to context.getString(R.string.rumi_thinking_auto)) +
                thinking.values.map {
                    RumiThinkingChoice.Effort(it) to effortLabel(it, context)
                }
        RumiThinking.Toggle -> listOf(
            RumiThinkingChoice.Auto to context.getString(R.string.rumi_thinking_auto),
            RumiThinkingChoice.Off to context.getString(R.string.rumi_thinking_off),
            RumiThinkingChoice.On to context.getString(R.string.rumi_thinking_on),
        )
        is RumiThinking.Budget -> {
            val max = thinking.max.coerceAtLeast(thinking.min)
            val mid = ((thinking.min + max) / 2).coerceAtLeast(thinking.min)
            listOf(RumiThinkingChoice.Auto to context.getString(R.string.rumi_thinking_auto)) +
                listOf(thinking.min, mid, max)
                    .distinct()
                    .map { RumiThinkingChoice.Budget(it) to tokenLabel(it, context) }
        }
    }

/**
 * A level the way a human reads it.
 *
 * Values arrive machine-shaped (`xhigh`), and showing them as is means making the
 * user guess. An unknown value is neither translated nor hidden: a model released
 * tomorrow will bring its own, and it is better to show it as is than not to show
 * it at all.
 */
private fun effortLabel(value: String, context: Context): String = when (value.lowercase()) {
    "none" -> context.getString(R.string.rumi_thinking_off)
    "minimal" -> context.getString(R.string.rumi_thinking_minimal)
    "low" -> context.getString(R.string.rumi_thinking_low)
    "medium" -> context.getString(R.string.rumi_thinking_medium)
    "high" -> context.getString(R.string.rumi_thinking_high)
    "xhigh" -> context.getString(R.string.rumi_thinking_xhigh)
    "max" -> context.getString(R.string.rumi_thinking_max)
    else -> value.replaceFirstChar { it.uppercase() }
}

private fun tokenLabel(tokens: Int, context: Context): String = when {
    tokens <= 0 -> context.getString(R.string.rumi_thinking_off)
    tokens >= 1_000_000 -> "${tokens / 1_000_000}M"
    tokens >= 1_000 -> "${tokens / 1_000}k"
    else -> "$tokens"
}

/**
 * The catalogue's note in the language the interface is set to.
 *
 * `RumiModels` builds the note as a value, not as a sentence, precisely so this
 * resolution can happen here — where the localised context is the one Compose is
 * drawing with — rather than on the worker that read the catalogue.
 */
@Composable
private fun catalogueNoteText(note: RumiModels.CatalogueNote): String = when (note) {
    is RumiModels.CatalogueNote.RefreshFailed ->
        stringResource(R.string.rumi_models_refresh_failed, note.reason)

    is RumiModels.CatalogueNote.LoadFailed ->
        stringResource(R.string.rumi_models_load_failed, note.reason)

    RumiModels.CatalogueNote.NoModelList -> stringResource(R.string.rumi_models_no_list)

    is RumiModels.CatalogueNote.NotInCatalogue ->
        stringResource(R.string.rumi_models_not_in_catalogue, note.count)

    is RumiModels.CatalogueNote.CapabilitiesFailed ->
        stringResource(R.string.rumi_models_capabilities_failed, note.count, note.reason)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSheet(
    catalogue: RumiModels.Catalogue?,
    loading: Boolean,
    selectedId: String?,
    thinking: RumiThinkingChoice,
    onPick: (RumiModel) -> Unit,
    onPickThinking: (RumiThinkingChoice) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val models = catalogue?.models.orEmpty()
    // Resolved here, not where the catalogue was built: the catalogue is read
    // on a worker holding the raw application context, so a sentence built there
    // followed the phone's language instead of the one chosen in the app.
    val note = catalogue?.note?.let { catalogueNoteText(it) }

    fun dismiss() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.rumi_model),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
            )
            // The thinking switcher sits above the model list, not below: it
            // pertains to the selected model, and must be read together with it,
            // not as a separate setting somewhere else. The button set comes from
            // the model — see [ThinkingSwitcher].
            models.firstOrNull { it.id == selectedId }?.let { current ->
                ThinkingSwitcher(
                    model = current,
                    choice = thinking,
                    onPick = onPickThinking,
                )
            }
            if (loading) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.size(12.dp))
                    Text(
                        text = stringResource(R.string.rumi_models_refreshing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                if (models.isEmpty()) {
                    item(key = "empty") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.rumi_models_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onRefresh) {
                                Text(stringResource(R.string.rumi_refresh))
                            }
                        }
                    }
                } else {
                    items(items = models, key = { it.id }) { entry ->
                        ModelRow(
                            model = entry,
                            selected = entry.id == selectedId,
                            onPick = {
                                onPick(entry)
                                dismiss()
                            },
                        )
                    }
                }
                // A degraded capability list must stay visible: it is the only
                // place the app says what it had to assume about these models.
                if (note != null) {
                    item(key = "note") {
                        Text(
                            text = note,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(
                                start = 20.dp,
                                end = 20.dp,
                                top = 4.dp,
                                bottom = 20.dp,
                            ),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.size(8.dp))
        }
    }
}

@Composable
private fun ModelRow(model: RumiModel, selected: Boolean, onPick: () -> Unit) {
    val usable = model.tools
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = usable, onClick = onPick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model.name,
                style = MaterialTheme.typography.titleSmall,
                color = if (usable) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = model.summary(LocalContext.current),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!usable) {
                Text(
                    text = stringResource(R.string.rumi_model_no_tools),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = stringResource(R.string.state_selected),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// --- Shared pieces ---

@Composable
private fun Chevron(expanded: Boolean) {
    val motion = MaterialTheme.motionScheme
    val angle by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = motion.fastSpatialSpec<Float>(),
        label = "chevron",
    )
    Icon(
        imageVector = Icons.Rounded.ExpandMore,
        contentDescription = if (expanded) {
            stringResource(R.string.rumi_collapse)
        } else {
            stringResource(R.string.rumi_expand)
        },
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .size(18.dp)
            .rotate(angle),
    )
}

/**
 * A quiet pulse for work in flight: never fully dim, never fully lit.
 *
 * An endless loop has no counterpart in the motion scheme, so it is one of the
 * few places with a duration of its own — 1200 ms linear is the slowest fade
 * that still reads as "working".
 */
@Composable
private fun rememberShimmerAlpha(): Float {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween<Float>(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "shimmerAlpha",
    )
    return alpha
}

/** The one line under a tool's name: the tool's own summary, else its first argument. */
private fun toolSubtitle(part: RumiPart.Tool): String {
    part.summary?.takeIf { it.isNotBlank() }?.let { return it }
    if (part.arguments.isBlank()) return ""
    return runCatching {
        val args = JSONObject(part.arguments)
        val key = args.keys().asSequence().firstOrNull() ?: return@runCatching ""
        val value = args.opt(key)?.toString().orEmpty()
        "$key: ${value.take(60)}"
    }.getOrDefault(flatten(part.arguments, 60))
}

/** Indented JSON when the arguments are an object; the raw text when they are not. */
private fun prettyJson(arguments: String): String {
    if (arguments.isBlank()) return "{}"
    return runCatching { JSONObject(arguments).toString(2) }.getOrDefault(arguments)
}

private fun flatten(text: String, limit: Int): String {
    val flat = text.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
    return if (flat.length <= limit) flat else flat.take(limit) + "…"
}

/** The composer's one meta line: which model, and how it is spoken to. */
private fun composerMeta(model: RumiModel?, context: Context): String =
    if (model == null) {
        context.getString(R.string.rumi_state_no_model)
    } else {
        "${model.name} · ${model.protocolLabel}"
    }

/** What one part contributes to the transcript's "the last turn grew" signal. */
private fun partGrowthKey(part: RumiPart): Int = when (part) {
    is RumiPart.Text -> part.text.length
    is RumiPart.Reasoning -> part.text.length
    is RumiPart.Note -> part.text.length
    is RumiPart.Tool -> part.arguments.length + (part.result?.length ?: 0) +
        (if (part.running) 1 else 0)
}

private fun toolIcon(name: String): ImageVector = when (name) {
    "project_state" -> Icons.Rounded.List
    "open_panel" -> Icons.Rounded.Dashboard
    "select_layer" -> Icons.Rounded.Layers
    "seek", "transport" -> Icons.Rounded.PlayArrow
    "add_text", "update_layer" -> Icons.Rounded.TextFields
    "set_transition", "compare_effects" -> Icons.Rounded.CompareArrows
    "effects", "define_effect" -> Icons.Rounded.AutoFixHigh
    "snapshot" -> Icons.Rounded.PhotoCamera
    "filmstrip" -> Icons.Rounded.Movie
    else -> Icons.Rounded.Build
}

/** Openers that show what the assistant can do, in the order to try them. */
private val OpeningPrompts = listOf(
    R.string.rumi_prompt_title,
    R.string.rumi_prompt_blur,
    R.string.rumi_prompt_effect,
)
