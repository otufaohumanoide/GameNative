# IME do campo de busca do QuickMenu: abrir só no X, fechar no B — design (2026-08-10)

> **Problema:** ao navegar com o gamepad no QuickMenu (aba EFFECTS) até o campo de busca,
> o teclado virtual abre automaticamente. O esperado (padrão gamer, decisão do usuário):
> **X abre, B fecha** — navegar até o campo nunca deve abrir o teclado.
>
> **Causa raiz (verificada):** o mecanismo atual (`ScreenEffectsPanel.kt`) é "esconder
> depois de mostrar": um `LaunchedEffect` chama `keyboard?.hide()` **uma única vez**, e só
> se `GamepadNavigationClock.lastMoveAt` for < 400ms. Dois defeitos:
>
> 1. **Corrida:** o `hide()` roda antes do `startInputMethod` assíncrono do campo
>    (Compose inicia a sessão de IME um frame depois do foco) → o teclado aparece mesmo
>    com o hide() já executado.
> 2. **Walk-down sem stamp:** ao reabrir o menu, o bootstrap faz walk-down até o índice
>    lembrado (`QuickMenu.kt` — `repeat(effectsFocusIndex) moveFocus(Down)`); se o campo
>    é o índice lembrado, o foco pousa nele **sem** `lastMoveAt` recente (o stick não
>    moveu) → heurística não dispara → teclado abre em toda reabertura.
>
> **E não existe caminho de abrir explicitamente (X).**

## 1. Solução

**"Nunca abrir sozinho + abrir só no X, fechar no B":**

- **API:** `androidx.core.view.SoftwareKeyboardControllerCompat` — disponível desde core
  **1.11.0** (o projeto usa 1.15.0 — **sem bump de dependência**). `show()`/`hide()` são
  best-effort e funcionam **fora de sessão de input ativa** (backing de
  `WindowInsetsControllerCompat` para IME) — robusto contra o "null session"
  (issuetracker 311437241) que afeta o `SoftwareKeyboardController` do Compose.
- **Visibilidade do IME:** `snapshotFlow { WindowInsets.ime.getBottom(density) > 0 }` —
  padrão moderno da comunidade. `WindowInsets.isImeVisible` é evitado: retorna `true` na
  primeira composição (issuetracker 388616191).
- **Supressão:** foco chegando via gamepad (clock < 400ms, incluindo walk-down/guardian)
  → `hide()` imediato + **loop contínuo** (job cancelável, re-hide a cada 120ms) enquanto
  o campo estiver focado SEM intenção explícita — o retry limitado a 300ms ainda deixava o
  teclado piscar quando o `startInputMethod` abria tarde (verificado no device 2026-08-11:
  com o loop contínuo, 25/25 amostras `inputShown=false` navegando; X abre fixo 10/10;
  B fecha sem fechar o menu). O X cancela o job ANTES do `show()` (sem corrida hide/show);
  toque no campo também é intenção explícita (pointerInput observa o down sem consumir).
- **X abre:** `BUTTON_A`/`DPAD_CENTER`/`ENTER` via `GamepadKeyLogic.selectableActivation`
  com o campo focada e teclado fechado → `show()`.
- **B fecha:** `BUTTON_B` com o teclado aberto → `hide()` **consumido** (o menu não
  fecha — superfície mais interna vence, padrão do `gamepadBackHandler`); B com teclado
  fechado propaga (back normal do menu).
- **Reset:** `selectIntent = false` ao perder o foco ou quando o IME oculta (cobre back
  físico e outros caminhos).

## 2. Alterações

| Arquivo | Mudança |
|---|---|
| `QuickMenu.kt` (`requestMenuFocus`) | carimbar `GamepadNavigationClock.lastMoveAt = now` no início (walk-down + guardian = chegada programática) |
| `ScreenEffectsPanel.kt` (campo de busca) | `SoftwareKeyboardControllerCompat(LocalView.current)`; `imeVisible` via snapshotFlow; supressão com retry; `onPreviewKeyEvent` (X abre / B fecha); reset de `selectIntent` |
| `SearchFieldImeLogic.kt` (novo) | objeto puro: `arrivedViaGamepad(now, lastMoveAt, windowMs)` e `onKey(...)` → `OpenIme`/`CloseIme`/`Propagate` |
| `SearchFieldImeLogicTest.kt` (novo) | testes unitários (estilo `GamepadModifiersTest`) |

## 3. Não-escopo

- Sem bump de dependências (core 1.15.0 já tem a API).
- `NoExtractOutlinedTextField` não muda (continua genérico; o handler de teclas fica no
  call site).
- Sem auto-abrir do teclado em tap-toque no campo? **Não**: tap continua abrindo
  (intenção explícita de toque — o suppress só age quando o foco chegou via gamepad).
- Caminho GL (`GLScreenEffectsTabContent`) não tem lista de shaders — intocado.
