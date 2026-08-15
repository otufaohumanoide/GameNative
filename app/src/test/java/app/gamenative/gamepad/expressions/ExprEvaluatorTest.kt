package app.gamenative.gamepad.expressions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * J1 (spec 2026-08-16-J-expressions-dolphin, §4): avaliação pura — literais e
 * entradas, threshold 0.5 do and/or/not, e CADA uma das 14 funções com as
 * semânticas portadas do FunctionExpression.cpp (deadzone REESCALA, toggle latch
 * na borda + clear, hold segura e expira, tap 2 taps na janela e 3º reset,
 * smooth rampa up≠down, pulse one-shot, timer período, relative integra e satura).
 * NOTA: os testes com estado reusam o MESMO AST (o índice do call-site é parte da
 * chave do FuncState — o hub faz o parse UMA vez no cache, §2.2).
 */
class ExprEvaluatorTest {

    private val epsilon = 1e-5f

    private fun reader(values: Map<String, Float>): (String, Boolean) -> Float =
        { name, axis -> values[if (axis) "axis:$name" else name.lowercase()] ?: 0f }

    private fun eval(
        source: String,
        values: Map<String, Float> = emptyMap(),
        state: ExprState = ExprState(),
        dtMs: Long = 50L,
        nowMs: Long = 1000L,
    ): Float = ExprEvaluator.eval(ExprParser.parse(source), reader(values), state, dtMs, nowMs)

    @Test
    fun `literais e entradas`() {
        assertEquals(2.5f, eval("1.5 + 1"), epsilon)
        assertEquals(1f, eval("face_bottom", mapOf("face_bottom" to 1f)), epsilon)
        assertEquals(-0.7f, eval("axis:left_x", mapOf("axis:left_x" to -0.7f)), epsilon)
        assertEquals(1f, eval("l2", mapOf("left_trigger" to 1f)), epsilon)
    }

    @Test
    fun `and or not usam threshold 0 dot 5`() {
        assertEquals(1f, eval("face_bottom and face_right", mapOf("face_bottom" to 0.7f, "face_right" to 0.8f)), epsilon)
        assertEquals(0f, eval("face_bottom and face_right", mapOf("face_bottom" to 0.4f, "face_right" to 0.8f)), epsilon)
        assertEquals(1f, eval("face_bottom or face_right", mapOf("face_bottom" to 0.4f, "face_right" to 0.6f)), epsilon)
        assertEquals(0f, eval("face_bottom or face_right", mapOf("face_bottom" to 0.4f, "face_right" to 0.5f)), epsilon)
        assertEquals(0f, eval("not face_bottom", mapOf("face_bottom" to 0.9f)), epsilon)
        assertEquals(1f, eval("not face_bottom", mapOf("face_bottom" to 0.4f)), epsilon)
    }

    @Test
    fun `comparacoes retornam 1 ou 0`() {
        assertEquals(1f, eval("1 > 0"), epsilon)
        assertEquals(0f, eval("0 > 1"), epsilon)
        assertEquals(1f, eval("1 >= 1"), epsilon)
        assertEquals(1f, eval("1 == 1"), epsilon)
        assertEquals(1f, eval("1 != 2"), epsilon)
        assertEquals(0f, eval("2 < 1"), epsilon)
    }

    @Test
    fun `divisao por zero degrada a 0`() {
        assertEquals(0f, eval("1 / 0"), epsilon)
    }

    @Test
    fun `ternario escolhe pelo threshold`() {
        assertEquals(2f, eval("face_bottom ? 2 : 3", mapOf("face_bottom" to 0.9f)), epsilon)
        assertEquals(3f, eval("face_bottom ? 2 : 3", mapOf("face_bottom" to 0.4f)), epsilon)
    }

    @Test
    fun `not if abs min max clamp`() {
        // `not(...)` é o OPERADOR not (threshold 0.5 — spec §2.1): 0.7 → 0.
        assertEquals(0f, eval("not(0.7)"), epsilon)
        assertEquals(2f, eval("if(1, 2, 3)"), epsilon)
        assertEquals(3f, eval("if(0, 2, 3)"), epsilon)
        assertEquals(0.5f, eval("abs(-0.5)"), epsilon)
        assertEquals(1f, eval("min(1, 2)"), epsilon)
        assertEquals(2f, eval("max(1, 2)"), epsilon)
        assertEquals(1.5f, eval("clamp(3, 1, 1.5)"), epsilon)
    }

    @Test
    fun `deadzone reescala e nao clipa`() {
        // (|v| − dz) / (1 − dz): 0.1 com dz 0.5 → 0; 0.75 → 0.5.
        assertEquals(0f, eval("deadzone(0.1, 0.5)"), epsilon)
        assertEquals(0.5f, eval("deadzone(0.75, 0.5)"), epsilon)
        assertEquals(-0.5f, eval("deadzone(-0.75, 0.5)"), epsilon)
        assertEquals(1f, eval("deadzone(1, 0.5)"), epsilon)
    }

    @Test
    fun `toggle faz latch na borda e clear zera`() {
        val state = ExprState()
        val ast = ExprParser.parse("toggle(face_bottom)")
        val astClear = ExprParser.parse("toggle(face_bottom, face_right)")
        // 1ª borda → ON; solto mantém o latch.
        assertEquals(1f, ExprEvaluator.eval(ast, reader(mapOf("face_bottom" to 1f)), state, 50L, 1000L), epsilon)
        assertEquals(1f, ExprEvaluator.eval(ast, reader(mapOf("face_bottom" to 0f)), state, 50L, 1100L), epsilon)
        // 2ª borda → OFF.
        assertEquals(0f, ExprEvaluator.eval(ast, reader(mapOf("face_bottom" to 1f)), state, 50L, 1200L), epsilon)
        // clear com o 2º argumento zera (sem borda nova).
        assertEquals(0f, ExprEvaluator.eval(astClear, reader(mapOf("face_bottom" to 1f, "face_right" to 1f)), state, 50L, 1300L), epsilon)
    }

    @Test
    fun `hold segura o valor e expira ao soltar`() {
        val state = ExprState()
        val ast = ExprParser.parse("hold(face_bottom, 0.5)")
        // press em t=1000
        assertEquals(0f, ExprEvaluator.eval(ast, reader(mapOf("face_bottom" to 1f)), state, 50L, 1000L), epsilon)
        // antes de 0.5 s
        assertEquals(0f, ExprEvaluator.eval(ast, reader(mapOf("face_bottom" to 1f)), state, 50L, 1400L), epsilon)
        // depois de 0.5 s
        assertEquals(1f, ExprEvaluator.eval(ast, reader(mapOf("face_bottom" to 1f)), state, 50L, 1500L), epsilon)
        // soltar → expira
        assertEquals(0f, ExprEvaluator.eval(ast, reader(mapOf("face_bottom" to 0f)), state, 50L, 1600L), epsilon)
    }

    @Test
    fun `tap conta dois toques na janela e o terceiro reseta`() {
        val state = ExprState()
        val ast = ExprParser.parse("tap(face_bottom, 0.5)")
        // 1º tap
        assertEquals(0f, ExprEvaluator.eval(ast, reader(mapOf("face_bottom" to 1f)), state, 50L, 1000L), epsilon)
        // solta e re-tap dentro da janela → 1.0 enquanto segurado
        assertEquals(0f, ExprEvaluator.eval(ast, reader(mapOf("face_bottom" to 0f)), state, 50L, 1100L), epsilon)
        assertEquals(1f, ExprEvaluator.eval(ast, reader(mapOf("face_bottom" to 1f)), state, 50L, 1200L), epsilon)
        // solta e o 3º tap (dentro da janela) não dispara mais
        assertEquals(0f, ExprEvaluator.eval(ast, reader(mapOf("face_bottom" to 0f)), state, 50L, 1300L), epsilon)
        assertEquals(0f, ExprEvaluator.eval(ast, reader(mapOf("face_bottom" to 1f)), state, 50L, 1400L), epsilon)
        // solta DEPOIS da janela → reseta o contador
        assertEquals(0f, ExprEvaluator.eval(ast, reader(mapOf("face_bottom" to 0f)), state, 50L, 2000L), epsilon)
        // novo ciclo: 1º tap...
        assertEquals(0f, ExprEvaluator.eval(ast, reader(mapOf("face_bottom" to 1f)), state, 50L, 2100L), epsilon)
        assertEquals(0f, ExprEvaluator.eval(ast, reader(mapOf("face_bottom" to 0f)), state, 50L, 2200L), epsilon)
        // ...e o 2º tap dentro da janela dispara.
        assertEquals(1f, ExprEvaluator.eval(ast, reader(mapOf("face_bottom" to 1f)), state, 50L, 2300L), epsilon)
    }

    @Test
    fun `smooth rampa up e down diferentes`() {
        val state = ExprState()
        val upAst = ExprParser.parse("smooth(1, 0.2, 0.05)")
        // 0 → 1 com up = 0.2 s: dt 100 ms → passo 0.5
        assertEquals(0.5f, ExprEvaluator.eval(upAst, reader(emptyMap()), state, 100L, 1000L), epsilon)
        assertEquals(1f, ExprEvaluator.eval(upAst, reader(emptyMap()), state, 100L, 1100L), epsilon)
        // 1 → 0 com down = 0.05 s: dt 100 ms → passo 2.0 (satura em 0)
        val downAst = ExprParser.parse("smooth(0, 0.2, 0.05)")
        assertEquals(0f, ExprEvaluator.eval(downAst, reader(emptyMap()), state, 100L, 1200L), epsilon)
    }

    @Test
    fun `pulse one shot por borda`() {
        val state = ExprState()
        val ast = ExprParser.parse("pulse(face_bottom, 0.2)")
        assertEquals(1f, ExprEvaluator.eval(ast, reader(mapOf("face_bottom" to 1f)), state, 50L, 1000L), epsilon)
        // ainda ativo no meio do prazo
        assertEquals(1f, ExprEvaluator.eval(ast, reader(mapOf("face_bottom" to 0f)), state, 50L, 1100L), epsilon)
        // expira
        assertEquals(0f, ExprEvaluator.eval(ast, reader(mapOf("face_bottom" to 0f)), state, 50L, 1200L), epsilon)
        // nova borda
        assertEquals(1f, ExprEvaluator.eval(ast, reader(mapOf("face_bottom" to 1f)), state, 50L, 1300L), epsilon)
    }

    @Test
    fun `timer rampa periodica`() {
        val state = ExprState()
        val ast = ExprParser.parse("timer(1)")
        // A rampa começa na 1ª avaliação (t=1000 → 0).
        assertEquals(0f, ExprEvaluator.eval(ast, reader(emptyMap()), state, 50L, 1000L), epsilon)
        // t=1250 → 0.25 do período de 1 s.
        assertEquals(0.25f, ExprEvaluator.eval(ast, reader(emptyMap()), state, 50L, 1250L), 0.01f)
        // t=2200 → progress 1.2 → floor-reset → ~0.2.
        val v = ExprEvaluator.eval(ast, reader(emptyMap()), state, 50L, 2200L)
        assertTrue("esperado ~0.2, veio $v", v in 0.15f..0.25f)
    }

    @Test
    fun `relative integra e satura no max`() {
        val state = ExprState()
        val ast = ExprParser.parse("relative(1, 2, 1)")
        // input 1, speed 2, max 1: dt 100 ms → +0.2 por avaliação
        var v = ExprEvaluator.eval(ast, reader(emptyMap()), state, 100L, 1000L)
        assertEquals(0.2f, v, epsilon)
        repeat(5) {
            v = ExprEvaluator.eval(ast, reader(emptyMap()), state, 100L, 1100L + it * 100)
        }
        assertEquals(1f, v, epsilon) // saturou no max
    }

    @Test
    fun `relative compartilha slot entre chamadas`() {
        val state = ExprState()
        // up e down compartilham o slot 1: up sobe 0.2; down desce 0.2 de volta.
        val upAst = ExprParser.parse("relative(face_bottom, 2, 1, 1)")
        val downAst = ExprParser.parse("relative(face_right, 2, -1, 1)")
        val v1 = ExprEvaluator.eval(upAst, reader(mapOf("face_bottom" to 1f)), state, 100L, 1000L)
        assertEquals(0.2f, v1, epsilon)
        val v2 = ExprEvaluator.eval(downAst, reader(mapOf("face_right" to 1f)), state, 100L, 1100L)
        assertEquals(0f, v2, epsilon)
    }
}
