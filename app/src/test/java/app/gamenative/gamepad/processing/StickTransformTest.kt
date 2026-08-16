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

/** Limpeza 1.3-3 (doc pendentes): sanitização no LOAD do perfil, nunca por evento. */
class StickTransformLoadSanitizeTest {

    @org.junit.Test
    fun `withSanitizedLuts clamp e descarta lixo`() {
        val profile = app.gamenative.gamepad.profiles.GamepadProfile(
            leftStickLut = listOf(-1f, 0.5f, 2f, Float.NaN),
            rightStickLut = listOf(Float.NaN),
        )
        val clean = profile.withSanitizedLuts()
        org.junit.Assert.assertEquals(listOf(0f, 0.5f, 1f), clean.leftStickLut)
        // LUT inválida (vazia após limpeza) vira null — sem preferência
        org.junit.Assert.assertNull(clean.rightStickLut)
    }

    @org.junit.Test
    fun `withSanitizedLuts sem mudanca devolve a mesma instancia`() {
        val profile = app.gamenative.gamepad.profiles.GamepadProfile(
            leftStickLut = listOf(0f, 0.5f, 1f),
        )
        org.junit.Assert.assertSame(profile, profile.withSanitizedLuts())
    }
}

// ── K7 (spec 2026-08-16-K7, §1.1): anti-deadzone + maxOutput ──

class K7StickTransformTest {
    private fun config(
        deadzone: Float = 0.15f,
        anti: Float = 0f,
        maxOutput: Float = 1f,
        curve: ResponseCurve = ResponseCurve.LINEAR,
        mode: DeadzoneMode = DeadzoneMode.RADIAL,
    ) = StickTransformConfig(deadzone = deadzone, mode = mode, antiDeadzone = anti, maxOutput = maxOutput, curve = curve)

    @org.junit.Test
    fun `anti-deadzone 0 e maxOutput 1 sao identidade`() {
        val base = config()
        val plain = StickTransform.apply(StickSample(0.5f, 0.3f), base)
        val identity = StickTransform.apply(StickSample(0.5f, 0.3f), config(anti = 0f, maxOutput = 1f))
        org.junit.Assert.assertEquals(plain.x, identity.x, 1e-6f)
        org.junit.Assert.assertEquals(plain.y, identity.y, 1e-6f)
    }

    @org.junit.Test
    fun `anti-deadzone rescala a magnitude para comecar no anel`() {
        // Pós-deadzone mag=0.4 → anti 0.3 → boosted = 0.3 + 0.7*0.4 = 0.58.
        val r = StickTransform.apply(
            StickSample(0.4f, 0f),
            config(deadzone = 0f, anti = 0.3f),
        )
        org.junit.Assert.assertEquals(0.58f, r.x, 1e-4f)
    }

    @org.junit.Test
    fun `anti-deadzone nunca deixa o output entrar no limbo`() {
        // Pós-deadzone quase zero (mas fora) → output mínimo = anti.
        val r = StickTransform.apply(
            StickSample(0.01f, 0f),
            config(deadzone = 0f, anti = 0.25f),
        )
        org.junit.Assert.assertTrue(r.x >= 0.25f - 1e-4f)
    }

    @org.junit.Test
    fun `maxOutput clampa a saida final`() {
        // Borda cheia com teto 0.6 → 0.6.
        val r = StickTransform.apply(
            StickSample(1f, 0f),
            config(deadzone = 0f, maxOutput = 0.6f),
        )
        org.junit.Assert.assertEquals(0.6f, r.x, 1e-4f)
        // Dentro do teto não muda.
        val mid = StickTransform.apply(
            StickSample(0.3f, 0f),
            config(deadzone = 0f, maxOutput = 0.6f),
        )
        org.junit.Assert.assertEquals(0.3f, mid.x, 1e-4f)
    }

    @org.junit.Test
    fun `ordem anti depois da deadzone e antes do clamp`() {
        // deadzone 0.2 (mag 0.5 → pós-dz (0.5-0.2)/(1-0.2)=0.375) → anti 0.2 →
        // boosted = 0.2 + 0.8*0.375 = 0.5 → clamp 0.4 → 0.4.
        val r = StickTransform.apply(
            StickSample(0.5f, 0f),
            config(deadzone = 0.2f, anti = 0.2f, maxOutput = 0.4f),
        )
        org.junit.Assert.assertEquals(0.4f, r.x, 1e-4f)
    }

    @org.junit.Test
    fun `modo axial aplica anti e clamp por eixo com sinal`() {
        val r = StickTransform.apply(
            StickSample(-0.5f, 0f),
            config(deadzone = 0f, anti = 0.2f, mode = DeadzoneMode.AXIAL),
        )
        // |−0.5| → boosted 0.2+0.8*0.5 = 0.6, sinal preservado.
        org.junit.Assert.assertEquals(-0.6f, r.x, 1e-4f)
    }

    @org.junit.Test
    fun `dentro da deadzone o anti nao fabrica saida`() {
        val r = StickTransform.apply(
            StickSample(0.1f, 0f),
            config(deadzone = 0.2f, anti = 0.5f),
        )
        org.junit.Assert.assertTrue(r.inDeadzone)
        org.junit.Assert.assertEquals(0f, r.x, 0f)
        org.junit.Assert.assertEquals(0f, r.y, 0f)
    }
}
