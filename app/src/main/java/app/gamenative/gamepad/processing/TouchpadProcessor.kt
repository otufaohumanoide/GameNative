package app.gamenative.gamepad.processing

/**
 * Processador PURO do touchpad do controle → mouse (spec 2026-08-14-gamepad-u2-
 * touchpad-mouse, §1.1 + spec 2026-08-14-touchpad-drag-double-tap, P2-6):
 * transforma amostras absolutas normalizadas [0..1] do touchpad (AXIS_X/AXIS_Y do
 * device TOUCHPAD) em deltas de mouse + gestos (tap, arrasto, duplo-toque).
 *
 * JVM-testável (V5): nenhum android.* — as decisões vivem aqui; o estado entre
 * amostras ([TouchpadState]) é por device e morre no `removeDevice` (V6 — mesmo
 * padrão `buttonStates` do hub).
 *
 * Máquina de estados (P2-6): `idle → tapCandidate → dragging`.
 * - Finger-down → ancora a posição e o relógio (tapCandidate); rejeitado quando
 *   dentro da dead zone de pós-toque ([TouchpadConfig.postTouchDeadzoneMs] —
 *   bounce do touchpad gasto, moonlight `TOUCH_DOWN_DEAD_ZONE`).
 * - Move em tapCandidate → delta = (atual - anterior) * sensitivity, descartado
 *   abaixo de [TouchpadConfig.moveDeadzone]; segurar ≥ [TouchpadConfig.dragThresholdMs]
 *   vira ARRASTO: BUTTON_LEFT pressionado contínuo + deltas (gesto de arrastar
 *   itens/objetos — moonlight `LONG_PRESS_TIME_THRESHOLD` 650 ms).
 * - Tap = down→up em até [TouchpadConfig.tapWindowMs] com deslocamento total ≤
 *   [TouchpadConfig.tapMoveDeadzone] (0.03 normalizado ≈ 20–25 px do moonlight).
 * - Duplo-toque (opt-in [TouchpadConfig.doubleTapRightClick]): 2 taps dentro de
 *   [TouchpadConfig.doubleTapWindowMs] com ≤ [TouchpadConfig.doubleTapMoveDeadzone]
 *   → BUTTON_RIGHT (gesto "botão direito" do moonlight); default OFF = 2 cliques.
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
    /** Janela de tap (down→up), ms — moonlight: ≤ 250 ms. */
    val tapWindowMs: Long = 250L,
    /** Deslocamento total máximo (normalizado) para um toque contar como tap. */
    val tapMoveDeadzone: Float = 0.03f,
    /** Escala de conversão: 1.0 de percurso do touchpad → pixels de cursor. */
    val pixelsPerPadWidth: Float = 350f,
    // P2-6 (spec 2026-08-14-touchpad-drag-double-tap):
    /** Segurar o dedo ≥ este tempo (sem soltar) vira clique+arrasto contínuo. */
    val dragThresholdMs: Long = 650L,
    /** Janela do duplo-toque (entre os taps), ms — moonlight: 250 ms. */
    val doubleTapWindowMs: Long = 250L,
    /** Distância máxima entre os taps do duplo-toque (normalizado — ~60 px). */
    val doubleTapMoveDeadzone: Float = 0.06f,
    /** Dead zone de pós-toque: downs dentro dela são rejeitados (bounce). */
    val postTouchDeadzoneMs: Long = 100L,
    /** Opt-in por perfil: duplo-toque = clique DIREITO (default OFF = 2 cliques). */
    val doubleTapRightClick: Boolean = false,
)

/** Estado entre amostras — UMA instância por device, morta no removeDevice (V6). */
class TouchpadState {
    var fingerDown: Boolean = false
    var dragging: Boolean = false
    var lastX: Float = 0f
    var lastY: Float = 0f
    var tapStartX: Float = 0f
    var tapStartY: Float = 0f
    var tapStartAt: Long = 0L
    var downAtMs: Long = 0L
    var lastTapAt: Long = 0L
    var lastTapX: Float = 0f
    var lastTapY: Float = 0f
    var ignoreUntilMs: Long = 0L
}

data class TouchpadDecision(
    /** Delta a injetar, em PIXELS (já escalado por sensitivity e pixelsPerPadWidth). */
    val deltaX: Int,
    val deltaY: Int,
    /** Um tap completo terminou NESTA amostra (finger-up) → clique esquerdo. */
    val tap: Boolean,
    /** Transição para ARRASTO nesta amostra → BUTTON_LEFT pressionado (P2-6). */
    val dragPress: Boolean,
    /** Fim do arrasto (finger-up) → BUTTON_LEFT solto (P2-6). */
    val dragRelease: Boolean,
    /** Duplo-toque (opt-in) → clique DIREITO (P2-6). */
    val rightClick: Boolean,
) {
    companion object {
        val NONE = TouchpadDecision(0, 0, false, false, false, false)
    }
}

object TouchpadProcessor {

    fun process(sample: TouchSample, state: TouchpadState, config: TouchpadConfig): TouchpadDecision {
        if (sample.down) {
            if (!state.fingerDown) {
                // Borda de descida. P2-6: dead zone de pós-toque — bounce do touchpad
                // gasto rejeita o DOWN inteiro (o toque fantasma não move o cursor).
                if (sample.nowMs < state.ignoreUntilMs) {
                    return TouchpadDecision.NONE
                }
                // Âncora da posição + relógio (tap/drag candidates).
                state.fingerDown = true
                state.dragging = false
                state.lastX = sample.x
                state.lastY = sample.y
                state.tapStartX = sample.x
                state.tapStartY = sample.y
                state.tapStartAt = sample.nowMs
                state.downAtMs = sample.nowMs
                return TouchpadDecision.NONE
            }
            // Move com o dedo.
            val dx = sample.x - state.lastX
            val dy = sample.y - state.lastY
            state.lastX = sample.x
            state.lastY = sample.y
            val absDx = kotlin.math.abs(dx)
            val absDy = kotlin.math.abs(dy)
            // P2-6: segurar ≥ dragThresholdMs (sem soltar) engata o ARRASTO — o botão
            // esquerdo fica pressionado e os deltas continuam fluindo.
            val engageDrag = !state.dragging &&
                sample.nowMs - state.downAtMs >= config.dragThresholdMs
            if (engageDrag) {
                state.dragging = true
            }
            if (absDx < config.moveDeadzone && absDy < config.moveDeadzone && !engageDrag) {
                return TouchpadDecision.NONE
            }
            return TouchpadDecision(
                deltaX = (dx * config.sensitivity * config.pixelsPerPadWidth).toInt(),
                deltaY = (dy * config.sensitivity * config.pixelsPerPadWidth).toInt(),
                tap = false,
                dragPress = engageDrag,
                dragRelease = false,
                rightClick = false,
            )
        }

        // Finger-up: encerra o gesto e reseta o estado do device.
        if (!state.fingerDown) return TouchpadDecision.NONE
        state.fingerDown = false
        val wasDragging = state.dragging
        state.dragging = false
        // P2-6: dead zone de pós-toque — o próximo down dentro dela é bounce.
        state.ignoreUntilMs = sample.nowMs + config.postTouchDeadzoneMs
        if (wasDragging) {
            return TouchpadDecision(0, 0, false, false, true, false)
        }
        val moved = kotlin.math.abs(sample.x - state.tapStartX) +
            kotlin.math.abs(sample.y - state.tapStartY)
        val withinWindow = sample.nowMs - state.tapStartAt <= config.tapWindowMs
        if (!withinWindow || moved > config.tapMoveDeadzone) {
            return TouchpadDecision.NONE
        }
        // Tap confirmado: duplo-toque (opt-in) → clique direito; senão esquerdo.
        val double = config.doubleTapRightClick &&
            state.lastTapAt != 0L &&
            sample.nowMs - state.lastTapAt <= config.doubleTapWindowMs &&
            kotlin.math.abs(sample.x - state.lastTapX) <= config.doubleTapMoveDeadzone &&
            kotlin.math.abs(sample.y - state.lastTapY) <= config.doubleTapMoveDeadzone
        if (double) {
            // Limpa o último tap: um terceiro toque não encadeia outro direito.
            state.lastTapAt = 0L
            return TouchpadDecision(0, 0, false, false, false, true)
        }
        state.lastTapAt = sample.nowMs
        state.lastTapX = sample.x
        state.lastTapY = sample.y
        return TouchpadDecision(0, 0, true, false, false, false)
    }
}
