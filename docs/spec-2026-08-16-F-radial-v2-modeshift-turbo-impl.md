# Impl doc — Spec 2026-08-16 F (Radial v2: submenus/ícones/hold + Mode Shift + Turbo)

**Data:** 2026-08-16
**Spec:** `docs/spec-2026-08-16-F-radial-v2-modeshift-turbo.md` (executor: sub-agente autônomo)
**Base:** impl doc 2026-08-15 (`docs/spec-2026-08-15-input-core-avancado-impl.md`,
§F3 — radial v1; desvios 5/6/7).
**Resultado:** implementado, gate completo verde, commit `feat(gamepad): …` (ver §6 do
master roadmap). Verificação on-device pendente (protocolo humano na §5).

## 1. O que foi feito (por seção do spec)

### §1.1 Schema v2 (`gamepad/radial/RadialMenuCore.kt`)

- `RadialSector.children: List<RadialSector> = emptyList()` (file:line 40) e
  `RadialSector.iconKey: String? = null` (file:line 46) — campos novos com default;
  JSON v1 antigo carrega normal (testado).
- `RadialMenuConfig.schemaVersion: Int = 2` (file:line 60) e
  `RadialMenuConfig.executeMode: ExecuteMode = TAP_RELEASE` (file:line 62).
- `enum class ExecuteMode { TAP_RELEASE, HOLD }` (file:line 113) — TAP_RELEASE é o
  caminho v1 byte-identical; HOLD resolve a deviação nº 6 do impl doc 2026-08-15
  (execução fechava o menu mesmo com HOLD segurado).
- `sanitized()` (file:lines 73-89): sanitização NO LOAD — `iconKey` fora da
  allowlist vira null (label só) e `children` dos FILHOS são zerados recursivamente
  (submenu de 1 nível; netos e além descartados — nunca crash com JSON malformado,
  risco §6). Idempotente (roundtrip testado). Chamado em `fromJson` (file:line 95).
- `ICON_ALLOWLIST` (file:lines 103-106): os 16 nomes do spec (sword, potion, map,
  bag, run, gear, heart, star, home, save, load, camera, chat, trade, craft, fight).

### §1.2 Overlay/Host/Editor

**`ui/component/radial/RadialMenuOverlay.kt`** (rewrite do arquivo v1):

- Nível de submenu: `var level by remember { mutableStateOf<Int?>(null) }`
  (file:line 89); `sectors = children do setor raiz quando level != null, senão os
  setores raiz` (file:line 90) — re-render com os filhos (1 nível; o parser zera
  children nos filhos). Estado POR NÍVEL via `remember(level)` (selected, tick, janela
  do stick, holders do vetor, anti-repeat — file:lines 95-111; lição C1: nada de
  estado stale ao trocar de nível).
- `activateSector` (file:lines 112-125): pai abre a sub-roda (`level = sector`);
  folha executa via `onExecute`. `select` (file:lines 137-151): tick F2.3 por
  mudança + HOLD executa NA MUDANÇA de setor (folhas apenas — abrir pai no meio do
  slide mataria o gesto em curso; pai abre no release/A).
- Anti-repeat do HOLD: `executeHoldIfNeeded` (file:lines 128-136) — executa só se o
  setor é NOVO e com a janela de 120 ms respeitada (mesma janela do stick; touch =
  mesmo critério). O release do touch chama o mesmo gate (garante o setor final sem
  re-executar).
- Voltar: B (`FACE_RIGHT`) no submenu → `level = null`; na raiz → `onCancel`
  (file:lines 187-190). Toque sem arrasto: submenu → volta; raiz → cancela
  (file:lines 266-268). Glifo central: `↩` no submenu / `✕` na raiz (file:lines 334-336).
- Stick fallback: A executa/abre; HOLD usa o anti-repeat em vez de executar de novo
  (file:lines 191-199). Touch release: pai abre; HOLD garante o setor final;
  TAP_RELEASE executa (file:lines 246-262).
- Ícone ACIMA do label: `RadialMenuIcons.vectorFor(sector.iconKey)` → `Icon` 18 dp
  (file:lines 310-322) — allowlist → Material icon, NUNCA asset; fora da allowlist o
  parser já normalizou a null.

**`ui/component/radial/RadialMenuIcons.kt`** (NOVO): mapa nome → `ImageVector`
(file:lines 34-61, `vectorFor` file:line 64). Sem ícone literal de espada no
Material Icons: sword → SportsMartialArts, potion → Science, fight → SportsMma,
trade → SwapHoriz, craft → Build, load → FileOpen (decisões comentadas no arquivo).

**`ui/component/radial/RadialMenuHost.kt`** — ciclo HOLD:

- Abertura com HOLD: `pausedByRadial = false` e SEM `pauseGame()` (file:lines 82-87)
  — painel persistente sobre o jogo RODANDO (os macros executados no meio do jogo
  precisam chegar ao jogo — ver deviação D1 abaixo).
- `onExecute` com HOLD: executa SEM fechar e SEM retomar (file:lines 113-118);
  `GamepadLayerEvent(false)` (listener existente, file:lines 95-99) é quem fecha.
  TAP_RELEASE = caminho v1 intacto (fecha + retoma antes de executar, file:lines
  119-128).

**`ui/component/radial/RadialMenuEditorDialog.kt`** (rewrite):

- Toggle do executeMode: chips TAP_RELEASE/HOLD (file:lines 266-292).
- Grade de ícones por setor: `IconChip` None + 16 vetores da allowlist
  (file:lines 405-434, `SectorEditorRow` file:lines 380-453).
- "Transformar em submenu" (`promoteToSubmenu`, file:lines 122-138): promove o setor
  a pai — a macro atual vira o PRIMEIRO filho (label/ícone herdados). Filhos
  editáveis (rótulo/ícone/captura de macro por CAMINHO `(top, child?)` — `capturePath`
  file:line 79, listener file:lines 171-192) + adicionar/remover filho
  (file:lines 147-166) + "Remover submenu" (file:lines 142-145). Captura via bus cru
  (mesmo padrão v1/GamepadRemapDialog).
- Save preserva children/iconKey/executeMode (file:lines 466-475).

### §1.3 Mode Shift (`gamepad/layers/*` + `GamepadHub.kt`)

- `LayerTriggerSpec.isShift: Boolean = false` (LayerTriggerSpec.kt file:line 30) —
  @Serializable com default: JSON v1 preserva (testado).
- Decisão PURA no resolver (testável em JVM): `LayerResolver.suppressCommonEvents`
  (LayerResolver.kt file:line 56). O motor de ativação NÃO mudou (HOLD/TOGGLE/
  DOUBLE_TAP idênticos — branch preserva a mecânica U3, testado).
- Hub: `resolveLayerTriggers` retorna Boolean (GamepadHub.kt file:lines 329-380) —
  com `isShift`: NÃO emite `GamepadLayerEvent` (não abre radial, não compete com
  triggers reais), NÃO dá tick háptico (file:lines 360-377) e o evento físico é
  CONSUMIDO (retorno true; os chamadores não chamam `emitLogical` — onKey
  file:lines 646-652, onAxis file:lines 690-696). Camada comum = pass-through
  inalterado (deviação nº 6 do impl doc 2026-08-15 preservada).
- Remap pela camada shift = `effectiveBindings` EXISTENTE (nenhuma mudança no
  resolver comum — teste dedicado). Estados V6: `layerStates.remove(deviceId)` já
  existia no `removeDevice` (file:line 794) — nada novo necessário.
- UI: toggle "Camada de shift" no editor de camadas do `GamepadRemapDialog`
  (file:lines 678-720, título/legenda das strings novas); re-captura do trigger
  PRESERVA `isShift` (file:lines 431-441).

### §1.4 Turbo/rapid-fire

**`gamepad/processing/TurboScheduler.kt`** (NOVO, puro — zero android.*):

- `PERIOD_DEFAULT_MS = 80L` (file:line 18), `MIN_PERIOD_MS = 2L` (file:line 21),
  `nextToggleAt(nowMs, periodMs, phase)` (file:lines 28-29): `nowMs + período/2`
  (onda quadrada duty 50%; ciclo completo down→up→down = 80 ms). `phase` documenta
  o estado lógico (0 = solto → próximo toggle é DOWN; 1 = segurado → próximo é UP)
  e alterna a cada toggle; período degradado clampado (nunca exceção).

**`gamepad/remap/GamepadBindingCodec.kt`**:

- `LayerBinding(raw, turbo)` (file:line 25) — o flag vive no TOKEN (a fonte física é
  a mesma; a camada decide se o alvo pulsa). `encode(binding, turbo = false)`
  (file:lines 27-34): `:turbo` opcional — default OFF = token byte-identical ao v1
  (testado). `decode` (file:lines 37-60): sufixo `:turbo` → flag; tokens legados →
  `turbo = false`; malformado → null (degrade). `conflicts` inalterado (RawBinding).

**`ui/screen/xserver/PhysicalControllerHandler.kt`** (arquivo próprio — ZERO
alterações no XServerScreen.kt):

- `turboStates: MutableMap<Int, MutableMap<String, TurboToggleState>>`
  (file:line 75) — deviceId → fonte (GamepadButton.name LÓGICO — a MESMA chave no
  caminho de tecla e no caminho de eixo; triggers digitais+analógicos não criam
  ciclo duplo) → fase/alvo/agendamento.
- `injectBinding` (file:lines 710-717): extração do MESMO caminho de injeção do U4
  (`handleInputEvent` + `sendGamepadState`/`sendVirtualGamepadState`) — o ciclo de
  turbo reusa EXATAMENTE este caminho, nada de rota nova.
- DOWN físico com turbo: `startTurbo` (file:lines 726-737) — DOWN lógico imediato +
  `scheduleTurboToggle` (file:lines 744-762) no Handler MAIN (`mainHandler`,
  file:line 73): cada disparo alterna a fase e re-agenda meio período
  (`TurboScheduler.nextToggleAt`). UP físico: `stopTurbo` (file:lines 768-773) —
  cancela o agendamento (`removeCallbacks`) + release lógico limpo (mesma
  disciplina do `remappedAxisBindings`).
- `applyUniversalKeyRemap` brancheia por `decoded.turbo` (file:lines 239-262);
  `handleRemappedAxis` ganhou `sourceKey` (GamepadButton.name lógico, file:line 667)
  e o branch turbo (file:lines 680-690): pressed → start, released → stop — o ciclo
  é DONO da fonte enquanto ativo (onda quadrada digital; o valor analógico só
  liga/desliga).
- V6: `deviceRemovedListener` registrado no init (file:lines 79-88) — estados de
  turbo morrem no `GamepadDeviceRemovedEvent` do hub (`removeDevice`); desregistrado
  no `cleanup()` (file:line 145), que também cancela ciclos pendentes + release
  limpo (file:lines 136-144). Perfil novo (`setProfile`) mantém o listener (único
  por handler, identity-registry do bus).

**`gamepad/remap/GamepadRemapDialog.kt`** — UI:

- Toggle "Turbo" no chip de binding da camada (`RemapRow`, file:lines 1124-1143):
  `setTurbo` re-encoda o token com/sem sufixo (file:lines 305-313); captura NOVA
  zera o flag (default OFF). Período fixo 80 ms v2 (fora de escopo: período
  configurável na UI).

### Strings (EN + pt-rBR)

14 chaves novas em `res/values/strings.xml` e `res/values-pt-rBR/strings.xml`
(bloco "F (spec 2026-08-16-F…)": radial_menu_execute_mode_*,
radial_menu_icon_*, radial_menu_submenu_*, gamepad_layer_shift_*,
gamepad_binding_turbo_title).

## 2. Desvios do spec (decisões registradas — com justificativa)

1. **"Binding.turbo" virou `LayerBinding` (codec), não um campo em `RawBinding`.**
   O spec usa "Binding" como conceito do binding de CAMADA; a classe
   `com.winlator.inputcontrols.Binding` (enum do virtual gamepad) já ocupa esse nome
   nos arquivos de injeção. O flag vive no TOKEN serializado (o `RawBinding` continua
   sendo só a fonte física — conflicts/mapping intactos) e o default OFF mantém o
   token byte-identical ao v1.
2. **HOLD é painel persistente SEM pausa do jogo.** O spec diz "o host NÃO fecha nem
   retoma" — para os macros executados no meio do jogo chegarem ao jogo, o host
   também NÃO PAUSA ao abrir (com o jogo pausado, o dispatch cairia no contexto
   OVERLAY e os macros não alcançariam o jogo — exatamente o defeito que a deviação
   nº 6 documenta). Consequência registrada no spec §3 ("Nota do impl"): em HOLD o
   stick/trigger continuam pass-through ao jogo (semântica de painel). Registrado no
   spec §3.
3. **Submenu em HOLD abre no release (touch)/A (stick), não na MUDANÇA de seleção.**
   O spec manda folhas executarem na mudança de setor (anti-repeat 120 ms); para
   PAIS, abrir no meio do slide cancelaria o gesto em curso (o re-render troca o
   `pointerInput` e mata o `awaitEachGesture`). Abrir no release/A mantém o gesto
   vivo e o mesmo resultado. Executar folha continua na MUDANÇA (spec literal).
4. **Chave do ciclo de turbo = GamepadButton.name lógico, não o keycode/eixo cru.**
   O spec pede `chaveDoEixoOuBotao`; a chave LÓGICA é o mesmo identificador nos dois
   caminhos físicos (KeyEvent digital + MotionEvent analógico dos triggers) — sem
   ela, um trigger com turbo iniciaria DOIS ciclos (um por caminho) e os toggles
   intercalariam. Mesma semântica, chave unificada.
5. **Remover submenu descarta os filhos** (sem mesclar macros de volta ao pai) — o
   spec não define o caminho inverso de "transformar em submenu"; descarte é o
   comportamento mais previsível e o botão é explícito.
6. **Turbo em alvo-hat não pulsa** (consumido sem injeção) — decisão U4 v1 existente
   (dpad via canal de tecla) preservada; turbo só faz sentido onde há injeção.
7. **`LayerResolver.suppressCommonEvents(spec)` como helper puro** — o branch pedido
   fica no hub (resolveLayerTriggers), mas a decisão em si é extraída pura para ser
   testável em JVM (o gate `*LayerResolver*` cobre o branch).
8. **Onda quadrada 50% (toggle a cada meio período).** O spec dá só o período fixo
   de 80 ms; ciclo completo down→up→down = 80 ms é o rapid-fire clássico
   (DS4Windows). `phase` seleciona a BORDA que o handler aplica no instante retornado.

## 3. Verificação (gate)

### 3.1 JVM (feita — nunca a suíte inteira, AGENTS.md)

```
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:testModernDebugUnitTest --tests "*Radial*" --tests "*LayerResolver*" --tests "*Turbo*"
```
→ **34 testes, 0 falhas** (RadialMenuCoreTest 17, LayerResolverTest 12,
TurboSchedulerTest 5). Extra fora do filtro do gate (regra do spec — classe que não
casa com os filtros): `--tests "*GamepadBindingCodec*"` → **8 testes, 0 falhas**
(codec refatorado para LayerBinding).

### 3.2 Build (feita)

`JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:assembleModernDebug` →
OK (librashader de fonte; dex do XServerScreen intocado — ZERO locals novas).

### 3.3 On-device (pendente — registrado no spec §3 "Nota do impl")

Protocolo humano no Mi 11 + DS4/Silksong (padrão do repo): config v1 antiga carrega
normal; submenu abre/retorna (release/B); HOLD mantém o menu aberto executando por
mudança de setor e fecha no release do gatilho; shift consome o botão físico e
remapeia sem abrir radial; turbo alterna no jogo enquanto segura e solta limpo.

## 4. Riscos acompanhados

- Dex do XServerScreen: ZERO linhas tocadas — toda a fase F vive nos arquivos
  próprios do hotspot radial + PhysicalControllerHandler/GamepadHub/GamepadRemapDialog
  (padrão respeitado).
- Degradação byte-identical: children vazio / iconKey null / isShift false / turbo
  false / executeMode TAP_RELEASE = comportamento EXATO do v1 (testes de roundtrip e
  default por peça); config v1 antiga carrega normal (teste dedicado).
- `build/` stale: build limpo usado nas verificações (sem indício de classe fantasma;
  compile fresco de todos os arquivos tocados).
- Captura de trigger preserva `isShift` (regressão testada por leitura do código —
  sem teste JVM: a captura vive na UI).
