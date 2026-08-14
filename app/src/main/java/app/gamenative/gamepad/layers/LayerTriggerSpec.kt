package app.gamenative.gamepad.layers

import kotlinx.serialization.Serializable

/**
 * Trigger de ativação de uma camada de ação (spec 2026-08-14-gamepad-u3-u4-layers-
 * remap-jogo, §1.1): a ÚNICA forma de ligar uma camada — nada de heurística por nome.
 *
 * [button] = `GamepadButton.name` do botão FÍSICO que aciona (PRÉ-remap — decisão
 * U3 §1.3: os triggers resolvem no botão físico, antes do remap da camada; ver
 * `GamepadHub.resolveLayerTriggers`). P3-2 do spec 2026-08-14-gamepad-upgrades-
 * pendencias: o KDoc anterior dizia "pós-remap" e contradizia o hub.
 * [mode]:
 * - [LayerTriggerMode.HOLD]: segurar ativa; soltar desativa (ex.: segurar L2 = "Sprint").
 * - [LayerTriggerMode.TOGGLE]: cada pressionada inverte (ex.: click de L3 = "Sniper").
 * - [LayerTriggerMode.DOUBLE_TAP]: dois toques dentro de [doubleTapMs] invertem.
 */
@Serializable
data class LayerTriggerSpec(
    val button: String,
    val mode: LayerTriggerMode,
    val doubleTapMs: Int = 250,
)

enum class LayerTriggerMode { HOLD, TOGGLE, DOUBLE_TAP }
