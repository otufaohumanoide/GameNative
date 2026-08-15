package app.gamenative.gamepad.processing

import kotlin.math.abs
import kotlinx.serialization.Serializable

/**
 * Modificadores POR BINDING (spec 2026-08-16-H-binding-modifiers-duckstation, §2.1) —
 * port clean-room das SEMÂNTICAS do DuckStation (GPL-3; NENHUM código copiado, só
 * semânticas reimplementadas em Kotlin e citadas):
 *
 * - `reference/duckstation/src/util/input_manager.h:59-95` — `enum InputModifier
 *   { None, Negate, FullAxis }` + bit `invert` no `InputBindingKey`: cada BINDING
 *   carrega seus modificadores, independentes das configurações globais do pad.
 * - `reference/duckstation/src/util/input_manager.cpp:944-948` —
 *   `ApplySingleBindingScale(scale, deadzone, value)`: escala POR BINDING
 *   (`{Name}Scale`) e deadzone POR BINDING (`{Name}Deadzone`), independentes do
 *   global — o modelo portado aqui.
 * - `reference/duckstation/src/duckstation-qt/inputbindingdialog.cpp:42-57` — os
 *   sliders por binding da UI (sensibilidade + deadzone no dialog de captura).
 *
 * Os campos vivem NO TOKEN do binding (GamepadBindingCodec, sufixo `:m=`) — NENHUM
 * campo novo no GamepadProfile (§2.4 do spec). Todos null-default: `apply` retorna o
 * valor intacto — base da degradação byte-identical.
 */
@Serializable
data class BindingModifier(
    /** Inverte o sinal do eixo NESTE binding (DuckStation `invert`). null = false. */
    val invert: Boolean? = null,
    /** FullAxis: eixo centrado −1..1 vira 0..1 (fórmula exata do DuckStation). null = false. */
    val fullAxis: Boolean? = null,
    /** Sensibilidade fina do binding, 50–200% (0.5..2.0). null = 1.0. */
    val scale: Float? = null,
    /** Deadzone do binding (0.0..0.5) — VENCE a global quando presente. null = sem override. */
    val deadzone: Float? = null,
) {
    /** Todos os campos no default — o codec omite o sufixo `:m=` (token byte-identical). */
    fun isDefault(): Boolean =
        invert != true && fullAxis != true &&
            (scale == null || scale == 1f) &&
            (deadzone == null || deadzone == 0f)
}

/** Decisões PURAS do modificador por binding (zero android.* — JVM-testável). */
object BindingModifiers {

    /** Limite dos sliders da UI: sensibilidade 50–200%. */
    const val SCALE_MIN = 0.5f
    const val SCALE_MAX = 2.0f

    /** Limite dos sliders da UI: zona morta 0–50%. */
    const val DEADZONE_MIN = 0.0f
    const val DEADZONE_MAX = 0.5f

    /**
     * Ordem FIXA (documentada e testada): fullAxis → invert → scale → deadzone.
     *
     * - fullAxis: `v * 0.5f + 0.5f` — fórmula EXATA do `InputModifier::FullAxis` do
     *   DuckStation (input_manager.cpp: "value * 0.5 + 0.5").
     * - invert: `-v`
     * - scale: `v * scale` (clampado a [SCALE_MIN]..[SCALE_MAX])
     * - deadzone: `|v| < dz ⇒ 0` — SEM rescale (o rescale radial é do
     *   DeadzoneProcessor; AQUI o zero é limiar do binding único; dz clampado a
     *   [DEADZONE_MIN]..[DEADZONE_MAX])
     *
     * `mod == null` retorna [value] intacto — o caminho atual exato (§2.1).
     */
    fun apply(value: Float, mod: BindingModifier?): Float {
        if (mod == null) return value
        var v = value
        if (mod.fullAxis == true) v = v * 0.5f + 0.5f
        if (mod.invert == true) v = -v
        v *= (mod.scale ?: 1f).coerceIn(SCALE_MIN, SCALE_MAX)
        val dz = (mod.deadzone ?: 0f).coerceIn(DEADZONE_MIN, DEADZONE_MAX)
        if (dz > 0f && abs(v) < dz) v = 0f
        return v
    }
}
