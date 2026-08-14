package app.gamenative.gamepad.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** F1.1 (spec 2026-08-15-input-core-avancado): StickTransform — deadzone + curvas + LUT. */
class StickTransformTest {

    @Test
    fun `linear radial preserva direcao e rescala`() {
        val config = StickTransformConfig(deadzone = 0.2f, curve = ResponseCurve.LINEAR)
        val result = StickTransform.apply(StickSample(0.6f, 0.8f), config) // mag = 1.0
        assertTrue(!result.inDeadzone)
        // saída = entrada (deadzone 0.2 − 0.05 = 0.15 < 1.0; renormaliza para 1.0 → sem mudança)
        assertEquals(0.6f, result.x, 0.001f)
        assertEquals(0.8f, result.y, 0.001f)
    }

    @Test
    fun `dentro da deadzone zera`() {
        val result = StickTransform.apply(
            StickSample(0.05f, 0.02f),
            StickTransformConfig(deadzone = 0.15f),
        )
        assertTrue(result.inDeadzone)
        assertEquals(0f, result.x, 0f)
        assertEquals(0f, result.y, 0f)
    }

    @Test
    fun `modo axial trata eixos independentes`() {
        // x abaixo do limiar, y acima: axial mantém y e zera x (radial zeraria os dois).
        val result = StickTransform.apply(
            StickSample(0.05f, 0.8f),
            StickTransformConfig(deadzone = 0.15f, mode = DeadzoneMode.AXIAL, curve = ResponseCurve.LINEAR),
        )
        // limiar efetivo 0.15−0.05 = 0.10 → (0.8−0.1)/(1−0.1) = 0.7778
        assertEquals(0f, result.x, 0.001f)
        assertEquals(0.7778f, result.y, 0.001f)
    }

    @Test
    fun `exponential curva a magnitude preservando direcao`() {
        val config = StickTransformConfig(deadzone = 0f, hysteresis = 0f, curve = ResponseCurve.EXPONENTIAL)
        val result = StickTransform.apply(StickSample(0.5f, 0f), config)
        assertEquals(0.25f, result.x, 0.001f) // 0.5²
        assertEquals(0f, result.y, 0f)
    }

    @Test
    fun `scurve smoothstep nas pontas e no meio`() {
        val config = StickTransformConfig(deadzone = 0f, hysteresis = 0f, curve = ResponseCurve.SCURVE)
        assertEquals(0f, StickTransform.curve(0f, config), 0.001f)
        assertEquals(1f, StickTransform.curve(1f, config), 0.001f)
        assertEquals(0.5f, StickTransform.curve(0.5f, config), 0.001f) // 3·0.25−2·0.125
    }

    @Test
    fun `lut interpola linearmente entre pontos`() {
        val lut = listOf(0f, 0.25f, 1f) // 3 pontos: meio = 0.25, fim = 1
        assertEquals(0.25f, StickTransform.lutValue(0.5f, lut), 0.001f)
        assertEquals(0f, StickTransform.lutValue(0f, lut), 0.001f)
        assertEquals(1f, StickTransform.lutValue(1f, lut), 0.001f)
        // entre o 1º e o 2º ponto: t=0.25 → pos=0.5 → 0 + (0.25−0)·0.5
        assertEquals(0.125f, StickTransform.lutValue(0.25f, lut), 0.001f)
    }

    @Test
    fun `lut vazia degrada para linear`() {
        val config = StickTransformConfig(curve = ResponseCurve.LUT, lut = emptyList())
        assertEquals(0.7f, StickTransform.curve(0.7f, config), 0.001f)
    }

    @Test
    fun `lut sanitizada clamp e descarta nao-finitos`() {
        val dirty = listOf(-1f, 0.5f, 2f, Float.NaN, Float.POSITIVE_INFINITY)
        val clean = StickTransform.sanitizeLut(dirty)
        assertEquals(listOf(0f, 0.5f, 1f), clean)
        // menos de 2 pontos úteis → vazia
        assertEquals(emptyList<Float>(), StickTransform.sanitizeLut(listOf(Float.NaN, 1f)))
    }

    @Test
    fun `dominio fora da faixa clampa`() {
        val config = StickTransformConfig(curve = ResponseCurve.EXPONENTIAL)
        assertEquals(1f, StickTransform.curve(2f, config), 0.001f)
        assertEquals(0f, StickTransform.curve(-1f, config), 0.001f)
    }

    @Test
    fun `axial com curva preserva sinal por eixo`() {
        val config = StickTransformConfig(
            deadzone = 0f, hysteresis = 0f,
            mode = DeadzoneMode.AXIAL, curve = ResponseCurve.EXPONENTIAL,
        )
        val result = StickTransform.apply(StickSample(-0.5f, 0.5f), config)
        assertEquals(-0.25f, result.x, 0.001f)
        assertEquals(0.25f, result.y, 0.001f)
    }
}
