# Spec de implementação — Performance MediaTek/Mali (frentes A, B, C)

**Data:** 2026-10-09
**Base:** `docs/spec-2026-10-09-mali-mediatek-performance.md` (aprovado).
**Status:** implementado; **18 testes JVM verdes** (0 falhas, 0 erros);
`compileModernDebugKotlin` + `compileModernDebugJavaWithJavac` OK.
**Verificação on-device:** **pendente — nenhum device adb conectado** (§9 do spec e §4 abaixo).

---

## 1. O que foi implementado (evidências file:line)

| Peça | Arquivo | Detalhe |
|---|---|---|
| Classificação pura ARM/Mali | `app/src/main/java/app/gamenative/utils/GpuClassification.kt` (novo) | `GpuVendor` (`:15`); `isMali` (`:49`, case-insensitive, null-safe, cobre **Mali + Immortalis**); `maliModel` (`:61`, token `g57`/`g78`/`g715`/`t860`); `maliSeries` (`:70`); `vendorFamily` (`:78`); `shouldExcludeBcnCompute` (`:106`) |
| Chave de geração (ponto de extensão) | idem | `maliModel`/`maliSeries` entregam a chave por geração; o **mapa** por geração não foi criado (sem medição — §3 do spec) |
| Migração sítio 1 | `XServerScreen.kt:6042` | `renderer?.contains("Mali") != true` → `!GpuClassification.isMali(renderer)` |
| Migração sítios 2 e 3 | `BionicProgramLauncherComponent.java:260`, `GlibcProgramLauncherComponent.java:278` | `renderer.contains("Mali")` → `GpuClassification.INSTANCE.isMali(renderer)` (cobre Immortalis; null-safe) |
| BCN Mali-aware (B1) | `XServerScreen.kt:6097` | `excludeBcnCompute` delegado a `GpuClassification.shouldExcludeBcnCompute(vendorId, getRenderer(context), isWrapperGamenative)`; `WRAPPER_EMULATE_BCN` intocado |
| Present mode: fonte única | `app/src/main/java/app/gamenative/utils/PresentModeMapping.kt` (novo) | `UI_MODES` (`:29`), `toVkPresentMode` (`:36`) substitui o `when` inline |
| Present mode: UI | `ContainerConfigDialog.kt:227` | `rendererPresentModes = PresentModeMapping.UI_MODES` (4 itens, novos anexados no fim) |
| Present mode: consumo | `XServerScreen.kt:2041` | `val vkMode = PresentModeMapping.toVkPresentMode(pm)` |
| A4a — probe nativo exposto | `LosslessScaling.java:95` | `supportsFrameGeneration(context, driverName)` com cache `ConcurrentHashMap` + `catch (Throwable)`; `nativeSupportsFrameGeneration` declarado (`:119`), chamado em `:102` |
| A4a — chamada diagnóstica | `LsfgVkManager.kt:317` (chamada), `:351` (helper) | roda no `vsyncWriteExecutor` (fora da main), uma vez por driver, quando `enabled` |
| A3 — strings | `values/strings.xml:2334,2347`; `values-pt-rBR/strings.xml:1570,1583` | dependência real (Vulkan 1.3 + compute + storage-image) e "pode não funcionar em alguns drivers Mali ou PowerVR" |
| Comentário obsoleto corrigido | `XServerScreen.kt:2023-2027` | afirmava que o padrão era o GL renderer e citava `useLegacyRenderer` como caminho vivo |
| Testes | `app/src/test/java/app/gamenative/utils/GpuClassificationTest.kt` (14), `PresentModeMappingTest.kt` (4) | 18 no total, 0 falhas |

## 2. Desvios do spec (decisões registradas — com justificativa)

1. **B1 não pôde reusar `renderer` como local.** O spec previa
   `shouldExcludeBcnCompute(vendorId, renderer, isWrapperGamenative)` reusando locals existentes,
   mas o `val renderer` de `XServerScreen.kt:6040` vive num bloco aninhado (`if (ALWAYS_REEXTRACT…)`
   → `if (mainWrapperSelection…)`) e **não está em escopo** em `:6097`. Optou-se por
   `GPUInformation.getRenderer(context)` — a variante **cacheada** por `PrefManager`, a mesma que os
   launchers e todos os helpers Adreno usam — em vez de `getRenderer(null, null)`, que criaria e
   destruiria uma segunda instância Vulkan não-cacheada no boot.
2. **`isAdreno`/`isXclipse` removidos.** Eram usados exclusivamente por `excludeBcnCompute`; com a
   delegação ficariam mortos. A remoção **reduz** 2 locals no arquivo que está no limite do registry
   de dex (o spec pedia "sem locals novos"; isto é menos que antes).
3. **A4a entregue como probe + log; o aviso na UI foi deliberadamente NÃO implementado.** O spec
   previa "estado explícito na aba do QuickMenu". Como o veredito do probe ainda **não foi validado
   em device** (§A4b, bloqueado por design), exibir ao usuário um "indisponível neste driver" não
   verificado é pior que o silêncio atual — poderia desencorajar um setup que funciona. A condição
   para o aviso é a mesma de A4b: o probe concordar com a realidade em Adreno e em Mali.
4. **`maliModel` cobre `Mali-Txxx` (Midgard).** A regex herdada de `CommunityConfigService.kt:785`
   casa `t860`; mantida por ser a forma já validada no repo, e `maliSeries` devolve `860`. Não há
   (nem se inventou) política específica por geração.

## 3. Regressão garantida

- **Adreno/Xclipse byte-idênticos:** `shouldExcludeBcnCompute` reproduz a expressão anterior
  (`vendorId == 0x5143` → sempre `true`; `0x144D` → só com wrapper-gamenative). Há testes nomeados
  `- regression` para os dois casos, incluindo o caso `vendorId=0x5143` com renderer `null`.
- **Containers existentes:** `rendererPresentMode` é persistido como **string** e o índice da UI é
  derivado por `indexOfFirst` (`ContainerConfigDialog.kt:711-714`); os novos modos foram
  **anexados no fim**, então `fifo`/`mailbox` mantêm os índices 0/1. Teste dedicado garante que os
  dois primeiros itens de `UI_MODES` nunca se movam.
- **Fallback de present mode:** entrada desconhecida/vazia/ausente → FIFO (`2`), igual ao fallback
  nativo (`VulkanRendererContext.cpp:2330-2333`).
- **`XServerScreen.kt` sem locals novos:** B1 e C2 usam somente locals pré-existentes; B1 removeu dois.

## 4. Pendente on-device (mesma lista do spec §8)

1. Valores reais dos nós Mali no DDK do aparelho (`mali0`, devfreq) e se existem.
2. Veredito real do probe (`supported=...` no log `LosslessScaling`) → habilita ou descarta A4b.
3. A/B de BCN compute (ON vs OFF) medido por `metrics-*.jsonl` + relatório de debug.
4. Mudança de comportamento em **Immortalis** (`BOX64_MMAP32` 1→0, skip de `zink_dlls`).
5. `immediate`/`relaxed` sem tearing perceptível.

## 5. Verificação executada (comandos exatos)

```
JAVA_HOME=/home/marcos/android-studio/jbr ./gradlew :app:testModernDebugUnitTest \
  --tests "*GpuClassification*" --tests "*PresentModeMapping*" --no-configuration-cache
```
→ `BUILD SUCCESSFUL`; resultados autoritativos em
`app/build/test-results/testModernDebugUnitTest/TEST-app.gamenative.utils.GpuClassificationTest.xml`
(`tests="14" failures="0" errors="0"`) e
`...TEST-app.gamenative.utils.PresentModeMappingTest.xml` (`tests="4" failures="0" errors="0"`).

**Notas de toolchain (divergem do `AGENTS.md`):**

- `JAVA_HOME` é `/home/marcos/android-studio/jbr`; o `AGENTS.md` aponta
  `/home/annapaula/android-studio/jbr`, **inexistente** nesta máquina. `java` não está no PATH e
  `local.properties` já aponta `sdk.dir=/home/marcos/Android/Sdk`.
- **`--no-configuration-cache` é necessário:** o build já tem 6 problemas **pré-existentes** de
  configuration cache nas tasks `compileLibrashader*`/`copyLibrashader*`
  (`cannot serialize Gradle script object references`), não relacionados a esta mudança.
- `~/.gradle` (5,1 GB de cache) está fora do sandbox de escrita do workspace; rodar o Gradle exige
  permissão de escrita no `GRADLE_USER_HOME`.
