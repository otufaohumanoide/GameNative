package app.gamenative.ui.component

import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Colored focus ring (sweep gradient border rotating while focused).
 *
 * Kept as a thin wrapper over the gamepad focus language ([Modifier.gamepadFocus] in
 * [GamepadFocusState.Focused]) for surfaces OUTSIDE the QuickMenu (LibraryGridCard, InfoCard…).
 * Defaults aligned with the language defaults (3dp / 1200ms, spec 2026-08-15
 * focus-feedback-v2, §2) so every surface shares the same ring weight and rotation speed.
 *
 * Pass the element's clickable [interactionSource] so focus is tracked, and apply this after the
 * clip/background so the border draws on top. [durationMillis] is one full rotation.
 */
@Composable
fun Modifier.focusRing(
    interactionSource: InteractionSource,
    shape: Shape,
    width: Dp = 3.dp,
    durationMillis: Int = 1200,
): Modifier = gamepadFocus(
    state = GamepadFocusState.Focused,
    shape = shape,
    interactionSource = interactionSource,
    width = width,
    durationMillis = durationMillis,
)
