package app.gamenative.gamepad.expressions

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * J1 §2.1/§2.2: estado de UMA chamada de função com memória (por call-site —
 * `ExprEvaluator` aloca por `nome@índice`). Campos reutilizados pelas 14 funções:
 * `value` (smooth/relative), `released` (toggle/tap/pulse), `state` (toggle/hold/
 * pulse), `startMs`/`deadlineMs` (timer/hold/tap/pulse), `taps` (tap).
 */
class FuncState {
    var value: Float = 0f
    var lastUpdateMs: Long = 0L
    var released: Boolean = true
    var state: Boolean = false
    var startMs: Long = 0L
    var deadlineMs: Long = 0L
    var taps: Int = 0
}

/**
 * J1 §2.1: as 14 funções do subset — SEMÂNTICAS portadas clean-room de
 * `reference/dolphin/Source/Core/InputCommon/ControlReference/FunctionExpression.cpp`
 * (GPL-2.0; cada KDoc cita a classe-fonte):
 * not, if, abs, min, max, clamp, deadzone, smooth, toggle, hold, tap, pulse,
 * timer, relative. Aridade validada no PARSE (erro com coluna).
 */
object ExprFuncs {

    /** CONDITION_THRESHOLD do Dolphin (FunctionExpression.h). */
    const val THRESHOLD = 0.5f

    data class FunctionSpec(
        val name: String,
        val minArgs: Int,
        val maxArgs: Int,
        val fn: (args: List<Float>, state: FuncState, dtMs: Long, nowMs: Long) -> Float,
    )

    private fun seconds(ms: Long): Float = ms / 1000f

    val FUNCTIONS: Map<String, FunctionSpec> = listOf(
        // not(expression) — NotExpression: 1.0 - x.
        FunctionSpec("not", 1, 1) { a, _, _, _ -> 1f - a[0] },
        // if(condition, true_expression, false_expression) — IfExpression.
        FunctionSpec("if", 3, 3) { a, _, _, _ -> if (a[0] > THRESHOLD) a[1] else a[2] },
        // abs(expression) — AbsExpression.
        FunctionSpec("abs", 1, 1) { a, _, _, _ -> abs(a[0]) },
        // min(a, b) — MinExpression.
        FunctionSpec("min", 2, 2) { a, _, _, _ -> min(a[0], a[1]) },
        // max(a, b) — MaxExpression.
        FunctionSpec("max", 2, 2) { a, _, _, _ -> max(a[0], a[1]) },
        // clamp(value, min, max) — ClampExpression.
        FunctionSpec("clamp", 3, 3) { a, _, _, _ -> a[0].coerceIn(a[1], a[2]) },
        // deadzone(input, amount) — DeadzoneExpression: REESCALA (não clipa):
        // copysign(max(0, |v| - dz) / (1 - dz), v).
        FunctionSpec("deadzone", 2, 2) { a, _, _, _ ->
            val v = a[0]
            val dz = a[1]
            if (dz <= 0f) return@FunctionSpec v
            if (dz >= 1f) return@FunctionSpec 0f
            val rescaled = max(0f, abs(v) - dz) / (1f - dz)
            if (v < 0f) -rescaled else rescaled
        },
        // smooth(input, seconds_up[, seconds_down]) — SmoothExpression: rampa de
        // attack/release; max_move = elapsed/sec por avaliação.
        FunctionSpec("smooth", 2, 3) { a, s, dt, now ->
            val desired = a[0]
            val up = a[1]
            val down = if (a.size == 3) a[2] else up
            val ramp = if (desired < s.value) down else up
            if (ramp <= 0f) {
                s.value = desired
            } else {
                val maxMove = seconds(dt.coerceAtLeast(1L)) / ramp
                val diff = desired - s.value
                val step = min(maxMove, abs(diff))
                s.value += if (diff < 0f) -step else step
            }
            s.value
        },
        // toggle(input[, clear]) — ToggleExpression: latch na borda de subida
        // (> THRESHOLD); clear zera.
        FunctionSpec("toggle", 1, 2) { a, s, _, _ ->
            val input = a[0]
            if (input < THRESHOLD) {
                s.released = true
            } else if (s.released && input > THRESHOLD) {
                s.released = false
                s.state = !s.state
            }
            if (a.size == 2 && a[1] > THRESHOLD) s.state = false
            if (s.state) 1f else 0f
        },
        // hold(input, seconds) — HoldExpression: segura true após `seconds` de
        // press contínuo; solta (input < THRESHOLD) reseta.
        FunctionSpec("hold", 2, 2) { a, s, _, now ->
            val input = a[0]
            if (input < THRESHOLD) {
                s.state = false
                s.startMs = 0L
            } else if (!s.state) {
                // A contagem começa na BORDA de pressão (startMs 0 = parado).
                if (s.startMs == 0L) s.startMs = now
                if (seconds(now - s.startMs) >= a[1]) s.state = true
            }
            if (s.state) 1f else 0f
        },
        // tap(input, seconds[, taps=2]) — TapExpression: retorna 1 ENQUANTO o
        // enésimo tap está segurado; soltar + janela vencida reseta o contador.
        FunctionSpec("tap", 2, 3) { a, s, _, now ->
            val input = a[0]
            val secondsTap = a[1]
            val desired = if (a.size == 3) (a[2] + 0.5f).toInt() else 2
            val elapsed = if (s.startMs == 0L) 0L else now - s.startMs
            val timeUp = seconds(elapsed) > secondsTap
            if (input < THRESHOLD) {
                s.released = true
                if (s.taps > 0 && timeUp) s.taps = 0
            } else {
                if (s.released) {
                    if (s.taps == 0) s.startMs = now
                    s.taps += 1
                    s.released = false
                }
                return@FunctionSpec if (desired == s.taps) 1f else 0f
            }
            0f
        },
        // pulse(input, seconds) — PulseExpression: one-shot na borda; estende o
        // prazo se re-disparado enquanto ativo.
        FunctionSpec("pulse", 2, 2) { a, s, _, now ->
            val input = a[0]
            if (input < THRESHOLD) {
                s.released = true
            } else if (s.released) {
                s.released = false
                val secondsPulse = (a[1] * 1000f).toLong()
                if (s.state) {
                    s.deadlineMs += secondsPulse
                } else {
                    s.state = true
                    s.deadlineMs = now + secondsPulse
                }
            }
            if (s.state && now >= s.deadlineMs) s.state = false
            if (s.state) 1f else 0f
        },
        // timer(seconds) — TimerExpression: rampa 0..1 periódica (floor-reset).
        FunctionSpec("timer", 1, 1) { a, s, _, now ->
            val sec = a[0]
            if (s.startMs == 0L) s.startMs = now
            var progress = if (sec <= 0f) -1f else seconds(now - s.startMs) / sec
            if (progress < 0f || !progress.isFinite()) {
                s.startMs = now
                0f
            } else if (progress >= 1f) {
                val resetCount = floor(progress)
                s.startMs += (sec * resetCount * 1000f).toLong()
                progress -= resetCount
                progress
            } else {
                progress
            }
        },
        // relative(input, speed[, max_abs_value[, shared_slot]]) —
        // RelativeExpression: integra rate-of-change com saturação no max.
        // J1: o 4º argumento é o SLOT COMPARTILHADO (número) — variáveis $ são
        // non-goal; pares up/down usam o mesmo slot (ver ExprEvaluator).
        FunctionSpec("relative", 2, 4) { a, s, dt, _ ->
            val input = a[0]
            val speed = a[1]
            val maxAbs = if (a.size >= 3) a[2] else 1f
            val maxMove = input * seconds(dt.coerceAtLeast(1L)) * speed
            val diffFromZero = abs(0f - s.value)
            val diffFromMax = abs(maxAbs - s.value)
            val move = min(max(maxMove, -diffFromZero), diffFromMax)
            s.value += move * (if (maxAbs < 0f) -1f else 1f)
            max(0f, s.value * (if (maxAbs < 0f) -1f else 1f))
        },
    ).associateBy { it.name }

    /** Erro de aridade para o parser (null = aridade válida). */
    fun arityError(name: String, argCount: Int): String? {
        val spec = FUNCTIONS[name] ?: return "função desconhecida: $name"
        return when {
            argCount < spec.minArgs -> "$name espera pelo menos ${spec.minArgs} argumento(s) (recebeu $argCount)"
            argCount > spec.maxArgs -> "$name espera no máximo ${spec.maxArgs} argumento(s) (recebeu $argCount)"
            else -> null
        }
    }
}
