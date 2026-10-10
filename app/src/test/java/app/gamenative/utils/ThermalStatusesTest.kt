package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThermalStatusesTest {

    @Test
    fun `none and unknown statuses render nothing`() {
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
}
