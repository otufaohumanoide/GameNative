package app.gamenative.ui.component

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView

/**
 * Converts gamepad analog-stick (AXIS_X/AXIS_Y) and hat-switch D-pad (AXIS_HAT_X/AXIS_HAT_Y)
 * axis motion into Compose focus navigation.
 *
 * Compose's focus system only reacts to key events (KEYCODE_DPAD_*). Android gamepads report
 * the left stick and the D-pad hat as *axis motion* in onGenericMotionEvent, which Compose
 * ignores — so joystick users cannot move focus in menus. This composable installs an
 * [View.OnGenericMotionListener] on the host view while [enabled] and moves focus once per
 * [cooldownMs] once an axis crosses [deadZone] (holding the stick scrolls steadily, never
 * free-runs). The event is consumed only when a movement is actually issued.
 *
 * Spec: docs/superpowers/specs/2026-08-08-dpad-shader-navigation-design.md
 */
@Composable
fun JoystickFocusNavigator(
    enabled: Boolean,
    deadZone: Float = 0.45f,
    releaseZone: Float = 0.30f,
    cooldownMs: Long = 180L,
) {
    val focusManager = LocalFocusManager.current
    val view = LocalView.current
    DisposableEffect(enabled, view) {
        if (!enabled) return@DisposableEffect onDispose {}
        var lastMoveAt = 0L
        // Hysteresis (P3-20): after issuing a move, the stick must return below releaseZone
        // before another move is accepted — a stick resting near the dead-zone edge cannot
        // produce phantom movement.
        var armed = true
        val listener = View.OnGenericMotionListener { _, ev ->
            if (ev.actionMasked != MotionEvent.ACTION_MOVE) return@OnGenericMotionListener false
            val isGamepad = (ev.source and InputDevice.SOURCE_JOYSTICK) != 0 ||
                (ev.source and InputDevice.SOURCE_DPAD) != 0
            if (!isGamepad) return@OnGenericMotionListener false
            val stickX = ev.getAxisValue(MotionEvent.AXIS_X)
            val stickY = ev.getAxisValue(MotionEvent.AXIS_Y)
            val hatX = ev.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hatY = ev.getAxisValue(MotionEvent.AXIS_HAT_Y)
            val magnitude = kotlin.math.max(
                kotlin.math.max(kotlin.math.abs(stickX), kotlin.math.abs(stickY)),
                kotlin.math.max(kotlin.math.abs(hatX), kotlin.math.abs(hatY)),
            )
            if (!armed) {
                if (magnitude < releaseZone) armed = true
                return@OnGenericMotionListener true
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
            if (direction == null) return@OnGenericMotionListener false
            if (now - lastMoveAt < cooldownMs) return@OnGenericMotionListener true
            lastMoveAt = now
            armed = false
            focusManager.moveFocus(direction)
            true
        }
        view.setOnGenericMotionListener(listener)
        onDispose {
            view.setOnGenericMotionListener(null)
        }
    }
}
