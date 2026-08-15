package app.gamenative.gamepad.processing

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F (spec 2026-08-16-F-radial-v2-modeshift-turbo, §1.4): scheduler PURO de
 * turbo/rapid-fire — onda quadrada de down/up sintéticos com duty cycle 50%.
 */
class TurboSchedulerTest {

    @Test
    fun `periodo default e 80 ms`() {
        assertEquals(80L, TurboScheduler.PERIOD_DEFAULT_MS)
    }

    @Test
    fun `nextToggleAt e meio periodo adiante`() {
        assertEquals(1040L, TurboScheduler.nextToggleAt(1000L, 80L, 0))
        assertEquals(1040L, TurboScheduler.nextToggleAt(1000L, 80L, 1))
        assertEquals(1060L, TurboScheduler.nextToggleAt(1000L, 120L, 0))
    }

    @Test
    fun `nextToggleAt e deterministico`() {
        val first = TurboScheduler.nextToggleAt(5000L, TurboScheduler.PERIOD_DEFAULT_MS, 0)
        val second = TurboScheduler.nextToggleAt(5000L, TurboScheduler.PERIOD_DEFAULT_MS, 0)
        assertEquals(first, second)
    }

    @Test
    fun `fases alternam e o ciclo completo dura o periodo default`() {
        // DOWN imediato em t0 (o handler injeta na hora); UP em t0+40 (fase 1);
        // próximo DOWN em t0+80 (fase 0) — ciclo down→up→down = 80 ms = período.
        val down = 1000L
        val up = TurboScheduler.nextToggleAt(down, TurboScheduler.PERIOD_DEFAULT_MS, 1)
        assertEquals(1040L, up)
        val nextDown = TurboScheduler.nextToggleAt(up, TurboScheduler.PERIOD_DEFAULT_MS, 0)
        assertEquals(1080L, nextDown)
        assertEquals(TurboScheduler.PERIOD_DEFAULT_MS, nextDown - down)
    }

    @Test
    fun `periodo degradado clampado ao minimo`() {
        assertEquals(1001L, TurboScheduler.nextToggleAt(1000L, 0L, 0))
        assertEquals(1001L, TurboScheduler.nextToggleAt(1000L, -10L, 1))
        assertEquals(1001L, TurboScheduler.nextToggleAt(1000L, 1L, 0))
    }
}
