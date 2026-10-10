package app.gamenative.powercontrol.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Testes JVM de `SystemMetricsSources.parseTemperatureC` (frentes I1/I2 do plano mali):
 * os nós variam entre inteiro em °C, decimal (Mali/Samsung `gpu_tmu`, ex. "37.0") e milésimos
 * (ex. "45000").
 */
class TemperatureParsingTest {

    @Test
    fun `plain celsius integers pass through`() {
        assertEquals(45, SystemMetricsSources.parseTemperatureC("45"))
        assertEquals(37, SystemMetricsSources.parseTemperatureC("  37 "))
        assertEquals(1, SystemMetricsSources.parseTemperatureC("1"))
        assertEquals(150, SystemMetricsSources.parseTemperatureC("150"))
    }

    @Test
    fun `decimal celsius is accepted`() {
        assertEquals(37, SystemMetricsSources.parseTemperatureC("37.0"))
        assertEquals(38, SystemMetricsSources.parseTemperatureC("37.6"))
    }

    @Test
    fun `millidegrees are converted`() {
        assertEquals(45, SystemMetricsSources.parseTemperatureC("45000"))
        assertEquals(45, SystemMetricsSources.parseTemperatureC("45200"))
        assertEquals(37, SystemMetricsSources.parseTemperatureC("37000"))
    }

    @Test
    fun `garbage and implausible values are rejected`() {
        assertNull(SystemMetricsSources.parseTemperatureC(null))
        assertNull(SystemMetricsSources.parseTemperatureC(""))
        assertNull(SystemMetricsSources.parseTemperatureC("   "))
        assertNull(SystemMetricsSources.parseTemperatureC("abc"))
        assertNull(SystemMetricsSources.parseTemperatureC("0"))
        assertNull(SystemMetricsSources.parseTemperatureC("200"))
        assertNull(SystemMetricsSources.parseTemperatureC("-5"))
    }
}
