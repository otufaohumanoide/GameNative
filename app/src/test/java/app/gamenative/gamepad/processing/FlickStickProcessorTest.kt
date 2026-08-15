package app.gamenative.gamepad.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/** F1.2 (spec 2026-08-15-input-core-avancado): FlickStickProcessor. */
class FlickStickProcessorTest {

    private val config = FlickStickConfig()
    private val state = FlickStickState()

    private fun process(x: Float, y: Float, nowMs: Long): FlickStickOutput =
        FlickStickProcessor.process(StickSample(x, y), nowMs, state, config)

    @Test
    fun `abaixo do activationRadius nao emite nada`() {
        val out = process(0.4f, 0.4f, 0L) // mag 0.57 < 0.85
        assertEquals(0f, out.yawRadS, 0f)
    }

    @Test
    fun `hold acima do limiar emite taxa continua proporcional`() {
        // stick na extrema direita: ângulo 0 → cos=1; força = (1−0.85)/0.15 = 1
        val out = process(1f, 0f, 0L)
        assertEquals(config.maxYawRadS, out.yawRadS, 0.001f)
        // metade da sobra: mag = 0.925 → força 0.5
        val mid = process(0.925f, 0f, 10L)
        assertEquals(config.maxYawRadS * 0.5f, mid.yawRadS, 0.01f)
    }

    @Test
    fun `direcao esquerda inverte o sinal`() {
        val out = process(-1f, 0f, 0L)
        assertEquals(-config.maxYawRadS, out.yawRadS, 0.001f)
    }

    @Test
    fun `snapAngle protege a direcao de micro-giros`() {
        // ancora apontando para a direita
        process(1f, 0f, 0L)
        // micro-giro de 5° (abaixo do snap de 15°): direção NÃO muda
        val angle = 5f * PI.toFloat() / 180f
        val out = process(1f * kotlin.math.cos(angle), 1f * kotlin.math.sin(angle), 10L)
        // cos(5°) ≈ 0.996 — ainda aponta quase à direita
        assertEquals(config.maxYawRadS, out.yawRadS, 0.01f)
    }

    @Test
    fun `flick reto dispara burst instantaneo`() {
        // empurra rápido (hold 100ms < flickWindow) e solta — flick legítimo SEM giro
        process(1f, 0f, 0L)
        val out = process(0f, 0f, 100L)
        assertEquals(config.maxYawRadS, out.yawRadS, 0.001f)
        // burst continua sem novo input até acabar
        val during = process(0f, 0f, 150L)
        assertEquals(config.maxYawRadS, during.yawRadS, 0.001f)
        // após o burst, volta a zero
        val after = process(0f, 0f, 100L + config.flickBurstMs + 1L)
        assertEquals(0f, after.yawRadS, 0f)
    }

    @Test
    fun `hold longo nao vira flick`() {
        process(1f, 0f, 0L)
        val out = process(0f, 0f, config.flickWindowMs + 1L)
        assertEquals(0f, out.yawRadS, 0f)
    }

    @Test
    fun `micro-giro continuo abaixo do snap nao muda a saida mas acumula`() {
        process(1f, 0f, 0L) // ancora 0°
        // giro de 10° (abaixo de 15°): saída continua na direção ancorada
        val a = 10f * PI.toFloat() / 180f
        val out = process(kotlin.math.cos(a), kotlin.math.sin(a), 10L)
        assertEquals(config.maxYawRadS, out.yawRadS, 0.01f)
    }

    @Test
    fun `diferenca angular normaliza para o caminho curto`() {
        assertEquals(0f, FlickStickProcessor.angularDiffDeg(0f, 0f), 0.001f)
        assertEquals(90f, FlickStickProcessor.angularDiffDeg(0f, PI.toFloat() / 2f), 0.001f)
        // 350° vs 10°: caminho curto = 20°
        assertEquals(20f, FlickStickProcessor.angularDiffDeg(350f * PI.toFloat() / 180f, 10f * PI.toFloat() / 180f), 0.01f)
    }

    @Test
    fun `release sem flick limpa o estado`() {
        process(1f, 0f, 0L)
        process(0f, 0f, config.flickWindowMs + 1L) // hold longo → sem burst
        val again = process(1f, 0f, 2000L) // novo hold: reancora
        assertEquals(config.maxYawRadS, again.yawRadS, 0.001f)
    }
}
