package app.gamenative.gamepad.processing

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Curva do rumble de TELEFONE (spec 2026-08-16-A-rumble-fallback-usage-media, §1.3):
 * a curva exata do GameNative ORIGINAL (`getPhoneRumbleAmplitude`, WinHandler.java do
 * commit `2c184243^`:843-850 — `norm = amplitude/255; curved = norm^0.6;
 * amp = curved*255; amp<=1 → 0; clamp 255`), restaurada como fallback quando o
 * controle não expõe vibrator (decisão nº 5 do impl doc do input core — "reversível
 * se o campo reclamar"; o campo reclamou).
 *
 * Objeto PURO (zero android.*) — testável em JVM, pacote `gamepad/processing`
 * (invariante do master roadmap 2026-08-16, §2).
 */
object RumblePhoneCurve {

    /** Destino efetivo de uma vibração (spec A §1.4) — decisão pura compartilhada. */
    enum class RumbleTarget { CONTROLLER, PHONE, NONE }

    /**
     * mix 0..1 → amplitude 0..255 com curva pow 0.6 (Winlator original) e clamp.
     * `amp <= 1 → 0` (padrão SDL: amplitude < 1 = cancel). Mix negativo/maior que 1
     * é clampeado ANTES da curva (defensivo — a entrada real já vem de
     * [app.gamenative.ui.component.GamepadHaptics.mixIntensity], clamp 0..1).
     */
    fun amplitudeFor(mix: Float): Int {
        if (mix <= 0f) return 0
        val curved = mix.coerceIn(0f, 1f).pow(0.6f)
        val amplitude = (curved * 255f).roundToInt()
        return if (amplitude <= 1) 0 else amplitude.coerceAtMost(255)
    }

    /**
     * Decisão pura do destino (§1.4) — a MESMA lógica do fallback de 1.1, extraída
     * para função testável/compartilhada: CONTROLLER quando o device expõe vibrator;
     * senão PHONE quando o fallback está ligado (`gamepadPhoneRumbleFallback`);
     * senão NONE (no-op silencioso, V11).
     */
    fun rumbleTargetFor(hasDeviceVibrators: Boolean, phoneFallbackEnabled: Boolean): RumbleTarget =
        when {
            hasDeviceVibrators -> RumbleTarget.CONTROLLER
            phoneFallbackEnabled -> RumbleTarget.PHONE
            else -> RumbleTarget.NONE
        }
}
