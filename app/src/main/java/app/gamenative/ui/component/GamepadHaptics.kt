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
 * Wine/XInput → Vibrator) foi DIMENSIONADO no spec (§1.3) e ganhou contrato no P2-5
 * (spec 2026-08-14-gamepad-upgrades-pendencias): [rumbleDevice] é a assinatura única
 * low/high/duration/cancel que a ponte futura traduz — zero retrabalho.
 */
object GamepadHaptics {

    /** Efeitos de menu — padrões curtos (D4: sutil na ativação, menor no back). */
    enum class HapticEffect { ACTIVATE, BACK, LAYER_TICK }

    /**
     * P2-5: mix SDL para device de 1 motor — `low*0.6 + high*0.4` (clamp 0..1).
     * Pura (testada em JVM).
     */
    fun mixIntensity(low: Float, high: Float): Float =
        (low * 0.6f + high * 0.4f).coerceIn(0f, 1f)

    /**
     * P2-5: amplitude do one-shot — `round(intensity*255)` clamp ≤255; 0 = cancel
     * (intensidade <= 0 OU round < 1 ⇒ vibrator.cancel(), padrão SDL exato:
     * `value < 1 → vibrator.cancel()`). Pura (testada em JVM).
     */
    fun amplitudeFor(intensity: Float): Int {
        if (intensity <= 0f) return 0
        val value = kotlin.math.round(intensity * 255f).toInt()
        return if (value < 1) 0 else value.coerceAtMost(255)
    }

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
     * P2-5: os efeitos de menu passam pelo MESMO contrato do rumble do jogo
     * ([rumbleDevice]) — um único ponto de vibração.
     */
    fun vibrateDevice(context: Context, deviceId: Int, effect: HapticEffect) {
        if (!PrefManager.gamepadRumbleEnabled) return
        val hub = PluviaApp.gamepadHub
        val device = hub.deviceFor(deviceId)
        val profile = device?.let { hub.profileFor(deviceId, hub.activeAppId) }
        val enabled = when (effect) {
            HapticEffect.ACTIVATE -> profile?.rumbleOnActivate ?: true
            HapticEffect.BACK -> profile?.rumbleOnBack ?: true
            // F2.3: tick de camada não tem gate de perfil (global apenas) — nunca
            // passa por aqui (ver [tickDevice]), mas o when precisa ser exaustivo.
            HapticEffect.LAYER_TICK -> true
        }
        if (!enabled) return
        if (device == null) {
            vibrate(context, if (effect == HapticEffect.ACTIVATE) 18L else 12L)
            return
        }
        val (low, high) = when (effect) {
            HapticEffect.ACTIVATE -> 0.4f to 0.2f
            HapticEffect.BACK -> 0.3f to 0.15f
            HapticEffect.LAYER_TICK -> 0.4f to 0.2f
        }
        val durationMs = if (effect == HapticEffect.ACTIVATE) 18L else 12L
        rumbleDevice(deviceId, low, high, durationMs)
    }

    /**
     * F2.3 (spec 2026-08-15-input-core-avancado): tick háptico na ativação de camada
     * (U3) e no setor do radial menu — `EFFECT_CLICK` (API 30+; fallback one-shot
     * 10 ms). Gate: `gamepadRumbleEnabled` guarda TUDO + toggle dedicado
     * `gamepadLayerTickEnabled` (SettingsGroupGamepad). Device sem vibrator =
     * no-op silencioso (V11 — nunca vibra o telefone por tick).
     */
    fun tickDevice(deviceId: Int) {
        if (!PrefManager.gamepadRumbleEnabled) return
        if (!PrefManager.gamepadLayerTickEnabled) return
        // Log ANTES da checagem de vibrators: evidência do CALL (ativação de camada/
        // setor) mesmo quando o device não expõe vibrator (ex.: DS4 via USB).
        timber.log.Timber.d("GamepadHaptics: LAYER_TICK device=%d", deviceId)
        val vibrators = deviceVibrators(deviceId) ?: return
        if (vibrators.isEmpty()) return
        for (vibrator in vibrators) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching {
                    vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                }.onFailure {
                    vibrate(vibrator, LAYER_TICK_FALLBACK_MS)
                }
            } else {
                vibrate(vibrator, LAYER_TICK_FALLBACK_MS)
            }
        }
    }

    /**
     * P2-5 (spec 2026-08-14-gamepad-upgrades-pendencias): contrato de rumble do JOGO —
     * assinatura única `(deviceId, low 0..1, high 0..1, durationMs)` (padrão SDL
     * `rumble(device_id, low, high, duration_ms)`):
     * - Device com **≥2 vibrators**: motor 0 = low, motor 1 = high (DualSense expõe 2);
     * - Device com **1 vibrator**: mix `low*0.6 + high*0.4`;
     * - `low == high == 0` ⇒ `vibrator.cancel()` (parar é parte do contrato);
     * - Amplitude: [amplitudeFor] (clamp 1..255; <1 = cancel); try/catch com fallback
     *   para o one-shot de amplitude default (API <26/defensivo, padrão SDL).
     *
     * `gamepadRumbleEnabled` guarda TUDO (efeitos de menu E jogo — a ponte
     * Wine/XInput futura só traduz low/high/duration para esta função).
     */
    fun rumbleDevice(deviceId: Int, low: Float, high: Float, durationMs: Long): Boolean {
        if (!PrefManager.gamepadRumbleEnabled) return false
        val vibrators = deviceVibrators(deviceId) ?: return false
        if (vibrators.isEmpty()) return false
        if (low <= 0f && high <= 0f) {
            // Jogos mandam rumble contínuo com durações longas e depois cancelam.
            vibrators.forEach { runCatching { it.cancel() } }
            return false
        }
        if (vibrators.size >= 2) {
            vibrateWithAmplitude(vibrators[0], low, durationMs)
            vibrateWithAmplitude(vibrators[1], high, durationMs)
        } else {
            vibrateWithAmplitude(vibrators[0], mixIntensity(low, high), durationMs)
        }
        // Limpeza 1.3-4: retorno REAL — true = vibração de fato disparada (o
        // WinHandler usa para precisar o isRumbling; cancel/gate-off/sem-vibrator = false).
        return true
    }

    /**
     * P2-5: vibrators DO device — API 31+ via VibratorManager (ids individuais;
     * DualSense expõe 2); fallback para o `getVibrator()` legado (API 16+, funcional
     * em tudo). null = sem vibrator.
     */
    private fun deviceVibrators(deviceId: Int): List<Vibrator>? {
        val inputDevice = InputDevice.getDevice(deviceId) ?: return null
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = inputDevice.vibratorManager
                val ids = manager?.vibratorIds
                if (ids != null && ids.isNotEmpty()) {
                    ids.map { id -> manager.getVibrator(id).takeIf { it.hasVibrator() } }
                        .filterNotNull()
                        .ifEmpty { legacyVibrators(inputDevice) }
                } else {
                    legacyVibrators(inputDevice)
                }
            } else {
                legacyVibrators(inputDevice)
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    @Suppress("DEPRECATION")
    private fun legacyVibrators(inputDevice: InputDevice): List<Vibrator> {
        val vibrator = inputDevice.vibrator
        return if (vibrator.hasVibrator()) listOf(vibrator) else emptyList()
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

    /** Fallback do tick de camada em API < 30 (one-shot curto). */
    private const val LAYER_TICK_FALLBACK_MS = 10L

    /** P2-5: one-shot com amplitude 0..255; 0 = cancel; fallback defensivo (SDL). */
    private fun vibrateWithAmplitude(vibrator: Vibrator, intensity: Float, durationMs: Long) {
        val amplitude = amplitudeFor(intensity)
        if (amplitude == 0) {
            runCatching { vibrator.cancel() }
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
            }.onFailure {
                runCatching {
                    vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
                }
            }
        } else {
            @Suppress("DEPRECATION")
            runCatching { vibrator.vibrate(durationMs) }
        }
    }
}
