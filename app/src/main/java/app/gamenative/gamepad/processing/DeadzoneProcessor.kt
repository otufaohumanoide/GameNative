package app.gamenative.gamepad.processing

import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Deadzone e histerese (spec 2026-08-13, Parte I §6 e Passo 5).
 *
 * - RADIAL: ignora o par quando √(x²+y²) < limiar — o natural para sticks.
 * - AXIAL: ignora eixo a eixo quando |v| < limiar — o natural para triggers e sticks
 *   com drift assimétrico.
 *
 * Rescalonamento: depois de subtrair o limiar, o valor é renormalizado para 0..1
 * (`(|v| − t)/(1 − t)`), preservando sinal e direção — o jogo não perde a faixa útil.
 *
 * Histerese (saída em `deadzone − hysteresis`): a assimetria entrada/saída que mata o
 * jitter em torno do limiar. As assinaturas congeladas [process]/[processAxis] são
 * STATELESS — a opção adotada é "threshold documentado" (Passo 5): o limiar efetivo de
 * saída é `deadzone − hysteresis`. O estado completo (entrar em `deadzone`, sair em
 * `deadzone − hysteresis`) é responsabilidade do GamepadHub na Onda 2 (padrão
 * DuckStation/Dolphin).
 */
/** F1.1 (spec 2026-08-15-input-core-avancado): serializável (campo de perfil). */
@kotlinx.serialization.Serializable
enum class DeadzoneMode { RADIAL, AXIAL }

data class DeadzoneConfig(
    val leftStick: Float = 0.15f,
    val rightStick: Float = 0.15f,
    val leftTrigger: Float = 0.08f,
    val rightTrigger: Float = 0.08f,
    val mode: DeadzoneMode = DeadzoneMode.RADIAL,
    val hysteresis: Float = 0.05f,
)

data class StickSample(val x: Float, val y: Float)

data class DeadzoneResult(val x: Float, val y: Float, val inDeadzone: Boolean)

object DeadzoneProcessor {

    /**
     * Processa um par de stick com a deadzone do par em [DeadzoneConfig.leftStick] — o
     * tradutor passa um config ajustado para o stick direito (deadzone = rightStick).
     * O limiar efetivo é `leftStick − hysteresis` (threshold documentado).
     */
    fun process(sample: StickSample, config: DeadzoneConfig): DeadzoneResult {
        val exit = effectiveThreshold(config.leftStick, config.hysteresis)
        return when (config.mode) {
            DeadzoneMode.RADIAL -> {
                val magnitude = sqrt(sample.x * sample.x + sample.y * sample.y)
                if (magnitude <= exit) {
                    DeadzoneResult(0f, 0f, inDeadzone = true)
                } else {
                    // Preserva a direção do vetor e renormaliza a magnitude para 0..1.
                    val scale = (magnitude - exit) / (magnitude * (1f - exit))
                    DeadzoneResult(sample.x * scale, sample.y * scale, inDeadzone = false)
                }
            }
            DeadzoneMode.AXIAL -> {
                val x = rescaleAxis(sample.x, exit)
                val y = rescaleAxis(sample.y, exit)
                DeadzoneResult(x, y, inDeadzone = x == 0f && y == 0f)
            }
        }
    }

    /**
     * Processa um valor de trigger (axial, tipicamente 0..1). Limiar efetivo
     * `deadzone − hysteresis` (threshold documentado), saída rescalonada 0..1.
     */
    fun processAxis(value: Float, deadzone: Float): Float {
        val exit = effectiveThreshold(deadzone, DEFAULT_HYSTERESIS)
        return rescaleAxis(value, exit)
    }

    /** `(|v| − t) / (1 − t)` com sinal preservado; 0 dentro do limiar. */
    private fun rescaleAxis(value: Float, threshold: Float): Float {
        if (abs(value) <= threshold) return 0f
        return sign(value) * (abs(value) - threshold) / (1f - threshold)
    }

    /** Proteção contra divisão por zero (dz + hyst ≥ 1) e limiar negativo. */
    private fun effectiveThreshold(deadzone: Float, hysteresis: Float): Float =
        (deadzone - hysteresis).coerceIn(0f, 0.99f)

    /** Histerese usada por [processAxis] (assinatura stateless do contrato). */
    const val DEFAULT_HYSTERESIS = 0.05f
}
