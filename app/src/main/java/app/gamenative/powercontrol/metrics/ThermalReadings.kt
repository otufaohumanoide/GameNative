package app.gamenative.powercontrol.metrics

import android.content.Context
import android.os.Build
import android.os.PowerManager as AndroidPowerManager

/**
 * Leituras térmicas do sistema para o caminho live (frente I4 do `docs/plano-mali-mediatek.md`):
 * status de throttling (`getCurrentThermalStatus`, API 29+) e headroom (`getThermalHeadroom`,
 * API 30+). Único ponto de acesso, com as guardas de versão de API num só lugar.
 *
 * Headroom é a razão de quão perto o device está do limite térmico (≈1,0 = no limiar; >1 =
 * throttlando) — insumo tanto do HUD quanto do JSONL de A/B.
 */
object ThermalReadings {

    const val HEADROOM_FORECAST_SECONDS = 10

    fun status(context: Context): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            (context.getSystemService(Context.POWER_SERVICE) as? AndroidPowerManager)
                ?.currentThermalStatus
        }.getOrNull()?.takeIf { it >= 0 }
    }

    fun headroom(context: Context): Float? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            (context.getSystemService(Context.POWER_SERVICE) as? AndroidPowerManager)
                ?.getThermalHeadroom(HEADROOM_FORECAST_SECONDS)
        }.getOrNull()?.takeIf { it.isFinite() }
    }
}
