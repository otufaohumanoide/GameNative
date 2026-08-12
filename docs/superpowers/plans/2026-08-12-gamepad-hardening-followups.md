# Plano — Follow-ups do hardening de gamepad (adendo pós-implementação)

**Data:** 2026-08-12
**Origem:** revisão do commit `55337822` (spec `docs/spec-2026-08-12-quickmenu-gamepad-pipeline-hardening.md`).
A implementação está sólida; este plano fecha 4 gaps apontados no adendo + sincroniza docs e
verificação. **Dividido em 4 subagents paralelos + 1 agente final sequencial** (sem overlap de
arquivos entre os 4 primeiros).

---

## Contexto comum (todos os agents)

- App Android/Kotlin + Compose 1.8. Pipeline de gamepad: `MainActivity.dispatchKeyEvent/
  dispatchGenericMotionEvent` → bus `PluviaApp.events` (multicast; `EventDispatcher` com
  registry por identidade) → `XServerScreen.onKeyEvent/onMotionEvent` (roteia jogo×overlay
  via `OverlayInputState`) → superfícies Compose (`BusJoystickFocusNavigator` +
  `BusGamepadKeyBridge` no QuickMenu/browser/edit-mode; `GamepadFocusScope` = navigator +
  bridge view-level nos diálogos).
- Relógio compartilhado `GamepadNavigationClock.lastMoveAt` (GamepadBusInput.kt:100-103):
  estampado por movimentos REAIS de foco (navigators, bridge DPAD) e pelo bootstrap
  `requestMenuFocus()` (QuickMenu.kt:1147). Lido por: dedupe `GamepadMoveDedupe`,
  gentileza dos guardiões (QuickMenu.kt:1259, ShaderBrowserOverlay.kt:292) e supressão de
  IME do campo de busca (`GamepadSearchField.kt:96-100` via `SearchFieldImeLogic.
  arrivedViaGamepad`).
- Convenções do repo: lógica pura JVM-testável (padrão `GamepadStickLogic`), KDoc
  explicando o porquê, spec → impl doc → MILESTONES, PT-BR nos docs.
- Testes: `./gradlew :app:testModernDebugUnitTest --tests "*Gamepad*" --tests "*SearchField*" --tests "*Shader*"` (91 verdes em HEAD).
- NÃO tocar: `PhysicalControllerHandler`, `WinHandler`/native, renderer, `GamepadStickLogic`,
  `EventDispatcher` (estável pós-M3), `DebugGamepadInput` (exceto doc), specs antigos.

---

## Missão A — GamepadTrace com gate DEBUG (MainActivity)

**Problema:** commit `55337822` adicionou `ExternalController.isGameController(event.device)`
em `MainActivity.dispatchKeyEvent` (:576-604) e `dispatchGenericMotionEvent` (:606-614).
O check roda em TODOS os eventos de tecla/motion do app inteiro (biblioteca inclusa), em
TODAS as builds — `Timber.d` só silencia o log; `isGameController` faz `hasKeys`/
`getMotionRange` no caminho crítico de dispatch.

**Mudança (MainActivity.kt):**
- Gate de build: `if (BuildConfig.DEBUG) { ... }` envolvendo os dois blocos de log
  (mesmo padrão de `DebugGamepadInputHarness`).
- Dentro do gate, filtrar PRIMEIRO por bitmask barato antes do `isGameController`:
  `(event.source and (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK)) != 0`
  — descarta teclado/toque sem custo; só então logar.

**Aceite:** release build não executa `isGameController` por evento (revisar bytecode não é
necessário; revisão de código basta); debug segue logando GamepadTrace para gamepad.

---

## Missão B — Relógios separados: movimentos reais × foco programático

**Problema:** `requestMenuFocus()` estampa `GamepadNavigationClock.lastMoveAt`
(QuickMenu.kt:1147) com o propósito original de suprimir o IME quando o foco aterrissa
programaticamente. Com o dedupe M4 lendo o MESMO relógio, um bootstrap/restore estampado
suprime o 1º movimento real do usuário dentro de 120 ms (janela do dedupe) e rearma a
gentileza dos guardiões (600 ms) sem ter havido gesto.

**Mudanças:**
1. `GamepadBusInput.kt` (`GamepadNavigationClock`): adicionar
   `@Volatile var programmaticFocusAt: Long = 0L`; atualizar KDoc — `lastMoveAt` passa a
   significar APENAS movimentos reais (stick/hat/tecla DPAD vencedora);
   `programmaticFocusAt` = bootstraps/restores programáticos.
2. `QuickMenu.kt:1147` (`requestMenuFocus()`): estampar `programmaticFocusAt` em vez de
   `lastMoveAt`.
3. `ShaderBrowserOverlay.kt` (guardião M6, bloco "restoring focus"): estampar
   `programmaticFocusAt` após o restore (o browser não usa GamepadSearchField hoje, mas a
   semântica fica correta e futura).
4. `SearchFieldImeLogic.kt` (`arrivedViaGamepad`): passar a receber `lastMoveAt` E
   `programmaticFocusAt` e decidir com `maxOf(ambos)` — IME suprimido quando o foco veio
   de movimento OU de bootstrap programático. Documentar a mudança no KDoc.
5. `GamepadSearchField.kt:96-100`: passar os dois timestamps.
6. `SearchFieldImeLogicTest.kt`: atualizar casos existentes + novos: só programático;
   só movimento; nenhum; ambos (janela 400 ms exata); programático antigo > janela não
   suprime.
7. Verificar (grep) se algo mais lê `GamepadNavigationClock` e ajustar KDoc do
   `GamepadMoveDedupe.kt:11` se necessário (o texto "shared clock" continua válido para
   movimentos reais).

**Aceite:** dedupe nunca vê o stamp do bootstrap (1º movimento pós-abertura nunca
suprimido); IME continua suprimido ao aterrissar no campo de busca por walk-down/guardian;
testes JVM verdes.

---

## Missão C — Dedupe DPAD×hat nos diálogos (GamepadFocusScope)

**Problema:** M4 cobriu o caminho do BUS (QuickMenu/browser) mas não os diálogos em
janelas separadas: `GamepadFocusScope` (GamepadModifiers.kt:263-292) compõe
`JoystickFocusNavigator` (eixos consultam o dedupe e estampam) + `GamepadKeyBridge`
(trata só BUTTON_A); as teclas DPAD chegam CRUAS ao Compose do diálogo, cujo focus
system as processa nativamente SEM consultar/estampar o relógio. Em controles que emitem
hat+key no mesmo gesto, o diálogo move o foco 2× (1º gesto não é dedupado: o eixo estampa,
a tecla nunca consulta).

**Mudança (GamepadModifiers.kt, `GamepadFocusScope`):**
- No Box raiz, adicionar `Modifier.onPreviewKeyEvent` ANTES do content:
  - se `keyEvent.type == KeyEventType.KeyDown` e `nativeKeyEvent.keyCode in
    (DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT)`:
    - `now = SystemClock.uptimeMillis()`
    - se `GamepadMoveDedupe.shouldDispatchKeyMove(now, GamepadNavigationClock.lastMoveAt,
      nativeKeyEvent.repeatCount)`:
      - se `repeatCount == 0` → estampar `lastMoveAt = now`
      - retornar `false` (Compose processa a tecla nativamente)
    - senão → retornar `true` (consumido — o eixo já moveu dentro da janela)
  - senão `false`.
- KDoc explicando a simetria com o caminho do bus (M4) e que o canal de eixos do diálogo
  (`JoystickFocusNavigator`) já consulta/estampa o mesmo relógio — a supressão do 2º
  canal passa a valer nos dois sentidos.
- `GamepadMoveDedupe` já está testado; não é necessário teste novo de Compose. Se algum
  teste `GamepadModifiersTest` quebrar por ordem de modifiers, ajustar com a mesma
  semântica.

**Aceite:** 1 pressão de D-pad físico (hat+key) em QUALQUER diálogo
(TouchGesture/ShooterMode/PhysicalController/ControllerBinding/ElementEditor/
PlayingBlocked) = 1 movimento; testes JVM verdes.

---

## Missão D — Focus-lock: toolbar de edição não-focável com o menu aberto (C7)

**Problema:** `EditModeToolbar` é composta quando `isEditMode && areControlsVisible`
(XServerScreen.kt:2620) SEM gate de `showQuickMenu` — com o menu aberto em modo edição, a
toolbar fica composta e focável ATRÁS do menu (os `TextButton` são focusable): é o alvo de
fuga do `moveFocus` na borda (C7). O navigator de edit mode já é gateado por
`isEditMode && !showQuickMenu` (:2614) — a toolbar deveria seguir o mesmo gate.

**Mudanças (XServerScreen.kt):**
1. Call site da `EditModeToolbar` (:2620): condição vira
   `if (isEditMode && areControlsVisible && !showQuickMenu)`.
2. Verificar (grep `clickable|focusable|gamepadSelectable|TextButton` no XServerScreen)
   se existe OUTRO nó focável composto com `showQuickMenu == true` fora do QuickMenu.
   Resultados conhecidos: manual-resume overlay (:2773-2790) já é gateado por
   `!showQuickMenu`; `ControllerSlotStatusOverlay` (:2762, debug pref) — confirmar que
   não é focável (se for, gatear igualmente). Registrar o resultado da varredura no
   impl doc.

**Aceite:** em edit mode com o menu aberto, navegar até a borda do conteúdo/rail NÃO
escapa para a toolbar; fechando o menu, a toolbar reaparece e o bootstrap do Add button
segue funcionando (fluxo G7 intocado).

---

## Missão F — Reversão do P1: START não é mais toggle do QuickMenu (decisão do usuário 2026-08-12)

**Problema:** o P1 (spec `docs/spec-2026-08-12-quickmenu-gamepad-ux-review.md`) fez o START
espelhar o HOME: abre o menu com ele fechado (XServerScreen.kt:1565-1575) e fecha com ele
aberto (GamepadBusInput.kt:176-189). **Decisão do usuário:** o QuickMenu só deve
abrir/fechar por **Home/PS**; START volta a ser do jogo.

**Mudanças (divididas entre Alpha e Gamma — sem overlap de arquivos):**

- **F-bridge (Agent Alpha)** — `GamepadBusInput.kt:176-189`: remover a chamada
  `onCloseOverlay()` do branch `KEYCODE_BUTTON_START`; MANTER o consumo (`return true`)
  para DOWN/UP com overlay aberto — START nunca chega ao jogo atrás do menu, mas também
  não fecha o menu. Atualizar o KDoc do bridge (:134-135) e o comentário do bloco (a
  menção "START mirrors HOME" sai).
- **F-XServerScreen (Agent Gamma)** — `XServerScreen.kt:1565-1575`: remover o bloco
  "START opens QuickMenu (P1 toggle)" e o `handled = true` associado; o fluxo volta a
  `physicalControllerHandler?.onKeyEvent` → perfil do usuário → `winHandler` (START volta
  a pausar o jogo com o menu fechado). Atualizar o comentário que referencia P1.
- **F-docs (Agent Delta)** — notas de reversão: spec hardening (§0 decisão 4 e referências
  a P1), impl doc, MILESTONES; linha de status na seção P1 do spec
  `2026-08-12-quickmenu-gamepad-ux-review.md`: "Revertido (2026-08-12) por decisão do
  usuário — START volta ao jogo; só Home/PS alterna o menu".

**Aceite:** PS abre/fecha intacto (toggle G6); START com menu fechado chega ao jogo
(pausa via perfil); START com menu aberto é consumido SEM fechar o menu; SELECT continua
consumido com overlay aberto; nenhum log de abertura do menu disparado por START
(`GamepadRoute` com ctx=NONE + code=108 não invoca `gameBack`).

---

## Missão E — Docs sync + verificação final (SEQUENCIAL, após A–D/F)

1. **Spec sync:** em `docs/spec-2026-08-12-quickmenu-gamepad-pipeline-hardening.md`,
   adicionar bloco de status no topo: implementado em `55337822`; correção da premissa
   D1 (FocusRing.kt KEPT — callers reais `Modifier.focusRing` na biblioteca; ver impl doc
   §2); follow-ups A–F implementados neste plano (apontar para este arquivo); **P1
   revertido** (decisão do usuário: só Home/PS alterna o menu — ver Missão F).
2. **Impl doc:** em `docs/spec-2026-08-12-quickmenu-gamepad-pipeline-hardening-impl.md`,
   nova seção "Follow-ups (adendo)" resumindo A–F com os resultados de verificação de
   cada missão (cada agent deve deixar o resumo pronto no seu relatório).
3. **Spec P1:** em `docs/spec-2026-08-12-quickmenu-gamepad-ux-review.md`, linha de status
   na seção P1: "Revertido (2026-08-12) por decisão do usuário — START volta ao jogo;
   só Home/PS alterna o menu".
4. **MILESTONES.md:** entrada do follow-up com os commits.
5. **JVM:** rodar a suíte completa (comando no contexto comum) — deve permanecer verde
   com os testes novos de B.
6. **quickmenu-verify.sh:** sem mudanças obrigatórias; se o cenário [D] (registry) puder
   ganhar um grep de `GamepadTrace` sem fragilizar, adicionar.
7. **On-device (checklist manual para o usuário):** V4 com o D-pad físico do DS4 no MENU e
   num DIÁLOGO (1 pressão = 1 linha — valida C); abrir o menu em edit mode e navegar às
   bordas (valida D); abrir o menu e pressionar o stick imediatamente após o bootstrap
   (valida B — 1º movimento não suprimido); **F:** PS abre/fecha; START com menu fechado
   pausa o jogo; START com menu aberto é consumido sem fechar o menu.

---

## Dispatch dos subagents (4 paralelos + 1 sequencial)

### Agent Alpha — Missões A + B + F-bridge
Arquivos: `MainActivity.kt`, `GamepadBusInput.kt`, `QuickMenu.kt`, `ShaderBrowserOverlay.kt`,
`SearchFieldImeLogic.kt`, `GamepadSearchField.kt`, `SearchFieldImeLogicTest.kt`.
Entregar: diff + `testModernDebugUnitTest --tests "*SearchField*" --tests "*Gamepad*"` verde.
(Anotar nos commits: "fix(gamepad): … (plano 2026-08-12 follow-ups, missões A/B/F-bridge)".)

### Agent Beta — Missão C
Arquivos: `GamepadModifiers.kt` (somente `GamepadFocusScope`); ler `GamepadMoveDedupe.kt`
e `JoystickFocusNavigator.kt` para manter simetria.
Entregar: diff + testes `GamepadModifiersTest` verdes.

### Agent Gamma — Missões D + F-XServerScreen
Arquivos: `XServerScreen.kt` (gate da toolbar + varredura de focusables + remoção do bloco
P1 do START).
Entregar: diff + relatório da varredura (lista de nós focáveis compostos com
`showQuickMenu==true` fora do QuickMenu e o que foi feito em cada um) + confirmação de que
START com menu fechado volta a fluir para `physicalControllerHandler`.

### Agent Delta — Missão E (rodar SÓ DEPOIS que Alpha/Beta/Gamma terminarem)
Arquivos: docs (spec hardening + spec P1 + impl doc + MILESTONES), opcional
`tools/quickmenu-verify.sh`.
Entregar: docs sincronizados (inclui a reversão do P1) + suíte JVM completa verde +
checklist on-device pronto para o usuário.

---

## Ordem e dependências

```
Alpha (A+B+F-bridge) ─┐
Beta  (C)             ─┼─ (paralelos, sem overlap de arquivos) ─► Delta (E, sequencial)
Gamma (D+F-xserver)   ─┘
```

- Alpha/Beta/Gamma não compartilham arquivos: sem conflitos de merge.
- Beta pode começar imediatamente (C não depende de B: o dedupe consulta `lastMoveAt`,
  que após B passa a conter SÓ movimentos reais — melhor, mas não bloqueante).
- F está fatiada por ARQUIVO (bridge = Alpha; XServerScreen = Gamma) — as duas metades
  são independentes em código; a semântica completa só fecha quando ambas estiverem
  aplicadas (verificação on-device de F é item do Delta).
- Delta é o único autorizado a tocar docs/MILESTONES (evita conflito com os commits dos
  outros três).
