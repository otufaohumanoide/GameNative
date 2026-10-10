package app.gamenative.utils

/**
 * Rótulos dos estados térmicos de `android.os.PowerManager` (API 29+) usados pelo HUD
 * (frente I4 do `docs/plano-mali-mediatek.md`). Pura e JVM-testável; não duplicar o mapeamento.
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

    /** `null` para [NONE] e valores desconhecidos: nada é exibido quando não há throttling. */
    fun label(status: Int): String? = when (status) {
        LIGHT -> "LIGHT"
        MODERATE -> "MODERATE"
        SEVERE -> "SEVERE"
        CRITICAL -> "CRITICAL"
        EMERGENCY -> "EMERGENCY"
        SHUTDOWN -> "SHUTDOWN"
        else -> null
    }
}
