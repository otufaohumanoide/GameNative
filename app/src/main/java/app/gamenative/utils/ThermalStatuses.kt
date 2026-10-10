package app.gamenative.utils

/**
 * Estados térmicos de `android.os.PowerManager` (API 29+) e seus rótulos, usados pelo HUD e
 * pelos relatórios de diagnóstico (frentes I4 do `docs/plano-mali-mediatek.md`). Pura e
 * JVM-testável; é a única fonte dos nomes (não duplicar em outro arquivo).
 */
object ThermalStatuses {

    // Mesmos valores de android.os.PowerManager.THERMAL_STATUS_*
    const val NONE = 0
    const val LIGHT = 1
    const val MODERATE = 2
    const val SEVERE = 3
    const val CRITICAL = 4
    const val EMERGENCY = 5
    const val SHUTDOWN = 6

    private val NAMES = arrayOf("NONE", "LIGHT", "MODERATE", "SEVERE", "CRITICAL", "EMERGENCY", "SHUTDOWN")

    /** Rótulo para o HUD: `null` em [NONE] (nada a exibir) e em valores desconhecidos. */
    fun label(status: Int): String? = if (status in LIGHT..SHUTDOWN) NAMES[status] else null

    /** Nome completo para relatórios: inclui "NONE"; valor fora da faixa vira o próprio número. */
    fun name(status: Int): String = NAMES.getOrElse(status) { status.toString() }
}
