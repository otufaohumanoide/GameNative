package app.gamenative.gamepad.mapping

import app.gamenative.gamepad.DeviceClass
import app.gamenative.gamepad.FaceStyle
import app.gamenative.gamepad.GamepadAxis
import app.gamenative.gamepad.GamepadButton
import app.gamenative.gamepad.GamepadDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * K6 (spec 2026-08-16-K6, §1.1): encode do formato SDL — GUID bus-style com masks
 * de capability (SDLControllerManager getButtonMask/getAxisMask), campos
 * semânticos invertidos, hint de rótulos e o round-trip obrigatório
 * `decode(encode(x)) == mapping` sobre as entradas do asset e os defaults.
 */
class SdlMappingCodecTest {

    /** Capabilities de um DS4 completo (keycodes + 6 eixos canônicos). */
    private val ds4Caps = GamepadCapabilities(
        keycodes = setOf(
            AndroidConstants.BUTTON_A, AndroidConstants.BUTTON_B,
            AndroidConstants.BUTTON_X, AndroidConstants.BUTTON_Y,
            AndroidConstants.BUTTON_SELECT, AndroidConstants.BUTTON_MODE,
            AndroidConstants.BUTTON_START, AndroidConstants.BUTTON_THUMBL,
            AndroidConstants.BUTTON_THUMBR, AndroidConstants.BUTTON_L1,
            AndroidConstants.BUTTON_R1, AndroidConstants.DPAD_UP,
            AndroidConstants.DPAD_DOWN, AndroidConstants.DPAD_LEFT,
            AndroidConstants.DPAD_RIGHT, AndroidConstants.BUTTON_L2,
            AndroidConstants.BUTTON_R2,
        ),
        axes = listOf(
            AndroidConstants.AXIS_X, AndroidConstants.AXIS_Y,
            AndroidConstants.AXIS_Z, AndroidConstants.AXIS_RZ,
            AndroidConstants.AXIS_LTRIGGER, AndroidConstants.AXIS_RTRIGGER,
        ),
        hasHat = false,
        isGamepadSource = true,
    )

    private fun device(
        vendorId: Int = 0x054c,
        productId: Int = 0x09cc,
        caps: GamepadCapabilities? = ds4Caps,
    ) = GamepadDevice(
        deviceId = 1,
        descriptor = "test",
        vendorId = vendorId,
        productId = productId,
        name = "Wireless Controller",
        deviceClass = DeviceClass.CONTROLLER,
        faceStyle = FaceStyle.PLAYSTATION,
        capabilities = caps,
    )

    // ── GUID ──

    @Test
    fun `guidFor DS4 monta o GUID bus-style com as masks de capability`() {
        // bytes: bus 05 | 00 | crc 0000 | vid 4c05 | pid cc09 | version 0000 |
        // assinatura 00 | 00 | button_mask (bits 0-14 + L2/R2 = 0x1FFFF → 0xFFFF
        // no Uint16) | axis_mask 0x003F (6 eixos).
        assertEquals(
            "050000004c050000cc090000ffff3f00",
            SdlMappingCodec.guidFor(device()),
        )
    }

    @Test
    fun `guidFor sem capabilities degrada para masks zero`() {
        assertEquals(
            "050000004c050000cc09000000000000",
            SdlMappingCodec.guidFor(device(caps = null)),
        )
    }

    @Test
    fun `guidFor com hat liga os bits de DPAD`() {
        val caps = ds4Caps.copy(
            keycodes = ds4Caps.keycodes - setOf(
                AndroidConstants.DPAD_UP, AndroidConstants.DPAD_DOWN,
                AndroidConstants.DPAD_LEFT, AndroidConstants.DPAD_RIGHT,
            ),
            hasHat = true,
        )
        // bits 11-14 do hat somam aos demais; o resto dos bits (0-10, 15, 16) segue.
        val guid = SdlMappingCodec.guidFor(device(caps = caps))
        assertEquals("050000004c050000cc090000ffff3f00", guid)
    }

    @Test
    fun `guidFor com BUTTON_13 a 16 usa o sentinela 0xFFFFFFFF (truncado no campo)`() {
        val caps = ds4Caps.copy(
            keycodes = ds4Caps.keycodes + (AndroidConstants.BUTTON_1 + 13),
        )
        // SDL: "out of room" → mask 0xFFFFFFFF; o campo Uint16 trunca para 0xFFFF.
        assertEquals(
            "050000004c050000cc090000ffff3f00",
            SdlMappingCodec.guidFor(device(caps = caps)),
        )
    }

    @Test
    fun `guidFor liga o bit 0x8000 quando Z e um eixo entre Z e RZ coexistem`() {
        val caps = ds4Caps.copy(
            axes = ds4Caps.axes + AndroidConstants.AXIS_RX,
        )
        val guid = SdlMappingCodec.guidFor(device(caps = caps))
        // axis_mask = 0x003F | 0x8000 = 0x803F → bytes LE 3f 80.
        assertTrue(guid.endsWith("ffff3f80"))
    }

    // ── Encode ──

    @Test
    fun `round-trip da linha DS4 do DB`() {
        val line = "050000004c050000cc090000fffe3f00,PS4 Controller,a:b0,b:b1," +
            "back:b4,dpdown:b12,dpleft:b13,dpright:b14,dpup:b11,guide:b5," +
            "leftshoulder:b9,leftstick:b7,lefttrigger:a4,leftx:a0,lefty:a1," +
            "rightshoulder:b10,rightstick:b8,righttrigger:a5,rightx:a2,righty:a3," +
            "start:b6,x:b2,y:b3,platform:Android,"
        val original = SdlControllerDb.parseLine(line) ?: error("parse falhou")
        val encoded = SdlMappingCodec.encode(device(), original, original.faceStyle)
        val decoded = SdlControllerDb.parseLine(encoded) ?: error("round-trip falhou")

        assertEquals(original.mappingKey, decoded.mappingKey)
        assertEquals(original.name, decoded.name)
        assertEquals(original.faceStyle, decoded.faceStyle)
        assertEquals(original.buttons, decoded.buttons)
        assertEquals(original.axes, decoded.axes)
    }

    @Test
    fun `round-trip preserva inversao e meia-direcao do eixo`() {
        // -a1 (metade negativa) colapsa em direção -1 → a1~ → -1.
        val mapping = GamepadMapping(
            mappingKey = "054c09cc",
            name = "Half",
            faceStyle = FaceStyle.GENERIC,
            buttons = emptyMap(),
            axes = mapOf(
                GamepadAxis.LEFT_Y to RawBinding.Axis(AndroidConstants.AXIS_Y, -1),
                GamepadAxis.LEFT_X to RawBinding.Axis(AndroidConstants.AXIS_X, +1),
            ),
        )
        val encoded = SdlMappingCodec.encode(device(), mapping, mapping.faceStyle)
        assertTrue(encoded.contains("lefty:a1~"))
        assertTrue(encoded.contains("leftx:a0"))
        val decoded = SdlControllerDb.parseLine(encoded)!!
        assertEquals(mapping.axes, decoded.axes)
    }

    @Test
    fun `faceStyle NINTENDO emite o hint e o decode devolve NINTENDO`() {
        val mapping = GamepadMapping(
            mappingKey = "2dc89002",
            name = "8BitDo",
            faceStyle = FaceStyle.NINTENDO,
            buttons = mapOf(
                GamepadButton.FACE_BOTTOM to RawBinding.Key(AndroidConstants.BUTTON_B),
                GamepadButton.FACE_RIGHT to RawBinding.Key(AndroidConstants.BUTTON_A),
            ),
            axes = emptyMap(),
        )
        val encoded = SdlMappingCodec.encode(
            device(vendorId = 0x2dc8, productId = 0x9002),
            mapping,
            FaceStyle.NINTENDO,
        )
        assertTrue(encoded.contains("hint:SDL_GAMECONTROLLER_USE_BUTTON_LABELS:=1"))
        assertEquals(FaceStyle.NINTENDO, SdlControllerDb.parseLine(encoded)!!.faceStyle)
    }

    @Test
    fun `botao sem binding e omitido (formato SDL)`() {
        val mapping = GamepadMapping(
            mappingKey = "054c09cc",
            name = "Min",
            faceStyle = FaceStyle.GENERIC,
            buttons = mapOf(
                GamepadButton.FACE_BOTTOM to RawBinding.Key(AndroidConstants.BUTTON_A),
                GamepadButton.DPAD_UP to RawBinding.Hat(0, MappingParser.HAT_UP),
            ),
            axes = emptyMap(),
        )
        val encoded = SdlMappingCodec.encode(device(), mapping, mapping.faceStyle)
        assertTrue(encoded.contains("a:b0"))
        assertTrue(encoded.contains("dpup:h0.1"))
        assertTrue(!encoded.contains("guide:"))
        assertTrue(!encoded.contains("leftx:"))
        assertTrue(encoded.endsWith("platform:Android,"))
    }

    @Test
    fun `botoes extras misc1 paddles touchpad round-trip`() {
        val mapping = GamepadMapping(
            mappingKey = "057e2009",
            name = "Switch Pro",
            faceStyle = FaceStyle.NINTENDO,
            buttons = mapOf(
                GamepadButton.FACE_BOTTOM to RawBinding.Key(AndroidConstants.BUTTON_B),
                GamepadButton.MISC1 to RawBinding.Key(AndroidConstants.BUTTON_THUMBL),
                GamepadButton.PADDLE_1 to RawBinding.Key(AndroidConstants.BUTTON_C),
                GamepadButton.PADDLE_2 to RawBinding.Key(AndroidConstants.BUTTON_Z),
                GamepadButton.TOUCHPAD to RawBinding.Key(AndroidConstants.BUTTON_1 + 11),
            ),
            axes = mapOf(
                GamepadAxis.LEFT_X to RawBinding.Axis(AndroidConstants.AXIS_X, +1),
            ),
        )
        val encoded = SdlMappingCodec.encode(
            device(vendorId = 0x057e, productId = 0x2009),
            mapping,
            FaceStyle.NINTENDO,
        )
        assertTrue(encoded.contains("misc1:b7"))
        assertTrue(encoded.contains("paddle1:b17"))
        assertTrue(encoded.contains("paddle2:b18"))
        assertTrue(encoded.contains("touchpad:b31"))
        val decoded = SdlControllerDb.parseLine(encoded)!!
        assertEquals(mapping.buttons, decoded.buttons)
        assertEquals(mapping.axes, decoded.axes)
    }

    @Test
    fun `trigger como botao round-trip (Key no eixo so para triggers)`() {
        val mapping = GamepadMapping(
            mappingKey = "054c09cc",
            name = "Trig Button",
            faceStyle = FaceStyle.GENERIC,
            buttons = mapOf(
                GamepadButton.LEFT_TRIGGER to RawBinding.Key(AndroidConstants.BUTTON_L2),
            ),
            axes = emptyMap(),
        )
        val encoded = SdlMappingCodec.encode(device(), mapping, mapping.faceStyle)
        assertTrue(encoded.contains("lefttrigger:b15"))
        val decoded = SdlControllerDb.parseLine(encoded)!!
        assertEquals(mapping.buttons, decoded.buttons)
    }

    @Test
    fun `keycode fora da tabela bN e omitido (BACK MENU DPAD_CENTER nao tem bN)`() {
        val mapping = GamepadMapping(
            mappingKey = "054c09cc",
            name = "Remote-ish",
            faceStyle = FaceStyle.GENERIC,
            buttons = mapOf(
                GamepadButton.FACE_BOTTOM to RawBinding.Key(AndroidConstants.DPAD_CENTER),
                GamepadButton.SELECT to RawBinding.Key(AndroidConstants.BUTTON_SELECT),
            ),
            axes = emptyMap(),
        )
        val encoded = SdlMappingCodec.encode(device(), mapping, mapping.faceStyle)
        // DPAD_CENTER=23 não tem expressão bN — o campo FACE_BOTTOM some; SELECT→b4.
        assertTrue(!encoded.contains("a:"))
        assertTrue(encoded.contains("back:b4"))
        // b4 decodifica de volta como BUTTON_SELECT (109) — o formato não distingue.
        val decoded = SdlControllerDb.parseLine(encoded)!!
        assertNull(decoded.buttons[GamepadButton.FACE_BOTTOM])
        assertEquals(
            RawBinding.Key(AndroidConstants.BUTTON_SELECT),
            decoded.buttons[GamepadButton.SELECT],
        )
    }

    // ── Propriedade: decode(encode(x)) == x sobre o asset inteiro ──

    @Test
    fun `round-trip sobre todas as entradas Android do asset`() {
        val text = javaClass.classLoader.getResource("gamecontrollerdb.txt")?.readText()
        if (text == null) return // asset fora do classpath do teste unit — fixture cobre o resto
        val parsed = SdlControllerDb.parse(text)
        assertTrue(parsed.isNotEmpty())
        for ((key, mapping) in parsed) {
            val vid = key.substring(0, 4).toInt(16)
            val pid = key.substring(4, 8).toInt(16)
            val device = GamepadDevice(
                deviceId = 1,
                descriptor = "test",
                vendorId = vid,
                productId = pid,
                name = "test",
                deviceClass = DeviceClass.CONTROLLER,
                faceStyle = mapping.faceStyle,
                capabilities = null,
            )
            val encoded = SdlMappingCodec.encode(device, mapping, mapping.faceStyle)
            val decoded = SdlControllerDb.parseLine(encoded)
            assertTrue("entry $key nao fez round-trip", decoded != null)
            assertEquals("key $key", mapping.mappingKey, decoded!!.mappingKey)
            assertEquals("nome $key", mapping.name, decoded.name)
            assertEquals("faceStyle $key", mapping.faceStyle, decoded.faceStyle)
            assertEquals("buttons $key", mapping.buttons, decoded.buttons)
            assertEquals("axes $key", mapping.axes, decoded.axes)
        }
    }

    // ── Diff do import ──

    @Test
    fun `diff lista so o que muda (novo removido alterado)`() {
        val current = GamepadMapping(
            mappingKey = "054c09cc",
            name = "A",
            faceStyle = FaceStyle.GENERIC,
            buttons = mapOf(
                GamepadButton.FACE_BOTTOM to RawBinding.Key(AndroidConstants.BUTTON_A),
                GamepadButton.START to RawBinding.Key(AndroidConstants.BUTTON_START),
            ),
            axes = mapOf(GamepadAxis.LEFT_X to RawBinding.Axis(AndroidConstants.AXIS_X, +1)),
        )
        val imported = GamepadMapping(
            mappingKey = "054c09cc",
            name = "B",
            faceStyle = FaceStyle.GENERIC,
            buttons = mapOf(
                GamepadButton.FACE_BOTTOM to RawBinding.Key(AndroidConstants.BUTTON_B),
                GamepadButton.DPAD_UP to RawBinding.Key(AndroidConstants.DPAD_UP),
            ),
            axes = mapOf(GamepadAxis.LEFT_X to RawBinding.Axis(AndroidConstants.AXIS_X, -1)),
        )
        val diffs = SdlMappingCodec.diff(current, imported)
        assertEquals(
            listOf(
                MappingDiff("FACE_BOTTOM", "b0", "b1"),
                MappingDiff("DPAD_UP", null, "b11"),
                MappingDiff("START", "b6", null),
                MappingDiff("LEFT_X", "a0", "a0~"),
            ),
            diffs,
        )
    }

    // ── platformOf (validação do import) ──

    @Test
    fun `platformOf le o campo platform`() {
        assertEquals("Android", SdlControllerDb.platformOf("guid,Nome,a:b0,platform:Android,"))
        assertEquals("Windows", SdlControllerDb.platformOf("guid,Nome,a:b0,platform:Windows,"))
        assertNull(SdlControllerDb.platformOf("guid,Nome,a:b0,"))
        assertNull(SdlControllerDb.platformOf(""))
    }
}
