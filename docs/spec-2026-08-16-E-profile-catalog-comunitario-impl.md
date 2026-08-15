# Impl doc — Spec 2026-08-16 E (catálogo comunitário de perfis — offline-first)

**Data:** 2026-08-16
**Spec:** `docs/spec-2026-08-16-E-profile-catalog-comunitario.md` (executor: agente autônomo)
**Base:** arquitetura-irmã do shader catalog (`tools/shaders/sync_slang_shaders.py` +
`ShaderBrowserOverlay`/`ShaderPagingLogic`) + fases F/D (schema do perfil congelado).
**Resultado:** implementado, gate completo verde, commit `feat(gamepad): …` (ver §6 do
master roadmap). Verificação on-device pendente (protocolo humano na §5).

## 1. O que foi feito (por seção do spec)

### §1.1 Tool de sync determinístico — `tools/profiles/sync_profile_repo.py` + `seed/`

- Fonte pinada em constante no topo (`SEED_COMMIT = "local-seed-v1"`, file:line 40) —
  v1 lê a pasta-semente `tools/profiles/seed/` (5 perfis de exemplo, criados a partir
  de perfis reais exportados; o repo comunitário externo pluga depois sem mudança de
  formato — README da seed documenta o schema da entry).
- Validação por entry (rejeita com AVISO, nunca aborta o sync): allowlist de campos do
  `GamepadProfile` (file:lines 53-69, espelho do Kotlin — campo desconhecido descarta a
  entry); enums (faceStyle/gyroMode/deadzone modes/response curves/layer trigger
  modes/direções de swipe — file:lines 70-84); tipos (bool/float finito/int);
  `touchpadSwipes` com `keyCode` int e `SWIPE_OPEN_RADIAL = -1000` como valor VÁLIDO
  (spec D); `layers` mapa camada→mapa botão→string. `iconKey`/`children` do radial não
  existem no GamepadProfile — a validação F/D é vacante aqui e o parser Kotlin
  normaliza no load (comentado no script).
- LUTs sanitizadas na SAÍDA (file:lines 106-112 — `sanitize_lut`: clamp 0..1, drop
  não-finitos, mínimo 2 pontos — espelho de `StickTransform.sanitizeLut`,
  `MIN_LUT_POINTS = 2`).
- Saída `app/src/main/assets/profile-catalog.json`: `{ generatedFrom, schemaVersion: 1,
  profiles: [...] }` ordenada por id, UTF-8, sem timestamps (file:lines 216-232) —
  determinístico (2× runs → bytes idênticos, md5 igual; gate do spec).

### §1.2 Parser puro — `gamepad/profiles/ProfileCatalog.kt` (NOVO)

- `CatalogEntry` (file:line 19): id, game (null = universal), faceStyle, controller,
  name, author, description, downloads, profile — `@Serializable`, `ignoreUnknownKeys`.
- `ProfileCatalog.parse(text): CatalogResult` (file:line 67): cada entry decodificada
  ISOLADA — inválida vira contagem (`invalidCount`) e nunca derruba o resto; texto
  ilegível = catálogo vazio, sem exceção (risco §6 herdado).
- `search(profiles, query)` (file:line 90): tokens case-insensitive sobre
  game/nome/controle/autor/descrição; TODOS os tokens precisam casar; blank = todos.
- `forGame(profiles, appId)` (file:line 110): casa EXATO case-insensitive com o appId;
  null → vazio; universal (game null) NÃO casa (aparece na lista geral/busca —
  decisão registrada).
- `summaryOf(profile)` (file:line 125): diff-resumo por categoria em ordem fixa
  (`ProfileSummaryCategory` file:line 41 — BINDINGS, GYRO, LAYERS, SWIPES, STICK,
  RUMBLE, TOUCHPAD). O spec lista bindings/gyro/camadas/swipes; STICK/RUMBLE/TOUCHPAD
  completam a cobertura dos campos reais do perfil (um perfil só de rumble não pode
  renderizar resumo vazio — desvio justificado abaixo).

### §1.3 Browser — `ui/component/ProfileCatalogBrowser.kt` (NOVO)

- JANELA própria (`Dialog`, file:line 65) aberta por cima do GamepadRemapDialog —
  dialogs usam `GamepadFocusScope` de VIEW (regra do repo: nunca navigator de bus em
  janela separada); o remap desliga seu próprio escopo de foco enquanto o browser está
  por cima (`catalogOpen` — GamepadRemapDialog.kt:640, 649, 1222-1228).
- Mesma ergonomia do ShaderBrowserOverlay: busca primeiro (`GamepadSearchField` —
  IME só abre com intenção explícita X/A, regra `GamepadNavigationClock`), paginação
  via `ShaderPagingLogic.decidePage` (puro, reuso; página = 8), A abre/apply, B
  hierárquico (detalhe → lista → fecha), `gamepadBackHandler` no fechamento.
- Ordenação com contexto: perfis `forGame(appId)` primeiro + chip "Este jogo"
  (file:lines 83-88, 265-275).
- Preview da entry (file:line 352): descrição + chips do `summaryOf` localizados +
  autor/jogo/controle/downloads.
- **Aplicar** = `hub.saveGameProfile(appId, entry.profile)` (file:lines 395-401) —
  override do JOGO ATUAL no gameStore; o merge 3-camadas (JOGO > GLOBAL > AUTO) faz o
  resto e o hub re-resolve NA HORA (saveGameProfile → invalidateProfiles, F3.2);
  NADA é escrito no escopo global. Sem jogo (appId null) o botão desabilita com hint
  (mesmo padrão do escopo "Este jogo" de B §1.4).
- Entrada no remap: botão "Importar do catálogo" no footer, `enabled = appId != null`
  (GamepadRemapDialog.kt:1171-1182); status de sucesso no dialog após aplicar
  (file:line 1225-1227).
- Catálogo carregado do ASSET (offline, nunca rede — file:lines 70-84); falha de
  leitura → tela de erro, nunca crash; entries inválidas contadas no rodapé.

### §1.4 Badge "personalizado" na Library

- `GamepadProfileStore.hasOverrides(key)` (file:line 44) + `overrideKeys()`
  (file:line 47): consulta leve do cache em memória (invalidado em save/clear — mesma
  garantia do hot path M1).
- `GamepadHub.profileOverrideGameIds()` (GamepadHub.kt:314) — main thread (contrato
  M1 do store).
- `ProfileOverrideBadge` (LibraryAppItem.kt:199) — mesmo padrão visual do
  `ShaderActiveBadge` (círculo 20 dp preto 0.5 + ícone SportsEsports 12 dp, tint
  accentCyan para diferenciar do shader); conteúdo localizado. Grid: empilhado sob o
  badge de shader (LibraryGridCard.kt:382-384); List: lado a lado em Row no canto do
  ícone (LibraryListCard.kt:148-160 — nunca sobrepostos).
- LibraryScreen: `profileOverrideIds` lido UMA vez por visita à tela
  (LibraryScreen.kt:240-243) → panes → cards (`hasProfileOverrides = item.appId in
  profileOverrideIds`); re-navegar recarrega — aplicar perfil em jogo reflete na
  próxima visita (mesma semântica do shaderEnabledIds).

## 2. Arquivos afetados

| Arquivo | Mudança |
|---|---|
| `tools/profiles/sync_profile_repo.py` + `seed/` (5 perfis + README) | NOVO (§1.1) |
| `app/src/main/assets/profile-catalog.json` | NOVO gerado (§1.1) |
| `gamepad/profiles/ProfileCatalog.kt` | NOVO puro (§1.2) |
| `ui/component/ProfileCatalogBrowser.kt` | NOVO (§1.3) |
| `gamepad/remap/GamepadRemapDialog.kt` | botão de entrada + gating (§1.3) |
| `gamepad/profiles/GamepadProfileStore.kt` + `GamepadHub.kt` | `hasOverrides`/`overrideKeys`/`profileOverrideGameIds` (§1.4) |
| `ui/screen/library/*` (AppItem, GridCard, ListCard, panes, screen) | badge (§1.4) |
| `res/values*/strings.xml` | 31 chaves EN+pt-rBR |
| `app/src/test/.../ProfileCatalogTest.kt` | NOVO — parse/robustez/search/forGame/summaryOf (§1.2) |

## 3. Testes

- `ProfileCatalogTest` (9 testes, 0 falhas): parse valida e conta inválidos sem
  exceção (entry não-objeto descartada com contagem; campo extra de perfil IGNORADO
  pelo parser — leniência do `ignoreUnknownKeys`, o sync tool é quem rejeita na
  geração); lixo nunca lança (string vazia, texto inválido, shape errada); perfil
  completo preservado (swipes com SWIPE_OPEN_RADIAL, camadas, triggers isShift, LUT);
  extras sobrevivem; busca por tokens case-insensitive sobre os 5 campos com
  interseção; forGame exato + universal não casa; summaryOf ordem fixa / vazio /
  parciais.
- Regressão vizinha (filtros do hotspot): `*GamepadProfileStore* *ProfileCatalog*
  *Touchpad*` → 55 testes, 0 falhas.
- Sync determinístico: 2× runs → md5 idêntico (`8a3c6de4…`), 5/5 perfis validados, 0
  descartados.

## 4. Desvios (com justificativa)

- **`summaryOf` cobre 7 categorias (spec lista 4)** — o spec exemplifica
  bindings/gyro/camadas/swipes, mas o GamepadProfile real tem stick (F1), rumble (U5)
  e touchpad (P2-6); sem as categorias extras, um perfil só de rumble renderizaria
  resumo vazio no preview. Ordem fixa + teste de ordem.
- **`forGame` não casa perfis universais (game null)** — "para este jogo" = alvo
  declarado; o universal aparece na lista geral/busca (o catálogo v1 tem 2 universais
  no seed). Decisão testada e documentada no KDoc.
- **Paginação por linha "Anterior/Próxima" em vez de L2/R2** — o ShaderBrowser usa
  L2/R2 (bus-level); este browser é uma JANELA (view-level) e as linhas focáveis são
  o padrão do repo para dialogs (previsível com stick, mesma ergonomia A/B).
- **Browser como janela própria, não overlay dentro do remap** — dois dialogs
  empilhados = um dono do input por janela (o remap desliga o próprio escopo via
  `catalogOpen`); um overlay dentro do Dialog do remap criaria duas superfícies de
  foco na mesma janela (contra a regra "uma superfície, um dono").

## 5. Verificação (gate)

```
python3 tools/profiles/sync_profile_repo.py && python3 tools/profiles/sync_profile_repo.py && git diff --exit-code -- app/src/main/assets/profile-catalog.json
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:testModernDebugUnitTest --tests "*ProfileCatalog*"
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:assembleModernDebug
```
→ sync 2× determinístico (bytes idênticos; diff vazio com o asset commitado —
verificado por md5 na primeira geração e pelo gate re-rodado no checkpoint);
ProfileCatalogTest 9/9; `assembleModernDebug` OK (dex do XServerScreen intocado —
ZERO linhas tocadas).

On-device (pendente — protocolo consolidado do fechamento 2026-08-16, §2 linha E):
browser offline abre pelo remap com foco por gamepad; aplicar perfil muda o jogo NA
HORA (re-resolve F3.2); badge aparece/some no card do jogo (log do store + badge no
card).

## 6. Invariantes respeitadas

- Lógica pura em `gamepad/profiles` sem android.* (parse/search/forGame/summaryOf) —
  JVM-testável; UI em arquivo próprio.
- Degradação byte-identical: catálogo é SÓ leitura + escrita de override por jogo
  (o caminho do perfil sem catálogo não muda); botão desabilitado sem jogo; parser
  leniente nunca crasha.
- Store: política V1 intacta (chaves desconhecidas preservadas — o apply grava via
  `save`, que reinjeta extras); `hasOverrides` serve do cache M1.
- `XServerScreen.kt`: ZERO linhas tocadas; strings EN + pt-rBR; commits em PT-BR
  referenciando o spec; nada embarca dinamicamente (asset gerado offline).
