package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the renderer present-mode mapping (spec 2026-10-09-mali-mediatek-performance).
 *
 * The values follow the Vulkan enum order (0 = IMMEDIATE, 1 = MAILBOX, 2 = FIFO,
 * 3 = FIFO_RELAXED). Unknown input must fall back to FIFO, matching the native
 * fallback when the driver does not support the requested mode.
 */
class PresentModeMappingTest {

    @Test
    fun `every ui mode maps to its vulkan value`() {
        assertEquals(2, PresentModeMapping.toVkPresentMode("fifo"))
        assertEquals(1, PresentModeMapping.toVkPresentMode("mailbox"))
        assertEquals(0, PresentModeMapping.toVkPresentMode("immediate"))
        assertEquals(3, PresentModeMapping.toVkPresentMode("relaxed"))
    }

    @Test
    fun `mapping is case insensitive`() {
        assertEquals(1, PresentModeMapping.toVkPresentMode("Mailbox"))
        assertEquals(0, PresentModeMapping.toVkPresentMode("IMMEDIATE"))
        assertEquals(3, PresentModeMapping.toVkPresentMode("Relaxed"))
        assertEquals(2, PresentModeMapping.toVkPresentMode("FIFO"))
    }

    @Test
    fun `unknown or missing modes fall back to fifo`() {
        assertEquals(2, PresentModeMapping.toVkPresentMode(null))
        assertEquals(2, PresentModeMapping.toVkPresentMode(""))
        assertEquals(2, PresentModeMapping.toVkPresentMode("bogus"))
    }

    @Test
    fun `ui modes are unique and keep the persisted indices stable`() {
        // The dropdown stores the INDEX, so the first two entries must never move:
        // reordering would silently change the present mode of existing containers.
        assertEquals(listOf("fifo", "mailbox"), PresentModeMapping.UI_MODES.take(2))
        assertEquals(4, PresentModeMapping.UI_MODES.size)
        assertEquals(PresentModeMapping.UI_MODES.size, PresentModeMapping.UI_MODES.toSet().size)
        assertTrue(PresentModeMapping.UI_MODES.containsAll(listOf("immediate", "relaxed")))
    }
}
