# librashader / RetroArch Shaders — Log de Tentativas (todas falharam)

**Propósito:** documentar TODAS as mudanças já feitas na integração librashader do GameNative, marcadas
como **tentativas falhas**, para que uma nova sessão de trabalho **não proponha as mesmas soluções**.
**Alvo:** Xiaomi Mi 11 (alioth), Adreno 650, Vulkan 1.1.128, driver stock (`/vendor/lib64/hw/vulkan.adreno.so`).
**Estado final:** a tela continua **preta** quando um shader é aplicado; o app não crasha; `applyFrame`
reporta sucesso; o conteúdo filtrado **existe** em `processedImage` (verificado por readback), mas **nenhuma
forma de leitura/presentação testada** o exibiu na tela.

---

## 1. Estado do repositório

Nada da integração foi commitado além do doc de design (`dc6b8c38`). Única fonte do "estado original"
(= upstream Winlator) é `origin/master` — **nada foi pushado**.

| arquivo | estado | como reverter ao original |
|---|---|---|
| `app/src/main/cpp/winlator/VulkanRendererContext.cpp` | modificado | `git restore app/src/main/cpp/winlator/VulkanRendererContext.cpp` |
| `app/src/main/cpp/winlator/VulkanRendererContext.h` | modificado | `git restore app/src/main/cpp/winlator/VulkanRendererContext.h` |
| `app/src/main/cpp/winlator/vulkan_jni.cpp` | modificado | `git restore app/src/main/cpp/winlator/vulkan_jni.cpp` |
| `app/src/main/cpp/CMakeLists.txt` | modificado | `git restore app/src/main/cpp/CMakeLists.txt` |
| `app/build.gradle.kts` | modificado | `git restore app/build.gradle.kts` |
| `app/src/main/java/com/winlator/renderer/VulkanRenderer.java` | modificado | `git restore app/src/main/java/com/winlator/renderer/VulkanRenderer.java` |
| `app/src/main/java/app/gamenative/ui/component/ScreenEffectsPanel.kt` | modificado | `git restore app/src/main/java/app/gamenative/ui/component/ScreenEffectsPanel.kt` |
| `.gitignore` | modificado | `git restore .gitignore` |
| `app/src/legacy/jniLibs/arm64-v8a/libvulkan_renderer.so` | deletado | `git restore app/src/legacy/jniLibs/arm64-v8a/libvulkan_renderer.so` |
| `app/src/modern/jniLibs/arm64-v8a/libvulkan_renderer.so` | deletado | `git restore app/src/modern/jniLibs/arm64-v8a/libvulkan_renderer.so` |
| `app/src/main/cpp/winlator/VulkanLibrashader.cpp` | **novo** (sem versão original) | remover para desfazer a integração |
| `app/src/main/cpp/winlator/VulkanLibrashader.h` | **novo** (sem versão original) | remover para desfazer a integração |
| `app/src/main/java/com/winlator/renderer/RetroArchShaderConfig.java` | **novo** | remover |
| `app/src/main/java/com/winlator/renderer/RetroArchShaderPreset.java` | **novo** | remover |
| `app/src/main/java/com/winlator/renderer/LibrashaderParam.java` | **novo** | remover |
| `app/src/main/java/com/winlator/renderer/ShaderImporter.java` | **novo** | remover |
| `app/src/main/java/app/gamenative/ui/component/dialog/RetroArchShaderDialog.kt` | **novo** | remover |
| `app/src/main/assets/retroarch/` | **novo** (19 presets curados) | remover |
| `AGENTS.md` | **novo** | remover |
| `build-apk.sh` | **novo** | remover |

**Importante:** a exclusão dos prebuilts em `jniLibs` foi **correta e necessária** (um prebuilt antigo sem as
funções JNI do librashader causava `UnsatisfiedLinkError` no runtime — o `.so` do CMake é o único fonte).

---

## 2. Arquitetura implementada (contexto)

- **`VulkanLibrashader.cpp/.h`** — wrapper `dlopen` da C API (`liblibrashader.so`, ABI 2). Funções resolvidas
  via `dlsym`: `libra_preset_*`, `libra_vk_filter_chain_*`. Resolve também `vkGetDeviceProcAddr` para a vk
  API usada internamente (`libra_vk_filter_chain_create` com `device_info`).
- **`VulkanRendererContext.*`** — renderer Vulkan. Path shader usa:
  - `offscreenImage` (saída do compositor, render target) — `usage = COLOR_ATTACHMENT|SAMPLED|TRANSFER_SRC`.
  - `processedImage` (saída da filter chain) — `usage = COLOR_ATTACHMENT|SAMPLED|TRANSFER_SRC`.
  - `offscreenRenderPass` dedicado (finalLayout = COLOR_ATTACHMENT_OPTIMAL).
  - `filterCmdBuf` dedicado (command pool + fence `filterFence`).
  - Present via blit do `processedImage` → swapchain.
- **`vulkan_jni.cpp`** — `nativeInitLibrashader`, `nativeLoadLibrashaderPreset`, `nativeEnableLibrashader`,
  `nativeSetLibrashaderParam`, `nativeGetLibrashaderError`.
- **UI** — `ScreenEffectsPanel.kt` (toggle inline), `RetroArchShaderDialog.kt` (helpers), `ShaderImporter.java`
  (importa `.slangp` + dependências), `VulkanRenderer.java` (API pública).

---

## 3. Cronologia de TODAS as tentativas

> Legenda: ✅ FUNCIONOU (mantém) · ❌ FALHOU (não resolveu o problema) · 🧪 diagnóstico

### 3.1 Integração base (estado inicial do trabalho)
- Wrapper dlopen + JNI + UI + offscreen/processed + blit por **sampler** no mesmo `filterCmdBuf`.
- Resultado: build OK; **tela preta** ao aplicar shader.

### 3.2 ✅ Fix do crash (duplo `EndCommandBuffer`)
- `diagnoseOffscreenReadback()` chamava `vk_.EndCommandBuffer(cb)` na linha 1684 **e** de novo dentro de
  `endOneTime()` → null deref no driver Adreno (`qglinternal::vkEndCommandBuffer`), SIGSEGV, app fechava.
- Correção: removido o `EndCommandBuffer` redundante.
- Resultado: ✅ crash resolvido (app deixa de fechar).

### 3.3 Alinhamento com melonDS (parte barata)
- `libra_preset_ctx_set_allow_rotation(&presetCtx, false)` (símbolo resolvido e chamado).
- `filter_chain_vk_opt_t` explícito: `version=2, frames_in_flight=3, force_no_mipmaps=false,
  use_dynamic_rendering=false, disable_cache=false`.
- `PresetOpt` com 4 campos (adicionado `sensor_uniforms`).
- Getters de versão como `size_t(* )()`.
- `preset = nullptr` logo após `libra_vk_filter_chain_create`.
- Resultado: ❌ nenhum efeito na tela preta.

### 3.4 Espera do fence do frame anterior
- `WaitForFences(inFlightFences[prevFrame])` antes de o compositor reescrever `offscreenImage`.
- Resultado: ❌ nenhum efeito.

### 3.5 ✅ Fix de layout do `offscreenImage` (sampler passou a funcionar)
- `offscreenRenderPass`: `initialLayout = finalLayout = COLOR_ATTACHMENT_OPTIMAL`.
- Transição explícita `COLOR_ATTACHMENT_OPTIMAL → SHADER_READ_ONLY_OPTIMAL` no `filterCmdBuf` antes do blit.
- Resultado: ✅ o sampler de `offscreenImage` passou a ler o jogo (READBACK-SC mostrava o jogo). Ou seja:
  o problema nunca foi o sampler nem a composição — era o **layout/transição do `offscreenImage`**.

### 3.6 🧪 TEST MODE (`gLibraTestBlitOffscreen`)
- Flag hardcoded que desvia do librashader e blita `offscreenImage` direto → swapchain, com readback dos
  primeiros 3 frames (`READBACK offscreen ...`).
- Resultado: isolou o problema — compositor + blit estão OK; o que falha é a **leitura da saída da filter
  chain** (`processedImage`).

### 3.7 🧪 Diagnósticos de readback
- `diagnoseOffscreenReadback()` → `READBACK offscreen ...`
- `diagnoseProcessedReadback()` → `READBACK-P processed ...`
- `diagnoseSwapchainReadback()` → `READBACK-SC swapchain ...` (via `vkCmdCopyImageToBuffer` +
  `vkUnmapMemory`/`vkCmdCopyImageToBuffer` adicionados ao `VkTable`).
- Resultado: 🧪 **evidência-chave** (ver seção 4).

### 3.8 Barreira pós-filtro alargada (C1)
- `transition(processedImage, COLOR_ATTACHMENT_OPTIMAL → SHADER_READ_ONLY_OPTIMAL, MEMORY_WRITE|COLOR_ATTACHMENT_WRITE
  → SHADER_READ, ALL_COMMANDS → FRAGMENT_SHADER)`.
- Resultado: ❌ nenhum efeito.

### 3.9 `queueMtx` (external sync)
- Mutex serializando `QueueSubmit`/`QueuePresent`/`endOneTime`/`loadLibrashaderPreset` (reload) — por causa do
  `vkQueueSubmit` + `queueWaitIdle` que o `libra_vk_filter_chain_create` faz na UI thread.
- Resultado: ❌ nenhum efeito na tela preta (ainda que seja correto manter por segurança).

### 3.10 Split de submissions (Sub-A applyFrame + fence; Sub-B sampler blit)
- Sub-A: transição `offscreenImage`, `applyFrame`, EndCommandBuffer, submit `filterFence`, `WaitForFences`.
- Sub-B: transição `processedImage`, sampler blit `processedImage → swapchain`, cursor, EndCommandBuffer,
  submit + present.
- Resultado: ❌ sampler de `processedImage` **ainda preto mesmo cross-submission**.

### 3.11 Transfer blit (`vkCmdBlitImage`)
- `blitImageToSwapchainTransfer()` / `blitProcessedToSwapchainTransfer()`: mover `offscreenImage` /
  `processedImage` → swapchain com o motor de transferência (como o melonDS move a saída do filtro).
- Swapchain: `imageUsage` ganhou `TRANSFER_DST_BIT`.
- Resultado: ❌ tela preta no `processedImage` mesmo via transfer no mesmo submission.

### 3.12 Split + transfer blit (última tentativa)
- Sub-A (applyFrame + fence wait) + Sub-B (`blitProcessedToSwapchainTransfer`).
- Resultado: ❌ reportado preto pelo usuário. **Sem log capturado** (logcat limpo/device reiniciado) — o
  resultado é o relato do usuário, não evidência de log.

---

## 4. Evidências de diagnóstico (verificadas por log)

| leitura de imagem | método | onde | resultado |
|---|---|---|---|
| `offscreenImage` | transfer readback (`vkCmdCopyImageToBuffer`, submission separada) | TEST MODE | ✅ jogo presente (`ff0d112d` etc.) |
| `offscreenImage` | sampler blit → swapchain | TEST MODE (após 3.5) | ✅ jogo visível |
| `offscreenImage` | transfer blit → swapchain | TEST MODE | ✅ jogo visível |
| `processedImage` | transfer readback (submission separada, frame seguinte) | path real | ✅ **conteúdo presente** (`ff0c1028`, `ff0b0b26`, `00080c27`) |
| `processedImage` | sampler blit → swapchain (mesmo submission) | path real | ❌ preto |
| `processedImage` | sampler blit → swapchain (cross-submission / split) | path real | ❌ preto |
| `processedImage` | transfer blit → swapchain (mesmo submission) | path real | ❌ preto |
| swapchain | transfer readback | TEST MODE | ✅ jogo presente (após 3.5) |

**Leitura da evidência:** o librashader **está renderizando corretamente** (o `processedImage` contém o
resultado do shader). O que falha é **toda** leitura de `processedImage` feita no mesmo frame/pipeline da
escrita — sampler ou transfer, mesmo/mesmo submission ou cross-submission. Apenas a leitura por transfer
num command buffer one-time separado **no frame seguinte** lê o conteúdo corretamente.

---

## 5. Fatos estabelecidos (o que NÃO é o problema)

- **Não é o librashader:** `processedImage` tem os pixels do shader (READBACK-P ≠ 0).
- **Não é o alpha:** swapchain usa `VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR` (VulkanRendererContext.cpp:295-297) e o
  shader de blit força `alpha = 1.0` (window.frag:289). A teoria "alpha=0 → SurfaceFlinger PRE_MULTIPLIED"
  foi **descartada** (e o melonDS também usa OPAQUE).
- **Não é o formato:** tudo `R8G8B8A8_UNORM` (offscreen, processed, swapchain).
- **Não é cross-submission isoladamente:** o split (3.10) também ficou preto.
- **Não é sampler vs transfer isoladamente:** ambos ficam pretos com `processedImage` (3.10, 3.11, 3.12).
- **Não é external sync:** `queueMtx` (3.9) não mudou nada.
- **Não é a composição/present:** o blit de `offscreenImage` → swapchain funciona (3.5, TEST MODE).
- **Não é mipmap/descriptor:** `processedImage` tem 1 nível; descritor aponta `processedView`.

---

## 6. Anti-soluções (NÃO propor de novo em uma nova sessão)

1. Não rediagnosticar "o librashader está renderizando?" — **já comprovado que sim** (READBACK-P).
2. Não testar "sampler vs transfer" como causa raiz — **ambos falham** com `processedImage`.
3. Não testar "split de submissions / cross-submission" sozinho — **falhou** (3.10, 3.12).
4. Não adicionar/remover `TRANSFER_DST_BIT` da swapchain — **não resolve** (3.11).
5. Não mexer em alpha / compositeAlpha — **não é o problema** (já OPAQUE).
6. Não alargar mais barreiras `ALL_COMMANDS`/`MEMORY_WRITE` — **não resolve** (3.8).
7. Não re-adicionar `queueMtx` como solução — **não resolve** (3.9) (manter por segurança é ok).
8. Não re-introduzir prebuilts em `jniLibs` — causa `UnsatisfiedLinkError`.
9. Não descomentar o target `winlator` no CMake — fontes incompletas no repo.
10. Não reativar TEST MODE (`gLibraTestBlitOffscreen`) como resposta — é diagnóstico, não fix.
11. Não assumir que o problema é o layout de `offscreenImage` — **já foi corrigido** (3.5).
12. Não mudar `use_dynamic_rendering` para `true` sem investigação — issue upstream #225 mostra preto com
    dynamic rendering; não foi o caminho validado.

---

## 7. Pistas para a próxima investigação (ainda não tentadas)

> Apenas direções, não soluções. Investigar antes de implementar.

- **Diferença `offscreenImage` (funciona) vs `processedImage` (falha):** o `offscreenImage` é escrito pelo
  compositor do GameNative (render pass próprio, finalLayout=COLOR_ATTACHMENT). O `processedImage` é escrito
  **internamente** pelo librashader (dynamic rendering / render passes internos). Vale investigar se a
  transição de layout que o librashader deixa registrada no `processedImage` após `libra_vk_filter_chain_frame`
  está coerente com a que o GameNative assume (`COLOR_ATTACHMENT_OPTIMAL`). O doc oficial diz que a saída
  **permanece** em `COLOR_ATTACHMENT_OPTIMAL` e que o caller faz a transição final — mas o estado real pode
  diferir no driver Adreno.
- **Readback do `processedImage` no MESMO frame (não só no frame seguinte):** confirmar se a leitura por
  transfer *imediatamente após* `applyFrame` (no mesmo pipeline) também lê preto — isolaria se o problema é
  "leitura dentro do frame" vs "leitura do processedImage em geral".
- **Formatar o `libra_vk_filter_chain_frame` com `viewport` nulo** (usar o padrão) — o GameNative passa um
  viewport 1:1; verificar se há diferença.
- **Verificar se `offscreenImage` precisa de `TRANSFER_SRC` para presets de histórico** (já tem) e se o
  `processedImage` deveria ser apresentado **sem transição intermediária** (blit direto do estado em que o
  librashader deixou).
- **Comparar passo a passo com o fluxo real do melonDS** (que funciona): ele blita `topOutput` → `atlasOutput`
  **dentro do mesmo command buffer** do filtro via `vkCmdBlitImage` e depois amostra o atlas num submission
  posterior. O GameNative tentou transfer blit do `processedImage` **direto → swapchain**; a diferença é que o
  melonDS primeiro copia para um **atlas intermediário dedicado** e só depois apresenta.

---

## 8. Referências

- **Spec de design:** `docs/superpowers/specs/2026-07-31-librashader-fix-design.md` (commit `dc6b8c38`).
- **melonDS (referência funcionando):** commit `fa2d9107d2834d25a3b418cf2bf491e3e8a00448` do
  `SapphireRhodonite/melonDS-android-lib`; código salvo localmente em
  `/home/annapaula/.local/share/opencode/tool-output/tool_fbaacb4a9001U7qREoNHruNWS9`
  (`VulkanSurfacePresenter.cpp`: `runRetroArchFilter`, submit-and-wait dedicado, layout tracking manual,
  blits `topOutput`/`bottomOutput` → `atlasOutput`).
- **librashader local:** `/home/annapaula/GameNative/librashader/` (v0.12.0, ABI 2) —
  `librashader-capi/src/runtime/vk/filter_chain.rs` (doc: output permanece em `COLOR_ATTACHMENT_OPTIMAL`,
  caller faz a transição final), `librashader-runtime-vk/tests/hello_triangle/mod.rs` (padrão oficial).
- **3 sessões de pesquisa externa (Qwen)** sobre GMEM/submission boundary/alpha — síntese incorporada nas
  seções 4–6. A pesquisa 2 continha **afirmações incorretas** (alpha PRE_MULTIPLIED; "melonDS renderiza direto
  no swapchain") — **descartadas**, contrariadas pelo código real do melonDS e pelo nosso código.

---

## 9. ✅ RESOLVIDO (2026-08-07) — causa raiz e fix completo

**Resultado:** o shader RetroArch (librashader) agora aparece na tela no Xiaomi Mi 11
(Adreno 650). Verificado no device com `easymode.slangp` (CRT com scanlines visíveis) e com
`invert.slangp` (imagem negativa inconfundível). Commit: `fix: make librashader (RetroArch
shaders) actually present on screen`.

### Causas reais (cadeia de bugs, cada um produzia tela preta)

1. **Import do AHB inválido** — `importAHBToWinTex` usava `VkExternalFormatANDROID` com um
   valor de `VkFormat` (37/44) como "external format" junto com formato real — uso inválido
   que fazia o driver amostrar preto. Fix: usar `fmtP.format` reportado pelo driver
   (B8G8R8A8 para os buffers BGRA_8888 do jogo).
2. **Fix 3.5 perdido** — o offscreenRenderPass tinha `finalLayout=SRO` (blob WIP restaurado,
   anterior ao fix 3.5). O sampler do Adreno não lia a imagem. Restaurado: `finalLayout=CAO`
   + transição explícita CAO→SRO (barreira larga melonDS) antes de cada leitura por sampler.
   Além disso, o pipeline do compositor precisa ser criado contra o offscreenRenderPass
   (`offscreenPipeline` dedicado).
3. **Atlas (transfer-write) amostra preto no Adreno** — a cópia `filterOutput→atlas` via
   `vkCmdCopyImage` produzia uma imagem que o sampler lê como preto (imagens escritas por
   render pass amostram corretamente; por transfer não). Fix: apresentar `filterOutputImage`
   direto, sem atlas.
4. **Split submissions não executam** — applyFrame num submit+wait e o blit de present num
   CB separado gravava draws que nunca executavam. Fix: applyFrame + blit no MESMO command
   buffer/submission (estrutura do TEST MODE C).
5. **Cursor limpava o swapchain** — o bloco do cursor abria um SEGUNDO render pass no
   swapchain com `loadOp=CLEAR` (renderPass compartilhado) e `clearValueCount=0`, apagando
   o frame apresentado sempre que o cursor estava visível (jogos com cursor no menu).
   Fix: desenhar o cursor DENTRO do render pass do present.
6. **Shader não aplicava no launch** — a config persistida do RetroArch nunca era enviada ao
   renderer na inicialização do jogo. Fix: `XServerScreen` aplica `loadShaderConfig` +
   `loadRetroArchShaderPreset` + `setRetroArchShaderEnabled` quando o renderer existe.
7. **Crash cold-start** — `XServerScreen` compunha com `appId` vazio e
   `ContainerUtils.getContainer("")` lançava exceção. Fix: guard early-return (sem chamada
   composable no branch — um `Box()` ali quebra o DEX verifier do ART 11).

### Diagnóstico que resolveu
- `READBACK-OFF-GRID` (grade 5x5 do offscreen via transfer) provou que o compositor desenhava
  o jogo no offscreen — o problema era a LEITURA por sampler (não o desenho).
- TEST MODEs comutáveis em runtime via `setprop debug.gamenative.libradiag` (2=AHB direto,
  4=offscreen→swapchain, 5=filterOutput→swapchain) isolaram o caminho que funciona.
- O shader `invert.slangp` (negativo) deixou qualquer progresso visual inconfundível.
- A lição principal: **os docs antigos (seções 1-8) foram enganados por um deadlock**: o
  `EndCommandBuffer` duplo / CB não finalizado fazia o render loop travar e a tela congelar
  preta — os "sampler failures" atribuídos a layout/GENERAL eram artefatos do deadlock.

---

## 10. Shaders libretro + loop de teste automatizado (2026-08-07)

**Resultado:** 131 presets do https://github.com/libretro/slang-shaders embarcados no APK
(assets/retroarch), substituindo os 5 originais. Default: `film/technicolor` (transformação de
cor forte; verificado no device: média da tela sobe de ~34/23/23 (baseline) para ~64/56/54 com
103/128 amostras com conteúdo). 16 de 17 presets amostrados compilam e renderizam no Mi 11
(apenas `misc/glass` falha ao compilar).

### Infraestrutura de teste (cibernética / caixa-preta)

- **Atuador:** `adb shell setprop debug.gamenative.preset <abs/path.slangp>` → a chain do
  librashader recarrega em runtime na render thread (sem reiniciar o jogo; ~6s por shader).
- **Sensores:** `screencap` + estatísticas de pixels (numpy), logcat (`preset chain active`,
  `filter chain create failed`, `READBACK-OFF`), liveness (`pidof`, contagem de frames).
- **Controlador:** `tools/shader-test-loop/shader_test_loop.py` — itera a lista de presets,
  classifica (BRIGHT/VISIBLE/BLACK/CHAIN_FAIL/CRASH) e grava CSV.
- **Controle positivo:** `misc/invert` (negativo — tela claramente brilhante quando ativo).

### Fix adicional: deferred preset reload

O reload da chain feito pela thread da UI (chain create faz submit/waitIdle na queue) corria
contra a gravação de frames da render thread → SIGSEGV no driver (vkEndCommandBuffer). Todos os
reloads agora são aplicados na render thread (pedidos JNI viram pendência + hook de runtime),
o que também eliminou o deadlock do primeiro load (o processamento estava dentro de
`if (libraPath)`, mas o primeiro load precisa rodar com active=false).
