# Compatibilidade total do QuickMenu com joystick — auditoria e correções (2026-08-10)

> **Problema:** com o jogo aberto, o usuário abre o QuickMenu pelo botão Home/PS do controle e
> navega até a aba de shaders (EFFECTS). O menu *parece* funcional, mas por vezes a navegação
> pelo stick **morre** depois de interagir (toggle de shader, colapso de categoria, busca,
> limpar busca). Sintoma exato reportado: "perde a habilidade de navegação pelos menus".
>
> **Escopo (decisão do usuário):** garantir que **todo** o QuickMenu — rail, 7 abas, rodapé,
> diálogos alcançáveis e modo de edição de controles — seja 100% navegável por joystick.
> Este spec documenta a auditoria linha a linha e o design das correções. **Nenhum código é
> alterado até a revisão deste spec** (fluxo de trabalho: spec → revisão → implementação).
>
> **Base:** spec `2026-08-09-quickmenu-joystick-navigation-design.md` (implementado em
> `92f0c4b4`); este spec é a camada de **garantia**: audita o que foi feito, encontra as
> causas-raiz da navegação morta e cobre as lacunas restantes.
> **Alinhamento 2026:** práticas oficiais de Kotlin (convenções, API explícita, paradigma
> FP/OO, organização de projeto, Gradle) aplicadas ao design — §3.8.

## 1. Contexto & princípios

- **Mobile-first:** neste handheld o gamepad é o modo principal de input; o toque é luxo.
- **100% sem toque** no QuickMenu: abrir → navegar → ativar → ajustar → voltar → fechar.
- **Zero regressão de toque** e **caminho do jogo intocado** (`PhysicalControllerHandler` /
  `WinHandler` / `evshim.c`).
- **Consistência (Norman):** mesmas teclas = mesmos significados em todas as superfícies do
  QuickMenu; uma única linguagem visual de foco; seleção ≠ foco.
- **Nunca nascer morto:** o menu aberto sempre tem foco em algum lugar; se o foco for perdido
  por qualquer reestruturação, ele é restaurado automaticamente.
- **Práticas Kotlin 2026** (aplicadas no design, §3.8): lógica pura com estado imutável (FP),
  API explícita no código novo, composição em vez de parâmetros, extensões, `enum` no lugar de
  Boolean ambíguo, nomenclatura e organização de arquivos conforme as convenções oficiais.

## 2. Auditoria — estado atual (verificado linha a linha)

### 2.1 Pipeline de input (funciona, fica)

```
Home/PS (KEYCODE_BUTTON_MODE) → MainActivity.dispatchKeyEvent (MainActivity.kt:576-604)
        │  emite TUDO no bus PluviaApp.events (EventDispatcher.kt:61-73 — TODOS os listeners rodam)
        ▼
XServerScreen.onKeyEvent / onMotionEvent (XServerScreen.kt:1466-1608)
        │  consulta OverlayInputContext (fonte única, XServerScreen.kt:1456-1464)
        ├── OVERLAY → teclas: false (Compose decide); motion: true (consome — o jogo nunca vê o stick)
        └── NONE    → PhysicalControllerHandler (PhysicalControllerHandler.kt:87-118)
                        └── OPEN_NAVIGATION_MENU (linha 396) → gameBack (XServerScreen.kt:1347) → showQuickMenu
```

Com o overlay aberto, o QuickMenu instala no Box raiz (QuickMenu.kt:564-567):

- **`BusJoystickFocusNavigator`** (GamepadBusInput.kt:32-89) — eixos `AXIS_X/Y` + hat
  `AXIS_HAT_X/Y` → `focusManager.moveFocus`; dead zone 0.45, release 0.30, cooldown 180 ms.
- **`BusGamepadKeyBridge`** (GamepadBusInput.kt:106-148) — `BUTTON_A` → `DPAD_CENTER` sintético
  (com haptics); `BUTTON_B`/`L1`/`R1`/`L2`/`R2`/`DPAD_*`/`ENTER` redistribuídos crus; todos
  consumidos no nível do bus (o jogo não os vê).

Diálogos (janelas separadas) recebem eventos direto na view e usam o framework view-level:
`JoystickFocusNavigator` (JoystickFocusNavigator.kt:27-83) + `GamepadKeyBridge`
(GamepadKeyBridge.kt:25-50) via `GamepadFocusScope` (GamepadModifiers.kt:243-272).

### 2.2 Já compatível (verificado)

| Item | Onde | Status |
|---|---|---|
| Rail inteira (tab buttons, Exit, Close) focável + A-ativável (`gamepadSelectable`) + focusRequesters | QuickMenu.kt:640-768, 1752-1859 | ✓ |
| Abas HUD/CONTROLLER/TOOLS/INVITE: toggles/chips/radios/rows com `gamepadSelectable`; adjustment rows com `gamepadAdjustableRow` (A-lock, B-unlock, reset em blur) | QuickMenu.kt:1156-1500, 911-951, 1095-1154, 1026-1093 | ✓ |
| L1/R1 trocam aba; L2/R2 scroll por página; footer hints `GamepadActionBar` (só visual) | QuickMenu.kt:521-559, 959-970 | ✓ |
| B hierárquico (conteúdo→aba→fechar) via `backAction` + `railFocused`; caminhos disjuntos (B cru vs BACK físico) | QuickMenu.kt:497-515 | ✓ |
| Bootstrap de foco com 3 retries/80ms + fallback para a rail (TOOLS/INVITE vazios) | QuickMenu.kt:982-1023 | ✓ |
| Bus consumindo o stick enquanto overlay aberto (jogo intocado) | XServerScreen.kt:1561-1567 | ✓ |
| Diálogos (ElementEditor, TouchGesture, ShooterMode, PhysicalController, ControllerBinding, PlayingBlocked): `GamepadFocusScope` + B-close + foco inicial | ElementEditorDialog:340, TouchGesture:97, ShooterMode:81, PhysicalControllerConfigSection:228, ControllerBindingDialog:133, XServerScreen:2793 | ✓ |
| Sliders Material3 com A-lock (`LockableSliderRow`, `canFocus=false` no Slider) | ElementEditorDialog:1171-1217, ShooterModeSettingsDialog:440-509 | ✓ |
| Pickers aninhados com `GamepadFocusScope` próprio | TouchGestureSettingsDialog:776-809 | ✓ |
| Scrim não-focável (pointerInput + detectTapGestures) | QuickMenu.kt:578-584 | ✓ |
| Harness de debug (setprop) para reprodução sem dispositivo físico | DebugGamepadInput.kt:44-170 | ✓ |

### 2.3 Causas-raiz da navegação morta (prioridade de correção)

| # | Causa-raiz | Evidência | Mecanismo |
|---|---|---|---|
| **RC1** | **Deadlock de histerese no stick** — após **um** movimento, `armed=false` e só re-arma quando `magnitude < 0.30` (releaseZone). Um stick com drift/descanso em ≥ 0.30 engole **todos** os eventos seguintes: navegação morta após o 1º movimento até o stick voltar fisicamente ao centro. | GamepadBusInput.kt:57-60 (`if (magnitude < releaseZone) armed = true`), JoystickFocusNavigator.kt:55-57 | `armed` nunca re-arma → `return true` sem mover |
| **RC2** | **Foco Compose perdido quando a linha focada sai da composição** — a lista da aba EFFECTS é reestruturada por: toggle de shader (`shaderEnabled`, :703-813), colapso de categoria (`collapsedCategories`, :757-785), busca (`shaderQuery`, :743-811), expansão nativa (:832-998) e load assíncrono dos presets (:609-619). Se o nó focado é descartado, o `FocusOwner` fica sem nó e `moveFocus()` vira no-op — menu aberto, visualmente OK, joystick morto. Nada re-restaura (sem `focusRestorer`, sem re-bootstrap). | ScreenEffectsPanel.kt:703-813, 733-737 (ícone limpar: removido com `query=""`), 757-785, 832; Compose 1.8 limpa o foco ao remover o nó | nó focado removido → foco inexistente |
| **RC3** | **Race no bootstrap (G9 walk-down)** — `requestFocus()` é deferido; o `repeat(effectsFocusIndex) { moveFocus(Down) }` roda no mesmo frame (QuickMenu.kt:996-998) e atua com foco antigo/inexistente → restauração cai na linha errada. Agravado por (a) nós focáveis aninhados (clickables internos das linhas de ajuste) e (b) o campo de busca consumir slot sem rastreio. | QuickMenu.kt:993-998; ScreenEffectsPanel.kt:1390-1409, 1453; ScreenEffectsPanel.kt:722-724 | `moveFocus` conta nós físicos, não slots |

### 2.4 Gaps de compatibilidade restantes

| # | Gap | Evidência |
|---|---|---|
| G1 | **A em "No filter" mata o menu** — `disableShaders()` remove o bloco de shaders inteiro **incluindo o nó focado** (gatilho RC2 via joystick puro) | ScreenEffectsPanel.kt:705-712 + 626-630 |
| G2 | **A no ícone de limpar busca mata o menu** — `shaderQuery=""` remove o ícone focado | ScreenEffectsPanel.kt:733-737 |
| G3 | **Nós focáveis aninhados nas linhas de ajuste** — trilho ± e botões −/+ são `.clickable` focáveis: a navegação Down "para" até 5× por linha (trap) e quebra a contagem do walk-down | ScreenEffectsPanel.kt:1390-1409, 1453; QuickMenu.kt:2048, 2058, 2107 |
| G4 | **Campo de busca consome slot sem `gamepadFocusIndex`** — foco no campo nunca reporta índice; slot↔nó divergem | ScreenEffectsPanel.kt:722-724 |
| G5 | **`QuickMenuToggleRow` não repassa `enabled`** — linha desabilitada (FPS Limiter sob LSFG, :1187) continua focável → foco-trap (A não faz nada) | QuickMenu.kt:2165-2172 |
| G6 | **PS não fecha o menu** — com overlay aberto, `XServerScreen.onKeyEvent` devolve false no ramo gamepad (:1489-1506) e `KEYCODE_BUTTON_MODE` não é tratado por ninguém (não está no `handledKeys` do bridge, GamepadBusInput.kt:110-122). PS só funciona com menu fechado | XServerScreen.kt:1489-1506; GamepadBusInput.kt:110-122 |
| G7 | **Modo edição (`isEditMode`) sem navegação** — `EditModeToolbar` (XServerScreen.kt:2925) usa `TextButton` puros, sem navegador/bridge/bootstrap: o stick morre ao sair do QuickMenu para "Editar controles"; DropdownMenu "copy from" inalcançável por stick | XServerScreen.kt:2925-3035; `isEditMode` está no overlayInputContext (:1457) mas nenhum navegador está ativo |
| G8 | **Gear de configurações sem `gamepadSelectable`** — funciona via `clickable`+DPAD_CENTER "por acidente", sem anel e sem rastreio de foco | QuickMenu.kt:2445 |
| G9 | **`AccentActionRow` (reset) sem slot/gamepadFocusIndex** — focável, mas não rastreado (restauração nunca o alcança) | ScreenEffectsPanel.kt:990-995; AccentActionRow.kt:68-74 (clickable + focusRing manuais) |
| G10 | **INVITE vazio sem mensagem** — lista vazia cai no fallback da rail (funciona), mas sem explicar ao usuário | QuickMenu.kt:1054-1067 |
| G11 | **Standalone `ScreenEffectsPanel`** — código morto (sem caller) sem navegador; documentar/remover | ScreenEffectsPanel.kt:1003-1262 |
| G12 | **Riscos cross-input** — toque (colapso/toggle/troca de scalingMode) remove subárvore com foco do stick (mesma classe do G1, via toque) | ScreenEffectsPanel.kt:757-773, 862-865 |

## 3. Design

### 3.1 RC1 — `GamepadStickLogic` (lógica pura, imutável) + testes

**Arquivo novo** `app/src/main/java/app/gamenative/ui/component/GamepadStickLogic.kt`
(arquivo descritivo, declarações de nível superior — convenção oficial de nomenclatura; uma
classe/objeto por arquivo).

```kotlin
/** Direção de um movimento emitido; mapeia 1:1 para FocusDirection nos navegadores. */
enum class GamepadStickDirection { Up, Down, Left, Right }

/** Estado imutável do navegador (FP): o retorno da decisão carrega o novo estado. */
data class GamepadStickState(
    val armed: Boolean = true,
    val lastMoveAt: Long = 0L,
)

/** Resultado da decisão: move, ou evento consumido sem mover (com o estado atualizado). */
data class GamepadStickDecision(
    val state: GamepadStickState,
    val direction: GamepadStickDirection?,
)

object GamepadStickLogic {
    fun decide(
        previous: GamepadStickState,
        now: Long,
        magnitude: Float,
        direction: GamepadStickDirection?,
        deadZone: Float = 0.45f,
        cooldownMs: Long = 180L,
    ): GamepadStickDecision
}
```

Semântica (correção RC1):

- Re-arm quando `magnitude < deadZone` (0.45) — **não** mais `< releaseZone` (0.30). Um stick
  em repouso/drift de 0.30–0.44 re-arma; o cooldown de 180 ms impede free-run ao segurar.
- `direction == null` → consome sem mover (evento neutro não desarma).
- Cooldown derruba o movimento mas **preserva** o estado armado.
- Nenhum estado mutável: `previous` + parâmetros → `GamepadStickDecision`.

Os dois navegadores passam a **compor** esta lógica (elimina a duplicação atual):
`BusJoystickFocusNavigator` (GamepadBusInput.kt) e `JoystickFocusNavigator`
(JoystickFocusNavigator.kt). O cálculo de `magnitude`/`direction` continua no navegador
(Android: `MotionEvent`), a decisão fica na função pura.

Testes JVM primeiro (`app/src/test/java/app/gamenative/ui/component/GamepadStickLogicTest.kt`,
arquivo nomeado pela classe — convenção):

| Caso | Asserção |
|---|---|
| 1º push além do dead zone move imediatamente | `direction=Down`, `armed=false`, `lastMoveAt=now` |
| **Regressão:** repouso 0.40 (≥ release antigo, < dead zone) re-arma | após move, `update(mag=0.40, dir=null)` → re-armado; push seguinte move |
| Stick segurado acima do dead zone nunca re-arma | `mag=0.6` repetido → nunca move |
| Cooldown derruba movimento mas preserva armado | move→centro→push em 150 ms → sem move, `armed=true`; push em 250 ms → move |
| Hat com magnitude própria move independente do stick | hat 0.6 → move |
| Stick neutro não move nem desarma | `mag=0.1, dir=null` → consumido, `armed=true` |

### 3.2 RC2 — Guardião de foco (re-bootstrap automático)

No QuickMenu (Box raiz, QuickMenu.kt:517):

- `var menuHasFocus by remember { mutableStateOf(false) }` + `.onFocusChanged { menuHasFocus = it.hasFocus }`.
- Extrair o bootstrap atual (QuickMenu.kt:982-1023) para `suspend fun requestMenuFocus()`
  (função local, usada pelos dois efeitos — **composição em vez de duplicação**).
- Novo `LaunchedEffect(isVisible, menuHasFocus)`: se `isVisible && !menuHasFocus` →
  `delay(150)` → re-checa → `requestMenuFocus()`. O re-cheque evita roubar o foco quando o
  bootstrap de abertura ou o próprio input do usuário já pousou o foco.

Cobertura: **todas** as abas e gatilhos — G1 (toggle "No filter"), G2 (limpar busca),
colapso de categoria, load assíncrono, G12 (cross-input) — o guardião restaura pelo índice
lembrado da aba selecionada.

### 3.3 RC3 — Restauração de posição sem race

- No `requestMenuFocus()`: para a aba EFFECTS, `effectsItemFocusRequester.requestFocus()` →
  `withFrameNanos { }` (aguarda o foco aterrissar) → `repeat(effectsFocusIndex) { moveFocus(Down) }`.
  Sem o boundary de frame, o walk atua com foco velho (QuickMenu.kt:996-998).
- **G4:** o campo de busca ganha `gamepadFocusIndex(nextFocusSlot(), onFocusIndexChanged)`
  (ScreenEffectsPanel.kt:722-724) — slots contíguos ao foco real.
- **G3:** com 1 nó por linha (§3.4), o walk conta linhas corretamente.

### 3.4 Linhas de ajuste — 1 nó focável por linha

Trilho ± e botões −/+ são *touch-only*: `.focusProperties { canFocus = false }` **antes** do
`.clickable(...)` (mantém toque/ripple/semântica, remove do foco Compose):

- `ScreenEffectAdjustmentRow` (ScreenEffectsPanel.kt:1390-1409, 1453).
- `QuickMenuAdjustmentRow` (QuickMenu.kt:2048, 2058, 2107) — mesma mecânica na aba HUD.
- O nó externo `gamepadAdjustableRow` continua sendo **o** alvo de foco da linha (A-lock,
  DPAD_L/R ajusta, B unlock).
- **G5:** `QuickMenuToggleRow` repassa `enabled = enabled` ao `gamepadSelectable`
  (QuickMenu.kt:2165-2172) — linha desabilitada não-focável (sem foco-trap).

### 3.5 G6 — PS fecha o menu (enum, não Boolean ambíguo)

Em `BusGamepadKeyBridge` (GamepadBusInput.kt):

```kotlin
enum class ModeKeyBehavior { CloseOverlay, None }
```

- `KEYCODE_BUTTON_MODE` entra no bridge (antes ignorado); `ACTION_DOWN` com `repeatCount == 0`
  → `ModeKeyBehavior.CloseOverlay` invoca o callback de fechamento; DOWN/UP sempre consumidos.
- QuickMenu passa `ModeKeyBehavior.CloseOverlay` (`onDismiss`) — **alternância**: PS abre
  (caminho `PhysicalControllerHandler`, menu fechado) e fecha (bridge, menu aberto).
- Comportamento padrão `None` preserva o estado atual para outros usos futuros do bridge.
- O jogo nunca vê a tecla Mode (consumida no bus; `XServerScreen.onKeyEvent` devolve false no
  ramo overlay).

### 3.6 G7 — Modo edição navegável

- Navegadores bus-level ativos enquanto `isEditMode && !showQuickMenu` (na XServerScreen,
  junto ao `EditModeToolbar`): `BusJoystickFocusNavigator(enabled = true)` +
  `BusGamepadKeyBridge(enabled = true)` (PS mapeado como `None` — alternar menu no meio de uma
  edição seria surpreendente).
- Bootstrap de foco no primeiro botão (Add) do `EditModeToolbar` (XServerScreen.kt:2925):
  `FocusRequester` + `LaunchedEffect(isEditMode, areControlsVisible)` com 3 retries.
- `gamepadBackHandler(onClose)` no Box raiz da toolbar — **B = Close** (cancela a edição,
  restaura o snapshot), paridade com o botão Close.
- DropdownMenu "copy from" torna-se alcançável (foco por stick, DPAD navega os itens, A ativa).

### 3.7 Ergonomia restante

- **G8 — Gear:** `secondaryIcon` migra de `clickable(role=Role.Button)` para `gamepadSelectable`
  (QuickMenu.kt:2445) — anel de foco + rastreio; continua sendo um nó à parte (acionável).
- **G9 — Reset:** `AccentActionRow` (AccentActionRow.kt) migra para `gamepadSelectable` +
  `gamepadFocusIndex(index, onChange)` (parâmetros opcionais, sem quebrar call sites); call
  sites da aba EFFECTS passam `nextFocusSlot()`/`onFocusIndexChanged` (ScreenEffectsPanel.kt:990-995).
- **G10 — INVITE vazio:** mensagem explícita quando `friends.isEmpty()` (string nova
  `steam_invite_no_friends`, EN + pt-rBR).
- **G11 — Standalone:** remover `ScreenEffectsPanel` morto (ScreenEffectsPanel.kt:1003-1262)
  ou documentar como fora de uso — decisão do revisor.

### 3.8 Aplicação das práticas Kotlin 2026 (mapa)

| Prática 2026 | Aplicação |
|---|---|
| **FP — imutabilidade e funções puras** | `GamepadStickState`/`GamepadStickDecision` imutáveis; `decide()` pura e testável em JVM (nenhum `var` na lógica) |
| **API explícita** | Disciplina no código novo (visibilidade e tipos de retorno explícitos em `GamepadStickLogic.kt` e no enum `ModeKeyBehavior`); `explicitApi()` global **não** habilitado (módulo com milhares de declarações públicas — fora de escopo), convenção documentada aqui |
| **Evitar Boolean ambíguo** | Tecla Mode → `enum ModeKeyBehavior`; demais `enabled`/`isVisible` são idiomáticos do Compose e permanecem |
| **Composição em vez de parâmetros** | Um `requestMenuFocus()` compartilhado (bootstrap + guardião); um `decide()` compartilhado pelos 2 navegadores (remove duplicação de histerese) |
| **Extensões** | `Modifier.gamepadFocusIndex`, `Modifier.gamepadSelectable` etc. (padrão existente, mantido); mapeamento direção → `FocusDirection` como extension property |
| **Nomenclatura** | `GamepadStickLogic.kt`/`GamepadStickLogicTest.kt` (arquivo = nome da classe); camelCase (classes `GamepadStickState`, funções `decide`) |
| **Organização de projeto** | Lógica pura no mesmo módulo `app` (decisão do usuário), package `ui.component` (já contém `GamepadKeyLogic`) |
| **Gradle/segurança** | Já em conformidade: KSP (não kapt), catálogo `libs.versions.toml`, Kotlin DSL — sem mudanças neste escopo; hygiene de versões/CVE é follow-up do repositório |

## 4. Arquivos afetados

**Novos:**

- `app/src/main/java/app/gamenative/ui/component/GamepadStickLogic.kt` — enum `GamepadStickDirection`,
  `data class GamepadStickState`, `data class GamepadStickDecision`, `object GamepadStickLogic`.
- `app/src/test/java/app/gamenative/ui/component/GamepadStickLogicTest.kt` — testes JVM (§3.1).

**Modificados:**

| Arquivo | Mudança |
|---|---|
| `ui/component/GamepadBusInput.kt` | `BusJoystickFocusNavigator` usa `GamepadStickLogic` (remove `armed`/`releaseZone` locais); `BusGamepadKeyBridge` ganha `enum ModeKeyBehavior` + `KEYCODE_BUTTON_MODE` |
| `ui/component/JoystickFocusNavigator.kt` | mesma composição da lógica (remover `releaseZone` do parâmetro) |
| `ui/component/QuickMenu.kt` | guardião `menuHasFocus` + `onFocusChanged` na raiz; `requestMenuFocus()` extraído; `withFrameNanos` no walk-down; `BusGamepadKeyBridge(ModeKeyBehavior.CloseOverlay)`; `QuickMenuToggleRow` repassa `enabled`; desfocar trilho/± (HUD); gear → `gamepadSelectable`; INVITE vazio (string nova) |
| `ui/component/ScreenEffectsPanel.kt` | desfocar trilho/± (linhas de ajuste); `gamepadFocusIndex` no campo de busca; `AccentActionRow` com slot nos 2 call sites; remover/documentar standalone morto |
| `ui/component/AccentActionRow.kt` | migrar para `gamepadSelectable` + `gamepadFocusIndex` |
| `ui/screen/xserver/XServerScreen.kt` | navegadores bus-level no `isEditMode`; `EditModeToolbar` com `focusRequester` + `gamepadBackHandler(onClose)` |
| `res/values/strings.xml`, `res/values-pt-rBR/strings.xml` | `steam_invite_no_friends` |

**Não tocados (verificado em auditoria):** `MainActivity`, `EventDispatcher`,
`PhysicalControllerHandler`, `WinHandler`, `GamepadModifiers.kt` (framework estável), os 5
diálogos já migrados (a menos que o revisor queira consolidar visual).

## 5. Verificação

### 5.1 Unit (JVM)

- `GamepadStickLogicTest.kt` — casos da tabela §3.1 (inclui o teste de regressão do drift).
- Suite existente `GamepadModifiersTest.kt` (19 testes) permanece verde (sem mudança no framework).

### 5.2 On-device (checklist completo, via harness + logcat)

Reprodução com `adb shell setprop debug.gamenative.input` (`key:188` = PS, `stick:x:y`,
`key:96` = A, `key:97` = B) e logcat (`BusJoystick: moveFocus`, `QMFocus: row N focused`,
`QuickMenu bootstrap`):

| # | Tarefa | Critério |
|---|---|---|
| T1 | Abrir/fechar pelo PS | PS abre com o menu fechado; PS **fecha** com o menu aberto; nenhum leak pro jogo |
| T2 | Navegar as 7 abas | stick e hat movem 1 linha por gesto; L1/R1 trocam aba; L2/R2 paginam |
| T3 | EFFECTS: toggle de shader | A em "No filter" desliga sem matar o foco (guardião restaura) — `QMFocus` segue fluindo |
| T4 | EFFECTS: colapso de categoria + busca + limpar | mesma garantia; campo de busca focável sem travar Up/Down; A no limpar não mata o foco |
| T5 | Restauração de posição | fechar na linha N da EFFECTS, reabrir → foco na mesma região (índice lembrado, sem race) |
| T6 | Ajustes | A trava, DPAD_L/R ajusta, B destrava; navegação passa pela linha com 1 único stop (sem traps nos botões ±) |
| T7 | Modo edição | "Editar controles" → toolbar focada; stick navega Add/Edit/Delete/Copy/Save/Close; B fecha (cancela); DropdownMenu "copy from" operável |
| T8 | Diálogos | TouchGesture/ShooterMode/PhysicalController/ControllerBinding/ElementEditor/PlayingBlocked: stick + A + B hierárquico; sliders com A-lock |

### 5.3 Regressão

- Toque intocado: tap/scroll/drag continuam funcionando (os clickables desfocados mantêm o
  handler de ponteiro).
- Jogo intocado: stick e teclas do gamepad **não** chegam ao guest com qualquer overlay aberto
  (já verificado no pipeline; re-teste após G6).

## 6. Aceite por tarefa (R4 — sem toque, <10 s, zero fechamentos acidentais)

| # | Tarefa | Critério |
|---|---|---|
| T1–T8 | §5.2 | todas verdes, sem toque, sem fechamento acidental (scrim inerte) |
| T9 | Sessão contínua | 10 min navegando abas/diálogos/modo edição sem nenhuma ocorrência de "menu morto" |

## 7. Fora de escopo / follow-ups

- Restante do app (biblioteca, login, downloads, settings, OAuth).
- `explicitApi()` global e otimizações de build (configuration cache, convention plugins) —
  follow-ups de repositório, não desta feature.
- Hygiene de versões/CVE (prática de segurança 2026) — follow-up de repositório.
- G9-índice por aba para HUD/LSFG/BFG (hoje só EFFECTS tem remember-selection) — pode ser
  unificado no futuro usando o mesmo `requestMenuFocus()`.
