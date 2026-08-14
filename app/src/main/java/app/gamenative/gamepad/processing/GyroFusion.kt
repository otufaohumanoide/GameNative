package app.gamenative.gamepad.processing

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Fusão de sensor 6-DOF — filtro complementar MAHONY (F1.3 do spec
 * 2026-08-15-input-core-avancado; algoritmo: Madgwick et al., "An efficient
 * orientation filter", variante Mahony com integrador de bias Ki).
 *
 * OPT-IN por perfil (`gyroFusionEnabled`) porque stacks BT diferentes expõem accel
 * com qualidade variável; desligado, o caminho é byte-identical (o hub não chama
 * esta classe).
 *
 * Honestidade técnica (registrada no spec): a gravidade observada pelo accel corrige
 * SÓ pitch/roll — o yaw NÃO é observável sem magnetômetro e permanece no
 * recenter + calibração contínua do [GyroProcessor] (P2-2). Por isso a correção do
 * erro é aplicada APENAS nos eixos X/Y (roll/pitch em eixos de sensor Android); o Z
 * (yaw) integra o gyro cru sem correção. A saída deste objeto é só `pitchRadS`
 * (corrigido); o yaw vem do [GyroProcessor] como sempre.
 *
 * Degradação (testada): accel ausente (0,0,0 — harness/devices sem accel) ou longe de
 * 1g (translação — o accel não referencia a gravidade) ⇒ correção ZERADA na amostra
 * (o integrador de bias congela) — pitch segue o gyro puro, nunca um valor errado.
 *
 * Sinais: mesmo contrato do [GyroProcessor] — pitch positivo = inclinar para cima
 * (deltaY negativo no mouse; rad/s no CAMERA). Deadzone com histerese 1.2×/0.8×
 * aplicada à saída (mesmo padrão do GyroProcessor, estado próprio por device).
 */
data class GyroFusionConfig(
    /** Ganho proporcional (default Mahony). */
    val kp: Float = 0.5f,
    /** Ganho do integrador de bias (0 = sem integrador). */
    val ki: Float = 0f,
    /**
     * Tolerância do accel para referenciar a gravidade: |mag/1g − 1| > tolerância ⇒
     * amostra ignorada para correção (translação/movimento).
     */
    val accelTolerance: Float = 0.25f,
    /** Deadzone angular da saída (rad/s) — mesmo default do gyro (~3°/s). */
    val deadzone: Float = 0.05f,
)

/** Estado por device (V6 — morre no removeDevice). */
class GyroFusionState {
    // Quatérnio (w, x, y, z) — identidade = nivelado.
    var qw = 1f
    var qx = 0f
    var qy = 0f
    var qz = 0f
    // Integrador de bias (termo Ki do Mahony).
    var integralX = 0f
    var integralY = 0f
    var integralZ = 0f
    var initialized = false
    var lastSampleMs: Long = 0L
    // Histerese da deadzone de saída (mesmo padrão GyroProcessor).
    var aboveDeadzone = false

    fun reset() {
        qw = 1f; qx = 0f; qy = 0f; qz = 0f
        integralX = 0f; integralY = 0f; integralZ = 0f
        initialized = false
        lastSampleMs = 0L
        aboveDeadzone = false
    }
}

data class GyroFusionOutput(
    /** Pitch corrigido pela fusão (rad/s), com deadzone+histerese. Sinal do GyroProcessor. */
    val pitchRadS: Float,
    /** false = amostra integrada SEM correção (accel inválido/ausente). */
    val corrected: Boolean,
) {
    companion object {
        val NONE = GyroFusionOutput(0f, false)
    }
}

object GyroFusion {

    private const val GRAVITY_M_S2 = 9.81f

    /**
     * Integra uma amostra. [gyroX/Y/Z] em rad/s (eixos Android); [accelX/Y/Z] em
     * m/s² (0,0,0 = sem accel); [dtSeconds] derivado do timestamp (clampado).
     */
    fun update(
        gyroX: Float,
        gyroY: Float,
        gyroZ: Float,
        accelX: Float,
        accelY: Float,
        accelZ: Float,
        nowMs: Long,
        state: GyroFusionState,
        config: GyroFusionConfig,
    ): GyroFusionOutput {
        val dt = if (state.lastSampleMs == 0L) {
            0f
        } else {
            ((nowMs - state.lastSampleMs).coerceIn(1L, 100L)) / 1000f
        }
        state.lastSampleMs = nowMs

        val hasAccel = accelX != 0f || accelY != 0f || accelZ != 0f
        val accelValid = hasAccel && accelNearGravity(accelX, accelY, accelZ, config)

        if (!state.initialized && accelValid) {
            // Bootstrap: orientação inicial direto do accel (roll/pitch; yaw = 0).
            initializeFromAccel(state, accelX, accelY, accelZ)
        }
        state.initialized = true

        // Erro = accel normalizado × vetor gravidade estimado (produto vetorial).
        var ex = 0f
        var ey = 0f
        var ez = 0f
        if (accelValid) {
            val invMag = 1f / sqrt(accelX * accelX + accelY * accelY + accelZ * accelZ)
            val ax = accelX * invMag
            val ay = accelY * invMag
            val az = accelZ * invMag
            // Gravidade estimada no frame do corpo: v = R(q)ᵀ · [0,0,1].
            val vx = 2f * (state.qx * state.qz - state.qw * state.qy)
            val vy = 2f * (state.qw * state.qx + state.qy * state.qz)
            val vz = state.qw * state.qw - state.qx * state.qx - state.qy * state.qy + state.qz * state.qz
            ex = ay * vz - az * vy
            ey = az * vx - ax * vz
            ez = ax * vy - ay * vx
        }

        // Integrador de bias (só acumula com correção válida — nunca integra ruído).
        state.integralX += ex * config.ki * dt
        state.integralY += ey * config.ki * dt
        state.integralZ += ez * config.ki * dt

        // Correção SÓ em X/Y (pitch/roll observáveis). Z (yaw) permanece gyro puro —
        // honestidade técnica do spec: sem magnetômetro não há referência de yaw.
        val wx = gyroX + config.kp * ex + state.integralX
        val wy = gyroY + config.kp * ey + state.integralY
        val wz = gyroZ

        // Integração do quatérnio: q̇ = ½ q ⊗ ω (Mahony).
        val halfDt = dt * 0.5f
        val qw = state.qw + (-state.qx * wx - state.qy * wy - state.qz * wz) * halfDt
        val qx = state.qx + (state.qw * wx + state.qy * wz - state.qz * wy) * halfDt
        val qy = state.qy + (state.qw * wy - state.qx * wz + state.qz * wx) * halfDt
        val qz = state.qz + (state.qw * wz + state.qx * wy - state.qy * wx) * halfDt
        val norm = sqrt(qw * qw + qx * qx + qy * qy + qz * qz)
        if (norm > 0f) {
            state.qw = qw / norm
            state.qx = qx / norm
            state.qy = qy / norm
            state.qz = qz / norm
        }

        // Saída: pitch = rotação corrigida no eixo X (sinal do GyroProcessor:
        // inclinar para cima = -X → pitch positivo).
        var pitch = -wx
        val threshold = if (state.aboveDeadzone) config.deadzone * 0.8f else config.deadzone * 1.2f
        if (abs(pitch) < threshold) pitch = 0f
        state.aboveDeadzone = abs(pitch) >= threshold

        return GyroFusionOutput(pitchRadS = pitch, corrected = accelValid)
    }

    /** |mag/1g − 1| ≤ tolerância (accel = referência de gravidade confiável). */
    private fun accelNearGravity(x: Float, y: Float, z: Float, config: GyroFusionConfig): Boolean {
        val mag = sqrt(x * x + y * y + z * z)
        return abs(mag / GRAVITY_M_S2 - 1f) <= config.accelTolerance
    }

    /** Bootstrap da atitude inicial: roll/pitch do accel; yaw = 0. Pura (testada). */
    private fun initializeFromAccel(state: GyroFusionState, x: Float, y: Float, z: Float) {
        val norm = sqrt(x * x + y * y + z * z)
        if (norm <= 0f) return
        val ax = x / norm
        val ay = y / norm
        val az = z / norm
        // roll = atan2(ay, az); pitch = asin(-ax). Quatérnio (w,x,y,z) = Rz(0)·Ry(pitch)·Rx(roll).
        val roll = kotlin.math.atan2(ay, az)
        val pitch = kotlin.math.asin((-ax).coerceIn(-1f, 1f))
        val cr = kotlin.math.cos(roll * 0.5f)
        val sr = kotlin.math.sin(roll * 0.5f)
        val cp = kotlin.math.cos(pitch * 0.5f)
        val sp = kotlin.math.sin(pitch * 0.5f)
        // Sem referência de yaw: zera a rotação em torno de Z (honestidade do spec).
        state.qz = 0f
        state.qw = cr * cp
        state.qx = sr * cp
        state.qy = cr * sp
        state.integralX = 0f
        state.integralY = 0f
        state.integralZ = 0f
    }
}
