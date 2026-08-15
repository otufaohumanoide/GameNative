package app.gamenative.gamepad.processing

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * G1 (spec 2026-08-16-G-gyro-v2, §3): acumulador sub-pixel do MOUSE mode — frações
 * acumulam até completar 1 px, o resto inteiro é emitido e a fração restante fica
 * guardada (padrão DS4Windows horizontalRemainder).
 */
class GyroPixelAccumulatorTest {

    @Test
    fun `fractions accumulate until a full pixel is emitted`() {
        val state = GyroMouseState()
        // 0.4 px por amostra × 5 = 2.0 px: 0, 0, 1, 0, 1.
        val emitted = (1..5).map { GyroPixelAccumulator.accumulate(0.4f, 0f, state).first }
        assertEquals(listOf(0, 0, 1, 0, 1), emitted)
        assertEquals(0f, state.remX, 0.0001f)
    }

    @Test
    fun `exact pixel totals leave a zero remainder`() {
        val state = GyroMouseState()
        var total = 0
        repeat(4) { total += GyroPixelAccumulator.accumulate(0.5f, 0f, state).first }
        assertEquals(2, total)
        assertEquals(0f, state.remX, 0.0001f)
    }

    @Test
    fun `slow rotation below one pixel still moves eventually`() {
        // O defeito original: .toInt() descartava 0.3 px por amostra — o cursor NUNCA
        // movia. Com o acumulador, 4 amostras de 0.3 px emitem 1 px (0.3→0.6→0.9→1.2).
        val state = GyroMouseState()
        val emitted = (1..4).map { GyroPixelAccumulator.accumulate(0.3f, 0f, state).first }
        assertEquals(listOf(0, 0, 0, 1), emitted)
        assertEquals(0.2f, state.remX, 0.0001f)
    }

    @Test
    fun `negative deltas accumulate symmetrically`() {
        val state = GyroMouseState()
        val emitted = (1..4).map { GyroPixelAccumulator.accumulate(-0.3f, 0f, state).first }
        assertEquals(listOf(0, 0, 0, -1), emitted)
        assertEquals(-0.2f, state.remX, 0.0001f)
    }

    @Test
    fun `both axes accumulate independently`() {
        val state = GyroMouseState()
        val (dx, dy) = GyroPixelAccumulator.accumulate(0.6f, 0.6f, state)
        assertEquals(0, dx)
        assertEquals(0, dy)
        val (dx2, dy2) = GyroPixelAccumulator.accumulate(0.6f, 0.3f, state)
        assertEquals(1, dx2)
        assertEquals(0, dy2)
        assertEquals(0.2f, state.remX, 0.0001f)
        assertEquals(0.9f, state.remY, 0.0001f)
    }
}
