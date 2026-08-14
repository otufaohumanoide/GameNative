package app.gamenative.gamepad.layers

import app.gamenative.gamepad.profiles.ActionLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * U3 (spec 2026-08-14-gamepad-u3-u4-layers-remap-jogo, §1.2): motor de ativação de
 * camadas — HOLD/TOGGLE/DOUBLE_TAP, uma camada por vez, estado por device (V6),
 * merge efetivo DEFAULT + ativa.
 */
class LayerResolverTest {

    private fun trigger(button: String, mode: LayerTriggerMode, doubleTapMs: Int = 250) =
        LayerTriggerSpec(button, mode, doubleTapMs)

    @Test
    fun `hold activates on down and deactivates on up`() {
        val state = LayerState()
        assertEquals(LayerChange.Activated("SPRINT"), LayerResolver.onButtonDown(state, "SPRINT", trigger("LEFT_BUMPER", LayerTriggerMode.HOLD), 0))
        assertEquals("SPRINT", state.activeLayer)
        assertEquals(LayerChange.Deactivated("SPRINT"), LayerResolver.onButtonUp(state, "SPRINT", trigger("LEFT_BUMPER", LayerTriggerMode.HOLD), 100))
        assertNull(state.activeLayer)
    }

    @Test
    fun `hold up of a non active layer does nothing`() {
        val state = LayerState()
        LayerResolver.onButtonDown(state, "A", trigger("LEFT_BUMPER", LayerTriggerMode.HOLD), 0)
        val change = LayerResolver.onButtonUp(state, "B", trigger("RIGHT_BUMPER", LayerTriggerMode.HOLD), 100)
        assertEquals(LayerChange.None, change)
        assertEquals("A", state.activeLayer)
    }

    @Test
    fun `toggle inverts on each press`() {
        val state = LayerState()
        assertEquals(LayerChange.Activated("SNIPER"), LayerResolver.onButtonDown(state, "SNIPER", trigger("RIGHT_STICK", LayerTriggerMode.TOGGLE), 0))
        assertEquals("SNIPER", state.activeLayer)
        assertEquals(LayerChange.Deactivated("SNIPER"), LayerResolver.onButtonDown(state, "SNIPER", trigger("RIGHT_STICK", LayerTriggerMode.TOGGLE), 1000))
        assertNull(state.activeLayer)
    }

    @Test
    fun `activating another layer replaces the active one`() {
        val state = LayerState()
        LayerResolver.onButtonDown(state, "A", trigger("LEFT_BUMPER", LayerTriggerMode.HOLD), 0)
        assertEquals(LayerChange.Activated("B"), LayerResolver.onButtonDown(state, "B", trigger("RIGHT_BUMPER", LayerTriggerMode.HOLD), 10))
        assertEquals("B", state.activeLayer)
        // Soltar o trigger da camada A não afeta B.
        assertEquals(LayerChange.None, LayerResolver.onButtonUp(state, "A", trigger("LEFT_BUMPER", LayerTriggerMode.HOLD), 20))
        assertEquals("B", state.activeLayer)
    }

    @Test
    fun `double tap within window toggles`() {
        val state = LayerState()
        // Primeiro tap: arma a janela.
        assertEquals(LayerChange.None, LayerResolver.onButtonDown(state, "X", trigger("FACE_TOP", LayerTriggerMode.DOUBLE_TAP), 0))
        assertNull(state.activeLayer)
        // Segundo tap dentro da janela: ativa.
        assertEquals(LayerChange.Activated("X"), LayerResolver.onButtonDown(state, "X", trigger("FACE_TOP", LayerTriggerMode.DOUBLE_TAP), 100))
        assertEquals("X", state.activeLayer)
        // Terceiro tap (fora da janela): arma de novo (sem toggle).
        assertEquals(LayerChange.None, LayerResolver.onButtonDown(state, "X", trigger("FACE_TOP", LayerTriggerMode.DOUBLE_TAP), 1000))
        // Quarto dentro: desativa.
        assertEquals(LayerChange.Deactivated("X"), LayerResolver.onButtonDown(state, "X", trigger("FACE_TOP", LayerTriggerMode.DOUBLE_TAP), 1100))
        assertNull(state.activeLayer)
    }

    @Test
    fun `two hold layers - pressing the second trigger while first is held activates it`() {
        // Regressão 2026-08-14: o flag único de hold quebrava múltiplas camadas HOLD.
        val state = LayerState()
        LayerResolver.onButtonDown(state, "A", trigger("LEFT_BUMPER", LayerTriggerMode.HOLD), 0)
        assertEquals("A", state.activeLayer)
        assertEquals(
            LayerChange.Activated("B"),
            LayerResolver.onButtonDown(state, "B", trigger("RIGHT_BUMPER", LayerTriggerMode.HOLD), 10),
        )
        assertEquals("B", state.activeLayer)
        // Soltar o trigger de A não afeta B; soltar B desativa B.
        LayerResolver.onButtonUp(state, "A", trigger("LEFT_BUMPER", LayerTriggerMode.HOLD), 20)
        assertEquals("B", state.activeLayer)
        assertEquals(
            LayerChange.Deactivated("B"),
            LayerResolver.onButtonUp(state, "B", trigger("RIGHT_BUMPER", LayerTriggerMode.HOLD), 30),
        )
        assertNull(state.activeLayer)
    }

    @Test
    fun `effective bindings merge default and active layer`() {
        val layers = mapOf(
            ActionLayer.DEFAULT.name to mapOf("FACE_BOTTOM" to "key:96"),
            "SPRINT" to mapOf("FACE_BOTTOM" to "key:97", "FACE_TOP" to "key:100"),
        )
        val defaultOnly = LayerResolver.effectiveBindings(layers, null)
        assertEquals("key:96", defaultOnly["FACE_BOTTOM"])
        assertNull(defaultOnly["FACE_TOP"])
        val withActive = LayerResolver.effectiveBindings(layers, "SPRINT")
        assertEquals("key:97", withActive["FACE_BOTTOM"]) // ativa sobrepõe DEFAULT
        assertEquals("key:100", withActive["FACE_TOP"])
    }
}
