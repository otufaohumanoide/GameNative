package app.gamenative.gamepad.processing

import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G2 (spec 2026-08-16-G-gyro-v2, §3): One Euro opt-in do MOUSE mode — constante
 * passa intacta, escada converge com atraso (e overshoot limitado da predição de
 * derivada), ruído de alta frequência é atenuado. Defaults DS4Windows
 * (minCutoff 1.0 / beta 0.7 / dCutoff 1.0).
 */
class OneEuroFilterTest {

    @Test
    fun `constant input passes through unchanged`() {
        val filter = OneEuroFilter()
        val outputs = (1..60).map { filter.filter(5f, 60f) }
        // Primeira amostra ancora; a constante é exata a partir daí.
        assertEquals(5f, outputs.first(), 0.0001f)
        assertEquals(5f, outputs.last(), 0.0001f)
    }

    @Test
    fun `step input converges with lag and bounded overshoot`() {
        val filter = OneEuroFilter()
        repeat(30) { filter.filter(0f, 60f) }
        val step = (1..120).map { filter.filter(10f, 60f) }
        // Lag: a primeira saída do degrau fica bem abaixo do alvo (suavização).
        assertTrue(step.first() < 5f)
        // Predição da derivada: overshoot clássico do One Euro existe, mas limitado.
        assertTrue(step.max() < 11f)
        assertTrue(step.min() > 0f)
        // Convergência: 120 amostras depois o alvo é atingido.
        assertEquals(10f, step.last(), 0.01f)
    }

    @Test
    fun `high frequency noise is attenuated`() {
        // Senoide de 20 Hz amostrada a 100 Hz: cutoff mínimo 1 Hz ⇒ atenuação forte.
        val filter = OneEuroFilter()
        val inputs = (0 until 400).map { i -> sin(2.0 * Math.PI * 20.0 * i / 100.0).toFloat() }
        val outputs = inputs.map { filter.filter(it, 100f) }
        val inputAmplitude = inputs.drop(100).map { abs(it) }.max()
        val outputAmplitude = outputs.drop(100).map { abs(it) }.max()
        assertTrue(
            "output amplitude $outputAmplitude should be well below input $inputAmplitude",
            outputAmplitude < inputAmplitude * 0.1f,
        )
    }

    @Test
    fun `reset re-anchors on the next sample`() {
        val filter = OneEuroFilter()
        repeat(60) { filter.filter(5f, 60f) }
        filter.reset()
        // Depois do reset a próxima chamada ancora de novo (sem mistura do valor
        // antigo — o hub recria o estado por borda de ativação).
        assertEquals(2f, filter.filter(2f, 60f), 0.0001f)
        repeat(60) { filter.filter(2f, 60f) }
        assertEquals(2f, filter.filter(2f, 60f), 0.0001f)
    }
}
