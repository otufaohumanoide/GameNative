package app.gamenative.gamepad

import app.gamenative.gamepad.mapping.GamepadMapping
import app.gamenative.gamepad.mapping.MappingDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Botão/tecla de CONFIRMAÇÃO por FaceStyle + swap (spec 2026-08-13-onda2 §1.6).
 */
class GamepadMappingTest {

    private fun mapping(faceStyle: FaceStyle): GamepadMapping =
        MappingDatabase.defaultAndroidMapping(faceStyle)

    @Test
    fun `xbox confirm is FACE_BOTTOM`() {
        assertEquals(GamepadButton.FACE_BOTTOM, mapping(FaceStyle.XBOX).confirmButton(swapOkCancel = false))
    }

    @Test
    fun `playstation confirm is FACE_BOTTOM`() {
        assertEquals(GamepadButton.FACE_BOTTOM, mapping(FaceStyle.PLAYSTATION).confirmButton(swapOkCancel = false))
    }

    @Test
    fun `nintendo confirm is FACE_RIGHT`() {
        assertEquals(GamepadButton.FACE_RIGHT, mapping(FaceStyle.NINTENDO).confirmButton(swapOkCancel = false))
    }

    @Test
    fun `swap inverts xbox confirm`() {
        assertEquals(GamepadButton.FACE_RIGHT, mapping(FaceStyle.XBOX).confirmButton(swapOkCancel = true))
    }

    @Test
    fun `swap inverts nintendo confirm`() {
        assertEquals(GamepadButton.FACE_BOTTOM, mapping(FaceStyle.NINTENDO).confirmButton(swapOkCancel = true))
    }

    @Test
    fun `xbox confirm keycode is BUTTON_A`() {
        assertEquals(96, mapping(FaceStyle.XBOX).confirmKeyCode(swapOkCancel = false))
    }

    @Test
    fun `nintendo confirm keycode is BUTTON_B`() {
        assertEquals(97, mapping(FaceStyle.NINTENDO).confirmKeyCode(swapOkCancel = false))
    }

    @Test
    fun `swap xbox confirm keycode is BUTTON_B`() {
        assertEquals(97, mapping(FaceStyle.XBOX).confirmKeyCode(swapOkCancel = true))
    }

    @Test
    fun `confirm keycode null when confirm has no key binding`() {
        val noKey = mapping(FaceStyle.GENERIC).copy(
            buttons = mapping(FaceStyle.GENERIC).buttons - GamepadButton.FACE_BOTTOM,
        )
        assertNull(noKey.confirmKeyCode(swapOkCancel = false))
    }
}
