package app.gamenative.gamepad.processing

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F0 (spec 2026-08-15-input-core-avancado): testes JVM do LatencyTracker — correlação
 * begin/end, frescor, sobrescrita de pendência órfã, percentis com interpolação e
 * limite do anel.
 */
class LatencyTrackerTest {

    @Before
    fun setUp() {
        LatencyTracker.enabled = true
        LatencyTracker.reset()
    }

    @After
    fun tearDown() {
        LatencyTracker.enabled = false
        LatencyTracker.reset()
    }

    @Test
    fun `desligado nao registra nada`() {
        LatencyTracker.enabled = false
        LatencyTracker.begin(LatencyTracker.Source.KEY, 0L)
        LatencyTracker.end(LatencyTracker.Source.KEY, 10_000_000L)
        assertEquals(0, LatencyTracker.snapshot(LatencyTracker.Source.KEY).count)
    }

    @Test
    fun `par begin-end registra uma amostra`() {
        LatencyTracker.begin(LatencyTracker.Source.KEY, 1_000_000_000L)
        LatencyTracker.end(LatencyTracker.Source.KEY, 1_010_000_000L)
        val s = LatencyTracker.snapshot(LatencyTracker.Source.KEY)
        assertEquals(1, s.count)
        assertEquals(10f, s.p50Ms, 0.01f)
        assertEquals(10f, s.p95Ms, 0.01f)
        assertEquals(10f, s.minMs, 0.01f)
        assertEquals(10f, s.maxMs, 0.01f)
    }

    @Test
    fun `end sem begin e descartado`() {
        LatencyTracker.end(LatencyTracker.Source.KEY, 1_000_000_000L)
        assertEquals(0, LatencyTracker.snapshot(LatencyTracker.Source.KEY).count)
    }

    @Test
    fun `begin orfao e sobrescrito pelo begin seguinte`() {
        // begin A sem end (menu consumiu o evento)...
        LatencyTracker.begin(LatencyTracker.Source.KEY, 1_000_000_000L)
        // ...begin B + end B: a amostra mede B, nunca A.
        LatencyTracker.begin(LatencyTracker.Source.KEY, 1_020_000_000L)
        LatencyTracker.end(LatencyTracker.Source.KEY, 1_022_000_000L)
        val s = LatencyTracker.snapshot(LatencyTracker.Source.KEY)
        assertEquals(1, s.count)
        assertEquals(2f, s.p50Ms, 0.01f)
    }

    @Test
    fun `par acima da janela de frescor e descartado`() {
        LatencyTracker.begin(LatencyTracker.Source.MOTION, 1_000_000_000L)
        LatencyTracker.end(LatencyTracker.Source.MOTION, 1_000_000_000L + 200_000_000L)
        assertEquals(0, LatencyTracker.snapshot(LatencyTracker.Source.MOTION).count)
    }

    @Test
    fun `elapsed negativo (clock para tras) e descartado`() {
        LatencyTracker.begin(LatencyTracker.Source.MOTION, 2_000_000_000L)
        LatencyTracker.end(LatencyTracker.Source.MOTION, 1_000_000_000L)
        assertEquals(0, LatencyTracker.snapshot(LatencyTracker.Source.MOTION).count)
    }

    @Test
    fun `fontes sao independentes`() {
        LatencyTracker.begin(LatencyTracker.Source.KEY, 1_000_000_000L)
        LatencyTracker.end(LatencyTracker.Source.KEY, 1_005_000_000L)
        LatencyTracker.begin(LatencyTracker.Source.MOTION, 1_000_000_000L)
        LatencyTracker.end(LatencyTracker.Source.MOTION, 1_003_000_000L)
        assertEquals(1, LatencyTracker.snapshot(LatencyTracker.Source.KEY).count)
        assertEquals(5f, LatencyTracker.snapshot(LatencyTracker.Source.KEY).p50Ms, 0.01f)
        assertEquals(1, LatencyTracker.snapshot(LatencyTracker.Source.MOTION).count)
        assertEquals(3f, LatencyTracker.snapshot(LatencyTracker.Source.MOTION).p50Ms, 0.01f)
        assertEquals(0, LatencyTracker.snapshot(LatencyTracker.Source.SENSOR).count)
    }

    @Test
    fun `percentis interpolam (metodo 7)`() {
        // 1..100 ms
        var t = 0L
        for (i in 1..100) {
            LatencyTracker.begin(LatencyTracker.Source.KEY, t)
            LatencyTracker.end(LatencyTracker.Source.KEY, t + i * 1_000_000L)
            t += 1_000_000_000L
        }
        val s = LatencyTracker.snapshot(LatencyTracker.Source.KEY)
        assertEquals(100, s.count)
        // pos = 99*0.5 = 49.5 → entre 50 e 51 → 50.5
        assertEquals(50.5f, s.p50Ms, 0.01f)
        // pos = 99*0.95 = 94.05 → entre 95 e 96 → 95.05
        assertEquals(95.05f, s.p95Ms, 0.01f)
        assertEquals(1f, s.minMs, 0.01f)
        assertEquals(100f, s.maxMs, 0.01f)
    }

    @Test
    fun `anel limita a CAPACIDADE sem perder as recentes`() {
        var t = 0L
        for (i in 1..5000) {
            LatencyTracker.begin(LatencyTracker.Source.KEY, t)
            LatencyTracker.end(LatencyTracker.Source.KEY, t + 1_000_000L)
            t += 1_000_000_000L
        }
        val s = LatencyTracker.snapshot(LatencyTracker.Source.KEY)
        assertEquals(4096, s.count)
        assertEquals(1f, s.minMs, 0.01f)
        assertEquals(1f, s.maxMs, 0.01f)
    }

    @Test
    fun `reset limpa amostras e pendencia`() {
        LatencyTracker.begin(LatencyTracker.Source.KEY, 1_000_000_000L)
        LatencyTracker.end(LatencyTracker.Source.KEY, 1_002_000_000L)
        LatencyTracker.reset()
        assertEquals(0, LatencyTracker.snapshot(LatencyTracker.Source.KEY).count)
        // pendência também morre: end pós-reset não emparelha com begin pré-reset
        LatencyTracker.end(LatencyTracker.Source.KEY, 2_000_000_000L)
        assertEquals(0, LatencyTracker.snapshot(LatencyTracker.Source.KEY).count)
    }

    @Test
    fun `report descreve fontes sem amostra`() {
        LatencyTracker.reset()
        val report = LatencyTracker.report()
        assertTrue(report.contains("KEY=no-samples"))
        assertTrue(report.contains("MOTION=no-samples"))
        assertTrue(report.contains("SENSOR=no-samples"))
    }
}
