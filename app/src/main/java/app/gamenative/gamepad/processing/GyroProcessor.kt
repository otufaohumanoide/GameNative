package app.gamenative.gamepad.processing

/**
 * Processador PURO do gyro (spec 2026-08-14-gamepad-u1-gyro, §1.3 — doc de intuito
 * U1): amostras de giroscópio (rad/s, eixos Android: X lateral-direita, Y frontal,
 * Z vertical) → deltas de rotação por amostra, com recenter explícito, ativação por
 * botão (padrão DS4Windows) e deadzone angular com histerese.
 *
 * JVM-testável (V5): nenhum android.*; o estado entre amostras ([GyroState]) vive no
 * hub keyed por deviceId e morre no `removeDevice` (V6).
 *
 * Semântica:
 * - Recenter: na BORDA de ativação (off→on) o offset atual vira zero (drift é
 *   inerente — decisão do intuito: recenter explícito, sem auto-calibração contínua).
 * - Ativação: [activate] vem do botão do perfil (hold) ou true quando sempre ativo.
 * - Deadzone angular com histerese: acima de `deadzone*1.2` passa a valer; abaixo de
 *   `deadzone*0.8` zera (evita tremulação na fronteira).
 * - Sinais: yaw (rotação no plano) → deltaX; pitch (inclinação) → deltaY. O sinal
 *   segue a convenção "girar para a direita = deltaX positivo, inclinar para cima =
 *   deltaY negativo" (verificação on-device pode inverter — anotado no spec).
 */
data class GyroSample(
    val gyroX: Float,
    val gyroY: Float,
    val gyroZ: Float,
    val nowMs: Long,
)

data class GyroConfig(
    /** Deadzone angular, rad/s (default 0.05 — ~3°/s). */
    val deadzone: Float = 0.05f,
)

/** Estado entre amostras — UMA instância por device, morta no removeDevice (V6). */
class GyroState {
    var active: Boolean = false
    var aboveDeadzone: Boolean = false
    var offsetX: Float = 0f
    var offsetY: Float = 0f
    var offsetZ: Float = 0f
    var lastSample: GyroSample? = null
}

data class GyroOutput(
    /** Rotação acumulada desde a última amostra, em RADIANOS (yaw → deltaX). */
    val deltaXRad: Float,
    /** Rotação acumulada desde a última amostra, em RADIANOS (pitch → deltaY). */
    val deltaYRad: Float,
    /** false = inativo (botão solto) ou primeira amostra (sem delta ainda). */
    val active: Boolean,
) {
    companion object {
        val NONE = GyroOutput(0f, 0f, false)
    }
}

object GyroProcessor {

    fun process(sample: GyroSample, state: GyroState, config: GyroConfig, activate: Boolean): GyroOutput {
        // Recenter na borda de ativação (off→on): o desvio atual vira zero — padrão
        // DS4Windows ("recenter a cada ativação").
        if (activate && !state.active) {
            state.offsetX = sample.gyroX
            state.offsetY = sample.gyroY
            state.offsetZ = sample.gyroZ
        }
        state.active = activate
        if (!activate) {
            state.lastSample = null
            state.aboveDeadzone = false
            return GyroOutput.NONE
        }
        val last = state.lastSample
        state.lastSample = sample
        if (last == null) {
            // Primeira amostra do período ativo: ancora, sem delta.
            return GyroOutput(0f, 0f, true)
        }

        val dt = ((sample.nowMs - last.nowMs).coerceIn(1L, 100L)) / 1000f
        var yaw = -(sample.gyroZ - state.offsetZ) // girar à direita = -Z → +deltaX
        var pitch = -(sample.gyroX - state.offsetX) // inclinar para cima = -X → -deltaY
        // Histerese correta (P1-4 do spec 2026-08-14-gamepad-upgrades-pendencias):
        // entrada 1.2× / saída 0.8× — acima de deadzone*1.2 passa a valer; abaixo de
        // deadzone*0.8 zera. O estado usa o MESMO threshold aplicado (o deadzone cru
        // desincronizava o limiar e invertia o comportamento na banda 0.8×–1.2×).
        val threshold = if (state.aboveDeadzone) config.deadzone * 0.8f else config.deadzone * 1.2f
        yaw = if (kotlin.math.abs(yaw) < threshold) 0f else yaw
        pitch = if (kotlin.math.abs(pitch) < threshold) 0f else pitch
        state.aboveDeadzone = kotlin.math.abs(yaw) >= threshold ||
            kotlin.math.abs(pitch) >= threshold
        return GyroOutput(
            deltaXRad = yaw * dt,
            deltaYRad = pitch * dt,
            active = true,
        )
    }
}
