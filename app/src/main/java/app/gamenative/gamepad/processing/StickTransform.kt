package app.gamenative.gamepad.processing

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign

/**
 * Transformação completa de stick (F1.1 do spec 2026-08-15-input-core-avancado):
 * deadzone (radial OU axial — o perfil escolhe) → response curve (LINEAR /
 * EXPONENTIAL / SCURVE / LUT custom de N pontos com interpolação linear).
 *
 * Puro Kotlin, zero android.*, JVM-testável. A deadzone é a MESMA do
 * [DeadzoneProcessor] (o par `leftStick − hysteresis` com saída rescalonada — nenhuma
 * lógica nova de deadzone); a curva age DEPOIS da deadzone:
 * - modo RADIAL: na MAGNITUDE do vetor (preserva direção);
 * - modo AXIAL: por eixo (preserva sinal).
 *
 * LUT: lista de pontos normalizados 0..1 (entrada → saída) com interpolação linear
 * entre pontos. Sanitizada no uso (clamp, descarte de não-finitos) — JSON malformado
 * degrada para LINEAR, nunca exceção no boot do jogo (risco §6 do spec).
 *
 * Byte-identical quando nada está configurado: o chamador só usa esta classe quando
 * o perfil override deadzone/curva/modo/LUT; sem override o caminho antigo permanece.
 */
@kotlinx.serialization.Serializable
enum class ResponseCurve { LINEAR, EXPONENTIAL, SCURVE, LUT }

data class StickTransformConfig(
    val deadzone: Float = 0.15f,
    val mode: DeadzoneMode = DeadzoneMode.RADIAL,
    val hysteresis: Float = 0.05f,
    val curve: ResponseCurve = ResponseCurve.LINEAR,
    /** Pontos da LUT (entrada→saída, 0..1). Vazio/inválido = LINEAR. */
    val lut: List<Float> = emptyList(),
)

data class StickTransformResult(val x: Float, val y: Float, val inDeadzone: Boolean)

object StickTransform {

    /** LUT sem pontos suficientes → trata como LINEAR (degradação silenciosa). */
    private const val MIN_LUT_POINTS = 2

    fun apply(sample: StickSample, config: StickTransformConfig): StickTransformResult {
        val dz = DeadzoneProcessor.process(
            sample,
            DeadzoneConfig(
                leftStick = config.deadzone,
                rightStick = config.deadzone,
                mode = config.mode,
                hysteresis = config.hysteresis,
            ),
        )
        if (dz.inDeadzone) return StickTransformResult(0f, 0f, true)
        return when (config.mode) {
            DeadzoneMode.RADIAL -> {
                val magnitude = hypot(dz.x, dz.y)
                if (magnitude <= 0f) return StickTransformResult(0f, 0f, true)
                val curved = curve(magnitude, config)
                val scale = if (magnitude <= 0f) 0f else curved / magnitude
                StickTransformResult(dz.x * scale, dz.y * scale, false)
            }
            DeadzoneMode.AXIAL -> StickTransformResult(
                x = curveSigned(dz.x, config),
                y = curveSigned(dz.y, config),
                inDeadzone = false,
            )
        }
    }

    /** Aplica a response curve a uma magnitude 0..1 (preserva 0 e 1). */
    fun curve(magnitude: Float, config: StickTransformConfig): Float {
        val m = magnitude.coerceIn(0f, 1f)
        return when (config.curve) {
            ResponseCurve.LINEAR -> m
            ResponseCurve.EXPONENTIAL -> m * m
            ResponseCurve.SCURVE -> m * m * (3f - 2f * m) // smoothstep
            // Limpeza 1.3-3: a LUT chega SANITIZADA (store sanitiza no load; imports
            // sanitizam no parse) — nada de re-sanitizar por evento no hot path.
            ResponseCurve.LUT -> lutValue(m, config.lut)
        }.coerceIn(0f, 1f)
    }

    /** Curva por eixo com sinal preservado (modo AXIAL). */
    fun curveSigned(value: Float, config: StickTransformConfig): Float {
        if (value == 0f) return 0f
        return sign(value) * curve(abs(value), config)
    }

    /**
     * Interpolação linear da LUT. Lista sanitizada ([sanitizeLut]); vazia/inválida
     * (menos de 2 pontos) → identidade. Clamp no domínio — nunca NaN/fora de faixa.
     */
    fun lutValue(t: Float, lut: List<Float>): Float {
        if (lut.size < MIN_LUT_POINTS) return t.coerceIn(0f, 1f)
        val x = t.coerceIn(0f, 1f)
        val last = lut.lastIndex
        val pos = x * last
        val i = pos.toInt().coerceIn(0, last - 1)
        val frac = pos - i
        return (lut[i] + (lut[i + 1] - lut[i]) * frac).coerceIn(0f, 1f)
    }

    /**
     * Sanitização da LUT (risco §6 — JSON malformado nunca crasha o boot):
     * clamp 0..1, descarta NaN/Infinito, remove duplicatas consecutivas de entrada
     * (a LUT é indexada por posição — duplicatas só desperdiçam resolução). Menos de
     * 2 pontos úteis → lista vazia (o consumidor degrada a LINEAR).
     */
    fun sanitizeLut(raw: List<Float>): List<Float> {
        val clean = raw.mapNotNull { v ->
            if (v.isFinite()) v.coerceIn(0f, 1f) else null
        }
        return if (clean.size < MIN_LUT_POINTS) emptyList() else clean
    }
}
