package app.gamenative.gamepad.layers

import kotlinx.serialization.Serializable

/**
 * Trigger de ativação de uma camada de ação (spec 2026-08-14-gamepad-u3-u4-layers-
 * remap-jogo, §1.1): a ÚNICA forma de ligar uma camada — nada de heurística por nome.
 *
 * [button] = `GamepadButton.name` do botão físico que aciona (pós-remap de camada).
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
