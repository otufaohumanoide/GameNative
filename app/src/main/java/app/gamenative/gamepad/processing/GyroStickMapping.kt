package app.gamenative.gamepad.processing

/**
 * Mapeamento velocidade angular → deflexão de stick do CAMERA mode (P1-2 do spec
 * 2026-08-14-gamepad-upgrades-pendencias): padrão DS4Windows `MouseJoystick` /
 * JoyShockLibrary `GyroMouseStick` — o gyro em modo stick é CONTROLE DE TAXA, não
 * integral. A deflexão é FUNÇÃO da velocidade angular (rad/s): parar de girar ⇒
 * deflexão volta a 0 automaticamente (nada a re-centrar).
 *
 * G4 (spec 2026-08-16-G-gyro-v2, §1): shaping por perfil — [maxOutput] (teto da
 * deflexão) e [antiDeadzone] (floor logo acima da deadzone), semântica
 * `SixMouseStick` (reference/DS4Windows/DS4Windows/DS4Control/Mouse.cs): a
 * velocidade morta pela deadzone remapeia o intervalo (dz..1] de deflexão linear
 * para (antiDeadzone..maxOutput]. Defaults (1.0, 0) = linear atual, byte-identical.
 *
 * JVM-testável (V5): nenhum android.* — clamp, sinal, retorno a zero e a curva de
 * shaping testados em [GyroStickMappingTest].
 */
object GyroStickMapping {

    /**
     * Escala default: 1.0 de deflexão com ~5 rad/s (~286°/s) e sensibilidade 1.0 —
     * dentro da faixa "máx deflexão em torno de 180–360°/s" da convenção JoyShock
     * (sensibilidade ajustável por perfil multiplica a entrada).
     */
    const val DEFAULT_SCALE: Float = 0.2f

    /** G4: teto default da deflexão (1.0 = deflexão completa). */
    const val DEFAULT_MAX_OUTPUT: Float = 1.0f

    /** G4: floor default acima da deadzone (0 = sem salto — linear atual). */
    const val DEFAULT_ANTI_DEADZONE: Float = 0.0f

    /**
     * Deflexão de um eixo do stick a partir da velocidade angular já morta pela
     * deadzone (rad/s). Sinal preservado; clamp [-1, 1].
     *
     * G4: com [maxOutput] < 1 a deflexão satura mais cedo (teto); com
     * [antiDeadzone] > 0 a menor velocidade acima da deadzone já produz uma
     * deflexão mínima (floor — salto imediato de stick, padrão SixMouseStick).
     * A curva é o remap afim (antiDeadzone..maxOutput) sobre a deflexão linear
     * (0..1] — defaults (1.0, 0) reproduzem o linear antigo EXATO.
     */
    fun deflection(
        angularVelRadS: Float,
        sensitivity: Float,
        scale: Float = DEFAULT_SCALE,
        maxOutput: Float = DEFAULT_MAX_OUTPUT,
        antiDeadzone: Float = DEFAULT_ANTI_DEADZONE,
    ): Float {
        val linear = (angularVelRadS * scale * sensitivity).coerceIn(-1f, 1f)
        if (linear == 0f) return 0f
        val maxOut = maxOutput.coerceIn(0f, 1f)
        // Correção G-v2-revisão: anti > maxOut invertia a resposta (mais rotação =
        // menos deflexão — não-monotônico). O clamp mantém a curva sempre
        // monotônica, mesmo com JSON importado contendo anti > maxOutput (a UI não
        // é a única porta de entrada).
        val anti = antiDeadzone.coerceIn(0f, 1f).coerceAtMost(maxOut)
        // (0..1] → (anti..maxOut]: floor = anti no menor movimento; teto = maxOut.
        val magnitude = anti + (maxOut - anti) * kotlin.math.abs(linear)
        return kotlin.math.sign(linear) * magnitude.coerceIn(0f, 1f)
    }
}
