package app.gamenative.gamepad.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // ── D (spec 2026-08-16-D-touchpad-swipes-macros) ──

    /** Down em (0.5,0.5) → up em (0.5+dx, 0.5+dy) aos 150 ms — gesto único. */
    private fun swipeGesture(
        dx: Float,
        dy: Float,
        upAtMs: Long = 150L,
        cfg: TouchpadConfig = config,
    ): TouchpadDecision {
        val state = TouchpadState()
        TouchpadProcessor.process(TouchSample(down = true, x = 0.5f, y = 0.5f, nowMs = 0), state, cfg)
        return TouchpadProcessor.process(
            TouchSample(down = false, x = 0.5f + dx, y = 0.5f + dy, nowMs = upAtMs),
            state,
            cfg,
        )
    }

    @Test
    fun `swipe right is decided on the finger up and suppresses tap`() {
        val d = swipeGesture(dx = 0.3f, dy = 0f)
        assertEquals(SwipeDir.RIGHT, d.swipe)
        assertFalse(d.tap)
        assertFalse(d.dragRelease)
        assertFalse(d.rightClick)
        // O up do swipe não gera delta de mouse.
        assertEquals(0, d.deltaX)
        assertEquals(0, d.deltaY)
    }

    @Test
    fun `swipe recognizes all 8 directions`() {
        val vectors = listOf(
            (0f to -0.3f) to SwipeDir.UP,
            (0.25f to -0.25f) to SwipeDir.UP_RIGHT,
            (0.3f to 0f) to SwipeDir.RIGHT,
            (0.25f to 0.25f) to SwipeDir.DOWN_RIGHT,
            (0f to 0.3f) to SwipeDir.DOWN,
            (-0.25f to 0.25f) to SwipeDir.DOWN_LEFT,
            (-0.3f to 0f) to SwipeDir.LEFT,
            (-0.25f to -0.25f) to SwipeDir.UP_LEFT,
        )
        for ((vector, expected) in vectors) {
            assertEquals(expected, swipeGesture(dx = vector.first, dy = vector.second).swipe)
        }
    }

    @Test
    fun `quick still tap is a tap not a swipe`() {
        val state = TouchpadState()
        TouchpadProcessor.process(TouchSample(down = true, x = 0.5f, y = 0.5f, nowMs = 0), state, config)
        val d = TouchpadProcessor.process(TouchSample(down = false, x = 0.5f, y = 0.5f, nowMs = 100), state, config)
        assertTrue(d.tap)
        assertNull(d.swipe)
    }

    @Test
    fun `slow long drag is a drag not a swipe`() {
        val state = TouchpadState()
        TouchpadProcessor.process(TouchSample(down = true, x = 0.5f, y = 0.5f, nowMs = 0), state, config)
        val engage = TouchpadProcessor.process(TouchSample(down = true, x = 0.8f, y = 0.5f, nowMs = 700), state, config)
        assertTrue(engage.dragPress)
        val up = TouchpadProcessor.process(TouchSample(down = false, x = 0.8f, y = 0.5f, nowMs = 900), state, config)
        assertTrue(up.dragRelease)
        assertNull(up.swipe)
    }

    @Test
    fun `fast long flick beyond swipe window is neither swipe nor tap`() {
        // Deslocamento 0.3 ≥ swipeMinDistance, mas duração 350 ms > swipeMaxMs 300 ms.
        val d = swipeGesture(dx = 0.3f, dy = 0f, upAtMs = 350L)
        assertNull(d.swipe)
        assertFalse(d.tap)
        assertFalse(d.dragRelease)
        assertFalse(d.rightClick)
    }

    @Test
    fun `fast flick below min distance is not a swipe`() {
        // Deslocamento 0.1 < swipeMinDistance 0.22 (e > tapMoveDeadzone → sem tap).
        val d = swipeGesture(dx = 0.1f, dy = 0f, upAtMs = 120L)
        assertNull(d.swipe)
        assertFalse(d.tap)
    }

    @Test
    fun `swipe at exactly the max duration is still a swipe`() {
        val d = swipeGesture(dx = 0.3f, dy = 0f, upAtMs = 300L)
        assertEquals(SwipeDir.RIGHT, d.swipe)
    }

    @Test
    fun `swipe suppresses right click after a tap within the double tap window`() {
        val cfg = TouchpadConfig(doubleTapRightClick = true)
        val state = TouchpadState()
        TouchpadProcessor.process(TouchSample(down = true, x = 0.5f, y = 0.5f, nowMs = 0), state, cfg)
        val first = TouchpadProcessor.process(TouchSample(down = false, x = 0.5f, y = 0.5f, nowMs = 50), state, cfg)
        assertTrue(first.tap)
        // Segundo gesto rápido dentro da janela do duplo-toque, mas com percurso de
        // swipe: o up vira SWIPE — nunca rightClick nem tap.
        TouchpadProcessor.process(TouchSample(down = true, x = 0.5f, y = 0.5f, nowMs = 160), state, cfg)
        val second = TouchpadProcessor.process(TouchSample(down = false, x = 0.8f, y = 0.5f, nowMs = 260), state, cfg)
        assertEquals(SwipeDir.RIGHT, second.swipe)
        assertFalse(second.rightClick)
        assertFalse(second.tap)
    }

    @Test
    fun `swipe disabled keeps the decision identical to the current behavior`() {
        val cfg = TouchpadConfig(swipeEnabled = false)
        // Rápido + longe com swipe OFF: exatamente a decisão atual (moved >
        // tapMoveDeadzone → NONE — sem tap, sem delta, sem swipe).
        val d = swipeGesture(dx = 0.3f, dy = 0f, upAtMs = 120L, cfg = cfg)
        assertNull(d.swipe)
        assertFalse(d.tap)
        assertFalse(d.dragPress)
        assertFalse(d.dragRelease)
        assertFalse(d.rightClick)
        assertEquals(0, d.deltaX)
        assertEquals(0, d.deltaY)
        // E o caminho de tap continua intacto com swipe OFF.
        val state = TouchpadState()
        TouchpadProcessor.process(TouchSample(down = true, x = 0.5f, y = 0.5f, nowMs = 0), state, cfg)
        val tap = TouchpadProcessor.process(TouchSample(down = false, x = 0.5f, y = 0.5f, nowMs = 100), state, cfg)
        assertTrue(tap.tap)
        assertNull(tap.swipe)
    }

    @Test
    fun `deltas keep flowing during the swipe move and stop on the up`() {
        val state = TouchpadState()
        TouchpadProcessor.process(TouchSample(down = true, x = 0.5f, y = 0.5f, nowMs = 0), state, config)
        // Move do percurso: delta de mouse flui normalmente (≈0.2 * 350 = 70 px;
        // arredondamento float do toInt — intervalo, não valor exato).
        val move = TouchpadProcessor.process(TouchSample(down = true, x = 0.7f, y = 0.5f, nowMs = 100), state, config)
        assertTrue(move.deltaX in 60..70)
        assertNull(move.swipe)
        // Up conclui o swipe: delta zero, direção do vetor start→end.
        val up = TouchpadProcessor.process(TouchSample(down = false, x = 0.8f, y = 0.5f, nowMs = 150), state, config)
        assertEquals(SwipeDir.RIGHT, up.swipe)
        assertEquals(0, up.deltaX)
    }
}
