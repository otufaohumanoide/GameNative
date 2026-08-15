package app.gamenative.gamepad.mapping

/**
 * Evento CRU — o que o Android entrega (spec 2026-08-13, Parte I §2). Registros puros
 * sem android.* (JVM-testáveis); o adapter Android (fino) converte KeyEvent/MotionEvent
 * nestes records.
 *
 * [RawKeyInput.action]: ACTION_DOWN=0 / ACTION_UP=1 (KeyEvent).
 * [RawAxisInput.axisValues]: chaves = constantes AXIS_* reais (AndroidConstants).
 */
data class RawKeyInput(
    val deviceId: Int,
    val source: Int,
    val keyCode: Int,
    val action: Int,
    val repeatCount: Int,
)

data class RawAxisInput(
    val deviceId: Int,
    val source: Int,
    val action: Int,
    val axisValues: Map<Int, Float>,
)
