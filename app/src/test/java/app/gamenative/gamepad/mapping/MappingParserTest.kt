package app.gamenative.gamepad.mapping

import app.gamenative.gamepad.GamepadButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gramática SDL real (spec 2026-08-13, Parte I §4 e Passo 3) validada com linhas REAIS
 * do SDL_GameControllerDB/gamecontrollerdb.txt (licença zlib).
 */
class MappingParserTest {

    // Linha real do DB (índice 577): PS4 Controller, vendor 054c product 05c4.
    private val realPs4Line =
        "030000004c050000c405000000000000,PS4 Controller," +
            "a:b1,b:b2,back:b8,dpdown:h0.4,dpleft:h0.8,dpright:h0.2,dpup:h0.1," +
            "guide:b12,leftshoulder:b4,leftstick:b10,lefttrigger:a3,leftx:a0,lefty:a1," +
            "rightshoulder:b5,rightstick:b11,righttrigger:a4,rightx:a2,righty:a5," +
            "start:b9,touchpad:b13,x:b0,y:b3,platform:Windows,"

    @Test
    fun `real gamecontrollerdb line parses with guid and name discarded`() {
        val mapping = MappingParser.parse(realPs4Line)!!
        // GUID → vendor+product (054c:05c4), nome é o campo 1.
        assertEquals("054c05c4", mapping.mappingKey)
        assertEquals("PS4 Controller", mapping.name)
        // Nada do GUID/nome virou binding.
        assertTrue(mapping.buttons.none { it.key.name.contains("0300") || it.key.name.contains("Controller") })
    }

    @Test
    fun `real line buttons map to the generic button space`() {
        val mapping = MappingParser.parse(realPs4Line)!!
        assertEquals(RawBinding.Key(189), mapping.buttons[GamepadButton.FACE_BOTTOM])   // a:b1
        assertEquals(RawBinding.Key(190), mapping.buttons[GamepadButton.FACE_RIGHT])   // b:b2
        assertEquals(RawBinding.Key(188), mapping.buttons[GamepadButton.FACE_LEFT])    // x:b0
        assertEquals(RawBinding.Key(191), mapping.buttons[GamepadButton.FACE_TOP])     // y:b3
        assertEquals(RawBinding.Key(196), mapping.buttons[GamepadButton.SELECT])       // back:b8
        assertEquals(RawBinding.Key(200), mapping.buttons[GamepadButton.GUIDE])        // guide:b12
        assertEquals(RawBinding.Key(197), mapping.buttons[GamepadButton.START])        // start:b9
        assertEquals(RawBinding.Key(192), mapping.buttons[GamepadButton.LEFT_BUMPER])  // leftshoulder:b4
        assertEquals(RawBinding.Key(193), mapping.buttons[GamepadButton.RIGHT_BUMPER]) // rightshoulder:b5
        assertEquals(RawBinding.Key(198), mapping.buttons[GamepadButton.LEFT_STICK])   // leftstick:b10
        assertEquals(RawBinding.Key(199), mapping.buttons[GamepadButton.RIGHT_STICK])  // rightstick:b11
    }

    @Test
    fun `hat mask follows the SDL bitfield`() {
        val mapping = MappingParser.parse(realPs4Line)!!
        assertEquals(RawBinding.Hat(0, 1), mapping.buttons[GamepadButton.DPAD_UP])
        assertEquals(RawBinding.Hat(0, 2), mapping.buttons[GamepadButton.DPAD_RIGHT])
        assertEquals(RawBinding.Hat(0, 4), mapping.buttons[GamepadButton.DPAD_DOWN])
        assertEquals(RawBinding.Hat(0, 8), mapping.buttons[GamepadButton.DPAD_LEFT])
    }

    @Test
    fun `axis values become axis bindings with direction`() {
        val mapping = MappingParser.parse(realPs4Line)!!
        assertEquals(RawBinding.Axis(0, +1), mapping.axes[app.gamenative.gamepad.GamepadAxis.LEFT_X])
        assertEquals(RawBinding.Axis(1, +1), mapping.axes[app.gamenative.gamepad.GamepadAxis.LEFT_Y])
        assertEquals(RawBinding.Axis(2, +1), mapping.axes[app.gamenative.gamepad.GamepadAxis.RIGHT_X])
        assertEquals(RawBinding.Axis(5, +1), mapping.axes[app.gamenative.gamepad.GamepadAxis.RIGHT_Y])
        assertEquals(RawBinding.Axis(3, +1), mapping.axes[app.gamenative.gamepad.GamepadAxis.LEFT_TRIGGER])
        assertEquals(RawBinding.Axis(4, +1), mapping.axes[app.gamenative.gamepad.GamepadAxis.RIGHT_TRIGGER])
    }

    @Test
    fun `inverted and half-axis syntax`() {
        // PS3 Controller: lefttrigger:a3~ (invertido)
        val inverted = MappingParser.parse(
            "030000004c0500006802000000000000,PS3 Controller," +
                "a:b2,b:b1,lefttrigger:a3~,righttrigger:a4~,leftx:a0,lefty:a1," +
                "rightx:a2,righty:a5,platform:Windows,",
        )!!
        assertEquals(
            RawBinding.Axis(3, -1),
            inverted.axes[app.gamenative.gamepad.GamepadAxis.LEFT_TRIGGER],
        )
        assertEquals(
            RawBinding.Axis(4, -1),
            inverted.axes[app.gamenative.gamepad.GamepadAxis.RIGHT_TRIGGER],
        )

        // PlayStation Classic: dpad no stick (dpup:-a1, dpdown:+a1) — botão via meia-eixo.
        val classic = MappingParser.parse(
            "030000004c050000da0c000000000000,Sony PlayStation Classic Controller," +
                "a:b2,b:b1,dpdown:+a1,dpleft:-a0,dpright:+a0,dpup:-a1," +
                "leftshoulder:b6,lefttrigger:b4,rightshoulder:b7,righttrigger:b5," +
                "start:b9,x:b3,y:b0,platform:Windows,",
        )!!
        assertEquals(RawBinding.Axis(1, -1), classic.buttons[GamepadButton.DPAD_UP])
        assertEquals(RawBinding.Axis(1, +1), classic.buttons[GamepadButton.DPAD_DOWN])
        assertEquals(RawBinding.Axis(0, -1), classic.buttons[GamepadButton.DPAD_LEFT])
        assertEquals(RawBinding.Axis(0, +1), classic.buttons[GamepadButton.DPAD_RIGHT])
    }

    @Test
    fun `trigger as button goes to the buttons map`() {
        val mapping = MappingParser.parse(
            "03000000d62000002a79000000000000,BDA PS4 Fightpad," +
                "a:b1,b:b2,lefttrigger:b6,righttrigger:b7,leftx:a0,lefty:a1," +
                "rightx:a2,righty:a5,platform:Windows,",
        )!!
        // b6 = 188+6 = 194 (BUTTON_7), b7 = 195 (BUTTON_8)
        assertEquals(
            RawBinding.Key(194),
            mapping.buttons[GamepadButton.LEFT_TRIGGER],
        )
        assertEquals(
            RawBinding.Key(195),
            mapping.buttons[GamepadButton.RIGHT_TRIGGER],
        )
        assertTrue(mapping.axes.isEmpty() || mapping.axes[app.gamenative.gamepad.GamepadAxis.LEFT_TRIGGER] == null)
    }

    @Test
    fun `unknown fields are tolerated`() {
        val mapping = MappingParser.parse(
            "030000004c050000c405000000000000,PS4 Controller," +
                "a:b0,b:b1,leftx:a0,lefty:a1,hint:SDL_GAMECONTROLLER_USE_GAMECUBE_LABELS:=1," +
                "platform:Linux,",
        )!!
        assertTrue(mapping.buttons.isNotEmpty())
        assertTrue(mapping.axes.isNotEmpty())
        // touchpad:/misc1:/paddle*: não existem no modelo → ignorados.
        val tolerant = MappingParser.parse(
            "030000004c050000c405000000000000,PS4 Controller," +
                "a:b0,touchpad:b13,misc1:b14,paddle1:b16,leftx:a0,platform:Windows,",
        )!!
        assertEquals(1, tolerant.buttons.size)
    }

    @Test
    fun `invalid lines return null`() {
        assertNull(MappingParser.parse("invalid!@#$"))
        assertNull(MappingParser.parse(""))
        assertNull(MappingParser.parse("GUID,nome")) // sem bindings
        assertNull(MappingParser.parse("GUID,nome,platform:Windows,")) // só platform
    }

    @Test
    fun `button index beyond b15 is out of the android generic space and ignored`() {
        val mapping = MappingParser.parse(
            "030000004c050000c405000000000000,PS4 Controller,a:b16,leftx:a0,platform:Windows,",
        )!!
        assertNull(mapping.buttons[GamepadButton.FACE_BOTTOM])
        assertTrue(mapping.axes.isNotEmpty())
    }
}
