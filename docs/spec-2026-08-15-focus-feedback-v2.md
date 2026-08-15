# Spec 2026-08-15 — Focus Feedback v2: linguagem visual de foco madura

**Data:** 2026-08-15
**Origem:** relato do usuário (perda de referência de foco no QuickMenu/settings do
fork vs. navegação "perfeita" da Library original) — auditoria feita no código.
**Princípio:** toda superfície navegável por gamepad deve responder em < 500 ms à
pergunta "onde estou?" — feedback de foco é infraestrutura, não decoração.

---

## 0. Diagnóstico (o que existe e por que falha)

A linguagem única JÁ existe (`GamepadFocus.kt`, spec 2026-08-09 §3.2): Focused =
ring animado, Selected = borda sólida, Locked = ring grosso. Os defeitos:

| # | Defeito | Evidência |
|---|---|---|
| D1 | Ring de foco sutil demais: 2dp, 1 rotação a cada 5000 ms — quase invisível em fundo escuro | `GamepadFocus.kt:59-60` (defaults `width=2.dp, durationMillis=5000`); comparar com a Library original: ring 4dp + escala 1.1 + tint de fundo (`FocusRing.kt:24`, `SettingsScreen.kt:246-295`, `LibraryGridCard`) |
| D2 | Ambiguidade selected × focused: rows de switch passam `selected = checked` → TODA toggle ligada exibe borda sólida persistente mesmo sem foco; várias "bordas acesas" + um ring fraco = o foco não se destaca | `SettingsGroupGamepad.kt:341` (`gamepadSelectable(selected = checked, …)`), mesmo padrão nas demais rows custom |
| D3 | Foco pousa em nós sem feedback: fallback do QuickMenu manda o foco para a rail do tab quando o conteúdo não tem nada focusable — nesse estado a área de conteúdo não mostra NENHUM indicador | `QuickMenu.kt:573,624` (comentários "Tab with no focusable content — fall back to its rail button") |

O usuário sente "perdido / sem borderline" — corretamente: ou o ring é fraco demais
(D1), ou há bordas demais e a errada domina (D2), ou não há nada (D3).

## 1. Design

### 1.1 Focused mais forte (D1) — `GamepadFocus.kt`

- Defaults novos: `width = 3.dp`, `durationMillis = 1200` (uma volta a cada 1,2 s —
  movimento perceptível na visão periférica).
- Cor do Focused: primária CLARA (não o sweep primary/tertiary lento). O sweep
  animado continua (identidade visual), mas com um anel-base sólido brilhante sob o
  sweep — o foco nunca "desaparece" entre as cores do gradiente.
- **Overlay de fundo focado**: `gamepadFocus` no estado Focused desenha um overlay
  translúcido do accent (alpha ≈ 0.08) clipado no shape, ANTES do ring — o mesmo
  recurso que a Library original usa (tint de fundo), agora parte da linguagem comum.
  Ganho: a row focada acende inteira, não só a borda.

### 1.2 Selected rebaixado (D2) — fim da competição com o foco

- `GamepadFocusState.Selected` deixa de usar borda sólida: passa a ser **tint de
  fundo persistente** (accent alpha ≈ 0.10) + eventual borda fina 1dp com alpha 0.5.
- Regra nova da linguagem: **borda animada/brilhante é EXCLUSIVA do foco**. Checked
  de toggle, aba ativa, preset ativo continuam visíveis (tint), mas nunca competem
  com o foco em peso visual.
- Nenhuma mudança nos call sites: o rebaixamento é feito dentro de `gamepadFocus`
  (centralizado — herda para todas as superfícies).

### 1.3 Auditoria de nós invisíveis (D3) — `QuickMenu.kt`

- Rail de fallback: os botões da rail já recebem `gamepadFocus` quando focados
  (`QuickMenu.kt:820`); confirmar que o fallback de tab vazia move o foco para um
  botão VISÍVEL da rail (não para um container) e que o ring aparece nele.
- Scrim e containers não-clicáveis nunca focusable (o scrim do browser já é
  NOT-clickable por isso — `QuickMenu.kt:738`); auditar os fallbacks das tabs
  vazias (TOOLS sem processos) para garantirem um alvo visível ou exibirem o
  estado "nada navegável" explicitamente (mensagem de conteúdo vazio focusable).
- Bootstrap de foco existente (`clearFocus(true)` + retries) permanece intocado.

### 1.4 Sliders e A-lock

- O lock (G4) já tem `Locked` = borda grossa + indicador `●`. Com Selected
  rebaixado, Locked passa a ser o ÚNICO estado com borda sólida estática além do
  foco — hierarquia: Focused (animado+brilhante) > Locked (sólido grosso) >
  Selected (tint). Documentar na KDoc da enum.

## 2. Arquivos afetados

| Arquivo | Mudança |
|---|---|
| `ui/component/GamepadFocus.kt` | Defaults 3dp/1200ms; anel-base sob o sweep; overlay de fundo no Focused; Selected = tint (1.1, 1.2) |
| `ui/component/FocusRing.kt` | Default `width` alinhado (4dp → 3dp) para uniformidade |
| `ui/component/QuickMenu.kt` | Auditoria dos fallbacks de tab vazia (1.3) |
| `ui/component/GamepadModifiers.kt` | Apenas KDoc (hierarquia de estados) |
| Strings EN + pt-rBR | Apenas se surgir mensagem de "conteúdo vazio navegável" (1.3) |

Zero mudança de comportamento de navegação (dedupe, guards, bootstrap intocados) —
este spec é APENAS visual/feedback.

## 3. Verificação

- Build: `assembleModernDebug` (sem mudança de API — risco baixo).
- On-device (Mi 11 + DS4, harness `stick:0:1`/`key:96`):
  - [ ] QuickMenu: ring visível a 1 m de distância; trocar de tab com foco visível
        em toda transição (incl. tab vazia);
  - [ ] Settings → Gamepad: com 3+ toggles ON, apenas UMA row "acesa" (a focada);
        checked sem foco = tint sutil, sem borda;
  - [ ] Slider com A-lock: lock claramente distinto de foco;
  - [ ] Radial editor / Shader browser / remap dialog: mesmo ring, mesma hierarquia.
- Screenshots antes/depois registrados no spec (padrão da sessão de validação).

## 4. Fora de escopo

- Redesign do remap dialog visual (mock do controle estilo PPSSPP) — spec próprio.
- Rumble fallback no telefone + `USAGE_MEDIA` — spec próprio (aprovado, separado).
- Touch/hover feedback, animações de listas.
