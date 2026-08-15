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

    // ── G4 (spec 2026-08-16-G-gyro-v2): shaping com maxOutput/antiDeadzone ──

    @Test
    fun `defaults are byte-identical to the legacy linear mapping`() {
        for (v in listOf(0.25f, 1f, 2f, 5f, -3f)) {
            val legacy = (v * GyroStickMapping.DEFAULT_SCALE).coerceIn(-1f, 1f)
            assertEquals(legacy, GyroStickMapping.deflection(v, 1f), 0f)
        }
    }

    @Test
    fun `antiDeadzone produces a minimum jump just above the deadzone`() {
        // Menor velocidade acima da deadzone já pula para o floor (semântica
        // SixMouseStick — a deadzone em si foi aplicada antes pelo GyroProcessor).
        assertEquals(0.2f, GyroStickMapping.deflection(0.001f, 1f, antiDeadzone = 0.2f), 0.001f)
        assertEquals(-0.2f, GyroStickMapping.deflection(-0.001f, 1f, antiDeadzone = 0.2f), 0.001f)
    }

    @Test
    fun `maxOutput caps the deflection at the configured ceiling`() {
        // Deflexão completa (5 rad/s × 0.2 = 1.0) satura em maxOutput.
        assertEquals(0.5f, GyroStickMapping.deflection(5f, 1f, maxOutput = 0.5f), 0.0001f)
        assertEquals(-0.5f, GyroStickMapping.deflection(-5f, 1f, maxOutput = 0.5f), 0.0001f)
    }

    @Test
    fun `curve points follow the affine remap above the deadzone`() {
        // (dz..1] → (anti..maxOut]: out = anti + (maxOut − anti)·|linear|.
        val maxOut = 0.8f
        val anti = 0.2f
        // Linear 0.5 (2.5 rad/s × 0.2): 0.2 + 0.6 × 0.5 = 0.5.
        assertEquals(0.5f, GyroStickMapping.deflection(2.5f, 1f, maxOutput = maxOut, antiDeadzone = anti), 0.0001f)
        // Linear 1.0: teto maxOut.
        assertEquals(0.8f, GyroStickMapping.deflection(5f, 1f, maxOutput = maxOut, antiDeadzone = anti), 0.0001f)
    }

    @Test
    fun `zero velocity stays zero with shaping`() {
        // O floor vale ACIMA da deadzone — repouso nunca gera deflexão.
        assertEquals(0f, GyroStickMapping.deflection(0f, 1f, maxOutput = 0.5f, antiDeadzone = 0.2f), 0.0001f)
    }
}
