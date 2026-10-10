package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThermalStatusesTest {

    @Test
    fun `none and unknown statuses render nothing in the HUD`() {
        assertNull(ThermalStatuses.label(ThermalStatuses.NONE))
        assertNull(ThermalStatuses.label(-1))
        assertNull(ThermalStatuses.label(7))
    }

    @Test
    fun `every mitigation level has its label`() {
        assertEquals("LIGHT", ThermalStatuses.label(ThermalStatuses.LIGHT))
        assertEquals("MODERATE", ThermalStatuses.label(ThermalStatuses.MODERATE))
        assertEquals("SEVERE", ThermalStatuses.label(ThermalStatuses.SEVERE))
        assertEquals("CRITICAL", ThermalStatuses.label(ThermalStatuses.CRITICAL))
        assertEquals("EMERGENCY", ThermalStatuses.label(ThermalStatuses.EMERGENCY))
        assertEquals("SHUTDOWN", ThermalStatuses.label(ThermalStatuses.SHUTDOWN))
    }

    @Test
    fun `report names include NONE and fall back to the raw value`() {
        assertEquals("NONE", ThermalStatuses.name(ThermalStatuses.NONE))
        assertEquals("MODERATE", ThermalStatuses.name(ThermalStatuses.MODERATE))
        assertEquals("SHUTDOWN", ThermalStatuses.name(ThermalStatuses.SHUTDOWN))
        assertEquals("7", ThermalStatuses.name(7))
        assertEquals("-1", ThermalStatuses.name(-1))
    }
}
