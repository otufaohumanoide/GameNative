package app.gamenative.gamepad.layers

/**
 * I (spec 2026-08-16-I-trigger-engine-keymapper, §2.2): motor PURO dos modos novos
 * de trigger — LONG_PRESS e SEQUENCE com disparo retardado. Port clean-room das
 * SEMÂNTICAS do key-mapper (GPL-3; NENHUM código copiado — só semânticas
 * reimplementadas em Kotlin e citadas):
 *
 * - `base/.../keymaps/ClickType.kt` — SHORT_PRESS/LONG_PRESS/DOUBLE_PRESS.
 * - `base/.../detection/KeyMapAlgorithm.kt`:
 *   - sequência = ordem IMPORTA, timeout POR PASSO (`sequenceTriggersTimeoutTimes`,
 *     ~linhas 109-117/733-744/1225-1265 — não total);
 *   - DISAMBIGUAÇÃO #1386 (`performActionsAfterSequenceTriggerTimeout`, ~linhas
 *     1751-1782): enquanto uma SEQUÊNCIA pode ainda completar, o short-press do
 *     botão inicial é RETARDADO — só dispara se a sequência morrer (sem isso o
 *     botão dispararia a ação curta E depois a sequência, duplo);
 *   - fallback do long-press (`performActionsOnFailedLongPress`, ~linhas 1406+):
 *     soltar antes do limiar NÃO dispara nada (aqui: nem ativa, nem emite — o
 *     trigger consome o botão desde o down);
 *   - overlap entre sequências que compartilham o 1º botão: o MESMO retardo cobre
 *     as duas; a mais longa que completar vence — a curta espera a resolução da
 *     longa (se a longa morrer, a curta ativa).
 *
 * O [LayerResolver] NÃO é tocado: HOLD/TOGGLE/DOUBLE_TAP continuam byte-identical.
 * Estado por device (V6 — morto no removeDevice; specs vêm do perfil no momento).
 */
class TriggerEngineState {
    /** Botão com LONG_PRESS contando (um por vez — o ÚLTIMO que armar vence). */
    var longPressArmed: String? = null
    var longPressDownAt: Long = 0L

    /** O clock já disparou o Activate do long-press armado. */
    var longPressActivated: Boolean = false

    /** Progresso das sequências PENDENTES por camada: camada → índice do próximo passo. */
    val seqProgress = mutableMapOf<String, Int>()

    /** Down do último passo ACEITO de cada sequência pendente (timeout POR PASSO). */
    val seqStepDownAt = mutableMapOf<String, Long>()

    /**
     * Botões de sequência com Down consumido e Up ainda não visto — o Up é
     * consumido para balancear (o Down nunca chegou ao jogo).
     */
    val heldSeqButtons = mutableSetOf<String>()

    /** Completamento RETARDADO (overlap): a curta espera a longa resolver. */
    var pendingActivate: PendingActivate? = null
}

/** I: completamento de sequência aguardando a resolução de uma mais longa. */
class PendingActivate(val layer: String, val length: Int, val firstButton: String)

sealed interface TriggerOutcome {
    /** Nada — o evento segue o caminho atual (não consumido pelo trigger novo). */
    data object None : TriggerOutcome

    /** Ativa a camada (tick + GamepadLayerEvent — mesma semântica do Activated U3). */
    data class Activate(val layer: String) : TriggerOutcome

    /** Desativa a camada (mesma semântica do Deactivated U3). */
    data class Deactivate(val layer: String) : TriggerOutcome

    /** Guarda o Down lógico do [button] até [untilMs] (disambiguação #1386). */
    data class DelayEmit(val button: String, val untilMs: Long) : TriggerOutcome

    /** A decisão pendente MORREU: libera AGORA os emits guardados do [button]. */
    data class ReleaseDelay(val button: String) : TriggerOutcome

    /** A sequência COMPLETOU: descarta (consome) os emits guardados do [button]. */
    data class ConsumeDelay(val button: String) : TriggerOutcome

    /** Consome o evento atual (balanço do Down consumido / botão do trigger). */
    data object Consume : TriggerOutcome
}

object TriggerEngine {

    /**
     * Down de um botão — consultado pelo hub para TODO botão de spec novo e para
     * TODO botão enquanto há sequência pendente (botão errado mata a sequência).
     * Retorna 0..N outcomes; `Consume`/`DelayEmit`/`Activate`/`ConsumeDelay`
     * marcam o evento como consumido pelo trigger.
     */
    fun onButtonDown(
        state: TriggerEngineState,
        specs: Map<String, LayerTriggerSpec>,
        layerName: String,
        spec: LayerTriggerSpec,
        button: String,
        nowMs: Long,
    ): List<TriggerOutcome> {
        return when (spec.mode) {
            LayerTriggerMode.LONG_PRESS -> {
                if (spec.button != button) return emptyList()
                // O ÚLTIMO botão de long-press vence (um por vez — estado único).
                state.longPressArmed = button
                state.longPressDownAt = nowMs
                state.longPressActivated = false
                listOf(TriggerOutcome.Consume)
            }
            LayerTriggerMode.SEQUENCE -> {
                val full = listOf(spec.button) + spec.sequence
                val progress = state.seqProgress[layerName] ?: 0
                if (button == full.getOrNull(progress)) {
                    // Passo esperado: arma/avança.
                    state.heldSeqButtons += button
                    state.seqStepDownAt[layerName] = nowMs
                    val next = progress + 1
                    if (next >= full.size) {
                        // COMPLETA. Overlap: se há sequência MAIS LONGA pendente
                        // compartilhando o 1º botão, a ativação fica RETARDADA
                        // (pendingActivate) — a longa que completar vence; a longa
                        // morrer libera a curta.
                        state.seqProgress.remove(layerName)
                        state.seqStepDownAt.remove(layerName)
                        val outcomes = mutableListOf<TriggerOutcome>()
                        val longerPending = specs.entries.any { (otherLayer, other) ->
                            otherLayer != layerName &&
                                other.mode == LayerTriggerMode.SEQUENCE &&
                                other.button == spec.button &&
                                state.seqProgress.containsKey(otherLayer) &&
                                other.sequence.size + 1 > full.size
                        }
                        if (longerPending) {
                            state.pendingActivate = PendingActivate(layerName, full.size, spec.button)
                        } else {
                            state.pendingActivate = null
                            outcomes += TriggerOutcome.Activate(layerName)
                        }
                        outcomes += TriggerOutcome.ConsumeDelay(spec.button)
                        outcomes
                    } else {
                        state.seqProgress[layerName] = next
                        // Re-estende o retardo compartilhado do 1º botão (por passo).
                        listOf(TriggerOutcome.DelayEmit(spec.button, nowMs + spec.seqTimeoutMs))
                    }
                } else {
                    // Botão errado: a sequência pendente MORRE e libera o retardo do
                    // 1º botão; um completamento retardado mais curto ativa agora.
                    if (progress == 0) return emptyList()
                    return dieSequence(state, specs, layerName, spec, full, button, emptyList())
                }
            }
            else -> emptyList()
        }
    }

    fun onButtonUp(
        state: TriggerEngineState,
        specs: Map<String, LayerTriggerSpec>,
        layerName: String,
        spec: LayerTriggerSpec,
        button: String,
        nowMs: Long,
    ): List<TriggerOutcome> {
        return when (spec.mode) {
            LayerTriggerMode.LONG_PRESS -> {
                if (spec.button != button) return emptyList()
                val activated = state.longPressArmed == button && state.longPressActivated
                if (state.longPressArmed == button) {
                    state.longPressArmed = null
                    state.longPressActivated = false
                }
                if (activated) {
                    listOf(TriggerOutcome.Deactivate(layerName), TriggerOutcome.Consume)
                } else {
                    // Up antes do limiar: NADA (nem ativa, nem deixa vazar — o Down
                    // já foi consumido).
                    listOf(TriggerOutcome.Consume)
                }
            }
            LayerTriggerMode.SEQUENCE -> {
                if (button in state.heldSeqButtons) {
                    state.heldSeqButtons.remove(button)
                    listOf(TriggerOutcome.Consume)
                } else {
                    emptyList()
                }
            }
            else -> emptyList()
        }
    }

    /**
     * Relógio (rodado pelo hub no flush de eventos — SEM timer/coroutine):
     * - LONG_PRESS armado no limiar → Activate;
     * - passo de sequência expirado → a sequência morre (ReleaseDelay do 1º botão).
     */
    fun onClock(
        state: TriggerEngineState,
        specs: Map<String, LayerTriggerSpec>,
        nowMs: Long,
    ): List<TriggerOutcome> {
        val outcomes = mutableListOf<TriggerOutcome>()
        val armed = state.longPressArmed
        if (armed != null && !state.longPressActivated) {
            val entry = specs.entries.firstOrNull { (_, s) ->
                s.mode == LayerTriggerMode.LONG_PRESS && s.button == armed
            }
            if (entry != null) {
                if (nowMs - state.longPressDownAt >= entry.value.longPressMs) {
                    state.longPressActivated = true
                    outcomes += TriggerOutcome.Activate(entry.key)
                }
            } else {
                // Arma órfã (perfil trocou) — morre sem efeito.
                state.longPressArmed = null
            }
        }
        for ((layerName, progress) in state.seqProgress.toList()) {
            if (progress <= 0) continue
            val spec = specs[layerName] ?: continue
            if (spec.mode != LayerTriggerMode.SEQUENCE) continue
            val stepDownAt = state.seqStepDownAt[layerName] ?: 0L
            if (nowMs - stepDownAt >= spec.seqTimeoutMs) {
                val full = listOf(spec.button) + spec.sequence
                outcomes += dieSequence(state, specs, layerName, spec, full, "", emptyList())
            }
        }
        return outcomes
    }

    /**
     * Morte de uma sequência pendente (botão errado ou timeout do passo):
     * limpa o progresso, libera o retardo do 1º botão e ativa um completamento
     * retardado mais curto se nenhuma mais longa continuar pendente.
     */
    private fun dieSequence(
        state: TriggerEngineState,
        specs: Map<String, LayerTriggerSpec>,
        layerName: String,
        spec: LayerTriggerSpec,
        full: List<String>,
        button: String,
        initial: List<TriggerOutcome>,
    ): List<TriggerOutcome> {
        state.seqProgress.remove(layerName)
        state.seqStepDownAt.remove(layerName)
        // O Down do 1º botão será RE-EMITIDO (ReleaseDelay) — o Up passa a vazar.
        state.heldSeqButtons.remove(spec.button)
        val outcomes = initial.toMutableList()
        val pending = state.pendingActivate
        if (pending != null && pending.firstButton == spec.button && pending.layer != layerName) {
            val stillLonger = specs.entries.any { (otherLayer, other) ->
                otherLayer != layerName &&
                    other.mode == LayerTriggerMode.SEQUENCE &&
                    other.button == spec.button &&
                    state.seqProgress.containsKey(otherLayer) &&
                    other.sequence.size + 1 > pending.length
            }
            if (!stillLonger) {
                state.pendingActivate = null
                outcomes += TriggerOutcome.Activate(pending.layer)
            }
        }
        // O botão errado só é consumido se pertence À sequência (tecla do trigger —
        // key-mapper consome as teclas do trigger enquanto pendente; botão alheio passa).
        if (button.isNotEmpty() && button in full) {
            outcomes += TriggerOutcome.Consume
        }
        outcomes += TriggerOutcome.ReleaseDelay(spec.button)
        return outcomes
    }
}
