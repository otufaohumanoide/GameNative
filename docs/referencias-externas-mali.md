# Referências externas para Mali/MediaTek — o que foi clonado e o que realmente serve

**Data:** 2026-10-09
**Origem:** pedido do usuário — clonar projetos de referência para consulta durante a
implementação de features de Mali/MediaTek.
**Local:** `reference/` (já previsto no `.gitignore:87-91` como área de clones de estudo;
**nada aqui é commitado**). Os clones somam ~258 MB.
**Verificação:** todas as descrições foram conferidas por leitura do código clonado — a §3
registra o que a lista original afirmava e **não se sustenta**.

---

## 1. O que foi clonado

| Projeto | Caminho | Tamanho | Serve para |
|---|---|---|---|
| [leegao/winlator-internals](https://github.com/leegao/winlator-internals) | `reference/winlator-internals/` | 684 KB | Arquitetura do Vortek + os **workarounds de driver para Mali** (a referência mais densa e acionável) |
| [ar37-rs/winlator_mali](https://github.com/ar37-rs/winlator_mali) | `reference/winlator_mali/` | 239 MB | Fork de Winlator focado em Mali: variáveis de ambiente e configuração de Vortex/VirGL/WineD3D |
| [FrankBarretta/LSFG-Android](https://github.com/FrankBarretta/LSFG-Android) | `reference/LSFG-Android/` | 232 KB (+ submódulo) | Frame generation em Android via MediaProjection + AHardwareBuffer |
| [Pipetto-crypto/bcn_layer](https://github.com/Pipetto-crypto/bcn_layer) | `reference/bcn_layer/` | 528 KB | Layer Vulkan que descomprime BC1-7 on-the-fly por compute shader |
| [rdevz-ph/FPS-Meter-Android](https://github.com/rdevz-ph/FPS-Meter-Android) | `reference/FPS-Meter-Android/` | 18 MB | Telemetria: FPS real, detecção Vulkan/GLES e temperaturas via Shizuku |

**Submódulo inicializado:** o conteúdo interessante do LSFG-Android está no submódulo
`lsfg-vk-android` (repo próprio `FrankBarretta/lsfg-vk-android`), que não vem com o clone
raso. Rodei `git submodule update --init --depth 1 lsfg-vk-android`; sem isso o clone é
inútil para o propósito (o patch AHB é lá).

**Não clonáveis:** *Sokatoa* e *Android Performance Analyzer (APA)* **não são repositórios
públicos** (verificado em 4 grafias) — são ferramentas oficiais do Google distribuídas por
download, não código aberto. Ficam fora desta pasta.

---

## 2. O que cada um entrega de fato (verificado)

### 2.1 `winlator-internals` — a referência de maior valor

Não é código: é uma série de artigos (Jekyll, em `_posts/`) com engenharia reversa do
**Vortek**, o "driver" Vulkan do Winlator.

- `2025-06-01-Vortek1.md` — modelo **cliente-servidor** do Vortek: `libvulkan_vortek.so`
  (cliente, ICD) serializa comandos Vulkan via ring buffer IPC para `libvortekrenderer.so`
  (servidor), que os executa no driver do sistema. Explica por que o wrapper consegue
  *patchar* chamadas e valores de retorno.
- `2025-06-02-Vortek2.md` — os workarounds para Mali:
  - **BCn emulado por decodificação JIT (CPU-bound)**: intercepta `vkCreateImage`, troca o
    formato comprimido pelo equivalente `VK_FORMAT_B8G8R8A8_UNORM` e preenche os buffers em
    `vkQueueSubmit` (`TextureDecoder::decodeAll`). O formato é *advertido* como suportado em
    `vkGetPhysicalDeviceImageFormatProperties` (`2025-06-28-Vortek3.md`).
  - **`gl_ClipDistance` removido (Mali)**: Mali não suporta a capability → remoção das
    instruções SPIR-V `OpDecorate … BuiltIn ClipDistance` e `OpCapability ClipDistance`
    (custa glitches de clipping, mas a pipeline passa a ser criada).
  - **emulação de `_USCALED`/`_SSCALED`** de vertex buffers dentro do shader.
- `2025-08-10-OpCompositeConstant.md` — **"Mali Adventures Part 1"**: bug do SPIR-V
  `OpSpecConstant` no Mali-G76 que quebrou o DXVK 1.7.3. **Conexão direta com o nosso fork:**
  é exatamente o bug por trás do nosso `WRAPPER_NO_PATCH_OPCONSTCOMP` (`XServerScreen.kt:5985`,
  ativo quando `dxwrapper` = `1.11.1-sarek`).
- ⭐ `2026-07-28-wsi-woes-mali.md` — **"WSI Woes on Mali"**, o achado mais acionável de todos:
  - Em **todo** Mali, a importação de AHB `HAL_PIXEL_FORMAT_BGRA_8888` é ilegal, então o
    swapchain externo falha e **tudo cai no caminho de blit** — um penhasco de desempenho.
  - `VK_EXT_map_memory_placed` não existe em Mali (exceto Turnip/Adreno recente) → precisa de
    emulação; a emulação quebra por causa do `VK_KHR_dedicated_allocation`.
  - Em GKI 4.x não há `dmabuf-heap` → cai para `ion-heap`, com UAPIs incompatíveis.
  - Correções que o próprio post já mandou para **`GameNative/mesa`** (o nosso wrapper!):
    pular a emulação de `map_memory_placed` para imagens de swapchain
    ([PR #6, commit `ffe2127`](https://github.com/GameNative/mesa/pull/6/commits/ffe2127566a57887e22619cb1e94fa361e22a7d9));
    **emular o swapchain BGRA via AHB `RGBA_8888` + image view BGRA** para habilitar
    *direct rendering* em Mali e driblar os bugs de blit ([PR #6, commit `2af0e2e`](https://github.com/GameNative/mesa/pull/6/commits/2af0e2e588792a40fd092f73f64da0ff1b9fcb35));
    desvincular os structs de `VK_KHR_dedicated_allocation` ([PR #8](https://github.com/GameNative/mesa/pull/8/changes/0bd55cbed77ddade643b31646dad69db8f04f08f));
    e `lseek` em cada fd de AHB para escolher o mmap-ável ([PR #7](https://github.com/GameNative/mesa/pull/7/changes)).

  **Por que importa para nós:** o nosso `Container.java:49` expõe `resourceType=auto|dmabuf` →
  `WRAPPER_RESOURCE_TYPE`, `useDRI3` decide `MESA_VK_WSI_DEBUG=sw` (`XServerScreen.kt:5991-5994`).
  Ou seja, já temos os botões que interagem com exatamente esse caminho de AHB/blit no Mali.

### 2.2 `winlator_mali` — mapa de configuração de Mali (com uma ressalva)

README confirma, e o código do fork só parcialmente implementa:

| Item do README | Verificado |
|---|---|
| VirGL: `MESA_GLSL_VERSION_OVERRIDE` = `410`/`150`/`140` | ✅ `README.md:12` |
| Vortex+WineD3D: `MESA_GL_VERSION_OVERRIDE` `3.2COMPAT`/`4.1COMPAT` + GLSL correspondente | ✅ `README.md:22-30` |
| Vortex+DXVK: desmarcar `VK_EXT_extended_dynamic_state` (texturas ausentes em alguns Mali) | ✅ `README.md:57` — mas é só instrução de UI: **o fork não tem mecanismo de blacklist de extensão Vulkan** (o que existe no Winlator é o `blacklistedExtensions` do wrapper, que **nós já temos** em `XServerScreen.kt:6065-6066`) |
| `mesa_glthread` off; `BOX64_SAFEFLAGS=2`; `WINEDEBUG=-all` | ✅ `README.md:72-78` |
| `BOX64_MMAP32` = `1` | ✅ `README.md:74`, **mas** o código do fork só define isso no caminho genérico `if (wow64Mode)` (`GuestProgramLauncherComponent.java:215`) — idêntico ao nosso. **Não é uma prescrição específica de Mali; é o default documentado.** Ver §3. |
| Mínimo suportado: Mali-G52 (driver 49.x+) ou G57+ | ✅ `README.md:49` — útil como referência de geração, casa com `GpuClassification.maliModel/maliSeries` |
| Defaults do fork: `ZINK_DESCRIPTORS=lazy ZINK_DEBUG=compact MESA_SHADER_CACHE_MAX_SIZE=512MB mesa_glthread=true WINEESYNC=1 MESA_VK_WSI_PRESENT_MODE=mailbox TU_DEBUG=noconform` | ✅ `Container.java:20` (nota: o README manda desligar `mesa_glthread`, o default do fork liga) |

Base: winlator-ajay / Fcharan-WinlatorMali / afeimod — linhagem mais antiga que a nossa.

### 2.3 `LSFG-Android` — AHB real, mas resolve outro problema

Confirmado no submódulo: `createContextFromAHB` (`lsfg-vk-android/src/context.cpp:119,123`),
`waitIdle` (`:206,253,255`), compartilhamento de imagem por AHB
(`include/mini/image.hpp:42`), tudo sob `#ifdef __ANDROID__`.

**Ressalva importante (corrige a premissa da lista):** o LSFG-Android usa
**MediaProjection + overlay de sistema** porque precisa capturar a tela de **outro app**
(Android 12+ impede carregar layer externo em processo não-debugável). O nosso fork faz
frame generation **dentro do nosso próprio renderer**, hookando o nosso swapchain — não há
MediaProjection envolvido, e portanto **a arquitetura dele não é portável**.

O que é portável é o patch Vulkan: **`AHardwareBuffer`** — e esse caminho **nós já
implementamos** (`device.cpp:46-54` exige AHB no Android; import em `image.cpp:244-300`).
Ver também a §3 sobre `robustness2`.

### 2.4 `bcn_layer` — é real, mas não tem a lógica que a lista descreve

Layer Vulkan autocontida: `src/bcn_layer.cpp`, `src/bcn.cpp`, shaders compute
`src/{s3tc,bc7,bc6,rgtc}*.comp`, `Makefile`, `libbcn_layer.json`. Usa Granite como projeto
externo (não é submódulo; os shaders parecem vendorizados em `src/`).

Serve para estudar **como interceptar `vkCreateImage`/`vkCmdCopyBufferToImage` e substituir
por uma pipeline de compute** — a mesma família de técnica que o Vortek descreve (mas em GPU,
não em CPU).

### 2.5 `FPS-Meter-Android` — telemetria, dependente de Shizuku

Confirmado: detecção Vulkan vs OpenGL ES por `dumpsys gpu` (`createdVulkanSwapchain`) e
`dumpsys gfxinfo` (`SurfaceFlingerFpsMonitor.kt:316-336`); FPS real por
`dumpsys SurfaceFlinger --latency` (`:170-171`); identidade do SoC por `getprop ro.soc.model`
/ `ro.board.platform` (`DeviceDiagnosticsScreen.kt:624-625`); temperaturas por
`dumpsys thermalservice` + `/sys/class/thermal/thermal_zone*`
(`SocThermalMonitor.kt:106-121`), com casamento de tipo por vendor — `cluster`, `cpuss`,
**`gpuss`** (`:263-286`).

**Tudo o que usa Shizuku é inaplicável ao GameNative** (não podemos exigir Shizuku), e o app
não lê nada de `mali0`/MediaTek GPU sysfs (ver §3).

---

## 3. Correções à lista original (o que não se sustenta)

| Afirmação da lista | Realidade verificada |
|---|---|
| "`bcn_layer` já tem lógica para ignorar BCn 1-3 em Xclipse e todos os formatos BCn em Adreno" | **Falso.** Zero ocorrências de `adreno`/`xclipse`/`mali` no repo. A exclusão por GPU **não existe** ali (a nossa regra de Adreno/Xclipse em `GpuClassification` não tem respaldo nesse projeto). |
| "`FPS-Meter` lê os sysfs de Mali `/sys/class/misc/mali0/device/utilization` e `/sys/devices/11800000.mali/clock`" | **Falso.** Não há nenhuma dessas strings. Ele lê `dumpsys thermalservice` e `/sys/class/thermal/thermal_zone*` genérico. |
| "A técnica de AHB do LSFG-Android é o fallback a implementar para Mali quando `VK_EXT_robustness2` não existe" | **Duas vezes falso.** (1) `robustness2` é opcional e já tem fallback no nosso código, e o backend default nem o toca; (2) o caminho AHB **já é obrigatório e implementado** no nosso LSFG. Além disso o MediaProjection do LSFG-Android resolve captura de outro app, não o nosso caso. |
| "`winlator_mali` recomenda `BOX64_MMAP32=1`, então o `=0` do GameNative para Mali está errado" | **Não é evidência de conflito.** O fork dele apenas documenta o comportamento genérico de `wow64Mode` (mesmo código que o nosso). Continua **sem medição** dos dois lados: é item de A/B on-device, não de correção. |
| "`winlator_mali` tem config de blacklist de extensão para Mali" | **Falso.** Não há mecanismo; é instrução para desmarcar na UI do Vortex. O equivalente no nosso fork (`blacklistedExtensions` → `WRAPPER_EXTENSION_BLACKLIST`) já existe. |

---

## 4. Itens acionáveis destilados (prioridade — nenhum implementado ainda)

1. **ALTA — caminho de blit no Mali (`wsi-woes`).** É o maior ganho de desempenho plausível
   documentado: em todo Mali o swapchain direto falha e tudo vira blit. Relaciona-se
   diretamente com `resourceType` / `useDRI3` / `MESA_VK_WSI_DEBUG` que já expomos, e as
   correções já estão em `GameNative/mesa` (PRs #6/#7/#8) — ou seja, é verificar se o wrapper
   que distribuímos inclui esses commits.
2. **ALTA — `VK_EXT_extended_dynamic_state` no Mali.** Fonte externa (winlator_mali) aponta
   texturas ausentes em alguns drivers Mali. Ação concreta possível: pré-popular
   `blacklistedExtensions` para Mali no preset/default (`graphicsDriverConfig`), sem código
   novo de motor.
3. **MÉDIA — `gpuss` na leitura de temperatura.** Nosso ranker de GPU
   (`SystemMetricsReader.kt:138-147`) cobre `gpu-silicon/gpu/g3d/kgsl/mali` mas **não** `gpuss`,
   que é como MediaTek/Exynos nomeiam a zona. Correção de 1 linha, com fonte
   (`SocThermalMonitor.kt:263-286`). Pertence à frente **D** (deferida).
4. **MÉDIA — `OpSpecConstant`/`WRAPPER_NO_PATCH_OPCONSTCOMP`.** O `winlator-internals`
   documenta o bug de Mali que o nosso próprio `WRAPPER_NO_PATCH_OPCONSTCOMP` endereça —
   leitura obrigatória antes de mexer nesse caminho.
5. **BAIXA — BCn via compute.** `bcn_layer` + Vortek2 são o material de estudo se a frente B
   evoluir de "excluir compute" para "transcodificar de forma confiável".

---

## 5. Como consultar (sem sujar o repo)

`reference/` está no `.gitignore` — `git status` mostra apenas `?? .prime/`. Para consultas
rápidas a partir da raiz:

```
grep -rn "createContextFromAHB" reference/LSFG-Android/lsfg-vk-android/src
grep -rn "ClipDistance" reference/winlator-internals/_posts
grep -rn "extended_dynamic_state" reference/winlator_mali/README.md
sed -n '1,80p' reference/bcn_layer/src/bcn_layer.cpp
```

Notas de pesquisa commitáveis seguem o precedente de `reference/sdl/gamecontrollerdb-notes.md`
e deste documento (em `docs/`).
