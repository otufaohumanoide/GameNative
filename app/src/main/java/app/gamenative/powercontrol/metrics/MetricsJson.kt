package app.gamenative.powercontrol.metrics

import java.util.Locale

/**
 * Serializador puro de uma amostra para o `metrics-*.jsonl` (lógica extraída de
 * [PerformanceMetricsCollector] para ser testável em JVM).
 *
 * O formato é o insumo dos protocolos de A/B do `docs/plano-mali-mediatek.md`; campos opcionais
 * são gravados como `null` (JSON) e floats usam `Locale.US`/duas casas.
 */
object MetricsJson {

    fun line(snapshot: MetricsSnapshot): String {
        return String.format(
            Locale.US,
            "{\"timestampMs\":%d,\"fps\":%.2f,\"frameTimeP50Ms\":%.2f,\"frameTimeP95Ms\":%.2f," +
                "\"frameTimeMaxMs\":%.2f,\"slowFrameCount\":%d,\"totalFrameCount\":%d," +
                "\"cpuUsagePercent\":%s,\"cpuUsageSource\":\"%s\",\"gpuUsagePercent\":%s," +
                "\"cpuTempC\":%s,\"gpuTempC\":%s,\"gpuFreqMhz\":%s,\"thermalStatus\":%s," +
                "\"thermalHeadroom\":%s}",
            snapshot.timestampMs,
            snapshot.fps,
            snapshot.frameTimeP50Ms,
            snapshot.frameTimeP95Ms,
            snapshot.frameTimeMaxMs,
            snapshot.slowFrameCount,
            snapshot.totalFrameCount,
            percent(snapshot.cpuUsagePercent),
            snapshot.cpuUsageSource.name,
            percent(snapshot.gpuUsagePercent),
            snapshot.cpuTempC?.toString() ?: "null",
            snapshot.gpuTempC?.toString() ?: "null",
            snapshot.gpuFreqMhz?.toString() ?: "null",
            snapshot.thermalStatus?.toString() ?: "null",
            ratio(snapshot.thermalHeadroom),
        )
    }

    private fun percent(value: Float?): String =
        value?.let { String.format(Locale.US, "%.1f", it) } ?: "null"

    private fun ratio(value: Float?): String =
        value?.let { String.format(Locale.US, "%.2f", it) } ?: "null"
}
