# Spec — Revisão de UX de gamepad do QuickMenu (P1–P6, padrões de console)

**Data:** 2026-08-12
**Origem:** revisão do usuário da cadeia rail → abas → conteúdo → browser de shaders contra
padrões PS4/Xbox + documento de referência (roteamento por bus = GameInputManager com FSM).
**Veredito da revisão:** a arquitetura está correta (escopo único por superfície, bus como
roteador centralizado) — sem mudança estrutural. Este documento fecha os 6 atritos reais.

---

## P1 — START/SELECT escapavam para o jogo com o menu aberto

**Problema:** `BusGamepadKeyBridge` interceptava A/B/L1/R1/L2/R2/DPAD/MODE, mas
BUTTON_START e BUTTON_SELECT passavam para o jogo por trás do menu (pausa inesperada / UI do
jogo abrindo).

**Correção (implementada e verificada on-device):**
- Bridge com o overlay aberto: **START consumido** e, com `ModeKeyBehavior.CloseOverlay`,
  o primeiro ACTION_DOWN fecha o menu — espelha o HOME (`GamepadBusInput.kt`).
- **SELECT consumido** (nunca chega ao jogo atrás do overlay).
- Menu fechado: **START abre o QuickMenu** (metade de abertura do toggle) no
  `XServerScreen.onKeyEvent` (slot-0, antes do binding engine) — espelha o HOME
  (`XServerScreen.kt`). O resume manual (overlay pausado) mantém prioridade e ainda usa START.

**Evidência on-device (harness):** menu fechado + `key:108` ⇒ log `START opens QuickMenu
(P1 toggle)` + menu abre com foco; menu aberto + `key:108` ⇒ menu fecha; menu aberto +
`key:109` ⇒ menu permanece (consumido).

> Nota de trade-off: com o menu fechado, START abre o sistema em vez do pause do jogo —
> comportamento espelhando HOME, como pedido. Reverter = remover o bloco no XServerScreen.

## P2 — L1/R1 sem gate de repeatCount

**Problema:** segurar R1 ciclava abas a ~20/s (repeat do Android).

**Correção:** no preview handler do QuickMenu, L1/R1 só agem com `repeatCount == 0`; L2/R2
continuam repetindo (scroll contínuo desejado). Sem mudança de API.

## P3 — Sem GamepadActionBar dentro do browser de shaders

**Problema:** as dicas A/B/LB/RB/LT/RT sumiam na superfície mais profunda.

**Correção:** `ShaderBrowserOverlay` ganhou a própria `GamepadActionBar` (footer contextual):
**A = Selecionar shader · B = Voltar · PS = Fechar browser** (novo `GamepadButton.GUIDE` com o
ícone do guia Xbox; strings EN/pt-rBR). A dica do duplo-clique continua no subtítulo da linha.

## P4 — Troca de aba levava o foco para o rail (2 pressões por troca)

**Problema:** `selectAdjacentTab` focava o botão da aba; era preciso DPAD_RIGHT extra.

**Correção:** a troca por L1/R1 agora foca **o conteúdo da nova aba** (primeira linha +
walk-down até o índice lembrado — protocolo G9), com fallback para o botão do rail quando a
aba não tem itens focáveis (ex.: TOOLS sem processos). Uma pressão a menos por troca, padrão
Ozone/console.

**Evidência on-device:** `selectAdjacentTab(delta=1)` + `QMFocus: row 0 focused` — o foco
cai na lista, não no rail.

## P5 — L2/R2 rolavam sem mover o foco (linha podia sair da viewport)

**Problema:** `scrollBy` puro deixava o foco numa linha fora da viewport; DPAD depois pulava
para linhas invisíveis.

**Correção:** com o foco no conteúdo, L2/R2 **movem o foco N passos** (N = meia viewport /
altura de linha, mínimo 1) — o Compose auto-scrolla a linha focada para a viewport, então a
seleção acompanha a página. Com o foco no rail (sempre visível), mantém-se o scroll puro.

**Evidência on-device:** `R2` ⇒ `QMFocus: row 1` → `row 2`; `L2` ⇒ `row 0`.

## P6 — Botão fechar (X) do header inalcançável por gamepad

**Problema:** o `QuickMenuCloseButton` ficava fora do fluxo de foco.

**Correção:** rota explícita via `focusProperties { up = closeButtonFocusRequester }` na
coluna do rail e no box do conteúdo — DPAD/stick **Up** a partir da primeira linha (rail ou
conteúdo) foca o X; A ativa (fecha o menu). O botão ganhou `focusRequester` próprio.

**Evidência on-device:** stick Up na linha 0 ⇒ `moveFocus(Up)`; A ⇒ `dismissOverlayMenu`.

---

## Verificação

- **101/101 testes JVM verdes** (Shader* + Gamepad* + SearchFieldIme + FpsLimiter).
- **On-device (Mi 11 + DualShock 4, via harness `debug.gamenative.input`):**
  P1 ✓ (abrir/fechar/consumir), P2 (gate de código; pressão única ok), P3 ✓ (footer renderizado),
  P4 ✓, P5 ✓, P6 ✓. Duplo-clique A-A e F1–F10 do spec anterior: pendentes de confirmação
  final com o usuário (teste manual em andamento).
- **Harness corrigido:** `gamepadDeviceId()` agora pontua devices (gamepad+botões > joystick)
  — o DS4 expõe 3 devices (controle + touchpad + sensores) e o touchpad também anuncia
  GAMEPAD; a seleção preferia o touchpad, cujos eventos o roteamento real ignora.

## Não fazer (proteções de escopo)

- Não reconstruir o sistema de input (bus já é o roteador centralizado).
- Não tocar em `VulkanLibrashader.cpp`, `VulkanRenderer.java` nem efeitos nativos.
- Não mudar o comportamento do primeiro clique (sem delay de debounce).
