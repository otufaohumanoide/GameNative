package app.gamenative.ui.component

import android.view.KeyEvent
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Bridges common gamepad buttons that Compose does not understand natively:
 * - BUTTON_A -> DPAD_CENTER (activates any focused clickable/selectable; Compose only
 *   reacts to Enter/Space/DPAD_CENTER),
 * - BUTTON_B -> BACK (dismisses BackHandler-aware overlays/dialogs).
 *
 * Installed on the host view (window) via setOnKeyListener, which runs BEFORE Compose's
 * dispatch; the translated events are re-dispatched through the same view so the rest of
 * the pipeline (focus system, BackHandler) behaves exactly as if the user pressed the
 * native key. The original button events are consumed so the game never sees them while
 * an overlay is open.
 *
 * Spec: docs/superpowers/specs/2026-08-08-dpad-shader-navigation-design.md
 */
@Composable
fun GamepadKeyBridge(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled, view) {
        if (!enabled) return@DisposableEffect onDispose {}
        val listener = View.OnKeyListener { _, keyCode, event ->
            when (keyCode) {
                KeyEvent.KEYCODE_BUTTON_A -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        // Translate A -> DPAD_CENTER (activation key Compose understands).
                        val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
                        val up = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER)
                        view.dispatchKeyEvent(down)
                        view.dispatchKeyEvent(up)
                    }
                    true // consume A (up too) so it never reaches the game/other layers
                }
                KeyEvent.KEYCODE_BUTTON_B -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        view.dispatchKeyEvent(
                            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK),
                        )
                        view.dispatchKeyEvent(
                            KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK),
                        )
                    }
                    true
                }
                else -> false
            }
        }
        view.setOnKeyListener(listener)
        onDispose {
            view.setOnKeyListener(null)
        }
    }
}
