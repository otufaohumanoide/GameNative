# Impl doc — Spec 2026-08-16 I (Trigger engine: LONG_PRESS/SEQUENCE — port key-mapper)

**Data:** 2026-08-16
**Spec:** `docs/spec-2026-08-16-I-trigger-engine-keymapper.md`
**Executor:** Prime Agent (fase I do master roadmap input avançado 2026-H2; o
sub-agente delegado parou sem escritas — assumido inline pelo orquestrador).
**Base:** fase H commitada (`10526ba5` + checkpoint `a2901b1b`).
**Resultado:** implementado, gate completo verde. On-device pendente (humano, §6).

## 1. O que foi feito (por seção do spec)

### §2.1 Modelo — `gamepad/layers/LayerTriggerSpec.kt`

- `enum LayerTriggerMode { HOLD, TOGGLE, DOUBLE_TAP, LONG_PRESS, SEQUENCE }`
  (file:50).
- Campos novos com default: `longPressMs: Int = 500` (file:43),
  `sequence: List<String> = emptyList()` (file:45), `seqTimeoutMs: Int = 400`
  (file:47) — JSON v1 preserva (kotlinx + `ignoreUnknownKeys` do store, política
  V1). `merged()`/`isDefault()` do GamepadProfile INTOCADOS — o merge de
  `layerTriggers` é por chave de camada (`base.layerTriggers + override.layerTriggers`,
  GamepadProfileStore) e o spec inteiro viaja como valor (nada a atualizar).

### §2.2 Motor puro — `gamepad/layers/TriggerEngine.kt` (NOVO)

- `TriggerEngineState` (file:27): `longPressArmed`/`longPressDownAt`/
  `longPressActivated` (um long-press por vez — o ÚLTIMO botão que armar vence,
  documentado), `seqProgress`/`seqStepDownAt` POR CAMADA (overlap exige progresso
  independente), `heldSeqButtons` (botões com Down consumido — o Up é consumido
  para balancear) e `pendingActivate` (completamento retardado do overlap).
- `TriggerOutcome` (file:54): `None`, `Activate`, `Deactivate`, `DelayEmit(button,
  untilMs)`, `ReleaseDelay(button)`, `ConsumeDelay(button)`, `Consume`.
  **Desvio registrado:** o sketch do spec não tinha `ReleaseDelay`/`ConsumeDelay`/
  `Consume` — necessários para o hub saber liberar/descartar a fila e marcar
  consumo do evento; a semântica segue o spec §2.2/§2.3 (resolução ⇒ descarta ou
  libera; consumo dos botões da sequência enquanto a decisão pendente vive).
- Semânticas portadas do key-mapper (KDoc cita ClickType.kt e KeyMapAlgorithm.kt
  com linhas — clean-room, nada copiado):
  - **LONG_PRESS** (`onButtonDown` file:85): down arma e CONSUME (isShift
    implícito); `onClock` (file:190) dispara `Activate` no limiar
    `longPressMs`; up antes do limiar = nada (nem ativa, nem vaza — fallback
    `performActionsOnFailedLongPress`); up depois = `Deactivate` (`onButtonUp`
    file:149).
  - **SEQUENCE**: ordem importa, timeout POR PASSO (não total — o passo aceito
    re-arma `seqStepDownAt`); completar ⇒ `Activate` + `ConsumeDelay` do 1º botão;
    botão errado OU vencimento do passo (`dieSequence` file:229) ⇒ `ReleaseDelay`
    do 1º botão (disambiguação #1386: o short-press do 1º botão só dispara se a
    sequência morrer); botão da sequência fora de ordem é consumido (tecla do
    trigger pendente — key-mapper consome as teclas do trigger enquanto pendente);
    botão ALHEIO ao trigger morre a sequência mas PASSA ao jogo.
  - **Overlap** (mesmo 1º botão): completamento com sequência MAIS LONGA pendente
    ⇒ ativação RETARDADA (`pendingActivate`); a longa completar descarta a curta
    (só a longa ativa); a longa morrer (botão errado/timeout) ativa a curta; o
    MESMO retardo cobre as duas (`ConsumeDelay` vence `ReleaseDelay` por ordem
    determinística no hub).
- **LayerResolver intocado**: só o branch `else -> None` de exaustividade do enum
  estendido (file:64 do LayerResolver.kt) — HOLD/TOGGLE/DOUBLE_TAP byte-identical
  (LayerResolverTest 12/12 verde).

### §2.3 Retardo no hub — `gamepad/GamepadHub.kt`

- `triggerEngineStates` + `pendingEmits` por device (file:356); `PendingEmit`
  (file:1403) guarda a LISTA de eventos do botão retardado (Down e — se o usuário
  soltar com a decisão viva — o Up entra NA MESMA fila: o par sai junto na
  liberação, nunca um Down fantasma sem Up).
- `resolveLayerTriggers` (file:411): engine consultado ANTES do resolver — para
  botões dos specs novos E para TODO botão enquanto há sequência pendente (botão
  errado mata). Outcomes aplicados com `ConsumeDelay` ANTES de `ReleaseDelay`
  (ordem determinística — um completamento vence a morte da sobreposta).
- `applyTriggerOutcome` (file:518): Activate/Deactivate mexem no
  `layerState.activeLayer` (mesma semântica de tick + GamepadLayerEvent do U3,
  respeitando `isShift`); DelayEmit/Consume/Release/ConsumeDelay operam a fila;
  ReleaseDelay emite via `emitLogical` (o remap da camada ativa aplica no evento
  atrasado).
- **Flush SEM timer novo** — `flushTriggerClock` (file:484) no TOPO de
  `onKey`/`onAxis`/`onSensorSample`: `TriggerEngine.onClock` (long-press no
  limiar + vencimento de passos) + varredura da fila por prazo. Sem engine armado
  e fila vazia ⇒ retorno imediato (zero custo — byte-identical). Os eventos de
  input são o relógio (~120 Hz stick / ~50 Hz sensor — o spec autoriza).
- V6: `triggerEngineStates`/`pendingEmits` mortos no `removeDevice` (file:1336) e
  limpos na troca de container `setActiveAppId` (file:194 — a ativação reinicia
  fechada com o perfil novo, padrão G5/V6).

### §2.4 UI — `gamepad/remap/GamepadRemapDialog.kt`

- `sequenceDraft` (file:135): captura encadeada da SEQUENCE — a 1ª tecla vira o
  `button`, as 2 seguintes viram os passos NA ORDEM (máx 3 botões; BACK/ESCAPE
  encerra; progresso "Botão N/3" no status — file:529).
- Chips LONG_PRESS/SEQUENCE na segunda fileira de modos (5 chips não cabem numa
  linha); rótulo da sequência mostra a cadeia completa com glifos
  ("A → B → C · Sequência").
- Sliders: `longPressMs` 200–1500 (file:1993) e `seqTimeoutMs` 200–1000
  (file:2002) via `GyroSliderRow` (gamepad-navegável, padrão do dialog).
- Conflito estendido (file:510): mesmo 1º botão em duas SEQUÊNCIAS = AVISO
  (`gamepad_layer_sequence_overlap_warning`) e NÃO bloqueio — o engine resolve o
  overlap (a mais longa que completar vence); os demais conflitos continuam
  bloqueando como antes.

### §2.5 Catálogo

- `ProfileCatalog.summaryOf`: SEM mudança necessária — `layerTriggers` não-vazio
  já conta na categoria LAYER existente (qualquer modo). Registrado.
- `tools/profiles/sync_profile_repo.py`: `LAYER_TRIGGER_MODES` +=
  LONG_PRESS/SEQUENCE (file:69); validação de `longPressMs`/`seqTimeoutMs`
  (ints) e `sequence` (lista de strings curtas, máx 2 passos — file:236).
- Seed `tools/profiles/seed/longpress-sequence-triggers.json`: SNIPER via
  LONG_PRESS (600 ms) + RADIAL via SEQUENCE SELECT→SELECT→FACE_TOP (400 ms/passo)
  — o asset regenerado (7 perfis) comprova os campos end-to-end. Determinismo:
  2× runs → md5 `e53b04e9…` idêntico.

## 2. Testes

- `TriggerEngineTest` (NOVO, 15 testes): long-press ativa no limiar via onClock
  (e não re-dispara), soltar antes = nada (só Consume — nem Activate nem
  Deactivate), ativar e soltar = Deactivate, limiar configurável; sequência
  completa descartando o retardo, botão errado (fora da lista) libera o retardo
  e NÃO consome, botão da sequência fora de ordem consome e mata, timeout sem
  passo libera, timeout POR PASSO (B aceito re-arma o relógio), 3 passos;
  overlap: longa que completa vence (curta retardada e descartada), curta ativa
  quando a longa morre no botão extra E no timeout; degradação (HOLD/TOGGLE/
  DOUBLE_TAP não passam pelo engine); up de botão alheio passa.
- Regressão: `LayerResolverTest` 12/12, `ProfileCatalogTest` 10/10,
  `GamepadProfileStoreTest` 25/25 verdes (filtros do gate + extras).
- V6 (reset no removeDevice): sem teste de hub JVM (o hub é Android-bound) —
  coberto pela convenção V6 existente do repo; registrado.

## 3. Gate (comandos e resultados)

```
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:testModernDebugUnitTest --tests "*Trigger*" --tests "*Layer*" --tests "*Gamepad*" --offline
  → BUILD SUCCESSFUL (TriggerEngineTest 15/15, LayerResolverTest 12/12)
+ ProfileCatalogTest (10/10) e GamepadProfileStoreTest (25/25) — extra
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:assembleModernDebug --offline
  → BUILD SUCCESSFUL
python3 tools/profiles/sync_profile_repo.py ×2 → md5 e53b04e9… idêntico (determinístico)
```

## 4. On-device pendente (humano — spec §6)

1. **SELECT→SELECT→FACE_TOP abre o radial sem "vazar" o primeiro SELECT** — o
   1º SELECT fica retardado; se a sequência completar ele NUNCA chega ao jogo.
2. **Long-press de L3 = camada sniper** — segurar 600 ms ativa; soltar antes não
   faz nada; o botão não chega ao jogo desde o down.

## 5. Desvios do spec (registrados)

1. **`TriggerOutcome` estendido** com `ReleaseDelay`/`ConsumeDelay`/`Consume` —
   o sketch do spec (§2.2) não tinha como o hub distinguir liberar vs descartar a
   fila nem marcar consumo do evento; a semântica segue o §2.2/§2.3 do spec.
2. **`seqProgress`/`seqStepDownAt` por CAMADA** (não um campo único) — o sketch
   tinha um campo só; overlap com 1º botão divergente (A→B vs A→C) exige
   progresso independente por camada.
3. **`delayedPending(state)` omitido** — a fila viva do hub (`pendingEmits`) É o
   registro de pendências e os prazos viajam em `DelayEmit.untilMs`; a função do
   sketch duplicaria estado sem consumidor.
4. **Ativação de LONG_PRESS/SEQUENCE emite tick + GamepadLayerEvent** como
   qualquer ativação U3 (o "isShift implícito" do spec cobre o CONSUMO do botão,
   não a supressão do evento de camada); `isShift` explícito continua suprimindo
   como antes.
5. **Re-capturar o 1º botão de uma SEQUENCE zera os passos** (captura sempre
   começa do zero) — previsível; reordenar passos existentes é follow-up.
