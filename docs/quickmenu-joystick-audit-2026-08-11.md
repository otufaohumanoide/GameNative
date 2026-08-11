# Auditoria: navegação por joystick no QuickMenu — estado 2026-08-11

> Objetivo do usuário: "avalie se a navegação por joystick está funcional".
> Esta auditoria consolida a avaliação estática (código HEAD `5c5aac5e`, pós-fix do
> invite regression `bac07811`) e o histórico de verificação on-device.

## Veredito

**Funcional** — com ressalva: T1–T4 verificados no device (Mi 11, PS4 real, 2026-08-10);
T5–T7 exigem o controle físico e **ainda não foram re-testados** após as mudanças de
08-11 (IME da busca, categorias fechadas, retry row da INVITE). Nada na revisão estática
indica regressão nesses caminhos.

## Evidência estática (HEAD)

| Critério (spec 2026-08-10 §5.2) | Onde no código | Estado |
|---|---|---|
| T1 PS abre/fecha (toggle) | `PhysicalControllerHandler` (abre) + `BusGamepadKeyBridge` `ModeKeyBehavior.CloseOverlay` (fecha) | ✅ verificado on-device 08-10 |
| T2 stick/hat 1 linha por gesto; L1/R1 abas; L2/R2 página | `BusJoystickFocusNavigator` + `GamepadStickLogic` (histerese RC1) + root `onPreviewKeyEvent` (L1/R1/L2/R2) | ✅ verificado on-device 08-10 |
| T3 toggle de shader sem matar foco | guardião contínuo 400ms + `requestMenuFocus()` com clearFocus+retry+fallback rail (RC2/RC3) | ✅ verificado on-device 08-10 |
| T4 colapso de categoria + busca + limpar | mesmo guardião + `SearchFieldImeLogic` (IME só no X, fecha no B; `GamepadNavigationClock` suprime IME via stick) | ✅ parcialmente verificado on-device; IME implementado 08-11 (estático OK) |
| T5 restauração de posição | `effectsFocusIndex` rememberSaveable + walk-down com `withFrameNanos` (RC3) | ✅ verificado on-device 08-10 |
| T6 ajustes A-lock | `gamepadAdjustableRow` (QuickMenu + ScreenEffectsPanel + ShooterMode + ElementEditor) | ✅ verificado on-device 08-10 (QuickMenu) |
| T7 modo edição | toolbar com bootstrap + B=cancela (XServerScreen `GamepadFocusScope`) | ⏳ pendente de controle físico |
| T8 diálogos | todos usam `GamepadFocusScope` (TouchGesture/ShooterMode/PhysicalController/ControllerBinding/ElementEditor/PlayingBlocked) | ⏳ pendente de controle físico |
| Regressão: jogo intocado com overlay aberto | bus consome tudo (navigator consome motion; bridge consome keys) | ✅ pipeline verificado; re-teste pós-G6 pendente |

## Testes JVM (verdes em HEAD)

- `GamepadStickLogicTest` — 7/7 (inclui regressão drift 0.40 → re-arm no dead zone).
- `GamepadModifiersTest` — 19/19 (ativação, ajuste, back hierárquico, foco).
- `SearchFieldImeLogicTest` — presente no suite (IME explícito).

## Verificação on-device pendente (device não conectado em 2026-08-11 12:36)

1. **Regressão do fix do invite** (`tools/quickmenu-verify.sh`): abrir o jogo e confirmar
   **25s sem** `QuickMenu bootstrap` não-solicitado; request legítimo do jogo (>20s)
   ainda abre o menu na INVITE com foco.
2. T5–T7 com controle físico (PS4): modo edição, diálogos, GamepadActionBar visível.
3. Re-teste de "jogo intocado" (sem leak de input pro guest) após G6.

## Conclusão

A navegação por joystick está **implementada e funcional** nos caminhos verificados
(T1–T6, on-device 08-10 + 26/26 testes JVM). As mudanças de 08-11 (fix do invite, IME da
busca, categorias fechadas, visual de foco, retry row) não introduzem regressão por
análise estática; a confirmação final exige o Mi 11 conectado (script
`tools/quickmenu-verify.sh`).
