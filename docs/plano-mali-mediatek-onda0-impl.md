# Impl doc — plano Mali/MediaTek: Onda 0 e itens sem device

**Data:** 2026-10-10
**Executa:** `docs/plano-mali-mediatek.md` §5 "Onda 0 — sem device (pode começar já)"
**Spec de origem:** `docs/spec-2026-10-09-mali-mediatek-performance.md`
**Natureza:** registro de implementação. A Onda 0 não depende de device adb; tudo o que exige
medição on-device continua pendente e está listado em §5.

---

## 1. Resumo

Os itens da Onda 0 foram tratados: **I1a/I1b** (com o I1c) viraram código e testes, **H1** virou
correção de código e documentação, e **G2/F3/H3** viraram investigação/design registrado. Nenhum
item de tuning (F/G/H) virou default: a regra de ouro do plano — A/B antes de default — foi
respeitada, porque não há device conectado.

| Item | Entrega | Estado |
|---|---|---|
| **I1a** — descartar trip points `*-step` no ranking térmico | `ThermalZoneClassification` + uso em `SystemMetricsReader`/`PerfSampler` + testes | Entregue |
| **I1b** — descartar `soc` isolado (State of Charge, %) | idem acima | Entregue |
| **I1c** — `gpuss` já é coberto (`contains("gpu")`) | teste de regressão + correção do doc de referências | Entregue |
| **H1** — `ENABLE_BCN_COMPUTE` é no-op no wrapper shipado | comentários de código + spec §4.1 + reframe de K5 | Entregue |
| **G2** — decisão por capacidade (`enumerateExtensions`) | design registrado; G1 reframeado (wrapper já decide `extended_dynamic_state` no Mali) — §4.2 | Registrado |
| **F3** — proveniência do blob `wrapper-gamenative` | commit/origem identificados (blob == publicado) — §4.1 | Registrado |
| **H3** — como o transcode BCN por GPU é acionado | fluxo mapeado; `WRAPPER_BCN_GPU_CAP_MB` é o lever seguro de expor — §4.3 | Registrado |

---

## 2. I1a/I1b/I1c — telemetria térmica

### 2.1 O bug

`SystemMetricsReader.kt` rankeava zonas de `/sys/class/thermal` por `contains("cpu")` /
`contains("gpu")` / `contains("soc")`. Duas classes de zona não são termômetros:

- **Trip points de mitigação (`*-step`, `*-step-*`, `avg-step`, `max-step`).** São limiares de
  throttling; casavam os `contains(...)` e podiam ser escolhidas no lugar do sensor real — um trip
  de 85 °C era reportado como "85 °C" permanente.
- **`soc` isolado.** Em Qualcomm, uma zona chamada exatamente `soc` é *State of Charge* da bateria
  (%). O ranker de CPU a mapeava para o rank 3, então uma bateria a 85 % virava "85 °C" de CPU.
  Zonas qualificadas (`soc_thermal`, `soc-thermal`, `soc_max`, `soc-temp`, `soc_cpu`) continuam
  válidas — em MediaTek são temperatura legítima.

### 2.2 A mudança

A classificação virou lógica pura testável em JVM, em
`app/src/main/java/app/gamenative/powercontrol/metrics/ThermalZoneClassification.kt`:

- `isExcludedZoneType(type)` — `true` para trip point `*-step` ou `soc` isolado.
- `cpuRank(type)` / `gpuRank(type)` — os ranks originais, com `null` para zona excluída.

`SystemMetricsReader` passa a delegar a esses ranks (`cpuTempPaths`/`gpuTempPaths`);
`PerfSampler.discoverSkinTempPaths` passa a reusar o mesmo guarda, para não ler `skin-step`.
Nenhuma ordem de rank existente foi alterada — só zonas antes elegíveis deixaram de ser.

Detalhe preservado de propósito: `cputop` e `soc_cpu` já casavam o rank genérico (`contains("cpu")`)
antes das cláusulas específicas, por causa da ordem do `when` original. Os testes travam esse
comportamento (não é uma regressão desta mudança).

Implementado em `SystemMetricsReader.kt` e `ThermalZoneClassification.kt`; classificação pura é a
única fonte das regras.

### 2.3 Verificação

- `app/src/test/java/app/gamenative/powercontrol/metrics/ThermalZoneClassificationTest.kt`
  (10 testes JVM) cobre I1a, I1b, I1c e a preservação dos ranks.
- Comando e resultado: ver §6.

---

## 3. H1 — `ENABLE_BCN_COMPUTE` é no-op com o wrapper que shipamos

O plano §2.1/§2.4 provou no binário que `ENABLE_BCN_COMPUTE`/`BCN_COMPUTE_AUTO` não existem no
`libvulkan_wrapper.so` do `wrapper-gamenative.tzst`; são legado da linhagem Winlator. Repetimos a
checagem nesta máquina (§6) e o resultado confirma: **ausentes**.

**Decisão:** manter como está. O gate preserva a decisão histórica de Adreno/Xclipse byte-idêntica,
custa nada e não altera `WRAPPER_EMULATE_BCN` — mas **nunca** deve ser apresentado como lever de
desempenho. O que mudou:

- Comentário explícito no sítio de `XServerScreen.kt` (bloco BCN) e no KDoc de
  `GpuClassification.shouldExcludeBcnCompute`, citando a verificação binária.
- `docs/spec-2026-10-09-mali-mediatek-performance.md` §4.1 — correção pós-entrega de que B1 é no-op
  no Mali; §8 item 3 (K5) reframado para testar `WRAPPER_BCN_*`, não `ENABLE_BCN_COMPUTE`.
- `docs/referencias-externas-mali.md` §4 item 3 — corrigido o item que dizia que `gpuss` não era
  coberto (I1c).

---

## 4. Investigações da Onda 0

Três itens da Onda 0 eram investigação, não código. Os relatórios completos ficam em
`/tmp/mali-onda0/` (temporários) e o essencial está destilado abaixo. Nenhum deles vira default:
são insumo para as Ondas 1–3.

### 4.1 F3 — proveniência do blob `wrapper-gamenative`

O blob que shipamos (`app/src/legacy/assets/graphics_driver/wrapper-gamenative.tzst`) é
**byte-idêntico** ao publicado em `downloads.gamenative.app` como
`wrapper-gamenative-20260724.tzst` (sha256
`1a546621cf285b50a6de47aeb0ada4a298d41ac24fc5ca2cdc46673859056d37`).

Origem identificada: **`GameNative/mesa`, branch `wrapper-25`, commit `b8663654f7ea`**
(merge do PR #8, 2026-07-25T05:04:22Z), tree `874f4ce8e3c522267a81646daa61fe912f85b0b6` —
o mesmo tree de `0bd55cbed77d` (2026-07-25T03:15:49Z), portanto equivalente à faixa
`0bd55cb`..`b8663654`. Confiança alta.

Como foi determinado (sem rebuild): o `.so` é stripped e não embute commit; a identificação
vem de marcadores de string e do **nível de log** do callsite
`Dedicated image handle type mismatch` — `info` até `c4c42fa81b61` (2026-07-24), `error` a
partir de `0bd55cbed77d` (2026-07-25). O limite superior é dado por literais de commits de
agosto/2026 em diante, todos ausentes no binário. O `mtime` do membro
(`2026-07-25T05:09:37Z`) cai 5 minutos após o merge.

Detalhes relevantes:

- **Não** corresponde ao HEAD do clone `reference/GameNative-mesa` (`8a01bd8`, CI do PR #16,
  2026-10-10) — o wrapper do clone é ~3 meses mais novo que o blob.
- O tar tem dono `utkarshdalal/staff` + xattr `com.apple.provenance`: foi empacotado
  **manualmente em macOS**, antes da CI do PR #16. A CI reproduz a mesma receita/layout/nome
  (`BUILD_DATE=$(date -u +%Y%m%d)`).
- O manifesto `graphics_driver_download.json` só tem `id`/`name`/`url` — não há campo de
  commit/hash, e o app não valida integridade. **Não** inventar campo ali.

Registro durável: `docs/wrapper-gamenative-provenance.md`.

### 4.2 G2 — decisão por capacidade (`enumerateExtensions`) e o reframe de G1

**Estado do código:** `GPUInformation.enumerateExtensions` (`GPUInformation.java:221`, JNI em
`extras/vulkan.c:359`) é **código morto confirmado** (zero chamadores). O repo já tem uma
enumeração **viva e melhor**, `GPUHelper.vkGetDeviceExtensions()` (`GPUHelper.java:50`), que é a
usada pela UI de extensões (`ContainerConfigDialog.kt:594-604`). O commit `ce241623` ("enumeração
robusta") foi no `gpu_helper.c`, **não** em `GPUInformation`.

Limitações do morto, se for revivido: estado **global** compartilhado com `getVendorID`/`getRenderer`
(não é thread-safe, `extras/vulkan.c:16-17`), `apiVersion` fixo em 1.0, sem retry de `VK_INCOMPLETE`
e sem `DeleteLocalRef` no laço; array **vazio** = falha (não "sem extensões"). Ele só se justifica
para enumerar o driver **custom** (adrenotools); para o driver de sistema, o caminho vivo basta.

**Achado que reframeia G1 (verificado no binário shipado):** o `wrapper-gamenative` **já** decide
`VK_EXT_extended_dynamic_state` por capacidade e por engine, no nível nativo
(`wrapper_physical_device.c:355-390`): em ARM Mali ele **desliga** `..._state`/`..._state2` para
engines **não-D3D** e **mantém** (forjando as features) para DXVK/vkd3d. As strings dessa política
estão no `libvulkan_wrapper.so` de `wrapper-gamenative-20260724.tzst`. Consequência:

- pré-popular a blacklist no Mali (G1) é **no-op** para jogos Vulkan nativos (o wrapper já desliga);
- no caminho DXVK/vkd3d ela **contradiz** a política do wrapper e pode custar desempenho;
- a recomendação do `winlator_mali/README.md:57` é de outro driver (Vortex), não do
  `wrapper-gamenative`.

Além disso, **enumeração não detecta o bug que motiva a blacklist**: presença da extensão ≠ bug de
versão de driver. Ela serve como **piso de capacidade** (não remover o que nem existe); o gatilho
real exige **tabela curada por `maliModel`/`maliSeries`** (hoje vazia, por honestidade) **+ medição
on-device**.

**Desenho registrado (não implementado):** `object DeviceExtensionPolicy` puro/JVM-testável, peer de
`GpuClassification`, com `recommendedBlacklist(vendorId, renderer, isWrapperGamenative,
availableExtensions?, dxWrapper?)` e `mergeWithUser(...)`. Regras: não-Mali → vazio; Mali sem
wrapper-gamenative → vazio (o env só é lido pelo wrapper); enumeração falhou (`null`) → **fail-open**
(não blacklistar); extensão ausente → vazio; presente → só pela tabela curada (vazia). Plug point
recomendado: default por device em `ContainerUtils`/`PrefManager` (opção A) + pré-seleção na UI
(opção C); **não** merge em runtime no `XServerScreen` (opção B), porque `blacklistedExtensions=""`
é ambíguo. Nada disso vira default sem o A/B descrito na §5 (K1).

### 4.3 H3 — como o transcode BCN por GPU é acionado

**Achado principal:** o transcode por compute shader existe no fonte e **está no blob que
shipamos** — mas está desligado por default e **nós já expomos o lever**: `transcoder = gpu`
grava `WRAPPER_BCN_GPU=1`.

Fluxo (fonte, `reference/GameNative-mesa/src/vulkan/wrapper/`):

- `WRAPPER_BCN_GPU` é lido uma única vez em `wrapper_bcn_gpu_ready()`
  (`wrapper_device.c:4097-4118`); default **0**.
- O caminho GPU exige quatro condições `AND`: var != 0; imagem BCn emulada
  (`emulate_bcn > 1`); formato **BC1 ou BC7** (`:4022-4039`); pipeline inicializada
  (`bcn_gpu_state == 1`).
- O compute shader `wrapper_bcn_transcode.comp` é compilado **offline** com `glslc` e o SPIR-V
  é comitado em `wrapper_bcn_spv.h`; em runtime ele é embutido no `.so` e carregado lazy
  (`CreateShaderModule`/`CreateComputePipelines`, `:4041-4095`).
- O dispatch é **injetado no command buffer do app** (`CmdDispatch` em `:4304`); qualquer falha
  (pipeline, memória acima de `WRAPPER_BCN_GPU_CAP_MB`, offset desalinhado) cai
  **silenciosamente para a CPU**.
- O shader **assume destino ASTC**; com `WRAPPER_BCN_ASTC=0` + `WRAPPER_BCN_GPU=1` o resultado
  é incorreto (bug latente — hoje não atingido porque o default de ASTC é 1).

No binário shipado: `WRAPPER_BCN_GPU`, `WRAPPER_BCN_GPU_CAP_MB` e `WRAPPER_BCN_ASTC` estão
**presentes**; `WRAPPER_BCN_POLICY` e `ENABLE_BCN_COMPUTE` **ausentes**. O blob SPIR-V embutido
é byte-idêntico a `wrapper_bcn_spv.h` (27.485 palavras). As variantes `Pipetto-mesa` e
`mesa-wrapper-CI` não têm nenhum desses tokens.

**O que falta no Mali não é capacidade** (compute é core Vulkan; ASTC é nativo): é a correção do
bug declarado pelo autor (dispatch inline na tela de launch do Hades deixa a tela preta) e uma
medição que mostre ganho.

**Implicação para H2:** `transcoder` e `quality` já estão expostos; `WRAPPER_BCN_GPU_CAP_MB` é
seguro de expor (default 128 MB); **não** expor `WRAPPER_BCN_POLICY` enquanto o blob não tiver o
token (seria tuning falso, o mesmo erro de `ENABLE_BCN_COMPUTE`).

---

## 5. Pendente (bloqueado por device — K1)

Nenhum item abaixo foi tocado. Quase todos exigem device adb; em 2026-10-10 apareceu um device
**Mali** (Samsung SM-A146M, Mali-G68 — ver §7), que destrava as frentes Mali mas **não** é o
MediaTek de K1.

- **F1** — `WRAPPER_BYPASS_SWAPCHAIN_PLACED=1` no Mali. Maior valor/menor custo; medir **primeiro**
  quando houver device. Não virou default porque a regra de ouro exige A/B (o próprio autor
  marcou `TODO: turn on by default if safe`).
- **F2** — A/B de `WRAPPER_DISABLE_PLACED` no Mali.
- **F5** — instrumentar o log de WSI (direct vs blit) no JSONL de métricas.
- **F6** — atualizar o asset legacy — **reescopado após verificação, ver §8**: o alvo nomeado no
  plano (`libvulkan_wrapper.tar.xz`) não é consumido por nenhum caminho do app; o
  `wrapper-gamenative.tzst` legacy já está no blob jul/2026; os demais `wrapper*.tzst` exigem
  rebuild upstream + publicação.
- **G1/G3** — blacklist `VK_EXT_extended_dynamic_state` e preset "Mali Otimizado". **Reframeados
  por G2 (§4.2):** G1 vira opt-in por modelo **medido** (não por "ser Mali"), pois o wrapper já
  decide isso nativamente; depende de K1.
- **H2** — expor `WRAPPER_BCN_GPU_CAP_MB` (seguro) e, com acoplamento `ASTC=0 ⇒ GPU=0`,
  `WRAPPER_BCN_ASTC`.
- **H4** — mapa BCN por geração (só depois de medir; chave `GpuClassification.maliModel/maliSeries`
  já existe).
- **I2–I5** — leitores GED/gpufreq, frequência de GPU e throttling no caminho live.
- **J1–J4** — driver de performance Mali.
- **K2–K5** — pendências on-device herdadas do commit `e4222aae`.

---

## 6. Verificação

```bash
JAVA_HOME=/home/marcos/android-studio/jbr ./gradlew :app:testModernDebugUnitTest \
  --tests "*ThermalZoneClassification*" --tests "*GpuClassification*" \
  --tests "*PresentModeMapping*" --no-configuration-cache
```

Resultado: **BUILD SUCCESSFUL**, 28 testes, 0 falhas (`ThermalZoneClassificationTest` 10,
`GpuClassificationTest` 14, `PresentModeMappingTest` 4).

```bash
JAVA_HOME=/home/marcos/android-studio/jbr ./gradlew :app:compileModernDebugKotlin \
  --no-configuration-cache
```

Resultado: **BUILD SUCCESSFUL** (só warnings pré-existentes de `XServerScreen.kt`).

Rechecagem do binário shipado (confirma o plano §2.4 e alimenta H1/H3):

```bash
cd /tmp && mkdir -p bincheck && cd bincheck
zstd -dc <repo>/app/src/legacy/assets/graphics_driver/wrapper-gamenative.tzst | tar -xf -
SO=usr/lib/libvulkan_wrapper.so
grep -qa ENABLE_BCN_COMPUTE "$SO"       # ausente
grep -qa WRAPPER_BCN_POLICY "$SO"       # ausente
grep -qa WRAPPER_BCN_GPU "$SO"          # PRESENTE
grep -qa WRAPPER_BYPASS_SWAPCHAIN_PLACED "$SO"  # PRESENTE
sha256sum "$SO"  # 2aecf1c11cde57008a3cc5e1a551e095d7843d3aa84b3756953afc134035e7c5
```

Notas de toolchain (divergem do `AGENTS.md`): `JAVA_HOME` desta máquina é
`/home/marcos/android-studio/jbr` (o `AGENTS.md` aponta `/home/annapaula/...`), `java` não está no
PATH e `--no-configuration-cache` é necessário pelos problemas pré-existentes das tasks
`librashader`.


---

## 7. Device conectado em 2026-10-10

Apareceu um device via adb durante a execução: Samsung **SM-A146M** (Galaxy A14 5G), Exynos 1330,
**Mali-G68**, Android 15, sem root. Confirmações lidas só com `adb` (nada instalado/alterado):

- `cmd gpu vkjson`: Vulkan 1.3, driverID 9 (ARM proprietary), expõe
  `VK_EXT_extended_dynamic_state` e `VK_EXT_extended_dynamic_state2`, **não** expõe
  `VK_EXT_map_memory_placed`, tem ASTC HDR. Isso confirma a premissa de G2 (§4.2) em hardware
  Mali real.
- `/sys/class/thermal` é **negado** ao usuário `shell`; as métricas já gravadas pelo app
  (`metrics-*.jsonl`) mostram `cpuTempC`/`gpuTempC` **null** — a correção I1a/I1b não é
  observável neste device porque não há leitura nenhuma.
- É hardware **Mali**, não MediaTek: destrava as frentes Mali (F/G/H/J) para A/B, mas **não** as
  MediaTek (I2–I5) nem satisfaz o K1 como escrito.

O A/B on-device de F1/F2/G1 exige um jogo rodando (o wrapper só carrega no launch do container) e
coleta de `metrics-*.jsonl`; é interativo e não foi feito.


---

## 8. F6 — verificação do "asset legacy" e reescopo

A verificação do plano §2.4/F6 não se sustentou totalmente. Fatos apurados (2026-10-10):

1. **`libvulkan_wrapper.tar.xz` não tem consumidor.** O único uso é o manifesto
   (`graphics_driver_download.json`) e o teste `GraphicsDriverDownloaderTest`. A extração de
   driver usa `graphics_driver/$componentId.tzst` (`XServerScreen.kt:5483-5486`), onde
   `componentId` é o id do driver (`wrapper`, `wrapper-v2`, `wrapper-gamenative`,
   `wrapper-legacy`, `wrapper-leegao`). Nenhum caminho usa `libvulkan_wrapper` como id. O
   arquivo é um `.so` de ago/2025 (18.4 MB, sem nenhum dos tokens novos), mas atualizá-lo
   **não muda comportamento nenhum**.
2. **O `wrapper-gamenative.tzst` legacy já está atualizado**: é byte-idêntico ao blob
   jul/2026 que shipamos (o F3 já provou blob == publicado). Não há o que corrigir nele.
3. **Os assets legacy realmente atrasados são os outros wrappers.** Verificado nos `.tzst` de
   `app/src/legacy/assets/graphics_driver/` (token presente?):

   | Asset legacy | tamanho do `.so` | `WRAPPER_EMULATE_BCN` | `WRAPPER_BCN_ASTC` | `WRAPPER_BYPASS_SWAPCHAIN_PLACED` |
   |---|---:|:--:|:--:|:--:|
   | `wrapper-gamenative.tzst` | 2.710.376 | ✅ | ✅ | ✅ |
   | `wrapper.tzst` | 19.021.832 | ✅ | ❌ | ❌ |
   | `wrapper-v2.tzst` | 18.454.184 | ❌ | ❌ | ❌ |
   | `wrapper-legacy.tzst` | 27.317.184 | ❌ | ❌ | ❌ |
   | `wrapper-leegao.tzst` | 8.709.528 | ❌ | ❌ | ❌ |

4. **Atualizá-los não é código deste repo.** Cada `wrapper*` vem de uma origem/variante
   distinta; é preciso rebuild (NDK) + empacotamento + **publicação em
   `downloads.gamenative.app`** + bump do manifesto. Só o `wrapper-gamenative` tem receita de CI
   pronta (PR #16 do `GameNative-mesa`), e ele já está atual.

**Conclusão:** F6, como escrito (atualizar `libvulkan_wrapper.tar.xz`), é **no-op**. O item real
seria "reconstruir e publicar os assets `wrapper*` antigos", que é infraestrutura (build +
upload), não uma mudança de código — e depende de qual upstream cada variante deve seguir. Fica
registrado para decisão do usuário; nada foi alterado nos binários.


---

## 9. H2 (parte segura) — `WRAPPER_BCN_GPU_CAP_MB` exposto

O plano lista H2 na Onda 2, mas o item é **device-independent**: H3 confirmou no binário que
`WRAPPER_BCN_GPU_CAP_MB` existe no blob e é o lever novo seguro de expor. Não muda default nenhum
(o wrapper já usa 128 MB) e não toca no caminho de validação de nenhuma onda.

- `app/src/main/java/app/gamenative/utils/BcnTuning.kt` — lógica pura/JVM-testável:
  - `gpuCapMb`: vazio/não numérico → 128; abaixo de 16 → 16 (contrato do wrapper,
    `wrapper_device.c:4186-4191`).
  - `astcEnabled`: default ligado; só `"0"` desliga.
  - `gpuTranscodeEnabled`: exige ASTC ligado **e** `transcoder=gpu` (o acoplamento de H3 §1.4).
- `GraphicsTab.kt` — campo numérico "Memória do transcode GPU (MB)" (chave `bcnGpuCapMb`) e switch
  "Transcode ASTC" (chave `bcnAstc`), só no bloco do `wrapper-gamenative`.
- `XServerScreen.kt` — seta `WRAPPER_BCN_ASTC`, `WRAPPER_BCN_GPU` (já acoplado a ASTC) e
  `WRAPPER_BCN_GPU_CAP_MB`, ao lado de `WRAPPER_ASTC_BLOCK`.
- Strings EN (`values/`) e pt-rBR (`values-pt-rBR/`).

Com o acoplamento, desligar ASTC cai para RGBA8 e **força o caminho CPU** — o shader do caminho
GPU só é correto com destino ASTC (H3 §1.4), então a combinação buggy não é alcançável pela UI nem
pelo env var.

**Deliberadamente não exposto:** `WRAPPER_BCN_POLICY` — está no fonte do wrapper mas **ausente do
blob**; expô-lo seria tuning falso (mesmo erro de `ENABLE_BCN_COMPUTE`).


**Verificação (2026-10-10):**

```bash
JAVA_HOME=/home/marcos/android-studio/jbr ./gradlew :app:testModernDebugUnitTest \
  --tests "*BcnTuning*" --tests "*ThermalZoneClassification*" --no-configuration-cache
```

**BUILD SUCCESSFUL** — `BcnTuningTest` 5/5 e `ThermalZoneClassificationTest` 10/10, 0 falhas.
