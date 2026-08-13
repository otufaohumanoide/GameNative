# Spec 2026-08-13 — Botão Home sai do overlay direto para o jogo (design)

**Data:** 2026-08-13
**Origem:** pedido do usuário — "se eu selecionar um shader e apertar Home, quero voltar
direto para o jogo; hoje ele volta para o QuickMenu e só depois de fechar o menu é que
chego na tela de dar play. A intenção é criar pavimento para, no futuro, uma opção de
configuração 'ir direto ao jogo': com ela ativa, Home fecha tudo e o jogo já dá play —
o usuário decide o nível de atrito."
**Padrões obrigatórios (repo):** strings EN + pt-rBR; gamepad-first; zero regressão no
input do guest; fluxo spec → revisão → implementação. **Cuidado extra:** `XServerScreen.kt`
está no limite do verifier (registros de método dex) — a implementação não pode adicionar
locals novas à função principal; tudo via `remember {}`.

---

## Contexto — o fluxo atual (evidência)

- **Home no browser de shaders** fecha só o browser: o bridge próprio do browser
  (`ShaderBrowserOverlay.kt:188-189`, `modeKeyBehavior = CloseOverlay`,
  `onCloseOverlay = onClose`) chama `onClose` → `shaderBrowserOpen = false`
  (`QuickMenu.kt:694`) → o usuário cai no QuickMenu (EFFECTS) — um salto a mais.
- **O caminho "fecha tudo" já existe no browser**: o duplo-clique A A
  (`ConfirmAndClose`) executa `onClose(); onCloseQuickMenu()`
  (`ShaderBrowserOverlay.kt:636-637`).
- **Fechar o menu com `SUSPEND_POLICY_MANUAL`** deixa o jogo suspenso até o Play
  (`XServerScreen.kt:888-889`; `waitingForManualResume` em :1551) — é a "tela onde ele
  pode dar play".
- **O mecanismo de resume-forçado já existe**: `shouldForceResumeOnMenuClose`
  (`XServerScreen.kt:555`, setado em :1122 para o caso do teclado; consumido em
  :2831-2833 no `onAnimationComplete` → `forceResumeIfSuspended()`). O pavimento é
  reutilizar essa flag.

---

## M1 — Home no browser fecha browser + menu (comportamento imediato)

**Problema:** o usuário no fundo do browser precisa de DOIS Homes para voltar ao jogo
(browser → menu → jogo), quebrando o modelo "Home é a tecla do sistema" (P1 do spec
2026-08-12: START espelha HOME como toggle de sistema).

**Design:**
- Novo callback costurado de cima para baixo: `XServerScreen → QuickMenu →
  ShaderBrowserOverlay`, nome `onHomeFromOverlay` (no QuickMenu) / `onHome` (no browser):
  - `XServerScreen` ganha `val dismissOverlayToGame: () -> Unit = remember { ... }` que
    chama `dismissOverlayMenu()` e, **se** `PrefManager.homeButtonStraightToGame &&
    manualResumeMode && !neverSuspend`, seta `shouldForceResumeOnMenuClose = true`
    (setar DEPOIS de `dismissOverlayMenu()` — a linha :1122 sobrescreve a flag com o
    caminho do teclado). Passa como `onHomeFromOverlay` ao `QuickMenu` (:2771).
  - `QuickMenu` ganha o parâmetro `onHomeFromOverlay: () -> Unit` e o repassa ao browser
    como `onHome` (:692-697). O bridge do próprio menu (:708-710) passa a usar
    `onCloseOverlay = onHomeFromOverlay` (em vez de `onDismiss`) — com a opção desligada
    o comportamento é idêntico ao de hoje; com ela ligada, Home também sai direto do
    menu, mantendo a semântica única da tecla.
  - `ShaderBrowserOverlay` ganha `onHome: () -> Unit`; o bridge (:188-189) usa
    `onCloseOverlay = onHome`. B/back continuam hierárquicos (`navigateBack`, :247-259):
    uma superfície por vez — Home é a saída de emergência educada.
- O duplo-clique A A **não muda** (segue `onClose + onCloseQuickMenu`): é o loop rápido
  de experimento, não a tecla do sistema. Decisão registrada: se no futuro fizer sentido,
  ele pode respeitar a opção também.
- Rótulo do hint GUIDE na barra do browser passa a "Back to game" / "Voltar ao jogo"
  (`shader_browser_action_close`) — honesto com o novo destino da tecla.
- Comentários do P3 no browser (:878-881) e do bridge no QuickMenu (:703-706) atualizados
  para a nova semântica.

**Arquivos:** `XServerScreen.kt`, `QuickMenu.kt`, `ShaderBrowserOverlay.kt`,
`res/values/strings.xml`, `values-pt-rBR/strings.xml`.
**Aceite:** no browser, Home fecha browser+menu e cai na tela do jogo (Play com
SUSPEND_POLICY_MANUAL); B continua voltando um nível; duplo-clique inalterado; PS
abre/fecha o menu normalmente; jogo não recebe input extra.

---

## M2 — Opção de configuração "Home vai direto ao jogo" (o pavimento)

**Problema:** o usuário deve escolher o nível de atrito: com a opção ativa, Home fecha
tudo E o jogo volta a rodar sozinho (sem a tela de Play); desativada (padrão), Home
fecha tudo e mantém o Play — o comportamento atual de segurança.

**Design:**
- `PrefManager.homeButtonStraightToGame: Boolean` (default `false`, padrão
  `booleanPreferencesKey`, como `showFps` em `PrefManager.kt:351-354`).
- Toggle em `SettingsGroupInterface` (padrão `SettingsSwitch` já usado para
  `swapFaceButtons`/`showGamepadHints`, :315-348), posicionado junto aos itens de
  gamepad/interface, com título e subtítulo:
  - EN: "Home button returns to the game" / "With the in-game menu open, Home closes
    everything and resumes the game instead of showing the Play screen."
  - pt-BR: "Botão Home volta ao jogo" / "Com o menu do jogo aberto, Home fecha tudo e
    retoma o jogo, sem passar pela tela de Play."
- Efeito: `dismissOverlayToGame` (M1) já lê a pref — o toggle só liga/desliga o caminho
  de resume-forçado. Nenhuma mudança em XServerScreen além do M1.

**Arquivos:** `PrefManager.kt`, `SettingsGroupInterface.kt`, strings.
**Aceite:** opção OFF (padrão) = fluxo do M1 com tela de Play; ON = Home no browser e no
menu fecha tudo e o jogo retoma sozinho (manualResumeMode); containers com suspensão
"nunca" não são afetados (flag exige `!neverSuspend`); a opção persiste entre sessões.

---

## Pendências on-device (padrão do repo)

- [ ] M1: Silksong + container com SUSPEND_POLICY_MANUAL — Home no browser cai direto na
  tela do jogo (1 Home); B volta um nível; START espelha Home.
- [ ] M2: com a opção ON, Home (browser e menu) retoma o jogo sozinho; com OFF, mantém o
  Play; toggle persiste após reiniciar o app.
- [ ] Regressão: cenário do hardening de gamepad (spec 2026-08-12) — PS abre/fecha,
  bootstrap de foco, guardião — com o browser aberto.
