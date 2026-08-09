# Navegação por joystick completa no QuickMenu (2026-08-09)

> **Problema:** o QuickMenu — o painel que abre sobre o jogo (abas HUD / LSFG / EFFECTS /
> CONTROLLER / TOOLS / BFG / INVITE), responsável por trocar shader, usar shaders RetroArch e
> ajustar efeitos — ainda não é 100% navegável por joystick. Já existe infraestrutura sólida
> (navigator, bridge, BackHandler hierárquico, haptics, hints), mas a cobertura é assimétrica:
> três mecanismos de ativação concorrentes, linguagem visual de foco heterogênea, sub-diálogos
> com B morto, sliders Material3 sem A-lock, e strings não localizadas.
>
> **Escopo (decisão do usuário):** apenas o QuickMenu e as superfícies alcançáveis a partir
> dele. O restante do app (biblioteca, login, settings, downloads) fica para outro momento.
> **Spec independente** (não substitui, mas completa formalmente as fases 2.1 e 2.4 pendentes
> do spec `2026-08-08-gamepad-input-refactoring-design.md`).
> **Política de referência:** nowinandroid = métodos modernos de código, não design system
> (ver `2026-08-09-retroarch-nav-lessons.md`, §Política). Linguagem visual Pluvia preservada.

## 1. Contexto & princípios

- **Mobile-first:** neste handheld o gamepad é o modo principal de input; o toque é luxo.
- **100% sem toque** no QuickMenu: abrir → navegar → ativar → ajustar → voltar → fechar.
- **Zero regressão de toque** e **caminho do jogo intocado** (`PhysicalControllerHandler` /
  `WinHandler` / `evshim.c`).
- **Consistência (Norman):** mesmas teclas = mesmos significados em todas as superfícies do
  QuickMenu; uma única linguagem visual de foco; seleção ≠ foco.
- **Aceite por tarefa (Spool):** T1..T7 mensuráveis, sem toque.

## 2. Estado atual (verificado linha a linha)

### 2.1 Pipeline de input (funciona, fica)

```
MainActivity.dispatchKeyEvent / dispatchGenericMotionEvent  (MainActivity.kt:576-614)
        │  emite TUDO no bus PluviaApp.events
        ▼
XServerScreen.onKeyEvent / onMotionEvent (XServerScreen.kt:1460-1599)
        │  consulta OverlayInputContext (enum NONE/OVERLAY, fonte única, linhas 1450-1458)
        ├── OVERLAY → devolve false → Compose (QuickMenu / dialogs)
        └── NONE    → PhysicalControllerHandler/WinHandler (jogo)
```

No Compose, o QuickMenu instala no Box raiz (QuickMenu.kt:450-452):
- **`JoystickFocusNavigator`** (JoystickFocusNavigator.kt:26-83) — eixos `AXIS_X/Y` + hat
  `AXIS_HAT_X/Y` → `focusManager.moveFocus`; dead zone 0.45, release 0.30, cooldown 180 ms,
  histerese (P3-20 resolvido).
- **`GamepadKeyBridge`** (GamepadKeyBridge.kt:24-50) — `BUTTON_A` → `DPAD_CENTER` sintético
  (com haptics), `BUTTON_B` fica **cru** (decisão D1) — superfícies tratam o B.

### 2.2 O que já funciona

| Item | Onde | Status |
|---|---|---|
| Abas com `focusGroup` + `FocusRequester` + foco volta à aba selecionada (railFocused) | QuickMenu.kt:427, 429-446, 524-648, 831-850 | ✓ |
| `JoystickFocusNavigator` + `GamepadKeyBridge` no Box raiz | QuickMenu.kt:450-452 | ✓ |
| BackHandler hierárquico (conteúdo→aba→fechar) | QuickMenu.kt:429-446 | ✓ |
| A-lock / B-unlock nas linhas de ajuste | QuickMenuAdjustmentRow 1742-1950 (lock 1757, preview 1804-1836), ScreenEffectAdjustmentRow 1258-1290 | ✓ (mas código duplicado) |
| `gamepadActivate` (DPAD_CENTER/A/ENTER) | ScreenEffectsPanel.kt:1526-1546, usado em toggles/headers/presets/radios | ✓ (mas mecânica triplicada) |
| Footer hints `GamepadActionBar` (A=Selecionar, B=Voltar, L1/R1=Abas, L2/R2=Página) | só com gamepad conectado, localizado 14 locales | ✓ |
| L1/R1 trocam aba; L2/R2 scroll por página na EFFECTS | LibraryScreen-consistente | ✓ |
| Bridge + navigator nos 5 dialogs principais | ElementEditorDialog:317-318, TouchGesture:84-85, ShooterMode:69-70, PhysicalControllerConfigSection:217-218, ControllerBindingDialog:122-123 | ✓ |
| Haptics no foco/ativação | GamepadHaptics.kt; vibração no bridge | ✓ |
| Scrim não-focável (pointerInput + detectTapGestures) | QuickMenu.kt:454-469 | ✓ |
| Foco visível nas linhas de shader (borda accent) | ScreenEffectsPanel (ShaderPresetRow, headers) | ◐ heterogêneo |
| Strings do painel EFFECTS localizadas | `shader_*`, `lsfg_*`, `bfg_*`, `steam_invite_*` (values/strings.xml) | ✓ |

### 2.3 Gaps verificados (o que falta)

| # | Gap | Evidência |
|---|---|---|
| G1 | **B morto nos AlertDialogs aninhados** — B cru não dispara `dismissOnBackPress`; pickers (`CategorizedActionPicker`, `PanActionPicker`, `BindingActionPicker`), confirmação de saída do ElementEditor (1082-1137), `PlayingBlockedDialog` não mapeiam B | SettingsDialogBlocks.kt (pick + AlertDialog 749-781), ElementEditorDialog:1082-1137, XServerScreen:2783-2784 |
| G2 | **Três mecanismos de ativação concorrentes** — bridge (A→DPAD_CENTER), `gamepadActivate` (ScreenEffectsPanel:1526), `onPreviewKeyEvent` inline (QuickMenuDetailRow 2169-2184, adjustment rows 1804-1836) — cada um com semântica de consumo própria | P2-9 do spec anterior, não resolvido |
| G3 | **Linguagem visual de foco heterogênea** — focusRing rotativo (FocusRing.kt), bordas ciano (ScreenEffectsPanel:1628-1694), gradientes/accent no QuickMenu; estados focada/selecionada/travada indistinguíveis | P3-18 / D7 pendente |
| G4 | **Sliders Material3 sem A-lock** — ShooterModeSettingsDialog (`SliderSettingBlock`), ElementEditorDialog (sliders 670, 748, 779, 872), SizeAdjusterOverlay (1203-1210) | só sliders custom têm lock |
| G5 | **ControllerBindingDialog sem `focusGroup`** no scroll — foco não fica confinado; SearchBar sem tratamento | ControllerBindingDialog.kt:113-456 |
| G6 | **TOOLS não persiste `quickMenuLastTab`** (QuickMenu.kt:620-628) | inconsistência com as outras abas |
| G7 | **Strings hardcoded restantes** — `"Off"`/`"${value}x"` (QuickMenu.kt:1338, 1421), `"●"` (1867; ScreenEffectsPanel:1321), `"-"`/`"+"` (1894, 1942; 1339, 1387), `"$n pass"/"passes"` (ScreenEffectsPanel:1563), `" *32"` (QuickMenu.kt:948) | 14 locales; EN-only hoje |
| G8 | **Exit do rail sem FocusRequester** (QuickMenu.kt:639-647) | menor; alcançável via navegação |
| G9 | **Sem restauração de posição** — reabrir o QuickMenu perde o último item focado/scroll por aba | lição 7 Ozone (`2026-08-09-retroarch-nav-lessons.md`), `menu_remember_selection` |
| G10 | **B nas janelas de dialog com `dismissOnBackPress`** — depende do BACK físico do sistema; B do gamepad não fecha (idem G1, mas em Dialog raiz) | ElementEditorDialog:302-315, PhysicalControllerConfigSection:193-215, ControllerBindingDialog:113-123 |

## 3. Design

### 3.1 Framework compartilhado — `ui/component/GamepadModifiers.kt` (novo)

Um arquivo, quatro artefatos públicos, cada um com propósito único e testável em JVM:

| Artefato | Papel | Substitui |
|---|---|---|
| `Modifier.gamepadSelectable(selected, onClick)` | ativação única: `BUTTON_A` / `DPAD_CENTER` / `ENTER` (ACTION_DOWN, repeat 0) → `onClick()`; consome o evento; foco implícito (focável) + visual D7. Funciona com e sem bridge (A cru quando não há bridge; DPAD_CENTER quando há). | `gamepadActivate` (ScreenEffectsPanel:1526-1546), `onPreviewKeyEvent` de ativação (QuickMenuDetailRow:2169-2184, QuickMenuItemRow:2274-2284), `selectable(selected, onClick={})` que engolia DPAD_CENTER |
| `Modifier.gamepadAdjustableRow(locked, onLockChange, onAdjust, ...)` | padrão linha de ajuste: A/**DPAD_CENTER** trava, **B cru** destrava, `DPAD_LEFT/RIGHT` ajusta quando travado; reset do lock ao perder foco. | QuickMenuAdjustmentRow:1757-1836, ScreenEffectAdjustmentRow:1258-1290 |
| `Modifier.gamepadBackHandler(backAction)` | "B" hierárquico por superfície reagindo ao B **cru**; o mesmo lambda é registrado no `BackHandler` físico (paridade gamepad/toque; o físico continua funcionando para quem só tem BACK). | BackHandler hierárquico do QuickMenu (429-446), `onPreviewKeyEvent` de B do ScreenEffectsPanel (1093-1106), `dismissOnBackPress` como único caminho (G10) |
| `GamepadFocusScope` (composable) | wrapper que instala `JoystickFocusNavigator` + `GamepadKeyBridge` + `gamepadBackHandler`/`BackHandler` para uma janela (Dialog/AlertDialog/overlay) e o foco inicial; recebe `backAction` e opcional `initialFocusRequester`. | instalação manual repetida por janela (P2-11), incluindo o hack do `text` do PlayingBlocked (XServerScreen:2783-2784) |

Regras de consumo (fonte única):
- `gamepadSelectable` consome A/DPAD_CENTER/ENTER apenas em ACTION_DOWN com `repeatCount == 0`,
  apenas quando o nó está focado; devolve `false` caso contrário (propaga para o pai).
- `gamepadAdjustableRow` consome A/DPAD_CENTER (lock), B (unlock), DPAD_L/R (ajuste) quando
  travado; DPAD_L/R propaga quando destravado (navegação).
- `gamepadBackHandler` consome B apenas; o `BackHandler` físico usa o mesmo lambda (sem
  dupla execução: B cru não chega ao `OnBackPressedDispatcher`, BACK físico não chega ao
  modifier — caminhos disjuntos por construção).

### 3.2 Linguagem visual única — `ui/component/GamepadFocus.kt` (novo)

Substitui o focusRing rotativo + bordas ciano + gradientes por **um** componente com **três
estados semanticamente distintos**, theme-aware (paleta Pluvia, claro/escuro):

| Estado | Visual | Quando |
|---|---|---|
| **Focada** | anel animado (herdado do FocusRing, sem o gradiente multi-cor: primary/tertiary apenas) | nó tem foco e não está selecionado |
| **Selecionada** | accent persistente (borda sólida, sem animação) | item é a seleção atual da superfície (aba escolhida, preset ativo, toggle ligado) — nunca confundida com foco |
| **Travada** | anel sólido + indicador `●` (mantido do padrão atual, agora parte da linguagem) | linha de ajuste com A-lock ativo |

Forma de aplicação: `Modifier.gamepadFocus(state: GamepadFocusState, shape, interactionSource)`
com um enum `GamepadFocusState { Focused, Selected, Locked }` e helper
`Modifier.gamepadFocusable(...)` que combina focus+visual+semântica de acessibilidade.

Fim da regra "aba selecionada on-focus" (R2 do spec anterior já aplicado): a aba escolhida
permanece destacada por `selectedTab`, nunca por foco.

### 3.3 Correções dos gaps (por superfície)

| # | Superfície | Mudança |
|---|---|---|
| G1/G10 | AlertDialogs aninhados + Dialogs raiz | envolver em `GamepadFocusScope` com `backAction = dismiss`; sub-pickers (Categorized/Pan/Binding) ganham `gamepadBackHandler` + foco inicial no primeiro item |
| G2 | QuickMenu + EFFECTS | migrar todos os `onPreviewKeyEvent`/`gamepadActivate`/`selectable` de ativação para `gamepadSelectable`; manter `selectable` apenas onde o estado visual vier de outra fonte |
| G3 | todas as superfícies | aplicar `gamepadFocus`/`gamepadFocusable`; remover bordas ciano e gradientes de foco específicos |
| G4 | ShooterMode/ElementEditor/SizeAdjuster | envolver cada `Slider` M3 em linha com lock: A trava, B destrava, L/R ajusta (via `gamepadAdjustableRow` generalizado para aceitar um `value`+`onValueChange` de Slider) |
| G5 | ControllerBindingDialog | `focusGroup()` no scroll da lista; foco inicial na lista (ou na busca quando aberta); busca com `gamepadSelectable` |
| G6 | TOOLS | `PrefManager.quickMenuLastTab` no `onSelected` (paridade com as demais abas, QuickMenu.kt:620-628) |
| G7 | strings | migrar as strings listadas em G7 para `stringResource` (EN + pt-rBR + demais locales seguem o padrão existente; `"●"`/`"+"`/`"-"` como recursos de string) |
| G8 | Exit do rail | adicionar `FocusRequester` (foco inicial alternativo ao fechar menu é fora de escopo; apenas alcançável e com visual) |
| G9 | todas as abas | **remember-selection:** `rememberSaveable` por aba do índice do último item focado + scroll; reabrir restaura. Implementação: mapa `tab → lastFocusIndex` + `LaunchedEffect` no bootstrap de foco (831-850) usando o índice salvo; `scrollState` já é lembrado — persistir ambos juntos |

### 3.4 Alinhamento 2026 (nowinandroid, políticas registradas)

Adotado no QuickMenu (métodos modernos, sem mudar a linguagem visual Pluvia):
- **`FocusRequester` + `LaunchedEffect { requestFocus() }`** para autofoco ao entrar em
  janela/aba (padrão NIA SearchScreen.kt:519-549).
- **`collectIsFocusedAsState`** para o estado focado da linguagem visual (já usado no
  FocusRing — manter).
- **`rememberSaveable`** para estado de foco/scroll (G9).
- **`stringResource`** + recursos localizados (14 locales) — G7.
- **`BackHandler`** mantido (predominante no app) em vez de predictive-back/`OnBackPressedDispatcher`
  — a migração de API de back é escopo de app inteiro, fora daqui.
- Previews (`@Preview`) para `GamepadFocusScope`/`gamepadFocus` nos 3 estados, tema claro/escuro.

Fora de escopo (registrado, não feito): Navigation 3, edge-to-edge dinâmico,
`enableOnBackInvokedCallback`, lifecycle 2.10 / `collectAsStateWithLifecycle` (não há fluxo
no QuickMenu), baseline profiles, testes Roborazzi.

## 4. Arquivos afetados

**Novos:**
- `app/src/main/java/app/gamenative/ui/component/GamepadModifiers.kt` — `gamepadSelectable`,
  `gamepadAdjustableRow`, `gamepadBackHandler`, `GamepadFocusScope`.
- `app/src/main/java/app/gamenative/ui/component/GamepadFocus.kt` — `GamepadFocusState` enum,
  `Modifier.gamepadFocus`, `Modifier.gamepadFocusable` (inclui o anel animado extraído do
  FocusRing em modo "focada").
- `app/src/test/java/app/gamenative/ui/component/GamepadModifiersTest.kt` — unit tests JVM do
  framework (consumo, lock, back).

**Modificados:**
| Arquivo | Mudança |
|---|---|
| `ui/component/QuickMenu.kt` | migrar rail (524-648), tab buttons (540-628), chips (1677-1740), toggles (1997-2112), item/detail rows (2114-2350), adjustment rows (1742-1950) para o framework; `gamepadBackHandler` no lugar do BackHandler manual (429-446); TOOLS persiste tab (620-628); Exit com requester (639-647); remember-selection no bootstrap (831-850); strings G7 |
| `ui/component/ScreenEffectsPanel.kt` | `gamepadActivate`→`gamepadSelectable` (1493, 1593, 1644, 1701, 1784); `ScreenEffectAdjustmentRow`→`gamepadAdjustableRow` (1258-1290); B handler→`gamepadBackHandler` (1093-1106); visual D7 (1628-1694); strings (1563, 1321, 1339, 1387) |
| `ui/component/FocusRing.kt` | extrair o anel para `GamepadFocus.kt`; manter `focusRing` como wrapper para uso fora do QuickMenu (biblioteca) — sem mudança de comportamento |
| `ui/component/dialog/ElementEditorDialog.kt` | `GamepadFocusScope` na janela (317-318 já tem bridge — consolidar); sliders com lock (670, 748, 779, 872); AlertDialog de saída com back (1082-1137); pickers com back |
| `ui/component/dialog/TouchGestureSettingsDialog.kt` | `GamepadFocusScope` consolidado (84-85); pickers (749-781) com `gamepadBackHandler`; `GestureRow`/`DelayTextField` com `gamepadSelectable` |
| `ui/component/dialog/ShooterModeSettingsDialog.kt` | `GamepadFocusScope` (69-70); `SliderSettingBlock` com lock (141-357); `BindingActionPicker` com back |
| `ui/component/dialog/PhysicalControllerConfigSection.kt` | consolidar double-Dialog (193-215) sob `GamepadFocusScope` (217-218); presets/items com `gamepadSelectable`; `ControllerBindingDialog` filho com back |
| `ui/component/dialog/ControllerBindingDialog.kt` | `focusGroup` no scroll (113-456); foco inicial; busca `gamepadSelectable`; B fecha |
| `ui/component/SettingsDialogBlocks.kt` | `CategorizedActionPicker`/`BindingActionPicker` (162-254) com `gamepadBackHandler` + `gamepadSelectable` (reuso nos 3 dialogs) |
| `res/values/strings.xml` + 13 locales | `quick_menu_*`: `off`, `x_multiplier`, `locked_indicator`, `minus`, `plus`, `n_passes`, `wine_32bit_suffix` |
| `XServerScreen.kt` (se necessário) | PlayingBlocked (2783-2784) consolida em `GamepadFocusScope`; sem mudança de roteamento |

**Testes:**
- **Unit (JVM):** consumo/tradução de `gamepadSelectable` (A cru, DPAD_CENTER, ENTER, repeat,
  não-focado propaga), lock/unlock/ajuste de `gamepadAdjustableRow` (incl. reset on focus loss),
  `gamepadBackHandler` (B consome, outros passam), exclusividade física-BACK vs B cru.
- **Manual (device, DS4 conectado via Bluetooth):** T1..T7 sem toque; tema claro/escuro;
  TalkBack off (padrão) e on (regressão a11y); toque intocado (tap/scroll/drag); jogo intocado
  (pad → xinput em jogo); BACK físico sem double-fire.

## 5. Aceite por tarefa (R4 — sem toque, <10 s, zero fechamentos acidentais)

| # | Tarefa | Critério |
|---|---|---|
| T1 | Abrir o QuickMenu e navegar as 7 abas | stick/hat e L1/R1 mudam aba; aba selecionada permanece destacada com foco no conteúdo |
| T2 | EFFECTS: escolher shader RetroArch | navegar categorias/presets; A ativa preset; busca focável; L2/R2 página |
| T3 | Ajustar um valor (FPS target, FSR sharpness, flow scale) | A trava, L/R ajusta, B destrava; indicador ● visível; reset ao sair da linha |
| T4 | Voltar hierárquico | conteúdo → aba selecionada → fechar menu, com B; fechar sem acidentes (scrim inerte) |
| T5 | ElementEditor completo | navegar grupos/dropdowns/sliders/pickers; B fecha pickers e o dialog; confirmação de saída operável |
| T6 | Diálogos de configuração (TouchGesture/ShooterMode/PhysicalController/Binding) | navegar tudo com stick/d-pad; B hierárquico; sliders com lock |
| T7 | Linguagem visual | focada ≠ selecionada ≠ travada distinguíveis, claro e escuro |

## 6. Fora de escopo

- Restante do app (biblioteca, login, downloads, settings, OAuth).
- Abertura do menu sem botão Home em pads sem Home (P1-5 do spec anterior — decisão de
  produto, depende de teste em jogo; opcional em fase futura).
- Teclado virtual para busca; restauração entre sessões (só entre aberturas).
- Unificação total `GamepadInputController` na raiz (evolução futura registrada).
- Predictive back, Navigation 3, edge-to-edge, lifecycle 2.10 (app inteiro).
- Fallback `AXIS_RX/RY` do right stick (P1-6 do spec anterior — camada do jogo).
