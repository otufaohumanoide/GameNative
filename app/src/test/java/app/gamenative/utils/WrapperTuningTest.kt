package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the wrapper WSI knob (frente F do plano Mali/MediaTek).
 *
 * The contract that matters: the default must stay **off**, because the wrapper itself keeps
 * `WRAPPER_BYPASS_SWAPCHAIN_PLACED` at 0 with a `TODO: turn on by default if safe`, and the
 * plan's golden rule forbids making tuning a default without an on-device A/B.
 */
class WrapperTuningTest {

    @Test
    fun `only the literal 1 enables the swapchain bypass`() {
        assertTrue(WrapperTuning.bypassSwapchainPlaced("1"))
        assertTrue(WrapperTuning.bypassSwapchainPlaced(" 1 "))
    }

    @Test
    fun `absent empty or other values stay off - matching the wrapper default`() {
        assertFalse(WrapperTuning.bypassSwapchainPlaced(null))
        assertFalse(WrapperTuning.bypassSwapchainPlaced(""))
        assertFalse(WrapperTuning.bypassSwapchainPlaced("  "))
        assertFalse(WrapperTuning.bypassSwapchainPlaced("0"))
        assertFalse(WrapperTuning.bypassSwapchainPlaced("true"))
        assertFalse(WrapperTuning.bypassSwapchainPlaced("2"))
    }

    @Test
    fun `env value is always explicit so ui and wrapper cannot diverge`() {
        assertEquals("1", WrapperTuning.bypassSwapchainPlacedEnv("1"))
        assertEquals("0", WrapperTuning.bypassSwapchainPlacedEnv(null))
        assertEquals("0", WrapperTuning.bypassSwapchainPlacedEnv("0"))
        assertEquals("0", WrapperTuning.bypassSwapchainPlacedEnv("garbage"))
    }
}
