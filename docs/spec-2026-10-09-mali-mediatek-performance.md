# Spec 2026-10-09 — Performance MediaTek/Mali: classificação central, BCN e present modes

**Data:** 2026-10-09
**Origem:** pedido do usuário — melhorar o desempenho no seu aparelho MediaTek a partir de uma
análise externa (produzida por outro modelo) que propunha 5 tarefas. A análise foi **verificada
linha a linha contra o código** e **quatro de suas premissas estavam erradas** — a §1 registra
as correções, porque elas mudam o que vale a pena implementar.
**Escopo aprovado:** frentes **A** (classificação ARM/Mali + copy honesto do LSFG), **B**
(BCN compute no Mali) e **C** (expor present modes). **D** e **E** ficam deferidos (§6).
**Padrões obrigatórios (repo):** lógica pura JVM-testável (`GpuClassification`,
`PresentModeMapping`); strings EN (`values/`) + pt-rBR; commits `tipo(escopo): descrição`
referenciando este spec; nada de locals novos em `XServerScreen.kt` (limite de registry de dex).

---

## 1. Baseline factual — o que a análise de origem errou

| Afirmação da análise | Realidade (verificada) |
|---|---|
| "O padrão ainda é o renderer OpenGL (`GLRenderer`), e o Vulkan precisa ser ativado manualmente" | **Falso.** `Container.DEFAULT_DISPLAY_RENDERER = "vulkan"` (`app/src/main/java/com/winlator/container/Container.java:46`); `PrefManager` devolve `"vulkan"` (`PrefManager.kt:218-223`); `XServerView.java:32` só tem os ramos `vulkan`/`surfaceflinger`. GL entra apenas para virgl ou `displayRenderer=="gl"` (`XServerScreen.kt:2025`). **Não existe gating por vendor no renderer de display** — "forçar Vulkan para Mali" não tem objeto. |
| "LSFG não funciona em Mali provavelmente por falta da `VK_EXT_robustness2`, sem fallback" | **Falso.** robustness2 é **opcional com fallback implementado**: probe em `app/src/main/cpp/lsfg-vk-android/framegen/src/core/device.cpp:149-153`, `.pNext = hasRobustness2 ? &robustness2 : nullptr` (`:378`, `:394`), imagem fallback 1×1 `R8G8B8A8_UNORM` (`:534-538`) consumida em `descriptorset.cpp:104-107`. Nunca lança. **O backend default (nativo, `vkr_lsfg.cpp` + `lsfg::Device`) nem toca robustness2.** A causa-raiz nomeada pelo próprio código é `shaderStorageImageExtendedFormats` ausente em Mali Bifrost/Valhall (`device.cpp:302-312`) + `vulkanMemoryModel` ausente forçando o caminho FP16 (`:320-329`) — e `no_fp16 = false` já é hardcoded (`LsfgVkManager.kt:648`). |
| "A string 'só Adreno 6xx' está no `LsfgVkManager.kt`" | **Falso (arquivo errado).** É só copy: `values/strings.xml:2334`, `:2347` e `values-pt-rBR/strings.xml:1570`, `:1583`. **Nenhum código recusa rodar em Mali:** o gate é `LsfgVkManager.kt:99-113` = container **BIONIC** + toggle salvo + `Lossless.dll` presente. |
| "`presentMode` usa FIFO como padrão" | **Parcial.** Há **dois** present modes independentes: renderer de display = `fifo` (`Container.java:87`); driver do container = `mailbox` (`Container.java:49`). A análise confundiu os dois. |
| "Adicionar `BOX64_MMAP32=0` para Mali" (Tarefa 4) | **Já feito** nos dois launchers: `BionicProgramLauncherComponent.java:257-260`, `GlibcProgramLauncherComponent.java:276-278`. |
| "Instrumentar o HUD com métricas Mali" (Tarefa 5) | **Quase tudo já existe:** `mali0/device/{utilisation,utilization,gpuinfo,temp}` + descoberta de devfreq com token `mali` (`SystemMetricsReader.kt:37-101`, `134-152`) + `GpuUsageSampler` com delta de `gpuinfo` (`:371-417`), já consumido pelo HUD. |

### 1.1 Gaps reais confirmados (o que este spec ataca)

1. **Não há camada central de classificação ARM/Mali.** `GPUInformation.java` só tem helpers
   Adreno (`:157-205`); a detecção de Mali era `renderer.contains("Mali")` — case-sensitive —
   duplicada em 3 sítios: `XServerScreen.kt:6044`, `BionicProgramLauncherComponent.java:257`,
   `GlibcProgramLauncherComponent.java:276`. Há ainda um 4º regex independente em
   `CommunityConfigService.kt:785`.
2. **Bug de Immortalis (consequência de 1):** `"Immortalis-G715"` **não contém** a substring
   `"Mali"`. Logo, em aparelhos Immortalis, `BOX64_MMAP32` ficava `1` (em vez de `0`) e o
   `zink_dlls` era extraído como se o device não fosse ARM Mali (`XServerScreen.kt:6044`).
3. **`excludeBcnCompute` ignora Mali.** `XServerScreen.kt:6098`:
   `isAdreno || (isWrapperGamenative && isXclipse)`. O commit que introduziu isso
   (`17da0b3e`, PR #1673 — *"DX12 support for Mali and Exynos with wrapper-gamenative"*, cuja
   mensagem inclui *"Disable bcn emulation on wrapper-gamenative for xclipse"*) excluiu Adreno
   (todos) e Xclipse (wrapper-gamenative) mas **deixou Mali de fora**, apesar de o PR ser sobre Mali.
4. **UI oferece 2 de 4 present modes do renderer.** `ContainerConfigDialog.kt:226` hardcodava
   `listOf("fifo", "mailbox")`, mas o mapeamento em `XServerScreen.kt:2037-2043` já suportava 4
   (`mailbox→1`, `immediate→0`, `relaxed→3`, else→2) e o caminho nativo faz fallback seguro para
   FIFO (`VulkanRendererContext.cpp:2330-2333`). `immediate`/`relaxed` eram inalcançáveis.
5. **Probe nativo de capacidade LSFG é código morto.** `lsfg_jni.c:44-52`
   `nativeSupportsFrameGeneration` → `lsfg_probe.c:367-385` (checa API 1.3, compute queue,
   formatos, `vulkanMemoryModel`, storage features) **sem nenhum caller** Kotlin/Java; idem
   `VulkanRenderer.isFrameGenerationSupported()` (`VulkanRenderer.java:913-920`). Falhas do LSFG
   chegam **só no logcat** — o usuário apenas vê o FPS não subir.
6. **Comentário obsoleto e enganoso** em `XServerScreen.kt:2021-2024`, afirmando que o padrão é o
   GL renderer e citando um `useLegacyRenderer` que já não existe como caminho de escrita.

## 2. Objetivo

Centralizar a classificação de GPU ARM/Mali em lógica pura testável, corrigir o copy desatualizado
do LSFG, fechar a lacuna de BCN compute no Mali e expor os present modes já suportados pelo código —
**sem regressão em Adreno** e **sem inventar taxonomia que não possamos validar**.

---

## 3. Frente A — classificação central + copy honesto

### A1. `GpuClassification` (novo)

`app/src/main/java/app/gamenative/utils/GpuClassification.kt` — `object` puro, sem dependência
Android, no mesmo padrão JVM-testável de `GamepadStickLogic`/`ShaderPagingLogic`.

API: `isMali(renderer)`, `maliModel(renderer)`, `maliSeries(renderer)`, `vendorFamily(vendorId, renderer)`,
`shouldExcludeBcnCompute(vendorId, renderer, isWrapperGamenative)`; constantes
`VENDOR_QUALCOMM=0x5143`, `VENDOR_ARM=0x13B5`, `VENDOR_SAMSUNG=0x144D`, `VENDOR_IMAGINATION=0x1010`.

- `isMali` é case-insensitive, null-safe e cobre **Mali e Immortalis**.
- `maliModel` reusa a forma de regex já provada em `CommunityConfigService.kt:785`
  (`\b(mali|immortalis)[ -]*([a-z]\d+)\b`), cobrindo `Mali-G57`, `Mali-G78`, `Mali-G57 MC2`,
  `Mali-T860`, `Immortalis-G715`.
- **Decisão de design:** expõe-se **modelo + série** (chave estável e testável), mas **não** se
  inventa um mapa de configuração BCN por geração. Não há device nem medição que o fundamentem;
  criá-lo seria chutar valores. O mapa fica como ponto de extensão nomeado em
  `shouldExcludeBcnCompute`, a ser preenchido quando houver dados on-device (§8).

Aceite: testes JVM cobrindo a matriz de vendors/renderers, incluindo regressão de Adreno/Xclipse.

### A2. Migrar os 3 sítios ad-hoc

- `XServerScreen.kt:6044` → `!GpuClassification.isMali(renderer)`
- `BionicProgramLauncherComponent.java:257-259` → `GpuClassification.INSTANCE.isMali(renderer)`
- `GlibcProgramLauncherComponent.java:276-278` → idem

Equivalência exata para Mali; para Immortalis é **mudança intencional** (§1.2/risco R1).
**Não alterar** `CommunityConfigService.kt:785` — ele produz a chave canônica de lookups
comunitários (`arm:g78`); mexer mudaria chaves de matching existentes.

### A3. Strings do LSFG

`lsfg_description` e `lsfg_install_prompt` em `values/strings.xml` e `values-pt-rBR/strings.xml`
passam a declarar a dependência real (driver Vulkan 1.3 com compute e storage-image) e que
a geração pode não funcionar em alguns drivers **Mali ou PowerVR** — em vez do "apenas Adreno 6xx".

### A4a. Ligar o probe morto — diagnóstico apenas

`LosslessScaling.java`: declara `nativeSupportsFrameGeneration` (a implementação já existia em
`lsfg_jni.c:46-54`, com assinatura idêntica à de `nativeSupportsFp16`) e adiciona
`supportsFrameGeneration(context, driverName)` com cache `ConcurrentHashMap` por driver e
tratamento de `Throwable` (o símbolo pode faltar num `.so` antigo). Chamada em
`LsfgVkManager.applyNativeRuntime` via o `vsyncWriteExecutor` já existente (fora da main thread,
pois o probe cria/destrói uma instância Vulkan), logando o veredito.

**Não** alimenta `isSupported`/`isArmed`: transformar um probe não validado em gate poderia
desligar a geração de quadros que hoje funciona em Adreno.

### A4b. Gate por capacidade — BLOQUEADO (não implementar agora)

Só depois de o probe retornar `true` em Adreno conhecido-bom **e** `false` no device Mali é que o
veredito pode gatear o arming e virar aviso na UI. Sem essa evidência, um probe errado é pior que
o silêncio atual. Ver §8.

---

## 4. Frente B — BCN compute no Mali com `wrapper-gamenative`

**B1.** `XServerScreen.kt:6098` passa a delegar a `GpuClassification.shouldExcludeBcnCompute(...)`,
espelhando a decisão que o PR #1673 tomou para Xclipse. Regra:
Adreno (`0x5143`) sempre excluído; Xclipse (`0x144D`) excluído com wrapper-gamenative;
**Mali/Immortalis excluído com wrapper-gamenative (novo)**; resto `false`.
`WRAPPER_EMULATE_BCN` **não muda** — apenas `ENABLE_BCN_COMPUTE`/`BCN_COMPUTE_AUTO` são gateados,
exatamente como antes.

O sítio não tem `renderer` em escopo (o `val renderer` de `:6041` vive em bloco aninhado), então a
expressão usa `GPUInformation.getRenderer(context)` — a fonte **cacheada** (`PrefManager`), a mesma
que os launchers e os helpers Adreno usam — evitando um segundo probe Vulkan não-cacheado no boot.
Os locals `isAdreno`/`isXclipse` foram removidos (só existiam para essa expressão), o que **reduz**
locais no arquivo com limite de dex.

**B2. Reversão e medição.** A evidência de que Mali precisa disso é **inferência** (o PR excluiu dois
vendors e omitiu Mali), não medição. Portanto: o escape hatch já existente continua valendo
(`bcnEmulationType = software` ou `bcnEmulation = none`), e a manutenção da exclusão exige o
protocolo A/B descrito em §8.

Aceite: comportamento Adreno/Xclipse byte-idêntico (testes de regressão); Mali com wrapper-gamenative
sem `ENABLE_BCN_COMPUTE`; demais combinações inalteradas.

---

## 5. Frente C — expor `immediate`/`relaxed`

`app/src/main/java/app/gamenative/utils/PresentModeMapping.kt` (novo): `toVkPresentMode(mode)`
(única fonte do mapeamento, substituindo o `when` inline de `XServerScreen.kt:2038-2043`) e
`UI_MODES = ["fifo", "mailbox", "immediate", "relaxed"]`.

- `ContainerConfigDialog.kt:226` passa a usar `PresentModeMapping.UI_MODES`.
- **Anexar sempre no fim é obrigatório:** o dropdown deriva o índice do valor persistido
  (`indexOfFirst`, `ContainerConfigDialog.kt:711-714`) e persiste a **string**
  (`rendererPresentMode`); reordenar trocaria silenciosamente a configuração de containers
  existentes. Há teste garantindo que os dois primeiros itens nunca se movam.
- Fallback: modo desconhecido/vazio/ausente → FIFO (`2`), igual ao fallback nativo.

---

## 6. Deferido (com pontos de extensão registrados)

- **D. Métricas MediaTek.** Gaps verificados: **zero leitores** de `/sys/kernel/ged/*` e
  `/proc/gpufreq/*` (MediaTek usa GED, não `mali0`); frequência de GPU existe só no debug
  (`PerfSampler.kt:784-800` já descobre devfreq Mali `cur_freq`), ausente de `MetricsSnapshot` e
  `HudSnapshot` (`PerformanceHudModels.kt:31-48`); throttling existe só pós-hoc
  (`PerfVerdicts.kt:115-141`) e o collector sempre-ativo não registra estado térmico
  (`PerformanceMetricsCollector.kt:153-166`). Casas naturais: `SystemMetricsSources`
  (ponto de descoberta compartilhado documentado, `SystemMetricsReader.kt:18-21`),
  `MetricsSnapshot`, `HudSnapshot`.
- **E. Driver de performance Mali.** `app/src/main/java/app/gamenative/powercontrol/README.md:481`
  já rastreia "⏳ Mali GPU support (non-Samsung)"; hoje cai em `NoOpPerformanceDriver`
  (`PowerManager.kt:187-206`) e `PServerDriver` hardcoda kgsl (`:41`) → `isGpuSupported()` `false`.
  Sem root o ganho é limitado.

---

## 7. Riscos

- **R1 — Immortalis (A2):** `BOX64_MMAP32` passa de `1` para `0` e o `zink_dlls` deixa de ser
  extraído nesses aparelhos. É correção de inconsistência (Immortalis é ARM Mali), mas altera
  devices hoje não cobertos — validar on-device; revertível em 1 linha por sítio.
- **R2 — dex limit:** `XServerScreen.kt` está no limite do registry de dex. B1 e C2 **reduzem**
  código/local; não introduzir locals novos.
- **R3 — B é inferência:** daí o protocolo A/B (§8) e a revertibilidade serem obrigatórios.
- **R4 — `wrapper-gamenative` é blob binário baixado** (`graphics_driver_download.json` →
  `wrapper-gamenative-20260724.tzst`); nenhum env var `WRAPPER_*`/`BCN_*` tem contraparte em fonte
  no repo. O único lever é variável de ambiente — mudanças de internals do wrapper estão fora.
- **R5 — A4b bloqueado por design** (§3, A4b).

---

## 8. On-device pendente (nenhum device adb conectado no momento da implementação)

1. Legibilidade e valores reais dos nós Mali no DDK do aparelho alvo; se o kernel expõe
   `/sys/class/misc/mali0/device/`.
2. Veredito real de `supportsFrameGeneration` no device (A4a loga; com ele, decidir A4b).
3. Protocolo A/B de BCN: mesmo jogo/cena com BCN compute ON vs OFF, capturando `metrics-*.jsonl`
   (sempre ativo) e o relatório de debug (`PerfSampler`, só em debug run) para FPS/p95/slow-frames.
   **Critério:** manter a exclusão só se OFF ≥ paridade e sem regressão; senão reverter B1.
4. Mudança de comportamento em Immortalis (R1).
5. Present mode `immediate`/`relaxed` sem tearing perceptível no device (escolha do usuário; o
   fallback nativo já protege o caso de modo não suportado).

---

## 9. Verificação executada

- `JAVA_HOME=/home/marcos/android-studio/jbr ./gradlew :app:testModernDebugUnitTest --tests "*GpuClassification*" --tests "*PresentModeMapping*" --no-configuration-cache`
  → **18 testes, 0 falhas, 0 erros** (`GpuClassificationTest` 14, `PresentModeMappingTest` 4).
  Evidência: `app/build/test-results/testModernDebugUnitTest/TEST-app.gamenative.utils.{GpuClassification,PresentModeMapping}Test.xml`.
- `compileModernDebugKotlin` + `compileModernDebugJavaWithJavac` verdes (os sítios Java chamam o
  `object` Kotlin via `GpuClassification.INSTANCE`).
- **Notas de toolchain (divergem do `AGENTS.md`):** `JAVA_HOME` é `/home/marcos/android-studio/jbr`
  (o `AGENTS.md` aponta `/home/annapaula/...`, inexistente nesta máquina) e `java` não está no PATH.
  `--no-configuration-cache` é necessário porque o build já tem 6 problemas **pré-existentes** de
  configuration cache nas tasks `compileLibrashader*`/`copyLibrashader*` — não relacionados a esta
  mudança.
