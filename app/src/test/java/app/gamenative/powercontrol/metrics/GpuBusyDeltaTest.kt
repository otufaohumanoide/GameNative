package app.gamenative.powercontrol.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes JVM da leitura de contadores de GPU (frente I5 do `docs/plano-mali-mediatek.md`).
 *
 * `GpuBusyDelta` é o núcleo puro compartilhado pelo par kgsl (`gpubusy`) e pelo par devfreq
 * `busy_time`/`total_time`: converte dois contadores cumulativos em carga (%) e detecta kernels
 * que zeram o contador a cada leitura.
 */
class GpuBusyDeltaTest {

    @Test
    fun `first read establishes the baseline without a percentage`() {
        assertNull(GpuBusyDelta().update(10, 100))
    }

    @Test
    fun `delta between reads is the interval load`() {
        val delta = GpuBusyDelta()
        assertNull(delta.update(10, 100))
        assertEquals(50, delta.update(60, 200)) // 50 / 100
        assertEquals(25, delta.update(85, 300)) // 25 / 100
    }

    @Test
    fun `invalid inputs are ignored`() {
        val delta = GpuBusyDelta()
        assertNull(delta.update(-1, 100))
        assertNull(delta.update(10, 0))
        assertNull(delta.update(10, -5))
    }

    @Test
    fun `decreasing counters are detected as per-read after two occurrences`() {
        val delta = GpuBusyDelta()
        delta.update(100, 200)
        assertNull(delta.update(50, 100)) // streak 1
        assertFalse(delta.perReadCounters)
        assertNull(delta.update(40, 90)) // streak 2 -> per-read
        assertTrue(delta.perReadCounters)
        assertEquals(40, delta.update(40, 100)) // raw pair read as an interval
    }

    @Test
    fun `reset re-establishes the baseline`() {
        val delta = GpuBusyDelta()
        delta.update(10, 100)
        assertEquals(50, delta.update(60, 200))
        delta.reset()
        assertNull(delta.update(70, 140)) // needs two reads again
        assertEquals(50, delta.update(120, 240))
    }
}
