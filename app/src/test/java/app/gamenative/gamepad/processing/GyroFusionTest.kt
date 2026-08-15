package app.gamenative.gamepad.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/** F1.3 (spec 2026-08-15-input-core-avancado): Mahony — convergência e degradação. */
class GyroFusionTest {

    private fun flatAccel() = Triple(0f, 0f, 9.81f) // deitado na mesa (reação para cima)

    /** Roda N amostras de repouso (accel flat, gyro com bias) e devolve o pitch final. */
    private fun runFlat(config: GyroFusionConfig, biasX: Float = 0.01f, samples: Int = 500): Float {
        val state = GyroFusionState()
        var last: GyroFusionOutput = GyroFusionOutput.NONE
        var now = 0L
        for (i in 0 until samples) {
            now += 5L // 5 ms → 200 Hz
            last = GyroFusion.update(
                gyroX = biasX, gyroY = 0f, gyroZ = 0f,
                accelX = 0f, accelY = 0f, accelZ = 9.81f,
                nowMs = now, state = state, config = config,
            )
        }
        return last.pitchRadS
    }

    @Test
    fun `converge para nivelado sem drift de pitch acumulado`() {
        // bias de gyro 0.01 rad/s no eixo X: sem correção, o pitch integraria e
        // divergiria; com Mahony, a gravidade puxa de volta e o bias vira o integrador.
        val pitch = runFlat(GyroFusionConfig(kp = 0.5f, ki = 0.1f, deadzone = 0f), biasX = 0.01f)
        // pitch = -wx; wx converge para ~0 (bias absorvido pelo Ki) → pitch ≈ 0
        assertTrue("pitch divergiu: $pitch", kotlin.math.abs(pitch) < 0.05f)
    }

    @Test
    fun `sem integrador o bias vaza na saida`() {
        // ki = 0: o erro proporcional segura o quatérnio, mas a saída de taxa
        // (gyro corrigido) mantém o bias residual Kp·e ≠ bias — documentado: o Ki é
        // o que mata o bias. Só garantimos que não explode.
        val pitch = runFlat(GyroFusionConfig(kp = 0.5f, ki = 0f, deadzone = 0f), biasX = 0.01f)
        assertTrue("pitch explodiu: $pitch", kotlin.math.abs(pitch) < 0.5f)
    }

    @Test
    fun `accel ausente congela a correcao sem explodir`() {
        val state = GyroFusionState()
        var now = 0L
        var last: GyroFusionOutput = GyroFusionOutput.NONE
        for (i in 0 until 100) {
            now += 5L
            last = GyroFusion.update(
                gyroX = 0.1f, gyroY = 0f, gyroZ = 0f,
                accelX = 0f, accelY = 0f, accelZ = 0f, // harness sem accel
                nowMs = now, state = state, config = GyroFusionConfig(deadzone = 0f),
            )
        }
        // correção zerada → pitch = -gyroX puro
        assertEquals(-0.1f, last.pitchRadS, 0.001f)
        assertTrue(!last.corrected)
    }

    @Test
    fun `accel longe de 1g (translacao) nao corrompe a atitude`() {
        val state = GyroFusionState()
        var now = 0L
        // primeira amostra válida (bootstrap)
        GyroFusion.update(0f, 0f, 0f, 0f, 0f, 9.81f, now, state, GyroFusionConfig())
        // rajada de accel 3g (translação forte): correção deve ser ignorada
        var last: GyroFusionOutput = GyroFusionOutput.NONE
        for (i in 0 until 50) {
            now += 5L
            // mag ≈ 13.9 → |ratio−1| ≈ 0.41 > 0.25 → correção inválida
            last = GyroFusion.update(0f, 0f, 0f, 9.81f, 0f, 9.81f, now, state, GyroFusionConfig(deadzone = 0f))
        }
        assertTrue(!last.corrected)
        assertEquals(0f, last.pitchRadS, 0.001f)
    }

    @Test
    fun `quaternion permanece normalizado`() {
        val state = GyroFusionState()
        var now = 0L
        for (i in 0 until 2000) {
            now += 5L
            GyroFusion.update(
                gyroX = 0.3f, gyroY = -0.2f, gyroZ = 0.1f,
                accelX = 0f, accelY = 0f, accelZ = 9.81f,
                nowMs = now, state = state, config = GyroFusionConfig(),
            )
        }
        val norm = kotlin.math.sqrt(
            state.qw * state.qw + state.qx * state.qx + state.qy * state.qy + state.qz * state.qz,
        )
        assertEquals(1f, norm, 0.0001f)
    }

    @Test
    fun `deadzone com histerese zera micro-pitch`() {
        val state = GyroFusionState()
        var now = 0L
        GyroFusion.update(0f, 0f, 0f, 0f, 0f, 9.81f, 0L, state, GyroFusionConfig())
        now = 5L
        // abaixo de 0.05·1.2 → zero
        val out = GyroFusion.update(0.01f, 0f, 0f, 0f, 0f, 9.81f, now, state, GyroFusionConfig())
        assertEquals(0f, out.pitchRadS, 0.001f)
        // acima → passa
        now = 10L
        val out2 = GyroFusion.update(0.1f, 0f, 0f, 0f, 0f, 9.81f, now, state, GyroFusionConfig())
        assertEquals(-0.1f, out2.pitchRadS, 0.01f)
    }

    @Test
    fun `reset devolve a identidade`() {
        val state = GyroFusionState()
        GyroFusion.update(0.5f, 0.3f, 0.2f, 0f, 0f, 9.81f, 5L, state, GyroFusionConfig())
        state.reset()
        assertEquals(1f, state.qw, 0f)
        assertEquals(0f, state.qx, 0f)
        assertEquals(0f, state.qy, 0f)
        assertEquals(0f, state.qz, 0f)
    }
}
