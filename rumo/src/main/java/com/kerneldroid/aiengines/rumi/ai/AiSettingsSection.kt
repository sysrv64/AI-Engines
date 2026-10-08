// SPDX-License-Identifier: GPL-3.0-or-later
package com.kerneldroid.aiengines.rumi.ai

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.kerneldroid.aiengines.R
import com.kerneldroid.aiengines.ui.AiSectionHeader
import com.kerneldroid.aiengines.ui.aiSegmentedColors
import com.kerneldroid.aiengines.rumi.RumiAuth
import com.kerneldroid.aiengines.ui.AiSpacing
import com.kerneldroid.aiengines.ui.hapticConfirm
import kotlinx.coroutines.delay

/** How long the "saved" mark in the header stays up, in ms. */
private const val SAVED_PULSE_MS = 2000L

/**
 * Generative services: what the assistant can *create*, not only describe.
 *
 * ## Why one group per kind, and not a single list
 *
 * The kind is what the user switches on: they have no service "in general", they
 * have "speech" and "pictures", and those are switched on separately. A mixed
 * list would force reading the labels to work out what exactly would become
 * available.
 *
 * ## Why there is one save for the whole block
 *
 * A service's key, address and model are not three independent fields but one
 * connection: a key without an address and an address without a key are equally
 * useless, and saving them in halves would leave the setting in a state the user
 * did not choose. That is why the button is a single one, in the block's header,
 * and writes everything in one operation.
 *
 * What applies at once and what goes through the button: the switch and the
 * removal are a choice and an action, like the provider chips, and "chosen but
 * not saved" would only confuse them. The key, the address and the model are
 * input, and that is saved with the button.
 */
@Composable
fun AiSettingsSection() {
    val state by AiServices.state.collectAsState()
    val haptics = LocalHapticFeedback.current
    val entries = state.entries

    // The drafts are re-read from the store when the saved value changes —
    // including a write of our own. Otherwise, after saving, the field would
    // keep "dirty" input, and the button would light up again on it.
    var keyDrafts by remember(entries.map { it.id to it.apiKey }) {
        mutableStateOf(entries.associate { it.id to it.apiKey })
    }
    var modelDrafts by remember(entries.map { it.id to it.modelOverride }) {
        mutableStateOf(entries.associate { it.id to it.modelOverride })
    }
    var urlDrafts by remember(entries.map { it.id to it.baseUrlOverride }) {
        mutableStateOf(entries.associate { it.id to it.baseUrlOverride })
    }

    // We compare not the raw input but exactly what AiServices will write: the
    // key is trimmed at the edges, the address loses its trailing slash.
    // Otherwise the button would light up on a space that changes nothing.
    val dirty = entries.any { entry ->
        keyDrafts[entry.id].orEmpty().trim() != entry.apiKey ||
            modelDrafts[entry.id].orEmpty().trim() != entry.modelOverride ||
            urlDrafts[entry.id].orEmpty().trim().trimEnd('/') != entry.baseUrlOverride
    }

    var saveToken by remember { mutableStateOf(0) }
    var saved by remember { mutableStateOf(false) }
    LaunchedEffect(saveToken) {
        if (saveToken == 0) return@LaunchedEffect
        saved = true
        delay(SAVED_PULSE_MS)
        saved = false
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AiSpacing.m),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AiSpacing.m),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(AiSpacing.xs),
            ) {
                Text(
                    text = stringResource(R.string.rumi_ai_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.rumi_ai_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AiSaveButton(
                // We show "Saved" only in a clean state: if the user edits
                // something again, the old mark would lie.
                saved = saved && !dirty,
                enabled = dirty,
                onClick = {
                    entries.forEach { entry ->
                        val key = keyDrafts[entry.id].orEmpty()
                        if (key.trim() != entry.apiKey) AiServices.setApiKey(entry.id, key)
                        val model = modelDrafts[entry.id].orEmpty()
                        if (model.trim() != entry.modelOverride) AiServices.setModel(entry.id, model)
                        val url = urlDrafts[entry.id].orEmpty()
                        if (url.trim().trimEnd('/') != entry.baseUrlOverride) {
                            AiServices.setBaseUrl(entry.id, url)
                        }
                    }
                    haptics.hapticConfirm()
                    saveToken += 1
                },
            )
        }

        AiKind.entries.forEach { kind ->
            AiServiceGroup(
                kind = kind,
                entries = state.ofKind(kind),
                keyOf = { keyDrafts[it.id].orEmpty() },
                modelOf = { modelDrafts[it.id].orEmpty() },
                urlOf = { urlDrafts[it.id].orEmpty() },
                onKey = { id, value -> keyDrafts = keyDrafts + (id to value) },
                onModel = { id, value -> modelDrafts = modelDrafts + (id to value) },
                onUrl = { id, value -> urlDrafts = urlDrafts + (id to value) },
                onRemove = { id -> AiServices.removeCustom(id) },
            )
        }

        AiAddCustomBlock()
    }
}

/**
 * One group: the kind's header, an explanation and the service cards.
 *
 * An empty group does not disappear, except for sound, and that is deliberate:
 * an empty "Images" is the state "not configured yet", which has to be said,
 * while a kind with no known service is "this cannot be done", and that has to
 * be said too.
 */
@Composable
private fun AiServiceGroup(
    kind: AiKind,
    entries: List<AiEntry>,
    keyOf: (AiEntry) -> String,
    modelOf: (AiEntry) -> String,
    urlOf: (AiEntry) -> String,
    onKey: (String, String) -> Unit,
    onModel: (String, String) -> Unit,
    onUrl: (String, String) -> Unit,
    onRemove: (String) -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AiSpacing.s),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AiSpacing.xs)) {
            AiSectionHeader(kindTitle(kind, context))
            Text(
                text = kindNote(kind, context),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (entries.isEmpty()) {
            Surface(
                color = aiSegmentedColors().containerColor,
                shape = MaterialTheme.shapes.large,
            ) {
                Text(
                    text = stringResource(R.string.rumi_ai_empty),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(AiSpacing.l),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            return@Column
        }
        entries.forEach { entry ->
            AiServiceCard(
                entry = entry,
                key = keyOf(entry),
                model = modelOf(entry),
                url = urlOf(entry),
                onKey = { onKey(entry.id, it) },
                onModel = { onModel(entry.id, it) },
                onUrl = { onUrl(entry.id, it) },
                onRemove = { onRemove(entry.id) },
            )
        }
    }
}

/**
 * A service card: the switch, the key, the overrides.
 *
 * The state in words, not only in the switch's position: "a key is present, the
 * service is off" and "there is no key" look the same — grey — while what you do
 * about them differs. The same device as for web search.
 */
@Composable
private fun AiServiceCard(
    entry: AiEntry,
    key: String,
    model: String,
    url: String,
    onKey: (String) -> Unit,
    onModel: (String) -> Unit,
    onUrl: (String) -> Unit,
    onRemove: () -> Unit,
) {
    // A switch without a key or an address would turn on nothing: the service
    // would not become ready, and "on but not working" is a state that did not
    // exist.
    val canEnable = entry.apiKey.isNotEmpty() && entry.baseUrl.isNotEmpty()
    val context = LocalContext.current
    // Built-in services keep their line in a resource, a service the user added carries
    // it in the record: there is nowhere else to keep a text they wrote.
    val noteRes = entry.service.noteRes
    val note = if (noteRes != 0) stringResource(noteRes) else entry.service.note
    // An address or a model that is not set is unfinished input for a service the user
    // added, not an error: the field says what is missing instead of looking broken.
    val requiredHint = stringResource(R.string.rumi_ai_required_hint)
    Surface(
        color = aiSegmentedColors().containerColor,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AiSpacing.l),
            verticalArrangement = Arrangement.spacedBy(AiSpacing.l),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AiSpacing.m),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(AiSpacing.xs),
                ) {
                    Text(
                        text = entry.label +
                            if (entry.custom) stringResource(R.string.rumi_custom_suffix) else "",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = statusText(entry, context),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = entry.enabled,
                    onCheckedChange = { AiServices.setEnabled(entry.id, it) },
                    enabled = canEnable,
                )
            }
            if (note.isNotEmpty()) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = key,
                onValueChange = onKey,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.rumi_field_api_key)) },
                supportingText = {
                    Text(
                        text = stringResource(R.string.rumi_ai_key_support),
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                singleLine = true,
                // The key is a secret: we always mask it, so the field does not
                // reveal it by accident when the screen is shown or screenshotted.
                visualTransformation = PasswordVisualTransformation(),
            )
            OutlinedTextField(
                value = url,
                onValueChange = onUrl,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.rumi_field_base_url_override)) },
                supportingText = {
                    Text(
                        text = entry.service.baseUrl.ifEmpty { requiredHint },
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                singleLine = true,
            )
            OutlinedTextField(
                value = model,
                onValueChange = onModel,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.rumi_field_model_override)) },
                supportingText = {
                    Text(
                        text = entry.service.model.ifEmpty { requiredHint },
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                singleLine = true,
            )
            if (entry.custom) {
                TextButton(onClick = onRemove) {
                    Text(stringResource(R.string.rumi_ai_remove))
                }
            }
        }
    }
}

/**
 * Set up your own service.
 *
 * The kinds are limited to speech and images — where the compatible request
 * shape is documented (`POST {base}/audio/speech`, `POST {base}/images/generations`).
 * There is nothing to fill in for video and sound, and that is said here plainly
 * rather than hidden: a form that fails on every server in its own way is worse
 * than a missing form.
 */
@Composable
private fun AiAddCustomBlock() {
    val kinds = AiCatalog.customKinds
    var kind by remember { mutableStateOf(kinds.first()) }
    var label by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var auth by remember { mutableStateOf(RumiAuth.Bearer) }
    var headerName by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AiSpacing.s),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(AiSpacing.xs)) {
            AiSectionHeader(stringResource(R.string.rumi_ai_custom_title))
            Text(
                text = stringResource(R.string.rumi_ai_custom_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            color = aiSegmentedColors().containerColor,
            shape = MaterialTheme.shapes.large,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AiSpacing.l),
                verticalArrangement = Arrangement.spacedBy(AiSpacing.l),
            ) {
                AiChoiceRow(
                    title = stringResource(R.string.rumi_ai_produces),
                    options = kinds.map { it to it.id },
                    selected = kind,
                    onSelect = { kind = it },
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.rumi_field_name)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.rumi_field_base_url)) },
                    supportingText = {
                        Text(
                            text = stringResource(R.string.rumi_base_url_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.rumi_field_model)) },
                    singleLine = true,
                )
                AiChoiceRow(
                    title = stringResource(R.string.rumi_field_credential),
                    options = listOf(
                        RumiAuth.Bearer to "bearer",
                        RumiAuth.XApiKey to "x-api-key",
                        RumiAuth.Header to "header",
                    ),
                    selected = auth,
                    onSelect = { auth = it },
                )
                if (auth == RumiAuth.Header) {
                    OutlinedTextField(
                        value = headerName,
                        onValueChange = { headerName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.rumi_field_header_name)) },
                        singleLine = true,
                    )
                }
                Button(
                    // There is no point setting up a service without an address
                    // and a model: it would appear in the list and never become
                    // ready.
                    enabled = url.isNotBlank() && model.isNotBlank(),
                    onClick = {
                        val added = AiServices.addCustom(kind, label, url, model, auth, headerName)
                        if (added != null) {
                            label = ""
                            url = ""
                            model = ""
                            headerName = ""
                        }
                    },
                ) {
                    Text(stringResource(R.string.rumi_add))
                }
            }
        }
    }
}

// --- Labels and small details ---

private fun kindTitle(kind: AiKind, context: Context): String = when (kind) {
    AiKind.Speech -> context.getString(R.string.rumi_ai_kind_speech)
    AiKind.Sound -> context.getString(R.string.rumi_ai_kind_sound)
    AiKind.Image -> context.getString(R.string.rumi_ai_kind_image)
    AiKind.Video -> context.getString(R.string.rumi_ai_kind_video)
}

private fun kindNote(kind: AiKind, context: Context): String = when (kind) {
    AiKind.Speech -> context.getString(R.string.rumi_ai_kind_speech_note)
    AiKind.Sound -> context.getString(R.string.rumi_ai_kind_sound_note)
    AiKind.Image -> context.getString(R.string.rumi_ai_kind_image_note)
    AiKind.Video -> context.getString(R.string.rumi_ai_kind_video_note)
}

private fun statusText(entry: AiEntry, context: Context): String = when {
    entry.apiKey.isEmpty() -> context.getString(R.string.rumi_ai_status_no_key)
    entry.baseUrl.isEmpty() -> context.getString(R.string.rumi_ai_status_no_url)
    entry.model.isEmpty() -> context.getString(R.string.rumi_ai_status_no_model)
    entry.enabled -> context.getString(R.string.rumi_ai_status_ready, entry.model)
    else -> context.getString(R.string.rumi_ai_status_off, entry.model)
}

/** A choice row of two or three options: label on the left, chips on the right. */
@Composable
private fun <T> AiChoiceRow(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AiSpacing.xs),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(AiSpacing.s),
        ) {
            options.forEach { (value, text) ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    label = { Text(text) },
                )
            }
        }
    }
}

/**
 * The block's only action.
 *
 * Three states read off one button: grey and disabled — nothing to change;
 * filled with the accent — something to save; a tick and "Saved" — saved.
 */
@Composable
private fun AiSaveButton(saved: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled) {
        if (saved) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        }
        Text(
            if (saved) stringResource(R.string.rumi_saved) else stringResource(R.string.action_save),
        )
    }
}
