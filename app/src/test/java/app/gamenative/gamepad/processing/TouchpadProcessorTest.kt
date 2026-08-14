package app.gamenative.gamepad.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U2 (spec 2026-08-14-gamepad-u2-touchpad-mouse, §1.1) + P2-6 (spec
 * 2026-08-14-touchpad-drag-double-tap): decisões puras do touchpad → mouse — delta
 * com deadzone de toque, escala de sensibilidade, tap curto/parado = clique,
 * ARRASTO (segurar ≥ 650 ms = BUTTON_LEFT contínuo), DUPLO-TOQUE = clique direito
 * (opt-in) e dead zone de pós-toque (bounce).
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
        // Segundo down depois da dead zone de pós-toque (>= 100 ms após o up).
        val t3 = TouchpadProcessor.process(TouchSample(down = true, x = 0.5f, y = 0.5f, nowMs = 160), state, config)
        val t4 = TouchpadProcessor.process(TouchSample(down = false, x = 0.5f, y = 0.5f, nowMs = 210), state, config)
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

    // ── P2-6 (spec 2026-08-14-touchpad-drag-double-tap) ──

    @Test
    fun `hold past drag threshold engages left button drag`() {
        val state = TouchpadState()
        TouchpadProcessor.process(TouchSample(down = true, x = 0.5f, y = 0.5f, nowMs = 0), state, config)
        // Ainda dentro do limiar (600 < 650): só move, sem botão.
        val before = TouchpadProcessor.process(TouchSample(down = true, x = 0.6f, y = 0.5f, nowMs = 600), state, config)
        assertFalse(before.dragPress)
        // Cruza 650 ms: BUTTON_LEFT pressionado + deltas continuam.
        val engage = TouchpadProcessor.process(TouchSample(down = true, x = 0.65f, y = 0.5f, nowMs = 700), state, config)
        assertTrue(engage.dragPress)
        assertTrue(engage.deltaX > 0)
        assertTrue(state.dragging)
        // Move durante o arrasto: delta, sem novo press.
        val move = TouchpadProcessor.process(TouchSample(down = true, x = 0.7f, y = 0.5f, nowMs = 800), state, config)
        assertFalse(move.dragPress)
        assertTrue(move.deltaX > 0)
        // Soltar: release (nunca tap).
        val up = TouchpadProcessor.process(TouchSample(down = false, x = 0.7f, y = 0.5f, nowMs = 900), state, config)
        assertTrue(up.dragRelease)
        assertFalse(up.tap)
    }

    @Test
    fun `hold without movement also engages drag`() {
        val state = TouchpadState()
        TouchpadProcessor.process(TouchSample(down = true, x = 0.5f, y = 0.5f, nowMs = 0), state, config)
        // 650 ms segurando SEM mover → long-press vira arrasto (padrão moonlight).
        val engage = TouchpadProcessor.process(TouchSample(down = true, x = 0.5f, y = 0.5f, nowMs = 700), state, config)
        assertTrue(engage.dragPress)
        val up = TouchpadProcessor.process(TouchSample(down = false, x = 0.5f, y = 0.5f, nowMs = 800), state, config)
        assertTrue(up.dragRelease)
    }

    @Test
    fun `double tap with opt-in sends right click`() {
        val state = TouchpadState()
        val cfg = TouchpadConfig(doubleTapRightClick = true)
        TouchpadProcessor.process(TouchSample(down = true, x = 0.5f, y = 0.5f, nowMs = 0), state, cfg)
        val t1 = TouchpadProcessor.process(TouchSample(down = false, x = 0.5f, y = 0.5f, nowMs = 50), state, cfg)
        assertTrue(t1.tap) // primeiro tap: clique esquerdo
        TouchpadProcessor.process(TouchSample(down = true, x = 0.51f, y = 0.51f, nowMs = 160), state, cfg)
        val t2 = TouchpadProcessor.process(TouchSample(down = false, x = 0.51f, y = 0.51f, nowMs = 210), state, cfg)
        assertFalse(t2.tap)
        assertTrue(t2.rightClick)
        // Terceiro toque não encadeia outro direito (último tap limpo).
        TouchpadProcessor.process(TouchSample(down = true, x = 0.5f, y = 0.5f, nowMs = 320), state, cfg)
        val t3 = TouchpadProcessor.process(TouchSample(down = false, x = 0.5f, y = 0.5f, nowMs = 370), state, cfg)
        assertTrue(t3.tap)
        assertFalse(t3.rightClick)
    }

    @Test
    fun `double tap beyond window stays two left clicks`() {
        val state = TouchpadState()
        val cfg = TouchpadConfig(doubleTapRightClick = true)
        TouchpadProcessor.process(TouchSample(down = true, x = 0.5f, y = 0.5f, nowMs = 0), state, cfg)
        TouchpadProcessor.process(TouchSample(down = false, x = 0.5f, y = 0.5f, nowMs = 50), state, cfg)
        TouchpadProcessor.process(TouchSample(down = true, x = 0.5f, y = 0.5f, nowMs = 400), state, cfg)
        val t2 = TouchpadProcessor.process(TouchSample(down = false, x = 0.5f, y = 0.5f, nowMs = 450), state, cfg)
        assertTrue(t2.tap)
        assertFalse(t2.rightClick)
    }

    @Test
    fun `post touch dead zone rejects bounce down`() {
        val state = TouchpadState()
        TouchpadProcessor.process(TouchSample(down = true, x = 0.5f, y = 0.5f, nowMs = 0), state, config)
        TouchpadProcessor.process(TouchSample(down = false, x = 0.5f, y = 0.5f, nowMs = 50), state, config)
        // Down fantasma dentro de 100 ms: rejeitado (não ancora, não move).
        val bounce = TouchpadProcessor.process(TouchSample(down = true, x = 0.6f, y = 0.6f, nowMs = 80), state, config)
        assertFalse(state.fingerDown)
        assertEquals(0, bounce.deltaX)
        // Up do toque rejeitado: ignorado.
        val up = TouchpadProcessor.process(TouchSample(down = false, x = 0.6f, y = 0.6f, nowMs = 90), state, config)
        assertFalse(up.tap)
        // Depois da janela, down normal ancora.
        TouchpadProcessor.process(TouchSample(down = true, x = 0.5f, y = 0.5f, nowMs = 200), state, config)
        assertTrue(state.fingerDown)
    }
}
