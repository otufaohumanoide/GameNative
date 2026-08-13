# Spec 2026-08-12 — Pipeline de gamepad do QuickMenu: fim das contradições de roteamento

**Data:** 2026-08-12
**Origem:** sintoma do usuário — gamepad deixa de funcionar no QuickMenu após uso/testes
(só restart do app recupera; às vezes intermitente, às vezes fixo; persiste com o celular
desconectado). Edições recentes (browser de shaders, P1–P6, closure fix) tocaram código de
gamepad; há suspeita de dead code. Referências técnicas: documento "Controle de Gamepads no
Android" (roteamento centralizado, consumo correto, dedupe KeyEvent×MotionEvent) e o guia
de diagnóstico QuickMenu/input (hipóteses de foco/event loop/consumo).
**Decisões do usuário (registradas em sessão):**
1. **Mínimo no bus atual** — corrigir as contradições no pipeline existente; NÃO introduzir
   GameInputManager/FSM novo (proteção dos specs 2026-08-10 e 2026-08-12 preservada).
2. **Escopo único** — fases de instrumentação, roteamento, foco, dead code/UX e verificação
   em UM documento.
3. **Dead code removido** neste escopo.
4. **P1 mantido** — START continua espelhando HOME (abre com menu fechado, fecha com menu
   aberto, sempre consumido com overlay aberto).

---

## 0. Contexto — o pipeline atual (o que fica)

```
Controle físico → Android → MainActivity.dispatchKeyEvent/dispatchGenericMotionEvent
   (MainActivity.kt:576-604, 606-614 — emite no bus PluviaApp.events e decide o retorno
    da janela por `any { it }`: emit é MULTICAST, consumo é só decisão de janela)
        ↓
Bus PluviaApp.events (EventDispatcher.kt:61-73 — TODOS os listeners rodam, sem ordem
   garantida, sem interrupção antecipada)
        ├── XServerScreen.onKeyEvent/onMotionEvent (XServerScreen.kt:1510-1665) — roteia
        │    para o jogo (PhysicalControllerHandler/InputControlsView/WinHandler) ou
        │    devolve ao Compose; consulta OverlayInputContext (:1500-1508)
        ├── BusJoystickFocusNavigator (GamepadBusInput.kt:32-92) — stick/hat → moveFocus,
        │    instalado por superfície: QuickMenu (:682), browser (:174), edit mode (:2615)
        ├── BusGamepadKeyBridge (GamepadBusInput.kt:144-213) — A→DPAD_CENTER sintético,
        │    B/L1/R1/L2/R2/DPAD re-dispatch crus pro ComposeView; MODE/START/SELECT
        │    consumidos; PS/START fecham o overlay (P1/G6)
        └── LibraryScreen (LibraryScreen.kt:708-790) — padrão comprovado (fora da janela
             de jogo; não participa das superfícies do QuickMenu)
```

Invariante do projeto (spec 2026-08-10 §1): **com qualquer overlay aberto, o jogo não vê
NADA do gamepad** e o menu é 100% navegável por joystick. Este spec audita onde o código
quebra esse invariante e onde o foco pode morrer sem recuperação.

---

## 1. Auditoria — contradições encontradas (evidência file:line)

| # | Contradição | Evidência | Consequência |
|---|---|---|---|
| **C1** | **Roteamento com estado stale para sempre** — `overlayInputContext` é `val` derivado (XServerScreen.kt:1500-1508), mas os handlers do bus são registrados UMA vez em `DisposableEffect(Unit)` (XServerScreen.kt:1687-1690) → o listener captura o valor da 1ª composição (NONE) e nunca mais atualiza. As demais flags (`showQuickMenu` etc.) são state-backed e leem valor atual; o único valor stale é o derivado | XServerScreen.kt:1500-1508, 1510, 1533, 1687-1705 | Com overlay aberto, o branch `else` continua executando: teclas vão para `winHandler.onKeyEvent` (:1562, :1580), `physicalControllerHandler` (:1577), `inputControlsView` (:1578); motion vai para o jogo (:1626-1638). O jogo recebe input por trás do menu — cenário exato do documento de referência §1.1 ("overlay surdo, jogo respondendo") |
| **C2** | **Efeitos colaterais de jogo com overlay aberto** — no branch stale de C1, cada tecla gamepad com `repeatCount==0` dispara `ControllerManager.noteGamepadButton` → `winHandler.refreshControllerMappingsForHotplug()` e `setCurrentController()` — re-scan de mappings SDL com o menu aberto | XServerScreen.kt:1555-1564 | Candidato nº 1 ao estado corrompido que só restart resolve (re-init de mappings em pleno jogo pausado/menu aberto) |
| **C3** | **Registry frágil por `toString()` + churn de re-registro** — `EventDispatcher.off()` casa por `listener.toString()` (EventDispatcher.kt:41-47); `dismissOverlayMenu` é re-criado a cada recomposição do XServerScreen (polls de processos/HUD), e como `onCloseOverlay = onDismiss` é key do `DisposableEffect` do bridge (GamepadBusInput.kt:150), o bridge faz off/on a cada poll | EventDispatcher.kt:41-47; XServerScreen.kt:1099-1106; GamepadBusInput.kt:150; QuickMenu.kt:687-691 | Janelas de re-registro constantes; se algum `off()` falhar a identidade, listeners duplicados acumulam (vários navigators/bridges = movimentos duplicados ou comportamento caótico, permanente até restart) |
| **C4** | **Dupla navegação DPAD** — controles que emitem hat-axis E KEYCODE_DPAD_* no mesmo gesto movem o foco 2×: navigator (eixos) + bridge re-dispatchando a tecla crua para o Compose, cujo focus system trata DPAD nativamente | GamepadBusInput.kt:44-84 (moveFocus), 198-203 (re-dispatch) | Navegação pulando linhas/abas em controles que emitem os dois canais (documento de referência §1.3) |
| **C5** | **Race no bootstrap de foco** — `requestMenuFocus()` pode rodar concorrente: guardian (loop 400 ms) e restore de fechamento do browser são `LaunchedEffect` distintos; dois walk-downs simultâneos dobram o offset do índice lembrado; o guardian também pode dar `clearFocus(true)` no meio da navegação do usuário | QuickMenu.kt:1127-1191, 1216-1223, 1236-1248 | Foco restaurado na linha errada; sensação de "menu morto" intermitente |
| **C6** | **Browser de shaders sem guardião** — o guardian do menu é gateado por `!shaderBrowserOpen` (Missão 1 do spec anterior) e o browser não tem o seu: se o foco morrer dentro do browser (página "Show more", linhas de download mudando composição), nada restaura | QuickMenu.kt:1236-1248; ShaderBrowserOverlay.kt:241-269 (bootstrap one-shot, sem guardião) | Browser morto até PS/B fechar; sem recovery |
| **C7** | **Foco pode escapar do menu** — `moveFocus` a partir da borda pode pousar em nós focáveis fora do menu (superfícies do XServerScreen por trás do overlay); o guardian recupera (~400 ms), mas o salto é visível e pode cair em campo de texto/IME | GamepadBusInput.kt:79-82; QuickMenu.kt:1236-1248 | Salto de foco para fora do overlay; IME inesperado |
| **D1** | **Dead code** — `FocusRing.kt` (32 linhas, ZERO callers — o anel migrou para `GamepadFocus.kt`); `ModeKeyBehavior.None` só é usado pelo DEFAULT implícito do bridge no edit mode (XServerScreen.kt:2616) — a API não é explícita | FocusRing.kt (arquivo inteiro); GamepadBusInput.kt:112-118; XServerScreen.kt:2616 | Confunde diagnóstico; viola a prática de API explícita dos specs |

**Nota de honestidade:** C1 é comprovado por leitura de código (semântica de captura de
closure em Kotlin + `DisposableEffect(Unit)`); a manifestação on-device (o jogo recebendo
input por trás do menu e o hotplug churn) é a hipótese primária do sintoma do usuário e
deve ser CONFIRMADA na Fase 0 de instrumentação antes/durante a implementação.

---

## 2. Design das correções (missões)

### M1 — Estado de roteamento vivo (`OverlayInputState`) — corrige C1

**Novo** em `OverlayInputContext.kt` (junto do enum existente):

```kotlin
/**
 * Holder mutável do contexto de roteamento. O XServerScreen ESCREVE durante a composição
 * e os handlers do bus LEEM no momento do evento — o valor nunca é capturado stale por
 * closures registradas uma única vez (DisposableEffect(Unit)).
 */
class OverlayInputState {
    var context by mutableStateOf(OverlayInputContext.NONE)
        internal set
}
```

- XServerScreen: `val overlayInputState = remember { OverlayInputState() }`; durante a
  composição, `overlayInputState.context = if (…) OVERLAY else NONE` (o MESMO cálculo de
  XServerScreen.kt:1500-1508, agora alimentando o holder; remover o `val
  overlayInputContext` derivado).
- `onKeyEvent`/`onMotionEvent` passam a ler `overlayInputState.context` no momento do
  evento (os handlers registrados em `DisposableEffect(Unit)` capturam o HOLDER, estável —
  não o valor).
- `waitingForManualResume` continua como está (já lê state-backed no call time).

**Aceite:** com menu aberto, log do handler mostra `context=OVERLAY` (novo log da M8) e o
branch de jogo NUNCA roda; jogo não recebe tecla/motion com overlay aberto.

### M2 — Single-writer: zero efeitos colaterais de jogo com overlay aberto — corrige C2

Com M1, o branch `else` (jogo) só roda com `context == NONE` — o gate já fica correto.
Reforços desta missão:

- No branch OVERLAY do `onKeyEvent`, manter `false` (Compose decide) e **nunca** tocar em
  `ControllerManager`/`winHandler`/`physicalControllerHandler` — afirmar isso com o log da
  M8 (qualquer ocorrência de `noteGamepadButton`/`refreshControllerMappingsForHotplug` com
  overlay aberto = FAIL).
- Documentar no topo dos handlers a semântica: **emit = multicast; consumo = decisão de
  janela; cada listener é dono de UMA superfície e deve ser inerte fora dela.** Donos
  atuais (sem mudança estrutural):
  - OVERLAY (menu/browser/edit): navigator + bridge do bus (superfícies Compose).
  - NONE: XServerScreen (jogo).
  - Diálogos: janelas separadas — nunca chegam a este bus (invariante existente, mantido).

**Aceite:** 20 ciclos abrir/fechar com `logcat` greppando
`refreshControllerMappingsForHotplug` → 0 ocorrências com overlay aberto.

### M3 — Registry por identidade + callbacks estáveis — corrige C3

- **EventDispatcher.kt:** a chave de cada listener passa a ser a **instância do lambda**
  (identidade), não `listener.toString()`:
  - `listeners: MutableMap<KClass<out Event<*>>, MutableList<Pair<Any, EventListener<...>>>>`
    onde `Pair.first` é o próprio lambda passado a `on()`; `off()` remove por `===`.
  - Manter `once`, `clearAllListeners`, `emit` (snapshot `toList()` continua).
  - Expor `fun listenerCount(): Map<String, Int>` (nome da classe do evento → contagem) —
    base da instrumentação M8.
- **GamepadBusInput.kt (bridge):** os keys do `DisposableEffect` deixam de incluir
  `onCloseOverlay` (identidade instável): `val currentOnClose by rememberUpdatedState(onCloseOverlay)`
  e keys = `(enabled, view, modeKeyBehavior)`; o handler invoca `currentOnClose()`.
  Mesmo padrão no navigator se algum callback for adicionado (hoje keys = `(enabled)`, ok).
- **XServerScreen.kt:** tornar `dismissOverlayMenu` estável (`remember { … }` com as
  leituras dentro, ou passar `::`-bound estável) para não propagar instabilidade ao
  QuickMenu; onde não for possível, o `rememberUpdatedState` da M3 já neutraliza.
- **API explícita (dead code D1 parcial):** `BusGamepadKeyBridge(enabled = true)` do edit
  mode (XServerScreen.kt:2616) passa a declarar `modeKeyBehavior = ModeKeyBehavior.None`
  explicitamente.

**Aceite:** `listenerCount()` de `AndroidEvent.KeyEvent`/`MotionEvent` constante (±0) após
20 ciclos abrir/fechar menu + browser + edit mode; nenhum log de re-registro em rajada
durante polls (log da M8).

### M4 — Dedupe DPAD-key × hat-motion — corrige C4

**Novo** `app/src/main/java/app/gamenative/ui/component/GamepadMoveDedupe.kt` (lógica pura,
padrão `GamepadStickLogic`):

```kotlin
object GamepadMoveDedupe {
    const val WINDOW_MS = 120L

    /**
     * Decide se uma tecla DPAD deve ser re-dispatchada para o Compose: NÃO quando um
     * movimento de eixo (hat/stick) do MESMO gesto já moveu o foco dentro da janela.
     * Repeats (repeatCount > 0, segurar o D-pad) sempre passam — é o canal de repetição
     * contínua; o canal de eixos não repete enquanto segurado.
     */
    fun shouldDispatchKeyMove(now: Long, lastMoveAt: Long, repeatCount: Int): Boolean

    /**
     * Decide se um movimento de eixo deve mover o foco: NÃO quando uma tecla DPAD do
     * mesmo gesto já moveu dentro da janela (o bridge estampa o relógio ao dispatchar).
     */
    fun shouldDispatchAxisMove(now: Long, lastMoveAt: Long): Boolean
}
```

- `GamepadNavigationClock.lastMoveAt` (GamepadBusInput.kt:100-103) vira a fonte única dos
  dois canais (navigators já estampam; o bridge passa a estampar quando dispatcha um
  DPAD com `repeatCount == 0`).
- `BusGamepadKeyBridge` (GamepadBusInput.kt:198-203): para `KEYCODE_DPAD_*`, antes do
  re-dispatch, consulta `GamepadMoveDedupe`; suprimido → consome sem dispatchar.
- `BusJoystickFocusNavigator`/`JoystickFocusNavigator`: antes do `moveFocus`, consulta a
  decisão de eixo com o mesmo relógio.
- Testes JVM (`GamepadMoveDedupeTest`): primeiro canal vence; segundo suprimido dentro de
  120 ms; fora da janela passa; repeat de tecla passa sempre; limites exatos (120 ms).

**Aceite:** num controle que emite os dois canais, 1 pressão = 1 movimento de foco (log
`BusJoystick: moveFocus` + bridge debug contam 1 evento por gesto).

### M5 — Bootstrap de foco serializado + guardian gentil — corrige C5 (e mitiga C7)

- **Mutex único no QuickMenu:** `val focusMutex = remember { Mutex() }`; `requestMenuFocus()`
  envolve o corpo inteiro em `focusMutex.withLock { … }`. Guardian (:1236-1248) e restore
  de fechamento do browser (:1216-1223) passam a compartilhar o mesmo caminho — dois
  walk-downs simultâneos ficam impossíveis.
- **Guardian gentil:** antes de restaurar, se
  `SystemClock.uptimeMillis() - GamepadNavigationClock.lastMoveAt < 600` (usuário navegando
  ativamente), pula o ciclo (nunca `clearFocus(true)` no meio de um gesto).
- **Fuga de foco (C7):** coberta pelo guardian existente (foco fora do menu ⇒
  `menuHasFocus=false` ⇒ restore); a gentileza evita o pior caso (guardian limpando o foco
  que o usuário está usando). Verificação explícita na Fase 4 (navegar até a borda e além:
  foco não "pula" para fora perceptivelmente; se pular, volta sem intervalo morto).
- Sem mudança no `browserWasOpen` latch (continua necessário contra o bootstrap de
  abertura; agora serializado pelo mutex).

**Aceite:** log de dois walk-downs simultâneos nunca aparece (novo log no mutex: entrada/
saída com tab); restore de fechamento do browser cai na linha lembrada exata.

### M6 — Guardião do browser de shaders — corrige C6

Em `ShaderBrowserOverlay.kt`:

- `var browserHasFocus by remember { mutableStateOf(false) }` no Box raiz (`.onFocusChanged`),
  mesmo padrão do menu.
- `LaunchedEffect(Unit)` guardião: loop 400 ms; se `!browserHasFocus`, re-request
  `requesterFor(índice lembrado da tela atual)` com fallback para `requesterFor(0)`
  (reaproveitar o padrão de :241-269); gentileza via `GamepadNavigationClock` (M5).
- O guardião do MENU permanece gateado por `!shaderBrowserOpen` (Missão 1 do spec
  anterior) — os dois guardiões nunca coexistem.

**Aceite:** remover programaticamente a linha focada dentro do browser (ex.: busca limpa
troca a lista, "Show more" muda a página) ⇒ foco volta sozinho ≤ 400 ms (log novo
`ShaderBrowser guardian:`), sem PS/B.

### M7 — Remoção de dead code — corrige D1

- **Deletar** `app/src/main/java/app/gamenative/ui/component/FocusRing.kt` (zero callers;
  substituído por `GamepadFocus.kt` desde o spec 2026-08-09).
- **API explícita** no edit mode (M3): `modeKeyBehavior = ModeKeyBehavior.None` declarado.
  O enum fica (ambos os valores têm dono: `CloseOverlay` = menu/browser; `None` = edit
  mode) — a decisão de remover o valor exigiria reescrever o comportamento do edit mode;
  fora de escopo.
- Auditoria final do package `ui/component` por símbolos sem caller (mesma metodologia da
  G11 do spec 2026-08-10): candidatos sob revisão — `GamepadActionBar.visible`,
  `routeToCloseButton` (usado via focusProperties), `GLScreenEffectsTabContent` (caminho
  GL ativo, KEEP). Resultado registrado no spec de implementação.

### M8 — Instrumentação (Fase 0 do plano) — evidência antes/durante o fix

Logs DEBUG-only (`Timber.d`, que já só planta em debug):

| Tag | Onde | O quê |
|---|---|---|
| `GamepadTrace` | MainActivity.kt:576-614 | cada key/motion gamepad emitido: device, source, action, keyCode/axes |
| `GamepadRoute` | XServerScreen.kt:1510-1665 | contexto consultado, branch tomado (OVERLAY/NONE/manual-resume), efeitos de jogo executados (inclui `refreshControllerMappingsForHotplug`) |
| `EventBus` | EventDispatcher.kt | `on`/`off` com contagem por classe (`listenerCount()`) |
| `BusJoystick`/`BusGamepadKeyBridge` | GamepadBusInput.kt | existentes ("listening") + "stopped remaining=N"; decisões do dedupe (M4) |
| `QuickMenu guardian`/`bootstrap` | QuickMenu.kt | existentes + entrada/saída do mutex (M5) |
| `ShaderBrowser guardian` | ShaderBrowserOverlay.kt | novo (M6) |

`tools/quickmenu-verify.sh` estendido com greps dos tags acima + `listenerCount()` antes/
depois de cada ciclo (Fase 4).

---

## 3. Arquivos afetados

**Novos:**

- `app/src/main/java/app/gamenative/ui/component/GamepadMoveDedupe.kt` — lógica pura de
  dedupe entre canais de movimento (M4).
- `app/src/test/java/app/gamenative/ui/component/GamepadMoveDedupeTest.kt` — testes JVM
  (M4).

**Modificados:**

| Arquivo | Mudança |
|---|---|
| `ui/component/OverlayInputContext.kt` | novo holder `OverlayInputState` (M1) |
| `ui/screen/xserver/XServerScreen.kt` | escreve o holder na composição; handlers leem no call time; logs `GamepadRoute`; `modeKeyBehavior` explícito no edit mode; `dismissOverlayMenu` estável (M1/M2/M3/M8) |
| `events/EventDispatcher.kt` | registry por identidade (`===`), `listenerCount()`, logs (M3/M8) |
| `ui/component/GamepadBusInput.kt` | `rememberUpdatedState(onCloseOverlay)`; dedupe DPAD (M4) com estampa do relógio; logs de dispose com contagem (M3/M4/M8) |
| `ui/component/JoystickFocusNavigator.kt` | consulta `GamepadMoveDedupe` no caminho de eixos (M4) |
| `ui/component/QuickMenu.kt` | `focusMutex` em `requestMenuFocus()`; guardian gentil; logs do mutex (M5/M8) |
| `ui/component/ShaderBrowserOverlay.kt` | guardião próprio do browser + `browserHasFocus` + logs (M6/M8) |
| `MainActivity.kt` | logs `GamepadTrace` (M8) |
| `tools/quickmenu-verify.sh` | cenários novos + greps de instrumentação (Fase 4) |
| `docs/MILESTONES.md` | entrada ao final da implementação |

**Removidos:**

- `ui/component/FocusRing.kt` (M7).

**Não tocados (proteções):** `PhysicalControllerHandler` (caminho do jogo), `WinHandler`/
native, `VulkanLibrashader`/renderer, `GamepadStickLogic` (lógica validada), o framework
`GamepadModifiers`/`GamepadFocus`, os 6 diálogos migrados, `SteamInviteState`, o harness
`DebugGamepadInput` (exceto se a Fase 0 revelar interação — qualquer mudança volta ao
usuário).

---

## 4. Verificação

### 4.1 JVM (unit)

- `GamepadMoveDedupeTest` — casos da M4 (primeiro canal vence, janela, fronteiras,
  repeat-passthrough).
- Suites existentes verdes: `GamepadStickLogicTest` (7), `GamepadModifiersTest` (19),
  `ShaderDoubleClickLogicTest`, `ShaderToggleSubtitleTest`, `ShaderConfigResolveTest`,
  `SearchFieldImeLogicTest`, `FpsLimiter` — comando:
  `./gradlew :app:testDebugUnitTest --tests "*Gamepad*" --tests "*Shader*" --tests "*SearchField*"`.

### 4.2 On-device (harness `debug.gamenative.input` + controle físico, Mi 11)

| # | Cenário | Critério |
|---|---|---|
| V1 | 20 ciclos PS abre/fecha + browser abre/fecha + edit mode + 1 diálogo | `listenerCount()` de KeyEvent/MotionEvent estável (±0) ao fim; log sem re-registro em rajada |
| V2 | Menu aberto: apertar TODAS as teclas gamepad (A/B/L1/R1/L2/R2/DPAD/PS/START/SELECT) + stick | `GamepadRoute` mostra OVERLAY; ZERO `refreshControllerMappingsForHotplug`/`winHandler` com overlay aberto (C1/C2 fechadas) |
| V3 | Menu aberto, 30 s navegando | jogo congelado/invisível não recebe nada (log winHandler limpo); navegação 1 linha por gesto |
| V4 | Controle que emite DPAD key+hat (ou simular com harness: `stick` + `key:20` juntos) | 1 gesto = 1 movimento (M4) |
| V5 | Efeito que remove a linha focada (colapso de categoria, limpar busca, "Show more" no browser) | foco volta sozinho ≤ 400 ms sem PS/B (menu E browser — M6) |
| V6 | Fechar browser na linha N | menu volta com foco na linha N exata (mutex M5 — sem walk-down duplo) |
| V7 | P1 | START com menu fechado abre; com menu aberto fecha; SELECT nunca vaza; PS toggle intacto |
| V8 | Reconectar o controle com o menu aberto; desconectar o celular | navegação segue funcional; sem necessidade de restart |
| V9 | Sessão contínua 10 min alternando menu/browser/diálogos/jogo | nenhuma ocorrência de "menu morto" |
| V10 | Regressão: jogo SEM overlay | controle físico continua funcionando no jogo (PhysicalControllerHandler intocado) |

### 4.3 Aceite global

- T1–T9 da spec 2026-08-10 e F1–F10 do spec de browser (pendências on-device existentes)
  permanecem verdes quando re-testados no mesmo ciclo.
- `assembleModernDebug` compila sem warnings novos de Kotlin.

---

## 5. Fora de escopo / follow-ups

- GameInputManager/FSM centralizado (decisão do usuário: mínimo no bus atual).
- `explicitApi()` global, configuration cache e hygiene de versões (follow-ups do repo).
- Diálogos em janelas separadas (padrão `GamepadFocusScope`) — estáveis; só herdam o
  dedupe se usarem `JoystickFocusNavigator` (M4 aplica por construção, sem mudança de API).
- Unificação do remember-selection por aba (G9 futuro) usando o `requestMenuFocus()`
  serializado — facilitada pelo mutex, mas fora deste escopo.

## 6. Checklist de execução (ordem)

1. M8 (instrumentação) — evidência de C1/C2 no device antes de tocar no roteamento.
2. M1 + M2 (roteamento vivo + single-writer) — coração do fix.
3. M3 (registry por identidade + callbacks estáveis + API explícita edit mode).
4. M4 (dedupe) + testes JVM.
5. M5 (mutex + guardian gentil).
6. M6 (guardião do browser).
7. M7 (dead code + auditoria de callers).
8. Fase 4 completa (4.1 + 4.2 + 4.3); entrada em `docs/MILESTONES.md`.
