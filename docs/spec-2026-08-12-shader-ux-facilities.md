# Spec 2026-08-12 — Facilidades de UX para a integração de shaders (design)

**Data:** 2026-08-12
**Origem:** pedido do usuário — "que outra facilidade falta para a integração com shaders
de forma a melhorar a UX poderíamos implementar?". Este documento detalha 6 modificações
ordenadas por custo/valor, com design, arquivos, evidências (file:line) e aceite de cada
uma. **Nenhum código é alterado até a revisão deste spec** (workflow do repo:
spec → revisão → implementação).
**Padrões obrigatórios (repo):** lógica pura JVM-testável (`GamepadStickLogic`,
`ShaderDoubleClickLogic`, `ShaderToggleSubtitle`); gamepad-first (o handheld é o modo
principal de input — toque é luxo); mesmas teclas = mesmos significados em todas as
superfícies; strings EN + pt-rBR; zero regressão no jogo (input do guest intocado).

> **Baseline (antes de qualquer feature nova):** pendências on-device existentes seguem
> como pré-requisito — F1–F10 do browser (`docs/spec-2026-08-12-shader-browser-focus-double-click.md`
> §6.2) e o cenário self-heal do technicolor (`docs/spec-2026-08-12-shader-closure-fix.md`).

---

## M1 — Paginação por gamepad no browser de shaders (L1/R1/L2/R2)

**Problema:** o browser é a superfície mais profunda do QuickMenu, mas **não tem teclas de
paginação**: a única forma de avançar é navegar o foco até a row "Show more" (12 presets
por página). O QuickMenu já tem o padrão completo — L1/R1 gateados por `repeatCount == 0`
(P2) e L2/R2 repetindo (P5) — mas o preview handler da raiz do QuickMenu é gateado por
`!shaderBrowserOpen` (QuickMenu.kt:639) e o `ShaderBrowserOverlay` não instala nenhum
handler próprio (não há `onPreviewKeyEvent` no arquivo; o `BusGamepadKeyBridge` re-dispatcha
L1/R1/L2/R2 crus para o Compose, que os ignora).

**Evidência:** ShaderBrowserOverlay.kt:152 (`PAGE_SIZE = 12`), :188-189 (`pages`/`pageOf`),
:677-681 e :731-736 (slice + `LoadMoreRow`), :679/734 (`PresetRow(it, nextSlot())`).

**Design:**
- No Box/Column raiz do `ShaderBrowserOverlay` (junto ao `.onFocusChanged` do guardião
  M6, ~:559-566), adicionar `.onPreviewKeyEvent { … }`:
  - `KeyDown` com `KEYCODE_BUTTON_L1`/`R1`: página `-1`/`+1` apenas com
    `repeatCount == 0` (padrão P2 — o repeat do Android não cicla páginas).
  - `KeyDown` com `KEYCODE_BUTTON_L2`/`R2`: página `-1`/`+1` permitindo repeat
    (segurar pagina continuamente — padrão P5).
  - Consumir sempre (`true`) quando a página mudou.
- **Paginável = só telas com slice**: resultados de busca (`pages["search"]`,
  ShaderBrowserOverlay.kt:677-681) e telas de família (`pages[familyKey]`, :731-736).
  A Home (recents + lista de famílias, sem LoadMore) **ignora** as teclas (retorna
  `false` — nenhuma surpresa).
- Função local `pageScreen(delta: Int)`:
  - `screen = nav.current`; se for Home → return.
  - `key` = `"search"` ou `familyKey`; `count` = tamanho da lista completa
    (`catalog.search(query).size` ou `items.size`); `maxPage = max(0, ceil(count / PAGE_SIZE) - 1)`.
  - `pages[key] = (pages[key] + delta).coerceIn(0, maxPage)`; se nada mudou → return.
  - **Foco pós-paginação:** reusar o protocolo existente (`pendingFocus`/`navTick`,
    :215-269): `pendingFocus = índice da row que estava focada ANTES da troca, coerceIn(0,
    rowsDaNovaPágina - 1)` (o clamp evita pedir a row "Show more" de uma página que não a
    tem) + `navTick++`. Com isso a seleção permanece na mesma posição relativa da página.
- **Footer de hints (P3):** o `GamepadActionBar` do browser ganha LB/RB e LT/RT:
  `GamepadAction(GamepadButton.LB, R.string.action_previous_page)` /
  `GamepadButton.RB, R.string.action_next_page)` / LT/RT idem com rótulo de repetição.
  Verificar se o enum `GamepadButton` já tem os quatro (LB/RB/LT/RT existem — são usados
  no footer do QuickMenu); senão adicionar.

**Arquivos:** `ShaderBrowserOverlay.kt`, `res/values/strings.xml` +
`res/values-pt-rBR/strings.xml` (2 strings novas de hint).
**Aceite:** no device — browser aberto numa família com >12 presets: R1 avança 1 página,
L1 volta, segurar R2 pagina continuamente, foco permanece na mesma posição relativa e o
Compose auto-scrolla; na Home as 4 teclas não fazem nada; A/B/PS/back intactos.
**Testes:** lógica de clamp/maxPage extraída para função pura (`ShaderPagingLogic.kt`,
`decidePage(current, delta, count, pageSize)` + clamp do índice) com testes JVM
(fronteiras, página 0, página cheia).

---

## M2 — Estado "Baixando" na row do toggle (aba EFFECTS)

**Problema:** `shaderToggleSubtitle` tem 4 estados (ActivePreset / SelectedNotDownloaded /
PickPreset / Off) mas **nenhum de download em andamento**. O download vive em
`ShaderSectionState` (hoisted — sobrevive ao fechamento do browser), porém se o usuário
fecha o browser durante o download e volta à aba EFFECTS, a row do toggle mostra o estado
antigo, sem progresso.

**Evidência:** `ShaderToggleSubtitle.kt` (4 estados, sem Downloading);
`ShaderSectionState.kt:56-61` (`installing`/`progress` hoisted); call site
`ScreenEffectsPanel.kt:616-629` (o `ShaderSectionState` está disponível como
`shaderSection` no `ScreenEffectsTabContent`, QuickMenu.kt:1009).

**Design:**
- `ShaderToggleSubtitle` ganha o estado **`Downloading`**; a função pura vira
  `shaderToggleSubtitle(enabled, name, path, installing: Boolean)` com `installing` sendo
  o PRIMEIRO ramo do `when` (download em andamento domina qualquer outro estado).
- Call site (`ScreenEffectsPanel.kt:616-629`): passar `installing = shaderSection.installing`;
  o estado `Downloading` renderiza `stringResource(R.string.shader_downloading,
  (shaderSection.progress * 100).toInt())`.
- Strings novas EN/pt-rBR: `shader_downloading` = "Baixando… %d%%" / "Baixando… %d%%".
- Testes (`ShaderToggleSubtitleTest`): +3 casos — installing vence ActivePreset;
  installing vence SelectedNotDownloaded; installing com tudo off.

**Aceite:** iniciar download num preset de nuvem, fechar o browser (B) → aba EFFECTS
mostra "Baixando… X%" atualizando; ao concluir, o subtítulo muda para o nome do preset
aplicado (estado real, sem reopen do browser).
**Arquivos:** `ShaderToggleSubtitle.kt`, `ScreenEffectsPanel.kt`,
`ShaderToggleSubtitleTest.kt`, `strings.xml` ×2.

---

## M3 — Favoritos no browser (Y / long-press + seção na Home)

**Problema:** `ShaderRecents` guarda só os últimos 5 aplicados (sem pin). Não existe forma
de marcar presets favoritos para acesso rápido numa sessão longa de experimentação
(o fluxo A-A aplica-e-fecha recompensa ter uma lista estável de "candidatos").

**Evidência:** `ShaderRecents.kt` (SharedPreferences, MAX 5); Home do browser já renderiza
seção Recents (`ShaderBrowserOverlay.kt:687-692`); `combinedClickable` com `onLongClick`
já existe no `gamepadSelectable` (GamepadModifiers.kt:143-154) e o browser já usa
long-press para cancelar download (`PresetRow.onLongClick`, :412-423).

**Design:**
- **Novo `ShaderFavorites.kt`** (padrão `ShaderRecents`): SharedPreferences privada
  `"shader_favorites"`, MAX 20, `list() / add(path) / remove(path) / isFavorite(path) /
  toggle(path)` (puro e JVM-testável com `TemporaryFolder`… usar `SharedPreferences`
  abstraída como o recents — seguir o padrão do arquivo existente).
- **Home do browser:** seção "Favoritos" ACIMA de "Recentes" (quando não vazia),
  mesmas `PresetRow` (filtro `!it.broken`).
- **Toggle por gamepad:** a `PresetRow` ganha handler `KEYCODE_BUTTON_Y` no
  `onPreviewKeyEvent` do `gamepadSelectable` (só quando a row está focada, ACTION_DOWN,
  repeatCount == 0) → `ShaderFavorites.toggle(path)` + `GamepadHaptics.vibrate`.
  Y não é consumido em nenhuma superfície hoje — sem conflito.
- **Toggle por toque:** long-press na `PresetRow` (quando NÃO há download em andamento —
  o long-press de cancelar continua com prioridade quando `downloadingThis`).
- Indicador visual: ícone de estrela (trailing) nas rows favoritadas + hint no footer:
  `Y = Favoritar` (verificar enum `GamepadButton` — adicionar `Y` se ausente).

**Arquivos:** novo `ShaderFavorites.kt` (+ teste), `ShaderBrowserOverlay.kt`,
`GamepadModifiers.kt` (se o handler de Y for generalizado no `gamepadSelectable`),
`GamepadActionBar.kt` (ícone Y se ausente), `strings.xml` ×2.
**Aceite:** favoritar/desfavoritar por Y e por long-press reflete na seção da Home
imediatamente; persiste entre sessões; recents intocados.

---

## M4 — Badge de shader na biblioteca (per-game store)

**Problema:** o estado de shader é por-jogo (`PerGameShaderStore`, commit `21d8eba9`), mas
a biblioteca não indica quais jogos têm shader ativo — o usuário só descobre abrindo o
jogo e o QuickMenu.

**Design:**
- **Estado único por tela:** carregar UMA vez por composição da biblioteca um
  `Set<String>` de appIds com entrada `enabled == true` (ler o JSON do
  `PerGameShaderStore` diretamente — expor `fun enabledGameIds(): Set<String>` na store,
  com a mesma degradação a vazio em JSON malformado).
- **Onde:** no ponto que compõe os cards (`LibraryGridCard` / `LibraryListCard` — confirmar
  o parâmetro de identificação do jogo; a chave da store é `container.id`, que deve
  casar com o `appId` do item de biblioteca no formato `"STEAM_1293830"` — verificar o
  mapeamento antes de codar; se divergir, normalizar numa função pura única).
- **Visual:** pequeno ícone `AutoFixHigh` (mesmo ícone da aba EFFECTS) com tint accent,
  sobreposto ao canto da capa (grid e lista), com `contentDescription` localizada
  ("Shader ativo" / "Shader active").
- Cache: `remember { mutableStateOf(emptySet<String>()) }` + `LaunchedEffect(Unit)` no
  nível da tela (não por card — leitura única).

**Arquivos:** `PerGameShaderStore.kt` (novo `enabledGameIds()` + teste), `LibraryScreen.kt`
(ou o pane dono dos cards), `LibraryGridCard.kt`, `LibraryListCard.kt`, `strings.xml` ×2.
**Aceite:** jogo com shader ativo mostra o badge; desativar o shader no QuickMenu e voltar
à biblioteca remove o badge (releitura na próxima composição da tela).

---

## M5 — Etiqueta "pesado" no catálogo (data-driven, sem curadoria)

**Problema:** presets caros (multi-pass, CRT-royale e família) não são distinguíveis no
browser; num Adreno, o usuário escolhe "às cegas" e culpa o app pela queda de FPS.

**Evidência:** `ShaderPreset.deps: List<String>` existe no modelo do catálogo
(`ShaderCatalog.kt:41`) — a informação para decidir já está no manifest, sem baixar nada.

**Design:**
- Função pura `isHeavyPreset(preset: ShaderPreset): Boolean = preset.deps.size >=
  HEAVY_DEPS_THRESHOLD` (constante documentada, ex.: 6 deps — revisar com dados reais do
  catálogo antes de fixar; o objetivo é pegar multi-pass conhecidos sem curar lista).
- `PresetRow`: quando `heavy`, ícone discreto (ex.: `Icons.Default.Bolt` ou "⚠" pequeno)
  + string de acessibilidade; opcionalmente sufixo "(pesado)" no subtítulo.
- Testes JVM com fixtures de preset (deps 5/6/7 — fronteira exata).

**Arquivos:** novo `ShaderPresetCost.kt` (+ teste), `ShaderBrowserOverlay.kt`,
`strings.xml` ×2.
**Aceite:** presets com deps ≥ threshold exibem a etiqueta; threshold documentado;
download/applicação inalterados.

---

## M6 — Parâmetros de preset (flagship — recomendado como spec próprio em fases)

**Problema:** muitos presets slang expõem parâmetros (escala, nitidez, saturação). Hoje o
app só sabe ligar/desligar/trocar — o usuário não pode ajustar NENHUM valor. É a maior
melhoria de UX possível para a integração com shaders, mas atravessa native/JNI/UI/persistência.

**Evidência:** `VulkanLibrashader.h/cpp` não expõe API de parâmetros (grep por
"parameter" vazio); o librashader upstream provê `libra_shader_preset_set_param`
(confirmar a API exata nos headers pinados no repo — diretório `librashader/`).

**Design em fases (cada fase é um aceite independente):**
- **Fase A — Native:** em `VulkanLibrashader.h/cpp`, expor contagem/nome/valor (e faixa
  min/max/step, se o API pinado fornecer): `getRetroArchShaderParameterCount()`,
  `getRetroArchShaderParameterName(i)`, `getRetroArchShaderParameterValue(i)`,
  `setRetroArchShaderParameter(name, value)`. Proteções: só quando há chain ativo; callbacks
  na render thread (padrão do projeto — "aplicação de parâmetros apenas na render thread",
  lição do ARMSX2 registrada em `docs/ARMSX2-librashader-vulkan.md`).
- **Fase B — Ponte Java:** `VulkanRenderer.getRetroArchShaderParameters(): List<ShaderParameter>`
  e `setRetroArchShaderParameter(name, value)` (modelo `ShaderParameter(name, min, max, step, value)`).
- **Fase C — UI (gamepad-first):** seção/diálogo "Parâmetros" acessível pela row do preset
  ativo na aba EFFECTS (gear como os settings de Touchscreen/Shooter, QuickMenu.kt:1073-1087);
  sliders com o padrão `LockableSliderRow` (A-lock, DPAD_L/R ajusta com repeat, B destrava,
  reset em blur — padrão dos diálogos existentes); slots de foco contíguos (G9) para a
  navegação por stick.
- **Fase D — Persistência:** `PerGameShaderConfig` ganha `params: Map<String, Float> =
  emptyMap()` (o `ignoreUnknownKeys = true` do store já garante decode de arquivos antigos);
  aplicar na carga do preset (boot + `applyPreset`), resetar ao trocar de preset,
  persistir em cada ajuste (com debounce).

**Aceite (por fase):** A — parâmetros listáveis/ajustáveis via log de debug; B — teste de
roundtrip JNI no device; C — ajuste completo por joystick sem toque (T6 do padrão de
auditoria); D — parâmetro sobrevive a fechar/reabrir o jogo e é resetado ao trocar de preset.
**Arquivos:** `VulkanLibrashader.h/cpp`, `VulkanRenderer.java`, novo
`ShaderParameterEditor` (ui/component), `ScreenEffectsPanel.kt`/`QuickMenu.kt`,
`PerGameShaderStore.kt` + teste, `strings.xml` ×2.

---

## Ordem recomendada e divisão em subagents

```
Baseline on-device (F1–F10 + self-heal) — usuário
        │
        ├─ Agent 1: M1 (paginação) + M2 (Downloading)      — ShaderBrowserOverlay.kt,
        │     ShaderToggleSubtitle.kt, ScreenEffectsPanel.kt, strings, testes puros
        ├─ Agent 2: M3 (favoritos) + M5 (heavy)            — ShaderFavorites.kt,
        │     ShaderPresetCost.kt, ShaderBrowserOverlay.kt, GamepadActionBar.kt, strings
        └─ Agent 3: M4 (badge biblioteca)                  — PerGameShaderStore.kt,
              LibraryScreen/Cards, strings
        │
        └─ M6 como spec próprio (4 fases, native primeiro) — depois do restante
```

- Sem overlap de arquivos entre Agent 1 e Agent 2 exceto `ShaderBrowserOverlay.kt` —
  ajustar: Agent 1 entrega primeiro (paginação + Downloading), Agent 2 roda depois e
  integra favoritos/heavy no arquivo já modificado. Agent 3 é independente.
- Cada agente segue: lógica pura + testes JVM → integração → `testModernDebugUnitTest
  --tests "*Shader*"` verde → commit referenciando esta spec.

## Fora de escopo

- Preview/thumbnails por screenshot (aplicação imediata no jogo real já é melhor que
  preview; complexidade alta sem ganho proporcional).
- A/B de presets por botão dedicado (recents + favoritos cobrem o fluxo de comparação).
- Parâmetros de presets embarcados do renderer nativo (toon/FXAA/CRT — já têm UI própria).
- Auto-aplicar "último shader usado" em jogos novos (decisão de produto, não UX técnica).
