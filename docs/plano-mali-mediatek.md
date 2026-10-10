# Plano de ataque — desempenho Mali/MediaTek no fork GameNative

**Data:** 2026-10-09
**Base:** `docs/spec-2026-10-09-mali-mediatek-performance.md` (frentes A/B/C entregues) +
`docs/referencias-externas-mali.md` (clones de estudo em `reference/`).
**Natureza deste documento:** roadmap de execução. Cada item traz evidência `file:line`, mudança
concreta, arquivos, aceite, risco e esforço — para ser executado sem novas decisões de design.
**Ordem de leitura:** §1 estado atual → §2 correção que muda o plano → §3 frentes → §4 priorização
→ §5 ondas.

---

## 1. Estado atual

**Entregue e commitado:**

| Commit | Conteúdo |
|---|---|
| `e4222aae` | Frentes A/B/C: `GpuClassification` (bug de Immortalis), BCN Mali, `PresentModeMapping` (immediate/relaxed), probe LSFG exposto, strings honestas |
| `9272c57c` | Clones de estudo + `docs/referencias-externas-mali.md` |

**Clones disponíveis em `reference/`** (2,4 GB, ignorados pelo git — `.gitignore:89`):

| Repo | Papel no plano |
|---|---|
| `GameNative-mesa` (branch `wrapper-25`) | ⭐ **Fonte do wrapper que distribuímos** (`wrapper-gamenative`). Contém `wrapper_bcn_transcode.comp` |
| `Pipetto-mesa` (`wrapper-25`), `mesa-wrapper-CI` | Variantes/precursor do wrapper; comparação |
| `winlator-internals` | Workarounds de Mali (Vortek, WSI, OpSpecConstant, ClipDistance) |
| `winlator_mali`, `WinlatorMali`, `winlator-upstream` | Linhagem Winlator: env vars de Mali e código original do BCN |
| `LSFG-Android` (+ submódulo `lsfg-vk-android`) | Patch AHB; referência para LSFG |
| `bcn_layer`, `Granite` | BCn por compute shader |
| `FPS-Meter-Android` | Telemetria (thermal zones, dumpsys) |
| `vortek-deep-dive`, `compat_layer`, `lsfg-vk-upstream` | Apoio |

---

## 2. Correção que muda o plano (descoberta na verificação dos clones)

### 2.1 `ENABLE_BCN_COMPUTE` **não é lido** pelo wrapper que o Mali usa

`ENABLE_BCN_COMPUTE` / `BCN_COMPUTE_AUTO` — os env vars que a frente B gateia — **não existem em
nenhum arquivo** de:

- `reference/GameNative-mesa` (a fonte do `wrapper-gamenative`) → **0 ocorrências**
- `reference/Pipetto-mesa` → **0**
- `reference/mesa-wrapper-CI` → **0**
- `reference/Granite`, `winlator_mali`, `winlator-upstream` → **0**

Elas aparecem apenas em
`reference/WinlatorMali/app/src/main/java/com/winlator/cmod/XServerDisplayActivity.java:2345,2352`,
**no mesmo código que herdamos** do Winlator (inclusive o `!= 0x5143` original que o PR #1673
refatorou em `excludeBcnCompute`) — ou seja, são **legado** da linhagem, e o fork GameNative as
carrega sem que o driver as consuma.

**Confirmado no binário** (§2.4): `ENABLE_BCN_COMPUTE` está **ausente** do
`libvulkan_wrapper.so` dentro do `wrapper-gamenative.tzst` que distribuímos. Não é mais inferência.

**Consequência honesta:** a mudança B1 do commit `e4222aae` é **correta e inofensiva, mas é no-op
no Mali com `wrapper-gamenative`** — ela altera quais env vars são exportadas, e o driver as ignora.
Não entrega ganho de desempenho. Isso **não invalida** a centralização da classificação (frente A,
que corrigiu um bug real de Immortalis), mas a motivação "fechar a lacuna de BCN do PR #1673" não se
sustenta como *tuning*. A §3-H reframeia o assunto com os levers que **são** lidos.

### 2.2 O fix de WSI para Mali está no wrapper, mas **desligado por padrão**

`reference/GameNative-mesa/src/vulkan/wrapper/wrapper_device_memory.c:542-553`:

```c
static int bypass_swapchains = -1;
if (bypass_swapchains == -1)
   bypass_swapchains = getenv("WRAPPER_BYPASS_SWAPCHAIN_PLACED") ?
                       atoi(getenv("WRAPPER_BYPASS_SWAPCHAIN_PLACED")) : 0; // TODO: turn on by default if safe
```

- **Default = 0 (desligado)**, com um `TODO` do próprio autor: *"turn on by default if safe"*.
- **Nosso fork nunca seta `WRAPPER_BYPASS_SWAPCHAIN_PLACED`** (verificado: zero ocorrências em `app/src`).

Por que isso importa muito: `winlator-internals/_posts/2026-07-28-wsi-woes-mali.md` documenta que em
**todo** Mali a importação de AHB `BGRA_8888` é ilegal → o swapchain externo falha → **tudo cai no
caminho de blit**, um penhasco de desempenho. O bypass evita a emulação de `VK_EXT_map_memory_placed`
justamente para imagens de swapchain (marcadas por `is_wsi_image`, ver
`GameNative-mesa/src/vulkan/wrapper/wrapper_device.c:1717,1728`).

### 2.3 Cadeia causal que ninguém tinha conectado

1. `BionicProgramLauncherComponent.java:260` força `BOX64_MMAP32=0` no Mali.
2. `:262-263` só define `WRAPPER_DISABLE_PLACED=1` quando `BOX64_MMAP32==1 && !arm64ec` → **no Mali
   essa variável não é setada**.
3. Sem `WRAPPER_DISABLE_PLACED`, `wrapper_physical_device.c:224-235` **expõe**
   `VK_EXT_map_memory_placed` + `memoryMapPlaced`.
4. Com a extensão exposta, o Mali entra no caminho de emulação de `map_memory_placed`
   (`wrapper_device_memory.c:526-527`), que é exatamente o caminho que a §2.2 contorna.

Ou seja: a configuração atual do Mali **maximiza** a exposição ao caminho problemático. É a hipótese
de maior valor do plano.

### 2.4 Verificação nos **binários que distribuímos** (não só no fonte)

Extraí os assets e chequei `strings` nos `libvulkan_wrapper.so` reais. Isso separa "o fonte suporta"
de "o que shipamos suporta" — e a diferença é grande:

| Env var | `wrapper-gamenative.tzst` (blob, **jul/2026** — default do Mali) | `libvulkan_wrapper.tar.xz` (legacy, **ago/2025**) |
|---|---|---|
| `WRAPPER_BYPASS_SWAPCHAIN_PLACED` | ✅ **presente** | ❌ ausente |
| `WRAPPER_DISABLE_PLACED` | ✅ presente | ✅ presente |
| `WRAPPER_EXTENSION_BLACKLIST` | ✅ presente | ✅ presente |
| `WRAPPER_EMULATE_BCN` | ✅ presente | ❌ ausente |
| `WRAPPER_BCN_ASTC` / `_GPU` / `_GPU_CAP_MB` | ✅ presente | ❌ ausente |
| `WRAPPER_BCN_POLICY` | ❌ **ausente** | ❌ ausente |
| `WRAPPER_NO_REMOVE_CLIP_DISTANCE` | ✅ presente | ❌ ausente |
| `WRAPPER_NO_PATCH_OPCONSTCOMP` | ✅ presente | ✅ presente |
| `ENABLE_BCN_COMPUTE` | ❌ **ausente** | ❌ ausente |

**Conclusões que mudam o plano:**

1. **F1 é executável já** — o `WRAPPER_BYPASS_SWAPCHAIN_PLACED` existe no blob que shipamos. Não
   precisa rebuild de wrapper, só setar a variável. É o item de melhor relação valor/custo.
2. **§2.1 provada no binário:** `ENABLE_BCN_COMPUTE` não existe no blob → a frente B é no-op no Mali.
3. **`WRAPPER_BCN_POLICY` exige bump de wrapper** (está no fonte, não no blob). H2/H4 dependem disso.
4. **O asset legacy está ~11 meses atrasado** em relação ao blob (ago/2025 vs jul/2026): quem usa
   o driver `wrapper`/`legacy` não tem nem BCN-ASTC nem o bypass de WSI. Corrigir o asset é uma
   frente por si só.
5. `WRAPPER_NO_REMOVE_CLIP_DISTANCE` presente no blob → existe alavanca caso a remoção de
   `ClipDistance` (necessária no Mali) cause glitches visuais.

**Reproduzir** (da raiz do repo):

```bash
mkdir -p /tmp/bincheck && cd /tmp/bincheck
tar -xJf <repo>/app/src/legacy/assets/graphics_driver/libvulkan_wrapper.tar.xz
zstd -dc <repo>/app/src/legacy/assets/graphics_driver/wrapper-gamenative.tzst | tar -xf -
for s in WRAPPER_BYPASS_SWAPCHAIN_PLACED ENABLE_BCN_COMPUTE WRAPPER_BCN_POLICY; do
  printf '%-34s %s\n' "$s" "$(grep -qa "$s" usr/lib/libvulkan_wrapper.so && echo PRESENTE || echo ausente)"
done
```

---

## 3. Frentes de trabalho

### Frente F — WSI / caminho de blit no Mali (P0, maior ganho plausível)

| # | Ação | Evidência | Arquivos |
|---|---|---|---|
| **F1** | Definir `WRAPPER_BYPASS_SWAPCHAIN_PLACED=1` quando Mali + `wrapper-gamenative`. **Confirmado presente no blob que shipamos (§2.4) — executável já, sem rebuild** | §2.2, §2.4 | `XServerScreen.kt` (bloco de env vars ~`:6090`) |
| **F2** | Decidir `WRAPPER_DISABLE_PLACED=1` no Mali (hoje inalcançável por causa de MMAP32=0) — **A/B obrigatório** | §2.3; `wrapper_physical_device.c:224-235` | `BionicProgramLauncherComponent.java:257-263`, `GlibcProgramLauncherComponent.java:276-278` |
| **F3** | Registrar/verificar qual snapshot do `GameNative/mesa` o blob `wrapper-gamenative-20260724.tzst` corresponde | `graphics_driver_download.json`; CI em `GameNative-mesa` PR #16 | `app/src/main/assets/graphics_driver_download.json` |
| **F4** | Validar a emulação BGRA-via-AHB (habilita *direct rendering* no Mali, driblando os bugs de blit) | `wrapper_device.c:1717,1728`; internals PR #6 | nenhum (validação) |
| **F5** | Logar qual caminho WSI foi escolhido (direct vs blit) para tornar o penhasco atribuível | log já existe: `"Bypassing EXT_map_memory_placed emulation for swapchain image"` | integrar ao JSONL de métricas |
| **F6** | **Atualizar o asset legacy** (`libvulkan_wrapper.tar.xz`, ago/2025) para paridade com o blob `wrapper-gamenative` (jul/2026) — hoje ele não tem BCN-ASTC, bypass de WSI nem `WRAPPER_NO_REMOVE_CLIP_DISTANCE` | §2.4 | `app/src/legacy/assets/graphics_driver/libvulkan_wrapper.tar.xz`, `graphics_driver_download.json` |

**Aceite F1:** no logcat do device aparece `Bypassing EXT_map_memory_placed emulation for swapchain
image`; A/B de FPS/p95 via `metrics-*.jsonl` sem regressão.
**Risco:** baixo — é o caminho recomendado pelo autor; reversível por env var.

### Frente G — Extensões e preset Mali

| # | Ação | Evidência | Arquivos |
|---|---|---|---|
| **G1** | Pré-popular `blacklistedExtensions` com `VK_EXT_extended_dynamic_state` no Mali | fonte: `reference/winlator_mali/README.md:57`; consumo: `wrapper_physical_device.c:123-133` (match por **substring**); default hoje **vazio** (`Container.java:49`) | `Container.java:49`, `ContainerUtils.kt` (defaults por device) |
| **G2** | Usar `GPUInformation.enumerateExtensions` — **código morto** (único hit é a declaração, `GPUInformation.java:221`; impl. nativa existe) — para decidir blacklist/capacidade por device em vez de por nome | `GPUInformation.java:221`; commit `ce241623` (enumeração robusta) | `GPUInformation.java`, `XServerScreen.kt` |
| **G3** | Preset **"Mali Otimizado"** consolidando os levers validados: blacklist (G1), `WRAPPER_BYPASS_SWAPCHAIN_PLACED=1` (F1), `mesa_glthread=false`, `BOX64_SAFEFLAGS=2`, `WINEDEBUG=-all`, DXVK adequado | `winlator_mali/README.md:70-78` | `ContainerUtils.kt`, `graphicsDriverConfig` |

**Risco G1/G3:** médio — `VK_EXT_extended_dynamic_state` é usado pelo DXVK; remover em device que
não precisa pode **piorar**. Por isso G2 (capacidade real) vem antes de G1 virar default.

### Frente H — BCN de verdade (reframada pela §2.1)

| # | Ação | Evidência | Arquivos |
|---|---|---|---|
| **H1** | Corrigir o entendimento e decidir o destino de `ENABLE_BCN_COMPUTE` no caminho Mali (manter como está — é inofensivo — ou remover para não sugerir tuning falso) | §2.1 | `XServerScreen.kt:6103-6121`, `GpuClassification.kt:90-104` |
| **H2** | Expor os levers **que são lidos e estão no blob**: `WRAPPER_BCN_ASTC` (default **1** = transcode p/ ASTC, nativo no Mali), `WRAPPER_BCN_GPU` (nosso fork seta a partir de `transcoder`, default `cpu`), `WRAPPER_BCN_GPU_CAP_MB` (default 128 MB). ⚠️ **`WRAPPER_BCN_POLICY` está no fonte mas NÃO no blob (§2.4)** — expô-lo exige bump de wrapper | `wrapper_bcdec.c:21-56,70`; `wrapper_device.c:4105-4108,4185-4188`; §2.4 | `GraphicsTab.kt` (UI), `XServerScreen.kt` |
| **H3** | Confirmar como o transcode GPU é acionado (`wrapper_bcn_transcode.comp` existe só no `GameNative-mesa`) | `GameNative-mesa/src/vulkan/wrapper/wrapper_bcn_transcode.comp` | investigação |
| **H4** | Só depois de medição: mapa por geração (G57/G68/G77/G78/Immortalis) usando `GpuClassification.maliModel/maliSeries` — a chave já está pronta e testada | `GpuClassification.kt:61-71` | `GpuClassification.kt` |

**Nota:** `WRAPPER_BCN_POLICY` é **por formato**, não por GPU — continua sendo o mecanismo para o
"mapa por geração" que o usuário queria, mas a decisão por geração seria nossa (chave → policy).

### Frente I — Telemetria MediaTek (era frente D, deferida)

| # | Ação | Evidência |
|---|---|---|
| **I1a** | **Excluir zonas `-step`** do ranking de temperatura (CPU e GPU). Zonas `*-step`/`*-step-*` são *trip points* de mitigação térmica, **não leituras de temperatura** — mas casam com os nossos `contains("cpu")`/`contains("gpu")` e podem ser escolhidas no lugar do sensor real | fonte: `reference/FPS-Meter-Android/.../SocThermalMonitor.kt:235` (`type.endsWith("-step") \|\| contains("-step-") \|\| contains("avg-step") \|\| contains("max-step")`); nosso: `SystemMetricsReader.kt:111-124,138-147` (sem esse guarda) |
| **I1b** | **`soc` isolado não é temperatura.** Em Qualcomm, uma zona chamada exatamente `soc` é *State of Charge* da bateria (**%**), e o nosso ranker de CPU a mapeia para rank 3 (`contains("soc")`) — podendo reportar ~85 % como 85 °C. Guarda necessária: rejeitar `soc`/`soc-step` isolados, **mantendo** `soc_thermal`/`soc-thermal`/`soc_max`/`soc-temp` (que em MediaTek são temperatura legítima) | fonte: `SocThermalMonitor.kt:230-232` e `isValidSocZoneType` `:264-269`; nosso: `SystemMetricsReader.kt:116` e o check de faixa `:191-200` |
| **I1c** | **Nada a fazer com `gpuss`.** Verifiquei: `"gpuss".contains("gpu")` é `true`, então `gpuss` **já** é coberto pelo rank 1 (`SystemMetricsReader.kt:141`). Registrado aqui para que ninguém "conserte" um não-problema | `SystemMetricsReader.kt:141` |
| **I2** | Leitores MediaTek: `/sys/kernel/ged/*`, `/proc/gpufreq/*` — **zero leitores hoje** | `SystemMetricsReader.kt:45-101` |
| **I3** | Frequência de GPU no caminho **live** (HUD + `MetricsSnapshot`) — hoje só no debug (`PerfSampler.discoverGpuFreqPath`) | `PerfSampler.kt:784-800`; `PerformanceHudModels.kt:31-48` |
| **I4** | Throttling no caminho live (thermal status/headroom) — hoje só pós-hoc | `PerfVerdicts.kt:115-141`; `PerformanceHudModels.kt` (novo `MetricId`) |
| **I5** | Parse de contadores devfreq `busy_time`/`total_time` (o `GpuBusyDelta` só entende o par kgsl) | `SystemMetricsReader.kt:316-365` |

Casa natural: `SystemMetricsSources` (ponto de descoberta compartilhado documentado,
`SystemMetricsReader.kt:18-21`) → alimenta HUD e collector de uma vez.

### Frente J — Driver de performance Mali (era frente E, deferida)

| # | Ação | Evidência |
|---|---|---|
| **J1** | `MaliPerformanceDriver` em `powercontrol/drivers/` — peer de `PServerDriver`; devfreq `cur_freq`/`available_frequencies`/`scaling_governor`, escritas só se graváveis | `PServerDriver.kt:41` (hardcoda kgsl), `:135-137`; `PerformanceDriver.kt:15-222` |
| **J2** | Wire em `PowerManager.initialize` (hoje cai em `NoOpPerformanceDriver`) | `PowerManager.kt:187-206` |
| **J3** | Integrar a `DeviceGate`/`ClusterTuner` (o `TUNE G%` fica vazio no Mali hoje) | `PowerManager.kt:400-410,710` |
| **J4** | Reconhecer a pendência já rastreada no repo | `powercontrol/README.md:481` |

**Nota:** sem root, o ganho é limitado (majoritariamente leitura). Esforço alto — último da fila.

### Frente K — Validação on-device (bloqueia F/G/H/I)

| # | Pendência | Origem |
|---|---|---|
| **K1** | **Conseguir acesso adb a um device MediaTek** — hoje nenhum conectado; bloqueia quase tudo | `reference`+spec §8 |
| **K2** | Immortalis: `BOX64_MMAP32` 1→0 e skip de `zink_dlls` (mudança do `e4222aae`) | spec §8.4 |
| **K3** | Veredito do probe LSFG (log `LosslessScaling`) → destrava ou descarta A4b | spec §3-A4b |
| **K4** | `immediate`/`relaxed` sem tearing | spec §8.5 |
| **K5** | A/B de BCN — **reframado**: testar `WRAPPER_BCN_*` (que são lidos), não `ENABLE_BCN_COMPUTE` | §2.1 |

---

## 4. Priorização

| Frente | Valor | Esforço | Risco | Depende de |
|---|---|---|---|---|
| **F1** — `WRAPPER_BYPASS_SWAPCHAIN_PLACED=1` no Mali | **Alto** (ataque ao penhasco de blit) | **Baixo** (1 env var) | Baixo | K1 |
| **I1a/I1b** — excluir zonas `-step` e o `soc` isolado (%). **Bug real de telemetria**, não só de Mali | **Alto** (temperatura errada leva a decisão de tuning errada) | **Muito baixo** (poucas linhas) | Nenhum | — |
| **H1** — corrigir/decidir `ENABLE_BCN_COMPUTE` | Médio (honestidade do tuning) | Muito baixo | Nenhum | — |
| **G1** — blacklist `extended_dynamic_state` no Mali | Médio (texturas ausentes) | Baixo | Médio | G2, K1 |
| **F2** — `WRAPPER_DISABLE_PLACED` no Mali | Alto | Baixo | Médio | K1 |
| **G3** — preset "Mali Otimizado" | Médio (consolida) | Médio | Baixo | F1, G1 |
| **G2** — usar `enumerateExtensions` (dead code) | Médio (decisão por capacidade) | Médio | Baixo | — |
| **H2** — expor `WRAPPER_BCN_*` na UI | Médio | Médio | Baixo | — |
| **F3/F4/F5** — versão do wrapper, direct rendering, log WSI | Médio | Médio | Baixo | K1 |
| **F6** — atualizar o asset legacy (11 meses atrasado vs o blob) | Médio (afeta quem usa `wrapper`/`legacy`) | Médio | Baixo | — |
| **I2–I5** — GED/gpufreq, freq, throttling | Médio | Médio | Baixo | K1 |
| **H4** — mapa BCN por geração | Baixo até medir | Médio | Médio | K1, K5 |
| **J1–J4** — driver de performance Mali | Baixo sem root | **Alto** | Alto | K1 |

---

## 5. Ondas de execução

**Onda 0 — sem device (pode começar já)**
1. `I1a`/`I1b` — excluir zonas `-step` e o `soc` isolado do ranking térmico (bug de telemetria
   válido para Qualcomm **e** MediaTek; poucas linhas). Não confundir com `gpuss`, que já funciona (I1c).
2. `H1` — corrigir docs/código sobre `ENABLE_BCN_COMPUTE` (evita que eu ou outra sessão
   "otimize" uma variável ignorada).
3. `G2` — investigar `enumerateExtensions` e desenhar a decisão por capacidade.
4. `F3` — mapear o blob `wrapper-gamenative` ↔ commit do `GameNative/mesa`.
5. `H3` — entender o transcode GPU (`wrapper_bcn_transcode.comp`).

**Onda 1 — requer device (K1)**
6. `F1` → medir. É o item de maior valor e menor custo; medir **primeiro**.
7. `F2` → A/B controlado.
8. `K2`, `K4` (pendências do commit anterior).
9. `F5` → instrumentar o log de WSI para atribuir o penhasco.

**Onda 2 — consolidação**
10. `G1` + `G3` (preset Mali com o que foi validado).
11. `H2` (expor `WRAPPER_BCN_*`).
12. `I2`–`I5` (telemetria MediaTek).
13. `K3`, `K5`.

**Onda 3 — maior esforço**
14. `H4` (mapa BCN por geração, agora com dados).
15. `J1`–`J4` (driver de performance).

**Regra de ouro:** nenhum item de tuning (F/G/H) vira default sem o A/B correspondente registrado no
spec — a §2 mostra que assumptions de tuning neste domínio erram com facilidade.

---

## 6. Pré-requisitos e toolchain

- **Device MediaTek com adb** (K1) — hoje ausente; bloqueia Ondas 1–3.
- Build: `JAVA_HOME=/home/marcos/android-studio/jbr ./gradlew :app:assembleModernDebug`
  (o `AGENTS.md` aponta `/home/annapaula/...`, inexistente nesta máquina).
- Testes: `:app:testModernDebugUnitTest --tests "*Filtro*" --no-configuration-cache`
  (o `--no-configuration-cache` é necessário por 6 problemas pré-existentes nas tasks `librashader`).
- `~/.gradle` (5,1 GB) está fora do sandbox de escrita do workspace.

---

## 7. Riscos transversais

1. **Assumption de tuning erra fácil** — `ENABLE_BCN_COMPUTE` (§2.1) é o exemplo vivo: uma variável
   gateada por qualidade, mantida por duas gerações de fork, que o driver ignora. Todo item de
   tuning precisa de evidência de consumo no wrapper **e** medição.
2. **`wrapper-gamenative` é blob** — o único lever a partir do nosso código são **env vars**; mudar
   o wrapper exige build no `GameNative/mesa` e novo asset + manifesto.
3. **`VK_EXT_extended_dynamic_state`** — remover ajuda alguns Mali e pode custar desempenho em
   outros; nunca default sem capacidade real (G2).
4. **`WRAPPER_BYPASS_SWAPCHAIN_PLACED` tem `TODO: turn on by default if safe`** — o autor não tinha
   certeza. Tratar como hipótese a medir, não como verdade.
5. **Sem device, tudo isto é estático** — e este domínio já mostrou 4 premissas erradas na origem.

---

## 8. Mapa: frente → referência de consulta

| Frente | Consultar em `reference/` |
|---|---|
| F (WSI/blit) | `winlator-internals/_posts/2026-07-28-wsi-woes-mali.md`; `GameNative-mesa/src/vulkan/wrapper/wrapper_device_memory.c`; `wrapper_device.c` (BGRA/AHB, `is_wsi_image`) |
| G (extensões) | `winlator_mali/README.md:49-78`; `GameNative-mesa/.../wrapper_physical_device.c:123-133,224-235` |
| H (BCN) | `GameNative-mesa/.../wrapper_bcdec.c`, `wrapper_bcn_transcode.comp`; `bcn_layer/src/*` + `Granite`; `winlator-internals/_posts/2025-06-02-Vortek2.md` |
| I (telemetria) | `FPS-Meter-Android/.../SocThermalMonitor.kt`, `SurfaceFlingerFpsMonitor.kt`, `DeviceDiagnosticsScreen.kt` |
| J (driver) | `winlator_mali/README.md` (governors); nosso `powercontrol/README.md:481` |
| LSFG (K3) | `LSFG-Android/lsfg-vk-android/src/context.cpp`; `lsfg-vk-upstream` |
| OpSpecConstant | `winlator-internals/_posts/2025-08-10-OpCompositeConstant.md` + nosso `WRAPPER_NO_PATCH_OPCONSTCOMP` (`XServerScreen.kt:5983`) |

---

## 9. Status após a Onda 0 (2026-10-10)

A Onda 0 (itens sem device) foi executada. Registro completo — mudanças, investigações e
verificação — em **`docs/plano-mali-mediatek-onda0-impl.md`**.

| Item da Onda 0 | Resultado |
|---|---|
| I1a/I1b — trip points `*-step` e `soc` isolado | Entregue (`ThermalZoneClassification` + testes) |
| I1c — `gpuss` | Confirmado como não-problema; correção do doc de referências |
| H1 — `ENABLE_BCN_COMPUTE` | Mantido como inofensivo/no-op, com comentário e correção no spec §4.1 |
| G2 — `enumerateExtensions` / decisão por capacidade | Design registrado; **G1 reframeado** (abaixo) |
| F3 — proveniência do blob | `b8663654f7ea` (`GameNative/mesa`, `wrapper-25`); doc dedicado |
| H3 — transcode BCN por GPU | Fluxo mapeado; `WRAPPER_BCN_GPU_CAP_MB` é o lever novo seguro de expor |
| H2 — expor levers de BCN | `WRAPPER_BCN_GPU_CAP_MB` e `WRAPPER_BCN_ASTC` (acoplado: ASTC off ⇒ GPU off) expostos; POLICY fora (ausente do blob). Sem mudar defaults |
| I5 — contadores devfreq | `busy_time`/`total_time` de nós GPU alimentam o `GpuBusyDelta`; testes puros do delta. Verificação on-device pendente |
| I2 (parcial) — leitores MediaTek | paths de **utilização** de GPU GED/Mali kbase + `gpu_loading` no scan devfreq (verificados na referência). Frequência/temperatura GED pendentes (nomes não verificados) |
| I3 — clock da GPU no caminho live | Leitura compartilhada em `SystemMetricsSources`; `gpuFreqMhz` no `MetricsSnapshot`/JSONL e métrica de HUD (off por padrão). On-device pendente |
| I4 — throttling no caminho live | `thermalStatus` no `MetricsSnapshot`/JSONL + métrica de HUD (off por padrão); rótulos puros testados. On-device pendente |

### 9.1 Correção da Frente G (o que a Onda 0 mudou)

O **G1** ("pré-popular `blacklistedExtensions` com `VK_EXT_extended_dynamic_state` no Mali") foi
escrito a partir do `winlator_mali`, que descreve o **Vortex**. Verificado no binário que
shipamos: o `wrapper-gamenative` **já decide** isso no nível nativo e ciente do engine
(`wrapper_physical_device.c:355-390`) — em ARM Mali ele **desliga** `..._state`/`..._state2` para
engines não-D3D e **mantém** para DXVK/vkd3d. Portanto:

- para jogos Vulkan nativos, a blacklist é **no-op**;
- para DXVK/vkd3d, ela **contradiz** o wrapper e pode custar desempenho.

**G1 deixa de ser "pré-popular por ser Mali"** e vira **opt-in por modelo medido** (a tabela
curada por `maliModel`/`maliSeries` continua vazia até haver medição). Detalhes e desenho em
`plano-mali-mediatek-onda0-impl.md` §4.2.

### 9.2 O que continua pendente

**F1** (`WRAPPER_BYPASS_SWAPCHAIN_PLACED=1` no Mali) segue sendo o item de maior valor e menor
custo, e continua bloqueado por device (K1). Nada de tuning virou default — a regra de ouro da §5
foi respeitada.

### 9.3 Correção da Frente F (F6, verificada)

A conclusão 4 da §2.4 e o item **F6** confundem dois assets diferentes:

- **`libvulkan_wrapper.tar.xz`** (o arquivo nomeado no F6) **não é consumido por nenhum caminho
  do app** — a extração usa `graphics_driver/<driverId>.tzst`, e o id `libvulkan_wrapper` nunca é
  usado. Atualizá-lo é **no-op**.
- **`wrapper-gamenative.tzst`** (legacy) **já é** o blob jul/2026 — nada a corrigir.
- Os assets realmente atrasados são `wrapper.tzst`, `wrapper-v2.tzst`, `wrapper-legacy.tzst` e
  `wrapper-leegao.tzst` (verificados token a token); atualizá-los exige rebuild de cada variante
  e **publicação no `downloads.gamenative.app`** + bump do manifesto — infraestrutura, não código
  deste repo.

Detalhes e a tabela de tokens em `plano-mali-mediatek-onda0-impl.md` §8.

### 9.4 O que falta (só destrava com device ou decisão do usuário)

- **F1/F2/F4/F5** (WSI): tuning/validação no device; F1 exige A/B (regra de ouro §5).
- **G1/G3** (blacklist/preset): o wrapper já decide `extended_dynamic_state` (ver §9.1), então
  só por modelo **medido**.
- **H4** (mapa BCN por geração): só com medição on-device.
- **I2 (frequência/temperatura GED)**: nomes de nó não verificados em referência → precisa de
  device MediaTek; temperatura deste A14 é bloqueada ao adb.
- **J1–J4** (driver de performance Mali): esforço alto e ganho limitado sem root — último da
  fila. `J3` não é mecânico: `PowerManager.startClusterTuning` é tipado em `PServerDriver`
  (`PServerDriver.CpuCluster`), então integrar o tuner a um driver Mali exige refatorar o
  tuner para uma abstração comum — decisão de arquitetura, não um patch. Ver 2026-10-10 abaixo.
- **K2–K5**: pendências on-device herdadas do commit `e4222aae`.

Já entregues (device-independent): I1a/I1b/I1c, H1, G2, H2, F3, F6 (reescopado), I5, I2
(utilização), I3, I4.

---

## 10. Device conectado em 2026-10-10 (não é o MediaTek de K1)

Durante a execução da Onda 0 apareceu um device via adb:

| Item | Valor |
|---|---|
| Modelo | Samsung **SM-A146M** (Galaxy A14 5G), Android 15 |
| SoC | Exynos 1330 (`s5e8535`) — **não** MediaTek |
| GPU | **ARM Mali-G68** (driver `v1.r38p1`, driverID 9 = ARM proprietary) |
| Vulkan | 1.3; expõe `VK_EXT_extended_dynamic_state` e `..._state2`; **não** expõe `VK_EXT_map_memory_placed`; ASTC HDR presente |
| Root | não (`su` ausente) |
| `app.gamenative` | instalado (v0.0.1/21, 2026-08-16 — anterior ao fork atual), **não-debuggable** e
  assinado por um **keystore de debug de outra máquina** (`CN=Android Debug`, SHA-256
  `edd1fdec867407f65aa071ddd12f05c128118a8fd909d9d333de04a93e450ddf`) — não é o
  `~/.android/debug.keystore` desta máquina (`47332f43…`). Logo `adb install -r` do nosso
  build falha por assinatura; atualizar in-place exigiria aquele keystore |
| sysfs térmico | **negado** para o usuário `shell`; as métricas gravadas mostram `cpuTempC`/`gpuTempC` **null** |

O que isso destrava e o que não destrava:

- **Destrava parcialmente** as frentes **Mali** (F/G/H/J): é hardware Mali real, e o `cmd gpu vkjson`
  confirma a premissa de G2 (o driver base anuncia `extended_dynamic_state{,2}`; o wrapper os
  desliga para engines não-D3D).
- **Não destrava** as frentes **MediaTek** (I2–I5: GED/gpufreq) nem a verificação térmica — este
  device é Exynos e bloqueia o sysfs térmico para o adb.
- O A/B on-device de F1/F2/G1 continua exigindo **um jogo rodando** (e a coleta de
  `metrics-*.jsonl`), o que é interativo. O `libvulkan_wrapper.so` só é carregado quando um
  container é lançado.
