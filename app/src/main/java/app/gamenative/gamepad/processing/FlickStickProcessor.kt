package app.gamenative.gamepad.processing

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Flick Stick do stick DIREITO (F1.2 do spec 2026-08-15-input-core-avancado) — padrão
 * JoyShockLibrary adaptado à saída de TAXA do pipeline existente: o processador é
 * PURO e emite `yawRadS` (rad/s), a MESMA unidade do CAMERA mode do gyro (U1/P1-2) —
 * o consumidor é o `applyCameraGyro` (deflexão = f(velocidade), parar de girar ⇒
 * deflexão volta a 0 automaticamente).
 *
 * Semântica:
 * - Deflexão radial < [FlickStickConfig.activationRadius] (0.85): nenhuma saída
 *   (dead band) — o resto do stick físico não vaza para o jogo.
 * - Deflexão ≥ activationRadius (HOLD): taxa contínua proporcional à sobra acima do
 *   limiar, direção = ângulo do stick (cos(ângulo): apontar à direita = yaw +,
 *   à esquerda = yaw −; vertical puro = 0 — pitch de Flick Stick é follow-up, o
 *   contrato de saída é só yaw).
 * - FLICK: deflexão acima do limiar seguida de retorno abaixo dentro de
 *   [FlickStickConfig.flickWindowMs], COM percurso angular ≥ snapAngle (15°) — emite
 *   um burst de [FlickStickConfig.flickBurstMs] na direção do flick (yaw instantâneo).
 * - snapAngle (15°): descarta micro-giros acidentais — no HOLD, variações angulares
 *   acumuladas abaixo do snap NÃO movem a direção de saída (estabilidade contra
 *   jitter); o flick reto (empurra-e-solta sem girar) é um gesto legítimo e NÃO é
 *   suprimido pelo snap.
 */
data class FlickStickConfig(
    /** Deflexão radial mínima para o stick virar flick (0..1). */
    val activationRadius: Float = 0.85f,
    /** Percurso angular mínimo (graus) para considerar giro intencional. */
    val snapAngleDeg: Float = 15f,
    /**
     * Taxa máxima de yaw (rad/s). Default 5.0 = ponto de saturação do
     * GyroStickMapping (0.2 × 5.0 = deflexão 1.0 com sensibilidade 1).
     */
    val maxYawRadS: Float = 5f,
    /** Janela (ms) para um hold curto virar FLICK (release antes disso = flick). */
    val flickWindowMs: Long = 250L,
    /** Duração do burst de flick (ms) — yaw instantâneo no modelo de taxa. */
    val flickBurstMs: Long = 120L,
)

/** Estado por device (V6 — morre no removeDevice do hub). */
class FlickStickState {
    var active: Boolean = false
    var holdStartMs: Long = 0L
    /** Direção de saída corrente (rad, atan2) — snapAngle a protege de micro-giros. */
    var directionAngle: Float? = null
    var travelDeg: Float = 0f
    var lastAngle: Float? = null
    var burstUntilMs: Long = 0L
    var burstYawRadS: Float = 0f
}

data class FlickStickOutput(
    /** Velocidade angular de yaw (rad/s) — mesma unidade do CAMERA mode (U1). */
    val yawRadS: Float,
) {
    companion object {
        val NONE = FlickStickOutput(0f)
    }
}

object FlickStickProcessor {

    fun process(
        sample: StickSample,
        nowMs: Long,
        state: FlickStickState,
        config: FlickStickConfig,
    ): FlickStickOutput {
        // Burst em andamento dona a saída até acabar (o flick é um gesto atômico).
        if (nowMs < state.burstUntilMs) return FlickStickOutput(state.burstYawRadS)

        val magnitude = hypot(sample.x, sample.y)
        val angle = atan2(sample.y, sample.x) // rad; 0 = direita, +π/2 = cima

        if (magnitude >= config.activationRadius) {
            if (!state.active) {
                // Borda de ativação: ancora direção e zera o percurso.
                state.active = true
                state.holdStartMs = nowMs
                state.directionAngle = angle
                state.travelDeg = 0f
                state.lastAngle = angle
            } else {
                val delta = angularDiffDeg(angle, state.lastAngle ?: angle)
                state.travelDeg += delta
                state.lastAngle = angle
                // snapAngle: percurso acumulado abaixo do snap não move a direção —
                // micro-giros acidentais em volta do ângulo atual são descartados.
                if (state.travelDeg >= config.snapAngleDeg) {
                    state.directionAngle = angle
                }
            }
            val strength = ((magnitude - config.activationRadius) / (1f - config.activationRadius))
                .coerceIn(0f, 1f)
            val direction = state.directionAngle ?: angle
            return FlickStickOutput(directionYaw(direction, config) * strength)
        }

        // Abaixo do limiar: release do hold.
        if (state.active) {
            val heldMs = nowMs - state.holdStartMs
            // FLICK = excursão rápida acima do limiar seguida de release na janela.
            // Um flick RETO (empurra-e-solta sem girar) É um flick legítimo — o
            // snapAngle protege a DIREÇÃO no hold contínuo, não o gesto do flick.
            val wasFlick = heldMs <= config.flickWindowMs
            val flickDirection = state.directionAngle
            state.active = false
            state.travelDeg = 0f
            state.lastAngle = null
            state.directionAngle = null
            if (wasFlick && flickDirection != null) {
                state.burstUntilMs = nowMs + config.flickBurstMs
                state.burstYawRadS = directionYaw(flickDirection, config)
                return FlickStickOutput(state.burstYawRadS)
            }
        }
        return FlickStickOutput.NONE
    }

    /**
     * Ângulo do stick → direção do yaw: cos(ângulo) — apontar à direita (0) = +max,
     * à esquerda (π) = −max, vertical puro (±π/2) = 0 (contrato só-yaw; pitch de
     * flick é follow-up registrado no spec).
     */
    private fun directionYaw(angleRad: Float, config: FlickStickConfig): Float =
        config.maxYawRadS * cos(angleRad)

    /** Diferença angular normalizada em graus [0..180]. Pura (testada). */
    fun angularDiffDeg(aRad: Float, bRad: Float): Float {
        val diff = abs(aRad - bRad) % (2 * PI.toFloat())
        val shortest = if (diff > PI.toFloat()) 2 * PI.toFloat() - diff else diff
        return shortest * 180f / PI.toFloat()
    }
}
