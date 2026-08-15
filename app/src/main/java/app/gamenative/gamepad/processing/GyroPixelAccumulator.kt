package app.gamenative.gamepad.processing

/**
 * G1 do spec 2026-08-16-G-gyro-v2, §1: acumulador sub-pixel do MOUSE mode.
 *
 * O caminho antigo convertia o delta fracionário de pixels com `.toInt()` e
 * DESCARTAVA a fração — giro lento (delta < 1 px por amostra) podia nunca mover o
 * cursor. O acumulador soma em float e emite só a parte inteira, guardando o resto
 * (padrão DS4Windows `horizontalRemainder` — MouseCursor.sixaxisMoved,
 * reference/DS4Windows/DS4Windows/DS4Control/MouseCursor.cs).
 *
 * PURO (JVM-testável): nenhum android.*. Sem flag de opt-in: correção pura, não há
 * caminho antigo a preservar. O [GyroMouseState] é UM por device (V6 — o hub
 * descarta no removeDevice; deviceId efêmero nunca vaza resto).
 */
data class GyroMouseState(
    /** Resto fracionário horizontal ainda não emitido (px). */
    var remX: Float = 0f,
    /** Resto fracionário vertical ainda não emitido (px). */
    var remY: Float = 0f,
)

object GyroPixelAccumulator {

    /**
     * Soma [deltaXPx]/[deltaYPx] ao resto do [state], emite a parte inteira de cada
     * eixo e guarda a fração para a próxima amostra. Emissão zero quando o
     * acumulado ainda não completa 1 px.
     */
    fun accumulate(deltaXPx: Float, deltaYPx: Float, state: GyroMouseState): Pair<Int, Int> {
        state.remX += deltaXPx
        state.remY += deltaYPx
        val dx = state.remX.toInt()
        val dy = state.remY.toInt()
        state.remX -= dx
        state.remY -= dy
        return dx to dy
    }
}
