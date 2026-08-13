# Spec 2026-08-13 — Recuperação e feedback para shaders pesados demais (design)

**Data:** 2026-08-13
**Origem:** pedido do usuário — "quando o jogo trava por causa de um shader pesado demais
para o aparelho, o iniciante toma um susto e não quer mais usar o app; o usuário precisa
estar no controle, dentro de um sistema de feedback que informe — implícita ou
explicitamente — que algo deu errado". Este documento detalha 4 camadas de proteção
ordenadas por custo/valor (M1–M4), com design, arquivos, evidências (file:line) e aceite
de cada uma. **Nenhum código é alterado até a revisão deste spec** (workflow do repo:
spec → revisão → implementação).
**Padrões obrigatórios (repo):** lógica pura JVM-testável (`ShaderWatchdogLogic`,
`ShaderCrashGuard`); strings EN + pt-rBR; zero regressão no jogo (input do guest intocado);
a regra nativa intocável: **todo acesso ao chain vivo acontece na render thread** (lição
do ARMSX2 — `docs/ARMSX2-librashader-vulkan.md`).

---

## Contexto — o mecanismo exato do "voo do Ícaro"

O congelamento não é acidente: a aplicação de um preset roda **inteira na thread da UI**:

1. `ShaderSectionState.applyPreset` (`ShaderSectionState.kt:183-201`) é chamada de handlers
   do Compose e roda em `installScope = CoroutineScope(SupervisorJob() +
   Dispatchers.Main.immediate)` (`:58`);
2. ela chama `renderer.loadRetroArchShaderPreset(path)` (`:200`) → `VulkanRenderer.java:899-914`
   → `nativeLoadLibrashaderPreset` **sincronamente** dentro de `synchronized(lock)`;
3. `VulkanLibrashader.reloadPreset` (`VulkanLibrashader.cpp:52-120+`) constrói o chain novo
   INTEIRO na thread chamadora (preset_ctx_create → preset_create_with_options →
   `vk_filter_chain_create` = criação real de pipelines Vulkan — segundos a dezenas de
   segundos em Adreno para presets de 30-50 passes);
4. thread da UI congelada = app travado ("não está respondendo") = usuário força-fecha.

E o agravante que transforma susto em abandono — **a armadilha do reinício**: o estado é
persistido logo após o load (`persistShaderState(true, path, ...)`, `ShaderSectionState.kt:203`,
`PerGameShaderStore`), então ao reabrir o jogo o shader pesado é aplicado de novo e o
usuário renasce **dentro da geleia**, sem nunca saber o porquê.

O que o fork já tem de proteção (não duplicar): latch/create-first que degrada build
quebrado para o frame sem shader (`VulkanLibrashader.cpp:62-120`), o classificador
data-driven `ShaderPresetCost.isHeavyPreset` (passes ≥ 10 **e** deps ≥ 12,
`ShaderPresetCost.kt:23-32` — calibrado contra o manifest real, 2 541 presets), e o
feedback de download (`installing`/`Downloading… %`, `ShaderSectionState.kt:59-78`).
O que falta: o badge "Heavy" é **passivo** (não avisa, não impede), não há estado
"Applying…" (a UI não consegue nem desenhar — está bloqueada), o latch só cobre falha de
**build** (não "compilou mas derrete a GPU"), e nada conta a história depois da queda.

---

## M1 — O portão: confirmação antes de aplicar preset pesado

**Problema:** um toque em "Mega Bezel" aplica um preset de 30-50 passes direto, sem aviso
— o acidente é garantido por design. O metadado para prever o risco já existe
(`ShaderPresetCost`), mas é usado só para o badge visual.

**Evidência:** `ShaderPresetCost.kt:23-32`; `ShaderSectionState.applyPreset:183-201`;
padrão de diálogo de confirmação já existe no repo (`MessageDialog`, usado no confirm de
rede medida em `ShaderBrowserOverlay.kt:901-917`, estado `meteredConfirm` +
`pendingPreset` em `ShaderSectionState.kt`).

**Design:**
- `ShaderSectionState` ganha o par estado-ação, espelhando o padrão do metered:
  - `var heavyConfirmPreset by mutableStateOf<ShaderPreset?>(null)`
  - `fun requestApply(preset)` — se `ShaderPresetCost.isHeavyPreset(preset)`:
    `heavyConfirmPreset = preset`; senão: aplica direto (caminho atual, zero custo
    para os ~84% leves).
  - `fun confirmHeavyApply()` / `fun cancelHeavyApply()`.
- **Os dois caminhos de aplicação passam pelo portão**: o clique direto em row
  (browser + aba de efeitos) e o fim do download (`startInstall` →
  `applyPreset(requested)` em `ShaderSectionState.kt:94` — para pesados, o fim do
  download abre o diálogo em vez de aplicar).
- **Um único diálogo cobre as duas superfícies**: o browser substitui o conteúdo do menu
  (`shaderBrowserOpen`, `QuickMenu.kt:688-697`) e a aba de efeitos compõe dentro do mesmo
  Box — renderizar `MessageDialog` na raiz do QuickMenu, fora dos dois branches, quando
  `heavyConfirmPreset != null`. Título: "Shader pesado"; corpo: "Este shader usa %1$d
  passes e pode travar o jogo neste aparelho. Aplicar mesmo assim?"; botões
  "Cancelar" (destacado) / "Aplicar".
- Foco de gamepad: `MessageDialog` + `GamepadFocusScope` (padrão de diálogos do repo);
  o guardião do menu não disputa o foco com o diálogo (diálogo compõe por cima, como o
  confirm de rede medida).

**Arquivos:** `ShaderSectionState.kt`, `QuickMenu.kt` (render do diálogo na raiz),
`res/values/strings.xml` + `values-pt-rBR/strings.xml`.
**Aceite:** aplicar preset leve (fxaa) não mostra diálogo; aplicar pesado (crt-royale)
mostra; Cancelar não altera nada; Aplicar segue o fluxo atual; gamepad navega o diálogo
(A confirma, B cancela).

---

## M2 — A história pós-queda: nunca reiniciar dentro da geleia

**Problema:** quando o app morre congelado, o estado persistido diz "shader habilitado" e
o próximo launch reaplica o preset e trava de novo — o iniciante conclui "o app quebrou
para sempre" e desiste. Se o app não pode sobreviver à queda, ele precisa ao menos
**explicá-la e desarmar a bomba**.

**Evidência:** `persistShaderState(true, ...)` imediatamente após o load
(`ShaderSectionState.kt:203`); `PerGameShaderStore` (JSON com `ignoreUnknownKeys`,
`PerGameShaderStore.kt:34-94` — padrão de store com arquivo que pode ser reusado).

**Design:**
- Novo objeto puro JVM-testável **`ShaderCrashGuard`** (padrão `ShaderFavorites`/store):
  - estado persistido por-jogo (arquivo próprio, ex. `shaders/crash_guard.json`):
    `presetPath`, `presetName`, `armedAt` (epoch ms);
  - `arm(preset)` — chamado em `applyPreset` **somente para presets pesados**, antes do
    load; `disarm()` — chamado quando o shader sobreviveu; `armedFresh(now, maxAgeMs)`
    e o parse/latch para teste (fake de clock).
- **Desarmar quando saudável** (a parte "o shader sobreviveu"): em `ShaderSectionState`,
  um relógio periódico (`LaunchedEffect`/loop de 5 s no `installScope`): se `armed()` e
  `renderer.lastFrameTimeNs < HEAVY_HEALTHY_FRAME_NS` por **duas amostras consecutivas**
  → `disarm()` + persistir. (O getter de tempo de frame nasce no M3 — se o M2 for
  implementado antes, desarmar apenas no `onDispose` limpo do container/close do jogo;
  ver nota de dependência no M3.)
- **No próximo launch** (init do `ShaderSectionState`, antes de qualquer apply):
  se `armedFresh(now, 24h)` → **auto-desarmar a bomba**: limpar o preset do renderer
  (`clearRetroArchShaderPreset`, `VulkanRenderer.java:922-929`), persistir desabilitado,
  e expor `crashRecovered = Pair(name, path)` para a UI mostrar um diálogo único:
  "O shader %s travou o jogo na última execução e foi desativado." com botões
  "Reaplicar" (re-arma e aplica, assumindo o risco) / "OK". O usuário renasce numa
  tela que **funciona e explica** — controle total, susto convertido em narrativa.
- Diálogo renderizado na raiz do QuickMenu (mesmo mecanismo do M1), exibido uma única
  vez por sessão de jogo.

**Arquivos:** `shaders/ShaderCrashGuard.kt` (novo), `ShaderSectionState.kt`,
`QuickMenu.kt`, strings EN/pt-rBR, testes `ShaderCrashGuardTest`.
**Aceite:** aplicar pesado arma o guard; fechar o app antes do desarme → próximo launch
abre com shader desabilitado + diálogo explicativo; Reaplicar aplica de novo; preset leve
nunca arma; flag antiga (> 24 h) é ignorada.

---

## M3 — O guardião: watchdog de tempo de frame com auto-reversão

**Problema:** um preset que **compila** mas roda a 3 FPS não dispara o latch (que só cobre
falha de build) — o jogo vira slideshow e o usuário precisa descobrir sozinho o caminho de
volta. O app pode detectar e reverter sozinho, avisando.

**Evidência:** `reloadPreset` só falha em build (`VulkanLibrashader.cpp:99-113`); o
renderer não expõe tempo de frame (nenhum `lastFrameTime` em `VulkanRenderer.java`);
o latch garante que **reverter para frame sem shader é sempre seguro**.

**Design:**
- **Frame time no renderer** (dependência compartilhada com o M2): `volatile long
  lastFrameTimeNs` atualizado a cada frame apresentado na render thread (uma escrita
  barata; zero efeito mensurável). Getter público `getLastFrameTimeNs()`.
- **Lógica pura JVM-testável `ShaderWatchdogLogic`** (objeto, padrão
  `GamepadStickLogic`/`ShaderPagingLogic`):
  - entradas: amostras `(nowNs, frameTimeNs)`, preset aplicado em `appliedAtNs`;
  - decisão: depois de `WARMUP_MS = 5000` desde o apply, média das amostras da janela de
    `WINDOW_MS = 30000`; se média > `REVERT_FRAME_NS = 250_000_000` (4 FPS) e houve ≥
    `MIN_SAMPLES = 8` → `Revert`; senão `Keep`; amostras vazias/estagnadas (GPU travada
    de verdade) → `Keep` (não decidir sem dado — o M2 cobre a morte dura).
  - constantes revisáveis contra o aparelho de referência (Mi 11 / Adreno 650).
- **Runner**: em `ShaderSectionState`, após aplicar um preset **pesado**, um loop no
  `installScope` amostra `renderer.lastFrameTimeNs` a cada 2 s; `ShaderWatchdogLogic`
  decide; em `Revert` → `clearRetroArchShaderPreset()` + persistir desabilitado +
  `watchdogReverted = preset.name` → o QuickMenu mostra `MessageDialog`:
  "O shader %s foi desativado automaticamente — pesado demais para este aparelho."
  (com "OK"; opcionalmente "Reaplicar" re-arma). O loop morre no close do container e no
  cancelamento do apply.
- Nenhuma ação sobre o render thread (a regra do ARMSX2 continua: o clear também é
  deferred — `VulkanRenderer.java:916-929`).

**Arquivos:** `VulkanRenderer.java` (+getter), `shaders/ShaderWatchdogLogic.kt` (novo),
`ShaderSectionState.kt`, `QuickMenu.kt`, strings, testes `ShaderWatchdogLogicTest`.
**Aceite (JVM):** janela curta de FPS alto → Keep; média > 4 FPS após warm-up → Revert;
sem amostras suficientes → Keep; fronteiras exatas (7 vs 8 amostras).
**Aceite (on-device):** aplicar Mega Bezel → após ~5-35 s o shader reverte sozinho com o
diálogo; aplicar crt-lottes (leve) → nada acontece; reverter manualmente continua
funcionando.

---

## M4 — A troca assíncrona: remover o congelamento na origem

**Problema:** as camadas M1-M3 protegem e explicam, mas o congelamento da thread da UI
durante o build do chain ainda existe (aplicar um pesado **aceito** no M1 ainda trava por
segundos — sem o ANR só se o build for rápido). A correção estrutural é construir o chain
**fora** da thread da UI, com estado "Applying…" visível e cancelável.

**Evidência:** o build síncrono em `reloadPreset` (`VulkanLibrashader.cpp:52-120`,
chamado de `loadRetroArchShaderPreset` sob `synchronized(lock)`); a regra do repo permite
build fora da render thread **desde que o swap do chain vivo continue na render thread**
(a lição do ARMSX2 é sobre o chain vivo — o build novo já acontece antes do swap no
create-first).

**Design (esboço — revisão nativa obrigatória antes do código):**
- Nativo: `startLoadPreset(path)` dispara um `std::thread` dedicado que executa o mesmo
  create-first (preset_ctx → preset → filter_chain), **sem tocar no chain vivo**; ao
  terminar, publica o resultado num slot protegido por `mtx` (`pendingNewChain`,
  `pendingError`) e sinaliza. `applyFrame` (render thread) verifica o slot pendente e faz
  o swap lá (free do chain velho na render thread, depois do `vkQueueWaitIdle` existente).
  Falha de build publica o erro no slot — comportamento idêntico ao latch atual.
- Java: `loadRetroArchShaderPresetAsync(path)` retorna imediatamente;
  `isLibrashaderLoadPending()`/`getLibrashaderLastError()` para polling; `ShaderSectionState`
  ganha `applyingPreset` (estado visível: row com spinner + "Applying shader…", padrão do
  `Downloading…` existente) e `cancelApplying()` (abandona o slot pendente).
- Ordem de trocas: aplicar B enquanto A ainda compila → cancelar A e enfileirar B (slot
  único; o último apply vence — padrão já usado no metered/`pendingPreset`).

**Arquivos:** `VulkanLibrashader.h/.cpp`, `VulkanRenderer.java`, `ShaderSectionState.kt`,
UI (rows "Applying…"), strings.
**Aceite (on-device):** aplicar pesado não bloqueia a UI (menu responde, "Applying…"
visível); ao concluir, swap atômico na render thread; falha de build mantém o shader
anterior e mostra o erro; cancelar no meio não corrompe o chain vivo; Silksong nunca vê
input alterado.

---

## Ordem de execução e dependências

1. **M1** (independente, ~1 dia) — o portão já evita a maioria dos acidentes.
2. **M3-getter** (frame time no renderer, ~meio dia) — desbloqueia M2 e M3.
3. **M2** (usando o getter, ~1 dia) — desarma a armadilha do reinício.
4. **M3** (lógica + runner + diálogo, ~1-2 dias) — auto-recuperação em tempo real.
5. **M4** (revisão nativa, ~3-5 dias) — remove o congelamento na origem.

M1+M2+M3 juntas cobrem a UX completa ("avisar → explicar → curar") mesmo com o
congelamento existindo; M4 é o bônus estrutural.

## Pendências on-device (padrão do repo)

- [ ] M1: diálogo do portão com DS4 (foco inicial no "Cancelar", A/B, sem dead-menu).
- [ ] M2: matar o app durante Mega Bezel → próximo launch com diálogo pós-queda; flag
  antiga ignorada (falsificar o JSON para testar).
- [ ] M3: Mega Bezel reverte sozinho em < 35 s; crt-lottes não reverte; Silksong segue
  jogável após a reversão.
- [ ] M4: apply de pesado com menu responsivo + "Applying…"; cancelamento; latência de
  frame estável durante o build.
- [ ] Regressão: cenário completo do hardening de gamepad (spec
  2026-08-12-quickmenu-gamepad-pipeline-hardening) com os diálogos novos abertos.
