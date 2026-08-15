package app.gamenative.gamepad.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deadzone (spec 2026-08-13, Parte I §6 + D5): RADIAL/AXIAL, rescalonamento 0..1 real
 * (a saída NÃO é mais o valor cru — os testes antigos congelaram o comportamento
 * errado), histerese como threshold documentado (`deadzone − hysteresis`).
 */
class DeadzoneProcessorTest {

    private fun config(
        deadzone: Float = 0.3f,
        mode: DeadzoneMode = DeadzoneMode.RADIAL,
        hysteresis: Float = 0.05f,
    ) = DeadzoneConfig(
        leftStick = deadzone,
        rightStick = deadzone,
        leftTrigger = deadzone,
        rightTrigger = deadzone,
        mode = mode,
        hysteresis = hysteresis,
    )

    @Test
    fun `radial inside deadzone returns inDeadzone true and zero output`() {
        val result = DeadzoneProcessor.process(StickSample(0.1f, 0.1f), config())
        assertTrue(result.inDeadzone)
        assertEquals(0f, result.x, 0.001f)
        assertEquals(0f, result.y, 0.001f)
    }

    @Test
    fun `radial outside deadzone rescales to 0-1 preserving direction`() {
        // mag = 0.7071; exit = 0.25; scale = (0.7071-0.25)/(0.7071*0.75) = 0.862
        val result = DeadzoneProcessor.process(StickSample(0.5f, 0.5f), config())
        assertFalse(result.inDeadzone)
        assertEquals(0.431f, result.x, 0.001f)
        assertEquals(0.431f, result.y, 0.001f)
    }

    @Test
    fun `radial rescale preserves the sign of each axis`() {
        val result = DeadzoneProcessor.process(StickSample(-0.5f, 0.5f), config())
        assertTrue(result.x < 0f)
        assertTrue(result.y > 0f)
        assertEquals(0.431f, -result.x, 0.001f)
        assertEquals(0.431f, result.y, 0.001f)
    }

    @Test
    fun `hysteresis band - output emerges at deadzone minus hysteresis`() {
        // 0.3 está ABAIXO da deadzone (0.3) mas ACIMA do threshold de saída (0.25).
        val result = DeadzoneProcessor.process(StickSample(0.3f, 0f), config())
        assertFalse(result.inDeadzone)
        assertEquals((0.3f - 0.25f) / 0.75f, result.x, 0.001f)
        assertEquals(0f, result.y, 0.001f)
    }

    @Test
    fun `axial inside deadzone returns inDeadzone true and zero output`() {
        val result = DeadzoneProcessor.process(StickSample(0.1f, 0.2f), config(mode = DeadzoneMode.AXIAL))
        assertTrue(result.inDeadzone)
        assertEquals(0f, result.x, 0.001f)
        assertEquals(0f, result.y, 0.001f)
    }

    @Test
    fun `axial outside deadzone rescales per axis`() {
        val result = DeadzoneProcessor.process(StickSample(0.5f, 0.3f), config(mode = DeadzoneMode.AXIAL))
        assertFalse(result.inDeadzone)
        assertEquals(0.3333f, result.x, 0.001f) // (0.5-0.25)/0.75
        assertEquals(0.0667f, result.y, 0.001f) // (0.3-0.25)/0.75
    }

    @Test
    fun `axial with one live axis is not inDeadzone`() {
        val result = DeadzoneProcessor.process(StickSample(0.5f, 0.05f), config(mode = DeadzoneMode.AXIAL))
        assertFalse(result.inDeadzone)
        assertEquals(0.3333f, result.x, 0.001f)
        assertEquals(0f, result.y, 0.001f)
    }

    @Test
    fun `processAxis below the release threshold is silent`() {
        // threshold = 0.5 - 0.05 = 0.45; 0.2 < 0.45 → 0
        assertEquals(0f, DeadzoneProcessor.processAxis(0.2f, 0.5f), 0.001f)
    }

    @Test
    fun `processAxis above deadzone scales to 0-1`() {
        val result = DeadzoneProcessor.processAxis(0.6f, 0.5f)
        assertTrue(result > 0f)
        assertTrue(result < 1f)
        assertEquals(0.2727f, result, 0.001f) // (0.6-0.45)/0.55
    }

    @Test
    fun `processAxis hysteresis band partial emergence`() {
        // 0.48 está entre 0.45 e 0.5 — já emerge (threshold documentado de saída).
        val result = DeadzoneProcessor.processAxis(0.48f, 0.5f)
        assertTrue(result > 0f)
        assertTrue(result < 1f)
    }

    @Test
    fun `processAxis triggers axial mode and stays in 0-1`() {
        val result = DeadzoneProcessor.processAxis(-0.3f, 0.5f)
        assertTrue(result >= 0f && result <= 1f)
        assertEquals(0f, result, 0.001f)
    }

    @Test
    fun `processAxis guards against deadzone plus hysteresis at 1`() {
        // Antiga divisão por zero quando dz + hyst >= 1 — agora clamped.
        val result = DeadzoneProcessor.processAxis(1f, 1f)
        assertTrue(result.isFinite())
        assertTrue(result in 0f..1f)
    }
}
