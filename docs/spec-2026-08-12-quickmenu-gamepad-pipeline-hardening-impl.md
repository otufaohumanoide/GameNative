# Implementação — Spec 2026-08-12 QuickMenu gamepad pipeline hardening

**Data:** 2026-08-12
**Spec de origem:** `docs/spec-2026-08-12-quickmenu-gamepad-pipeline-hardening.md`
**Commit da implementação:** *(ver MILESTONES.md)*
**Resultado:** todas as missões M1–M8 implementadas; verificação on-device executada
(Mi 11 / alioth_global, Silksong 1030300, DS4 "Wireless Controller" conectado).

---

## 1. Resumo das mudanças por missão

| Missão | Arquivo(s) | O quê |
|---|---|---|
| **M1** (C1 — roteamento vivo) | `OverlayInputContext.kt`, `XServerScreen.kt` | Novo holder `OverlayInputState` (state-backed). O XServerScreen ESCREVE `overlayInputState.context` na composição (mesmo cálculo de antes); os handlers `onKeyEvent`/`onMotionEvent` (registrados uma vez em `DisposableEffect(Unit)`) leem o HOLDER no call time — nunca mais um valor capturado stale. |
| **M2** (C2 — single-writer) | `XServerScreen.kt` | Semântica documentada no topo dos handlers (emit = multicast; consumo = decisão de janela; cada listener é dono de UMA superfície). Com `ctx=OVERLAY` o branch de jogo (noteGamepadButton / refreshControllerMappingsForHotplug / winHandler / physicalControllerHandler) é inalcançável. Log `GamepadRoute` registra o branch. |
| **M3** (C3 — registry por identidade) | `EventDispatcher.kt`, `GamepadBusInput.kt`, `XServerScreen.kt` | Chave do registry = instância do lambda (`===` no `off()`); `listenerCount()` exposto; logs `EventBus: on/off` com contagem e warning `matched NOTHING` se um off não casar. Bridge: `rememberUpdatedState(onCloseOverlay)` + keys `(enabled, view, modeKeyBehavior)` — fim do churn off/on a cada poll. `dismissOverlayMenu` estável (`remember { … }`, leituras state-backed no call time). Edit mode: `modeKeyBehavior = ModeKeyBehavior.None` explícito (M7 parcial). |
| **M4** (C4 — dedupe DPAD×hat) | `GamepadMoveDedupe.kt` (novo), `GamepadBusInput.kt`, `JoystickFocusNavigator.kt` | Lógica pura `GamepadMoveDedupe` (janela 120 ms, repeat sempre passa). O bridge consulta antes de re-dispatchar DPAD e estampa o relógio compartilhado quando vence; os dois navigators consultam antes do `moveFocus`. |
| **M5** (C5 — bootstrap serializado + guardião gentil) | `QuickMenu.kt` | `focusMutex` (`remember { Mutex() }`) envolvendo TODO o `requestMenuFocus()` (abertura, restore do browser e guardião compartilham o mesmo caminho — dois walk-downs simultâneos impossíveis; logs mutex enter/exit). Guardião gentil: pula o ciclo se `lastMoveAt < 600 ms` (nunca `clearFocus(true)` no meio de um gesto). |
| **M6** (C6 — guardião do browser) | `ShaderBrowserOverlay.kt` | `browserHasFocus` (`.onFocusChanged` na raiz) + guardião em loop (400 ms) que restaura `focusIndices[nav.current.key()] ?: 0` com fallback para row 0; gentileza via `GamepadNavigationClock`; computa a chave do requester no CALL TIME (não captura o `requesterFor` da composição, que carrega o screenKey stale). |
| **M7** (D1 — dead code) | ver §2 | Auditoria final executada; `FocusRing.kt` **mantido** (a premissa "zero callers" do spec estava incorreta — ver §2). |
| **M8** (instrumentação) | `MainActivity.kt`, `EventDispatcher.kt`, `GamepadBusInput.kt`, `QuickMenu.kt`, `ShaderBrowserOverlay.kt`, `XServerScreen.kt`, `tools/quickmenu-verify.sh` | Tags `GamepadTrace` (MainActivity), `GamepadRoute` (XServerScreen, inclui hotplug), `EventBus` (on/off + contagem), `BusJoystick`/`BusGamepadKeyBridge` ("listening"/"stopped remaining=N"), mutex do QuickMenu, `ShaderBrowser guardian`. Script estendido com cenários C/D/E/F. |

---

## 2. Auditoria de dead code (M7) — RESULTADO

O spec (D1) afirmava que `FocusRing.kt` tinha **zero callers**. A auditoria por grep
(símbolo `focusRing`, metodologia G11) encontrou **13+ call sites ativos**:

- `LibraryTabBar.kt` (6×: linhas 225, 310, 559, 618…)
- `LibraryListCard.kt:85`, `LibraryGridCard.kt:145`, `LibraryAppScreen.kt:215, 325` (+ mais)
- Import explícito `app.gamenative.ui.component.focusRing` nesses arquivos — remover o
  arquivo QUEBRA a compilação da biblioteca.

O KDoc do próprio `FocusRing.kt` documenta o papel real: "thin wrapper … for surfaces
OUTSIDE the QuickMenu (LibraryGridCard, InfoCard…)". O anel migrou para `GamepadFocus.kt`
**apenas dentro do QuickMenu**; a biblioteca (fora da janela de jogo) continua usando o
wrapper. **Decisão: KEEP `FocusRing.kt`** — a premissa do spec estava incorreta e o
"não tocados (proteções)" do próprio spec proíbe migrar as superfícies da biblioteca
neste escopo.

Demais candidatos da auditoria:

| Símbolo | Veredito | Evidência |
|---|---|---|
| `FocusRing.kt` / `Modifier.focusRing` | **KEEP** | 13+ callers na biblioteca (acima) |
| `ModeKeyBehavior.None` | **API explícita** | Edit mode agora declara `modeKeyBehavior = ModeKeyBehavior.None` (antes só via DEFAULT implícito) |
| `GamepadActionBar.visible` | **KEEP** | Caller real: `LibraryAppScreen.kt:1218` (`visible = !optionsMenuVisible`) |
| `routeToCloseButton` | **KEEP** | Usado via `focusProperties` (QuickMenu, P6) |
| `GLScreenEffectsTabContent` | **KEEP** | Caminho GL ativo (`QuickMenu.kt:1013`) |

---

## 3. Correção de instrumentação: keycode do PS (descoberta on-device)

Durante a Fase 0/verificação, o PS sintético (`key:188`) **não** abria nem fechava o menu.
Instrumentação no bridge revelou:

- `KeyEvent.KEYCODE_BUTTON_MODE` = **110** (não 188).
- 188 = `KEYCODE_BUTTON_1` (botão genérico) — ignorado por XServerScreen e bridge.
- O botão PS físico do DS4 (BTN_MODE 0x13a) é mapeado pelo keylayout Android para
  **110** — o fluxo real do usuário sempre usou 110; o código do app estava correto.

**Correções:** doc do harness (`DebugGamepadInput.kt`) e `tools/quickmenu-verify.sh`
passaram a usar `key:110` para PS/BUTTON_MODE (o comentário `key:188 = BUTTON_MODE/PS`
estava errado desde o spec 2026-08-09).

---

## 4. Verificação executada

### 4.1 JVM — 91 testes verdes (`testModernDebugUnitTest`)
`GamepadMoveDedupeTest` 8/8 (primeiro canal vence; janela; fronteira exata 120 ms;
repeat-passthrough; supressões nos dois sentidos). Suites existentes verdes
(GamepadStickLogic 7, GamepadModifiers 19, Shader*, SearchField*).

### 4.2 On-device (Mi 11 + DS4, Silksong rodando) — evidenciado por logcat

| Cenário | Resultado |
|---|---|
| **V2 (C1/C2)** | Menu aberto: TODAS as teclas (A/B/L1/R1/L2/R2/DPAD×4/CENTER/START/SELECT/PS) + stick/hat logaram `GamepadRoute … ctx=OVERLAY`; **0 ocorrências** de `refreshControllerMappingsForHotplug`/`winHandler` com overlay aberto. Branch de jogo nunca rodou. |
| **V1 (C3)** | 20+ ciclos abrir/fechar (menu + browser): `EventBus: on/off KeyEvent|MotionEvent` **balanceados** (7==7 na janela observada; offs extras = fechamento de menu pré-janela), **0** `matched NOTHING`; `remaining=1` (só o XServerScreen) após cada fechamento; sem re-registro em rajada durante polls (1 listening/1 stopped por ciclo). |
| **V7 (P1)** | PS com menu fechado ABRE (PhysicalControllerHandler); PS com menu aberto FECHA (bridge → `dismissOverlayMenu`); START espelha (abre no game branch / fecha na bridge); SELECT nunca vaza com overlay. |
| **V6 + M5** | Bootstrap serializado: `mutex enter/exit` em toda abertura; `focus retry` + `fallback to rail` observados na tab TOOLS (sem itens) — menu nunca nasce morto. Guardião gentil observado: "user navigating, skipping cycle" ×2 e restore limpo em seguida. |
| **M6** | Browser: "Show more" removeu a linha focada → `ShaderBrowser root focus: false` → restore em **76 ms** (navTick) e, num segundo cenário, `ShaderBrowser guardian: restoring focus row=16` em **133 ms** — ≤ 400 ms sem PS/B. |
| **M8** | Todas as tags presentes (GamepadTrace key/motion, GamepadRoute, EventBus, listening/stopped, mutex, ShaderBrowser guardian). |

### 4.3 Pendências de verificação (manuais, com o controle físico na mão)

- **V4 (dedupe 1 gesto = 1 movimento):** o harness não consegue emitir key+hat dentro
  da janela de 120 ms (poll de 200 ms). O DS4 físico emite os dois canais no D-pad —
  verificação manual: pressionar o D-pad uma vez = 1 linha. Lógica coberta por 8 testes
  JVM.
- **V8** (reconectar o controle com o menu aberto), **V9** (sessão 10 min),
  **V10** (regressão do jogo sem overlay) — sessão contínua de ±10 min executada sem
  "menu morto"; o jogo continuou recebendo input do touchpad (device real) sem overlay.
- T1–T9 (spec 2026-08-10) e F1–F10 (browser) — sem regressão estrutural (nenhum
  arquivo protegido foi tocado; `PhysicalControllerHandler`/`WinHandler`/`GamepadStickLogic`/
  `GamepadModifiers`/`GamepadFocus` intocados).

---

## 5. Arquivos

**Novos:** `app/src/main/java/app/gamenative/ui/component/GamepadMoveDedupe.kt`,
`app/src/test/java/app/gamenative/ui/component/GamepadMoveDedupeTest.kt`,
`docs/spec-2026-08-12-quickmenu-gamepad-pipeline-hardening.md` (spec de origem, não versionado antes).

**Modificados:** `OverlayInputContext.kt`, `XServerScreen.kt`, `EventDispatcher.kt`,
`GamepadBusInput.kt`, `JoystickFocusNavigator.kt`, `QuickMenu.kt`, `ShaderBrowserOverlay.kt`,
`MainActivity.kt`, `DebugGamepadInput.kt` (doc do harness), `tools/quickmenu-verify.sh`,
`docs/MILESTONES.md`.

**Removidos:** nenhum (ver auditoria §2).

---

## 6. Follow-ups (adendo pós-revisão, 2026-08-12)

Plano: `docs/superpowers/plans/2026-08-12-gamepad-hardening-followups.md`. Missões e resultados:

| Missão | O quê | Estado |
|---|---|---|
| **A** | `GamepadTrace` do MainActivity gateado por `BuildConfig.DEBUG` + filtro barato de source (bitmask GAMEPAD/JOYSTICK) antes de `isGameController` — release não paga nada por evento | ✅ implementado |
| **B** | Relógios separados no `GamepadNavigationClock`: `lastMoveAt` = SÓ movimentos reais (dedupe/guardiões); novo `programmaticFocusAt` = bootstraps/restores (QuickMenu `requestMenuFocus`, guardian do browser). `SearchFieldImeLogic.arrivedViaGamepad` decide por `maxOf` dos dois — IME continua suprimido no foco programático e o dedupe nunca mais vê stamp de bootstrap (1º movimento pós-abertura nunca suprimido) | ✅ implementado; 5 casos novos em `SearchFieldImeLogicTest` |
| **C** | Dedupe DPAD×hat nos diálogos: `GamepadFocusScope` ganhou `onPreviewKeyEvent` na raiz (mesma semântica do `BusGamepadKeyBridge` — tecla vencedora estampa o relógio, perdedora é consumida; repeats passam) | ✅ implementado |
| **D** | C7 focus-lock: `EditModeToolbar` gateada por `!showQuickMenu` (era o alvo de fuga do `moveFocus` atrás do menu). Varredura: manual-resume overlay já gateado; `ControllerSlotStatusOverlay` não é focável (Row+Text); sem outros nós focáveis compostos com `showQuickMenu==true` | ✅ implementado |
| **F** | Reversão do P1: `BusGamepadKeyBridge` não fecha mais o overlay no BUTTON_START (consumo mantido); bloco "START abre o QuickMenu" removido do `XServerScreen.onKeyEvent` — START volta a fluir para `physicalControllerHandler`/perfil (pause do jogo) | ✅ implementado |

**Verificação:** suíte filtrada (`--tests "*Gamepad*" --tests "*SearchField*" --tests "*Shader*"`)
verde (inclui os 5 casos novos de B). **On-device pendente (checklist manual):**
- V4 com o D-pad físico do DS4 no MENU e num DIÁLOGO: 1 pressão = 1 linha (valida C).
- Menu em edit mode, navegar às bordas: foco não escapa para a toolbar (valida D).
- Abrir o menu e pressionar o stick imediatamente após o bootstrap: 1º movimento não
  suprimido (valida B).
- F: PS abre/fecha; START com menu fechado pausa o jogo (perfil); START com menu aberto é
  consumido sem fechar o menu.
