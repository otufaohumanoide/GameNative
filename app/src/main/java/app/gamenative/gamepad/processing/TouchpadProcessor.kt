package app.gamenative.gamepad.processing

/**
 * Processador PURO do touchpad do controle → mouse (spec 2026-08-14-gamepad-u2-
 * touchpad-mouse, §1.1): transforma amostras absolutas normalizadas [0..1] do touchpad
 * (AXIS_X/AXIS_Y do device TOUCHPAD) em deltas de mouse + tap = clique esquerdo.
 *
 * JVM-testável (V5): nenhum android.* — as decisões (deadzone de toque, tap, escala)
 * vivem aqui; o estado entre amostras ([TouchpadState]) é por device e morre no
 * `removeDevice` (V6 — mesmo padrão `buttonStates` do hub).
 *
 * Semântica:
 * - Finger-down → ancora a posição (zero delta); finger-up curto e parado → tap.
 * - Move com finger → delta = (atual - anterior) * sensitivity, descartado quando
 *   abaixo de [TouchpadConfig.moveDeadzone] (ruído de dedo parado não acumula).
 * - Tap = down→up em até [TouchpadConfig.tapWindowMs] com deslocamento total ≤
 *   [TouchpadConfig.tapMoveDeadzone] (normalizado). Toque duplo = 2 taps (sem
 *   debounce — decisão registrada no spec).
 */
data class TouchSample(
    val down: Boolean,
    val x: Float,
    val y: Float,
    val nowMs: Long,
)

data class TouchpadConfig(
    /** Multiplicador do delta (porcentagem de percurso do touchpad por amostra). */
    val sensitivity: Float = 1.0f,
    /** Delta absoluto mínimo por amostra (normalizado) — ruído de dedo parado. */
    val moveDeadzone: Float = 0.004f,
    /** Janela de tap (down→up), ms. */
    val tapWindowMs: Long = 250L,
    /** Deslocamento total máximo (normalizado) para um toque contar como tap. */
    val tapMoveDeadzone: Float = 0.08f,
    /** Escala de conversão: 1.0 de percurso do touchpad → pixels de cursor. */
    val pixelsPerPadWidth: Float = 350f,
)

/** Estado entre amostras — UMA instância por device, morta no removeDevice (V6). */
class TouchpadState {
    var fingerDown: Boolean = false
    var lastX: Float = 0f
    var lastY: Float = 0f
    var tapStartX: Float = 0f
    var tapStartY: Float = 0f
    var tapStartAt: Long = 0L
}

data class TouchpadDecision(
    /** Delta a injetar, em PIXELS (já escalado por sensitivity e pixelsPerPadWidth). */
    val deltaX: Int,
    val deltaY: Int,
    /** Um tap completo terminou NESTA amostra (finger-up) → clique esquerdo. */
    val tap: Boolean,
) {
    companion object {
        val NONE = TouchpadDecision(0, 0, false)
    }
}

object TouchpadProcessor {

    fun process(sample: TouchSample, state: TouchpadState, config: TouchpadConfig): TouchpadDecision {
        if (sample.down) {
            if (!state.fingerDown) {
                // Borda de descida: ancora posição + janela de tap.
                state.fingerDown = true
                state.lastX = sample.x
                state.lastY = sample.y
                state.tapStartX = sample.x
                state.tapStartY = sample.y
                state.tapStartAt = sample.nowMs
                return TouchpadDecision.NONE
            }
            // Move com o dedo: delta contra a última amostra.
            val dx = sample.x - state.lastX
            val dy = sample.y - state.lastY
            state.lastX = sample.x
            state.lastY = sample.y
            val absDx = kotlin.math.abs(dx)
            val absDy = kotlin.math.abs(dy)
            if (absDx < config.moveDeadzone && absDy < config.moveDeadzone) {
                return TouchpadDecision.NONE
            }
            return TouchpadDecision(
                deltaX = (dx * config.sensitivity * config.pixelsPerPadWidth).toInt(),
                deltaY = (dy * config.sensitivity * config.pixelsPerPadWidth).toInt(),
                tap = false,
            )
        }

        // Finger-up: tap se curto E parado; reseta o estado do device.
        if (!state.fingerDown) return TouchpadDecision.NONE
        val moved = kotlin.math.abs(sample.x - state.tapStartX) +
            kotlin.math.abs(sample.y - state.tapStartY)
        val withinWindow = sample.nowMs - state.tapStartAt <= config.tapWindowMs
        val tap = withinWindow && moved <= config.tapMoveDeadzone
        state.fingerDown = false
        return TouchpadDecision(0, 0, tap)
    }
}
