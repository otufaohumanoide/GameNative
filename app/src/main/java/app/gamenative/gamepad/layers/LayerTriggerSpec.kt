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
 * - [LayerTriggerMode.LONG_PRESS]: segurar [longPressMs] ativa; soltar antes = NADA
 *   (spec 2026-08-16-I-trigger-engine-keymapper, §2.1/2.2 — port do key-mapper:
 *   `ClickType.LONG_PRESS` + fallback `performActionsOnFailedLongPress`; o botão é
 *   CONSUMIDO desde o down — isShift implícito para o modo).
 * - [LayerTriggerMode.SEQUENCE]: [button] → [sequence] (2–3 botões, ordem importa,
 *   timeout POR PASSO de [seqTimeoutMs]) ativa; o short-press do 1º botão é
 *   RETARDADO até a resolução (disambiguação #1386 do key-mapper).
 * [isShift]:
 * - F (spec 2026-08-16-F-radial-v2-modeshift-turbo, §1.3): camada de SHIFT (chord
 *   Steam Input) — MESMO motor de ativação U3 (HOLD/TOGGLE/DOUBLE_TAP), mas o HUB
 *   suprime o GamepadLayerEvent (não abre radial) e o tick háptico, e o botão
 *   físico é CONSUMIDO (não chega ao jogo — camada comum é pass-through). O
 *   remap continua pelo effectiveBindings (mecânica U3 existente, intacta).
 *   Serializável com default false — JSON v1 preserva (degradação byte-identical).
 *
 * I (spec 2026-08-16-I, §2.1): campos novos com default — JSON v1 preserva
 * (kotlinx decodifica sem quebrar; `ignoreUnknownKeys` já ativo no store — V1).
 */
@Serializable
data class LayerTriggerSpec(
    val button: String,
    val mode: LayerTriggerMode,
    val doubleTapMs: Int = 250,
    val isShift: Boolean = false,
    // ── I (spec 2026-08-16-I-trigger-engine-keymapper) ──
    /** LONG_PRESS: ms até ativar (UI 200–1500). */
    val longPressMs: Int = 500,
    /** SEQUENCE: botões 2..N (após [button]); vazio = sem passos. */
    val sequence: List<String> = emptyList(),
    /** SEQUENCE: timeout POR PASSO em ms (UI 200–1000). */
    val seqTimeoutMs: Int = 400,
)

enum class LayerTriggerMode { HOLD, TOGGLE, DOUBLE_TAP, LONG_PRESS, SEQUENCE }
