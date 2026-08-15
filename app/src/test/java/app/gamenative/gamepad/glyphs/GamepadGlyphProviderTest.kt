package app.gamenative.gamepad.glyphs

import app.gamenative.gamepad.FaceStyle
import app.gamenative.gamepad.GamepadButton
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Glyph provider (spec 2026-08-13, Passo 7 — D8): TODO botão × TODO face style tem
 * label de resource (chaves criadas no strings.xml EN + pt-rBR).
 */
class GamepadGlyphProviderTest {

    @Test
    fun `every button has a resource label for every face style`() {
        for (style in FaceStyle.entries) {
            for (button in GamepadButton.entries) {
                val res = GamepadGlyphProvider.labelRes(button, style)
                assertTrue("$style/$button deve ter label", res != 0)
            }
        }
    }

    @Test
    fun `generic unknown buttons share the generic label`() {
        assertTrue(
            GamepadGlyphProvider.labelRes(GamepadButton.GUIDE, FaceStyle.GENERIC) ==
                GamepadGlyphProvider.labelRes(GamepadButton.LEFT_BUMPER, FaceStyle.GENERIC),
        )
    }
}
