package app.gamenative.gamepad.processing

import kotlin.math.PI

/**
 * Filtro One Euro (Casiez et al., CHI 2012) — G2 do spec 2026-08-16-G-gyro-v2, §1.
 * PURO (JVM-testável): nenhum android.*; o estado entre chamadas vive na própria
 * instância (UMA por eixo, por device — V6: o hub descarta no removeDevice).
 *
 * Algoritmo padrão do paper com cutoff adaptado à velocidade (beta):
 * - `alpha(minCutoff)` suaviza o valor (frequências abaixo do cutoff passam;
 *   jitter acima dele é atenuado);
 * - a derivada é filtrada com `alpha(dCutoff)` e o resultado PREDIZ o valor
 *   (`value += derivative*dt`) — movimentos rápidos passam, lentos são suavizados.
 *
 * Defaults DS4Windows (`GyroMouseStickInfo.DEFAULT_MINCUTOFF/DEFAULT_BETA`,
 * reference/DS4Windows/DS4Windows/DS4Control/ProfilePropGroups.cs): minCutoff
 * 1.0 Hz, beta 0.7, dCutoff 1.0 Hz.
 *
 * Uso (G2): filtra deltaXRad/deltaYRad POR EIXO antes da conversão em pixels, com
 * rate = 1/dt da amostra (dt clampado 1..100 ms — o MESMO do GyroProcessor).
 */
class OneEuroFilter(
    /** Cutoff mínimo (Hz) — suavização em repouso/movimento lento. */
    var minCutoff: Float = DEFAULT_MIN_CUTOFF,
    /** Coeficiente de velocidade — cutoff efetivo = minCutoff + beta*|derivada|. */
    var beta: Float = DEFAULT_BETA,
    /** Cutoff do filtro da derivada (Hz) — fixo no paper. */
    var dCutoff: Float = DEFAULT_DCUTOFF,
) {
    private var prevValue: Float = 0f
    private var prevDerivative: Float = 0f
    private var hasPrev: Boolean = false

    /** Filtra [value] assumindo amostragem a [rateHz]. Primeira chamada ancora. */
    fun filter(value: Float, rateHz: Float): Float {
        if (!hasPrev) {
            prevValue = value
            hasPrev = true
            return value
        }
        val rate = rateHz.coerceAtLeast(MIN_RATE_HZ)
        val dt = 1f / rate
        val alphaValue = alpha(minCutoff, rate)
        val filtered = alphaValue * value + (1f - alphaValue) * prevValue
        val alphaDerivative = alpha(dCutoff, rate)
        val derivative = alphaDerivative * (filtered - prevValue) * rate +
            (1f - alphaDerivative) * prevDerivative
        prevDerivative = derivative
        prevValue = filtered + derivative * dt
        return prevValue
    }

    /** Zera o estado — a próxima chamada ancora de novo (borda de ativação). */
    fun reset() {
        prevValue = 0f
        prevDerivative = 0f
        hasPrev = false
    }

    private fun alpha(cutoff: Float, rate: Float): Float {
        val tau = 1f / (TWO_PI * cutoff)
        return 1f / (1f + tau * rate)
    }

    companion object {
        const val DEFAULT_MIN_CUTOFF = 1.0f
        const val DEFAULT_BETA = 0.7f
        const val DEFAULT_DCUTOFF = 1.0f
        private const val TWO_PI = (2.0 * PI).toFloat()
        private const val MIN_RATE_HZ = 1f
    }
}
