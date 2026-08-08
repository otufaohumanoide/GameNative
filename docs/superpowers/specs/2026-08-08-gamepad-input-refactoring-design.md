# Auditoria e refatoração do suporte a gamepad (2026-08-08)

> **Problema:** o suporte a gamepad (Bluetooth) no GameNative trata o controle como
> "modo acessório" do toque, e não como input de primeira classe. Auditoria linha a linha
> confirmou **7 bugs P1** (sendo o mais grave: **BUTTON_B não faz nada em nenhuma superfície** —
> sintoma reportado pelo usuário), **6 problemas arquiteturais** (três mecanismos duplicados de
> ativação, fonte de verdade de "overlay aberto" fragmentada em lista de flags, cobertura
> assimétrica e não imposta) e **7 lacunas de UX mobile-first** (sem haptics, sem dicas de
> navegação, strings hardcoded em Kotlin, linguagem visual de foco heterogênea).
>
> **Status:** auditoria concluída e verificada. Arquitetura-alvo aprovada. Execução por fases
> (ver §5), cada fase com plano de implementação próprio.

## 1. Contexto & princípios

- **Mobile-first (Wroblewski):** neste handheld, o gamepad é a *restrição* de design — o toque é
  um luxo, não o contrário. O modo gamepad não é o "modo reduzido" da UI: é o modo principal.
- **Princípios adotados:**
  1. **O caminho de input do jogo é intocável.** `PhysicalControllerHandler`/`WinHandler`/
     `evshim.c` (pad → `GamepadState` → SHM/UDP → SDL virtual "Xbox 360 Controller") é o ativo
     mais valioso da app; refatorações não podem alterar seu comportamento nem adicionar latência.
  2. **100% sem toque:** toda superfície de UI deve ser completável com gamepad (abrir, navegar,
     ativar, ajustar, voltar, fechar).
  3. **Zero regressão de toque:** o fluxo touch continua idêntico.
  4. **Consistência (Norman):** mesmas teclas = mesmos significados em todas as superfícies;
     uma única linguagem visual de foco; estados de seleção e foco nunca se confundem.
  5. **Feedback (Norman):** toda ação tem feedback visual + tátil; o usuário nunca precisa
     adivinhar "onde estou" ou "o que aconteceu".
  6. **Descobribilidade progressiva (Wroblewski):** dicas transitórias, sem chrome permanente.
  7. **Acessibilidade:** o modo gamepad não pode quebrar TalkBack (foco Compose é a11y-visível;
     testes incluem TalkBack ligado/desligado).
  8. **Aceite por tarefa (Spool):** critérios de aceite são tarefas de usuário mensuráveis
     ("sem toque, em <10s, zero fechamentos acidentais"), não checklists de dev.

## 2. Estado atual

### 2.1 Fluxo de input

```
Input físico (teclado, gamepad, touch)
        │
        ▼
MainActivity.dispatchKeyEvent / dispatchGenericMotionEvent   (MainActivity.kt:575-614)
        │  emite TUDO no bus antes do window dispatch
        ▼
PluviaApp.events (EventDispatcher, PluviaApp.kt:199)
        │  AndroidEvent.KeyEvent / MotionEvent (AndroidEvent.kt:14-15)
        ├──▶ XServerScreen.onKeyEvent / onMotionEvent (XServerScreen.kt:1447-1590)
        │       ├── overlay aberto?  → devolve false → Compose (UI)
        │       └── jogo rodando?    → PhysicalControllerHandler / InputControlsView /
        │                             WinHandler → SHM/UDP → evshim.c → Wine (jogo)
        ├──▶ LibraryScreen (L1/R1, bootstrap de foco) (LibraryScreen.kt:685-766)
        └──▶ se ninguém consumir → super → window → ComposeView
                ├── GamepadKeyBridge (View.OnKeyListener):  A→DPAD_CENTER, B→BACK sintético
                │      (GamepadKeyBridge.kt:24-52) — instalado por janela com overlay
                ├── JoystickFocusNavigator (View.OnGenericMotionListener): eixos→moveFocus
                │      (JoystickFocusNavigator.kt:27-69)
                └── Compose focus system (DPAD_*, DPAD_CENTER, ENTER) + BackHandler
```

### 2.2 Matriz de cobertura atual

Legenda: ✓ suportado · ✗ não suportado · ◐ parcial · — não se aplica

| Superfície | Navegação | Ativação (A) | Voltar (B) | Foco visual | Obs. |
|---|---|---|---|---|---|
| QuickMenu (todas as abas) | ✓ D-pad+stick | ✓ (bridge→DPAD_CENTER) | ✗ **morto** | ◐ heterogêneo | BackHandler.kt:422 nunca dispara |
| Linhas de ajuste (QuickMenu + EFFECTS) | ✓ | ✗ **morto** (A-lock) | ✗ **morto** (B-unlock) | ✓ | `selectable(onClick={})` engole DPAD_CENTER |
| ElementEditorDialog | ✓ | ✓ | ✗ | ✓ | bridge na janela (317-318) |
| TouchGesture / ShooterMode / PhysController / PlayingBlocked | ✓ | ✓ | ✗ | ✓ | bridge por janela |
| ControllerBindingDialog | ✗ | ✗ | ✗ | ✗ | janela própria (ControllerBindingDialog.kt:111) sem suporte |
| EditModeToolbar + dropdown | ✗ | ✗ | ✗ | ✗ | (XServerScreen.kt:2535, 2903-3015) |
| GameInviteOverlay | ✗ | ✗ | ✗ | — | (PluviaMain.kt:1582) tem botões Join/Dismiss |
| AchievementOverlay | — | — | — | — | passivo |
| Manual-resume overlay | ✓ | ✓ | — | — | global A/ENTER/START (XServerScreen.kt:1458-1469) |
| ControllerSlotStatusOverlay | — | — | — | — | passivo (debug) |
| Biblioteca (telas) | ✓ | ✓ | ✓ | ✓ | filosofia Compose-raw (LibraryAppScreen.kt:671-703, 723-728) |
| Overlays da biblioteca (ConnectionStatusBanner etc.) | ◐ | ✗ | ✗ | ◐ | focusGroup apenas (ConnectionStatusBanner.kt:92) |
| **Jogo (xinput/dinput)** | ✓ | ✓ | ✓ | — | intocado — via PhysicalControllerHandler/WinHandler/evshim |

### 2.3 Infraestrutura que funciona e fica

- **Bus de eventos** (`PluviaApp.events`) — o roteamento em si é sólido e é a base correta
  (Abordagem C "Compose puro" é inviável: o handler do jogo é Java/View e stick/hat só chegam
  como `MotionEvent`).
- **`JoystickFocusNavigator`** — eixos→`moveFocus` com dead zone e cooldown; precisa de histerese.
- **Tradução A→DPAD_CENTER** do `GamepadKeyBridge` — modelo correto (estado atual: o BACK
  sintético *chega* ao pipeline de teclas do Compose, é o `BackHandler`/dispatcher que nunca o
  vê; a tradução do B será removida — ver D1).
- **Focus groups + bootstrap de foco** no QuickMenu (QuickMenu.kt:808-827).
- **`GamepadActionBar`** da biblioteca (GamepadActionBar.kt:179-188) — precedente de dicas
  de navegação com strings localizadas (`settings_interface_show_gamepad_hints_*`).
- **Haptics no `InputControlsView`** (InputControlsView.java:1007, 1022, 1204) e **rumble**
  do `WinHandler` (WinHandler.java:762-890) — padrões a seguir na UI Compose.

## 3. Achados

### P1 — Bugs (verificados linha a linha)

1. **BUTTON_B está 100% morto.** `GamepadKeyBridge` sintetiza `KEYCODE_BACK` via
   `view.dispatchKeyEvent` (GamepadKeyBridge.kt:41-52). O BACK sintético é despachado direto na
   view: passa pelo pipeline de teclas do Compose (que não trata BACK) e **nunca** chega ao
   `OnBackPressedDispatcher` da Activity — o `BackHandler(enabled=isVisible){onDismiss()}` do
   QuickMenu (QuickMenu.kt:422-424) só dispara com BACK *real* da Activity. Impacto: B não faz
   nada em **nenhuma** superfície com bridge (QuickMenu, os 4 dialogs com bridge, PlayingBlocked).
   *Sintoma reportado pelo usuário.*
2. **Linhas de ajuste inoperantes (código morto).** `QuickMenuAdjustmentRow`
   (QuickMenu.kt:1789-1794) e `ScreenEffectAdjustmentRow` (ScreenEffectsPanel.kt:1258-1286)
   tratam `BUTTON_A`/`BUTTON_B` *crus* no Compose — mas o bridge os consome no nível do View
   antes do Compose (GamepadKeyBridge.kt:24-52). A chega como `DPAD_CENTER` sintético e o
   `selectable(selected=isFocused, onClick={})` (QuickMenu.kt:1815-1820) o engole sem efeito;
   B não chega nunca. Resultado: A-lock e B-unlock são código morto — FPS lock, resolução e
   ajustes análogos não funcionam por gamepad.
3. **Ramo morto no `gamepadActivate`** (ScreenEffectsPanel.kt:1522-1540): o check de
   `KEYCODE_BUTTON_A` (1525-1529) nunca casa (A é consumido no view); a ativação só funciona via
   `DPAD_CENTER` sintético. Três camadas tentando "ativar" (bridge, modifier, onPreviewKeyEvent).
4. **Scrim focável** (QuickMenu.kt:437-446): `.clickable` → o nó é focável; um `moveFocus` em
   área vazia pode aterrissar foco invisível no scrim e um A fecha o menu sem aviso.
5. **Abrir o menu sem toque: só o botão Home.** `OPEN_NAVIGATION_MENU` é forçado apenas em
   `KEYCODE_BUTTON_MODE` (PhysicalControllerConfigSection.kt:76-88, 268-270;
   PhysicalControllerHandler.kt:394-398). Pads Bluetooth sem botão Home (a maioria) **não têm
   como abrir o QuickMenu via gamepad**; SELECT/START não têm binding padrão.
6. **Right stick pode morrer.** `ExternalController.processJoystickInput`
   (ExternalController.java:181-203) mapeia `AXIS_Z`/`AXIS_RZ`; vários pads Android reportam
   o right stick em `AXIS_RX`/`AXIS_RY` (sem fallback).
7. **`ControllerBindingDialog` sem suporte algum** (janela própria, ControllerBindingDialog.kt:111,
   aberto de ElementEditorDialog.kt:1028 / PhysicalControllerConfigSection.kt:543): nenhum
   navigator/bridge/ativação — A/B/DPAD mortos dentro dele.

### P2 — Arquitetura

8. **Fonte de verdade fragmentada.** "Overlay aberto?" é uma lista de flags repetida em
   `XServerScreen.onKeyEvent` (1470-1489) e `onMotionEvent` (1544-1548)
   (`showElementEditor || keepPausedForEditor || showQuickMenu || isEditMode ||
   showTouchGestureDialog || showShooterModeDialog || showPhysicalControllerDialog ||
   showPlayingBlockedDialog`). Todo overlay novo precisa lembrar de atualizar as **duas**
   condições — ou o gamepad vaza para o jogo com o menu aberto.
9. **Três mecanismos de ativação duplicados:** bridge global (A→DPAD_CENTER),
   `gamepadActivate` por linha (ScreenEffectsPanel.kt:1522-1540), `onPreviewKeyEvent` inline
   (QuickMenuActionRow 2150-2155, QuickMenuAdjustmentRow 1786-1814) — cada um com conjunto de
   teclas e semântica de consumo ligeiramente diferentes.
10. **Duas filosofias de gamepad:** bus + view-listener (in-game) vs. Compose-raw (biblioteca,
    LibraryAppScreen.kt:671-703). Semântica de B divergente: sintético (BACK) no jogo, raw
    (`BUTTON_B`) na biblioteca. Um único conjunto de modifiers compartilhados deve convergir.
11. **Bridge reinstalado por janela** (QuickMenu.kt:430, ElementEditorDialog.kt:317-318,
    TouchGestureSettingsDialog.kt:84-85, ShooterModeSettingsDialog.kt:69-70,
    PhysicalControllerConfigSection.kt:217-218, XServerScreen.kt:2774-2775) sem imposição de
    cobertura: o que esquece fica sem suporte (cf. P1-7).
12. **Caminho do BACK real frágil:** MainActivity.kt:587-600 engole DOWN+UP do BACK com
    `SteamService.keepAlive` para evitar double-fire do BackHandler do XServerScreen —
    engenharia sensível em que qualquer mudança nos BackHandlers reabre o bug.
13. **Sem testes do fluxo de teclas** — nenhum unit test da tradução; verificação só manual.

### P3 — UX mobile-first

14. **Sem haptics na UI Compose** (só Java `InputControlsView` e rumble do jogo).
15. **Strings hardcoded em Kotlin** (ScreenEffectsPanel.kt:652-691, QuickMenu.kt:1315-1428,
    XServerScreen.kt:3162-3192) — o app tem 14 locales; as strings novas são EN-only.
16. **Sem dicas de navegação no in-game** — há precedente bom na biblioteca (GamepadActionBar).
17. **Seleção ≠ foco violada:** a aba é "selecionada on-focus" (QuickMenu.kt:1567-1571) — quando
    o foco desce ao conteúdo, a aba perde o destaque visual e o usuário perde o "onde estou".
    O B hierárquico precisa de uma âncora visual estável.
18. **Linguagem visual de foco heterogênea:** focusRing rotativo (FocusRing.kt:44-123), bordas
    ciano (ScreenEffectsPanel.kt:1628-1694), gradientes/bordas accent (QuickMenu) — estados
    focada/selecionada/travada não são distinguíveis.
19. **IME vs gamepad:** o campo de busca focável abre o teclado; NoExtractOutlinedTextField
    (77-94) mitiga o DPAD, mas o fluxo gamepad ganha atrito.
20. **`JoystickFocusNavigator` sem histerese:** cooldown fixo de 180 ms, `moveFocus` ignora o
    retorno `false` (JoystickFocusNavigator.kt:50-56) — jitter próximo da borda da dead zone
    pode gerar movimento fantasma.

## 4. Arquitetura-alvo

Abordagem escolhida: **hardening incremental** (Abordagem B) — o pipeline atual (bus + view
listeners + Compose focus) é mantido; os problemas são corrigidos por componentes compartilhados
e uma fonte de verdade única. Unificação total (Abordagem A: `GamepadInputController` na raiz)
fica registrada como evolução futura (ver §7).

### 4.1 Componentes compartilhados

| Componente | Papel | Substitui |
|---|---|---|
| `Modifier.gamepadSelectable(selected, onClick)` | ativação única: `BUTTON_A`/`DPAD_CENTER`/`ENTER` (funciona com e sem bridge — converge as duas filosofias) | `gamepadActivate`, onPreviewKeyEvent de ativação |
| `Modifier.gamepadAdjustableRow(...)` | padrão linha de ajuste: A(**DPAD_CENTER**) trava, B(**BACK**) destrava, `DPAD_LEFT/RIGHT` ajusta | QuickMenuAdjustmentRow/ScreenEffectAdjustmentRow |
| `Modifier.gamepadBackHandler(backAction)` | "B" hierárquico por superfície, reagindo ao BACK **traduzido**; o mesmo lambda é usado pelo `BackHandler` físico (paridade gamepad/toque) | onPreviewKeyEvent de B dispersos |
| `OverlayInputContext` (enum) + holder | fonte de verdade única "quem consome input agora"; XServerScreen consome o holder em vez da lista de flags | lista de flags (P2-8) |
| `GamepadHaptics` (helper) | vibração por camada: sutil no foco, média na ativação, leve no voltar (constantes padrão Android; respeita configuração do sistema; só com gamepad conectado) | — |
| `GamepadHint` (transitória) | primeira dica de navegação por sessão, some após ~6 s de inatividade; reaparece ao entrar em superfície nova; strings localizadas (precedente: GamepadActionBar) | — |
| Linguagem visual única (D7) | um único estilo de foco (anel + leve escala, theme-aware) com estados **focada / selecionada / travada** | focusRing, bordas ciano, gradientes |

### 4.2 Decisões de design (D1-D7, com revisões R1-R5)

| # | Decisão | Status |
|---|---|---|
| D1 | Bridge mantém `A→DPAD_CENTER` e **deixa o B cru** chegar ao Compose (sem tradução, sem consumo). Toda superfície reage: linhas de ajuste tratam `BUTTON_B` cru (destravar) e `gamepadBackHandler` faz o voltar hierárquico; `BackHandler` físico usa o mesmo lambda. | aprovado (revisado no self-review) |
| D2 | **B hierárquico:** foco no conteúdo → volta ao botão da **aba selecionada** (e rola ao topo); foco no rail → fecha. `BackHandler` físico usa o mesmo lambda. | aprovado + R2 |
| R2 | **Seleção ≠ foco:** aba destacada por estado (`selectedTab`), nunca por foco; a aba escolhida permanece destacada com o foco no conteúdo. | aprovado |
| D3 | `OverlayInputContext` enum + holder — fim da lista de flags do XServerScreen. | aprovado |
| D4 | Haptics no foco/ativação/voltar, apenas com gamepad conectado. | aprovado + R5 |
| D5 | **Dica transitória** (não rodapé fixo — chrome mínimo, Wroblewski). | aprovado + R1 |
| D6 | Matriz de cobertura (Tabela 2.2) é o checklist de aceite por fase. | aprovado |
| D7 | Linguagem visual única de foco, theme-aware, estados focada/selecionada/travada. | novo (R3) |
| R4 | Aceite **por tarefa** em cada fase (T1..T6), com tempo-limite e zero toque. | aprovado |

### 4.3 Fluxo-alvo (resumo)

```
MainActivity → bus → XServerScreen.onKey/MotionEvent
        │  consulta OverlayInputContext (não mais a lista de flags)
        ├── context == NONE            → PhysicalControllerHandler/WinHandler (jogo)
        └── context == OVERLAY/DIALOG  → Compose
                ├── bridge traduz A→DPAD_CENTER; B cru segue para o Compose
                ├── JoystickFocusNavigator (com histerese)
                └── superfícies usam gamepadSelectable / gamepadAdjustableRow /
                    gamepadBackHandler (mesma semântica em todo o app)
```

## 5. Roadmap em 3 fases

Cada fase tem plano de implementação separado (writing-plans) e aceite **por tarefa**.
Tarefas comuns (R4): T1 abrir menu só com gamepad · T2 navegar até a aba EFFECTS ·
T3 selecionar preset · T4 voltar hierárquico (conteúdo→rail→fechar) · T5 ajustar um valor
(A-trava, B-destrava) · T6 fechar sem fechamentos acidentais. Critério: sem toque, <10 s,
zero fechamentos acidentais.

### Fase 1 — Bugs P1 (entregável imediato)

| # | Arquivo | Mudança | Aceite |
|---|---|---|---|
| 1.1 | `GamepadKeyBridge.kt` | **Decisão:** B deixa de ser traduzido/consumido — o bridge devolve `false` para `BUTTON_B` e o B cru chega ao pipeline do Compose. Superfícies tratam o B cru (linhas de ajuste destravam, `gamepadBackHandler` faz o voltar hierárquico). O BACK físico da Activity continua no `BackHandler` com o mesmo lambda. *Motivos: conserta o B-unlock das linhas de ajuste sem caminhos sintéticos; converge com a biblioteca (B cru); evita a fragilidade do dispatcher (P2-12); o jogo nunca vê o B porque o XServerScreen já recusou o evento quando há overlay.* | T4, T5, T6 |
| 1.2 | `QuickMenu.kt:422` | `BackHandler` hierárquico (conteúdo→aba selecionada→dismiss) | T4 |
| 1.3 | Linhas de ajuste | A-trava via DPAD_CENTER, B-destrava via BACK (remover `BUTTON_A/B` crus) | T5 |
| 1.4 | `QuickMenu.kt:437-446` | scrim não-focável (`pointerInput + detectTapGestures`) | T4, T6 |
| 1.5 | `ScreenEffectsPanel.kt:1522-1540` | remover ramo `BUTTON_A` morto do `gamepadActivate` (ou extrair já como `gamepadSelectable`) | T3 |
| 1.6 | Build + device | `assembleModernDebug` + teste físico | T1..T6 sem toque |

### Fase 2 — Framework compartilhado

| # | Mudança | Aceite |
|---|---|---|
| 2.1 | Extrair `gamepadSelectable` / `gamepadAdjustableRow` / `gamepadBackHandler` e migrar QuickMenu + EFFECTS + 4 dialogs | consistência: mesmas teclas, mesmo consumo |
| 2.2 | `OverlayInputContext` (enum + holder) consumido pelo XServerScreen (onKey+onMotion) | overlay novo só funciona registrando contexto |
| 2.3 | D2/R2: `selectedTab` por estado; foco volta ao botão da aba selecionada | T4 com aba visualmente destacada |
| 2.4 | D7: linguagem visual única de foco (focada/selecionada/travada) | estados distinguíveis em tema claro/escuro |
| 2.5 | D4/R5: haptics padronizados (gamepad conectado) | T3/T5 com vibração perceptível |
| 2.6 | `JoystickFocusNavigator` com histerese | sem movimento fantasma em jitter |

### Fase 3 — Cobertura completa

| # | Mudança | Aceite |
|---|---|---|
| 3.1 | `ControllerBindingDialog`, `EditModeToolbar`+dropdown, `GameInviteOverlay`, `ConnectionStatusBanner`, manual-resume button | matriz 2.2 sem ✗ |
| 3.2 | Biblioteca converge para `gamepadSelectable`/`gamepadBackHandler` (mantém L1/R1 e bootstrap) | mesmas teclas, mesmo significado |
| 3.3 | P1-5: alternativa de abertura do menu (ex.: SELECT ou START com fallback). **Trade-off a decidir na implementação:** ligar SELECT ao menu rouba o botão xinput "Back" dos jogos que o usam (o binding padrão atual deixa SELECT em passthrough); convenção de handhelds sugere SELECT=menu, mas exige teste em jogo | T1 em pad sem Home |
| 3.4 | P1-6: fallback `AXIS_RX/RY` no `ExternalController` | right stick funcional em pad com RX/RY |
| 3.5 | D5/R1: dica transitória + strings localizadas (14 locales) | dica aparece/desaparece; EN + pt-rBR corretos |
| 3.6 | Regressão geral (matriz 2.2 + toque + jogo) | matriz completa |

## 6. Estratégia de testes

- **Unit (JVM):** mapeamento/tradução de teclas e consumo (lógica pura extraída dos
  modifiers/bridge); dead zone/histerese do navigator.
- **Manual por fase:** tarefas T1..T6 com controle Bluetooth real; TalkBack desligado (padrão)
  e ligado (regressão de acessibilidade); tema claro/escuro (D7).
- **Regressão obrigatória por fase:** (a) toque intocado (tap/click/drag/scroll/swipe);
  (b) caminho do jogo intocado (pad → xinput dentro de um jogo); (c) BACK real da Activity
  (botão físico do dispositivo) sem double-fire.
- **Matriz de dispositivos recomendada:** pads com e sem botão Home; com D-pad físico (keys) e
  com hat (axes); right stick reportando `AXIS_Z/RZ` vs `AXIS_RX/RY`.

## 7. Fora de escopo / evoluções futuras

- **Unificação total (Abordagem A):** `GamepadInputController` único na raiz + registry de
  consumidores com prioridade — reavaliar depois da Fase 3.
- **Calibração de dead zone por dispositivo** e compensação de drift.
- **Teclado virtual para a busca** (gamepad não digita).
- **Restauração de posição de foco** entre aberturas do menu.
- **Perfil de binding padrão para SELECT/START** (abrir/fechar menu) — decisão de produto.

## Execução (a preencher por fase)

| Fase | Status | Data | Notas |
|---|---|---|---|
| 1 — Bugs P1 | **concluída** | 2026-08-08 | 1.1 bridge sem B; 1.2 BackHandler hierárquico (railFocused); 1.3 A-lock via DPAD_CENTER + B-unlock cru nas duas linhas de ajuste; 1.4 scrim pointerInput (não-focável); 1.5 ramo BUTTON_A morto removido do gamepadActivate; 1.6 build+install+boot OK (teste físico pendente) |
| 2 — Framework | **parcial** | 2026-08-08 | 2.2 OverlayInputContext (enum + fonte única no XServerScreen) ✓; 2.3 R2 seleção≠foco (removido auto-select on-focus) + foco volta à aba selecionada ✓; 2.5 GamepadHaptics + vibração no bridge ✓; 2.6 histerese no JoystickFocusNavigator ✓; 2.1 extração gamepadSelectable/gamepadAdjustableRow/gamepadBackHandler — pendente; 2.4 D7 linguagem visual — pendente |
| 3 — Cobertura | **parcial** | 2026-08-08 | 3.1 ControllerBindingDialog com navigator+bridge ✓; EditModeToolbar/dropdown, GameInviteOverlay, ConnectionStatusBanner — pendentes; 3.2-3.6 pendentes |
