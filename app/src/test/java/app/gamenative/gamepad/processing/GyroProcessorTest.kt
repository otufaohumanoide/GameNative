package app.gamenative.gamepad.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U1 (spec 2026-08-14-gamepad-u1-gyro, §1.3): decisões puras do gyro — recenter na
 * borda de ativação, deadzone angular com histerese, deltas proporcionais a
 * rotação×dt, sinais (girar à direita = +deltaX; inclinar para cima = -deltaY).
 */
class GyroProcessorTest {

    private val config = GyroConfig(deadzone = 0.05f)

    private fun sample(x: Float, y: Float, z: Float, ms: Long) = GyroSample(x, y, z, ms)

    @Test
    fun `inactive produces no output`() {
        val state = GyroState()
        val out = GyroProcessor.process(sample(1f, 0f, 0f, 0), state, config, activate = false)
        assertFalse(out.active)
        assertEquals(0f, out.deltaXRad, 0.0001f)
        assertEquals(0f, out.deltaYRad, 0.0001f)
    }

    @Test
    fun `first sample anchors without delta`() {
        val state = GyroState()
        val out = GyroProcessor.process(sample(0.5f, 0f, 0f, 0), state, config, activate = true)
        assertTrue(out.active)
        assertEquals(0f, out.deltaXRad, 0.0001f)
        assertEquals(0f, out.deltaYRad, 0.0001f)
    }

    @Test
    fun `activation edge recenters the offset`() {
        val state = GyroState()
        // Desvio de bias de 0.5 rad/s: a primeira amostra vira offset.
        GyroProcessor.process(sample(0.5f, 0f, 0f, 0), state, config, activate = true)
        val out = GyroProcessor.process(sample(0.5f, 0f, 0f, 16), state, config, activate = true)
        // bias ancorado → sem delta.
        assertEquals(0f, out.deltaXRad, 0.0001f)
        assertEquals(0f, out.deltaYRad, 0.0001f)
    }

    @Test
    fun `yaw right produces positive deltaX`() {
        val state = GyroState()
        GyroProcessor.process(sample(0f, 0f, 0f, 0), state, config, activate = true)
        // girar à direita = -Z (convenção Android); 1 rad/s por 16 ms → -0.016 rad.
        val out = GyroProcessor.process(sample(0f, 0f, -1f, 16), state, config, activate = true)
        assertEquals(0.016f, out.deltaXRad, 0.0005f)
    }

    @Test
    fun `pitch produces negative deltaY`() {
        val state = GyroState()
        GyroProcessor.process(sample(0f, 0f, 0f, 0), state, config, activate = true)
        // Convenção do processador: pitch = -(gyroX - offset) → +X = -deltaY.
        // (Sinais a confirmar on-device — anotado no spec U1.)
        val out = GyroProcessor.process(sample(1f, 0f, 0f, 16), state, config, activate = true)
        assertEquals(-0.016f, out.deltaYRad, 0.0005f)
    }

    @Test
    fun `below deadzone with hysteresis - delta is zeroed`() {
        val state = GyroState()
        GyroProcessor.process(sample(0f, 0f, 0f, 0), state, config, activate = true)
        // 0.03 rad/s < deadzone*0.8 (0.04) → zero.
        val out = GyroProcessor.process(sample(0f, 0f, -0.03f, 16), state, config, activate = true)
        assertEquals(0f, out.deltaXRad, 0.0001f)
    }

    @Test
    fun `deadzone hysteresis - entry and exit thresholds are sticky`() {
        val state = GyroState()
        GyroProcessor.process(sample(0f, 0f, 0f, 0), state, config, activate = true)
        // Estado "abaixo": precisa de >= deadzone*0.8 (0.04) para entrar.
        val below = GyroProcessor.process(sample(0f, 0f, -0.03f, 16), state, config, activate = true)
        assertEquals(0f, below.deltaXRad, 0.0001f)
        val entered = GyroProcessor.process(sample(0f, 0f, -0.045f, 32), state, config, activate = true)
        assertTrue(kotlin.math.abs(entered.deltaXRad) > 0f)
        // Estado "acima": zera apenas abaixo de deadzone*1.2 (0.06) — 0.045 ainda
        // acima do limiar de saída → permanece ativo.
        val sticky = GyroProcessor.process(sample(0f, 0f, -0.045f, 48), state, config, activate = true)
        assertTrue(kotlin.math.abs(sticky.deltaXRad) > 0f)
        // Cai para 0.03 (< 0.06) → zera; volta a 0.07 (> 0.04) → reativa.
        val exited = GyroProcessor.process(sample(0f, 0f, -0.03f, 64), state, config, activate = true)
        assertEquals(0f, exited.deltaXRad, 0.0001f)
        val reentered = GyroProcessor.process(sample(0f, 0f, -0.07f, 80), state, config, activate = true)
        assertTrue(kotlin.math.abs(reentered.deltaXRad) > 0f)
    }

    @Test
    fun `delta scales with dt`() {
        val state = GyroState()
        GyroProcessor.process(sample(0f, 0f, 0f, 0), state, config, activate = true)
        val outA = GyroProcessor.process(sample(0f, 0f, -1f, 16), state, config, activate = true)
        val stateB = GyroState()
        GyroProcessor.process(sample(0f, 0f, 0f, 0), stateB, config, activate = true)
        val outB = GyroProcessor.process(sample(0f, 0f, -1f, 32), stateB, config, activate = true)
        assertEquals(outB.deltaXRad, outA.deltaXRad * 2f, 0.0005f)
    }

    @Test
    fun `release then reactivate re-anchors without spike`() {
        val state = GyroState()
        GyroProcessor.process(sample(0f, 0f, 0f, 0), state, config, activate = true)
        GyroProcessor.process(sample(0f, 0f, -1f, 16), state, config, activate = true)
        GyroProcessor.process(sample(0f, 0f, -1f, 32), state, config, activate = false)
        // Nova ativação com bias diferente: recenter + ancora (sem delta).
        val out = GyroProcessor.process(sample(0.9f, 0f, 0f, 48), state, config, activate = true)
        assertEquals(0f, out.deltaXRad, 0.0001f)
        assertEquals(0f, out.deltaYRad, 0.0001f)
    }
}
