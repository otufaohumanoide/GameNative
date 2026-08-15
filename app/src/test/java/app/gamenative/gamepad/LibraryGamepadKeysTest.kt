package app.gamenative.gamepad

import app.gamenative.gamepad.mapping.AndroidConstants
import app.gamenative.gamepad.mapping.GamepadMapping
import app.gamenative.gamepad.mapping.MappingDatabase
import app.gamenative.gamepad.mapping.RawBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * U6 (spec 2026-08-14-gamepad-u6-libraryscreen-ok-cancel, §1.1): o conjunto de
 * keycodes lógicos que a LibraryScreen ouve — FaceStyle + swap + mapping, com fallback
 * raw (byte-identical) para device desconhecido ou botão sem binding de tecla.
 */
class LibraryGamepadKeysTest {

    private fun mapping(faceStyle: FaceStyle): GamepadMapping =
        MappingDatabase.defaultAndroidMapping(faceStyle)

    @Test
    fun `xbox default - confirm A cancel B`() {
        val keys = LibraryGamepadKeys.resolve(mapping(FaceStyle.XBOX), swapOkCancel = false)
        assertEquals(AndroidConstants.BUTTON_A, keys.confirmKey)
        assertEquals(AndroidConstants.BUTTON_B, keys.cancelKey)
        assertEquals(AndroidConstants.BUTTON_Y, keys.yKey)
        assertEquals(AndroidConstants.BUTTON_X, keys.xKey)
        assertEquals(AndroidConstants.BUTTON_L1, keys.l1Key)
        assertEquals(AndroidConstants.BUTTON_R1, keys.r1Key)
        assertEquals(AndroidConstants.BUTTON_SELECT, keys.selectKey)
        assertEquals(AndroidConstants.BUTTON_START, keys.startKey)
    }

    @Test
    fun `playstation default - confirm A cancel B (mesmo layout posicional)`() {
        val keys = LibraryGamepadKeys.resolve(mapping(FaceStyle.PLAYSTATION), swapOkCancel = false)
        assertEquals(AndroidConstants.BUTTON_A, keys.confirmKey)
        assertEquals(AndroidConstants.BUTTON_B, keys.cancelKey)
    }

    @Test
    fun `nintendo - confirm B cancel A`() {
        val keys = LibraryGamepadKeys.resolve(mapping(FaceStyle.NINTENDO), swapOkCancel = false)
        assertEquals(AndroidConstants.BUTTON_B, keys.confirmKey)
        assertEquals(AndroidConstants.BUTTON_A, keys.cancelKey)
    }

    @Test
    fun `swap inverts confirm and cancel`() {
        val xbox = LibraryGamepadKeys.resolve(mapping(FaceStyle.XBOX), swapOkCancel = true)
        assertEquals(AndroidConstants.BUTTON_B, xbox.confirmKey)
        assertEquals(AndroidConstants.BUTTON_A, xbox.cancelKey)

        val nintendo = LibraryGamepadKeys.resolve(mapping(FaceStyle.NINTENDO), swapOkCancel = true)
        assertEquals(AndroidConstants.BUTTON_A, nintendo.confirmKey)
        assertEquals(AndroidConstants.BUTTON_B, nintendo.cancelKey)
    }

    @Test
    fun `no mapping - raw fallback`() {
        val keys = LibraryGamepadKeys.resolve(null, swapOkCancel = true)
        assertSame(LibraryKeySet.FALLBACK, keys)
        assertEquals(AndroidConstants.BUTTON_A, keys.confirmKey)
        assertEquals(AndroidConstants.BUTTON_B, keys.cancelKey)
    }

    @Test
    fun `button bound to axis - raw fallback for that button`() {
        // Confirm mapeado em EIXO (sem keycode): o fallback raw do confirm é A.
        val buttons = mapOf(
            GamepadButton.FACE_BOTTOM to RawBinding.Axis(AndroidConstants.AXIS_X, 1),
            GamepadButton.FACE_RIGHT to RawBinding.Key(AndroidConstants.BUTTON_B),
        )
        val mapping = GamepadMapping(
            mappingKey = "test",
            name = "test",
            faceStyle = FaceStyle.GENERIC,
            buttons = buttons,
            axes = emptyMap(),
        )
        val keys = LibraryGamepadKeys.resolve(mapping, swapOkCancel = false)
        assertEquals(AndroidConstants.BUTTON_A, keys.confirmKey)
        assertEquals(AndroidConstants.BUTTON_B, keys.cancelKey)
    }

    @Test
    fun `cancelButton is the other face button of confirm`() {
        val m = mapping(FaceStyle.XBOX)
        assertEquals(GamepadButton.FACE_RIGHT, m.cancelButton(swapOkCancel = false))
        assertEquals(GamepadButton.FACE_BOTTOM, m.cancelButton(swapOkCancel = true))
        val n = mapping(FaceStyle.NINTENDO)
        assertEquals(GamepadButton.FACE_BOTTOM, n.cancelButton(swapOkCancel = false))
        assertEquals(GamepadButton.FACE_RIGHT, n.cancelButton(swapOkCancel = true))
    }
}
