package app.gamenative.gamepad.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-2/P2-3 (spec 2026-08-14-gamepad-upgrades-pendencias): calibração contínua por
 * janela estável (Dolphin IMUGyroscope) + stillness pelo accel (JoyShockLibrary).
 */
class GyroCalibrationTest {

    private val config = GyroConfig(deadzone = 0.05f, calibPeriodMs = 3000L)

    private fun sample(
        x: Float,
        y: Float,
        z: Float,
        ms: Long,
        accelX: Float = 0f,
        accelY: Float = 0f,
        accelZ: Float = 0f,
    ) = GyroSample(x, y, z, ms, accelX, accelY, accelZ)

    /** 150 amostras a 50 Hz = 3 s (período completo). */
    private fun runStillSamples(state: GyroState, bias: Float, startMs: Long, count: Int = 150) {
        for (i in 1..count) {
            GyroProcessor.process(sample(bias, 0f, 0f, startMs + i * 20L), state, config, activate = true)
        }
    }

    @Test
    fun `still device converges offset to the real bias after the period`() {
        val state = GyroState()
        // Ativação com amostra ruidosa DENTRO da deadzone: o recenter ancorou 0.29,
        // o bias real é 0.30 rad/s. Sem calibração contínua o offset ficaria 0.29
        // para sempre (o drift residual é exatamente o defeito do P2-2).
        GyroProcessor.process(sample(0.29f, 0f, 0f, 0), state, config, activate = true)
        // Controle PARADO em 0.30: |velocidade calibrada| = 0.01 < deadzone ⇒ acumula.
        runStillSamples(state, 0.30f, 0L)
        // Após o período (3 s), o offset virou a MÉDIA da janela (≈0.30) ⇒ delta ≈ 0
        // SEM re-ativação (aceite P2-2 (1)).
        val out = GyroProcessor.process(sample(0.30f, 0f, 0f, 4000L), state, config, activate = true)
        assertEquals(0f, out.deltaYRad, 0.0001f)
        assertEquals(0.30f, state.offsetX, 0.001f)
    }

    @Test
    fun `movement during the window resets the accumulator`() {
        val state = GyroState()
        GyroProcessor.process(sample(0.30f, 0f, 0f, 0), state, config, activate = true)
        runStillSamples(state, 0.30f, 0L, count = 50)
        assertTrue(state.runningCount > 0)
        // Girar o controle (0.30 → 0.80 rad/s) DURANTE a janela: velocidade calibrada
        // 0.50 > deadzone ⇒ zera o acumulador (nunca calibra em cima de movimento —
        // aceite P2-2 (2)).
        GyroProcessor.process(sample(0.80f, 0f, 0f, 1100L), state, config, activate = true)
        assertEquals(0, state.runningCount)
        assertEquals(0f, state.runningSumX, 0.0001f)
        // Volta ao repouso: uma NOVA janela começa do zero e converge de novo.
        runStillSamples(state, 0.30f, 1200L)
        val out = GyroProcessor.process(sample(0.30f, 0f, 0f, 5000L), state, config, activate = true)
        assertEquals(0f, out.deltaYRad, 0.0001f)
    }

    @Test
    fun `constant rotation never completes a window`() {
        val state = GyroState()
        GyroProcessor.process(sample(0.30f, 0f, 0f, 0), state, config, activate = true)
        // Rotação CONSTANTE a 0.80 rad/s por > 3 s (pan de câmera contínuo): a
        // velocidade calibrada 0.50 > deadzone em TODA amostra ⇒ a janela nunca
        // acumula — divergência deliberada do Dolphin (o critério dele por desvio da
        // média calibra em cima de rotação constante e congelaria a câmera).
        for (i in 1..200) {
            GyroProcessor.process(sample(0.80f, 0f, 0f, i * 20L), state, config, activate = true)
        }
        assertEquals(0, state.runningCount)
        assertEquals(0.30f, state.offsetX, 0.001f) // offset intocado
    }

    @Test
    fun `period zero disables continuous calibration`() {
        val state = GyroState()
        val disabled = GyroConfig(deadzone = 0.05f, calibPeriodMs = 0L)
        GyroProcessor.process(sample(0.20f, 0f, 0f, 0), state, disabled, activate = true)
        runStillSamples(state, 0.30f, 0L)
        // Comportamento atual preservado: offset continua o do recenter (0.20).
        assertEquals(0.20f, state.offsetX, 0.001f)
        assertEquals(0, state.runningCount)
    }

    @Test
    fun `inactive gyro never accumulates`() {
        val state = GyroState()
        GyroProcessor.process(sample(0.30f, 0f, 0f, 0), state, config, activate = true)
        // Solta a ativação: acumulador zera e não volta a acumular inativo.
        GyroProcessor.process(sample(0.30f, 0f, 0f, 20L), state, config, activate = false)
        assertEquals(0, state.runningCount)
        GyroProcessor.process(sample(0.30f, 0f, 0f, 40L), state, config, activate = false)
        assertEquals(0, state.runningCount)
    }

    @Test
    fun `accelerometer deviation from one g resets the window`() {
        val state = GyroState()
        // Repouso com accel ≈ 1g (0, 0, 9.81): acumula normal.
        GyroProcessor.process(sample(0.30f, 0f, 0f, 0, accelZ = 9.81f), state, config, activate = true)
        runStillSamples(state, 0.30f, 0L, count = 50)
        assertTrue(state.runningCount > 0)
        // Movimento detectado pelo accel (2g — desvio > tolerância 0.2) mesmo com o
        // gyro estável (ruído do gyro pode mascarar micro-movimento): zera.
        GyroProcessor.process(sample(0.30f, 0f, 0f, 1100L, accelZ = 19.62f), state, config, activate = true)
        assertEquals(0, state.runningCount)
    }

    @Test
    fun `zero accel means unknown and does not gate stillness`() {
        val state = GyroState()
        // Harness/sem accel: 0,0,0 ⇒ critério ignorado (compatibilidade com o
        // harness `gyro:x:y:z` e devices sem accel — P2-3 degradação silenciosa).
        GyroProcessor.process(sample(0.30f, 0f, 0f, 0), state, config, activate = true)
        runStillSamples(state, 0.30f, 0L, count = 50)
        assertTrue(state.runningCount > 0)
        val out = GyroProcessor.process(sample(0.30f, 0f, 0f, 4000L), state, config, activate = true)
        assertEquals(0f, out.deltaYRad, 0.0001f)
    }
}
