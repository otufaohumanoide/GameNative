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
}
