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
        assertEquals(1040L, TurboScheduler.nextToggleAt(1000L, 80L))
        assertEquals(1060L, TurboScheduler.nextToggleAt(1000L, 120L))
    }

    @Test
    fun `nextToggleAt e deterministico`() {
        val first = TurboScheduler.nextToggleAt(5000L, TurboScheduler.PERIOD_DEFAULT_MS)
        val second = TurboScheduler.nextToggleAt(5000L, TurboScheduler.PERIOD_DEFAULT_MS)
        assertEquals(first, second)
    }

    @Test
    fun `ciclo completo down-up-down dura o periodo default`() {
        // A alternância de FASE vive no PhysicalControllerHandler (turboStates:
        // 0 = solta → DOWN, 1 = segurada → UP — revisão de fechamento 2026-08-16,
        // nit nº 1: a função pura não recebe fase). Este teste prende só o
        // contrato de TIMING do ciclo: DOWN imediato em t0 (o handler injeta na
        // hora); UP em t0+40; próximo DOWN em t0+80 — ciclo = 80 ms = período.
        val down = 1000L
        val up = TurboScheduler.nextToggleAt(down, TurboScheduler.PERIOD_DEFAULT_MS)
        assertEquals(1040L, up)
        val nextDown = TurboScheduler.nextToggleAt(up, TurboScheduler.PERIOD_DEFAULT_MS)
        assertEquals(1080L, nextDown)
        assertEquals(TurboScheduler.PERIOD_DEFAULT_MS, nextDown - down)
    }

    @Test
    fun `periodo degradado clampado ao minimo`() {
        assertEquals(1001L, TurboScheduler.nextToggleAt(1000L, 0L))
        assertEquals(1001L, TurboScheduler.nextToggleAt(1000L, -10L))
        assertEquals(1001L, TurboScheduler.nextToggleAt(1000L, 1L))
    }
}
