# Spec 2026-08-16 E — Catálogo comunitário de perfis (offline-first, padrão do shader catalog)

**Data:** 2026-08-16
**Origem:** roadmap UX (Steam Input community configs / RetroArch autoconfig).
O fork JÁ tem a arquitetura-irmã pronta: `catalog.json` de shaders gerado por
`tools/shaders/sync_slang_shaders.py` (determinístico, commit pinado, nada
embarca dinamicamente) + browser (`ShaderBrowserOverlay` com `ShaderPagingLogic`
puro). Este spec replica o padrão para PERFIS de controle.
**Executor:** sub-agente autônomo. Leia `AGENTS.md` + master roadmap §2.
**DEPENDÊNCIA: ÚLTIMA fase** — congela o schema do perfil DEPOIS de F e D.
Spec autocontido.

## 0. Estado atual

- Perfis: `GamepadProfile` JSON (schemaVersion, campos null-default, V1 preserva
  chaves), store por device (global) e por jogo (appId), export/import SAF no
  `GamepadRemapDialog` (F3.3).
- Shader catalog: `assets/catalog.json` + `tools/shaders/sync_slang_shaders.py`
  (determinístico: sort estável, sem timestamps) + navegação offline no
  `ShaderBrowserOverlay` (paging/favoritos com lógica pura + fakes em teste).
- Library: `ShaderActiveBadge` nos cards (`LibraryGridCard.kt:379`,
  `LibraryListCard.kt:143`) — padrão de badge por jogo.

## 1. Design

### 1.1 Tool de sync (determinístico)

Novo `tools/profiles/sync_profile_repo.py` (padrão do sync de shaders):
- Fonte: repo Git de perfis comunitários, **commit pinado** (constante no topo,
  atualizar à mão quando o upstream mudar);
- Lê perfis (JSON soltos ou pasta por jogo), VALIDA cada um: allowlist de campos
  do `GamepadProfile` (rejeita desconhecidos com aviso, não aborta o sync),
  LUTs sanitizadas (clamp 0..1, drop não-finitos — espelho do
  `withSanitizedLuts`), bindings/ícones/macros dentro das allowlists das fases
  F/D (iconKey ∈ allowlist; direções de swipe ∈ enum; sem children >1 nível);
- Saída `app/src/main/assets/profile-catalog.json`:
  `{ "generatedFrom": "<commit>", "schemaVersion": 1, "profiles": [ { "id",
  "game", "faceStyle"?, "controller"?, "name", "author", "description",
  "downloads"?, "profile": {GamepadProfile JSON} } ] }` — ordenação estável
  (por id), UTF-8, sem timestamps → determinístico.
- Verificação de determinismo no gate: rodar 2× e diff vazio.
- v1 do repo-fonte: o fork mantém uma pasta-semente `tools/profiles/seed/`
  com 3–5 perfis de exemplo (criados a partir de perfis reais exportados),
  e o script lê dela por enquanto (o repo comunitário externo plugará depois
  sem mudança de formato).

### 1.2 Parser puro (JVM-testável)

Novo `gamepad/profiles/ProfileCatalog.kt`:
```kotlin
object ProfileCatalog {
    fun parse(text: String): CatalogResult   // erros por perfil NÃO derrubam o resto
    fun search(profiles: List<CatalogEntry>, query: String): List<CatalogEntry>  // por game/nome/controle
    fun forGame(profiles: List<CatalogEntry>, appId: String?): List<CatalogEntry>
}
```
`ignoreUnknownKeys`, entry inválida → descartada com contagem (nunca crash no
boot do jogo — risco §6 herdado).

### 1.3 Browser (reuso dos padrões do ShaderBrowser)

Novo `ui/component/ProfileCatalogBrowser.kt` (overlay/dialog, arquivo próprio —
mesma ergonomia do `ShaderBrowserOverlay`: busca, paginação, foco por gamepad
via `gamepadSelectable`, A aplica, B volta; IME do campo só abre em X/A — regra
`GamepadNavigationClock` do repo):
- Entrada: botão "Importar do catálogo" no `GamepadRemapDialog` (contexto =
  device ativo + jogo atual);
- Preview da entry: descrição + diff-resumo vs perfil atual (quais categorias
  o perfil toca: bindings/gyro/camadas/swipes — função pura `summaryOf(profile)`);
- **Aplicar** = gravar override do JOGO atual no `GamepadProfileStore` (appId do
  container ativo — o merge 3-camadas faz o resto; nada é escrito no global).

### 1.4 Badge "personalizado" na Library

- `GamepadProfileStore.hasOverrides(appId): Boolean` (consulta leve, cache em
  memória invalidada no save);
- `LibraryGridCard`/`LibraryListCard`: badge pequena (padrão `ShaderActiveBadge`)
  quando true; some quando os overrides são removidos.

## 2. Arquivos afetados

| Arquivo | Mudança |
|---|---|
| `tools/profiles/sync_profile_repo.py` + `seed/` | NOVO (1.1) |
| `app/src/main/assets/profile-catalog.json` | NOVO gerado (1.1) |
| `gamepad/profiles/ProfileCatalog.kt` | NOVO puro (1.2) |
| `ui/component/ProfileCatalogBrowser.kt` | NOVO (1.3) |
| `gamepad/remap/GamepadRemapDialog.kt` | botão de entrada (1.3) |
| `gamepad/profiles/GamepadProfileStore.kt` | `hasOverrides` (1.4) |
| `ui/screen/library/components/LibraryGridCard.kt`, `LibraryListCard.kt` | badge (1.4) |
| `res/values*/strings.xml` | chaves |
| `app/src/test/.../ProfileCatalogTest.kt` | NOVO — parse/robustez/search/forGame/summaryOf com fixture |

## 3. Verificação (gate)

```
python3 tools/profiles/sync_profile_repo.py && python3 tools/profiles/sync_profile_repo.py && git diff --exit-code -- app/src/main/assets/profile-catalog.json
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:testModernDebugUnitTest --tests "*ProfileCatalog*"
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:assembleModernDebug
```
On-device (humano, "on-device pendente"): browser abre pelo remap com foco por
gamepad; aplicar um perfil do catálogo muda o jogo NA HORA (F3.2 re-resolve);
badge aparece/some no card do jogo; catálogo funciona 100% offline.

Consolidado (fechamento 2026-08-16, §2 linha E — protocolo único do roadmap):
browser offline; aplicar perfil muda o jogo na hora; badge na Library —
evidência: log do store + badge no card. **Status: on-device pendente.**

## 4. Fora de escopo

Download de perfis por URL no app (offline-first — o catálogo via APK), contas/
login, upload direto do app p/ o repo comunitário (export SAF já cobre o
compartilhamento manual), favoritos de perfis (follow-up, padrão
`ShaderFavorites`), versionamento de perfil no catálogo (v2).
