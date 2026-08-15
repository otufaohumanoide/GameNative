package app.gamenative.gamepad

import app.gamenative.gamepad.mapping.GamepadMapping
import app.gamenative.gamepad.mapping.MappingDatabase
import app.gamenative.ui.component.GamepadStickDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bridge view-level dos diálogos (spec 2026-08-13-onda2 §1.5): decisões puras de
 * confirmação e direção de stick para janelas separadas (sem bus).
 */
class GamepadViewBridgeTest {

    private val xboxMapping: GamepadMapping = MappingDatabase.defaultAndroidMapping(FaceStyle.XBOX)

    // ── isConfirmKey ──

    @Test
    fun `confirm key matches for xbox face bottom`() {
        assertTrue(GamepadViewBridge.isConfirmKey(96, xboxMapping, swapOkCancel = false))
    }

    @Test
    fun `non-confirm key does not match`() {
        assertFalse(GamepadViewBridge.isConfirmKey(97, xboxMapping, swapOkCancel = false))
    }

    @Test
    fun `swap moves confirm to BUTTON_B`() {
        assertTrue(GamepadViewBridge.isConfirmKey(97, xboxMapping, swapOkCancel = true))
        assertFalse(GamepadViewBridge.isConfirmKey(96, xboxMapping, swapOkCancel = true))
    }

    @Test
    fun `nintendo natural confirm is BUTTON_B`() {
        val nintendo = MappingDatabase.defaultAndroidMapping(FaceStyle.NINTENDO)
        assertTrue(GamepadViewBridge.isConfirmKey(97, nintendo, swapOkCancel = false))
    }

    // ── stickDirection ──

    @Test
    fun `hat up wins over stick`() {
        assertEquals(
            GamepadStickDirection.Up,
            GamepadViewBridge.stickDirection(x = 0f, y = 0f, hatX = 0f, hatY = -1f, deadzone = 0.45f),
        )
    }

    @Test
    fun `hat right wins`() {
        assertEquals(
            GamepadStickDirection.Right,
            GamepadViewBridge.stickDirection(x = 0f, y = 0f, hatX = 1f, hatY = 0f, deadzone = 0.45f),
        )
    }

    @Test
    fun `stick inside deadzone is null`() {
        assertNull(
            GamepadViewBridge.stickDirection(x = 0.1f, y = 0.1f, hatX = 0f, hatY = 0f, deadzone = 0.45f),
        )
    }

    @Test
    fun `stick outside deadzone moves`() {
        assertEquals(
            GamepadStickDirection.Down,
            GamepadViewBridge.stickDirection(x = 0f, y = 0.6f, hatX = 0f, hatY = 0f, deadzone = 0.45f),
        )
    }

    @Test
    fun `stick left moves left`() {
        assertEquals(
            GamepadStickDirection.Left,
            GamepadViewBridge.stickDirection(x = -0.6f, y = 0f, hatX = 0f, hatY = 0f, deadzone = 0.45f),
        )
    }

    @Test
    fun `menu deadzone applies on raw values`() {
        // Valor cru 0.30 (drift zone): com deadzone de menu 0.45 não move.
        assertNull(
            GamepadViewBridge.stickDirection(x = 0.3f, y = 0f, hatX = 0f, hatY = 0f, deadzone = 0.45f),
        )
        // O mesmo valor cru com deadzone menor (perfil override 0.25) move.
        assertEquals(
            GamepadStickDirection.Right,
            GamepadViewBridge.stickDirection(x = 0.3f, y = 0f, hatX = 0f, hatY = 0f, deadzone = 0.25f),
        )
    }

    // directionFromResult e bindingForKey foram REMOVIDOS (spec 2026-08-14-onda2-pos-
    // implementacao, M4 — L5): sem consumidor de produção; o stickDirection compara
    // direto no cru e o remap resolve o binding por botão (bindingFor no diálogo).
}
