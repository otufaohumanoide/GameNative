package app.gamenative.gamepad.processing

import app.gamenative.gamepad.GamepadButton
import kotlin.math.sqrt

/**
 * K2 (spec 2026-08-16-K2, §1.1) — modo mouse universal por stick: segurar START
 * 750 ms (confirmado no release) ativa o modo; enquanto ativo, o stick ESQUERDO
 * move o cursor (rampa quadrática), A/B clicam e o dpad rola.
 *
 * Port clean-room do moonlight-android `ControllerHandler.java` (GPL-3 —
 * SEMÂNTICAS reimplementadas em Kotlin, NUNCA copiar código):
 * - toggle: hold START ≥ `START_DOWN_TIME_MOUSE_MODE_MS` = 750 (:60) confirmado
 *   no KEY-UP (:2371-2375 — evita toggle acidental duplo); release antes do
 *   limiar → nada (START segue para o jogo);
 * - rampa quadrática do cursor (`convertRawStickAxisToPixelMovement` :1837):
 *   px/report = 4 · deflection² (normaliza e escala pela magnitude² — precisão
 *   no centro, velocidade na borda). Aqui em px/s: `base + gain·mag²` com
 *   gain default 80 = 4 px/report × 20 reports/s (o período de report de 50 ms,
 *   `mouseEmulationReportPeriod` :2892);
 * - report de 50 ms SEM timer: gate por timestamp no estado (o flush do hub já
 *   roda por evento; sticks reportam ~60 Hz — o gate basta);
 * - A/B = cliques (`mouseEmulationActive` :1245-1265: A=left, B=right);
 * - dpad = scroll vertical (UP=+1, DOWN=−1) na borda de down + repetição com a
 *   MESMA janela de 120 ms do `GamepadMoveDedupe` (o Android repete o KeyEvent
 *   do dpad segurado; o repeat cru alimenta [onScrollRepeat]);
 * - sub-pixel: padrão G1 do gyro ([GyroPixelAccumulator]) — movimento lento
 *   nunca "congela".
 *
 * PURO (zero android.*) — JVM-testável. O estado é UM por device no hub (morto
 * em removeDevice, padrão V6); o hub passa `nowMs` (uptime) e os timestamps de
 * toggle/report/scroll são comparados SEMPRE com o mesmo relógio.
 */
class MouseModeState(
    /** Instante (ms) em que START foi pressionado; 0 = não pressionado. */
    var startDownAtMs: Long = 0L,
    /** START segurado ≥ [MouseModeProcessor.DEFAULT_TOGGLE_MS] (cruzou o limiar). */
    var armed: Boolean = false,
    /** Modo mouse LIGADO (flip no release do START armado). */
    var active: Boolean = false,
    /** Instante do último report de movimento (gate de 50 ms). */
    var lastReportAtMs: Long = 0L,
    /** Instante do último scroll emitido (anti-repeat de 120 ms). */
    var lastScrollAtMs: Long = 0L,
    /** Botão de dpad segurado (direção do repeat do scroll); null = nenhum. */
    var dpadHeld: GamepadButton? = null,
    /** Últimos valores lógicos do stick esquerdo (o hub atualiza ANTES do onStick). */
    var lastStickX: Float = 0f,
    var lastStickY: Float = 0f,
    /** Resto sub-pixel (padrão G1 — reuso do acumulador do gyro MOUSE). */
    val pixelState: GyroMouseState = GyroMouseState(),
)

/**
 * Velocidade do cursor (px/s) em função da deflexão: `base + gain·mag²`.
 * Defaults derivados do moonlight: gain 80 px/s = 4 px/report (50 ms) na borda
 * (mag=1); base 0 mantém a rampa pura do `convertRawStickAxisToPixelMovement`.
 */
data class MouseModeSpeed(
    val basePps: Float = 0f,
    val gainPps: Float = 80f,
)

/** Movimento de cursor emitido (pixels inteiros, sub-pixel acumulado). */
data class MouseMove(val dx: Int, val dy: Int)

/**
 * Desfecho de [MouseModeProcessor.onKey] — a UI/hub decide o efeito (haptic no
 * toggle, sink nos cliques/scroll). [None] = nada a fazer (evento segue o
 * pipeline normalmente).
 */
sealed interface MouseModeOutcome {
    data object None : MouseModeOutcome
    data object Activated : MouseModeOutcome
    data object Deactivated : MouseModeOutcome
    data class MouseButton(val left: Boolean, val down: Boolean) : MouseModeOutcome
    data class MouseScroll(val steps: Int) : MouseModeOutcome
}

object MouseModeProcessor {

    /** Hold de START para armar o toggle (moonlight `START_DOWN_TIME_MOUSE_MODE_MS` :60). */
    const val DEFAULT_TOGGLE_MS = 750L

    /** Período de report do cursor (moonlight `mouseEmulationReportPeriod` :2892). */
    const val REPORT_PERIOD_MS = 50L

    /** Janela de anti-repeat do scroll — a MESMA do `GamepadMoveDedupe.WINDOW_MS`. */
    const val SCROLL_REPEAT_MS = 120L

    /**
     * Evento de BOTÃO lógico do pipeline (borda — o hub só entrega transições).
     * [MouseModeOutcome.None] para START (arma/flipa e o evento CONTINUA para o
     * jogo/menus — "exceto START que faz o toggle e volta a ser START") e para
     * qualquer botão fora do vocabulário do modo com o modo inativo.
     */
    fun onKey(
        state: MouseModeState,
        button: GamepadButton,
        isDown: Boolean,
        nowMs: Long,
        toggleMs: Long = DEFAULT_TOGGLE_MS,
    ): MouseModeOutcome {
        armIfCrossed(state, nowMs, toggleMs)

        if (button == GamepadButton.START) {
            if (isDown) {
                state.startDownAtMs = nowMs
                state.armed = false
                return MouseModeOutcome.None
            }
            val armed = state.armed
            state.startDownAtMs = 0L
            state.armed = false
            if (!armed) return MouseModeOutcome.None
            state.active = !state.active
            if (!state.active) {
                // Desliga limpo: o próximo período ativo recomeça sem resto de
                // movimento nem dpad segurado (mesmo padrão do SixMouseReset do G1).
                state.dpadHeld = null
                state.pixelState.remX = 0f
                state.pixelState.remY = 0f
            }
            return if (state.active) MouseModeOutcome.Activated else MouseModeOutcome.Deactivated
        }

        if (!state.active) return MouseModeOutcome.None

        return when (button) {
            GamepadButton.FACE_BOTTOM -> MouseModeOutcome.MouseButton(left = true, down = isDown)
            GamepadButton.FACE_RIGHT -> MouseModeOutcome.MouseButton(left = false, down = isDown)
            GamepadButton.DPAD_UP -> scrollEdge(state, button, isDown, nowMs, +1)
            GamepadButton.DPAD_DOWN -> scrollEdge(state, button, isDown, nowMs, -1)
            GamepadButton.DPAD_LEFT, GamepadButton.DPAD_RIGHT -> {
                // Scroll horizontal não é exposto pelo sink (não-meta do spec §1.2)
                // — o botão é consumido (não chega ao jogo) mas não repete.
                if (isDown) state.dpadHeld = null
                MouseModeOutcome.None
            }
            else -> MouseModeOutcome.None
        }
    }

    /**
     * Repeat de scroll do dpad segurado — chamado pelo hub nos repeats CRUS do
     * KeyEvent (o Android repete o keycode do dpad; o tradutor descarta repeats,
     * então este é o ÚNICO canal de repetição). Janela de 120 ms no estado.
     */
    fun onScrollRepeat(state: MouseModeState, nowMs: Long): MouseModeOutcome {
        if (!state.active) return MouseModeOutcome.None
        val held = state.dpadHeld ?: return MouseModeOutcome.None
        if (nowMs - state.lastScrollAtMs < SCROLL_REPEAT_MS) return MouseModeOutcome.None
        val steps = when (held) {
            GamepadButton.DPAD_UP -> 1
            GamepadButton.DPAD_DOWN -> -1
            else -> return MouseModeOutcome.None
        }
        state.lastScrollAtMs = nowMs
        return MouseModeOutcome.MouseScroll(steps)
    }

    /**
     * Stick ESQUERDO lógico (pós deadzone/curva — o modo respeita a calibração do
     * usuário) → movimento do cursor. Gate de 50 ms por timestamp; o dt é o
     * período FIXO de report (50 ms) — o primeiro movimento após o stick parado
     * NUNCA salta (mesmo comportamento do postDelayed do moonlight). Sub-pixel
     * acumulado (G1). null = nada a emitir.
     */
    fun onStick(
        state: MouseModeState,
        x: Float,
        y: Float,
        nowMs: Long,
        speed: MouseModeSpeed = MouseModeSpeed(),
        toggleMs: Long = DEFAULT_TOGGLE_MS,
    ): MouseMove? {
        armIfCrossed(state, nowMs, toggleMs)
        if (!state.active) return null
        if (nowMs - state.lastReportAtMs < REPORT_PERIOD_MS) return null
        val mag = sqrt(x * x + y * y)
        if (mag < 1e-4f) return null
        // Rampa quadrática do moonlight: px/s = base + gain·mag² na direção do
        // vetor (x,y já normalizados -1..1 — a direção é o próprio vetor).
        val pps = speed.basePps + speed.gainPps * mag * mag
        val dtSec = REPORT_PERIOD_MS / 1000f
        val (dx, dy) = GyroPixelAccumulator.accumulate(
            deltaXPx = x * pps * dtSec,
            deltaYPx = y * pps * dtSec,
            state = state.pixelState,
        )
        state.lastReportAtMs = nowMs
        if (dx == 0 && dy == 0) return null
        return MouseMove(dx, dy)
    }

    /** START segurado cruzou o limiar → [MouseModeState.armed] (observado a cada evento). */
    private fun armIfCrossed(state: MouseModeState, nowMs: Long, toggleMs: Long) {
        if (state.startDownAtMs != 0L && !state.armed &&
            nowMs - state.startDownAtMs >= toggleMs
        ) {
            state.armed = true
        }
    }

    /** Borda de down do dpad → scroll imediato + marca o held (repeat). */
    private fun scrollEdge(
        state: MouseModeState,
        button: GamepadButton,
        isDown: Boolean,
        nowMs: Long,
        steps: Int,
    ): MouseModeOutcome {
        if (isDown) {
            state.dpadHeld = button
            state.lastScrollAtMs = nowMs
            return MouseModeOutcome.MouseScroll(steps)
        }
        if (state.dpadHeld == button) state.dpadHeld = null
        return MouseModeOutcome.None
    }
}
