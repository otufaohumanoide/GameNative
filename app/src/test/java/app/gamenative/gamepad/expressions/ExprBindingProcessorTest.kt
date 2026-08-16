package app.gamenative.gamepad.expressions

import app.gamenative.gamepad.GamepadAxis
import app.gamenative.gamepad.GamepadButton
import app.gamenative.gamepad.InputEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * J1 (spec 2026-08-16-J-expressions-dolphin, §4): integração PURA (fake do hub) —
 * perfil com `expr:face_bottom and axis:left_x > 0.5` em FACE_TOP ⇒ transições
 * emitem Down/Up do FACE_TOP; sem expr ⇒ nada muda; token expr: inválido é pulado
 * (degrade, nunca crash).
 */
class ExprBindingProcessorTest {

    private fun reader(values: Map<String, Float>): (String, Boolean) -> Float =
        { name, axis -> values[if (axis) "axis:$name" else name.lowercase()] ?: 0f }

    @Test
    fun `perfil com expr emite transicoes do botao dono`() {
        val effective = mapOf(
            "FACE_TOP" to "expr:face_bottom and axis:left_x > 0.5",
        )
        val bindings = ExprBindingProcessor.parseBindings(effective)
        assertEquals(1, bindings.size)
        assertEquals(GamepadButton.FACE_TOP, bindings[0].button)
        assertTrue(bindings[0].axis == null)

        val state = ExprState()
        val inputs = mapOf("face_bottom" to 1f, "axis:left_x" to 0.8f)
        val events = ExprBindingProcessor.evaluate(bindings, reader(inputs), state, 50L, 1000L, deviceId = 7)
        assertEquals(listOf(InputEvent.ButtonDown(7, GamepadButton.FACE_TOP)), events)

        // O valor cai → Up.
        val off = ExprBindingProcessor.evaluate(
            bindings,
            reader(mapOf("face_bottom" to 1f, "axis:left_x" to 0.2f)),
            state,
            50L,
            1100L,
            deviceId = 7,
        )
        assertEquals(listOf(InputEvent.ButtonUp(7, GamepadButton.FACE_TOP)), off)
    }

    @Test
    fun `expr em botao de trigger emite eixo continuo`() {
        val effective = mapOf("LEFT_TRIGGER" to "expr:deadzone(axis:left_y, 0.3)")
        val bindings = ExprBindingProcessor.parseBindings(effective)
        assertEquals(GamepadAxis.LEFT_TRIGGER, bindings[0].axis)
        val state = ExprState()
        val events = ExprBindingProcessor.evaluate(
            bindings,
            reader(mapOf("axis:left_y" to 0.8f)),
            state,
            50L,
            1000L,
            deviceId = 1,
        )
        // deadzone(0.8, 0.3) = 0.5/0.7 ≈ 0.714 > 0.5 → Down + AxisMotion contínuo.
        assertTrue(events.contains(InputEvent.ButtonDown(1, GamepadButton.LEFT_TRIGGER)))
        val motion = events.filterIsInstance<InputEvent.AxisMotion>().single()
        assertEquals(GamepadAxis.LEFT_TRIGGER, motion.axis)
        assertTrue("esperado ~0.714, veio ${motion.value}", motion.value in 0.70f..0.73f)
    }

    @Test
    fun `chave de eixo puro emite so AxisMotion sem transicoes de botao`() {
        // Correção A (spec-...-verificacao §5.1): a chave pode nomear SÓ um
        // GamepadAxis — o binding vira eixo contínuo, sem botão dono.
        val effective = mapOf("LEFT_X" to "expr:deadzone(axis:left_y, 0.3)")
        val bindings = ExprBindingProcessor.parseBindings(effective)
        assertEquals(1, bindings.size)
        assertTrue(bindings[0].button == null)
        assertEquals(GamepadAxis.LEFT_X, bindings[0].axis)
        val state = ExprState()
        val events = ExprBindingProcessor.evaluate(
            bindings,
            reader(mapOf("axis:left_y" to 0.8f)),
            state,
            50L,
            1000L,
            deviceId = 1,
        )
        assertTrue(events.none { it is InputEvent.ButtonDown || it is InputEvent.ButtonUp })
        val motion = events.filterIsInstance<InputEvent.AxisMotion>().single()
        assertEquals(GamepadAxis.LEFT_X, motion.axis)
        assertTrue("esperado ~0.714, veio ${motion.value}", motion.value in 0.70f..0.73f)
        // Chave que não é botão NEM eixo é pulada.
        assertTrue(ExprBindingProcessor.parseBindings(mapOf("GARBAGE" to "expr:1")).isEmpty())
    }

    @Test
    fun `sem expr nada muda`() {
        val effective = mapOf("FACE_TOP" to "key:96", "RIGHT_BUMPER" to "key:99:turbo")
        assertTrue(ExprBindingProcessor.parseBindings(effective).isEmpty())
        assertTrue(ExprBindingProcessor.evaluate(emptyList(), reader(emptyMap()), ExprState(), 50L, 1000L, 1).isEmpty())
    }

    @Test
    fun `expr invalido e pulado sem excecao`() {
        val effective = mapOf(
            "FACE_TOP" to "expr:face_bottom +",
            "FACE_RIGHT" to "expr:min(1)",
        )
        assertTrue(ExprBindingProcessor.parseBindings(effective).isEmpty())
        assertTrue(ExprBindingProcessor.hasExpressionTokens(effective))
    }

    @Test
    fun `chord emite quando todos os botoes seguram e suprime o final simples`() {
        val effective = mapOf(
            "FACE_TOP" to "expr:face_bottom + face_right",
            "FACE_RIGHT" to "expr:face_right", // binding simples do FINAL
        )
        val bindings = ExprBindingProcessor.parseBindings(effective)
        assertEquals(2, bindings.size)
        assertTrue(bindings[0].chord != null)
        val state = ExprState()
        val chords = ExprBindingProcessor.chordsOf(bindings)

        // A (modificador) segurado, B solto: nada emite (chord 0; B simples não
        // está suprimido ainda mas vale 0).
        val held = setOf("face_bottom")
        val events = ExprBindingProcessor.evaluate(
            bindings, reader(mapOf("face_bottom" to 1f)), state, 50L, 1000L, deviceId = 3, held, chords,
        )
        assertTrue(events.isEmpty())

        // B também segurado: o CHORD emite; o binding simples de B é suprimido.
        val both = ExprBindingProcessor.evaluate(
            bindings,
            reader(mapOf("face_bottom" to 1f, "face_right" to 1f)),
            state,
            50L,
            1100L,
            deviceId = 3,
            setOf("face_bottom", "face_right"),
            chords,
        )
        assertTrue(both.contains(InputEvent.ButtonDown(3, GamepadButton.FACE_TOP)))
        assertFalse(both.any { it is InputEvent.ButtonDown && it.button == GamepadButton.FACE_RIGHT })
    }

    @Test
    fun `superset de chords vence`() {
        val effective = mapOf(
            "FACE_TOP" to "expr:face_bottom + face_right",
            "FACE_LEFT" to "expr:face_bottom + face_right + face_top",
        )
        val bindings = ExprBindingProcessor.parseBindings(effective)
        val state = ExprState()
        val chords = ExprBindingProcessor.chordsOf(bindings)
        val held = setOf("face_bottom", "face_right", "face_top")
        val events = ExprBindingProcessor.evaluate(
            bindings, reader(mapOf("face_bottom" to 1f, "face_right" to 1f, "face_top" to 1f)), state, 50L, 1000L, deviceId = 3, held, chords,
        )
        // Só o MAIOR emite (o menor é suprimido por superconjunto).
        assertTrue(events.contains(InputEvent.ButtonDown(3, GamepadButton.FACE_LEFT)))
        assertFalse(events.any { it is InputEvent.ButtonDown && it.button == GamepadButton.FACE_TOP })
    }

    @Test
    fun `detecta tokens de expressao`() {
        assertTrue(ExprBindingProcessor.hasExpressionTokens(mapOf("A" to "expr:toggle(face_bottom)")))
        assertTrue(!ExprBindingProcessor.hasExpressionTokens(mapOf("A" to "key:96")))
        assertTrue(!ExprBindingProcessor.hasExpressionTokens(emptyMap()))
    }
}
