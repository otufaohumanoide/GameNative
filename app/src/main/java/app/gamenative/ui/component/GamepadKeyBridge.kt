package app.gamenative.ui.component

import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import app.gamenative.PluviaApp
import app.gamenative.PrefManager

/**
 * Bridges gamepad buttons that Compose does not understand natively.
 *
 * CONFIRM button (resolved per device by FaceStyle + swap via the hub — spec
 * 2026-08-13-onda2 §1.6; fallback BUTTON_A) -> DPAD_CENTER: activates any focused
 * clickable/selectable (Compose only reacts to Enter/Space/DPAD_CENTER). The translated
 * events are re-dispatched through the same view so the focus system behaves exactly as
 * if the user pressed DPAD_CENTER; the original key is consumed so the game never sees
 * it while an overlay is open.
 *
 * BUTTON_B is deliberately left RAW (decision D1, spec 2026-08-08-gamepad-input-refactoring):
 * surfaces handle it directly (adjustment rows unlock with B, gamepadBackHandler surfaces
 * map it to hierarchical back) — no synthetic BACK through the Activity dispatcher, which was
 * fragile (P2-12) and never reached OnBackPressedDispatcher anyway. The game never sees B
 * because the XServerScreen routing already hands the event to Compose when an overlay is
 * open.
 */
@Composable
fun GamepadKeyBridge(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled, view) {
        if (!enabled) return@DisposableEffect onDispose {}
        val listener = View.OnKeyListener { _, keyCode, event ->
            // Ghost gate (spec 2026-08-13): dialog windows are separate surfaces that never
            // pass through MainActivity's dispatcher — consume phantom touchpad keys silently
            // so a worn controller can't activate dialog rows (A -> DPAD_CENTER below).
            if ((event.source and InputDevice.SOURCE_CLASS_POINTER) != 0 &&
                PrefManager.ignoreControllerTouchpad
            ) {
                return@OnKeyListener true
            }
            when (keyCode) {
                // Fase 6 / Onda 2 (spec 2026-08-13-onda2 §1.6): o botão de CONFIRMAÇÃO é
                // resolvido por FaceStyle + swap via hub (fallback BUTTON_A — Xbox default).
                PluviaApp.gamepadHub.confirmKeyCodeFor(event.deviceId)
                    ?: KeyEvent.KEYCODE_BUTTON_A -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        // Translate confirm -> DPAD_CENTER (activation key Compose understands).
                        // U5: vibra o DEVICE (perfil rumbleOnActivate ?: global).
                        GamepadHaptics.vibrateDevice(view.context, event.deviceId, GamepadHaptics.HapticEffect.ACTIVATE)
                        val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
                        val up = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER)
                        view.dispatchKeyEvent(down)
                        view.dispatchKeyEvent(up)
                    }
                    true // consume confirm (up too) so it never reaches the game/other layers
                }
                else -> false // B and everything else reach Compose raw
            }
        }
        view.setOnKeyListener(listener)
        onDispose {
            view.setOnKeyListener(null)
        }
    }
}
