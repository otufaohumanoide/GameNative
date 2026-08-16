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
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import kotlinx.coroutines.delay

/**
 * Debug-only gamepad input harness (spec 2026-08-09 §testes): lets adb drive the FULL
 * input pipeline (Activity.dispatchKeyEvent/dispatchGenericMotionEvent → bus → XServerScreen
 * routing → Compose) without touching the physical controller, because MIUI blocks
 * `adb shell input` (INJECT_EVENTS denied).
 *
 * Protocol via the `debug.gamenative.input` system property (empty = idle):
 *   key:<keycode>            press + release (e.g. key:96 = BUTTON_A, key:110 = BUTTON_MODE/PS)
 *   key:<keycode>:down       press and hold (repeat events arrive like a held button)
 *   key:<keycode>:up         release
 *   scan:<scancode>[:down|up]
 *                            K4 (spec 2026-08-16-K4, §4.2): KeyEvent com keyCode
 *                            KEYCODE_UNKNOWN (0) + scanCode explícito — exercita o
 *                            alias de scanCode do DeviceQuirks (ex.: scan:704).
 *   stick:<x>:<y>            hold the left stick at x/y (e.g. stick:0:0.8 = down); repeated
 *                            ACTION_MOVE until `stick:0:0`
 *   hat:<x>:<y>              same for the D-pad hat
 *
 * V12 (spec 2026-08-14-gamepad-intuito-validacao-upgrades, V12 — verbos novos):
 *   touch:x:y                finger no touchpad em (x,y) normalizado [0..1] — ACTION_MOVE
 *                            com SOURCE_CLASS_POINTER (exercita o gate do ghost input
 *                            ANTES do consume: o forwarder do U2 lê no mesmo ponto)
 *   touchdown:x:y            finger-down (ACTION_DOWN)
 *   touchup:x:y              finger-up (ACTION_UP)
 *   touchtap                 touchdown + touchup (tap → clique esquerdo no jogo)
 *   gyro:x:y:z               amostra de giroscópio sintética (rad/s) — injetada DIRETO
 *                            no hub.onSensorSample (U1; sem sensor real no harness)
 *
 * NOTE (2026-08-12, spec pipeline-hardening): the PS/Home button is KEYCODE_BUTTON_MODE =
 * 110 — NOT 188 (KEYCODE_BUTTON_1, a generic pad button that neither the XServerScreen
 * nor the bus bridge recognizes). The DS4 keylayout maps BTN_MODE -> BUTTON_MODE (110).
 *
 * Example session (open menu, move down twice, press A, B):
 *   adb shell setprop debug.gamenative.input key:110
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

    // Baseline = the property value that already exists when the app starts. A stale
    // harness command left over from a previous session (e.g. "back") would otherwise
    // fire on the FIRST poll of every new session, making the QuickMenu open itself
    // at game start (2026-08-11: reproduced on-device — leftover `back:9` opened the
    // menu ~8 s after launch). Only commands set AFTER the harness starts fire.
    var lastCommand by remember { mutableStateOf(readInputProperty()) }
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

private fun readInputProperty(): String =
    DebugPropertyCache.read(INPUT_PROPERTY)

private const val INPUT_PROPERTY = "debug.gamenative.input"

private fun gamepadDeviceId(): Int? {
    val ids = InputDevice.getDeviceIds()
    val all = ids.toList().mapNotNull { id ->
        val device = InputDevice.getDevice(id) ?: return@mapNotNull null
        "id=$id name=${device.name} virtual=${device.isVirtual} src=0x" +
            Integer.toHexString(device.sources)
    }
    Log.d("DebugGamepad", "devices: ${all.joinToString(" | ")}")
    // Prefer the REAL controller, not peripheral sub-devices: a DS4 exposes three
    // devices ("Wireless Controller" + Touchpad + Motion Sensors) and the touchpad also
    // advertises GAMEPAD — but only the main controller has gamepad BUTTON keys, which
    // is what the app's routing (ExternalController.isGameController) actually accepts.
    // Score: +2 for gamepad source with button keys, +1 for joystick + axes.
    data class Candidate(val id: Int, val score: Int)
    val best = ids.toList().mapNotNull { id ->
        val device = InputDevice.getDevice(id) ?: return@mapNotNull null
        if (device.isVirtual) return@mapNotNull null
        var score = 0
        val hasGamepadKeys = device.hasKeys(
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y,
        ).any { it }
        if ((device.sources and InputDevice.SOURCE_GAMEPAD) != 0 && hasGamepadKeys) score += 2
        if ((device.sources and InputDevice.SOURCE_JOYSTICK) != 0 &&
            device.getMotionRange(MotionEvent.AXIS_X) != null
        ) score += 1
        if (score > 0) Candidate(id, score) else null
    }.maxByOrNull { it.score }
    return best?.id
}

private fun handleCommand(command: String, activity: Activity) {
    val parts = command.split(":")
    when (parts[0]) {
        // Doc pendentes-e-validacao-gamepad-universal (§1.1 ativar + §2 "sem o
        // controle físico"): MIUI bloqueia `adb input` — o toggle da Settings não é
        // alcançável por toque remoto. Verbo `pref:` com WHITELIST (nunca pref
        // arbitrário): pref:universal:1|0, pref:touchpadmouse:1|0, pref:rumble:1|0,
        // pref:layertick:1|0.
        "pref" -> {
            val name = parts.getOrNull(1)
            val value = parts.getOrNull(2) == "1"
            when (name) {
                "universal" -> {
                    PrefManager.gamepadUniversalEnabled = value
                    Log.d("DebugGamepad", "pref universal=$value")
                }
                "touchpadmouse" -> {
                    PrefManager.gamepadTouchpadMouseEnabled = value
                    Log.d("DebugGamepad", "pref touchpadmouse=$value")
                }
                "rumble" -> {
                    PrefManager.gamepadRumbleEnabled = value
                    Log.d("DebugGamepad", "pref rumble=$value")
                }
                "layertick" -> {
                    PrefManager.gamepadLayerTickEnabled = value
                    Log.d("DebugGamepad", "pref layertick=$value")
                }
                else -> Log.d("DebugGamepad", "pref unknown: $name")
            }
        }
        // F0 (spec 2026-08-15-input-core-avancado, V12+): dump agregado da medição de
        // latência (p50/p95 por fonte) no logcat — não depende do HUD estar visível,
        // só da coleta ligada via `debug.gamenative.latency 1`.
        // R7 (doc pendentes-e-validacao-gamepad-universal, §1.4): diagnóstico de
        // rumble por device — rumble:low:high:duration (0..1, ms). Chama o MESMO
        // contrato P2-5 do jogo/menu (GamepadHaptics.rumbleDevice).
        "rumble" -> {
            val low = parts.getOrNull(1)?.toFloatOrNull() ?: 0.5f
            val high = parts.getOrNull(2)?.toFloatOrNull() ?: 0.5f
            val duration = parts.getOrNull(3)?.toLongOrNull() ?: 200L
            val deviceId = gamepadDeviceId() ?: return
            val vibrated = GamepadHaptics.rumbleDevice(deviceId, low, high, duration)
            Log.d("DebugGamepad", "rumble dev=$deviceId low=$low high=$high dur=$duration -> $vibrated")
        }
        "latency" -> {
            when (parts.getOrNull(1)) {
                "report" -> Log.d("LatencyTracker", app.gamenative.gamepad.processing.LatencyTracker.report())
                "reset" -> {
                    app.gamenative.gamepad.processing.LatencyTracker.reset()
                    Log.d("LatencyTracker", "reset")
                }
                else -> Log.d("LatencyTracker", "usage: latency:report | latency:reset")
            }
        }
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
        // K4 (spec 2026-08-16-K4, §4.2): exercita o alias de scanCode do
        // DeviceQuirks — dispara um KeyEvent com keyCode KEYCODE_UNKNOWN (0) +
        // scanCode explícito, exatamente o caminho "sem .kl" que os quirks corrigem
        // (ex.: scan:704 = d-pad esquerdo cru). O verbo `key:` não leva scanCode
        // (protocolo congelado) — por isso o verbo próprio.
        "scan" -> {
            val scanCode = parts.getOrNull(1)?.toIntOrNull() ?: return
            val hold = parts.getOrNull(2)
            val deviceId = gamepadDeviceId() ?: 0
            Log.d("DebugGamepad", "scan $scanCode devId=$deviceId dev=${InputDevice.getDevice(deviceId)?.name} src=${InputDevice.SOURCE_GAMEPAD}")
            val now = SystemClock.uptimeMillis()
            fun keyEvent(action: Int) = KeyEvent(
                now, now, action, 0 /* KEYCODE_UNKNOWN */, 0, 0,
                deviceId, scanCode, 0, InputDevice.SOURCE_GAMEPAD,
            )
            when (hold) {
                "down" -> activity.dispatchKeyEvent(keyEvent(KeyEvent.ACTION_DOWN))
                "up" -> activity.dispatchKeyEvent(keyEvent(KeyEvent.ACTION_UP))
                else -> {
                    activity.dispatchKeyEvent(keyEvent(KeyEvent.ACTION_DOWN))
                    activity.dispatchKeyEvent(keyEvent(KeyEvent.ACTION_UP))
                }
            }
            Log.d("DebugGamepad", "scan $scanCode $hold")
        }
        "touch", "touchdown", "touchup" -> {
            val x = parts.getOrNull(1)?.toFloatOrNull() ?: 0.5f
            val y = parts.getOrNull(2)?.toFloatOrNull() ?: 0.5f
            val deviceId = gamepadDeviceId() ?: 0
            val now = SystemClock.uptimeMillis()
            val pointerProps = MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
            val pointerCoords = MotionEvent.PointerCoords().apply {
                this.x = x * 1000f
                this.y = y * 1000f
                setAxisValue(MotionEvent.AXIS_X, x)
                setAxisValue(MotionEvent.AXIS_Y, y)
            }
            val action = when (parts[0]) {
                "touchdown" -> MotionEvent.ACTION_DOWN
                "touchup" -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            val ev = MotionEvent.obtain(
                now, now, action, 1,
                arrayOf(pointerProps), arrayOf(pointerCoords),
                0, 0, 1f, 1f, deviceId, 0,
                // P5 (spec 2026-08-14-gamepad-upgrades-pendencias): fantasma = fonte
                // POINTER-class SEM classe JOYSTICK (dedo puro no touchpad). Fonte
                // só-POINTER = exatamente o que o gate consome E o forwarder do
                // touchpad→mouse (U2/V7) lê no mesmo ponto — caminho de ponta a ponta.
                InputDevice.SOURCE_CLASS_POINTER, 0,
            )
            activity.dispatchGenericMotionEvent(ev)
            Log.d("DebugGamepad", "motion ${parts[0]} $x $y")
        }
        "touchtap" -> {
            handleCommand("touchdown:0.5:0.5", activity)
            handleCommand("touchup:0.5:0.5", activity)
        }
        "gyro" -> {
            val x = parts.getOrNull(1)?.toFloatOrNull() ?: 0f
            val y = parts.getOrNull(2)?.toFloatOrNull() ?: 0f
            val z = parts.getOrNull(3)?.toFloatOrNull() ?: 0f
            val deviceId = gamepadDeviceId() ?: 0
            Log.d("DebugGamepad", "gyro $x $y $z devId=$deviceId")
            // V12 (U1): sem sensor real no harness — injeta direto no mesmo método que
            // o callback do sensor chama (GamepadHub.onSensorSample).
            PluviaApp.gamepadHub.onSensorSample(
                deviceId = deviceId,
                gyroX = x,
                gyroY = y,
                gyroZ = z,
                accelX = 0f,
                accelY = 0f,
                accelZ = 0f,
                nowMs = SystemClock.uptimeMillis(),
            )
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
