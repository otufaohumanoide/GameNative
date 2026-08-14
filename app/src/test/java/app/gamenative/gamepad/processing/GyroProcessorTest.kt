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
    fun `deadzone hysteresis - below entry threshold stays off`() {
        val state = GyroState()
        GyroProcessor.process(sample(0f, 0f, 0f, 0), state, config, activate = true)
        // (a) P1-4/P3-8: 0.9× (0.045 rad/s) constante por N amostras — abaixo da
        // entrada 1.2× (0.06) → sempre zero (nunca entra).
        for (i in 1..10) {
            val out = GyroProcessor.process(sample(0f, 0f, -0.045f, i * 16L), state, config, activate = true)
            assertEquals(0f, out.deltaXRad, 0.0001f)
        }
    }

    @Test
    fun `deadzone hysteresis - above entry threshold always passes`() {
        val state = GyroState()
        GyroProcessor.process(sample(0f, 0f, 0f, 0), state, config, activate = true)
        // (b) 1.3× (0.065) constante: entra na primeira amostra e permanece (a saída
        // 0.8× = 0.04 fica abaixo do sinal) → todas as 10 amostras passam.
        var nonzero = 0
        for (i in 1..10) {
            val out = GyroProcessor.process(sample(0f, 0f, -0.065f, i * 16L), state, config, activate = true)
            if (kotlin.math.abs(out.deltaXRad) > 0f) nonzero++
        }
        assertEquals(10, nonzero)
    }

    @Test
    fun `deadzone hysteresis - sticky above until below exit threshold`() {
        val state = GyroState()
        GyroProcessor.process(sample(0f, 0f, 0f, 0), state, config, activate = true)
        // (c) 1.3× entra...
        val entered = GyroProcessor.process(sample(0f, 0f, -0.065f, 16), state, config, activate = true)
        assertTrue(kotlin.math.abs(entered.deltaXRad) > 0f)
        // ...0.9× (entre a saída 0.8× e a entrada 1.2×) AINDA passa (sticky)...
        val sticky = GyroProcessor.process(sample(0f, 0f, -0.045f, 32), state, config, activate = true)
        assertTrue(kotlin.math.abs(sticky.deltaXRad) > 0f)
        // ...0.7× (abaixo da saída 0.8×) zera.
        val exited = GyroProcessor.process(sample(0f, 0f, -0.035f, 48), state, config, activate = true)
        assertEquals(0f, exited.deltaXRad, 0.0001f)
    }

    @Test
    fun `deadzone hysteresis - no flicker for signal oscillating around deadzone`() {
        val state = GyroState()
        GyroProcessor.process(sample(0f, 0f, 0f, 0), state, config, activate = true)
        // (d) Regressão do flicker (P1-4): tremor real alterna em volta da deadzone
        // (1.05× / 0.95×). Com o código invertido (estado pelo deadzone cru), o limiar
        // oscilava 0.8×/1.2× e a decisão alternava (~5 não-zero em 10). Com a histerese
        // correta (entrada 1.2×, saída 0.8×), tudo permanece OFF — decisão estável.
        var nonzero = 0
        for (i in 1..10) {
            val value = if (i % 2 == 1) -0.0525f else -0.0475f
            val out = GyroProcessor.process(sample(0f, 0f, value, i * 16L), state, config, activate = true)
            if (kotlin.math.abs(out.deltaXRad) > 0f) nonzero++
        }
        assertEquals(0, nonzero)
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
