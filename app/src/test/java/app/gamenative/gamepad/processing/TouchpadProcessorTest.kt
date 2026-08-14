package app.gamenative.gamepad.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U2 (spec 2026-08-14-gamepad-u2-touchpad-mouse, §1.1): decisões puras do touchpad →
 * mouse — delta com deadzone de toque, escala de sensibilidade, tap curto/parado =
 * clique, tap longo/movido ≠ clique, estado por amostra.
 */
class TouchpadProcessorTest {

    private val config = TouchpadConfig() // sensitivity 1.0, pixelsPerPadWidth 350

    @Test
    fun `finger down anchors and emits nothing`() {
        val state = TouchpadState()
        val d = TouchpadProcessor.process(TouchSample(down = true, x = 0.2f, y = 0.3f, nowMs = 0), state, config)
        assertEquals(0, d.deltaX)
        assertEquals(0, d.deltaY)
        assertFalse(d.tap)
        assertTrue(state.fingerDown)
    }

    @Test
    fun `move produces scaled delta`() {
        val state = TouchpadState()
        TouchpadProcessor.process(TouchSample(down = true, x = 0.2f, y = 0.3f, nowMs = 0), state, config)
        val d = TouchpadProcessor.process(TouchSample(down = true, x = 0.3f, y = 0.3f, nowMs = 16), state, config)
        // 0.1 de percurso * 1.0 * 350 px
        assertEquals(35, d.deltaX)
        assertEquals(0, d.deltaY)
        assertFalse(d.tap)
    }

    @Test
    fun `still finger below deadzone emits nothing`() {
        val state = TouchpadState()
        TouchpadProcessor.process(TouchSample(down = true, x = 0.5f, y = 0.5f, nowMs = 0), state, config)
        val d = TouchpadProcessor.process(TouchSample(down = true, x = 0.501f, y = 0.502f, nowMs = 16), state, config)
        assertEquals(0, d.deltaX)
        assertEquals(0, d.deltaY)
    }

    @Test
    fun `sensitivity scales the delta`() {
        val state = TouchpadState()
        val cfg = TouchpadConfig(sensitivity = 2f)
        TouchpadProcessor.process(TouchSample(down = true, x = 0.2f, y = 0.3f, nowMs = 0), state, cfg)
        val d = TouchpadProcessor.process(TouchSample(down = true, x = 0.3f, y = 0.3f, nowMs = 16), state, cfg)
        assertEquals(70, d.deltaX)
    }

    @Test
    fun `short still tap is a click`() {
        val state = TouchpadState()
        TouchpadProcessor.process(TouchSample(down = true, x = 0.5f, y = 0.5f, nowMs = 0), state, config)
        val d = TouchpadProcessor.process(TouchSample(down = false, x = 0.5f, y = 0.5f, nowMs = 100), state, config)
        assertTrue(d.tap)
        assertFalse(state.fingerDown)
    }

    @Test
    fun `long tap is not a click`() {
        val state = TouchpadState()
        TouchpadProcessor.process(TouchSample(down = true, x = 0.5f, y = 0.5f, nowMs = 0), state, config)
        val d = TouchpadProcessor.process(TouchSample(down = false, x = 0.5f, y = 0.5f, nowMs = 500), state, config)
        assertFalse(d.tap)
    }

    @Test
    fun `tap that moved is not a click`() {
        val state = TouchpadState()
        TouchpadProcessor.process(TouchSample(down = true, x = 0.5f, y = 0.5f, nowMs = 0), state, config)
        val d = TouchpadProcessor.process(TouchSample(down = false, x = 0.7f, y = 0.5f, nowMs = 100), state, config)
        assertFalse(d.tap)
    }

    @Test
    fun `double tap produces two clicks`() {
        val state = TouchpadState()
        val t1 = TouchpadProcessor.process(TouchSample(down = true, x = 0.5f, y = 0.5f, nowMs = 0), state, config)
        val t2 = TouchpadProcessor.process(TouchSample(down = false, x = 0.5f, y = 0.5f, nowMs = 50), state, config)
        val t3 = TouchpadProcessor.process(TouchSample(down = true, x = 0.5f, y = 0.5f, nowMs = 100), state, config)
        val t4 = TouchpadProcessor.process(TouchSample(down = false, x = 0.5f, y = 0.5f, nowMs = 150), state, config)
        assertFalse(t1.tap)
        assertTrue(t2.tap)
        assertFalse(t3.tap)
        assertTrue(t4.tap)
    }

    @Test
    fun `up without down is ignored`() {
        val state = TouchpadState()
        val d = TouchpadProcessor.process(TouchSample(down = false, x = 0.5f, y = 0.5f, nowMs = 0), state, config)
        assertFalse(d.tap)
        assertEquals(0, d.deltaX)
    }
}
