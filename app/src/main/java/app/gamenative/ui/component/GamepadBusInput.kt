package app.gamenative.ui.component

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import app.gamenative.PluviaApp
import app.gamenative.events.AndroidEvent
import timber.log.Timber

/**
 * Bus-level joystick navigator for in-game overlays (spec 2026-08-09, §2.1 + lessons).
 *
 * WHY bus-level: inside the game window, the XServerRendererView (GL) is a child of the
 * ComposeView, and the app's proven gamepad navigation (LibraryScreen.kt:734-764) consumes
 * AndroidEvent.MotionEvent directly from the PluviaApp.events bus — never relying on the
 * Android view hierarchy. The QuickMenu lives in the SAME window as the GL surface, where
 * view-level listeners are unreliable; the bus path is the one that demonstrably works in
 * this app, so the QuickMenu uses it too.
 *
 * While [enabled], ALL gamepad motion is consumed (the game must not receive the stick while
 * the overlay is up) and axis/hat movement is translated into Compose focus moves with the
 * same deadzone/hysteresis/cooldown semantics as [JoystickFocusNavigator] (which stays for
 * dialog windows, where events never reach this bus).
 */
@Composable
fun BusJoystickFocusNavigator(
    enabled: Boolean,
    deadZone: Float = 0.45f,
    releaseZone: Float = 0.30f,
    cooldownMs: Long = 180L,
) {
    val focusManager = LocalFocusManager.current
    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose {}
        var lastMoveAt = 0L
        var armed = true
        fun handleMotion(androidEvent: AndroidEvent.MotionEvent): Boolean {
            val ev = androidEvent.event ?: return false
            val isGamepad = (ev.source and InputDevice.SOURCE_JOYSTICK) != 0 ||
                (ev.source and InputDevice.SOURCE_DPAD) != 0
            if (!isGamepad) return false
            if (ev.actionMasked != MotionEvent.ACTION_MOVE) return true
            val stickX = ev.getAxisValue(MotionEvent.AXIS_X)
            val stickY = ev.getAxisValue(MotionEvent.AXIS_Y)
            val hatX = ev.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hatY = ev.getAxisValue(MotionEvent.AXIS_HAT_Y)
            val magnitude = maxOf(
                maxOf(kotlin.math.abs(stickX), kotlin.math.abs(stickY)),
                maxOf(kotlin.math.abs(hatX), kotlin.math.abs(hatY)),
            )
            if (!armed) {
                if (magnitude < releaseZone) armed = true
                // Consumed: the overlay owns the stick, even when not moving focus.
                return true
            }
            val now = SystemClock.uptimeMillis()
            val direction = when {
                hatY < -0.5f -> FocusDirection.Up
                hatY > 0.5f -> FocusDirection.Down
                hatX < -0.5f -> FocusDirection.Left
                hatX > 0.5f -> FocusDirection.Right
                stickY < -deadZone -> FocusDirection.Up
                stickY > deadZone -> FocusDirection.Down
                stickX < -deadZone -> FocusDirection.Left
                stickX > deadZone -> FocusDirection.Right
                else -> null
            }
            if (direction == null) return true
            if (now - lastMoveAt < cooldownMs) return true
            lastMoveAt = now
            armed = false
            Timber.d("BusJoystick: moveFocus(%s) mag=%.2f", direction, magnitude)
            focusManager.moveFocus(direction)
            return true
        }
        val handler: (AndroidEvent.MotionEvent) -> Boolean = ::handleMotion
        PluviaApp.events.on<AndroidEvent.MotionEvent, Boolean>(handler)
        Timber.d("BusJoystick: listening")
        onDispose {
            PluviaApp.events.off<AndroidEvent.MotionEvent, Boolean>(handler)
        }
    }
}

/**
 * Bus-level gamepad key bridge for in-game overlays (QuickMenu).
 *
 * Keys dispatched through the window go to the focused Android view; inside the game window
 * that is not guaranteed to be the ComposeView. The bus handler below delivers the gamepad
 * keys Compose understands directly to the ComposeView (bypassing window focus routing) and
 * consumes them so the game never sees them while the overlay is open.
 *
 * - BUTTON_A -> synthetic DPAD_CENTER (with haptics), same translation as [GamepadKeyBridge].
 * - BUTTON_B / L1 / R1 / L2 / R2 / DPAD_* / ENTER -> re-dispatched raw into the ComposeView
 *   (the surface handlers consume them: hierarchical back, tab switching, page scroll).
 *
 * [GamepadKeyBridge] (view-level) stays for dialog windows, whose events never hit this bus.
 */
@Composable
fun BusGamepadKeyBridge(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled, view) {
        if (!enabled) return@DisposableEffect onDispose {}
        val handledKeys = intArrayOf(
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_L2,
            KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
        )
        fun handleKey(androidEvent: AndroidEvent.KeyEvent): Boolean {
            val event = androidEvent.event
            if (event.keyCode == KeyEvent.KEYCODE_BUTTON_A) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    GamepadHaptics.vibrate(view.context)
                    view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
                    view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
                }
                return true
            }
            if (event.keyCode in handledKeys) {
                if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) {
                    view.dispatchKeyEvent(event)
                }
                return true
            }
            return false
        }
        val handler: (AndroidEvent.KeyEvent) -> Boolean = ::handleKey
        PluviaApp.events.on<AndroidEvent.KeyEvent, Boolean>(handler)
        Timber.d("BusGamepadKeyBridge: listening")
        onDispose {
            PluviaApp.events.off<AndroidEvent.KeyEvent, Boolean>(handler)
        }
    }
}
