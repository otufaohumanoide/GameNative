# Spec — libretro/slang-shaders sob demanda (substituição dos presets embarcados)

**Data:** 2026-08-11
**Status:** implementado e commitado (`f8b39e45`, tag `milestone-2026-08-11-slang-shaders-on-demand`); verificação on-device (§8.2) **pendente — sem dispositivo disponível** (loop adaptado para `files/retroarch_pack` em `tools/shader-test-loop/shader_test_loop.py`).
**Escopo:** apenas os shaders RetroArch (librashader). Os efeitos nativos do renderer (FSR, FXAA, Toon, Vivid, CRT/NTSC nativos, brilho/contraste/gama) e todo o resto do app **não são tocados**.

---

## 1. Problema e decisão central

O fork embarcava 131 presets em `assets/retroarch` (~2,4 MB no APK) importados em runtime pelo `ShaderImporter.java`. Suportar o catálogo completo do [libretro/slang-shaders](https://github.com/libretro/slang-shaders) — **2.541 presets em 35 famílias** — embarcado é inviável: inflaria o APK e obrigaria o usuário a carregar/rolar uma lista interminável.

**Decisão:** o APK não carrega nenhum arquivo de shader. Carrega apenas um **manifesto de metadados** (`catalog.json`, 604 KB) que torna o catálogo inteiro navegável instantaneamente, inclusive offline. Os arquivos reais vêm de um **único pack baixado sob demanda** (~53 MB, closure de dependências de todos os presets), uma única vez.

> Lente Jony Ive: *"decide what you're going to give up"*. Desistimos de shaders-offline-embarcados para ganhar um catálogo 19× maior com APK menor. O custo (um download único) é comunicado com honestidade — tamanho, progresso, retry — nunca escondido.

---

## 2. Estado atual do worktree (inventário)

### 2.1 Criado (novo sistema)

| Arquivo | Papel |
|---|---|
| `tools/shaders/sync_slang_shaders.py` | Gera `catalog.json` a partir do repo upstream (closure completa de dependências) |
| `app/src/main/assets/retroarch/catalog.json` | Manifesto único em assets (604 KB) |
| `app/src/main/java/app/gamenative/shaders/ShaderCatalog.kt` | Parse/query do manifesto (busca, famílias, subpastas, paginação — só metadados, sem filesystem) |
| `app/src/main/java/app/gamenative/shaders/ShaderPack.kt` | Download do tarball + extração filtrada por whitelist (`TarGz`, seguro contra path traversal) |
| `app/src/main/java/app/gamenative/shaders/ShaderConfigStore.kt` | Load/persist da config nos extras do container |
| `app/src/main/java/app/gamenative/shaders/ShaderRecents.kt` | Últimos 5 presets usados (SharedPreferences) |
| `app/src/main/java/app/gamenative/ui/component/ShaderSectionState.kt` | Estado compartilhado QuickMenu ⇄ browser; aplicar/alternar presets |
| `app/src/main/java/app/gamenative/ui/component/ShaderBrowserOverlay.kt` | Browser full-screen (busca → recentes → famílias → subpastas → presets, paginado) |
| `app/src/main/java/app/gamenative/ui/component/ShaderBrowserState.kt` | Estado de navegação persistente do browser (nível + busca + páginas + foco; §5.6) |
| `app/src/main/java/app/gamenative/ui/component/GamepadSearchField.kt` | Campo de busca com IME controlado (X abre, B fecha) |
| Testes: `ShaderCatalogTest.kt`, `TarGzTest.kt`, `ShaderBrowserNavTest.kt` | JVM, sem Android |

### 2.2 Modificado

- `QuickMenu.kt` — hoisteia `ShaderSectionState`; flag `shaderBrowserOpen` troca o conteúdo do menu pelo browser; preview de teclas L1/R1/L2/R2 e `BusGamepadKeyBridge` são gated enquanto o browser está aberto.
- `ScreenEffectsPanel.kt` — seção RetroArch vira: toggle principal + linha "Browse shaders"; efeitos nativos permanecem em seção colapsada separada.
- `XServerScreen.kt` — import de `loadShaderConfig` movido para `app.gamenative.shaders`.
- `strings.xml` (en + pt-rBR) — strings `shader_*`.

### 2.3 Removido (legado)

- `assets/retroarch/**` (131 presets, ~2,4 MB) — resta apenas `catalog.json`.
- `app/src/main/java/com/winlator/renderer/ShaderImporter.java`.
- `app/src/main/java/app/gamenative/ui/component/dialog/RetroArchShaderDialog.kt`.

---

## 3. Catálogo (`catalog.json`)

### 3.1 Geração

`python3 tools/shaders/sync_slang_shaders.py [--ref master] [--out PATH] [--fresh]`

Para cada `.slangp` do repo (exceto `test`, `spec`, `.gitlab-ci.yml`), o script resolve a **closure completa** de dependências:

- `shaderN = "<path>"` → passes `.slang`
- `#include "<path>"` em `.slang` → headers `.h/.inc`, recursivo (headers incluem headers)
- `textures = "A;B"` + `A = "*.png"` → LUTs/imagens
- `#reference "<path>"` → outros presets/configs, recursivo, cycle-safe

Caminhos são resolvidos relativos ao arquivo que referencia e devem permanecer dentro da raiz do repo. Preset com referência não resolvida **não é descartado silenciosamente**: recebe `"broken": true` e aparece na UI como indisponível.

### 3.2 Schema

```json
{
  "source": { "repo": "libretro/slang-shaders", "ref": "master",
              "commit": "a7f04a0698...", "generated": "ISO-8601",
              "packBytes": 53113982 },
  "families": [ { "name": "crt", "count": 131 }, ... ],
  "files": [ "<união de todos os arquivos que qualquer preset precisa>" ],
  "presets": [ { "path": "crt/easymode.slangp", "family": "crt",
                 "subfolder": null, "passes": 8, "bytes": 12345,
                 "broken": false } ]
}
```

Valores atuais: **2.541 presets · 35 famílias · 1.915 arquivos no pack · 7 presets broken · 53,1 MB**.

Notas:

- `files` é a whitelist exata de extração: nada fora dela é escrito em disco (READMEs, screenshots, LICENSE do repo upstream ficam de fora).
- `bytes` por preset é o tamanho da **sua** closure (dependências compartilhadas aparecem somadas em vários presets; `packBytes` é o custo real único). A UI exibe `bytes` como indicação de peso do efeito, não como custo de download.
- O layout do pack preserva os caminhos relativos à raiz do repo, então a resolução de `shaderN`/`#include`/`#reference`/texturas do librashader (inclusive cross-folder `../../crt/shaders/...`) funciona **sem nenhuma mudança**.

### 3.3 Cadência de re-sync

- Rodar o script antes de cada release ou mensalmente (o que vier primeiro); commitar `catalog.json` com a mensagem do novo commit upstream.
- O catálogo é **imutável dentro de uma versão do app**: o usuário nunca vê o catálogo mudar sob seus pés entre aberturas.
- `tools/shaders/cache/` é cache local do tarball (não commitar).

---

## 4. Pack sob demanda (`ShaderPack`)

### 4.1 Fluxo

```
não instalado ──(intenção do usuário)──► pré-checks ──► download (progresso %)
     ▲                                                        │
     │                                                        ▼
excluir pack  ◄──(confirmar)── instalado ◄── rename atômico ◄── extração filtrada
```

- **Download:** `https://codeload.github.com/libretro/slang-shaders/tar.gz/...`, OkHttp, timeouts 30s/60s, progresso por bytes.
- **Extração:** `TarGz.extract` grava somente arquivos na whitelist `catalog.files`; rejeita path traversal (caminhos absolutos, `..`, escape da raiz).
- **Atomicidade:** extrai para `filesDir/retroarch_pack.tmp`, só troca para `filesDir/retroarch_pack` após extração completa; falha limpa o tmp.
- **Marker:** `retroarch_pack/.complete` guarda o commit do catálogo — base para verificação de integridade (§4.3).
- A instalação roda em `Dispatchers.IO`; a UI continua navegável durante o download.

### 4.2 Correções obrigatórias (gaps do worktree)

1. **Download pelo commit pinado, não pelo branch.** Hoje `tarballUrl` usa `refs/heads/master`, mas o catálogo pinou `a7f04a0698`. Se o upstream andar, arquivos novos/renomeados quebram a whitelist. URL correta:
   `https://codeload.github.com/libretro/slang-shaders/tar.gz/<catalog.source.commit>`
2. **Pré-check de espaço:** `StatFs(filesDir)` — exigir `packBytes × 2 + 16 MB` (tmp + final) antes de começar; erro claro se faltar.
3. **Pré-check de rede limitada:** `ConnectivityManager.isActiveNetworkMetered` → diálogo de confirmação com o tamanho (`Download shader pack (53,1 MB)?`) antes de iniciar em dados móveis. Wi-Fi inicia direto.
4. **Cancelamento:** reter o `Call` do OkHttp; linha de progresso ganha ação de cancelamento que aborta a chamada e apaga `tmpDir`.
5. **Exclusão do pack:** quando instalado, a Home mostra linha de estado ("Shader pack instalado · 53,1 MB") que abre confirmação e chama `pack.clear()`. A exclusão **não** desliga o shader ativo na config (o preset simplesmente deixa de carregar — fallback já existente do renderer), e a UI volta ao estado "não instalado".

### 4.3 Integridade e atualização

- Ao carregar, comparar o commit de `.complete` com `catalog.source.commit`. Divergência (usuário restaurou dados, catálogo novo no APK) ⇒ tratar como não instalado.
- Quando um APK novo traz catálogo com commit diferente do pack instalado: a Home exibe "Atualizar shader pack (X MB)" (mesmo fluxo de instalação; `tmp → rename` substitui o pack inteiro sem janela quebrada — o pack antigo segue funcionando até a troca).
- Sem atualização automática em segundo plano: download só por intenção explícita.

### 4.4 Permissão e resiliência

- `INTERNET` já declarado (`AndroidManifest.xml:17`) — nenhuma permissão nova.
- Falha de rede nunca corrompe estado: ou o pack completo existe (marker) ou não existe.
- Erro de HTTP/timeout → estado `installFailed` com linha de retry.

---

## 5. Interface — o menu que não causa marasmo

### 5.1 Princípios

1. **Nunca renderizar a lista inteira.** Nenhum nível mostra mais que 12 itens de cada vez; paginação "Show more (N more)".
2. **Busca primeiro.** O campo de busca é a primeira linha focável da Home — quem sabe o que quer chega em 3 teclas; quem não sabe navega pelas famílias curadas.
3. **Hierarquia rasa e previsível:** Home → Família → (Subpasta, só quando >1) → Preset. Back-stack pura (`ShaderBrowserNav`), B/Back desfaz em ordem: limpa busca → desempilha → fecha.
4. **Estados visíveis e honestos:** preset não baixado mostra ícone de nuvem (não fica escondido); preset quebrado upstream mostra nuvem cortada e fica desabilitado; o único CTA de download mostra o tamanho real.
5. **Uma decisão de download, não 2.541.** Selecionar qualquer preset em nuvem dispara a instalação do pack inteiro (é o custo único) e, ao concluir, **aplica automaticamente o preset pedido** — a intenção do usuário é completada sem segundo toque.

### 5.2 Estrutura das telas

**Home (sem busca ativa):**
1. Campo de busca (slot de foco 0)
2. `DownloadCta` — ausente quando instalado; em download mostra %/progresso; em falha mostra retry
3. **Recently used** — últimos 5 (`ShaderRecents`), só presets válidos
4. **Browse shaders** — famílias em ordem curada com contagem ("131 presets")

**Home (com busca):** resultados globais paginados (nome amigável, família, path).

**Família:** `DownloadCta` → subpastas (se >1) → presets paginados.

**Linha de preset:** título amigável (`crt/easymode.slangp` → "Easymode"), subtítulo `Família · N passes · tamanho`, ícone à esquerda (nuvem/nuvem cortada), check à direita quando ativo. Selecionar o preset já ativo desliga só aquele preset (o sistema permanece ligado; o toggle principal é o único on/off).

### 5.3 Caso bezel (obrigatório antes de congelar o catálogo)

`bezel` responde por **1.490 dos 2.541 presets (59%)** e 440 dos 1.915 arquivos do pack. São molduras de tela (PNG), não efeitos de imagem — a maioria dos usuários deste app (jogos PC/Steam via Wine) nunca os usará. Hoje `bezel` é o 7º na `FAMILY_ORDER`. Decisão especificada:

- Mover `bezel` para o **fim** da lista de famílias na Home (sem mudar o catálogo nem o pack).
- Opcional (fase 2, se o tamanho do pack pesar): flag `--exclude bezel` no script de sync, removendo a família do catálogo e do pack; presets que só dependem de bezel saem, e `packBytes` cai substancialmente. Não fazer na fase 1 — manter catálogo completo primeiro.

### 5.4 Gamepad e foco

- O browser instala **seu próprio escopo** de gamepad (`BusJoystickFocusNavigator` + `BusGamepadKeyBridge` com `ModeKeyBehavior.CloseOverlay`); os do QuickMenu são gated enquanto aberto — sem disputa de foco.
- B/Back: navega o back-stack; PS fecha o browser (não o menu); L1/R1/L2/R2 do menu ficam inativos sob o browser.
- Protocolo de foco existente do app: `gamepadFocusIndex` + remember-selection por tela (`focusIndices[screenKey]`) + `FocusRequester` com retry; voltar a uma tela restaura a linha focada anterior.
- IME da busca abre só com X (nunca ao navegar), fecha com B — mesmo padrão do QuickMenu (spec 2026-08-10).

### 5.5 Ponto de entrada

`QuickMenu → aba Effects`: linha **RetroArch Shaders** (toggle) e, quando ligado, linha **Browse shaders** que abre o overlay full-screen. Efeitos nativos do renderer ficam abaixo, em seção colapsada própria — RetroArch shaders e efeitos nativos não se misturam na superfície.

### 5.6 Navegação persistente (cache do nível)

Depois que o usuário ativa um shader e volta ao QuickMenu, **reabrir o browser restaura o
mesmo nível** em que o shader foi escolhido — não reseta para Home:

- O estado de navegação (`ShaderBrowserNav` + busca + páginas + linha focada por tela)
  vive em `ShaderSectionState.browser` (hoisted no QuickMenu, que permanece composto com o
  painel fechado), não no overlay — fechar o browser não destrói o nível.
- Fechar o browser com download em andamento **não mata a instalação**: o estado de
  instalação (progresso, falha, metered, preset pendente) também é hoisted; o pack termina,
  o status atualiza e o preset pedido é aplicado automaticamente mesmo com o browser fechado.
- Restaurar foco: ao reabrir, o foco volta para a linha lembrada da tela restaurada
  (fallback para a primeira linha se a linha lembrada não existir mais).


---

## 6. Migração de configs do sistema antigo

Configs antigas persistem nos extras do container (`ShaderConfigStore.kt`): `retroArchShaderEnabled`, `retroArchShaderPresetPath` (absoluto, ex. `.../retroarch_presets/crt/...` — diretório que o `ShaderImporter` materializava e que **não existe mais**), `retroArchShaderPresetName`, `retroArchShaderRelativePath` (ex. `crt/easymode.slangp`).

Regra de load (`XServerScreen.kt:651` e `ShaderSectionState.init`):

1. `presetPath` existe no disco → carrega normalmente (caminho novo, nada a fazer).
2. `presetPath` não existe **e** `relativePath` resolve dentro do pack instalado → re-resolve `packDir/relativePath`, atualiza o absoluto persistido, carrega.
3. `relativePath` não resolve (pack não instalado) → mantém `enabled=true` e a seleção visível no menu, **não carrega nada** e **não baixa nada automaticamente** (nenhum uso de rede sem intenção). O renderer simplesmente roda sem preset; ao abrir o browser, o CTA de download está lá.
4. Config antiga sem `relativePath` (dialog antigo gravava só caminho absoluto) → tratar como (3) e limpar o path; o usuário reescolhe o preset.

Limpeza adicional (fase de remoção): apagar `filesDir/retroarch` e `filesDir/retroarch_presets` do sistema antigo em uma migração única no boot (best-effort, libera ~2,4 MB do usuário).

---

## 7. Renderer — invariante (zero mudança)

- `VulkanRenderer.loadRetroArchShaderPreset(String path)` (`VulkanRenderer.java:899`), `setRetroArchShaderEnabled` (:872), `clearRetroArchShaderPreset` (:922) recebem caminho absoluto e não sabem a origem do arquivo — pack novo é transparente.
- `VulkanLibrashader.cpp` intacto; os padrões ARMSX2 permanecem: parâmetros aplicados só na render thread, falha de chain → fallback + latch, create-first swap (chain nova criada antes de liberar a antiga).
- Consequência útil: preset que falhar ao compilar no pack novo degrada para o estado anterior, nunca crasha (já verificado on-device no milestone 2026-08-08).

---

## 8. Verificação

### 8.1 Testes automatizados (JVM)

Existentes: `ShaderCatalogTest` (parse/busca/paginação), `TarGzTest` (whitelist + traversal), `ShaderBrowserNavTest` (back-stack).

Adicionar:

- Re-resolução de `relativePath` (casos 1–4 da §6) como função pura testável.
- `ShaderPack`: decisão de espaço insuficiente / rede limitada (extrair pré-checks para funções puras).

Rodar: `./gradlew :app:testDebugUnitTest --tests "*Shader*" --tests "*TarGz*"`.

### 8.2 On-device (Xiaomi Mi 11 / Adreno 650, referência dos milestones)

- `tools/shader-test-loop/shader_test_loop.py` (`setprop debug.gamenative.preset` + screencap + logcat) contra presets-chave do pack: `crt/easymode.slangp`, `film/technicolor.slangp`, `ntsc/*`, um HDR (`hdr/crt-sony-megatron-*`) — delta-vs-baseline como no milestone 2026-08-08.
- Fluxo de download: primeira instalação (Wi-Fi), progresso visível, preset em nuvem → aplica sozinho ao concluir.
- Falha simulada (modo avião durante download) → retry; pack íntegro após sucesso.
- Exclusão do pack → UI volta ao CTA; preset ativo deixa de carregar sem crash.
- Rede limitada → diálogo de confirmação aparece.
- Migrado do APK anterior: shader antigo volta a funcionar após download do pack (caso §6.2).
- Gamepad: T1–T6 do padrão de auditoria (`docs/quickmenu-joystick-audit-2026-08-11.md`) no browser: foco nunca perdido, B navega, PS fecha browser não menu.
- APK final: tamanho deve **cair** ~2 MB vs. baseline com presets embarcados.

---

## 9. Riscos e mitigação

| Risco | Mitigação |
|---|---|
| `codeload.github.com` indisponível / rate limit | Baixar por commit pinado (§4.2.1); fase 2: URL secundária (asset de Release no repo próprio ou mirror) com fallback em cascata no `ShaderPack` |
| Upstream renomeia arquivos entre re-syncs | Catálogo pinado por commit; re-sync só com verificação dos warnings do script (presets broken nunca silenciosos) |
| 53 MB em dados móveis | Confirmação explícita com tamanho (§4.2.3) |
| Catálogo de 2.541 itens | Nunca listado inteiro; paginação + busca + bezel no fim (§5) |
| Falha no meio do download | tmp + rename atômico; estado binário (marker); retry |
| Presets quebrados upstream (7 hoje) | Marcados `broken`, visíveis e desabilitados — honestidade em vez de omissão |

## 10. Rollback

A mudança inteira é substituível por revert do(s) commit(s): presets embarcados voltam ao APK (+2,4 MB), `ShaderImporter`/`RetroArchShaderDialog` voltam, shaders offline novamente. Nenhuma dependência nova é introduzida (OkHttp já é usado no app; `kotlinx-serialization` já presente).

---

## 11. Checklist de execução (ordem)

1. `ShaderPack`: URL por commit pinado + pré-checks (espaço/metered) + cancelamento.
2. UI: linha de estado/exclusão do pack na Home; auto-aplicar preset pedido após install.
3. UI: mover `bezel` para o fim da `FAMILY_ORDER`.
4. Migração: re-resolução de `relativePath` em `ShaderSectionState`/`XServerScreen` + limpeza dos diretórios antigos.
5. Testes novos (§8.1) verdes + `./gradlew :app:assembleDebug`.
6. Verificação on-device (§8.2).
7. Commitar worktree completo (assets deletados, legado removido, sistema novo) + atualização do `README.md` (131 presets embarcados → catálogo sob demanda) e `docs/MILESTONES.md`.
8. Navegação persistente (§5.6): estado do browser hoisted em `ShaderSectionState` — reabrir volta ao nível do shader escolhido; instalação não morre ao fechar o browser; re-pick de preset migrado (§6.3) carrega em vez de limpar.
