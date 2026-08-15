package app.gamenative.gamepad.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 2026-08-16-A §1.3/§1.4: curva pura do rumble de TELEFONE (fórmula do
 * GameNative original — `getPhoneRumbleAmplitude`, WinHandler.java do commit
 * 2c184243^) e decisão pura do destino CONTROLLER | PHONE | NONE.
 */
class RumblePhoneCurveTest {

    @Test
    fun `zero or negative mix means silence`() {
        assertEquals(0, RumblePhoneCurve.amplitudeFor(0f))
        assertEquals(0, RumblePhoneCurve.amplitudeFor(-0.5f))
    }

    @Test
    fun `full mix maps to max amplitude`() {
        assertEquals(255, RumblePhoneCurve.amplitudeFor(1f))
    }

    @Test
    fun `curve matches the original pow 06 formula`() {
        // norm^0.6 * 255, arredondado (spec A §1.3), clamp e corte <= 1.
        assertEquals(168, RumblePhoneCurve.amplitudeFor(0.5f))   // 0.5^0.6*255 ≈ 168.2
        assertEquals(9, RumblePhoneCurve.amplitudeFor(0.004f))   // ≈ 9.3
        assertEquals(4, RumblePhoneCurve.amplitudeFor(0.001f))   // ≈ 4.0 (curva levanta)
    }

    @Test
    fun `the pow curve lifts small values unlike the linear sdl map`() {
        // O amplitudeFor linear (P2-5) zera mix < 1/255 ≈ 0.004; a curva pow 0.6 do
        // telefone mantém vibração audível para mix pequenos — o comportamento original.
        assertTrue(RumblePhoneCurve.amplitudeFor(0.001f) > 0)
        assertEquals(0, RumblePhoneCurve.amplitudeFor(0.00005f)) // abaixo do corte → 0
    }

    @Test
    fun `amplitude is monotonic across the sweep`() {
        var previous = 0
        for (i in 0..100) {
            val amp = RumblePhoneCurve.amplitudeFor(i / 100f)
            assertTrue("não-monotônico em $i: $amp < $previous", amp >= previous)
            previous = amp
        }
    }

    @Test
    fun `mix above one is clamped before the curve`() {
        assertEquals(RumblePhoneCurve.amplitudeFor(1f), RumblePhoneCurve.amplitudeFor(2f))
        assertEquals(255, RumblePhoneCurve.amplitudeFor(7f))
    }

    @Test
    fun `formula matches spec for the whole 1 to 255 range`() {
        // Equivalência com a fórmula do spec (round, corte <= 1, clamp 255).
        // Tolerância de 1: pow em float vs double pode cruzar uma borda de round.
        for (amp in 1..255) {
            val mix = amp / 255f
            val curved = Math.pow(mix.toDouble(), 0.6) * 255.0
            val expected = Math.round(curved).toInt()
                .let { if (it <= 1) 0 else it.coerceAtMost(255) }
            val actual = RumblePhoneCurve.amplitudeFor(mix)
            assertTrue("mix=$mix esperado=$expected atual=$actual", Math.abs(expected - actual) <= 1)
        }
    }

    @Test
    fun `target is controller whenever the device exposes a vibrator`() {
        assertEquals(
            RumblePhoneCurve.RumbleTarget.CONTROLLER,
            RumblePhoneCurve.rumbleTargetFor(hasDeviceVibrators = true, phoneFallbackEnabled = false),
        )
        assertEquals(
            RumblePhoneCurve.RumbleTarget.CONTROLLER,
            RumblePhoneCurve.rumbleTargetFor(hasDeviceVibrators = true, phoneFallbackEnabled = true),
        )
    }

    @Test
    fun `target falls back to phone only with the toggle on`() {
        assertEquals(
            RumblePhoneCurve.RumbleTarget.PHONE,
            RumblePhoneCurve.rumbleTargetFor(hasDeviceVibrators = false, phoneFallbackEnabled = true),
        )
        assertEquals(
            RumblePhoneCurve.RumbleTarget.NONE,
            RumblePhoneCurve.rumbleTargetFor(hasDeviceVibrators = false, phoneFallbackEnabled = false),
        )
    }
}
