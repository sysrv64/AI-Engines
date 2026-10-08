// SPDX-License-Identifier: GPL-3.0-or-later
package com.kerneldroid.aiengines.rumi

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
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.kerneldroid.aiengines.R
import com.kerneldroid.aiengines.ui.AiSectionHeader
import com.kerneldroid.aiengines.ui.aiSegmentedColors
import com.kerneldroid.aiengines.ui.AiSpacing
import com.kerneldroid.aiengines.ui.hapticConfirm
import kotlinx.coroutines.delay

/** How long the "saved" mark stays in the header, ms. */
private const val SAVED_PULSE_MS = 2000L

/**
 * The Rumi block of the settings screen: who answers, the key it authenticates
 * with, and how much of the conversation may be pictures.
 *
 * ## One way to sign in, and that is deliberate
 *
 * Two used to sit side by side here: account sign-in through the console and a key.
 * Sign-in was removed — it ran the device-code flow while presenting itself to the
 * console as the official CLI's public `client_id` (`opencode-cli`). That is
 * impersonating the client's identity, and OpenCode's terms of use forbid "deceptive"
 * requests to the service and name the key as the documented path for a third-party
 * client. The remaining path is the one that is permitted, and it is also simpler:
 * the key does not expire, no browser is needed.
 *
 * ## Why the provider is picked with chips, not a list
 *
 * There are six providers, and they fit on one line. A collapsed list would hide the
 * main thing — that there is a choice: a user arriving with a Gemini key should not
 * have to hunt for where to switch behind an "OpenCode Go" button.
 *
 * ## Why there is a single button and it lives in the header
 *
 * The key and the address are not two independent fields but one connection, and it
 * cannot be saved in halves: an address without a key and a key without an address are
 * equally useless. So there is one button, it sits in the block's header — where it is
 * visible that it applies to the whole block — and it writes everything in one operation.
 */
@Composable
fun RumiSettingsSection() {
    val settings by RumiSettings.state.collectAsState()
    val haptics = LocalHapticFeedback.current
    val provider = settings.provider
    // Built-in providers keep their one-line description in a resource, the user's own
    // carry it in the record itself: there is nowhere else to keep a text they wrote.
    val providerNote =
        if (provider.noteRes != 0) stringResource(provider.noteRes) else provider.note

    // The drafts are re-read from storage as soon as the saved value changes —
    // including by our own write. Otherwise a "dirty" input would remain in the field
    // after saving, and the button would light up on it again.
    var apiKey by remember(settings.apiKey, settings.providerId) {
        mutableStateOf(settings.apiKey)
    }
    var endpoint by remember(settings.endpointOverride, settings.providerId) {
        mutableStateOf(settings.endpointOverride)
    }
    // The search key applies immediately, like the snapshot chips: it is not part of
    // the provider connection but a separate service, and the half-state "provider
    // address with a key, but no search key" is not an unfinished input but a working
    // state.
    var exaKey by remember(settings.exaKey) { mutableStateOf(settings.exaKey) }

    // A custom provider's fields are its definition, so the drafts hang off it
    // rather than off the whole settings object.
    var customLabel by remember(provider.id, provider.label) { mutableStateOf(provider.label) }
    var customUrl by remember(provider.id, provider.baseUrl) { mutableStateOf(provider.baseUrl) }
    var customAuthHeader by remember(provider.id, provider.authHeader) {
        mutableStateOf(provider.authHeader)
    }
    var customModelsPath by remember(provider.id, provider.modelsPath) {
        mutableStateOf(provider.modelsPath)
    }
    var customCapability by remember(provider.id, provider.capabilityProvider) {
        mutableStateOf(provider.capabilityProvider)
    }
    var customSession by remember(provider.id, provider.sessionHeader) {
        mutableStateOf(provider.sessionHeader)
    }

    // We compare not the raw input but exactly what RumiSettings will write: the key
    // is trimmed, the address has its trailing slash cut. Otherwise "Save" would light
    // up on a space that changes nothing.
    val keyChanged = apiKey.trim() != settings.apiKey
    val endpointChanged = endpoint.trim().trimEnd('/') != settings.endpointOverride
    val customChanged = provider.custom && (
        customLabel.trim() != provider.label ||
            customUrl.trim().trimEnd('/') != provider.baseUrl ||
            customAuthHeader.trim() != provider.authHeader ||
            customModelsPath.trim() != provider.modelsPath ||
            customCapability.trim() != provider.capabilityProvider ||
            customSession != provider.sessionHeader
        )
    val dirty = keyChanged || endpointChanged || customChanged

    // The "saved" mark fades by itself after a couple of seconds — confirmation without
    // a modal that has to be dismissed. A token, not a flag: saving again must restart
    // the countdown rather than wait for someone else's timer.
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
        // The block header: the block's meaning on the left, the only action on the right.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AiSpacing.m),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(AiSpacing.xs),
            ) {
                Text(text = "Rumi", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(R.string.rumi_settings_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RumiSaveButton(
                // "Saved" is shown only in a clean state: if the user edits something
                // again, the old mark would lie.
                saved = saved && !dirty,
                enabled = dirty,
                onClick = {
                    // We write each field only if it changed, and with the same
                    // normalisation order as RumiSettings.
                    if (customChanged) {
                        RumiSettings.setCustomProviders(
                            settings.customProviders.map { p ->
                                if (p.id != provider.id) {
                                    p
                                } else {
                                    p.copy(
                                        label = customLabel.trim().ifBlank { "Custom" },
                                        baseUrl = customUrl.trim().trimEnd('/'),
                                        authHeader = customAuthHeader.trim(),
                                        modelsPath = customModelsPath.trim()
                                            .ifBlank { "/models" },
                                        capabilityProvider = customCapability.trim(),
                                        sessionHeader = customSession,
                                    )
                                }
                            },
                        )
                    }
                    if (keyChanged) RumiSettings.setApiKey(apiKey)
                    if (endpointChanged) RumiSettings.setEndpointOverride(endpoint)
                    haptics.hapticConfirm()
                    saveToken += 1
                },
            )
        }

        // The provider is a separate group: switching applies immediately, because it is
        // not editing fields but choosing who to talk to next.
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(AiSpacing.s),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(AiSpacing.xs)) {
                AiSectionHeader(stringResource(R.string.rumi_section_provider))
                Text(
                    text = providerNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(AiSpacing.s),
            ) {
                val all = RumiProviders.builtIn + settings.customProviders.filter { c ->
                    RumiProviders.builtIn.none { it.id == c.id }
                }
                all.forEach { candidate ->
                    // The marker is assembled here rather than stored on the provider:
                    // the label itself is a record the user may have written.
                    val chipLabel = candidate.label +
                        if (candidate.custom) stringResource(R.string.rumi_custom_suffix) else ""
                    FilterChip(
                        selected = candidate.id == settings.providerId,
                        onClick = { RumiSettings.setProviderId(candidate.id) },
                        label = { Text(chipLabel) },
                    )
                }
            }
        }

        // The connection fields in one capsule: the header button saves exactly it.
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
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.rumi_field_api_key)) },
                    supportingText = {
                        Text(
                            text = stringResource(R.string.rumi_key_support, provider.label),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    singleLine = true,
                    // The key is a secret: always masked, so the field does not reveal it
                    // accidentally during a screen share or a screenshot.
                    visualTransformation = PasswordVisualTransformation(),
                )

                // An empty address is a custom provider's unfinished input, not an error:
                // the field says what is missing instead of looking broken.
                val endpointHint =
                    if (provider.baseUrl.isEmpty()) stringResource(R.string.rumi_endpoint_required)
                    else provider.baseUrl
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.rumi_field_endpoint)) },
                    supportingText = {
                        Text(
                            text = endpointHint,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    singleLine = true,
                )

                if (provider.custom) {
                    RumiCustomProviderFields(
                        label = customLabel,
                        onLabel = { customLabel = it },
                        url = customUrl,
                        onUrl = { customUrl = it },
                        protocol = provider.protocol,
                        onProtocol = { protocol ->
                            RumiSettings.setCustomProviders(
                                settings.customProviders.map { p ->
                                    if (p.id == provider.id) p.copy(protocol = protocol) else p
                                },
                            )
                        },
                        auth = provider.auth,
                        onAuth = { auth ->
                            RumiSettings.setCustomProviders(
                                settings.customProviders.map { p ->
                                    if (p.id == provider.id) p.copy(auth = auth) else p
                                },
                            )
                        },
                        authHeader = customAuthHeader,
                        onAuthHeader = { customAuthHeader = it },
                        modelsPath = customModelsPath,
                        onModelsPath = { customModelsPath = it },
                        capability = customCapability,
                        onCapability = { customCapability = it },
                        session = customSession,
                        onSession = { customSession = it },
                    )
                }
            }
        }

        // Snapshots are a separate group and a separate capsule: they apply immediately
        // and have nothing to do with the save button.
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(AiSpacing.s),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(AiSpacing.xs)) {
                AiSectionHeader(stringResource(R.string.rumi_section_snapshots))
                Text(
                    text = stringResource(R.string.rumi_snapshots_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(
                color = aiSegmentedColors().containerColor,
                shape = MaterialTheme.shapes.large,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(AiSpacing.l),
                    horizontalArrangement = Arrangement.spacedBy(AiSpacing.s),
                ) {
                    listOf(0, 2, 4, 8).forEach { count ->
                        FilterChip(
                            selected = settings.imageBudget == count,
                            onClick = { RumiSettings.setImageBudget(count) },
                            label = {
                                Text(
                                    if (count == 0) stringResource(R.string.rumi_none)
                                    else count.toString(),
                                )
                            },
                        )
                    }
                }
            }
        }

        // Web search is its own capsule: it is not part of the provider connection but a
        // separate service with its own key, and is enabled independently of it.
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(AiSpacing.s),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(AiSpacing.xs)) {
                AiSectionHeader(stringResource(R.string.rumi_section_web_search))
                Text(
                    text = stringResource(R.string.rumi_web_search_note),
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
                    verticalArrangement = Arrangement.spacedBy(AiSpacing.s),
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
                                text = stringResource(R.string.rumi_search_web),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            // The state in words, not just the switch position:
                            // "key present, search off" and "no key" look the same — grey —
                            // yet the thing to do about them is different.
                            Text(
                                text = when {
                                    settings.exaKey.isEmpty() ->
                                        stringResource(R.string.rumi_search_add_key)
                                    settings.searchEnabled ->
                                        stringResource(R.string.rumi_search_ready)
                                    else ->
                                        stringResource(R.string.rumi_search_saved_off)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = settings.searchEnabled,
                            onCheckedChange = { RumiSettings.setSearchEnabled(it) },
                            enabled = settings.exaKey.isNotEmpty(),
                        )
                    }
                    OutlinedTextField(
                        value = exaKey,
                        onValueChange = {
                            exaKey = it
                            RumiSettings.setExaKey(it)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.rumi_field_exa_key)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
            }
        }
    }
}

/**
 * Fields of a custom provider.
 *
 * They appear only for a custom one: for a built-in, the address and the headers are
 * facts about someone else's service, and offering to edit them would be offering to
 * break a setting that cannot then be restored (built-ins have no reset).
 *
 * The protocol and the authorisation method are chosen immediately, not with the save
 * button: they are pickers like the provider itself, and "chose but did not save" would
 * leave the setting in a state the user cannot see.
 */
@Composable
private fun RumiCustomProviderFields(
    label: String,
    onLabel: (String) -> Unit,
    url: String,
    onUrl: (String) -> Unit,
    protocol: RumiProtocol,
    onProtocol: (RumiProtocol) -> Unit,
    auth: RumiAuth,
    onAuth: (RumiAuth) -> Unit,
    authHeader: String,
    onAuthHeader: (String) -> Unit,
    modelsPath: String,
    onModelsPath: (String) -> Unit,
    capability: String,
    onCapability: (String) -> Unit,
    session: Boolean,
    onSession: (Boolean) -> Unit,
) {
    OutlinedTextField(
        value = label,
        onValueChange = onLabel,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.rumi_field_name)) },
        singleLine = true,
    )
    OutlinedTextField(
        value = url,
        onValueChange = onUrl,
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
    // The chip wording is not a label but the token written into the settings file
    // (see RumiSettings), so these stay in English in every language.
    RumiChoiceRow(
        title = stringResource(R.string.rumi_field_protocol),
        options = listOf(
            RumiProtocol.Chat to "chat",
            RumiProtocol.Responses to "responses",
            RumiProtocol.Messages to "messages",
        ),
        selected = protocol,
        onSelect = onProtocol,
    )
    RumiChoiceRow(
        title = stringResource(R.string.rumi_field_credential),
        options = listOf(
            RumiAuth.Bearer to "bearer",
            RumiAuth.XApiKey to "x-api-key",
            RumiAuth.Header to "header",
        ),
        selected = auth,
        onSelect = onAuth,
    )
    if (auth == RumiAuth.Header) {
        OutlinedTextField(
            value = authHeader,
            onValueChange = onAuthHeader,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.rumi_field_header_name)) },
            singleLine = true,
        )
    }
    OutlinedTextField(
        value = modelsPath,
        onValueChange = onModelsPath,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.rumi_field_models_path)) },
        supportingText = {
            Text(
                text = stringResource(R.string.rumi_models_path_hint),
                style = MaterialTheme.typography.bodySmall,
            )
        },
        singleLine = true,
    )
    OutlinedTextField(
        value = capability,
        onValueChange = onCapability,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.rumi_field_capability)) },
        supportingText = {
            Text(
                text = stringResource(R.string.rumi_capability_hint),
                style = MaterialTheme.typography.bodySmall,
            )
        },
        singleLine = true,
    )
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
                text = stringResource(R.string.rumi_session_header),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.rumi_session_header_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = session, onCheckedChange = onSession)
    }
}

/** A choice row of two or three options: a label on the left, chips on the right. */
@Composable
private fun <T> RumiChoiceRow(
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
        Row(horizontalArrangement = Arrangement.spacedBy(AiSpacing.s)) {
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
 * The block's single action.
 *
 * Three states read from one button with no explanation: grey and disabled —
 * nothing to change; filled with the accent — there is something to save; a
 * checkmark and "Saved" — saved. None of them needs a dialog.
 */
@Composable
private fun RumiSaveButton(saved: Boolean, enabled: Boolean, onClick: () -> Unit) {
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
