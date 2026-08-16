package app.gamenative.gamepad.virtual

import app.gamenative.gamepad.GamepadAxis
import app.gamenative.gamepad.GamepadButton
import app.gamenative.gamepad.mapping.AndroidConstants
import com.winlator.inputcontrols.Binding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * K1 (spec 2026-08-16-K1, §1.1/§1.2): conversões do bridge do gamepad virtual —
 * Binding do overlay ↔ RawInput do hub (mapping IDENTIDADE: keycodes Android
 * canônicos) e InputEvent lógico → Binding da injeção final (caminho U4).
 */
class TouchGamepadSourceTest {

    // ── Botões: Binding → RawKeyInput ──

    @Test
    fun `botoes de face viram keycodes canonicos`() {
        assertEquals(
            AndroidConstants.BUTTON_A,
            TouchGamepadBridge.rawKeyFor(Binding.GAMEPAD_BUTTON_A, true)!!.keyCode,
        )
        assertEquals(
            AndroidConstants.BUTTON_B,
            TouchGamepadBridge.rawKeyFor(Binding.GAMEPAD_BUTTON_B, true)!!.keyCode,
        )
        assertEquals(
            AndroidConstants.BUTTON_X,
            TouchGamepadBridge.rawKeyFor(Binding.GAMEPAD_BUTTON_X, true)!!.keyCode,
        )
        assertEquals(
            AndroidConstants.BUTTON_Y,
            TouchGamepadBridge.rawKeyFor(Binding.GAMEPAD_BUTTON_Y, true)!!.keyCode,
        )
    }

    @Test
    fun `down e up viram action 0 e 1`() {
        val down = TouchGamepadBridge.rawKeyFor(Binding.GAMEPAD_BUTTON_A, true)!!
        val up = TouchGamepadBridge.rawKeyFor(Binding.GAMEPAD_BUTTON_A, false)!!
        assertEquals(AndroidConstants.ACTION_DOWN, down.action)
        assertEquals(AndroidConstants.ACTION_UP, up.action)
        assertEquals(TouchGamepadConstants.DEVICE_ID, down.deviceId)
    }

    @Test
    fun `dpad e demais botoes mapeiam`() {
        assertEquals(AndroidConstants.DPAD_UP, TouchGamepadBridge.rawKeyFor(Binding.GAMEPAD_DPAD_UP, true)!!.keyCode)
        assertEquals(AndroidConstants.DPAD_DOWN, TouchGamepadBridge.rawKeyFor(Binding.GAMEPAD_DPAD_DOWN, true)!!.keyCode)
        assertEquals(AndroidConstants.DPAD_LEFT, TouchGamepadBridge.rawKeyFor(Binding.GAMEPAD_DPAD_LEFT, true)!!.keyCode)
        assertEquals(AndroidConstants.DPAD_RIGHT, TouchGamepadBridge.rawKeyFor(Binding.GAMEPAD_DPAD_RIGHT, true)!!.keyCode)
        assertEquals(AndroidConstants.BUTTON_L1, TouchGamepadBridge.rawKeyFor(Binding.GAMEPAD_BUTTON_L1, true)!!.keyCode)
        assertEquals(AndroidConstants.BUTTON_R2, TouchGamepadBridge.rawKeyFor(Binding.GAMEPAD_BUTTON_R2, true)!!.keyCode)
        assertEquals(AndroidConstants.BUTTON_THUMBL, TouchGamepadBridge.rawKeyFor(Binding.GAMEPAD_BUTTON_L3, true)!!.keyCode)
        assertEquals(AndroidConstants.BUTTON_THUMBR, TouchGamepadBridge.rawKeyFor(Binding.GAMEPAD_BUTTON_R3, true)!!.keyCode)
        assertEquals(AndroidConstants.BUTTON_START, TouchGamepadBridge.rawKeyFor(Binding.GAMEPAD_BUTTON_START, true)!!.keyCode)
        assertEquals(AndroidConstants.BUTTON_SELECT, TouchGamepadBridge.rawKeyFor(Binding.GAMEPAD_BUTTON_SELECT, true)!!.keyCode)
    }

    @Test
    fun `binding nao-gamepad nao vira key`() {
        assertNull(TouchGamepadBridge.rawKeyFor(Binding.KEY_A, true))
        assertNull(TouchGamepadBridge.rawKeyFor(Binding.MOUSE_MOVE_LEFT, true))
        assertNull(TouchGamepadBridge.rawKeyFor(Binding.NONE, true))
    }

    // ── Sticks: Binding → RawAxisInput (eixo real + valor cru do delta) ──

    @Test
    fun `thumb esquerdo vira eixos X Y com o valor cru`() {
        // UP/DOWN compartilham o eixo Y (o ControlElement emite o MESMO deltaY
        // nas duas direções — o bridge usa o eixo, não a direção).
        val up = TouchGamepadBridge.rawAxisFor(Binding.GAMEPAD_LEFT_THUMB_UP, true, -0.8f)!!
        assertEquals(AndroidConstants.AXIS_Y, up.axisValues.keys.single())
        assertEquals(-0.8f, up.axisValues.values.single())
        val down = TouchGamepadBridge.rawAxisFor(Binding.GAMEPAD_LEFT_THUMB_DOWN, true, -0.8f)!!
        assertEquals(AndroidConstants.AXIS_Y, down.axisValues.keys.single())
        val left = TouchGamepadBridge.rawAxisFor(Binding.GAMEPAD_LEFT_THUMB_LEFT, true, -0.5f)!!
        assertEquals(AndroidConstants.AXIS_X, left.axisValues.keys.single())
        val right = TouchGamepadBridge.rawAxisFor(Binding.GAMEPAD_LEFT_THUMB_RIGHT, true, 0.5f)!!
        assertEquals(AndroidConstants.AXIS_X, right.axisValues.keys.single())
    }

    @Test
    fun `thumb direito usa Z e RZ reais`() {
        val up = TouchGamepadBridge.rawAxisFor(Binding.GAMEPAD_RIGHT_THUMB_UP, true, -0.3f)!!
        assertEquals(AndroidConstants.AXIS_RZ, up.axisValues.keys.single())
        val left = TouchGamepadBridge.rawAxisFor(Binding.GAMEPAD_RIGHT_THUMB_LEFT, true, -0.3f)!!
        assertEquals(AndroidConstants.AXIS_Z, left.axisValues.keys.single())
    }

    @Test
    fun `release do stick emite zero apenas com isDown false e valor zero`() {
        // O elemento solto chama handleInputEvent(binding, false) sem offset —
        // o bridge devolve null (o eixo par já foi emitido como 0 no release
        // anterior; sem duplicação).
        assertNull(TouchGamepadBridge.rawAxisFor(Binding.GAMEPAD_LEFT_THUMB_UP, false, 0f))
        // Release com offset não-zero (elemento que solta com o valor ainda
        // parcial) emite o zero do eixo.
        val release = TouchGamepadBridge.rawAxisFor(Binding.GAMEPAD_LEFT_THUMB_UP, false, 0.4f)!!
        assertEquals(0.4f, release.axisValues.values.single())
    }

    // ── Inversa: lógico → Binding da injeção final (caminho U4) ──

    @Test
    fun `botoes logicos viram bindings do overlay`() {
        assertEquals(Binding.GAMEPAD_BUTTON_A, TouchGamepadBridge.bindingFor(GamepadButton.FACE_BOTTOM))
        assertEquals(Binding.GAMEPAD_BUTTON_B, TouchGamepadBridge.bindingFor(GamepadButton.FACE_RIGHT))
        assertEquals(Binding.GAMEPAD_DPAD_UP, TouchGamepadBridge.bindingFor(GamepadButton.DPAD_UP))
        assertEquals(Binding.GAMEPAD_BUTTON_L2, TouchGamepadBridge.bindingFor(GamepadButton.LEFT_TRIGGER))
        assertEquals(Binding.GAMEPAD_BUTTON_START, TouchGamepadBridge.bindingFor(GamepadButton.START))
    }

    @Test
    fun `GUIDE e extras nao tem binding no overlay`() {
        assertNull(TouchGamepadBridge.bindingFor(GamepadButton.GUIDE))
        assertNull(TouchGamepadBridge.bindingFor(GamepadButton.MISC1))
        assertNull(TouchGamepadBridge.bindingFor(GamepadButton.TOUCHPAD))
    }

    @Test
    fun `eixos logicos viram pares de direcao do overlay`() {
        assertEquals(
            Binding.GAMEPAD_LEFT_THUMB_LEFT to Binding.GAMEPAD_LEFT_THUMB_RIGHT,
            TouchGamepadBridge.axisBindingsFor(GamepadAxis.LEFT_X),
        )
        assertEquals(
            Binding.GAMEPAD_LEFT_THUMB_UP to Binding.GAMEPAD_LEFT_THUMB_DOWN,
            TouchGamepadBridge.axisBindingsFor(GamepadAxis.LEFT_Y),
        )
        assertEquals(
            Binding.GAMEPAD_RIGHT_THUMB_UP to Binding.GAMEPAD_RIGHT_THUMB_DOWN,
            TouchGamepadBridge.axisBindingsFor(GamepadAxis.RIGHT_Y),
        )
        assertNull(TouchGamepadBridge.axisBindingsFor(GamepadAxis.LEFT_TRIGGER))
    }

    // ── Mapping identidade (entry virtual do MappingDatabase) ──

    @Test
    fun `entry virtual e a identidade dos pads normalizados`() {
        val mapping = app.gamenative.gamepad.mapping.MappingDatabase.mappingFor(0, 0)!!
        // keycode canônico → botão semântico (o bridge fala essa língua).
        assertEquals(
            GamepadButton.FACE_BOTTOM,
            mapping.buttons.entries.first { (it.value as app.gamenative.gamepad.mapping.RawBinding.Key).keyCode == AndroidConstants.BUTTON_A }.key,
        )
        assertEquals(
            GamepadAxis.LEFT_X,
            mapping.axes.entries.first { (it.value as app.gamenative.gamepad.mapping.RawBinding.Axis).axis == AndroidConstants.AXIS_X }.key,
        )
    }
}
