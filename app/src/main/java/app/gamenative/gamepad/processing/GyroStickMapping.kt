package app.gamenative.gamepad.processing

/**
 * Mapeamento velocidade angular → deflexão de stick do CAMERA mode (P1-2 do spec
 * 2026-08-14-gamepad-upgrades-pendencias): padrão DS4Windows `MouseJoystick` /
 * JoyShockLibrary `GyroMouseStick` — o gyro em modo stick é CONTROLE DE TAXA, não
 * integral. A deflexão é FUNÇÃO da velocidade angular (rad/s): parar de girar ⇒
 * deflexão volta a 0 automaticamente (nada a re-centrar).
 *
 * JVM-testável (V5): nenhum android.* — clamp, sinal e retorno a zero testados em
 * [GyroStickMappingTest].
 */
object GyroStickMapping {

    /**
     * Escala default: 1.0 de deflexão com ~5 rad/s (~286°/s) e sensibilidade 1.0 —
     * dentro da faixa "máx deflexão em torno de 180–360°/s" da convenção JoyShock
     * (sensibilidade ajustável por perfil multiplica a entrada).
     */
    const val DEFAULT_SCALE: Float = 0.2f

    /**
     * Deflexão de um eixo do stick a partir da velocidade angular já morta pela
     * deadzone (rad/s). Sinal preservado; clamp [-1, 1].
     */
    fun deflection(angularVelRadS: Float, sensitivity: Float, scale: Float = DEFAULT_SCALE): Float =
        (angularVelRadS * scale * sensitivity).coerceIn(-1f, 1f)
}
