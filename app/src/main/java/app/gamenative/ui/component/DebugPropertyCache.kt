package app.gamenative.ui.component

import android.os.SystemClock

/**
 * Leitura de propriedade de sistema com CACHE compartilhado (limpeza 1.3-2 da doc
 * pendentes-e-validacao-gamepad-universal.md): o harness de input (poll 200 ms) e o
 * HUD de latência (poll 500 ms) liam via `Runtime.exec(getprop)` CADA UM no seu
 * ritmo — até ~7 processos por segundo. Este cache serve a AMBOS com uma janela de
 * frescor curta (300 ms): no máximo ~3 exec/s no total, e leitores mais rápidos que
 * a janela reaproveitam o valor (debug-only, nunca no caminho do jogo).
 */
object DebugPropertyCache {

    /** Janela de frescor (ms) — acima dela o próximo read re-executa o getprop. */
    private const val FRESH_MS = 300L

    private data class Entry(val value: String, val readAt: Long)

    /** Cache POR PROPRIEDADE (harness e HUD leem props diferentes). */
    private val entries = mutableMapOf<String, Entry>()

    /** Lê [property]; dentro da janela de frescor devolve o valor cacheado. */
    @Synchronized
    fun read(property: String): String {
        val now = SystemClock.uptimeMillis()
        val entry = entries[property]
        if (entry != null && now - entry.readAt < FRESH_MS) {
            return entry.value
        }
        val value = readProperty(property)
        entries[property] = Entry(value, now)
        return value
    }

    private fun readProperty(property: String): String = try {
        val process = Runtime.getRuntime().exec(arrayOf("getprop", property))
        process.inputStream.bufferedReader().use { it.readText().trim() }
    } catch (_: Throwable) {
        ""
    }
}
