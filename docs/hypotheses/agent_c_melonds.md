# AGENTE C — Hipóteses de causa raiz: fluxo de apresentação (padrão melonDS) — REVISÃO FASE 3

**Papel:** AGENTE C, integração librashader, foco no fluxo de apresentação (imagens intermediárias,
command buffers, submission/fences, layout tracking). PESQUISA apenas.
**Referência que FUNCIONA (Adreno 650):** melonDS `VulkanSurfacePresenter.cpp`
(`/home/annapaula/.local/share/opencode/tool-output/tool_fbaacb4a9001U7qREoNHruNWS9`, 3388 linhas;
`runRetroArchFilter` = 2127–2393).
**Alvo:** Xiaomi Mi 11, Adreno 650, Vulkan 1.1.128, driver stock.
**Síntoma:** `processedImage` contém o shader (READBACK-P ✅), mas toda leitura para apresentação
(sampler e transfer, mesmo submission e cross-submission) dá preto.

> **Status da Fase 3:** versão REVISADA após o cross-review (A, B, D). As revisões confirmaram o
> mapeamento linha-a-linha do melonDS como exato (ponto forte), **rejeitaram a H1 anterior como
> mecanismo** (auto-contradição: refutada pelo próprio melonDS) e aprovaram a topologia como o fix de
> maior probabilidade, com **inversão primário/fallback** (copy engine como caminho primário), a borda
> anti-solução 4 declarada como desvio consciente e o experimento "minimal diff vs 3.11" para isolar a
> variável causal.

---

## 0. Resumo executivo

O GameNative apresenta o `processedImage` **direto** — a imagem que o librashader acabou de escrever via
render passes internos. O melonDS **nunca** faz isso: ele copia a saída do filtro (`topOutput`) para um
**atlas intermediário dedicado** (`atlasOutput`) dentro do **mesmo command buffer** do filtro
(2353–2382), deixa o atlas em `VK_IMAGE_LAYOUT_GENERAL` (2383) e só num **submission posterior** amostra
esse atlas para a swapchain (2390–2391 → 2610–2625 → 3065–3142), com layout tracking manual
(`resource.layout`, 2255–2280) e submit-and-wait dedicado com queue lock (2228–2243).

O cross-review **refutou** a dicotomia "leitura por textura vs copy engine" como causa raiz: o melonDS
faz exatamente `vkCmdBlitImage` (transfer) da saída do filtro, no mesmo CB, no mesmo Adreno, e funciona
(2357–2382). A dicotomia permanece apenas como **diagnóstico de caminho**, não como causa. A hipótese
revisada é a **divergência de estado/layout específico do `processedImage` na configuração do
GameNative** (mecanismo exato em aberto), e a solução é a reimplantação fiel do padrão melonDS com
**`vkCmdCopyImage` (copy engine) como caminho primário** do filtro→atlas — o caminho que não depende da
leitura "frágil" — e **blit por transfer como variante de diagnóstico**. A topologia completa
(atlas + submit-and-wait + present em `GENERAL` + tracking) é o fix de maior probabilidade apoiado por
todos os agentes.

---

## 1. Mapeamento exato das diferenças (com linhas da referência — re-verificadas na Fase 3)

### 1.1 Topologia de imagens

| item | melonDS (referência) | GameNative (falhou) |
|---|---|---|
| Imagem apresentada | `atlasOutput` — **dedicada**, criada com `TRANSFER_SRC\|TRANSFER_DST\|COLOR_ATTACHMENT\|SAMPLED` (1995–1998) | `processedImage` — saída direta do filtro, `COLOR_ATTACHMENT\|SAMPLED\|TRANSFER_SRC` |
| Escrita da imagem apresentada | **só por transfer blit** (`copyFilteredScreen`, 2361–2380), nunca por render pass | por render passes internos do librashader (opacos ao app) |
| Leitura da saída do filtro | cópia `topOutput→atlasOutput` **no MESMO CB do filtro** (2357–2382), e apresentação do atlas num **CB posterior** (2390–2391) | blit/amostragem do `processedImage` **direto → swapchain** (3.1, 3.8, 3.10, 3.11, 3.12) |
| Nº de imagens dedicadas | 5 (`topInput/bottomInput/topOutput/bottomOutput/atlasOutput`, 2093–2097) + `layout` por imagem (2036) | 2 (`offscreen` + `processed`), sem campo de layout |

### 1.2 Layout tracking e barreiras

- melonDS `imageBarrier` (2255–2280): `oldLayout = resource.layout` (2260) — o layout **rastreado**, não
  assumido; `srcAccessMask = MEMORY_WRITE|TRANSFER_WRITE|COLOR_ATTACHMENT_WRITE` (2258); `srcStage =
  ALL_COMMANDS` (2270); e atualiza `resource.layout = newLayout` (2279) após cada barreira.
- melonDS deixa a imagem apresentada em `GENERAL` ao fim do filtro (2383) e o presente a lê em
  `GENERAL`: descritor com `imageLayout = VK_IMAGE_LAYOUT_GENERAL` (2625) + barreira `GENERAL→GENERAL`
  no início do CB de presente (3094–3103, disparada em 3128–3142).
- GameNative (3.8): `transition(processedImage, COLOR_ATTACHMENT_OPTIMAL → SHADER_READ_ONLY_OPTIMAL, ...)`
  — parte do **layout assumido** `COLOR_ATTACHMENT_OPTIMAL` (documentado no header do librashader:
  "A pipeline barrier **will not** be created for the final pass... The output image will remain in
  `VK_COLOR_ATTACHMENT_OPTIMAL` after all shader passes") e apresenta em `SHADER_READ_ONLY_OPTIMAL` /
  `TRANSFER_SRC_OPTIMAL`.

### 1.3 Modelo de submission

- melonDS: pool/CB/fence **dedicados** ao filtro (2073–2091); `begin()` = reset-fence → reset-CB →
  begin `ONE_TIME_SUBMIT` (2245–2253); `submitAndWait` = End → `vkQueueSubmit` sob `GetQueueLock()`
  (2237–2240) → `vkWaitForFences(UINT64_MAX)` (2241–2242). Cada frame filtrado **completa na CPU**
  antes de o CB de presente ser gravado (presentFrame: 1050–1064 roda o filtro, depois 1067–1152 grava/
  submete o presente).
- GameNative: 3.10 (split) e 3.12 (split+transfer) fizeram espera de fence entre Sub-A e Sub-B, mas o
  Sub-B ainda lia o `processedImage` direto. O `endOneTime` do readback que **funciona** (470–477) cria
  fence novo, submete e espera `UINT64_MAX` — submission isolada, com espera própria.

### 1.4 Swapchain

- melonDS: swapchain `imageUsage = COLOR_ATTACHMENT_BIT` apenas (1484); a swapchain é **só** render
  target do CB de presente. Nada é blitado para ela.
- GameNative: 3.11/3.12 adicionaram `TRANSFER_DST_BIT` à swapchain para blit direto — **anti-solução 4**.

---

## 2. Hipóteses de causa raiz (revistas na Fase 3)

> **O que foi refutado e RETIRADO:** a H1 anterior ("a leitura da saída de render pass do librashader por
> textura/copy-engine é incoerente no Adreno; copy engine é o único caminho coerente") é **refutada como
> afirmação geral** pelo próprio melonDS (fato-âncora: blita `topOutput→atlasOutput` por `vkCmdBlitImage`
> no mesmo CB e funciona, 2357–2382) e era **auto-contraditória** (o fix primário proposto então — blit
> `filterOutput→atlas` no mesmo CB — era essa mesma leitura; sob a H1, o próprio fix falharia). A dicotomia
> textura-vs-copy-engine sobrevive apenas como **instrumento de diagnóstico** (qual caminho lê a saída do
> filtro quando o destino é uma imagem dedicada), não como causa raiz.

### H1 — Divergência de estado/layout específico do `processedImage` na configuração do GameNative (mecanismo exato em ABERTO)

**Enunciado:** o problema é **específico da configuração do caminho de leitura do `processedImage` no
GameNative**, não uma propriedade geral da saída de render pass do librashader nem do Adreno. O melonDS
lê a saída do filtro in-frame com sucesso; o GameNative falha em **todo** método de leitura in-frame
(sampler, transfer, mesmo submission e cross-submission), mesmo com fence (3.10). Portanto a variável
causal está em **como o GameNative constrói a leitura dessa imagem específica**, com candidatos que o
experimento do §7.4 isola **um por vez**:

1. **Destino da leitura = swapchain** (vs. imagem dedicada do app): toda leitura que falha tem destino
   swapchain no mesmo ciclo; o readback que funciona e o atlas melonDS nunca leem para a swapchain no
   mesmo ciclo (variável residual que **nenhuma** hipótese explica conclusivamente — fatos de revisão).
2. **`oldLayout` assumido (`COLOR_ATTACHMENT_OPTIMAL`) vs. estado efetivo** que o driver mantém para o
   `processedImage` na sequência exata do GameNative (transição `CA→SRO` ou `CA→TRANSFER_SRC` gravada
   sobre um render pass final que **não emitiu barreira própria** — contrato `librashader.h:1705–1720`).
   O melonDS escapa porque parte sempre do **layout rastreado** (`resource.layout`, 2260), nunca de um
   assumido.
3. **Ausência de intermediária dedicada** cujo histórico de layout o app **possui** integralmente, e
   cuja última escrita é um TRANSFER write sob controle do app (o que o melonDS garante para o atlas).

**Por que explicaria a evidência (como família):**
- `offscreenImage` (render pass do próprio GameNative, transição própria, fix 3.5) lê OK — o app **possui**
  o histórico de layout dessa imagem.
- `processedImage` (escrito pelo librashader, histórico não-dono) falha em sampler e transfer in-frame,
  mas o readback copy-engine de uma submission isolada lê certo — consistente com a divergência ser do
  **estado/contexto da leitura in-frame**, não do conteúdo da imagem.
- O melonDS funciona porque lê por um caminho cujo estado ele controla de ponta a ponta (atlas dedicado,
  layout rastreado, present posterior em `GENERAL`).

**Mecanismo exato:** **em aberto.** Nenhuma das hipóteses de mecanismo (esta, A-H1, B-H2, D-H1) foi
provada como causa única. O atlas é um fix robusto sob múltiplos mecanismos; **o sucesso dele NÃO deve
ser lido como confirmação de mecanismo específico** (consenso do cross-review). Os experimentos §7
existem para identificar a causa; a topologia é o remédio.

### H2 — Layout de apresentação: `GENERAL` vs `SHADER_READ_ONLY_OPTIMAL` (contribuinte, possivelmente suficiente)

**Enunciado:** o melonDS apresenta o atlas em `VK_IMAGE_LAYOUT_GENERAL` (descritor 2625, barreira
`GENERAL→GENERAL` 3094–3103/3128–3142). O GameNative apresenta o `processedImage` em
`SHADER_READ_ONLY_OPTIMAL` (3.8) e em `TRANSFER_SRC_OPTIMAL` (3.11). No Adreno, a amostragem de uma
imagem recém-escrita por render pass do qual o app não é dono é historicamente mais confiável em
`GENERAL`, enquanto `SHADER_READ_ONLY_OPTIMAL` pode servir a cache de textura sem invalidação adequada
nessa configuração específica.

**Limitação de H2 (por que não é a história completa):** sozinho não explica o fracasso do **transfer
blit** (3.11/3.12), que não usa shader. Por isso H2 é tratado como contribuinte e como teste barato de
desambiguação (§7.1).

**Relação com a evidência "readback do frame seguinte OK":** o readback não é sampler nem blit; não
depende nem do layout de apresentação nem da cache de textura, e roda numa submission isolada com espera
própria — por isso lê o conteúdo correto independentemente de H1/H2 (e a ambiguidade da posição do
readback na linha do tempo deve ser logada, §7.0).

---

## 3. Solução testável — reimplantação fiel do padrão melonDS (topologia completa)

> **Não é "adicionar TRANSFER_DST" nem "alargar barreiras": é mudar a TOPOLOGIA de imagens**, de
> "apresentar a saída do filtro direto" para "copiar a saída do filtro para uma imagem intermediária
> dedicada no mesmo CB do filtro, e apresentar essa imagem depois". Este é o fix de maior probabilidade,
> apoiado por A, B e D.

Fluxo novo proposto (espelhando 2073–2091, 2127–2393, 2610–2625, 3065–3142):

```
offscreenImage (render target do compositor, inalterado, fix 3.5 mantido)
    │  transição CAO → SHADER_READ_ONLY_OPTIMAL (como hoje, funciona)
    ▼
[CB do filtro — pool/CB/fence DEDICADOS, como 2073–2091]
    libra_vk_filter_chain_frame(input=offscreen, out=filterOutput)
    │  filterOutput é uma imagem DEDICADA nova (usage TRANSFER_SRC|TRANSFER_DST|COLOR_ATTACHMENT|SAMPLED,
    │   mesmo padrão 1995–1998), com campo layout rastreado (2036)
    ▼  MESMO CB do filtro (como 2357–2382):
    imageBarrier(filterOutput, CAO→TRANSFER_SRC_OPTIMAL, TRANSFER_READ, TRANSFER)      ← 2255–2280 + 2358
    imageBarrier(atlasOutput, →TRANSFER_DST_OPTIMAL, TRANSFER_WRITE, TRANSFER)          ← 2357
    ▼  CAMINHO PRIMÁRIO (copy engine, ver §3.1):
    vkCmdCopyImage(filterOutput TSRC → atlasOutput TDST)                                 (copy engine)
    imageBarrier(atlasOutput, TDST→GENERAL, SHADER_READ, FRAGMENT_SHADER)               ← 2383
    ▼
submitAndWait: End → QueueSubmit sob queueMtx → WaitForFences(UINT64_MAX)               ← 2228–2243
    (CPU bloqueia até o filtro completar, como o melonDS faz por frame)
    ▼
[CB de presente do GameNative (existente)]
    barreira GENERAL→GENERAL no atlasOutput (src MEMORY_WRITE|SHADER_WRITE|TRANSFER_WRITE,
    srcStage ALL_COMMANDS, dst FRAGMENT_SHADER)                                          ← 3094–3103, 3128–3142
    descritor do window shader: imageLayout = VK_IMAGE_LAYOUT_GENERAL                    ← 2625
    render pass → swapchain (swapchain volta a ser SÓ COLOR_ATTACHMENT — desvio consciente, §3.2)
```

### 3.1 Inversão primário/fallback (decisão da Fase 3)

| caminho filtro→atlas | status | justificativa |
|---|---|---|
| **`vkCmdCopyImage` (copy engine)** | **PRIMÁRIO** | Não passa pela leitura via textura da saída do filtro — evita depender da "leitura frágil". Copia 1:1 (regiões `VkImageCopy`, com offset de destino para bottom layer, como o `copyFilteredScreen` do melonDS, 2361–2380). É o caminho de menor risco sob **qualquer** mecanismo (consenso A/B/D: mitigar a auto-contradição da Fase 2). |
| **`vkCmdBlitImage` (transfer)** | **variante de DIAGNÓSTICO** | Roda na mesma topologia, com as barreiras transfer-largas do melonDS (2258/2270). Se o atlas **aparecer** via transfer também → a leitura transfer é coerente quando o destino é imagem dedicada (isola "destino", §7.4-1). Se **falhar** → confirma a fragilidade do caminho transfer da saída do filtro nesta config (e o copy engine é obrigatório). **Não** é o fix primário. |

### 3.2 Borda anti-solução 4 — desvio consciente e justificado

A anti-solução 4 proíbe "adicionar/remover `TRANSFER_DST_BIT` da swapchain — não resolve (3.11)". O fix
revisado **remove** o `TRANSFER_DST` que o 3.11 adicionou, devolvendo a swapchain a
`imageUsage = COLOR_ATTACHMENT_BIT` **apenas** — exatamente como a referência que funciona (melonDS:1484),
onde a swapchain é **só** render target e nada é blitado para ela.

**Declaração:** isto é um **desvio consciente da letra da anti-solução 4**, e não um re-teste dela:
- **Não re-testa** "adicionar/remover TRANSFER_DST como fix" (o 3.11 já provou que não resolve sozinho).
- **Reverte** o estado da swapchain ao padrão da referência como parte da **mudança de topologia** — o
  destino de escrita sai da swapchain e vai para uma **imagem dedicada do app**. A remoção do
  `TRANSFER_DST` **não é o mecanismo do fix**; é consequência de a apresentação passar a ser via render
  pass do atlas. Registrado como tensão menor aceita no cross-review (anti-4 / consenso).

### O que muda concretamente em relação ao que o GameNative já tentou

| tentativa GameNative | mudança no padrão melonDS |
|---|---|
| 3.1/3.8 — sampler blit do `processedImage` → swapchain, no CB do filtro | a leitura da saída do filtro passa a ser uma **cópia para o atlas dedicado** no mesmo CB (2361–2382); o que o sampler toca é o **atlas**, nunca o `processedImage` |
| 3.10/3.12 — split: Sub-B lê `processedImage` → swapchain após fence | a imagem apresentada é **outra** (`atlasOutput`), cuja última escrita é TRANSFER write (2357→2383), não um render pass do librashader |
| 3.11 — transfer blit `processedImage` → swapchain (swapchain TRANSFER_DST) | a cópia é `filterOutput → atlasOutput` (imagens dedicadas); a swapchain perde o `TRANSFER_DST` (§3.2) |
| 3.8 — transição partindo do layout **assumido** CAO | layout tracking manual `resource.layout` (2255–2280): toda barreira parte do layout rastreado e atualiza o campo; a imagem apresentada termina em `GENERAL` (2383) e é lida em `GENERAL` (2625) |
| 3.4/3.9 — fence/queueMtx avulsos | `begin()/submitAndWait` dedicados (2245–2243): fence próprio por conjunto de recursos, submit sob queue lock, espera UINT64_MAX — serialização completa do frame filtrado antes do presente |

### Pré-requisitos de base

- A integração está **revertida** (estado atual de `VulkanRendererContext.cpp` = 1278 linhas, sem
  librashader). Reaplicar a base descrita na tabela do doc de tentativas (VulkanLibrashader, JNI, CMake,
  Java/UI) antes de implementar a topologia acima. Não há código de implementação aqui (AGENTE C = pesquisa).

---

## 4. Observável esperado

1. **Tela deixa de ser preta** com shader ativo: a apresentação mostra os mesmos pixels do READBACK-P
   (`ff0c1028`, `ff0b0b26`, `00080c27`), sem flicker.
2. **Diagnóstico `READBACK-atlas`** (copy engine, uma vez) sobre `atlasOutput` após o `submitAndWait`:
   conteúdo não-zero e igual ao READBACK-P — confirma que o atlas recebeu o filtro.
3. **Zero mudança no caminho sem shader**: compositor + presente existentes intactos (fix 3.5 mantido).
4. Se H2 estiver certo, o teste minimalista do §7.1 sozinho já tira o preto — e o atlas seria opcional.
5. Se a **variante transfer** do atlas (blit) também aparecer → a leitura transfer é coerente com destino
   dedicado; o copy engine primário continua por robustez.

---

## 5. Esforço / risco

| | |
|---|---|
| **Esforço** | Médio. +2 imagens dedicadas (`filterOutput`, `atlasOutput`) com campo `layout`; bloco de submission do filtro (begin/submitAndWait) reescrito; barreira `GENERAL→GENERAL` + descritor `GENERAL` no presente; remover `TRANSFER_DST` da swapchain. Reusa `VkTable` existente (`CmdCopyImage`, `CmdBlitImage`, `CmdPipelineBarrier`, `QueueSubmit`, fences). Nenhuma mudança no librashader nem no wrapper (ABI 2 mantida, `use_dynamic_rendering=false`). Custo GPU: +1 cópia fullscreen/frame (~insignificante). Memória: +2 imagens. |
| **Risco principal** | Race presente×filtro: se o CB de presente for gravado/submetido **sem** a serialização do `submitAndWait` (ou sem espera da fence do filtro), o atlas pode ser amostrado antes da escrita. O melonDS evita por construção (CPU bloqueia no wait, 2241–2242). Replicar a espera é obrigatório. |
| **Risco de H2 ser suficiente** | Se §7.1 confirmar H2 isolado, a topologia completa pode ser dispensada — economia de trabalho; risco: deixar de tratar H1 e quebrar em outro preset/histórico. |
| **Risco residual do caminho primário** | `vkCmdCopyImage` exige **mesmo formato/extent e regiões 1:1** (sem escalonamento) — atendido aqui (a cópia do melonDS também é 1:1). Se `filterOutput` e `atlasOutput` divergirem em formato/extent, o fix precisa do blit — e aí a variante transfer deixa de ser só diagnóstico. |
| **Risco de "leitura da saída ainda falhar"** | Com copy engine primário, a topologia **não depende** da leitura via textura da saída do filtro; o caso de falha residual seria o presente do atlas em si (GENERAL + imagem dedicada + submit-and-wait), que é exatamente o padrão melonDS. |

---

## 6. Conformidade com as 12 anti-soluções (revisada)

| # | anti-solução | conformidade da solução proposta |
|---|---|---|
| 1 | não rediagnosticar "o librashader renderiza?" | ✅ aceita READBACK-P como prova; não repete o diagnóstico (o READBACK-atlas de §4 é verificação do **novo** caminho, não do librashader) |
| 2 | não testar sampler vs transfer como causa raiz | ✅ não propõe "trocar sampler por transfer" como fix; a dicotomia textura-vs-copy-engine foi **rebaixada a diagnóstico** (Fase 3) e o fix muda a **topologia** com copy engine primário |
| 3 | não testar split/cross-submission sozinho | ✅ o split do presente **é** parte do padrão, mas lê uma **imagem diferente** (atlas), não o `processedImage` |
| 4 | não mexer em TRANSFER_DST da swapchain | ⚠️ **desvio consciente declarado** (§3.2): a swapchain volta a só `COLOR_ATTACHMENT` (1484) como **reversão** à referência, não como re-teste; o blit é `filterOutput→atlasOutput`, imagens dedicadas |
| 5 | não mexer em alpha/compositeAlpha | ✅ intocado |
| 6 | não alargar barreiras ALL_COMMANDS/MEMORY_WRITE | ✅ as barreiras usam o **mesmo** receituário já tentado (2258/2270 = 3.8) — o que muda é a topologia e o layout tracking; a barreira transfer-larga aparece apenas no experimento §7.4-3 como **diagnóstico de uma variável**, não como fix |
| 7 | não readicionar queueMtx como solução | ✅ queue lock apenas como proteção de execução (2237–2240), igual ao melonDS; não é apresentado como a causa |
| 8 | não reintroduzir prebuilts em jniLibs | ✅ intocado |
| 9 | não descomentar target `winlator` no CMake | ✅ intocado |
| 10 | não reativar TEST MODE como resposta | ✅ TEST MODE permanece só diagnóstico; a solução é o path real |
| 11 | não assumir que o problema é o layout do `offscreenImage` | ✅ `offscreenImage` intocado (fix 3.5 mantido); o layout novo é do atlas, imagem nova |
| 12 | não usar dynamic rendering | ✅ `use_dynamic_rendering=false` mantido (3.3) |

---

## 7. Diagnósticos de desambiguação (em ordem de prioridade; antes ou junto da solução)

> Ordem alinhada ao consenso do cross-review (A/B/D): primeiro o barato que corrige a base de evidência
> e discrimina layout; depois o que isola a variável causal; por fim a topologia completa.

**7.0 — Log da posição do READBACK-P relativa ao `applyFrame` do frame N+1 + `oldLayout`/`srcImageLayout`
usado (P1 do consenso; ~30 min).** O `processedImage` é reescrito todo frame; sem logar se o readback
roda antes/depois da transição do frame seguinte, "o atraso materializa o resolve" **não está
estabelecido**. Corrige a base de evidência de todas as hipóteses.

**7.1 — Teste H2 minimalista (probe GENERAL; ~1 dia).** Manter `processedImage` como hoje, mas apresentar
com descritor `imageLayout = GENERAL` + barreira `GENERAL→GENERAL` no CB de presente (como
2625/3094–3142). Se a tela sair do preto **sem** atlas → H2 é suficiente e a topologia vira opcional.
Deve rodar ANTES da topologia completa (economia real — ressalva A/B/D).

**7.2 — Readback do `processedImage` no MESMO frame (~1h).** Readback copy-engine imediatamente após
`applyFrame`, no mesmo pipeline (pista #7 do doc de tentativas; E1 de B/D). Separa "estado da fonte no
momento da leitura" (layout) de "visibilidade postergada" (timing). Com o log da 7.0, discrimina a
família H1/H2.

**7.3 — Caminho de leitura vs destino dedicado.** Com a topologia pronta, comparar o resultado da
**variante transfer (blit)** e do **primário (copy engine)** no mesmo atlas: se ambos aparecerem, o
gargalo não é o caminho de leitura da saída do filtro; se só o copy engine aparecer, a leitura via
textura da saída do filtro é o ponto frágil **nesta configuração** (não como regra geral — melonDS
contradiz a generalização).

**7.4 — Minimal diff vs 3.11 (mesmo caminho de leitura, UMA variável por vez).** O 3.11 fez transfer
blit do `processedImage` → swapchain e falhou; o melonDS faz transfer blit da saída do filtro → imagem
dedicada e funciona. O experimento isolante usa **exatamente o caminho de leitura do 3.11** (transfer
blit da saída do filtro), variando **uma** variável do padrão melonDS por vez — **não** é anti-solução
(não é "trocar sampler por transfer"); é instrumentação:

| # | variável (uma por vez) | descrição |
|---|---|---|
| 7.4-1 | **destino dedicado** | transfer blit `processedImage` → **imagem dedicada nova** (mesma topologia do atlas, sem present ainda), em vez da swapchain. Se aparecer → a leitura transfer é coerente com destino dedicado; a variável causal é o **destino swapchain**. |
| 7.4-2 | **`GENERAL`** | mesmo destino dedicado, mas apresentando a imagem em `GENERAL` (descritor 2625 + barreira `GENERAL→GENERAL`). Isola a variável layout de apresentação (H2) da variável destino. |
| 7.4-3 | **barreira transfer-larga (2258/2270)** | no caminho transfer do 3.11, trocar a barreira pós-filtro pelos parâmetros exatos do melonDS: `srcAccess = MEMORY_WRITE\|TRANSFER_WRITE\|COLOR_ATTACHMENT_WRITE` (2258), `srcStage = ALL_COMMANDS` (2270), `dst = TRANSFER_READ`, `dstStage = TRANSFER`. Parâmetro **nunca testado** no caminho transfer do GameNative (o 3.8 testou o caminho sampler com máscaras estreitas). **Não** é a anti-solução 6 (é o receituário exato da referência que funciona, isolado como variável). |

Resultado orientador: se 7.4-1 mudar o resultado (preto→visível), o destino dedicado é causal e a
topologia atlas é o remédio; se não mudar, avança para 7.4-2 (GENERAL) e 7.4-3 (barreira), sempre uma
variável de cada vez, parando no primeiro visível.

---

## Declaração de revisão da Fase 3

Em atendimento ao cross-review (A/B/D) e ao documento consolidado, o AGENTE C revisa a própria hipótese
da Fase 2 nos seguintes pontos:

1. **H1 reescrita:** retirei a dicotomia "leitura por textura vs copy engine" como causa raiz — refutada
   pelo próprio melonDS (fato-âncora: `vkCmdBlitImage` da saída do filtro no mesmo CB, 2357–2382, funciona
   no mesmo Adreno) e auto-contraditória na versão anterior (o fix primário reproduzia a leitura que a H1
   marcava como frágil). A H1 revisada passa a ser a **divergência de estado/layout específico do
   `processedImage` na configuração do GameNative**, com o mecanismo exato declarado **em aberto** e com
   os candidatos (destino swapchain; `oldLayout` assumido vs rastreado; ausência de intermediária) listados
   para isolamento experimental.
2. **Inversão primário/fallback:** `vkCmdCopyImage` (copy engine) vira o caminho **primário** do
   filtro→atlas; o blit por transfer vira **variante de diagnóstico** (§3.1). A topologia deixa de
   depender da leitura "frágil".
3. **Borda anti-solução 4 declarada:** a remoção do `TRANSFER_DST` da swapchain é explicitamente um
   **desvio consciente e justificado** ("reverter, não re-testar"), ancorado no melonDS:1484 (swapchain
   só `COLOR_ATTACHMENT`), e não o mecanismo do fix (§3.2).
4. **Experimento "minimal diff vs 3.11" adicionado** (§7.4): mesmo caminho de leitura do 3.11, variando
   **uma** variável do padrão melonDS por vez — destino dedicado; `GENERAL`; barreira transfer-larga
   (2258/2270).
5. **Topologia completa e prioridade dos diagnósticos mantidas:** atlas + submit-and-wait + present em
   `GENERAL` + tracking (fix de maior probabilidade, consenso de todos os agentes); ordem §7 = log da
   posição → probe GENERAL → readback in-frame → minimal diff → topologia.
6. **Citações do melonDS re-verificadas e exatas** (ponto forte mantido): 1995–1998, 2036, 1484,
   2228–2243, 2245–2253, 2255–2280, 2353–2383, 2390–2391, 2625, 3094–3103/3128–3142.

---

## Referências (linhas da referência melonDS citadas — re-verificadas na Fase 3)

- Imagens dedicadas + usos: 1980–2038 (usos 1995–1998; `layout` 2036)
- Recursos de submission dedicados: 2073–2091 (imagens 2093–2097)
- `runRetroArchFilter`: 2127–2393
- `submitAndWait` com queue lock + fence: 2228–2243
- `begin()`: 2245–2253
- `imageBarrier` (layout tracking): 2255–2280 (srcAccess 2258, oldLayout 2260, srcStage 2270, update 2279)
- `sourceAtlasBarrier`: 2284–2298; `copyScreenInput` (input blits): 2300–2319
- Filtro: 2353–2355
- Barreiras pós-filtro: 2357–2359; `copyFilteredScreen` (cópia p/ atlas): 2361–2380; chamadas 2381–2382
- Atlas → `GENERAL`: 2383; saída do filtro = atlas: 2390–2391
- Presente em `GENERAL`: descritor 2625; barreira `GENERAL→GENERAL` 3094–3103/3128–3142
- Swapchain só `COLOR_ATTACHMENT`: 1484; presente em submission separado: 3281–3381
- Contrato do librashader (sem barreira no pass final; saída permanece em CAO): `librashader.h:1705–1720`
