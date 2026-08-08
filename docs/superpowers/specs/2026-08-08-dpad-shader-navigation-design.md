# D-pad / Joystick navigation for the shader menu (2026-08-08)

> **Problema:** o menu de efeitos (aba EFFECTS do QuickMenu — a "varinha") não é navegável por
> joystick. Causas identificadas no código:
> 1. **Stick analógico não gera key events.** O D-pad gera `KEYCODE_DPAD_*` (que o Compose
>    navega), mas o stick esquerdo e o hat-switch geram *axis motion*
>    (`onGenericMotionEvent` / `AXIS_X/Y`, `AXIS_HAT_X/Y`) — o Compose ignora e o evento cai no
>    vazio (`XServerScreen.onMotionEvent` já devolve `false` com o menu aberto, esperando que o
>    Compose consuma — mas ele não consome).
> 2. **BUTTON_A não ativa itens focados.** O `clickable` do Compose reage a Enter/Espaço/
>    `DPAD_CENTER`, mas o botão A do gamepad (`KEYCODE_BUTTON_A`) não dispara `onClick`.
> 3. **Sem indicação visual de foco** em várias linhas do menu de shaders
>    (`ShaderPresetRow`, `ShaderCategoryHeader`, `NativeEffectsHeader`).

## Design

### 1. `JoystickFocusNavigator` (novo composable)
- Instala um `View.OnGenericMotionListener` no `LocalView` enquanto habilitado.
- Traduz eixo do stick esquerdo (`AXIS_X/Y`) e hat do D-pad (`AXIS_HAT_X/Y`) em
  `FocusManager.moveFocus(Up/Down/Left/Right)`.
- Dead zone 0.45; **cooldown 180ms** entre movimentos (segurar o stick rola a lista sem voar).
- Consome o evento quando move (evita efeito colateral no jogo); devolve `false` em dead zone.
- Instalado no Box raiz do QuickMenu (`enabled = isVisible`) — beneficia todas as abas.

### 2. Ativação por botão A
- Modifier helper `gamepadActivate(isFocused, onClick)`: `BUTTON_A` / `DPAD_CENTER` / `ENTER`
  (ACTION_DOWN, repeat 0) em item focado → `onClick()` e consome (evita double-fire do
  `clickable`).
- Aplicado em: `ShaderPresetRow`, `ShaderCategoryHeader`, `NativeEffectsHeader`,
  `ScreenEffectToggleRow`, `ScreenEffectRadioRow`, `AccentActionRow` (Reset).

### 3. Foco visível
- `ShaderPresetRow`/`ShaderCategoryHeader`/`NativeEffectsHeader`: `collectIsFocusedAsState` +
  borda accent (estilo `OptionListItem`) para o usuário ver onde está.
- Linhas já usam `clickable` (focável no Compose 1.7) — o que faltava era o *visual* + A.

### 4. Campo de busca
- Continua focável (digitação exige teclado — fora de escopo mapear teclado virtual).

## Checklist

| # | Arquivo | Mudança | Aceite |
|---|---|---|---|
| 1 | `docs/superpowers/specs/2026-08-08-dpad-shader-navigation-design.md` | este doc | — |
| 2 | `ui/component/JoystickFocusNavigator.kt` (novo) | listener de axis motion → moveFocus | compila |
| 3 | `ui/component/QuickMenu.kt` | `JoystickFocusNavigator(enabled = isVisible)` no Box raiz | compila |
| 4 | `ui/component/ScreenEffectsPanel.kt` | `gamepadActivate` + foco visível nas linhas do menu | compila; stick move foco; A ativa |
| 5 | Build + device | `assembleModernDebug` + install | sem crash; navegação por stick/d-pad/A |

## Fora de escopo
- Teclado virtual para a busca (joystick não digita).
- Navegação por stick nas abas de configuração do QuickMenu (herdam o navigator do item 3).
- Reordenação do painel (spec separado).


## Execução (2026-08-08)

- `JoystickFocusNavigator.kt` criado (axis/hax → moveFocus, dead zone 0.45, cooldown 180ms,
  consome só quando move).
- `QuickMenu.kt`: `JoystickFocusNavigator(enabled = isVisible)` no Box raiz — todas as abas
  ganham navegação por stick/hat.
- `ScreenEffectsPanel.kt`: helper `Modifier.gamepadActivate(isFocused, onClick)` (BUTTON_A /
  DPAD_CENTER / ENTER, consome o evento) aplicado em `ShaderPresetRow`, `ShaderCategoryHeader`,
  `NativeEffectsHeader`, `ScreenEffectToggleRow`, `ScreenEffectRadioRow`; foco visível
  (borda accent) em `ShaderPresetRow`/`ShaderCategoryHeader`/`NativeEffectsHeader`.
- Notas de API: `KeyEvent.type` é extension property (`import androidx.compose.ui.input.key.type`);
  `FocusDirection` vive em `androidx.compose.ui.focus` (não foundation).
- Build OK, instalado, app boota sem crash. Verificação física (stick/d-pad/A) pendente do
  usuário — caminho de teclas (DPAD_*) já era tratado pelo Compose; o novo cobre o eixo.


## Auditoria de navegação por controle no overlay do jogo (2026-08-08, 2ª rodada)

**Metodologia:** auditado o fluxo "após abrir o jogo" — sidebar (QuickMenu), abas, dialogs e
editor de elementos — contra o padrão da LibraryScreen (handlers globais de key/motion + itens
focáveis).

**O que JÁ funcionava:**
- LibraryScreen (tela inicial): L1/R1 trocam abas, DPAD/eixos fazem bootstrap de foco, itens
  `OptionListItem` (selectable) com foco visível.
- QuickMenu: abas (`QuickMenuTabButton`) são selectable + focusRing + selecionam no foco;
  conteúdo usa clickable/selectable; `JoystickFocusNavigator` + `GamepadKeyBridge` no Box raiz
  (esta sessão e a anterior).
- Roteamento do XServerScreen: com `showQuickMenu`/`showElementEditor`/`keepPausedForEditor`/
  `isEditMode`, teclas E motion de gamepad passam ao Compose.

**Gaps encontrados e corrigidos:**
1. **Dialogs em janelas separadas não tinham suporte**: `ElementEditorDialog`,
   `TouchGestureSettingsDialog`, `ShooterModeSettingsDialog`, `PhysicalControllerConfigSection`
   e o AlertDialog de "Playing blocked" — agora cada um tem `JoystickFocusNavigator` +
   `GamepadKeyBridge` no content (stick/hat → foco; A → ativar item focado; B → voltar).
2. **Roteamento não cobria os estados de dialog**: a condição
   `(showElementEditor || keepPausedForEditor || showQuickMenu || isEditMode)` foi ampliada com
   `showTouchGestureDialog || showShooterModeDialog || showPhysicalControllerDialog ||
   showPlayingBlockedDialog` (teclado e motion) — senão gamepad ia pro jogo com o dialog aberto.
3. **BUTTON_A/B não ativam/fecham nada no Compose**: novo `GamepadKeyBridge` traduz
   A→DPAD_CENTER e B→BACK no nível da view (antes do dispatch do Compose), re-despachando o
   evento traduzido — funciona para qualquer item focado e qualquer BackHandler.

**Pendência:** verificação física com controle real (stick/d-pad/A/B) — injeção via adb não
abre o sidebar (rotas do jogo consomem).
