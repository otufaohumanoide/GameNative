package app.gamenative.gamepad.processing

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P1-2 (spec 2026-08-14-gamepad-upgrades-pendencias): mapeamento velocidade→deflexão
 * do CAMERA mode — clamp, sinal, retorno a zero e sensibilidade.
 */
class GyroStickMappingTest {

    @Test
    fun `zero velocity maps to zero deflection`() {
        assertEquals(0f, GyroStickMapping.deflection(0f, 1f), 0.0001f)
    }

    @Test
    fun `sign is preserved`() {
        assert(GyroStickMapping.deflection(2f, 1f) > 0f)
        assert(GyroStickMapping.deflection(-2f, 1f) < 0f)
    }

    @Test
    fun `deflection scales linearly with velocity`() {
        val slow = GyroStickMapping.deflection(1f, 1f)
        val fast = GyroStickMapping.deflection(2f, 1f)
        assertEquals(fast, slow * 2f, 0.0001f)
    }

    @Test
    fun `deflection clamps at plus and minus one`() {
        assertEquals(1f, GyroStickMapping.deflection(100f, 1f), 0.0001f)
        assertEquals(-1f, GyroStickMapping.deflection(-100f, 1f), 0.0001f)
        // ~286°/s (5 rad/s) = 1.0 com sensibilidade 1.0 (default do mapeamento).
        assertEquals(1f, GyroStickMapping.deflection(5f, 1f), 0.0001f)
    }

    @Test
    fun `sensitivity scales the deflection`() {
        val base = GyroStickMapping.deflection(1f, 1f)
        assertEquals(base * 2f, GyroStickMapping.deflection(1f, 2f), 0.0001f)
    }

    @Test
    fun `stopping rotation returns to zero`() {
        // P1-2: o defeito antigo INTEGRAVA deltas — o stick permanecia no último
        // valor quando o gyro parava. Taxa ⇒ velocidade 0 ⇒ deflexão 0.
        assertEquals(0f, GyroStickMapping.deflection(0f, 1f), 0.0001f)
        assertEquals(0f, GyroStickMapping.deflection(0f, 3f), 0.0001f)
    }
}
