package app.gamenative.powercontrol.metrics

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Testes JVM do formato do `metrics-*.jsonl` (fonte de dados dos A/B do
 * `docs/plano-mali-mediatek.md`), incluindo os campos novos de I3/I4.
 */
class MetricsJsonTest {

    @Test
    fun `full sample serializes every field`() {
        val line = MetricsJson.line(
            MetricsSnapshot(
                timestampMs = 1234L,
                fps = 59.94f,
                frameTimeP50Ms = 16.67f,
                frameTimeP95Ms = 20.0f,
                frameTimeMaxMs = 33.0f,
                slowFrameCount = 2,
                totalFrameCount = 100,
                cpuUsagePercent = 42.5f,
                cpuUsageSource = CpuUsageSource.PROC_STAT,
                gpuUsagePercent = 87.0f,
                cpuTempC = 55,
                gpuTempC = 61,
                gpuFreqMhz = 850,
                thermalStatus = 3,
            ),
        )

        assertEquals(
            "{\"timestampMs\":1234,\"fps\":59.94,\"frameTimeP50Ms\":16.67,\"frameTimeP95Ms\":20.00," +
                "\"frameTimeMaxMs\":33.00,\"slowFrameCount\":2,\"totalFrameCount\":100," +
                "\"cpuUsagePercent\":42.5,\"cpuUsageSource\":\"PROC_STAT\",\"gpuUsagePercent\":87.0," +
                "\"cpuTempC\":55,\"gpuTempC\":61,\"gpuFreqMhz\":850,\"thermalStatus\":3}",
            line,
        )
    }

    @Test
    fun `missing optional readings are written as null`() {
        val line = MetricsJson.line(
            MetricsSnapshot(
                timestampMs = 7L,
                fps = 0f,
                frameTimeP50Ms = 0f,
                frameTimeP95Ms = 0f,
                frameTimeMaxMs = 0f,
                slowFrameCount = 0,
                totalFrameCount = 0,
                cpuUsagePercent = null,
                cpuUsageSource = CpuUsageSource.UNAVAILABLE,
                gpuUsagePercent = null,
                cpuTempC = null,
                gpuTempC = null,
            ),
        )

        assertEquals(
            "{\"timestampMs\":7,\"fps\":0.00,\"frameTimeP50Ms\":0.00,\"frameTimeP95Ms\":0.00," +
                "\"frameTimeMaxMs\":0.00,\"slowFrameCount\":0,\"totalFrameCount\":0," +
                "\"cpuUsagePercent\":null,\"cpuUsageSource\":\"UNAVAILABLE\",\"gpuUsagePercent\":null," +
                "\"cpuTempC\":null,\"gpuTempC\":null,\"gpuFreqMhz\":null,\"thermalStatus\":null}",
            line,
        )
    }
}
