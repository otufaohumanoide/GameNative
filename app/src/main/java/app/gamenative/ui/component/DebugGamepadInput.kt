package app.gamenative.ui.component

import android.app.Activity
import androidx.activity.ComponentActivity
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.gamenative.BuildConfig
import kotlinx.coroutines.delay

/**
 * Debug-only gamepad input harness (spec 2026-08-09 §testes): lets adb drive the FULL
 * input pipeline (Activity.dispatchKeyEvent/dispatchGenericMotionEvent → bus → XServerScreen
 * routing → Compose) without touching the physical controller, because MIUI blocks
 * `adb shell input` (INJECT_EVENTS denied).
 *
 * Protocol via the `debug.gamenative.input` system property (empty = idle):
 *   key:<keycode>            press + release (e.g. key:96 = BUTTON_A, key:188 = BUTTON_MODE/PS)
 *   key:<keycode>:down       press and hold (repeat events arrive like a held button)
 *   key:<keycode>:up         release
 *   stick:<x>:<y>            hold the left stick at x/y (e.g. stick:0:0.8 = down); repeated
 *                            ACTION_MOVE until `stick:0:0`
 *   hat:<x>:<y>              same for the D-pad hat
 *
 * Example session (open menu, move down twice, press A, B):
 *   adb shell setprop debug.gamenative.input key:188
 *   adb shell setprop debug.gamenative.input stick:0:0.8 ; sleep 0.5 ; setprop ... stick:0:0
 *   ... (repeat) ; adb shell setprop debug.gamenative.input key:96
 *   adb shell setprop debug.gamenative.input key:97   (BUTTON_B)
 *
 * Events are dispatched with the first connected SOURCE_GAMEPAD device so the routing in
 * XServerScreen treats them as a real controller (OVERLAY → Compose).
 */
@Composable
fun DebugGamepadInputHarness(enabled: Boolean) {
    if (!BuildConfig.DEBUG) return
    val context = LocalContext.current
    val activity = context as? Activity
        ?: (app.gamenative.PluviaApp.xServerView?.context as? Activity)
    if (activity == null) return

    var lastCommand by remember { mutableStateOf("") }
    LaunchedEffect(enabled) {
        while (enabled) {
            val command = readInputProperty()
            if (command.isNotEmpty() && command != lastCommand) {
                lastCommand = command
                runCatching { handleCommand(command, activity) }
                    .onFailure { Log.w("DebugGamepad", "command failed: $command", it) }
            } else if (command.isEmpty()) {
                lastCommand = ""
            }
            delay(200)
        }
    }
}

private fun readInputProperty(): String = try {
    val process = Runtime.getRuntime().exec(arrayOf("getprop", "debug.gamenative.input"))
    process.inputStream.bufferedReader().use { it.readText().trim() }
} catch (_: Throwable) {
    ""
}

private fun gamepadDeviceId(): Int? {
    val ids = InputDevice.getDeviceIds()
    val all = ids.toList().mapNotNull { id ->
        val device = InputDevice.getDevice(id) ?: return@mapNotNull null
        "id=$id name=${device.name} virtual=${device.isVirtual} src=0x" +
            Integer.toHexString(device.sources)
    }
    Log.d("DebugGamepad", "devices: ${all.joinToString(" | ")}")
    // Exclude virtual devices: the app's controller routing rejects them
    // (ExternalController.isGameController), and Android's virtual device (id -1)
    // also advertises SOURCE_GAMEPAD. The fingerprint reader (uinput-fpc) also
    // advertises GAMEPAD; a real controller uniquely has GAMEPAD + JOYSTICK + axes.
    return ids.firstOrNull { id ->
        val device = InputDevice.getDevice(id)
        device != null && !device.isVirtual &&
            (device.sources and InputDevice.SOURCE_GAMEPAD) != 0 &&
            (device.sources and InputDevice.SOURCE_JOYSTICK) != 0 &&
            device.getMotionRange(MotionEvent.AXIS_X) != null
    }
}

private fun handleCommand(command: String, activity: Activity) {
    val parts = command.split(":")
    when (parts[0]) {
        "back" -> {
            // Debug-only menu toggle: drives the OnBackPressedDispatcher directly (the
            // BackHandler XServerScreen registers calls gameBack), so the QuickMenu can be
            // opened/closed without a physical gamepad device (which the controller routing
            // would otherwise require).
            (activity as? ComponentActivity)?.onBackPressedDispatcher?.onBackPressed()
            Log.d("DebugGamepad", "back dispatched")
        }
        "key" -> {
            val keyCode = parts.getOrNull(1)?.toIntOrNull() ?: return
            val hold = parts.getOrNull(2)
            val deviceId = gamepadDeviceId() ?: 0
            Log.d("DebugGamepad", "key $keyCode devId=$deviceId dev=${InputDevice.getDevice(deviceId)?.name} src=${InputDevice.SOURCE_GAMEPAD}")
            val now = SystemClock.uptimeMillis()
            when (hold) {
                "down" -> {
                    activity.dispatchKeyEvent(
                        KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0, deviceId, 0, 0, InputDevice.SOURCE_GAMEPAD)
                    )
                }
                "up" -> {
                    activity.dispatchKeyEvent(
                        KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, 0, deviceId, 0, 0, InputDevice.SOURCE_GAMEPAD)
                    )
                }
                else -> {
                    activity.dispatchKeyEvent(
                        KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0, deviceId, 0, 0, InputDevice.SOURCE_GAMEPAD)
                    )
                    activity.dispatchKeyEvent(
                        KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, 0, deviceId, 0, 0, InputDevice.SOURCE_GAMEPAD)
                    )
                }
            }
            Log.d("DebugGamepad", "key $keyCode $hold")
        }
        "stick", "hat" -> {
            val x = parts.getOrNull(1)?.toFloatOrNull() ?: return
            val y = parts.getOrNull(2)?.toFloatOrNull() ?: return
            // deviceId 0 is fine for the bus-level navigator (it only inspects the EVENT
            // source + axis values); XServerScreen skips non-gamepad devices.
            val deviceId = gamepadDeviceId() ?: 0
            val now = SystemClock.uptimeMillis()
            val pointerProps = MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
            val pointerCoords = MotionEvent.PointerCoords().apply {
                this.x = 0f
                this.y = 0f
                if (parts[0] == "stick") {
                    setAxisValue(MotionEvent.AXIS_X, x)
                    setAxisValue(MotionEvent.AXIS_Y, y)
                    setAxisValue(MotionEvent.AXIS_HAT_X, 0f)
                    setAxisValue(MotionEvent.AXIS_HAT_Y, 0f)
                } else {
                    setAxisValue(MotionEvent.AXIS_HAT_X, x)
                    setAxisValue(MotionEvent.AXIS_HAT_Y, y)
                    setAxisValue(MotionEvent.AXIS_X, 0f)
                    setAxisValue(MotionEvent.AXIS_Y, 0f)
                }
            }
            val ev = MotionEvent.obtain(
                now, now, MotionEvent.ACTION_MOVE, 1,
                arrayOf(pointerProps), arrayOf(pointerCoords),
                0, 0, 1f, 1f, deviceId, 0,
                InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_GAMEPAD, 0,
            )
            activity.dispatchGenericMotionEvent(ev)
            Log.d("DebugGamepad", "motion ${parts[0]} $x $y")
        }
    }
}
