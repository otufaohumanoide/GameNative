package app.gamenative.gamepad.layers

import app.gamenative.gamepad.profiles.ActionLayer

/**
 * Motor de ativação de camadas (spec 2026-08-14-gamepad-u3-u4-layers-remap-jogo, §1.2 —
 * conceito Steam Input/DS4Windows, sem DSL textual): estado por device (V6 — morto no
 * removeDevice) + decisões PURAS (V5) a partir do [LayerTriggerSpec] do perfil.
 *
 * Regras:
 * - Uma camada ativa por vez; ativar outra DESATIVA a anterior. `null` = camada DEFAULT.
 * - HOLD: down ativa, up desativa (só se a camada ativa é esta).
 * - TOGGLE: cada down inverte.
 * - DOUBLE_TAP: dois downs dentro de [LayerTriggerSpec.doubleTapMs] invertem; um tap
 *   isolado arma a janela; o terceiro tap (fora da janela) reinicia a sequência.
 */
class LayerState {
    var activeLayer: String? = null

    /** Botões HOLD atualmente segurados (por nome — um por device, V6). */
    val heldButtons = mutableSetOf<String>()

    /** Janela de duplo-toque: true depois do primeiro tap, false após o segundo. */
    var tapArmed: Boolean = false
    var lastTapAt: Long = 0L
}

sealed interface LayerChange {
    data class Activated(val layer: String) : LayerChange
    data class Deactivated(val layer: String) : LayerChange
    data object None : LayerChange
}

object LayerResolver {

    /**
     * Mapa de bindings EFETIVO de um device: camada DEFAULT (base, editável no remap)
     * + camada ativa por cima (U3 §1.3). `activeLayer == null` → só DEFAULT.
     */
    fun effectiveBindings(
        layers: Map<String, Map<String, String>>,
        activeLayer: String?,
    ): Map<String, String> {
        val base = layers[ActionLayer.DEFAULT.name].orEmpty()
        val active = activeLayer?.let { layers[it] }.orEmpty()
        return base + active
    }

    /**
     * F (spec 2026-08-16-F-radial-v2-modeshift-turbo, §1.3): camada de SHIFT usa o
     * MESMO motor U3 de ativação (nada muda aqui — branch preserva a mecânica), mas
     * o HUB suprime os "eventos comuns" (GamepadLayerEvent — não abre radial — e o
     * tick háptico) e consome o botão físico. A decisão fica PURA aqui (testável em
     * JVM) e o hub só consulta o resultado.
     */
    fun suppressCommonEvents(spec: LayerTriggerSpec): Boolean = spec.isShift

    fun onButtonDown(
        state: LayerState,
        layerName: String,
        trigger: LayerTriggerSpec,
        nowMs: Long,
    ): LayerChange {
        return when (trigger.mode) {
            LayerTriggerMode.HOLD -> {
                // holdArmed POR BOTÃO (o flag único quebrava múltiplas camadas HOLD:
                // segurar A e apertar B não ativava B — teste 2026-08-14).
                if (state.heldButtons.add(trigger.button)) {
                    activate(state, layerName)
                } else {
                    LayerChange.None
                }
            }
            LayerTriggerMode.TOGGLE -> {
                if (state.activeLayer == layerName) {
                    deactivate(state, layerName)
                } else {
                    activate(state, layerName)
                }
            }
            LayerTriggerMode.DOUBLE_TAP -> {
                val withinWindow = state.tapArmed &&
                    nowMs - state.lastTapAt <= trigger.doubleTapMs
                state.tapArmed = true
                state.lastTapAt = nowMs
                if (withinWindow) {
                    state.tapArmed = false // segundo tap consome a janela
                    if (state.activeLayer == layerName) {
                        deactivate(state, layerName)
                    } else {
                        activate(state, layerName)
                    }
                } else {
                    LayerChange.None // primeiro tap: arma a janela
                }
            }
        }
    }

    fun onButtonUp(
        state: LayerState,
        layerName: String,
        trigger: LayerTriggerSpec,
        nowMs: Long,
    ): LayerChange {
        return when (trigger.mode) {
            LayerTriggerMode.HOLD -> {
                state.heldButtons.remove(trigger.button)
                if (state.activeLayer == layerName) {
                    deactivate(state, layerName)
                } else {
                    LayerChange.None
                }
            }
            else -> LayerChange.None
        }
    }

    private fun activate(state: LayerState, layerName: String): LayerChange {
        state.activeLayer = layerName
        return LayerChange.Activated(layerName)
    }

    private fun deactivate(state: LayerState, layerName: String): LayerChange {
        state.activeLayer = null
        return LayerChange.Deactivated(layerName)
    }
}
