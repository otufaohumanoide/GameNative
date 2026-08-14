package app.gamenative.gamepad.mapping

import app.gamenative.gamepad.GamepadAxis
import app.gamenative.gamepad.GamepadButton
import app.gamenative.gamepad.InputEvent
import app.gamenative.gamepad.processing.DeadzoneConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tradutor (spec 2026-08-13, Passo 4 + D9): keycodes REAIS do Android (96-110, 19-22,
 * 188-203) — nenhum keycode inventado. Casos: DS4 X físico → FACE_BOTTOM; DInput axes
 * 0/1/3/4 → sticks; trigger axis vs botão; hat → DPAD; desconhecido → vazio.
 */
class EventTranslatorTest {

    private fun key(
        keyCode: Int,
        action: Int = AndroidConstants.ACTION_DOWN,
        repeatCount: Int = 0,
        deviceId: Int = 1,
    ) = RawKeyInput(deviceId = deviceId, source = 0x01000010, keyCode = keyCode, action = action, repeatCount = repeatCount)

    private fun axis(
        values: Map<Int, Float>,
        deviceId: Int = 1,
    ) = RawAxisInput(deviceId = deviceId, source = 0x01000010, action = 0, axisValues = values)

    @Test
    fun `ds4 physical X keycode 96 maps to FACE_BOTTOM`() {
        val mapping = MappingDatabase.mappingFor(0x054c, 0x09cc)!!
        val events = EventTranslator.translateKey(key(keyCode = 96), mapping)
        assertEquals(listOf(InputEvent.ButtonDown(1, GamepadButton.FACE_BOTTOM)), events)
    }

    @Test
    fun `action up maps to ButtonUp`() {
        val mapping = MappingDatabase.mappingFor(0x054c, 0x09cc)!!
        val events = EventTranslator.translateKey(key(keyCode = 96, action = AndroidConstants.ACTION_UP), mapping)
        assertEquals(listOf(InputEvent.ButtonUp(1, GamepadButton.FACE_BOTTOM)), events)
    }

    @Test
    fun `repeat down is swallowed`() {
        val mapping = MappingDatabase.mappingFor(0x054c, 0x09cc)!!
        val events = EventTranslator.translateKey(key(keyCode = 96, repeatCount = 1), mapping)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `invented keycodes from the old implementation are ignored`() {
        // 304/305/307/308 não existem no Android — NENHUM evento pode casar.
        val mapping = MappingDatabase.mappingFor(0x054c, 0x09cc)!!
        for (keyCode in listOf(304, 305, 307, 308, 289, 290, 300)) {
            assertTrue("keyCode $keyCode deve ser ignorado", EventTranslator.translateKey(key(keyCode), mapping).isEmpty())
        }
    }

    @Test
    fun `unknown device falls back to null and caller uses default mapping`() {
        assertEquals(null, MappingDatabase.mappingFor(0x1234, 0x5678))
        val mapping = MappingDatabase.defaultAndroidMapping(app.gamenative.gamepad.FaceStyle.GENERIC)
        assertEquals(
            listOf(InputEvent.ButtonDown(1, GamepadButton.FACE_BOTTOM)),
            EventTranslator.translateKey(key(keyCode = 96), mapping),
        )
    }

    @Test
    fun `dinput axes 0 1 3 4 map to sticks via the mapping dictionary`() {
        // Perfil estilo DInput com right stick nos eixos 3/4 (padrão Nacon/Astro).
        val mapping = GamepadMapping(
            mappingKey = "00000000",
            name = "DInput 3/4",
            faceStyle = app.gamenative.gamepad.FaceStyle.GENERIC,
            buttons = emptyMap(),
            axes = mapOf(
                GamepadAxis.LEFT_X to RawBinding.Axis(0, +1),
                GamepadAxis.LEFT_Y to RawBinding.Axis(1, +1),
                GamepadAxis.RIGHT_X to RawBinding.Axis(3, +1),
                GamepadAxis.RIGHT_Y to RawBinding.Axis(4, +1),
            ),
        )
        // Deadzone zero isola o ROTEAMENTO (mapping-driven) do processamento (testado à parte).
        val events = EventTranslator.translateAxis(
            axis(mapOf(0 to 0.5f, 1 to -0.3f, 3 to 0.2f, 4 to 0.8f)),
            mapping,
            DeadzoneConfig(leftStick = 0f, rightStick = 0f),
        )
        assertTrue(events.any { it == InputEvent.AxisMotion(1, GamepadAxis.LEFT_X, 0.5f) })
        assertTrue(events.any { it == InputEvent.AxisMotion(1, GamepadAxis.LEFT_Y, -0.3f) })
        assertTrue(events.any { it == InputEvent.AxisMotion(1, GamepadAxis.RIGHT_X, 0.2f) })
        assertTrue(events.any { it == InputEvent.AxisMotion(1, GamepadAxis.RIGHT_Y, 0.8f) })
    }

    @Test
    fun `trigger axis is deadzone processed and rescaled`() {
        val mapping = MappingDatabase.defaultAndroidMapping(app.gamenative.gamepad.FaceStyle.XBOX)
        // AXIS_LTRIGGER=17 com valor 0.9; deadzone 0.08 → saída rescalonada ~1.0.
        val events = EventTranslator.translateAxis(
            axis(mapOf(17 to 0.9f)),
            mapping,
            DeadzoneConfig(leftTrigger = 0.08f, rightTrigger = 0.08f),
        )
        val motion = events.filterIsInstance<InputEvent.AxisMotion>()
        // (0.9 − 0.03) / (1 − 0.03) ≈ 0.897 — rescalonado para 0..1, sem perder faixa.
        assertTrue(motion.any { it.axis == GamepadAxis.LEFT_TRIGGER && it.value in 0.8f..1.0f })
    }

    @Test
    fun `trigger as button arrives via translateKey`() {
        val mapping = MappingDatabase.defaultAndroidMapping(app.gamenative.gamepad.FaceStyle.XBOX)
        // KEYCODE_BUTTON_L2=104 → LEFT_TRIGGER.
        val events = EventTranslator.translateKey(key(keyCode = 104), mapping)
        assertEquals(listOf(InputEvent.ButtonDown(1, GamepadButton.LEFT_TRIGGER)), events)
    }

    @Test
    fun `hat axes convert to dpad buttons`() {
        val mapping = MappingDatabase.mappingFor(0x0079, 0x0006)!! // genérico DInput com hat
        // AXIS_HAT_Y=-1 (cima) → DPAD_UP.
        val up = EventTranslator.translateAxis(axis(mapOf(15 to 0f, 16 to -1f)), mapping, DeadzoneConfig())
        assertTrue(up.contains(InputEvent.ButtonDown(1, GamepadButton.DPAD_UP)))

        // AXIS_HAT_X=+1 (direita) → DPAD_RIGHT.
        val right = EventTranslator.translateAxis(axis(mapOf(15 to 1f, 16 to 0f)), mapping, DeadzoneConfig())
        assertTrue(right.contains(InputEvent.ButtonDown(1, GamepadButton.DPAD_RIGHT)))

        // Hat neutro → libera (estado completo da amostra; o hub vira transição).
        val neutral = EventTranslator.translateAxis(axis(mapOf(15 to 0f, 16 to 0f)), mapping, DeadzoneConfig())
        assertTrue(neutral.contains(InputEvent.ButtonUp(1, GamepadButton.DPAD_UP)))
        assertTrue(neutral.contains(InputEvent.ButtonUp(1, GamepadButton.DPAD_RIGHT)))
    }

    @Test
    fun `stick inside deadzone emits nothing`() {
        val mapping = MappingDatabase.defaultAndroidMapping(app.gamenative.gamepad.FaceStyle.GENERIC)
        val events = EventTranslator.translateAxis(
            axis(mapOf(0 to 0.05f, 1 to 0.0f)),
            mapping,
            DeadzoneConfig(leftStick = 0.15f),
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun `mapping axes drive the translator not hardcoded indices`() {
        // Axis 5 não é trigger no Android (AXIS_RTRIGGER=18); sem binding → sem evento.
        val mapping = MappingDatabase.defaultAndroidMapping(app.gamenative.gamepad.FaceStyle.GENERIC)
        val events = EventTranslator.translateAxis(
            axis(mapOf(5 to 0.9f)),
            mapping,
            DeadzoneConfig(),
        )
        assertTrue(events.isEmpty())
    }
}
