# Spec 2026-08-16 I — Trigger engine: LONG_PRESS e SEQUENCE com disparo retardado (port key-mapper)

**Data:** 2026-08-16
**Executor:** agente autônomo (Prime Agent). Leia `AGENTS.md` e o roadmap mestre
`docs/spec-2026-08-16-master-roadmap-input-avancado.md` (§1 loop, §2 regras) ANTES.
**Posição na fila:** fase I (segunda, DEPOIS de H — commit de fronteira obrigatório).
Spec AUTOCONTIDO — delegável a sub-agente `rlm(...)`.
**Turn budget sugerido:** 25–35 turns.

## 0. Origem e onde ler a fonte

Feature EXCLUSIVA do key-mapper entre os references (nenhum outro tem motor de
trigger com SEQUÊNCIA ordenada + disambiguação de disparo retardado). Clean-room:
reimplementar semânticas, citar a fonte no KDoc. key-mapper é GPL-3.

Leia (nessa ordem — são ~1.400 linhas no total, leia com foco nos pontos marcados):
1. `reference/key-mapper/base/src/main/java/io/github/sds100/keymapper/base/keymaps/ClickType.kt`
   — SHORT_PRESS / LONG_PRESS / DOUBLE_PRESS (pequeno, leia inteiro).
2. `reference/key-mapper/base/src/main/java/io/github/sds100/keymapper/base/detection/KeyMapAlgorithm.kt`
   (~1.250 linhas — NÃO leia inteiro de uma vez; busque e leia):
   - `TriggerMode.Sequence` vs `TriggerMode.Parallel` (paralelo = chord
     simultâneo; sequência = ordem IMPORTA);
   - o tratamento de **timeout de sequência** e **overlap entre triggers**
     (quando dois triggers compartilham o primeiro botão);
   - **A DISAMBIGUAÇÃO CENTRAL** (issue #1386 do key-mapper, comentada no código):
     enquanto uma SEQUÊNCIA pode ainda completar (botão inicial pressionado,
     timeout não expirou), a ação do SHORT-PRESS daquele botão é **RETARDADA** —
     só dispara se a sequência morrer (timeout) ou não existir. Sem isso, A
     dispara a ação curta E depois a sequência A→B dispara de novo (duplo).
   - **fallback sets**: long-press que solta antes do limiar NÃO dispara a ação
     longa — vira short-press; double-press que não fecha a janela idem.
3. `reference/key-mapper/base/src/main/java/io/github/sds100/keymapper/base/keymaps/`
   (diretório) — `Trigger.kt`/`KeyMap.kt` para o modelo de dados (só para entender
   o que o algoritmo consome; NÃO portar o modelo — o fork já tem `LayerTriggerSpec`).

## 1. Estado atual (anchors do fork)

- `gamepad/layers/LayerTriggerSpec.kt` — `button: String` (ÚNICO, físico),
  `mode: HOLD|TOGGLE|DOUBLE_TAP`, `doubleTapMs = 250`, `isShift`.
- `gamepad/layers/LayerResolver.kt` — máquina de estados pura:
  `onButtonDown/onButtonUp(state, layerName, trigger, nowMs): LayerChange`;
  `LayerState { activeLayer, heldButtons, tapArmed, lastTapAt }`. Uma camada ativa.
- `gamepad/GamepadHub.kt:351` `resolveLayerTriggers` — despacha ButtonDown/Up
  lógicos para o resolver; SHIFT consume o evento (hub:373-375); sem foco de janela
  = no-op (F2.4, hub:358).
- Trigger editor: `GamepadRemapDialog.kt` (capture `captureLayerTrigger`,
  `pendingTriggerMode`, detecção de conflito hub:473-481).

O que FALTA (este spec):
- **LONG_PRESS**: segurar N ms ativa a camada; soltar antes = NADA acontece (não é
  hold, não vira curto — o botão é do trigger, comportamento tipo "modificador
  moroso" de key-mapper `ClickType.LONG_PRESS`).
- **SEQUENCE**: A → B (2–3 botões, ordem importa, timeout entre passos) ativa a
  camada; o **short-press de A é retardado** até a resolução (disambiguação #1386).
- Delays configuráveis por trigger (`longPressMs`, `seqTimeoutMs`).

## 2. Design

### 2.1 Modelo — `LayerTriggerSpec` (evolução compatível)

```kotlin
@Serializable
data class LayerTriggerSpec(
    val button: String,                       // 1º botão (atual)
    val mode: LayerTriggerMode,
    val doubleTapMs: Int = 250,
    val isShift: Boolean = false,
    // NOVO (I):
    val longPressMs: Int = 500,               // LONG_PRESS
    val sequence: List<String> = emptyList(), // SEQUENCE: botões 2..N (após `button`)
    val seqTimeoutMs: Int = 400,              // timeout por PASSO da sequência
)
// enum LayerTriggerMode { HOLD, TOGGLE, DOUBLE_TAP, LONG_PRESS, SEQUENCE }
```
Defaults preservam JSON v1 (kotlinx: campos novos com default não quebram decode;
`ignoreUnknownKeys` já ativo no store — política V1).

### 2.2 Motor puro — `gamepad/layers/TriggerEngine.kt` (NOVO)

NÃO mexer no `LayerResolver` (HOLD/TOGGLE/DOUBLE_TAP ficam byte-identical). Novo
objeto puro que o hub consulta ANTES do resolver para os modos novos:

```kotlin
class TriggerEngineState {
    var longPressArmed: String? = null        // botão com long-press contando
    var longPressDownAt: Long = 0L
    var seqProgress: Int = 0                  // índice do próximo passo
    var seqStepDownAt: Long = 0L
    val heldSeqButtons = mutableSetOf<String>()
}

sealed interface TriggerOutcome {
    data object None : TriggerOutcome            // nada (seguir caminho atual)
    data class Activate(val layer: String) : TriggerOutcome
    data class Deactivate(val layer: String) : TriggerOutcome
    data class DelayEmit(val button: String, val untilMs: Long) : TriggerOutcome
}

object TriggerEngine {
    fun onButtonDown(state, layerName, spec, logicalButton, nowMs): TriggerOutcome
    fun onButtonUp(state, layerName, spec, logicalButton, nowMs): TriggerOutcome
    fun onClock(state, specs, nowMs): List<TriggerOutcome>  // vencimentos (long/seq)
    fun delayedPending(state): Map<String, Long>           // botões retardados vivos
}
```

Semânticas (portar do key-mapper):
- **LONG_PRESS down:** arma `longPressArmed` + timestamp. **Up antes do limiar:**
  NADA (nem ativa, nem emite — o trigger CONSUME o botão desde o down: `isShift`
  implícito para o modo). **Clock no limiar:** `Activate`. 
- **SEQUENCE down do 1º botão:** `seqProgress=1`, marca `DelayEmit` para o botão
  (retardo = `seqTimeoutMs`). **Down do próximo botão esperado:** avança progresso;
  completa ⇒ `Activate` e o retardo do 1º botão é DESCARTADO (consumido). **Down de
  botão errado OU clock do timeout do passo:** sequência morre; os emits retardados
  são LIBERADOS (ver 2.3). **Timeout entre passos:** mesmo relógio do passo
  (key-mapper usa timeout por passo, não total).
- **Overlap** (dois triggers SEQUENCE compartilhando o 1º botão): o MESMO retardo
  cobre os dois; sequência mais longa que completar vence (a curta já morreu no
  botão errado). Se AMBAS morrerem ⇒ libera o retardo.
- Trigger de modo novo IMPLICA consumo do(s) botão(ões) da sequência enquanto a
  decisão está pendente (mesma regra do SHIFT F §1.3 — o botão não chega ao jogo
  adiantado; chega atrasado se a sequência morrer, ou nunca se completar).

### 2.3 Retardo no hub — fila pendente + flush barato

- `GamepadHub`: `pendingEmits: MutableMap<Int deviceId, MutableMap<String button, (event, deadlineMs)>>`
  (estado V6 — morto no removeDevice).
- `resolveLayerTriggers` (hub:351): para specs LONG_PRESS/SEQUENCE consulta o
  `TriggerEngine`; `DelayEmit` ⇒ guarda o ButtonDown lógico no pendingEmits e NÃO
  emite; resolução ⇒ descarta (consumido) ou libera (emite agora).
- **Flush SEM timer novo:** no TOPO de `onKey`, `onAxis` e `onSensorSample`, varrer
  `pendingEmits` do device (mapa minúsculo) liberando vencidos — os eventos de input
  são o relógio (≤ ~8 ms de granularidade a 120 Hz de polling; para MOUSE/CAMERA o
  sensor entrega 50 Hz — suficiente). Sem coroutines, sem timers (padrão V3).
- `onClock` também roda no flush: é quem dispara LONG_PRESS no limiar e mata passos
  de sequência expirados.
- Injeção do atraso só existe quando há spec LONG_PRESS/SEQUENCE no perfil —
  sem elas, `pendingEmits` fica vazio e o caminho é byte-identical.

### 2.4 UI — editor de trigger

No fluxo `captureLayerTrigger` (dialog:444+): dropdown de modo ganha LONG_PRESS e
SEQUENCE; LONG_PRESS mostra slider `longPressMs` 200–1500; SEQUENCE entra em modo
captura encadeada (captura 2–3 botões em sequência, na ordem) + slider
`seqTimeoutMs` 200–1000. Detecção de conflito existente (hub:473-481) estendida:
mesmo 1º botão em duas sequências = aviso, não bloqueio. Strings EN + pt-rBR.

### 2.5 Catálogo

`ProfileCatalog.summaryOf`: triggers com modos novos contam na categoria LAYER
existente. `sync_profile_repo.py` allowlist: `sequence` (lista de strings curtas),
`longPressMs`/`seqTimeoutMs` (ints) — regenerar 2× → diff vazio.

## 3. Arquivos

| Arquivo | Mudança |
|---|---|
| `gamepad/layers/TriggerEngine.kt` | NOVO (2.2, puro) |
| `gamepad/layers/LayerTriggerSpec.kt` | modos novos + campos default (2.1) |
| `gamepad/GamepadHub.kt` | pendingEmits + flush + consulta ao engine (2.3) |
| `gamepad/remap/GamepadRemapDialog.kt` | editor (2.4) |
| `gamepad/profiles/ProfileCatalog.kt` | summary (2.5) |
| `tools/profiles/sync_profile_repo.py` | allowlist (2.5) |
| `app/src/test/.../TriggerEngineTest.kt` | NOVO (§4) |
| `res/values*/strings.xml` | EN + pt-rBR |

## 4. Testes (JVM, puros)

`TriggerEngineTest`:
- LONG_PRESS: ativa no limiar (via onClock); soltar antes = None (nem ativa nem
  deixa vazar); ativar e soltar = Deactivate; limiar configurável.
- SEQUENCE: A→B completa (Activate) com retardo de A descartado; A→C = botão
  errado mata e LIBERA o A retardado; A + timeout = libera A; A→B→C de 3 passos;
  timeout POR PASSO (A→B ok, demora no C = morre no C).
- Overlap: A→B e A→B→C: completar A→B→C ativa só a longa; só A→B completada = a
  curta ativa, longa morta no botão extra.
- Degradação: sem specs novas ⇒ todos os outcomes None (LayerResolver intocado —
  testar HOLD/TOGGLE/DOUBLE_TAP existentes continuam verdes).
- State V6: reset do engine no removeDevice (se coberto por teste de hub existente).

## 5. Gate

```
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:testModernDebugUnitTest --tests "*Trigger*" --tests "*Layer*" --tests "*Gamepad*" --offline
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:assembleModernDebug --offline
python3 tools/profiles/sync_profile_repo.py && python3 tools/profiles/sync_profile_repo.py && git diff --exit-code -- tools/profiles/
```
On-device pendente (humano, Silksong): SELECT→SELECT→FACE_TOP abre radial sem
"vazar" o primeiro SELECT no jogo; long-press de L3 = camada sniper.

## 6. Não-metas

Chords paralelos (Parallel do key-mapper — o fork já tem camadas HOLD simultâneas),
constraints do key-mapper (app foreground etc. — o fork já resolve por appId),
expressões nos triggers (spec J), timers/coroutines (flush por evento), tocar
`XServerScreen.kt`.

## 7. Critério de conclusão (para o goal)

Gate verde + commit `feat(gamepad): trigger engine LONG_PRESS/SEQUENCE com disparo retardado (spec 2026-08-16-I-trigger-engine-keymapper)` + impl doc `<spec>-impl.md` + checkpoint na tabela §6 do roadmap mestre.
