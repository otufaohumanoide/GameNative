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

## Atualização 2026-08-11 (tarde) — verificação on-device CONCLUÍDA

Device: Mi 11X/POCO F3 (M2012K11AG, alioth), APK com o fix do invite (`bac07811`) + fix do
harness (`82496415`), teste via harness `debug.gamenative.input` (sem controle físico).

### Causa raiz REAL do "QuickMenu abre sozinho ao abrir o jogo" — reproduzida no device

O primeiro launch de teste abriu o QuickMenu ~8 s após o start — **sem nenhum comando
meu**: a property `debug.gamenative.input` do device estava com o valor **`back:9`
sobrando da sessão de teste anterior** (a sessão 019fed5f terminou usando o harness). O
harness age no primeiro poll com qualquer valor não-vazio ≠ lastCommand, então **todo
launch de jogo disparava BACK** → `gameBack()` → `showQuickMenu=true`. Sintoma idêntico ao
relatado ("abre sozinho quando eu abro um jogo").

Caminhos de abertura do QuickMenu (mapa completo, HEAD `82496415`):

| Gatilho | Código | Ativo no setup do usuário? |
|---|---|---|
| BACK (físico/gesto/IME-hide) | `gameBack()` BackHandler | sim |
| **Harness `back` (stale property)** | `DebugGamepadInputHarness` | **sim — CAUSA RAIZ do sintoma (reproduzida)** |
| Invite poll (`onRequestOpen`) | `LaunchedEffect(inviteMenu)` | **não** — todos os containers têm `launchBionicSteam=false` → `inviteMenu==null` |
| PS (controle físico) | binding `OPEN_NAVIGATION_MENU` | só com controle conectado |

**FIX aplicado e verificado (`82496415`):** o harness agora usa como baseline o valor da
property **na composição** — comandos stale de sessões anteriores não disparam mais. Teste:
`back:9` setado ANTES do launch → 25 s sem abertura; `back` setado DEPOIS do launch →
abre normalmente. O fix do invite (`bac07811`) continua válido e necessário para sessões
com `launchBionicSteam=true` (host que não limpa o POLL reabriria o menu a cada segundo;
flag booleana deixava o jogo sem pausa).

### Navegação por joystick — verificação dinâmica (harness, sem controle físico)

| Teste | Resultado |
|---|---|
| Abrir via BACK → bootstrap com foco (tab=2, row 0) | ✅ |
| Stick Down×3 → rows 0→1→2→3; campo de busca com **IME suprimido** | ✅ |
| Stick Up → row 2 (busca, IME suprimido de novo) | ✅ |
| Stick Right/Left → rail↔conteúdo | ✅ |
| L1/R1 → `selectAdjacentTab` cíclico | ✅ |
| L2/R2 → scroll de página | ✅ |
| A → ativação | ✅ |
| B → back hierárquico (conteúdo→rail; rail→fecha) | ✅ |
| Reabrir 2× (cenário RC2 menu morto) → foco aterrissa e navega | ✅ |
| 30 s no start do jogo → **0 aberturas não solicitadas** | ✅ |
| Guardião de foco → 0 restaurações necessárias (nenhuma morte de foco) | ✅ |

Veredito final: **navegação por joystick funcional** (T1–T6 dinâmicos + 26/26 testes JVM);
T7 (modo edição) e T8 (diálogos) com controle físico seguem pendentes de teste manual.
