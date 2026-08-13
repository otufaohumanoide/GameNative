# Spec — IME do browser de shaders: nunca abrir sozinho no bootstrap de foco

**Data:** 2026-08-13
**Complementa:** `docs/superpowers/specs/2026-08-10-search-field-ime-explicit-design.md` (refina o gate de supressão do campo compartilhado; não substitui).
**Escopo:** `GamepadSearchField.kt` e `SearchFieldImeLogic.kt` + testes. Zero mudança no `ShaderBrowserOverlay.kt`, no bus de input, nos navigators/bridges ou no QuickMenu.
**Status:** implementado — ver `docs/MILESTONES.md`.

---

## 0. Problema (verificado no código)

Ao abrir o browser de shaders (QuickMenu → Shaders → "Explorar"), o teclado virtual abre automaticamente — mesmo quando o usuário só quer navegar a lista (recents/favoritos) sem buscar.

**Causa raiz:** o bootstrap de foco do browser foca a linha lembrada; no Home a linha 0 **é o campo de busca** (`ShaderBrowserOverlay.kt:788` — `focusRequester = requesterFor(0)`). O campo ganha foco **programaticamente** (`requestFocus()`), e o Compose inicia a sessão de IME ao ganhar foco.

A supressão em `GamepadSearchField.kt:92-109` só dispara se `SearchFieldImeLogic.arrivedViaGamepad(now, lastMoveAt, 400)` — ou seja, se o stick moveu nos últimos 400 ms. Ao chegar no "Explorar" pressionando **X** (botão não carimba `GamepadNavigationClock.lastMoveAt`), ou com mais de 400 ms desde o último move, a heurística falha → sem supressão → teclado abre sozinho.

O gate é frágil por natureza: ele tenta inferir *como* o foco chegou (gamepad vs. toque) através de um relógio de movimento — mas a chegada por `requestFocus()` programático (bootstrap, guardião de foco, restauração de tela) não é "chegada via toque" e também não tem carimbo recente.

## 1. Solução

**Regra nova (vale para o campo inteiro):** o teclado só abre por **intenção explícita** — toque no campo ou X/A/DPAD_CENTER/ENTER — independentemente de como o foco chegou. Foco sem intenção (bootstrap, guardião, walk-down, navegação por stick) ⇒ supressão contínua (hide imediato + loop de 120 ms, mecânica já existente).

Concretamente em `GamepadSearchField.kt`:

1. **Remover o gate `arrivedViaGamepad`** no `LaunchedEffect(searchFieldFocused)`: focou → `if (!searchImeWanted) startImeSuppression()`. A intenção passa a ser o único critério, e ela já é expressa por `searchImeWanted` (setado pelo X e pelo toque).
2. **Toque no campo = intenção incondicional:** no `pointerInput`, registrar o down **antes** do foco aterrar — hoje há `if (searchFieldFocused)` que só cobre re-toque em campo já focado; sem remover esse guard, um tap que aterra foco seria suprimido pelo loop novo (regressão). Novo comportamento: qualquer primeiro down na área do campo → `searchImeWanted = true` + `stopImeSuppression()`. **Anti-vazamento:** ao fim do gesto (`waitForUpOrCancellation`), se o foco não aterrou (down na faixa de padding de 16 dp em campo não-focado), `searchImeWanted` volta a `false` — a intenção não pode vazar para o próximo ganho de foco via gamepad (abriria o teclado sem intenção). O restart do `pointerInput(searchFieldFocused)` só cancela o gesto justamente quando o foco mudou (caso em que a intenção deve ser mantida).

Fluxos resultantes:

| cenário | comportamento |
|---|---|
| abrir browser (foco bootstrap na busca) | supressão ativa, teclado nunca abre |
| stick para baixo sobre a lista | campo perde foco → supressão para |
| voltar ao campo com stick | supressão ativa de novo, teclado não abre |
| X/A/ENTER no campo | `searchImeWanted = true`, cancela supressão, `show()` |
| B com teclado aberto | fecha (consumido; superfície não fecha) |
| tap no campo (focado ou não) | `searchImeWanted = true` no down, cancela supressão → teclado abre |
| IME oculto por outro caminho (back físico) | `LaunchedEffect(imeVisible)` reseta `searchImeWanted` |

## 2. Alterações

| Arquivo | Mudança |
|---|---|
| `GamepadSearchField.kt` | remove `SearchFieldImeLogic.arrivedViaGamepad(...)` do efeito de foco; supressão incondicional sem intenção; `pointerInput` sem o guard `searchFieldFocused` |
| `SearchFieldImeLogic.kt` | remove `arrivedViaGamepad` (sem callers após a mudança — padrão do repo: lógica morta não fica) |
| `SearchFieldImeLogicTest.kt` | remove os 4 testes de "gamepad arrival detection" |

`GamepadSearchField` tem um único caller (`ShaderBrowserOverlay.kt:782`) — o QuickMenu (painel de efeitos) não usa o componente; o comportamento do menu não muda.

## 3. Não-escopo

- `ShaderBrowserOverlay.kt` intocado (foco/paginação/guardião ficam como estão — só o campo muda de política).
- Bus de input, `GamepadNavigationClock`, navigators e bridges intocados.
- X abre / B fecha (regras do spec 2026-08-10) permanecem idênticas; `SearchFieldImeLogic.onKey` não muda.

## 4. Verificação

### 4.1 Testes JVM

- `SearchFieldImeLogicTest` atualizado (removidos só os testes do gate morto; `onKey` intacto).
- Rodar: `./gradlew :app:testModernDebugUnitTest --tests "*Shader*" --tests "*Gamepad*" --tests "*SearchField*"`.

### 4.2 On-device (Mi 11; harness `debug.gamenative.input`)

| # | cenário | esperado |
|---|---|---|
| I1 | PS → Shaders → Explorar (chegada via X) | teclado NÃO abre; foco na busca |
| I2 | stick para baixo → navegar recents/favoritos 10 s | teclado nunca abre |
| I3 | voltar ao campo com stick (up) | teclado não abre |
| I4 | X no campo | teclado abre; digitar funciona |
| I5 | B com teclado aberto | teclado fecha, browser continua aberto |
| I6 | tap no campo | teclado abre |
| I7 | X → B → X (ciclo) | abre/fecha estável, sem piscar |
| I8 | reabrir browser após usar busca (estado cacheado) | query restaurada, teclado fechado |
