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
    /**
     * K4 (spec 2026-08-16-K4, §1.3.2): scanCode cru do KeyEvent — o alias de
     * scanCode do quirk ativo corrige o keycode ANTES da tradução quando o keycode é
     * KEYCODE_UNKNOWN (device sem .kl). Default 0 = não informado (nenhum quirk usa
     * scanCode 0) — chamadores antigos intactos, degradação byte-identical.
     */
    val scanCode: Int = 0,
)

data class RawAxisInput(
    val deviceId: Int,
    val source: Int,
    val action: Int,
    val axisValues: Map<Int, Float>,
)
