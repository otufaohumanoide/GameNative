package app.gamenative.ui.component

import app.gamenative.PluviaApp
import app.gamenative.PrefManager

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.InputDevice

/**
 * Haptics de gamepad por DEVICE (spec 2026-08-14-gamepad-u5-rumble, §1.1 — doc de
 * intuito U5): vibra o CONTROLE (não só o telefone). Restrições verificadas em
 * api-versions.xml: `InputDevice.getVibrator()` funciona desde API **16** (deprecado
 * em 31 — mas funcional em TODAS as configurações do fork); `getVibratorManager()`
 * é API 31+. Caminho: getVibrator() em todas as versões; getVibratorManager() como
 * fallback quando o device não expõe vibrator próprio (raro). Runtime guard + 
 * degradação silenciosa (V11 — sem vibrator = no-op).
 *
 * Efeitos de MENU por perfil (rumbleOnActivate/rumbleOnBack, §1.2): o bridge resolve
 * o perfil do device e silencia quando o usuário desligou. O rumble do JOGO (ponte
 * Wine/XInput → Vibrator) foi DIMENSIONADO no spec (§1.3) e é follow-up com spec
 * próprio.
 */
object GamepadHaptics {

    /** Efeitos de menu — padrões curtos (D4: sutil na ativação, menor no back). */
    enum class HapticEffect { ACTIVATE, BACK }

    fun isGamepadConnected(): Boolean = InputDevice.getDeviceIds().any { id ->
        val device = InputDevice.getDevice(id)
        device != null && (device.sources and InputDevice.SOURCE_GAMEPAD) != 0
    }

    /** Fallback legado: vibrator do SISTEMA (nenhum device identificado). */
    fun vibrate(context: Context, durationMs: Long = 18L) {
        if (!isGamepadConnected()) return
        val vibrator = systemVibrator(context) ?: return
        if (!vibrator.hasVibrator()) return
        vibrate(vibrator, durationMs)
    }

    /**
     * Vibra o DEVICE (U5). Respeita `PrefManager.gamepadRumbleEnabled` e o perfil do
     * device (rumbleOnActivate/rumbleOnBack — perfil vence global). DeviceId
     * desconhecido → fallback `vibrate(context)` (comportamento histórico).
     */
    fun vibrateDevice(context: Context, deviceId: Int, effect: HapticEffect) {
        if (!PrefManager.gamepadRumbleEnabled) return
        val hub = PluviaApp.gamepadHub
        val device = hub.deviceFor(deviceId)
        val profile = device?.let { hub.profileFor(deviceId, hub.activeAppId) }
        val enabled = when (effect) {
            HapticEffect.ACTIVATE -> profile?.rumbleOnActivate ?: true
            HapticEffect.BACK -> profile?.rumbleOnBack ?: true
        }
        if (!enabled) return
        if (device == null) {
            vibrate(context, if (effect == HapticEffect.ACTIVATE) 18L else 12L)
            return
        }
        val vibrator = deviceVibrator(deviceId) ?: return
        if (!vibrator.hasVibrator()) return
        val durationMs = if (effect == HapticEffect.ACTIVATE) 18L else 12L
        vibrate(vibrator, durationMs)
    }

    /** API 16+: vibrator DO device (deprecado em 31, mas funciona em tudo — V11). */
    private fun deviceVibrator(deviceId: Int): Vibrator? {
        val inputDevice = InputDevice.getDevice(deviceId) ?: return null
        return runCatching {
            @Suppress("DEPRECATION")
            inputDevice.vibrator.takeIf { it.hasVibrator() }
        }.getOrNull()
            ?: runCatching { vibratorManagerVibrator(deviceId) }.getOrNull()
    }

    /** Fallback API 31+: VibratorManager.getVibrator(id do device), quando existir. */
    private fun vibratorManagerVibrator(deviceId: Int): Vibrator? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val inputDevice = InputDevice.getDevice(deviceId) ?: return null
        val manager = inputDevice.vibratorManager ?: return null
        val ids = manager.vibratorIds
        if (ids == null || ids.isEmpty()) return null
        // O id do device nem sempre está na lista — primeiro match pelo próprio id,
        // senão o default (o vibrator do device costuma ser o único além do telefone).
        val vibrator = manager.getVibrator(deviceId)
        return vibrator.takeIf { it.hasVibrator() }
            ?: manager.defaultVibrator.takeIf { it.hasVibrator() && manager.vibratorIds.size == 1 }
    }

    private fun systemVibrator(context: Context): Vibrator? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            context.getSystemService(Vibrator::class.java)
        }
        else -> {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun vibrate(vibrator: Vibrator, durationMs: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE),
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs)
        }
    }
}
