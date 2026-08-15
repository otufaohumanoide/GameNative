package app.gamenative.gamepad.processing

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * H (spec 2026-08-16-H-binding-modifiers-duckstation, §4): decisões PURAS do
 * modificador por binding — fórmula EXATA do FullAxis do DuckStation, ordem fixa
 * fullAxis → invert → scale → deadzone, clamp dos limites e identidade com null
 * (base da degradação byte-identical).
 */
class BindingModifiersTest {

    private val epsilon = 1e-6f

    @Test
    fun `mod null e identidade`() {
        assertEquals(0.7f, BindingModifiers.apply(0.7f, null), epsilon)
        assertEquals(-0.3f, BindingModifiers.apply(-0.3f, null), epsilon)
        assertEquals(0f, BindingModifiers.apply(0f, null), epsilon)
    }

    @Test
    fun `mod todos default e identidade`() {
        assertEquals(0.7f, BindingModifiers.apply(0.7f, BindingModifier()), epsilon)
        assertEquals(
            -0.3f,
            BindingModifiers.apply(-0.3f, BindingModifier(invert = false, scale = 1f, deadzone = 0f)),
            epsilon,
        )
    }

    @Test
    fun `fullAxis usa a formula exata do DuckStation`() {
        // input_manager.cpp: "value * 0.5 + 0.5" — eixo centrado −1..1 → 0..1.
        val mod = BindingModifier(fullAxis = true)
        assertEquals(0f, BindingModifiers.apply(-1f, mod), epsilon)
        assertEquals(0.25f, BindingModifiers.apply(-0.5f, mod), epsilon)
        assertEquals(0.5f, BindingModifiers.apply(0f, mod), epsilon)
        assertEquals(0.75f, BindingModifiers.apply(0.5f, mod), epsilon)
        assertEquals(1f, BindingModifiers.apply(1f, mod), epsilon)
    }

    @Test
    fun `invert troca o sinal`() {
        val mod = BindingModifier(invert = true)
        assertEquals(-0.8f, BindingModifiers.apply(0.8f, mod), epsilon)
        assertEquals(0.4f, BindingModifiers.apply(-0.4f, mod), epsilon)
        assertEquals(0f, BindingModifiers.apply(0f, mod), epsilon)
    }

    @Test
    fun `scale multiplica dentro dos limites`() {
        assertEquals(0.8f, BindingModifiers.apply(0.4f, BindingModifier(scale = 2f)), epsilon)
        assertEquals(0.4f, BindingModifiers.apply(0.8f, BindingModifier(scale = 0.5f)), epsilon)
        assertEquals(-0.6f, BindingModifiers.apply(-0.3f, BindingModifier(scale = 2f)), epsilon)
    }

    @Test
    fun `deadzone zera abaixo do limiar sem rescale`() {
        val mod = BindingModifier(deadzone = 0.3f)
        assertEquals(0f, BindingModifiers.apply(0.29f, mod), epsilon)
        assertEquals(0f, BindingModifiers.apply(-0.29f, mod), epsilon)
        // |v| == dz passa (limiar estrito <) e SEM rescale — o rescale radial é do
        // DeadzoneProcessor; aqui o zero é o limiar do binding único (§2.1).
        assertEquals(0.3f, BindingModifiers.apply(0.3f, mod), epsilon)
        assertEquals(0.7f, BindingModifiers.apply(0.7f, mod), epsilon)
    }

    @Test
    fun `ordem fixa fullAxis invert scale deadzone`() {
        val mod = BindingModifier(fullAxis = true, invert = true, scale = 2f, deadzone = 0.5f)
        // v=1: full→1.0, inv→−1.0, scale→−2.0, |−2| ≥ 0.5 → −2.0.
        assertEquals(-2f, BindingModifiers.apply(1f, mod), epsilon)
        // v=0: full→0.5, inv→−0.5, scale→−1.0, |−1| ≥ 0.5 → −1.0.
        assertEquals(-1f, BindingModifiers.apply(0f, mod), epsilon)
        // v=−0.6: full→0.2, inv→−0.2, scale→−0.4, |−0.4| < 0.5 → 0.
        assertEquals(0f, BindingModifiers.apply(-0.6f, mod), epsilon)
        // v=−0.5: full→0.25, inv→−0.25, scale→−0.5, |−0.5| ≥ 0.5 → −0.5 (limiar estrito).
        assertEquals(-0.5f, BindingModifiers.apply(-0.5f, mod), epsilon)
    }

    @Test
    fun `scale e deadzone clampam nos limites do spec`() {
        // scale clamp 0.5..2.0: 9 → 2.0.
        assertEquals(1f, BindingModifiers.apply(0.5f, BindingModifier(scale = 9f)), epsilon)
        // scale clamp 0.5..2.0: 0.01 → 0.5.
        assertEquals(0.25f, BindingModifiers.apply(0.5f, BindingModifier(scale = 0.01f)), epsilon)
        // deadzone clamp 0.0..0.5: 0.9 → 0.5.
        assertEquals(0f, BindingModifiers.apply(0.2f, BindingModifier(deadzone = 0.9f)), epsilon)
        assertEquals(0.6f, BindingModifiers.apply(0.6f, BindingModifier(deadzone = 0.9f)), epsilon)
        // deadzone negativa → clamp 0 (sem efeito).
        assertEquals(0.1f, BindingModifiers.apply(0.1f, BindingModifier(deadzone = -0.4f)), epsilon)
    }
}
