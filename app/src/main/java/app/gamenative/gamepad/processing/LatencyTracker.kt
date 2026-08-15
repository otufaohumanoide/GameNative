package app.gamenative.gamepad.processing

/**
 * Medição de latência de ponta a ponta do pipeline de input (F0 do spec
 * 2026-08-15-input-core-avancado): t0 na ingestão (`MainActivity.dispatchKeyEvent`/
 * `dispatchGenericMotionEvent` e `GamepadHub.onSensorSample`) → t1 no
 * `PhysicalControllerHandler` (onKeyEvent/onGenericMotionEvent/applyCameraGyro).
 *
 * Baseline para a decisão "migrar C++/Rust ou não" — critério de saída do F0:
 * p95 < 16 ms ⇒ migração arquivada por falta de evidência.
 *
 * Design:
 * - Puro Kotlin, ZERO android.* — o tempo entra como parâmetro (`System.nanoTime()`
 *   no chamador) e os percentis são computáveis em JVM (testado).
 * - Hot path barato: `enabled` off ⇒ begin/end são um load + branch. On ⇒ um write/
 *   read de slot por evento, sem alocação em rajada.
 * - Correlação begin/end por SLOT pendente por fonte (KEY/MOTION/SENSOR): o dispatch
 *   é síncrono na main thread (EventDispatcher é síncrono; sensor entrega na main
 *   via Looper — P2-7), então cada begin é imediatamente seguido de UM end dentro da
 *   mesma pilha de chamadas. Begin sem end (menu aberto consumiu o evento, rota sem
 *   handler) é SOBRESCRITO pelo begin seguinte — nunca contamina a amostra seguinte.
 * - Guarda de frescor (100 ms): end órfão (handler chamado por rota que não passou
 *   pelo begin) emparelharia com um begin velho — acima da janela o par é descartado.
 * - Anel limitado por fonte (4096 amostras): memória constante, amostras recentes.
 *
 * `latency:report` do harness (DebugGamepadInput) e o HUD (LatencyDebugOverlay)
 * leem [snapshot]/[allSnapshots] — fora do hot path.
 */
object LatencyTracker {

    /** Fontes medidas. KEY/MOTION = dispatch → handler; SENSOR = onSensorSample → applyCameraGyro. */
    enum class Source { KEY, MOTION, SENSOR }

    /** Estatística de uma fonte (ms). */
    data class Snapshot(
        val count: Int,
        val p50Ms: Float,
        val p95Ms: Float,
        val minMs: Float,
        val maxMs: Float,
    ) {
        companion object {
            val EMPTY = Snapshot(0, 0f, 0f, 0f, 0f)
        }
    }

    /** Habilita/desabilita a coleta (toggle `debug.gamenative.latency 1`). */
    @Volatile
    var enabled: Boolean = false

    private const val CAPACITY = 4096

    /** Não-const: `entries.size` não é valor de tempo de compilação. */
    private val SOURCES = Source.entries.size

    /** Janela de frescor: acima disso o par begin/end é descartado (100 ms). */
    private const val MAX_PAIR_NANOS = 100_000_000L

    /** Slot pendente por fonte (-1 = sem pendência; 0 é timestamp legítimo). Main thread only. */
    private val pending = LongArray(SOURCES) { -1L }

    /** Anéis por fonte. Main thread only. */
    private val rings = Array(SOURCES) { LongArray(CAPACITY) }
    private val heads = IntArray(SOURCES)
    private val counts = IntArray(SOURCES)

    /**
     * t0 na ingestão. Sem end no mesmo dispatch (menu consumiu, rota sem handler) o
     * slot é sobrescrito pelo próximo begin — amostra nunca contamina a seguinte.
     */
    fun begin(source: Source, nowNanos: Long) {
        if (!enabled) return
        pending[source.ordinal] = nowNanos
    }

    /** t1 no PhysicalControllerHandler. Pendência ausente/velha = descartado. */
    fun end(source: Source, nowNanos: Long) {
        if (!enabled) return
        val idx = source.ordinal
        val start = pending[idx]
        if (start < 0L) return
        pending[idx] = -1L
        val elapsed = nowNanos - start
        if (elapsed < 0L || elapsed > MAX_PAIR_NANOS) return
        record(idx, elapsed)
    }

    /** Estatística de uma fonte; ordena uma cópia do anel (chamado fora do hot path). */
    fun snapshot(source: Source): Snapshot = snapshotIndex(source.ordinal)

    fun allSnapshots(): Map<Source, Snapshot> =
        Source.entries.associateWith { snapshot(it) }

    /** Limpa anéis e pendências (verbo `latency:reset`). */
    fun reset() {
        pending.fill(-1L)
        for (i in 0 until SOURCES) {
            counts[i] = 0
            heads[i] = 0
        }
    }

    /** Dump agregado para o logcat (`latency:report`). */
    fun report(): String = buildString {
        append("LatencyTracker report:")
        for (source in Source.entries) {
            val s = snapshot(source)
            append(' ')
            append(source.name)
            if (s.count == 0) {
                append("=no-samples")
            } else {
                append(" n=").append(s.count)
                append(" p50=").append(fmt(s.p50Ms))
                append("ms p95=").append(fmt(s.p95Ms))
                append("ms min=").append(fmt(s.minMs))
                append("ms max=").append(fmt(s.maxMs)).append("ms")
            }
        }
    }

    private fun record(idx: Int, elapsedNanos: Long) {
        val ring = rings[idx]
        val head = heads[idx]
        ring[head] = elapsedNanos
        heads[idx] = (head + 1) % CAPACITY
        if (counts[idx] < CAPACITY) counts[idx]++
    }

    private fun snapshotIndex(idx: Int): Snapshot {
        val count = counts[idx]
        if (count == 0) return Snapshot.EMPTY
        val sorted = rings[idx].copyOf(count).apply { sort() }
        var min = Long.MAX_VALUE
        var max = Long.MIN_VALUE
        for (v in sorted) {
            if (v < min) min = v
            if (v > max) max = v
        }
        return Snapshot(
            count = count,
            p50Ms = percentileMs(sorted, 0.50f),
            p95Ms = percentileMs(sorted, 0.95f),
            minMs = min / 1_000_000f,
            maxMs = max / 1_000_000f,
        )
    }

    /** Percentil com interpolação linear (método 7, padrão numpy). Puro (testado). */
    private fun percentileMs(sorted: LongArray, p: Float): Float {
        val n = sorted.size
        if (n == 1) return sorted[0] / 1_000_000f
        val pos = (n - 1) * p
        val lo = pos.toInt()
        val frac = pos - lo
        // Long*Double = Double — evita o tipo comum Float/Double (Number) que quebra o div.
        val v: Double = if (lo + 1 < n) {
            sorted[lo].toDouble() + (sorted[lo + 1] - sorted[lo]) * frac.toDouble()
        } else {
            sorted[lo].toDouble()
        }
        return (v / 1_000_000.0).toFloat()
    }

    private fun fmt(v: Float): String = "%.2f".format(v)
}
