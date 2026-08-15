package app.gamenative.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P2-5 (spec 2026-08-14-gamepad-upgrades-pendencias): contrato de rumble — mix
 * 0.6/0.4 para device de 1 motor e amplitude 0..255 com cancel (padrão SDL).
 */
class GamepadHapticsTest {

    @Test
    fun `single motor mix is 06 low plus 04 high`() {
        assertEquals(0.6f, GamepadHaptics.mixIntensity(1f, 0f), 0.0001f)
        assertEquals(0.4f, GamepadHaptics.mixIntensity(0f, 1f), 0.0001f)
        assertEquals(0.5f, GamepadHaptics.mixIntensity(0.5f, 0.5f), 0.0001f)
    }

    @Test
    fun `mix is clamped between zero and one`() {
        assertEquals(1f, GamepadHaptics.mixIntensity(2f, 2f), 0.0001f)
        assertEquals(0f, GamepadHaptics.mixIntensity(-1f, -1f), 0.0001f)
    }

    @Test
    fun `amplitude maps intensity into one to two hundred fifty five`() {
        assertEquals(255, GamepadHaptics.amplitudeFor(1f))
        assertEquals(128, GamepadHaptics.amplitudeFor(0.5f))
        assertEquals(1, GamepadHaptics.amplitudeFor(0.004f))
    }

    @Test
    fun `round below one also means cancel`() {
        // SDL: value = round(intensity*255); value < 1 ⇒ vibrator.cancel().
        assertEquals(0, GamepadHaptics.amplitudeFor(0.001f))
    }

    @Test
    fun `zero intensity means cancel`() {
        // SDL: intensidade 0.0 ⇒ vibrator.cancel() — parar é parte do contrato.
        assertEquals(0, GamepadHaptics.amplitudeFor(0f))
        assertEquals(0, GamepadHaptics.amplitudeFor(-0.5f))
    }
}
