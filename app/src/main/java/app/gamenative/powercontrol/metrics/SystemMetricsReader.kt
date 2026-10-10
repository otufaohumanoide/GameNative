package app.gamenative.powercontrol.metrics

import android.os.SystemClock
import java.io.File
import java.util.Locale
import timber.log.Timber
import kotlin.math.roundToInt

enum class CpuUsageSource {
    PROC_STAT,
    CPU_FREQUENCY,
    UNAVAILABLE,
}

data class CpuUsageReading(val percent: Int, val source: CpuUsageSource)

data class GpuUsageReading(val percent: Int, val source: String)

/**
 * Device sysfs discovery shared by the metrics collector and the on-screen HUD.
 * Discovery results are cached process-wide so both consumers scan the tree once.
 */
object SystemMetricsSources {
    private const val TAG = "PowerMetrics"

    @Volatile
    private var gpuUsagePathsCache: List<String>? = null

    @Volatile
    private var thermalZonesCache: List<Pair<String, String>>? = null

    @Volatile
    private var cpuTempPathsCache: List<String>? = null

    @Volatile
    private var gpuTempPathsCache: List<String>? = null

    private val fixedGpuTempPaths = listOf(
        "/sys/class/kgsl/kgsl-3d0/temp",
        "/sys/class/kgsl/kgsl-3d0/devfreq/temp",
        "/sys/class/misc/mali0/device/temp",
        "/sys/kernel/gpu/temp",
        // Samsung/Exynos Mali: temperatura da GPU em °C (verificado no A14: 37.0).
        "/sys/kernel/gpu/gpu_tmu",
    )

    @Synchronized
    fun gpuUsagePaths(): List<String> {
        gpuUsagePathsCache?.let { return it }

        val candidates = linkedSetOf<String>()
        fun add(path: String) {
            if (File(path).canRead()) {
                candidates += path
            }
        }

        listOf(
            "/sys/class/kgsl/kgsl-3d0/gpubusy",
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
            "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
            "/sys/class/misc/mali0/device/utilisation",
            "/sys/class/misc/mali0/device/utilization",
            "/sys/class/misc/mali0/device/gpuinfo",
            "/sys/devices/platform/mali/utilization",
            "/sys/kernel/gpu/gpu_busy",
            "/sys/class/misc/pvrsrvkm/device/utilisation",
            "/sys/class/devfreq/gpu/load",
            // MediaTek GED e Mali kbase (frente I2 do plano mali). Paths verificados em
            // reference/WinlatorMali/.../HudDataSource.java:46-55; todos são porcentagens.
            "/sys/module/ged/parameters/gpu_loading",
            "/sys/module/mali_kbase/parameters/mali_gpu_utilization",
            "/sys/module/mali/parameters/mali_gpu_utilization",
            "/proc/mali/utilization",
        ).forEach(::add)

        listOf(
            File("/sys/class/devfreq"),
            File("/sys/devices/virtual/devfreq"),
        ).forEach { devfreqRoot ->
            if (!devfreqRoot.isDirectory) return@forEach
            val nodeDirs = devfreqRoot.listFiles { file -> file.isDirectory } ?: emptyArray<File>()
            for (nodeDir in nodeDirs) {
                val nodePath = nodeDir.path.lowercase(Locale.US)
                val looksLikeGpuNode = listOf("gpu", "mali", "g3d", "kgsl").any { token ->
                    nodePath.contains(token)
                }
                val usageFiles = listOf(
                    "gpu_busy_percentage",
                    "gpu_load",
                    "gpu_loading", // MediaTek GED (ex.: /sys/class/devfreq/mtk-dvfs-gpu)
                    "utilisation",
                    "utilization",
                    "load",
                    "gpuinfo",
                )
                for (fileName in usageFiles) {
                    val file = File(nodeDir, fileName)
                    if (!file.canRead()) continue
                    if (looksLikeGpuNode || fileName == "gpu_busy_percentage" || fileName == "gpuinfo") {
                        candidates += file.path
                    }
                }
                // devfreq expõe o par `busy_time`/`total_time` (cumulativo) em vez de uma linha só;
                // em nós de GPU alimentamos o mesmo GpuBusyDelta (frente I5 do plano mali). Fica por
                // último para não roubar a prioridade dos nós de porcentagem acima.
                if (looksLikeGpuNode &&
                    File(nodeDir, "busy_time").canRead() &&
                    File(nodeDir, "total_time").canRead()
                ) {
                    candidates += File(nodeDir, "busy_time").path
                }
            }
        }

        val paths = candidates.toList()
        gpuUsagePathsCache = paths
        Timber.tag(TAG).v("Discovered GPU usage paths: %s", paths.joinToString())
        return paths
    }

    @Volatile
    private var gpuFreqPathCache: String? = null

    /**
     * Caminho do `cur_freq` da GPU (kgsl ou devfreq). Ponto único de descoberta para o HUD,
     * o collector sempre-ativo e o sampler de debug (frente I3 do plano mali).
     */
    @Synchronized
    fun gpuFreqPath(): String? {
        gpuFreqPathCache?.let { return it }

        listOf(
            "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
            "/sys/class/kgsl/kgsl-3d0/gpuclk",
            "/sys/class/kgsl/kgsl-3d0/clock_mhz",
            // Samsung/Exynos Mali: kHz (verificado no A14; gpu_max_clock=949000).
            "/sys/kernel/gpu/gpu_clock",
        ).firstOrNull { File(it).canRead() }?.let {
            gpuFreqPathCache = it
            return it
        }

        for (root in listOf(File("/sys/class/devfreq"), File("/sys/devices/virtual/devfreq"))) {
            val nodes = root.listFiles { file -> file.isDirectory } ?: continue
            for (node in nodes) {
                val path = node.path.lowercase(Locale.US)
                if (listOf("gpu", "mali", "g3d", "kgsl").none { path.contains(it) }) continue
                val file = File(node, "cur_freq")
                if (file.canRead()) {
                    gpuFreqPathCache = file.path
                    return file.path
                }
            }
        }
        return null
    }

    /**
     * Frequência atual da GPU em MHz. O nó pode estar em Hz ou kHz (kgsl/devfreq variam); a
     * normalização é a mesma que o sampler de debug usava.
     */
    fun readGpuFreqMhz(): Int? {
        val raw = readLongFromLine(gpuFreqPath() ?: return null) ?: return null
        return when {
            raw >= 100_000_000L -> (raw / 1_000_000L).toInt()
            raw >= 100_000L -> (raw / 1_000L).toInt()
            else -> raw.toInt()
        }
    }

    /**
     * Thermal zones ranked for CPU representativeness (lower rank wins):
     * cpu-silicon, cpu-0, generic cpu, soc, s5p-tmu, cputop, tsens, cluster, big/little.
     *
     * O ranker é [ThermalZoneClassification], que descarta trip points de mitigação (`*-step`)
     * e `soc` isolado (State of Charge da bateria em %) - itens I1a/I1b do spec Mali/MediaTek.
     */
    @Synchronized
    fun cpuTempPaths(): List<String> {
        cpuTempPathsCache?.let { return it }

        val paths = prioritizePaths(allThermalZones(), ThermalZoneClassification::cpuRank)

        cpuTempPathsCache = paths
        return paths
    }

    /**
     * Vendor GPU temperature nodes first, then thermal zones ranked by
     * gpu-silicon, generic gpu, g3d, kgsl, mali. Trip points de mitigação (`*-step`) são
     * descartados por [ThermalZoneClassification] (item I1a do spec Mali/MediaTek).
     */
    @Synchronized
    fun gpuTempPaths(): List<String> {
        gpuTempPathsCache?.let { return it }

        val zonePaths = prioritizePaths(allThermalZones(), ThermalZoneClassification::gpuRank)

        val paths = (fixedGpuTempPaths + zonePaths).distinct()
        gpuTempPathsCache = paths
        return paths
    }

    @Synchronized
    private fun allThermalZones(): List<Pair<String, String>> {
        thermalZonesCache?.let { return it }

        val zones = listOf(
            File("/sys/class/thermal"),
            File("/sys/devices/virtual/thermal"),
        ).flatMap { thermalDir ->
            val zoneDirs = thermalDir.listFiles { file ->
                file.isDirectory && file.name.startsWith("thermal_zone")
            } ?: return@flatMap emptyList<Pair<String, String>>()

            zoneDirs.mapNotNull { zone ->
                val type = readFirstLine(File(zone, "type").path)
                    ?.trim()
                    ?.lowercase(Locale.US)
                    ?: return@mapNotNull null
                Pair(type, File(zone, "temp").path)
            }
        }.distinctBy { it.second }

        thermalZonesCache = zones
        return zones
    }

    private fun prioritizePaths(
        zones: List<Pair<String, String>>,
        ranker: (String) -> Int?,
    ): List<String> {
        return zones
            .mapNotNull { (type, path) ->
                ranker(type)?.let { rank -> Triple(type, path, rank) }
            }
            .sortedWith(compareBy({ it.third }, { it.second }))
            .map { it.second }
    }

    fun readTemperatureC(paths: List<String>): Int? {
        for (path in paths.distinct()) {
            parseTemperatureC(readFirstLine(path))?.let { return it }
        }
        return null
    }

    /**
     * Normaliza o conteúdo de um nó de temperatura para °C. Aceita inteiro ou decimal (alguns
     * nós Mali/Samsung reportam "37.0") e o formato em milésimos usado por vários kernels
     * (ex.: 45000 -> 45 °C). `null` quando fora da faixa plausível (1..150 °C).
     */
    fun parseTemperatureC(raw: String?): Int? {
        val value = raw?.trim()?.toDoubleOrNull() ?: return null
        val celsius = if (value > 1000.0) ((value + 500.0) / 1000.0).toInt() else value.roundToInt()
        return celsius.takeIf { it in 1..150 }
    }

    fun readFirstLine(path: String): String? {
        return try {
            File(path).bufferedReader().use { it.readLine() }
        } catch (_: Exception) {
            null
        }
    }

    fun readNthLine(path: String, lineIndex: Int): String? {
        return try {
            File(path).bufferedReader().useLines { lines ->
                lines.drop(lineIndex).firstOrNull()
            }
        } catch (_: Exception) {
            null
        }
    }

    fun readPercentFromLine(path: String): Int? {
        val raw = readFirstLine(path)?.trim() ?: return null
        val token = raw.split(Regex("\\s+"))
            .map { it.replace(Regex("[^0-9]"), "") }
            .firstOrNull { it.isNotEmpty() }
            ?: return null
        return token.toIntOrNull()?.coerceIn(0, 100)
    }

    fun readLongFromLine(path: String): Long? {
        return readFirstLine(path)?.trim()?.toLongOrNull()
    }
}

/**
 * Delta-based `/proc/stat` reader. Each instance owns its own previous sample,
 * so several consumers can sample at independent cadences.
 */
class CpuUsageSampler {
    private var lastTotal: Long? = null
    private var lastIdle: Long? = null

    fun reset() {
        lastTotal = null
        lastIdle = null
    }

    fun sample(): CpuUsageReading? {
        val parts = SystemMetricsSources.readFirstLine("/proc/stat")
            ?.trim()
            ?.split(Regex("\\s+"))

        if (parts != null && parts.size >= 5 && parts.firstOrNull() == "cpu") {
            val values = parts.drop(1).mapNotNull { it.toLongOrNull() }
            if (values.size >= 4) {
                val idle = values.getOrElse(3) { 0L }
                val iowait = values.getOrElse(4) { 0L }
                val total = values.sum()
                val idleTotal = idle + iowait

                val previousTotal = lastTotal
                val previousIdle = lastIdle
                lastTotal = total
                lastIdle = idleTotal

                if (previousTotal != null && previousIdle != null) {
                    val totalDiff = total - previousTotal
                    val idleDiff = idleTotal - previousIdle
                    if (totalDiff > 0) {
                        val percent = (((totalDiff - idleDiff).coerceAtLeast(0L)) * 100L / totalDiff)
                            .toInt()
                            .coerceIn(0, 100)
                        return CpuUsageReading(percent, CpuUsageSource.PROC_STAT)
                    }
                }
            }
        }

        val fallback = readFromFrequency() ?: return null
        return CpuUsageReading(fallback, CpuUsageSource.CPU_FREQUENCY)
    }

    /**
     * Biased proxy: reports the aggregate clock ratio, not occupancy. Only used
     * when /proc/stat is unreadable, and always tagged as such on the sample.
     */
    private fun readFromFrequency(): Int? {
        var currentTotal = 0L
        var maxTotal = 0L

        repeat(Runtime.getRuntime().availableProcessors()) { cpuIndex ->
            val current = SystemMetricsSources
                .readLongFromLine("/sys/devices/system/cpu/cpu$cpuIndex/cpufreq/scaling_cur_freq")
            val max = SystemMetricsSources
                .readLongFromLine("/sys/devices/system/cpu/cpu$cpuIndex/cpufreq/cpuinfo_max_freq")
            if (current != null && max != null && max > 0L) {
                currentTotal += current.coerceIn(0L, max)
                maxTotal += max
            }
        }

        if (maxTotal <= 0L) {
            return null
        }

        return ((currentTotal * 100L) / maxTotal).toInt().coerceIn(0, 100)
    }
}

/**
 * Turns the cumulative `busy total` pair of kgsl `gpubusy` into a load percentage
 * for the interval between two reads.
 *
 * Some kernels clear the counters on read instead of accumulating; a repeated
 * decrease switches this sampler to reading the raw pair as an interval value.
 */
class GpuBusyDelta {
    private var lastBusy = -1L
    private var lastTotal = -1L
    private var decreaseStreak = 0

    var perReadCounters: Boolean = false
        private set

    fun reset() {
        lastBusy = -1L
        lastTotal = -1L
        decreaseStreak = 0
    }

    fun update(busy: Long, total: Long): Int? {
        if (busy < 0L || total <= 0L) return null

        val previousBusy = lastBusy
        val previousTotal = lastTotal
        lastBusy = busy
        lastTotal = total

        if (perReadCounters) {
            return percent(busy, total)
        }
        if (previousBusy < 0L || previousTotal < 0L) return null

        val busyDelta = busy - previousBusy
        val totalDelta = total - previousTotal
        if (busyDelta < 0L || totalDelta <= 0L) {
            decreaseStreak++
            if (decreaseStreak >= PER_READ_DETECTION_STREAK) {
                perReadCounters = true
            }
            return null
        }

        decreaseStreak = 0
        return percent(busyDelta, totalDelta)
    }

    private fun percent(busy: Long, total: Long): Int? {
        if (total <= 0L) return null
        return ((busy * 100L) / total).toInt().coerceIn(0, 100)
    }

    private companion object {
        const val PER_READ_DETECTION_STREAK = 2
    }
}

/**
 * Reads the first GPU usage node that yields a value, keeping the per-node delta
 * state needed by counter-style nodes.
 */
class GpuUsageSampler {
    private val busyDelta = GpuBusyDelta()
    private var lastGpuInfoMs: Long? = null
    private var lastGpuInfoWallMs: Long = 0L

    fun reset() {
        busyDelta.reset()
        lastGpuInfoMs = null
        lastGpuInfoWallMs = 0L
    }

    fun sample(): GpuUsageReading? {
        return SystemMetricsSources.gpuUsagePaths()
            .asSequence()
            .mapNotNull { readSample(it) }
            .firstOrNull()
    }

    private fun readSample(path: String): GpuUsageReading? {
        return when (path.substringAfterLast("/")) {
            "gpubusy" -> {
                val raw = SystemMetricsSources.readFirstLine(path)?.trim() ?: return null
                val parts = raw.split(Regex("\\s+"))
                if (parts.size < 2) return null
                val busy = parts[0].toLongOrNull() ?: return null
                val total = parts[1].toLongOrNull() ?: return null
                busyDelta.update(busy, total)?.let { GpuUsageReading(it, path) }
            }
            "gpuinfo" -> {
                val line = SystemMetricsSources.readNthLine(path, 1)?.trim() ?: return null
                val gpuMs = line.split(Regex("\\s+")).lastOrNull()?.toLongOrNull() ?: return null
                val now = SystemClock.elapsedRealtime()
                val previousMs = lastGpuInfoMs
                val previousWall = lastGpuInfoWallMs
                lastGpuInfoMs = gpuMs
                lastGpuInfoWallMs = now
                if (previousMs == null || previousWall <= 0L) return null
                val wallDelta = now - previousWall
                if (wallDelta <= 0L) return null
                val gpuDelta = (gpuMs - previousMs).coerceAtLeast(0L)
                GpuUsageReading(((gpuDelta * 100L) / wallDelta).toInt().coerceIn(0, 100), path)
            }
            // devfreq: `busy_time` e `total_time` são arquivos separados no diretório do nó.
            "busy_time" -> {
                val busy = SystemMetricsSources.readLongFromLine(path) ?: return null
                val total = SystemMetricsSources.readLongFromLine(
                    path.substringBeforeLast('/') + "/total_time",
                ) ?: return null
                busyDelta.update(busy, total)?.let { GpuUsageReading(it, path) }
            }
            else -> SystemMetricsSources.readPercentFromLine(path)?.let { percent ->
                GpuUsageReading(percent, path)
            }
        }
    }
}
