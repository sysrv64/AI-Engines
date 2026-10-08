// SPDX-License-Identifier: GPL-3.0-or-later
package com.kerneldroid.aiengines.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.dp

/**
 * The module's own UI kit.
 *
 * The assistant's screens are built here, but they may not read the host app's
 * design system: a library that reached into `RumoSpacing` or `NavContainers`
 * would not compile on its own and would silently change when the app restyles a
 * screen it does not own. So the handful of bricks the assistant uses are copied
 * instead — a scale of dp constants and a header are not worth a shared module,
 * and a copy that drifts is a smaller problem than a hidden dependency.
 *
 * These are near-copies of the app's `ui/theme` and `ui/nav` pieces, deliberately
 * the same values and shapes: the assistant tab must look like the rest of the app.
 */
object AiSpacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 20.dp
    val card = 24.dp
    val maxPane = 600.dp
}

/** The margin between a group header and its capsule. Mirrors the app's `NavHeaderGap`. */
private val AiHeaderGap = 4.dp

/**
 * Group header: "what is this block". Semantic, not decorative — the colour is
 * muted so it does not fight the group's content.
 */
@Composable
fun AiSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = AiHeaderGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        trailing?.invoke()
    }
}

/**
 * Segment colours: `surfaceBright` on the screen background `surfaceContainer`.
 *
 * Tonal contrast instead of a border — Material's own answer to "this is a
 * capsule" without a single line. The values stay stock on purpose.
 */
@Composable
fun aiSegmentedColors(): ListItemColors = ListItemDefaults.segmentedColors()

/** Confirmation haptic. Modelled on the app's `hapticConfirm`; no extra dependency. */
fun HapticFeedback.hapticConfirm() = performHapticFeedback(HapticFeedbackType.Confirm)

/**
 * One action in a list-row menu.
 *
 * The icon is required, not optional: the menu opens over the list, and a row
 * without an icon reads more slowly than one with it.
 */
data class AiMenuAction(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

/** Icons for the assistant's menus; only the two the chat row offers. */
object AiMenuIcons {
    val Edit = Icons.Rounded.Edit
    val Delete = Icons.Rounded.Delete
}

private class AiHoldConfiguration(
    private val base: ViewConfiguration,
    private val holdMs: Long,
) : ViewConfiguration by base {
    override val longPressTimeoutMillis: Long get() = holdMs
}

/**
 * A hold lasting [holdMs] instead of the system one.
 *
 * The system hold is about half a second, and for a list that is little: a touch
 * during a scroll lasts just as long, so a menu opening on a hold would trigger on
 * an ordinary finger movement. Three seconds is a deliberate action.
 */
const val AiListHoldMillis = 3_000L

/** Wrap a list so that a hold inside it lasts [holdMs]. */
@Composable
fun ProvideAiHoldDuration(holdMs: Long = AiListHoldMillis, content: @Composable () -> Unit) {
    val base = LocalViewConfiguration.current
    val configuration = remember(base, holdMs) { AiHoldConfiguration(base, holdMs) }
    CompositionLocalProvider(LocalViewConfiguration provides configuration, content = content)
}

/**
 * The bottom allowance for the navigation dock, which is an overlay over the whole
 * app. Mirrors the app's `DockContentInset`; the assistant's composer reserves it
 * so the dock does not cover the input field.
 */
val AiDockContentInset = 72.dp
