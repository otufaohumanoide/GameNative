# Impl doc — Spec 2026-08-16 D (touchpad: swipes direcionais → macros / abrir radial)

**Data:** 2026-08-16
**Spec:** `docs/spec-2026-08-16-D-touchpad-swipes-macros.md` (executor: agente autônomo)
**Base:** impl doc 2026-08-14 (`spec-2026-08-14-gamepad-u2-touchpad-mouse-impl`/P2-6 —
TouchpadProcessor + forwarder) e fase F (RadialMenuExecutor/RadialMenuHost finais).
**Resultado:** implementado, gate completo verde, commit `feat(gamepad): …` (ver §6 do
master roadmap). Verificação on-device pendente (protocolo humano na §5).

## 1. O que foi feito (por seção do spec)

### §1.1 Swipe PURO no `TouchpadProcessor` (`gamepad/processing/TouchpadProcessor.kt`)

- Config nova em `TouchpadConfig`: `swipeEnabled = true`, `swipeMinDistance = 0.22f`,
  `swipeMaxMs = 300L` (file:lines 66-70) — null-default em todo canto, OFF =
  decisão idêntica à atual.
- `enum class SwipeDir { UP, UP_RIGHT, RIGHT, DOWN_RIGHT, DOWN, DOWN_LEFT, LEFT,
  UP_LEFT }` (file:line 119) — ordem = setores no sentido horário a partir de 0°
  (cima); os NOMES são as chaves de `GamepadProfile.touchpadSwipes`.
- `TouchpadDecision.swipe: SwipeDir? = null` (file:line 106) — chamadores existentes
  byte-identical (o campo tem default; os 6 primeiros argumentos inalterados).
- Decisão NO UP, ANTES das regras de tap/duplo-toque (file:lines 177-182):
  `swipeDirection` (file:lines 216-226) — duração down→up ≤ swipeMaxMs E norma
  euclidiana do vetor start→end ≥ swipeMinDistance → 8 setores via
  `RadialMenuGeometry.angleOf` + `sectorIndex(count=8)` (reuso do radial, convenção
  0°=cima). Um gesto, uma decisão: swipe SUPRIME tap/drag-release/rightClick e o
  delta de mouse daquele up (retorno antecipado com campos false + swipe).
- Desambiguação estrutural testada: drag (≥650 ms) nunca é swipe (janela ≤300 ms);
  duplo-toque exige 2 taps PARADOS (desloc ≤0.06 ≪ 0.22); tap rápido parado continua
  tap (distância 0 < swipeMinDistance). Deltas do percurso continuam fluindo DURANTE
  o move (a decisão de swipe só existe no up).

### §1.2 Perfil — `gamepad/profiles/GamepadProfile.kt` + `GamepadProfileStore.kt`

- `GamepadProfile.touchpadSwipes: Map<String, List<RadialMacroKey>>? = null`
  (GamepadProfile.kt:59) — keys = nomes do SwipeDir; null/vazio = OFF.
- `isDefault()`: `touchpadSwipes.isNullOrEmpty()` entra no default (GamepadProfile.kt:110)
  — mapa vazio salva como default (remove a entrada), mesma convenção de layers.
- `merged()`: união por direção com o JOGO vencendo (GamepadProfileStore.kt:151-154)
  — o jogo nunca apaga direções do device; null preserva o outro lado.
- Store: nada a mudar — serialização automática + política V1 (chaves desconhecidas
  preservadas no save) cobre o campo novo.

### §1.3 Forwarder → ação — `gamepad/GamepadTouchpadForwarder.kt` + bus + host

- `decision.swipe != null` → lookup `profile.touchpadSwipes[swipeDir.name]` no perfil
  EFETIVO do device (`hub.profileFor` — merge device→jogo; file:lines 110-120):
  - lista de `RadialMacroKey` → `swipeExecutorSink.execute(keys, deviceId)` — o jogo
    NÃO pausa (macros caem no caminho do jogo, igual ao radial pós-execução);
  - lista com 1 `RadialMacroKey(SWIPE_OPEN_RADIAL)` → `PluviaApp.events.emit(
    GamepadSwipeEvent(deviceId))` — o pause/resume par-e-par é do RadialMenuHost;
  - sem binding para a direção → `return true` imediato (consumido, NADA acontece —
    nem delta de mouse no up, pois os deltas do percurso já fluíram durante o move).
- `SwipeExecutorSink` fun-interface + `NoopSwipeExecutorSink` (file:lines 54, 153) —
  o forwarder é app-scoped e NÃO tem Activity; a implementação real é INJETADA pelo
  MainActivity (mesmo padrão de injeção do `TouchpadMouseSink`):
  `PluviaApp.gamepadTouchpad.swipeExecutorSink = … RadialMenuExecutor.execute(keys,
  deviceId, this@MainActivity)` (MainActivity.kt:212-215).
- `GamepadSwipeEvent(deviceId)` em `events/GamepadBusEvents.kt:34` (main thread,
  síncrono, como o GamepadLayerEvent).
- `RadialMenuHost`: listener do swipe (file:lines 112-130) — MESMO ciclo de abertura
  do caminho de camada (pause/resume par-e-par, `pausedByRadial`), SEM exigir
  triggerLayer configurado; HOLD mantém o painel sem pausar (pausa só em TAP_RELEASE);
  menu já aberto ignora (preserva o pausedByRadial — um segundo swipe re-setaria o
  flag e quebraria o resume par-e-par).
- `SWIPE_OPEN_RADIAL = -1000` (`gamepad/radial/RadialMenuCore.kt:25`) — keyCode
  RESERVADO fora do range Android real; o forwarder intercepta ANTES do executor,
  nenhum KeyEvent sintético carrega o valor.

### §1.4 UI — `gamepad/remap/GamepadRemapDialog.kt`

- Seção "Swipes" só com `device.hasTouchpad` (capability V11 — some sem touchpad
  físico, nunca mostra erro; file:lines 923-965): 8 linhas `SwipeBindingRow` (um
  SwipeDir cada) com rótulo localizado, ação atual (macro "X → Y → Z" / "Abrir
  radial" / "Nada"), captura de macro e atalho "Abrir radial".
- Captura de macro (file:lines 559-592): bus CRU do deviceId no padrão do
  RadialMenuEditorDialog — teclas CONCATENAM enquanto a captura está ativa (macro de
  N teclas); BACK/ESCAPE encerra; `dismissOnBackPress = visualCapture == null &&
  captureSwipe == null` (file:line 627) e `GamepadFocusScope` desliga durante a
  captura (file:lines 635-644). Mutuamente exclusiva com as capturas existentes
  (todo início de captura zera captureSwipe; o início da captura de swipe zera as
  demais — file:lines 946-951).
- Save: `touchpadSwipes = if (swipeBindings.isEmpty()) null else swipeBindings`
  (file:line 187); import SAF re-popula o estado (file:line 212); export/import
  cobertos pela serialização automática do campo.
- Strings EN (`values/strings.xml`) + pt-rBR (`values-pt-rBR/strings.xml`): 12 chaves
  novas (título/subtítulo, 8 direções, "Nada", "Aperte as teclas… (BACK encerra)",
  "Abrir radial").

## 2. Arquivos afetados

| Arquivo | Mudança |
|---|---|
| `gamepad/processing/TouchpadProcessor.kt` | swipe puro (§1.1) |
| `gamepad/profiles/GamepadProfile(+Store).kt` | `touchpadSwipes` + merge/isDefault (§1.2) |
| `gamepad/GamepadTouchpadForwarder.kt` | dispatch de swipe + sink injetável (§1.3) |
| `MainActivity.kt` | injeção do SwipeExecutorSink (§1.3) |
| `events/GamepadBusEvents.kt` | `GamepadSwipeEvent` (§1.3) |
| `ui/component/radial/RadialMenuHost.kt` | listener do swipe→abrir (§1.3) |
| `gamepad/radial/RadialMenuCore.kt` | `SWIPE_OPEN_RADIAL` (§1.3) |
| `gamepad/remap/GamepadRemapDialog.kt` | seção Swipes (§1.4) |
| `res/values*/strings.xml` | chaves (§1.4) |
| `TouchpadProcessorTest` + `GamepadProfileStoreTest` | casos novos (ver §3) |

## 3. Testes

- `TouchpadProcessorTest` (24 testes, 0 falhas) — 9 novos de swipe: direito suprime
  tap; 8 direções; tap rápido parado ≠ swipe; drag lento longo ≠ swipe; flick rápido
  fora da janela ≠ swipe ≠ tap; flick curto < distância mínima ≠ swipe; duração
  exatamente no limite = swipe; swipe suprime rightClick na janela do duplo-toque;
  swipe OFF = decisão idêntica à atual; deltas fluem durante o move e param no up.
- `GamepadProfileStoreTest` (22 testes, 0 falhas) — 3 novos: roundtrip + null default;
  merge = união por direção com o jogo vencendo; detecção de default inclui swipes
  (vazio = OFF, save remove a entrada).

## 4. Desvios (com justificativa)

- **MainActivity entra na lista de arquivos** (spec §2 não listava): o forwarder é
  app-scoped sem Activity; a injeção do sink pelo MainActivity é o MESMO padrão do
  `TouchpadMouseSink` (U2/P5-6) — sem isso o executor ficaria no-op. "A última janela
  criada vence" — mesma política do forwarder compartilhado.
- **Captura de macro na UI = padrão do RadialMenuEditorDialog (bus cru), não o editor
  inteiro**: o spec pedia "reuso do editor do radial" — reusamos o PADRÃO de captura
  (concatenação de teclas, BACK encerra) dentro do GamepadRemapDialog, como as demais
  capturas do dialog; embutir o editor inteiro criaria uma segunda superfície
  sobreposta com foco próprio (contra a regra do repo: uma superfície, um dono).

## 5. Verificação (gate)

```
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:testModernDebugUnitTest --tests "*Touchpad*"
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:assembleModernDebug
```
→ TouchpadProcessorTest 24/24 verdes; extra fora do filtro (regra do spec — classe
que não casa com `*Touchpad*`): `--tests "*GamepadProfileStore*"` → 22/22 verdes;
`assembleModernDebug` OK (dex do XServerScreen intocado — ZERO locals novas).

On-device (pendente — protocolo consolidado do fechamento 2026-08-16, §2 linha D):
`setprop debug.gamenative.input touch:0.5:0.5` + swipe sintético rápido vs arrasto
lento → macro/radial dispara; arrasto/duplo-toque intactos.

## 6. Invariantes respeitadas

- Degradação byte-identical: `swipeEnabled=false`/`touchpadSwipes=null`/vazio =
  caminho EXATO do P2-6 atual (testes de default por peça); campo novo null-default
  no schema; store preserva chaves desconhecidas (V1).
- Lógica pura em `gamepad/processing` sem android.* (SwipeDir/TouchpadConfig/
  TouchpadProcessor) — testável em JVM.
- `XServerScreen.kt`: ZERO linhas tocadas; a UI nova vive no GamepadRemapDialog.
- Um gesto, uma decisão: swipe nunca gera tap+delta+rightClick no mesmo up.
- Strings EN + pt-rBR; commits em PT-BR referenciando o spec.
