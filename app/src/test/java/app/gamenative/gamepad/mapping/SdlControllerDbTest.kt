package app.gamenative.gamepad.mapping

import app.gamenative.gamepad.FaceStyle
import app.gamenative.gamepad.GamepadAxis
import app.gamenative.gamepad.GamepadButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F1.4 (spec 2026-08-15-input-core-avancado): interpretação ANDROID do
 * gamecontrollerdb — GUID bus-style, enum SDL de botões, ordem de eixos do driver.
 * Fixtures = linhas reais do DB pinado.
 */
class SdlControllerDbTest {

    // Linha real do DB (platform:Android): DS4 no formato bus-style do backend 2.0.16.
    private val ds4Line =
        "050000004c050000cc090000fffe3f00,PS4 Controller,a:b0,b:b1,back:b4,dpdown:b12," +
            "dpleft:b13,dpright:b14,dpup:b11,guide:b5,leftshoulder:b9,leftstick:b7," +
            "lefttrigger:a4,leftx:a0,lefty:a1,rightshoulder:b10,rightstick:b8," +
            "righttrigger:a5,rightx:a2,righty:a3,start:b6,x:b2,y:b3,platform:Android,"

    @Test
    fun `guid bus-style extrai vendor e product`() {
        assertEquals("054c09cc", SdlControllerDb.mappingKeyFromGuid("050000004c050000cc090000fffe3f00"))
        assertEquals("045e028e", SdlControllerDb.mappingKeyFromGuid("030000005e0400008e02000000007265"))
    }

    @Test
    fun `guid legado hex-do-nome e rejeitado`() {
        assertEquals("", SdlControllerDb.mappingKeyFromGuid("38653964633230666463343334313533"))
        assertEquals("", SdlControllerDb.mappingKeyFromGuid("curto"))
    }

    @Test
    fun `linha DS4 vira layout Android normalizado`() {
        val mapping = SdlControllerDb.parseLine(ds4Line) ?: error("parse falhou")
        assertEquals("054c09cc", mapping.mappingKey)
        assertEquals(FaceStyle.PLAYSTATION, mapping.faceStyle)
        // b0 → BUTTON_A (enum SDL), não BUTTON_1
        assertEquals(RawBinding.Key(AndroidConstants.BUTTON_A), mapping.buttons[GamepadButton.FACE_BOTTOM])
        assertEquals(RawBinding.Key(AndroidConstants.BUTTON_B), mapping.buttons[GamepadButton.FACE_RIGHT])
        assertEquals(RawBinding.Key(AndroidConstants.BUTTON_MODE), mapping.buttons[GamepadButton.GUIDE])
        assertEquals(RawBinding.Key(AndroidConstants.DPAD_UP), mapping.buttons[GamepadButton.DPAD_UP])
        // eixos: a0..a5 = X/Y/Z/RZ/LTRIGGER/RTRIGGER
        assertEquals(RawBinding.Axis(AndroidConstants.AXIS_X, 1), mapping.axes[GamepadAxis.LEFT_X])
        assertEquals(RawBinding.Axis(AndroidConstants.AXIS_RZ, 1), mapping.axes[GamepadAxis.RIGHT_Y])
        assertEquals(RawBinding.Axis(AndroidConstants.AXIS_LTRIGGER, 1), mapping.axes[GamepadAxis.LEFT_TRIGGER])
    }

    @Test
    fun `parse do arquivo completo monta o mapa sem excecao`() {
        val text = javaClass.classLoader.getResource("gamecontrollerdb.txt")?.readText()
        if (text == null) return // asset fora do classpath do teste unit — fixture cobre o resto
        val map = SdlControllerDb.parse(text)
        assertTrue("mapa vazio", map.isNotEmpty())
        assertTrue("DS4 deveria estar no mapa", map.containsKey("054c09cc"))
    }

    @Test
    fun `trigger como botao vai para o mapa de botoes`() {
        // b15 = BUTTON_L2 (enum SDL) — pad sem eixo analógico de trigger
        val line = "050000004c050000cc090000ffff0f00,Fake Pad,a:b0,lefttrigger:b15,leftx:a0,lefty:a1,platform:Android,"
        val mapping = SdlControllerDb.parseLine(line) ?: error("parse falhou")
        assertEquals(RawBinding.Key(AndroidConstants.BUTTON_L2), mapping.buttons[GamepadButton.LEFT_TRIGGER])
        assertNull(mapping.axes[GamepadAxis.LEFT_TRIGGER])
    }

    @Test
    fun `eixo invertido vira direcao negativa`() {
        val line = "050000004c050000cc090000ffff0f00,Fake Pad,a:b0,leftx:a0,lefty:a1~,platform:Android,"
        val mapping = SdlControllerDb.parseLine(line) ?: error("parse falhou")
        assertEquals(RawBinding.Axis(AndroidConstants.AXIS_Y, -1), mapping.axes[GamepadAxis.LEFT_Y])
    }

    @Test
    fun `dpad em hat usa a mascara SDL`() {
        val line = "050000004c050000cc090000ffff0f00,Fake Pad,a:b0,dpup:h0.1,dpdown:h0.4,dpleft:h0.8,dpright:h0.2,platform:Android,"
        val mapping = SdlControllerDb.parseLine(line) ?: error("parse falhou")
        assertEquals(RawBinding.Hat(0, MappingParser.HAT_UP), mapping.buttons[GamepadButton.DPAD_UP])
        assertEquals(RawBinding.Hat(0, MappingParser.HAT_DOWN), mapping.buttons[GamepadButton.DPAD_DOWN])
        assertEquals(RawBinding.Hat(0, MappingParser.HAT_LEFT), mapping.buttons[GamepadButton.DPAD_LEFT])
        assertEquals(RawBinding.Hat(0, MappingParser.HAT_RIGHT), mapping.buttons[GamepadButton.DPAD_RIGHT])
    }

    @Test
    fun `botoes genericos BUTTON_1 a 16 mapeiam b20 a b35`() {
        val line = "050000004c050000cc090000ffff0f00,Fake Pad,a:b20,b:b21,start:b22,platform:Android,"
        val mapping = SdlControllerDb.parseLine(line) ?: error("parse falhou")
        assertEquals(RawBinding.Key(AndroidConstants.BUTTON_1), mapping.buttons[GamepadButton.FACE_BOTTOM])
        assertEquals(RawBinding.Key(AndroidConstants.BUTTON_1 + 1), mapping.buttons[GamepadButton.FACE_RIGHT])
        assertEquals(RawBinding.Key(AndroidConstants.BUTTON_1 + 2), mapping.buttons[GamepadButton.START])
    }

    @Test
    fun `linha malformada degrada a null`() {
        assertNull(SdlControllerDb.parseLine(""))
        assertNull(SdlControllerDb.parseLine("# comentario"))
        assertNull(SdlControllerDb.parseLine("guid,nome"))
        // GUID legado (sem vid/pid) → null (ignorado pelo parse)
        assertNull(SdlControllerDb.parseLine("38653964633230666463343334313533,Nome,a:b0,platform:Android,"))
        // binding sem campo reconhecido
        assertNull(SdlControllerDb.parseLine("050000004c050000cc090000ffff0f00,Nome,totalmente:desconhecido:1,platform:Android,"))
    }

    @Test
    fun `faceStyle inferido do vendor`() {
        assertEquals(FaceStyle.PLAYSTATION, SdlControllerDb.faceStyleForVendor("054c09cc"))
        assertEquals(FaceStyle.XBOX, SdlControllerDb.faceStyleForVendor("045e028e"))
        assertEquals(FaceStyle.NINTENDO, SdlControllerDb.faceStyleForVendor("057e2009"))
        assertEquals(FaceStyle.GENERIC, SdlControllerDb.faceStyleForVendor("2dc89002"))
    }

    // ── K3 (spec 2026-08-16-K3, §1.3): hint de rótulos ──

    @Test
    fun `hint USE_BUTTON_LABELS vira NINTENDO para vendor nao identificavel`() {
        val line = "05000000010200000304000000000000,Generic Labeled,a:b0,b:b1," +
            "leftx:a0,lefty:a1,hint:SDL_GAMECONTROLLER_USE_BUTTON_LABELS:=1,platform:Android,"
        val mapping = SdlControllerDb.parseLine(line) ?: error("parse falhou")
        assertEquals(FaceStyle.NINTENDO, mapping.faceStyle)
    }

    @Test
    fun `hint perde para Sony e MS inequivocos`() {
        val sony = "050000004c050000cc090000fffe3f00,PS4 Labeled,a:b0,b:b1," +
            "leftx:a0,lefty:a1,hint:SDL_GAMECONTROLLER_USE_BUTTON_LABELS:=1,platform:Android,"
        assertEquals(FaceStyle.PLAYSTATION, SdlControllerDb.parseLine(sony)!!.faceStyle)

        val ms = "030000005e0400008e02000000007265,Xbox Hinted,a:b0,b:b1," +
            "leftx:a0,lefty:a1,hint:SDL_GAMECONTROLLER_USE_BUTTON_LABELS:=1,platform:Android,"
        assertEquals(FaceStyle.XBOX, SdlControllerDb.parseLine(ms)!!.faceStyle)
    }

    @Test
    fun `hint negado nao muda o estilo`() {
        val line = "05000000010200000304000000000000,Generic Positional,a:b0,b:b1," +
            "leftx:a0,lefty:a1,hint:!SDL_GAMECONTROLLER_USE_BUTTON_LABELS:=1,platform:Android,"
        val mapping = SdlControllerDb.parseLine(line) ?: error("parse falhou")
        assertEquals(FaceStyle.GENERIC, mapping.faceStyle)
    }

    @Test
    fun `faceStyleForVendor com hint explicito`() {
        assertEquals(FaceStyle.NINTENDO, SdlControllerDb.faceStyleForVendor("02010403", usesButtonLabels = true))
        assertEquals(FaceStyle.GENERIC, SdlControllerDb.faceStyleForVendor("02010403"))
        // O hint VENCE para vendor não-identificável (8BitDo/2dc8 não é Sony/MS).
        assertEquals(FaceStyle.NINTENDO, SdlControllerDb.faceStyleForVendor("2dc89002", usesButtonLabels = true))
        assertEquals(FaceStyle.PLAYSTATION, SdlControllerDb.faceStyleForVendor("054c09cc", usesButtonLabels = true))
    }

    // ── K3 (spec 2026-08-16-K3, §1.4): botões extras do DB ──

    @Test
    fun `misc1 paddle e touchpad viram botoes extras`() {
        // bN = enum SDL do backend Android: b7=THUMBL, b17=C, b18=Z,
        // b31 = BUTTON_1+11 (touchpad click de algumas entries).
        val line = "050000007e0500000920000000000000,Switch Pro Test,a:b1,b:b0," +
            "guide:b5,misc1:b7,leftx:a0,lefty:a1,paddle1:b17,paddle2:b18,touchpad:b31,platform:Android,"
        val mapping = SdlControllerDb.parseLine(line) ?: error("parse falhou")
        assertEquals(RawBinding.Key(AndroidConstants.BUTTON_THUMBL), mapping.buttons[GamepadButton.MISC1])
        assertEquals(RawBinding.Key(AndroidConstants.BUTTON_C), mapping.buttons[GamepadButton.PADDLE_1])
        assertEquals(RawBinding.Key(AndroidConstants.BUTTON_Z), mapping.buttons[GamepadButton.PADDLE_2])
        assertEquals(RawBinding.Key(AndroidConstants.BUTTON_1 + 11), mapping.buttons[GamepadButton.TOUCHPAD])
        // Semântica padrão intacta na mesma linha.
        assertEquals(RawBinding.Key(AndroidConstants.BUTTON_B), mapping.buttons[GamepadButton.FACE_BOTTOM])
    }

    @Test
    fun `paddles 3 e 4 tambem parseiam`() {
        val line = "050000007e0500000920000000000000,Elite Test,a:b0," +
            "paddle1:b20,paddle2:b21,paddle3:b22,paddle4:b23,leftx:a0,platform:Android,"
        val mapping = SdlControllerDb.parseLine(line) ?: error("parse falhou")
        assertEquals(RawBinding.Key(AndroidConstants.BUTTON_1), mapping.buttons[GamepadButton.PADDLE_1])
        assertEquals(RawBinding.Key(AndroidConstants.BUTTON_1 + 1), mapping.buttons[GamepadButton.PADDLE_2])
        assertEquals(RawBinding.Key(AndroidConstants.BUTTON_1 + 2), mapping.buttons[GamepadButton.PADDLE_3])
        assertEquals(RawBinding.Key(AndroidConstants.BUTTON_1 + 3), mapping.buttons[GamepadButton.PADDLE_4])
    }
}
