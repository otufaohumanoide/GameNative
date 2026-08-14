# Spec de implementação — Onda 2: integração da camada universal de gamepads

**Data:** 2026-08-13
**Base:** spec 2026-08-13-gamepad-universal-onda2.md (aprovado).
**Status:** implementado; 125 testes JVM verdes (0 falhas); `assembleModernDebug` OK.
**Verificação on-device (O1–O11):** pendente — sem dispositivo; cenários no
`tools/quickmenu-verify.sh` (§[G] novo).

---

## 1. O que foi implementado (evidências file:line)

| Peça | Arquivo | Detalhe |
|---|---|---|
| Hub app-scoped | `PluviaApp.kt:83-87, 207-211` | `gamepadHub = GamepadHub(this).also { it.start() }` no onCreate; companion `@Volatile lateinit` |
| Wiring do dispatch | `MainActivity.kt:594-597, 649-653` | `hub.onKey/onAxis` após o emit cru (adapter fino); retorno do dispatch intocado |
| Listener duplicado removido | `MainActivity.kt` (field+registro+unregister removidos); `XServerScreen.kt:1504-1530` | XServerScreen assina `GamepadDeviceAddedEvent`/`GamepadDeviceRemovedEvent` com o MESMO corpo do listener antigo |
| appId vivo | `XServerScreen.kt` (composição: `hub.activeAppId = container.id`); `GamepadHub.kt:66-67, 159` | holder vivo (lição C1) — lido no call time |
| Adapter fino | `gamepad/mapping/AndroidInputAdapter.kt` (novo) | KeyEvent/MotionEvent → RawKeyInput/RawAxisInput |
| Bridge view-level | `gamepad/GamepadViewBridge.kt` (novo) | decisões puras de confirmação/direção para diálogos |
| Confirm por FaceStyle | `GamepadMapping.kt` (`confirmButton`/`confirmKeyCode`); `GamepadHub.kt:111-116` (`confirmKeyCodeFor`); `GamepadBusInput.kt` (bridge); `GamepadKeyBridge.kt` (view) | FACE_BOTTOM (Xbox/PS/Gen) / FACE_RIGHT (Nintendo), invertido por `swapOkCancel`; fallback BUTTON_A |
| Deadzone de menu por device | `GamepadHub.kt:124-128` (`menuDeadzoneFor`); `GamepadBusInput.kt` (navigator); `JoystickFocusNavigator.kt` | profile override ?: `gamepadMenuStickDeadzone` (0.45), sobre valor CRU; ghost de device removido consumido e descartado |
| E2 — deadzone no jogo | `PhysicalControllerHandler.kt` (`applyProfileDeadzone`) | perfil universal aplicado ao estado do ExternalController (sticks radiais, triggers axiais) |
| Glyphs no ActionBar | `GamepadActionBar.kt` | FaceStyle ativo não-Xbox → `GamepadGlyph` (label posicional); Xbox mantém ícones; `swapFaceButtons` como swap posicional |
| Instrumentação | `GamepadHub.kt:192-197` (`GamepadLogical`) | par do GamepadTrace cru, gate-aware |
| Gate | `PrefManager.kt:1456-1459` | default **false** (flip = último passo da verificação on-device, spec §1.8) |
| Testes | `GamepadMappingTest` (9), `GamepadViewBridgeTest` (14) | 125 totais, 0 falhas |
| Verificação script | `tools/quickmenu-verify.sh` | seção [G] Onda 2 (greps GamepadHub/GamepadLogical) |

## 2. Desvios do spec (decisões registradas — com justificativa)

1. **Consumidores de menu MANTÊM assinatura crua** (spec §1.5 pedia migração para
   `GamepadInputEvent`). Motivos:
   - **Repeats**: `translateKey` emite `ButtonDown` só com `repeatCount == 0`
     (contrato congelado) — o canal de repetição contínua do DPAD segurado é a tecla
     crua; migrar mataria a rolagem contínua.
   - **Deadzone do menu**: o AxisMotion lógico já vem rescalonado com a deadzone do
     JOGO (0.15); o threshold do menu (0.45) precisa do valor CRU — senão o menu
     perderia sensibilidade (0.30 cru → 0.17 rescalonado → falso neutro).
   - **Invariante de consumo**: a decisão "o jogo não vê nada com overlay aberto" vive
     no caminho cru (XServerScreen + bridge) — mexer nisso é risco C1/C2 reciclado.
   O fluxo lógico é EMITIDO (gate), LOGADO (`GamepadLogical`) e CONSUMIDO pelo
   `GamepadViewBridge` (diálogos traduzem localmente) — o tradutor entra em produção
   sem tocar o caminho de janela.
2. **Hub NÃO chama ControllerManager** (spec §1.2 pedia). Ordem de init: o hub nasce no
   `PluviaApp.onCreate` e o `ControllerManager.init` só roda no `MainActivity.onCreate`
   — `onDeviceConnected` com `inputManager` null = NPE em todo boot. O XServerScreen
   (bus handlers) chama o ControllerManager como antes — comportamento preservado.
3. **E2 aplica deadzone só com override explícito de perfil** (spec §1.7 pedia aplicar
   sempre com gate ON). O `ExternalController` já aplica a `flat` do próprio device via
   `getCenteredAxis`; forçar 0.15 por cima mudaria o caminho do jogo SEM override
   (violaria V10 byte-identical). Sem override → caminho intacto; com override → o
   valor do perfil.
4. **GamepadActionBar**: estilo não-Xbox mostra `GamepadGlyph`; Xbox mantém os ícones
   existentes (API pública inalterada — o enum local de ícones é usado por vários
   call sites; migração total é follow-up cosmético).

## 3. Verificação

### 3.1 JVM (feita)
- 125 testes: 66 camada universal + `GamepadMappingTest` 9 + `GamepadViewBridgeTest` 14
  + suítes Gamepad/Shader/SearchField existentes. 0 falhas, 0 erros.
- `assembleModernDebug` BUILD SUCCESSFUL (só warnings Rust pré-existentes do librashader).

### 3.2 On-device (pendente — sem dispositivo)
- `tools/quickmenu-verify.sh` seção [G]: greps `GamepadHub: started/added/removed` +
  `GamepadLogical:` com o gate ON (o gate vive no DataStore — ligar pela UI antes).
- Cenários O1–O11 do spec-onda2 §3.2 (byte-identical com gate OFF; OK/Cancel por
  FaceStyle; deadzone por device no menu e no jogo; hotplug; dedupe; diálogos; remap;
  latência < 1 ms).
- Aceite global: T1–T9 / V1–V10 / F1–F10 re-testados no mesmo ciclo.

### 3.3 Pendências pós-verificação
- Flip `gamepadUniversalEnabled` default → true (PrefManager.kt:1458).
- `tools/milestone.sh` + entrada em `docs/MILESTONES.md` (tag anotada).
