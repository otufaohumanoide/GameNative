package app.gamenative.gamepad.mapping

/**
 * Evento CRU do touchpad de um controle (spec 2026-08-14-gamepad-u2-touchpad-mouse,
 * §1.2): o que o adapter Android extrai do MotionEvent ANTES do gate de ghost input
 * consumir. Registro puro sem android.* (JVM-testável); o adapter fino
 * ([AndroidInputAdapter.toRawTouch]) faz a conversão.
 *
 * [x]/[y] = posição absoluta do dedo NORMALIZADA [0..1] (AXIS_X/AXIS_Y do device
 * TOUCHPAD — o DS4 reporta o dedo como deflexão total 0..1; valores fora da faixa são
 * clampados pelo adapter).
 */
data class RawTouchInput(
    val deviceId: Int,
    val source: Int,
    /** true = dedo presente (ACTION_DOWN/POINTER_DOWN), false = ausente (UP/POINTER_UP). */
    val down: Boolean,
    val x: Float,
    val y: Float,
    val nowMs: Long,
)
