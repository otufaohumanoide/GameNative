# Spec 2026-08-13 — Onda 2: integração da camada universal de gamepads

**Data:** 2026-08-13
**Origem:** a camada universal (`app/gamenative/gamepad/`) está implementada, testada
(102 testes JVM verdes, `assembleModernDebug` OK) e aprovada na validação — mas **não
está ligada**: o `GamepadHub` não é instanciado, o gate `PrefManager.gamepadUniversalEnabled`
está desligado (default false) e os consumidores de menu continuam usando eventos crus.
Este spec define a integração (Onda 2 do spec 2026-08-13-gamepad-universal.md), o passo
que transforma a camada em benefício on-device.
**Decisões do usuário:** Onda 2 executada por UM agente serial (arquivos quentes
compartilhados); ordem E1 (menu) → E2 (jogo) → E3 (verificação); nenhuma feature nova
nesta onda — só ligar o que existe.
**Referências:** spec 2026-08-13-gamepad-universal.md (arquitetura/ownership),
spec 2026-08-13-gamepad-universal-correcao.md (contratos corrigidos), spec
2026-08-12-quickmenu-gamepad-pipeline-hardening.md (invariantes de roteamento).

---

## 0. Estado atual (o que entra nesta onda)

| Peça | Status | Onde |
|---|---|---|
| `GamepadHub` (listener único, StateFlows, tradutor, dedupe de transições) | pronto, sem wiring | `gamepad/GamepadHub.kt` |
| `EventTranslator`/`MappingDatabase`/`MappingParser` | prontos, testados | `gamepad/mapping/` |
| `DeadzoneProcessor`/`AnalogToDpad` | prontos, testados | `gamepad/processing/` |
| `GamepadProfileStore`/`ProfileResolver` | prontos, testados | `gamepad/profiles/` |
| `GamepadGlyphProvider`/`GamepadRemapDialog`/`GamepadBindingCodec` | prontos; diálogo sem caller | `gamepad/glyphs/`, `gamepad/remap/` |
| Wrappers de bus | prontos | `events/GamepadBusEvents.kt` |
| Keys do PrefManager (`gamepadUniversalEnabled`=false, `gamepadStickDeadzone`=0.15f, `gamepadMenuStickDeadzone`=0.45f, `gamepadSwapOkCancel`=false) | prontas | `PrefManager.kt:1455-1471` |
| Strings EN+pt-rBR (65 chaves `gamepad_*`) | prontas | `values/strings.xml`, `values-pt-rBR/strings.xml` |

**Correções menores pendentes (da validação):**
1. `GamepadHub.onAxis:122` passa `appId = null` — precisa do appId real (perfis por jogo).
2. `GamepadHub` não é instanciado — listeners antigos continuam em
   `MainActivity.kt:144-156, 216, 385` e `XServerScreen.kt:1464-1495`.
3. `GamepadHub.onKey` não distingue device TOUCHPAD (decisão desta onda).

---

## 1. Design da integração

### 1.1 Hub como singleton app-scoped

`PluviaApp` ganha `val gamepadHub: GamepadHub` (lazy, `start()` na primeira leitura;
`stop()` nunca — o app-scope vive até o processo morrer). Justificativa:
`MainActivity` tem MÚLTIPLAS instâncias (multi-janela/external display — `index` no
código); um hub por Activity registraria N listeners duplicados (o exato bug C3 que o
hardening eliminou). O hub é o ÚNICO dono do `InputDeviceListener`.

### 1.2 Wiring no MainActivity (o mínimo)

Em `dispatchKeyEvent`/`dispatchGenericMotionEvent`, DEPOIS do emit cru no bus e SEM
alterar a semântica de retorno:

```kotlin
// após PluviaApp.events.emit(...) do evento cru (multicast — o retorno do dispatch
// continua sendo decidido pelos listeners crus, NÃO pelo hub)
PluviaApp.gamepadHub.onKey(RawKeyInput(event.deviceId, event.source, event.keyCode, event.action, event.repeatCount))
PluviaApp.gamepadHub.onAxis(RawAxisInput(ev.deviceId, ev.source, ev.actionMasked, axisValuesDoEvent))
```

O adapter `KeyEvent`/`MotionEvent` → `RawKeyInput`/`RawAxisInput` fica em um arquivo
fino novo (`gamepad/mapping/AndroidInputAdapter.kt`) — **nunca** dentro do MainActivity
(limite do dex é do XServerScreen, mas a regra de higiene vale para todos). O gate de
ghost input (MainActivity:592-604, 645-653) CONTINUA ANTES do hub — eventos de touchpad
fantasma nunca chegam ao tradutor.

Remover o `controllerDeviceListener` antigo (MainActivity:144-156, 216, 385) e a
chamada `ControllerManager` associada — o hub passa a chamar
`ControllerManager.getInstance().onDeviceConnected/Disconnected` em `addDevice`/
`removeDevice` (comportamento atual preservado, agora centralizado).

### 1.3 Remoção do listener duplicado do XServerScreen (alivia o dex)

`XServerScreen.kt:1464-1495` (DisposableEffect com `InputManager.registerInputDeviceListener`)
é REMOVIDO. A tela passa a assinar os eventos do bus:

```kotlin
PluviaApp.events.on<GamepadDeviceAddedEvent, Unit> { ... }
PluviaApp.events.on<GamepadDeviceRemovedEvent, Unit> { ... }
```

com o mesmo corpo do callback antigo (`ControllerManager.onDeviceConnected/Disconnected`
— se o hub já não cobrir — `scanForExternalDevices()`, `evaluateDevice(device)`).
**Regra do dex:** a função principal do XServerScreen não ganha locals novas — o corpo
da assinatura vive em um helper privado ou em `remember`.

### 1.4 appId vivo (padrão OverlayInputState — lição C1)

Perfis por jogo precisam do `appId` do container no MOMENTO do evento, nunca capturado:

- XServerScreen escreve `PluviaApp.gamepadHub.activeAppId = container.id` durante a
  composição (holder vivo, padrão `OverlayInputState` — escrito na composição, lido no
  call time).
- `GamepadHub.activeAppId` é `@Volatile var` (hot path síncrono, sem StateFlow — a UI
  não precisa observar isso).
- `onAxis` passa a usar `profileFor(deviceId, activeAppId)` (correção 1 da validação).

### 1.5 Migração dos consumidores de menu (E1)

**`BusJoystickFocusNavigator`** (GamepadBusInput.kt:34-113) — passa a assinar
`GamepadInputEvent` no lugar de `AndroidEvent.MotionEvent`:

- `InputEvent.AxisMotion(LEFT_X/LEFT_Y/RIGHT_X/RIGHT_Y)` → magnitude/direção via
  `AnalogToDpad.sampleToDirection` + `GamepadStickLogic.decide` (RC1 preservado) —
  o fluxo atual de `stickState`/`GamepadNavigationClock`/`GamepadMoveDedupe` é MANTIDO,
  só a fonte muda.
- Deadzone do menu: `profile.leftStickDeadzone ?: PrefManager.gamepadMenuStickDeadzone`
  (0.45 default — o valor que o menu SEMPRE usou; a key `gamepadStickDeadzone` 0.15f é
  do JOGO. Dois valores separados de propósito).
- Consumo: como hoje, o navigator consome TODA motion do controle quando enabled
  (o jogo não vê o stick com overlay aberto). `AxisMotion` de triggers também é
  consumido (sem ação — o overlay é dono do device).
- Device validity: antes de mover foco, `hub.deviceFor(deviceId) != null` (ghost de
  device removido ignorado — critério de hotplug).

**`BusGamepadKeyBridge`** (GamepadBusInput.kt:164-269) — passa a assinar
`GamepadInputEvent` no lugar de `AndroidEvent.KeyEvent`:

- `ButtonDown(FACE_BOTTOM)` → DPAD_CENTER sintético (haptics, como hoje) — **apenas**
  quando FACE_BOTTOM é o botão de confirmação do FaceStyle ativo (ver 1.6).
- `ButtonDown(B)`/L1/R1/L2/R2/DPAD_* → re-dispatch no ComposeView como hoje.
- `ButtonDown(START)`/`SELECT`/`GUIDE` → consumidos (P1/G6 preservados).
- O dedupe DPAD×hat (C4) continua obrigatório: dispositivos que emitem hat E tecla DPAD
  geram DOIS `ButtonDown(DPAD_*)` lógicos (um via `translateAxis` do hat, outro via
  `translateKey`) — o `GamepadMoveDedupe`/`GamepadNavigationClock` atual decide
  primeiro-canal-vence EXATAMENTE como no pipeline cru. O bridge deixa de re-dispatchar
  DPAD cru e passa a trabalhar sobre o lógico; o navigator idem. **Não remover o dedupe.**

**`JoystickFocusNavigator`** + **`GamepadKeyBridge`** (view-level, diálogos) — mesma
migração, mas sem bus: leem do hub via getter síncrono
(`hub.onAxis` já emite no bus? NÃO — diálogos são janelas separadas e os eventos NUNCA
chegam ao bus do MainActivity; eles recebem `KeyEvent`/`MotionEvent` via
`setOnKeyListener`/`OnGenericMotionListener` na view do diálogo). Decisão: os diálogos
continuam view-level e ganham um helper fino que traduz na hora:
`GamepadViewBridge.translateAndHandle(KeyEvent/MotionEvent, deviceId, appIdAtual,
consumidor)` — mesmo `EventTranslator` + deadzones de perfil, sem tocar no bus.
O gate de ghost input (GamepadKeyBridge.kt:35-39) continua ANTES da tradução.

### 1.6 OK/Cancel por FaceStyle (Fase 6 do spec original)

- Botão de confirmação padrão por estilo: `XBOX/PLAYSTATION/GENERIC` → `FACE_BOTTOM`;
  `NINTENDO` → `FACE_RIGHT` (o A fica à direita no layout Nintendo).
- `PrefManager.gamepadSwapOkCancel` (ou `profile.swapOkCancel`) INVERTE a escolha
  (comportamento `menu_swap_ok_cancel_buttons` do RetroArch).
- Aplicado no `BusGamepadKeyBridge`/`GamepadKeyBridge`: só o botão de confirmação vira
  DPAD_CENTER; o outro (ex.: FACE_RIGHT no Xbox) segue raw para o Compose (back
  hierárquico — decisão D1 do spec 2026-08-08, preservada).
- `GamepadActionBar`/glyphs do QuickMenu: labels passam a usar
  `GamepadGlyphProvider` com o FaceStyle do device ativo (as strings já existem).

### 1.7 Caminho do jogo (E2 — atrás de V10)

`PhysicalControllerHandler.onGenericMotionEvent` (PhysicalControllerHandler.kt:141-177):
aplica deadzone por device ANTES de injetar. Mudança mínima:

- Ler `hub.profileFor(deviceId, appId)`; com
  `PrefManager.gamepadUniversalEnabled=true`, os valores de stick passam pelo
  `DeadzoneProcessor` com `leftStickDeadzone ?: PrefManager.gamepadStickDeadzone`
  (0.15f — igual ao `ExternalController.STICK_DEAD_ZONE` atual, que vira fallback).
- Triggers: `processAxis` com `leftTriggerDeadzone/rightTriggerDeadzone`.
- **Antes desta mudança:** rodar V10 (regressão: controle físico no jogo SEM overlay)
  para ter baseline; depois, rodar de novo com o gate ligado e comparar.
- Nada mais muda no handler nesta onda (sem remapeamento no jogo — o perfil `layers`
  continua sendo consumido só pelo menu/remap UI; remap no jogo é follow-up).

### 1.8 Gate

`gamepadUniversalEnabled` fica como kill-switch: default **false até a verificação
on-device E3 passar**; o commit final da onda flippa o default para true. A tradução
roda sempre que o gate liga; sem gate, o fluxo é byte-identical ao atual.

### 1.9 Instrumentação

- Tag novo `GamepadLogical` (Timber.d) no hub: cada `GamepadInputEvent` emitido
  (device, tipo, botão/eixo, valor) — par do `GamepadTrace` cru.
- `GamepadHub` loga add/remove com classificação (já existe) + `listenerCount()` do bus
  nos ciclos de teste (padrão M8 do hardening).
- `tools/quickmenu-verify.sh` ganha os cenários da §3.

---

## 2. Arquivos afetados

**Novos:**
- `gamepad/mapping/AndroidInputAdapter.kt` — `KeyEvent`/`MotionEvent` → `RawKeyInput`/
  `RawAxisInput` (fino, não testado em JVM; coberto on-device).
- `gamepad/GamepadViewBridge.kt` — helper view-level dos diálogos (traduz na hora,
  sem bus).
- `app/src/test/java/app/gamenative/gamepad/GamepadViewBridgeTest.kt` — lógica pura do
  helper (decidir consumir/confirmar por FaceStyle, sem views).

**Modificados (dono único: agente E):**

| Arquivo | Mudança |
|---|---|
| `PluviaApp.kt` | `val gamepadHub: GamepadHub` lazy app-scoped |
| `MainActivity.kt` | remove `controllerDeviceListener` (:144-156, :216, :385); chama `hub.onKey/onAxis` pós-emit cru; gate de ghost intacto |
| `events/GamepadBusEvents.kt` | sem mudança (já pronto) |
| `gamepad/GamepadHub.kt` | `@Volatile var activeAppId`; `onAxis` usa `profileFor(deviceId, activeAppId)`; `addDevice/removeDevice` chamam `ControllerManager`; filtro TOUCHPAD no `onKey`/`onAxis` (só CONTROLLER emite lógico — touchpad continua gate do MainActivity); log `GamepadLogical` |
| `ui/screen/xserver/XServerScreen.kt` | remove listener duplicado (:1464-1495); assina `GamepadDeviceAdded/RemovedEvent`; escreve `hub.activeAppId = container.id` na composição; **zero locals novas** |
| `ui/component/GamepadBusInput.kt` | navigator/bridge consomem `GamepadInputEvent`; deadzone de perfil; FaceStyle OK/Cancel; dedupe/clock mantidos |
| `ui/component/JoystickFocusNavigator.kt`, `GamepadKeyBridge.kt` | usam `GamepadViewBridge` (tradução na hora + OK/Cancel + ghost gate) |
| `ui/screen/xserver/PhysicalControllerHandler.kt` | deadzone por device via hub (E2, pós-V10) |
| `ui/component/GamepadActionBar.kt` / QuickMenu labels | glyphs por FaceStyle via `GamepadGlyphProvider` |
| `PrefManager.kt` | só consumo (keys já existem); flip do default do gate no commit final |
| `tools/quickmenu-verify.sh` | cenários §3 + greps `GamepadLogical` |
| `docs/MILESTONES.md` | entrada ao final (`tools/milestone.sh`) |

**Não tocados (proteções):** `EventDispatcher.kt`, `OverlayInputContext.kt`,
`GamepadStickLogic.kt`, `GamepadMoveDedupe.kt`, `GamepadFocus.kt`,
`GamepadModifiers.kt`, `DebugGamepadInput.kt`, toda a camada `gamepad/` (exceto hub),
nativo/Vulkan, `ExternalController` (fallback apenas).

---

## 3. Verificação

### 3.1 JVM

- `GamepadViewBridgeTest` — decisões puras: FaceStyle→botão de confirmação,
  swapOkCancel inverte, ghost gate antes da tradução, device inválido não consome.
- Suítes existentes verdes: `*Gamepad*` + `*gamepad*` (102 testes hoje) + `*Shader*` +
  `*SearchField*` (comando filtrado do AGENTS.md, nunca a suíte completa).

### 3.2 On-device (Mi 11 + DS4; harness `debug.gamenative.input`)

| # | Cenário | Critério |
|---|---|---|
| O1 | Boot com o gate OFF | comportamento byte-identical ao atual: jogo e menu intactos (V1–V10 do hardening) |
| O2 | Gate ON, menu aberto, apertar TODAS as teclas + stick | `GamepadLogical` mostra ButtonDown/AxisMotion lógicos; navegação 1 linha/gesto; jogo não recebe nada (log `GamepadRoute` limpo) |
| O3 | DS4 vs Xbox vs Switch (ou simulado via perfil faceStyle) | botão de confirmação correto por FaceStyle; `menu_swap_ok_cancel` inverte |
| O4 | Perfil de device com deadzone 0.30 no DS4 | navegação do menu usa 0.30; outro device usa 0.45 (menu) — deadzone do MENU é `gamepadMenuStickDeadzone`, não a do jogo |
| O5 | Perfil por jogo (appId): deadzone X no jogo A, default no B | aplicado ao trocar de container; persiste após restart |
| O6 | Hotplug: conectar/desconectar com menu aberto, durante o jogo, com o celular desconectando | zero crash; `listenerCount()` estável; navegação segue funcional; device removido não move foco |
| O7 | Controle que emite DPAD key+hat (ou harness `stick`+`key:20`) | 1 gesto = 1 movimento (dedupe lógico) |
| O8 | Diálogo (ex.: ElementEditor) com controle físico | `GamepadViewBridge` navega/fecha com B; OK/Cancel respeita FaceStyle; ghost gate ativo |
| O9 | Remap: abrir `GamepadRemapDialog`, capturar botão/eixo, conflito, salvar | round-trip funciona; perfil persiste no arquivo `files/gamepad/` |
| O10 | Regressão jogo: controle físico sem overlay (V10 baseline) e com gate ON (E2) | sticks respondem como antes; deadzone 0.15 fallback == `ExternalController.STICK_DEAD_ZONE` |
| O11 | Latência | overhead do hub no dispatch < 1 ms (timestamps `GamepadTrace`→`GamepadLogical` no logcat) |

### 3.3 Aceite global

- T1–T9 (2026-08-10), V1–V10 (hardening 2026-08-12), F1–F10 (browser) re-testados
  verdes no mesmo ciclo.
- `assembleModernDebug` sem warnings novos; flip do gate default=true no commit final.

---

## 4. Ordem de execução (agente E, commits separados)

1. E1a — hub app-scoped + adapter + wiring MainActivity + remoção do listener duplicado
   do XServerScreen + `activeAppId` + correções 1-3 da validação. Gate OFF. (O1)
2. E1b — migração dos consumidores de menu (bus + view) + FaceStyle OK/Cancel +
   glyphs no ActionBar. Gate OFF ainda (consumidores novos ficam inertes sem o gate).
3. E2 — PhysicalControllerHandler com deadzone por device (pós-V10 baseline).
4. E3 — ligar o gate no device, rodar O1–O11 + aceite global; flippar default;
   `tools/quickmenu-verify.sh` atualizado; entrada em `docs/MILESTONES.md`
   (`tools/milestone.sh`); spec de implementação (`...-onda2-impl.md`) com evidências
   file:line.

## 5. Fora de escopo / follow-ups

- Gyro → mouse/câmera (stub `SensorUpdate` pronto).
- Touchpad DS4/DualSense → mouse (stub `TouchpadMotion` + gate de ghost existente).
- Remap aplicado ao JOGO (hoje `layers` serve o menu/UI; injetar no
  `PhysicalControllerHandler` é outro spec).
- Action Layers completas (chords/toggles); rumble avançado.
- UI de deadzones por device (sliders no remap dialog — o perfil já persiste).
