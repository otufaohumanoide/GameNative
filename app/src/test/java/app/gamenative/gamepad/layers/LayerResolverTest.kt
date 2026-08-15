package app.gamenative.gamepad.layers

import app.gamenative.gamepad.profiles.ActionLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U3 (spec 2026-08-14-gamepad-u3-u4-layers-remap-jogo, §1.2): motor de ativação de
 * camadas — HOLD/TOGGLE/DOUBLE_TAP, uma camada por vez, estado por device (V6),
 * merge efetivo DEFAULT + ativa.
 */
class LayerResolverTest {

    private fun trigger(button: String, mode: LayerTriggerMode, doubleTapMs: Int = 250, isShift: Boolean = false) =
        LayerTriggerSpec(button, mode, doubleTapMs, isShift)

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

    // ── F (spec 2026-08-16-F-radial-v2-modeshift-turbo, §1.3): camada de SHIFT ──

    @Test
    fun `shift hold trigger ativa e desativa igual a camada comum`() {
        // Branch preserva a mecânica U3 — a ativação é IDÊNTICA; só o HUB suprime
        // os eventos comuns (GamepadLayerEvent/tick) e consome o botão físico.
        val state = LayerState()
        assertEquals(
            LayerChange.Activated("SHIFT"),
            LayerResolver.onButtonDown(state, "SHIFT", trigger("LEFT_BUMPER", LayerTriggerMode.HOLD, isShift = true), 0),
        )
        assertEquals("SHIFT", state.activeLayer)
        assertEquals(
            LayerChange.Deactivated("SHIFT"),
            LayerResolver.onButtonUp(state, "SHIFT", trigger("LEFT_BUMPER", LayerTriggerMode.HOLD, isShift = true), 100),
        )
        assertNull(state.activeLayer)
    }

    @Test
    fun `shift toggle e double tap seguem a mecanica comum`() {
        val state = LayerState()
        assertEquals(
            LayerChange.Activated("SHIFT"),
            LayerResolver.onButtonDown(state, "SHIFT", trigger("RIGHT_STICK", LayerTriggerMode.TOGGLE, isShift = true), 0),
        )
        assertEquals(
            LayerChange.Deactivated("SHIFT"),
            LayerResolver.onButtonDown(state, "SHIFT", trigger("RIGHT_STICK", LayerTriggerMode.TOGGLE, isShift = true), 1000),
        )
        assertEquals(
            LayerChange.None,
            LayerResolver.onButtonDown(state, "SHIFT", trigger("FACE_TOP", LayerTriggerMode.DOUBLE_TAP, isShift = true), 0),
        )
        assertEquals(
            LayerChange.Activated("SHIFT"),
            LayerResolver.onButtonDown(state, "SHIFT", trigger("FACE_TOP", LayerTriggerMode.DOUBLE_TAP, isShift = true), 100),
        )
    }

    @Test
    fun `shift suprime eventos comuns e camada comum nao`() {
        // Decisão PURA consumida pelo hub (resolveLayerTriggers): shift NÃO emite
        // GamepadLayerEvent (não abre radial) e NÃO dá tick háptico.
        assertTrue(LayerResolver.suppressCommonEvents(trigger("X", LayerTriggerMode.HOLD, isShift = true)))
        assertFalse(LayerResolver.suppressCommonEvents(trigger("X", LayerTriggerMode.HOLD)))
        assertFalse(LayerResolver.suppressCommonEvents(trigger("X", LayerTriggerMode.TOGGLE)))
    }

    @Test
    fun `shift ativo resolve effectiveBindings pela camada shift`() {
        // NENHUMA mudança no resolver comum — a camada shift remapeia pelo merge
        // DEFAULT + ativa existente (mecânica U3).
        val layers = mapOf(
            ActionLayer.DEFAULT.name to mapOf("FACE_BOTTOM" to "key:96"),
            "SHIFT" to mapOf("FACE_BOTTOM" to "key:97", "FACE_TOP" to "key:100"),
        )
        val withShift = LayerResolver.effectiveBindings(layers, "SHIFT")
        assertEquals("key:97", withShift["FACE_BOTTOM"])
        assertEquals("key:100", withShift["FACE_TOP"])
    }

    @Test
    fun `trigger spec serializa isShift e default preserva v1`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val encoded = json.encodeToString(
            LayerTriggerSpec.serializer(),
            LayerTriggerSpec("FACE_BOTTOM", LayerTriggerMode.HOLD, isShift = true),
        )
        val decoded = json.decodeFromString<LayerTriggerSpec>(encoded)
        assertTrue(decoded.isShift)
        // JSON v1 (sem isShift) → false (degradação byte-identical).
        val v1 = json.decodeFromString<LayerTriggerSpec>(
            """{"button":"FACE_BOTTOM","mode":"HOLD","doubleTapMs":250}""",
        )
        assertFalse(v1.isShift)
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
