# Cross-Review — AGENTE C (referência melonDS) das hipóteses A, B e D

**Autor:** AGENTE C — integração librashader do melonDS que funciona em Adreno/Android.
**Escopo:** revisar **A** (`agent_a_vulkan.md`), **B** (`agent_b_librashader.md`) e **D** (`agent_d_present.md`).
A própria proposta (C) não é revisada.
**Fontes verificadas de novo nesta revisão:**
- melonDS `VulkanSurfacePresenter.cpp` (`runRetroArchFilter:2127–2393`, `imageBarrier:2255–2280`,
  `submitAndWait:2228–2243`, blit `topOutput→atlasOutput:2357–2383`, atlas→`GENERAL:2383`,
  saída do filtro = atlas `2390–2391`, descriptor `GENERAL:2625`, barreira de presente `GENERAL→GENERAL:3094–3142`,
  swapchain só `COLOR_ATTACHMENT:1484`, `createRetroArchImage:1980–2038`).
- GameNative: swapchain atual com `imageUsage = COLOR_ATTACHMENT_BIT` somente
  (`VulkanRendererContext.cpp:279`); WIP `6a648093`: compositor transiciona `processedImage UNDEFINED→CAO`
  (`recordCompositorPass`, linhas 1783–1796), `applyFrame` em 1042, transição `CAO→SRO` em 1051–1054.
- Contrato librashader: `librashader.h:1705–1720` ("no barrier for the final pass; output permanece em CAO").
- **Observação:** o diretório `/home/annapaula/GameNative/librashader/` (citado como fonte por A e B)
  **não existe mais** no repo; apenas `app/build/generated/librashader/include/librashader.h` (header) é verificável.

---

## Fatos-âncora do melonDS que TODAS as hipóteses precisam explicar

1. **O melonDS lê a saída do filtro do librashader (`topOutput`) por `vkCmdBlitImage` (transfer),
   no MESMO command buffer, contiguamente após `recordFrame`** (2353–2383), e **funciona em Adreno**.
   Isto **refuta diretamente** a família "leitura por transfer/sampler da saída do filtro é fundamentalmente
   quebrada no Adreno" (agente D H2) e "apenas copy-to-host força o commit" (agente B H1).
2. O blit de saída usa barreiras com **`oldLayout = resource.layout` (rastreado, 2260)**,
   **`srcAccessMask = MEMORY_WRITE|TRANSFER_WRITE|COLOR_ATTACHMENT_WRITE` (2258)** e
   **`srcStage = ALL_COMMANDS` (2270)** — ou seja, o post-filter barrier do melonDS tem **srcAccess bem
   largo incluindo COLOR_ATTACHMENT_WRITE** e srcStage ALL_COMMANDS, indo para `TRANSFER_READ/TRANSFER`.
3. **O melonDS NUNCA amostra a saída do filtro diretamente.** Ele a blita (transfer) para um **atlas
   dedicado** (`atlasOutput`, imagem própria do app, 1995–1998) e só então amostra o **atlas por sampler**,
   em `GENERAL` (2625, 3094–3142), num **CB posterior** a um `submitAndWait` (fence esperado na CPU, 2241–2242).
4. A imagem amostrada pelo presente (`atlasOutput`) é **escrita por TRANSFER** (2381–2383), não por render
   pass; e o app é **dono integral do layout dela**.
5. Swapchain do melonDS: `imageUsage = COLOR_ATTACHMENT_BIT` apenas (1484). Nada é blitado para ela.

**Consequência para a revisão:** o vetor causal "caminho de leitura" (sampler vs transfer vs copy-to-host)
está **descartado como causa raiz** — o melonDS faz transfer da saída do filtro e funciona. O que separa o
melonDS (funciona) do GameNative (preto) é: **(i)** o GameNative lê/presenta a saída do filtro **diretamente
para a swapchain** (o melonDS nunca faz isso), **(ii)** o GameNative não usa um intermediário dedicado cujo
layout é 100% do app, **(iii)** o GameNative nunca apresentou via `GENERAL`.

---

## Revisão do AGENTE A (`agent_a_vulkan.md`)

### Consistência com a evidência
- **H1 (divergência de layout pós-frame):** explica o quadro observacional (toda leitura do mesmo frame preta,
  readback do frame seguinte OK, offscreen OK). Porém o **mecanismo central ("estado real ≠ CAO no Adreno")
  é contradito pelo melonDS**: o melonDS faz `CAO→TRANSFER_SRC` sobre a saída do filtro (`topOutput`,
  2358) partindo do **mesmo layout assumido CAO** e funciona. Se o estado pós-frame divergisse de CAO no
  Adreno, o blit do melonDS também leria lixo. A divergência real, se existe, não é de `oldLayout` da
  transição — é de **estado interno do driver pós-resolve/clean**, e o melonDS resolve isso com o **atlas
  intermediário** (H2 do próprio A), não mudando o `oldLayout`.
- **H2 (atlas intermediário + submit-and-wait):** **plenamente consistente** — é o padrão melonDS
  (blit `topOutput→atlasOutput` 2361–2382, fence na CPU 2241–2242, presente do atlas depois 2390–2391).

### Falha técnica
- **Probe da H1 com `oldLayout = UNDEFINED`:** transição partindo de `UNDEFINED` instrui o driver a
  **não preservar o conteúdo** da imagem. Resultado "mostrou conteúdo" é informativo; resultado "preto"
  é **inconclusivo** (pode ser descarte, não causa). O próprio A reconhece com o ⚠️ — mas o experimento
  como "refuta H1 se continuar preto" não é válido.
- **Diagnóstico "logar oldLayout do readback":** é o ponto forte de A — barato e discrimina. Porém o
  readback que funciona (frame seguinte) faz sua **própria** transição até `TRANSFER_SRC_OPTIMAL` num CB
  one-time separado com `endOneTime` (fence + wait `UINT64_MAX`, `VulkanRendererContext.cpp:470–477`);
  se ele usa `oldLayout=CAO` e funciona, isso **contradiz a H1** (o CAO estaria correto). Vale o teste.

### Testabilidade
- Log do readback: barato (~30 min), discrimina H1 vs H2. Probe UNDEFINED: barato mas inconclusivo se preto.
- H2 (atlas): ~3–6h, diretamente comparável ao melonDS.

### Conformidade anti-solução
- H2: ✅ conforme (não toca alpha, `TRANSFER_DST` da swapchain, barreiras largas, dynamic rendering,
  prebuilts, TEST MODE como resposta). H1: ✅ conforme às 12 anti-soluções (mudar `oldLayout` não é
  "alargar barreiras").

### Objeções fortes
1. H1 como causa raiz está refutada pelo fato-âncora 1: o melonDS faz a mesma transição `CAO→TRANSFER_SRC`
   na saída do filtro e funciona.
2. O probe UNDEFINED tem falso-negativo por descarte de conteúdo — interpretação ambígua.
3. H1 e H2 coexistem confusamente: o diagnóstico que discrimina é o "logar oldLayout do readback" (barato),
   não o probe UNDEFINED.

### Veredito
**Apoiar com ressalvas.** H2 (atlas intermediário) = padrão melonDS provado → apoiar. H1 como causa raiz →
rejeitar (contradita pelo melonDS), mas **manter o diagnóstico barato** (logar `oldLayout`/`srcImageLayout`
do readback que funciona) como experimento de discriminação. Não executar o probe UNDEFINED.

---

## Revisão do AGENTE B (`agent_b_librashader.md`)

### Consistência com a evidência
- **H1 ("só copy-to-host força o commit; leitura image→image lê GMEM não-resolvido"):** **contradita pelo
  fato-âncora 1**. O melonDS lê `topOutput` (saída do filtro) por **`vkCmdBlitImage` image→image no mesmo
  CB** e funciona. Se "leituras image→image não disparam o resolve", o atlas do melonDS estaria preto.
- **H2 (GENERAL é o layout tolerado):** **consistente com o melonDS** — 100% das amostras do melonDS são
  `GENERAL` (2625, 3094–3142). É a observação mais acertada de B.
- **Passo 3 (atlas):** padrão melonDS fiel (criar atlas `TRANSFER_DST|TRANSFER_SRC|SAMPLED` 1995–1998,
  blit no mesmo CB 2361–2382, `submitAndWait` 2241–2242, presente do atlas em GENERAL). ✅

### Falha técnica
1. **Citação de código-fonte inexistente:** B cita internals de `librashader-runtime-vk/src/...` em
   `/home/annapaula/GameNative/librashader/`, que **não existe** (verificado). O único verificável é o
   contrato do header (`librashader.h:1705–1720`). As afirmações (begin_pass `UNDEFINED→CA`,
   `loadOp=CLEAR`, sem `end_pass` no pass final, `residuals[frame%N].dispose()`) não podem ser conferidas
   no repo — devem ser marcadas como **não verificáveis** antes de basear decisões.
2. **H1 ignora o srcAccess do blit do melonDS:** o blit que funciona no melonDS usa
   `srcAccess = MEMORY_WRITE|TRANSFER_WRITE|COLOR_ATTACHMENT_WRITE` + `srcStage = ALL_COMMANDS`
   (2258/2270). Se a tentativa 3.11 do GameNative usou máscaras estreitas (ex. `COLOR_ATTACHMENT_WRITE →
   TRANSFER_READ`), o parâmetro **nunca testado** não é "alargar barreiras de 3.8" (que era o caminho
   SAMPLER, não TRANSFER) — é uma barreira de **transfer-read larga**, que é exatamente a do melonDS e
   que **não é anti-solução 6** (3.8 testou sampler, não transfer). Isso é um experimento barato e legítimo
   que B não isolou.
3. A leitura do clear `[0,0,0,0]` como explicação do preto é compatível com a evidência, mas o melonDS
   prova que o resolve do último pass **não fica pendente** quando a barreira de saída tem srcAccess largo.

### Testabilidade
- **E1 (readback no mesmo frame):** barato (~1h) e **excelente discriminador** — se o copy-to-host no
  mesmo frame lê conteúdo, refuta a parte "só depois de frames completos" da H1. Apoiar fortemente.
- **E2 (probe GENERAL):** barato, mas **não discrimina** H1×H2 de forma limpa se o blit de 3.11
  continuar preto — GENERAL no sampler não cobre o transfer blit. Usar como teste de contribuinte.
- **E3 (atlas):** padrão melonDS; ~1 dia; o fix robusto sob qualquer hipótese.

### Conformidade anti-solução
- ✅ conforme às 12; E1/E2 são instrumentação, não fix; E3 não toca swapchain `TRANSFER_DST`, alpha,
  dynamic rendering, barreiras largas como solução.

### Objeções fortes
1. H1 é refutada pelo fato-âncora 1 (melonDS lê a saída do filtro por transfer no mesmo CB).
2. Fontes do librashader citadas não existem no repo → validação impossível sem re-fetch.
3. B não nota que o **parâmetro de barreira transfer-read larga do melonDS (2258/2270) nunca foi testado
   no GameNative** no caminho de transfer (3.11), e que isso é barato e **não** cai na anti-solução 6.

### Veredito
**Apoiar com ressalvas.** Rejeitar H1 como mecanismo (contradita pelo melonDS). Apoiar **E1** (readback
same-frame) e **E3** (atlas) como caminho. **Adicionar experimento novo**: barreira pós-filtro com
`srcAccess = MEMORY_WRITE|TRANSFER_WRITE|COLOR_ATTACHMENT_WRITE`, `srcStage = ALL_COMMANDS`,
`dst = TRANSFER_READ`, `dstStage = TRANSFER`, e então `vkCmdBlitImage` de `processedImage` para uma
imagem dedicada (não a swapchain). Marcar todas as citações de internals do librashader como não
verificáveis até o fonte ser re-obtido.

---

## Revisão do AGENTE D (`agent_d_present.md`)

### Consistência com a evidência
- **Seção 2 (rigor de ordenação):** **excelente e correta** — o fence de 3.10 (espera `filterFence`) já
  refuta "race de execução Vulkan-legal"; o readback que funciona também cruza submission. Isso está
  alinhado com o melonDS (o fence da CPU em 2241–2242 não é o que torna o atlas legível; é a topologia).
- **H1 (transição no-op por oldLayout divergente):** mesmo defeito do A-H1 — contradita pelo melonDS que
  usa o CAO assumido e funciona (fato-âncora 1).
- **H2 (visibilidade postergada; só copy-to-host comita):** contradita pelo melonDS (blit image→image
  da saída do filtro funciona). A nota "leitura imagem→imagem não aciona o commit" é factualmente falsa
  no Adreno, dada a referência que funciona.

### Falha técnica
1. **E0 é um no-op ou uma repetição de 3.10:** o texto descreve inserir `DeviceWaitIdle` **entre gravar
   `applyFrame` e gravar o blit no MESMO `filterCmdBuf`**. Como o CB só é submetido depois, esperar a
   GPU no meio da gravação **não ordena nada** (nada foi submetido ainda). Se a intenção era split
   (end/submit/wait/begin), é exatamente a tentativa 3.10 que falhou. E0 não adiciona informação.
2. **E2 é inviável sem violar a anti-solução 4:** `vkCmdBlitImage(processedImage → swapchain, TRANSFER_DST)`
   exige que as imagens da swapchain tenham `VK_IMAGE_USAGE_TRANSFER_DST_BIT`. A swapchain atual tem
   **apenas `COLOR_ATTACHMENT_BIT`** (`VulkanRendererContext.cpp:279`) e 3.11 provou que adicionar
   `TRANSFER_DST_BIT` não resolve. O próprio D diz "sem mudar imageUsage", o que é **ilegal/UB** para um
   destino de transfer blit. E2 ou viola a anti-solução 4 ou é inválido.
3. **Foco no lado do presente:** D analisa o lado swapchain, mas a evidência (TEST MODE) mostra que o
   blit de `offscreenImage → swapchain` funciona com o MESMO código de swapchain (3.5). O problema é o
   **estado/layout da fonte** (`processedImage`), não a swapchain — o melonDS usa swapchain só como
   `COLOR_ATTACHMENT` (1484) e nunca blita para ela.

### Testabilidade
- **E1 (readback same-frame):** barato e bom (mesmo do B-E1). Apoiar.
- **E0:** inútil como descrito (no-op). **E2:** inválido com a swapchain atual (usage) e anti-solução 4.

### Conformidade anti-solução
- E2 viola o espírito da anti-solução 4 (re-introduz `TRANSFER_DST` da swapchain, ainda que "sem mudar
  imageUsage" — o que é tecnicamente impossível para transfer blit). Demais itens ✅.
- E0 não viola explicitamente, mas é um no-op — não é experimento válido.

### Objeções fortes
1. O rigor de ordenação (seção 2) é o melhor dos três e deve ser mantido.
2. E0 e E2 têm falhas técnicas concretas (no-op na gravação; usage da swapchain). E2 deve ser **reescrito**
   para blitar para uma **imagem dedicada** (padrão melonDS), não para a swapchain.
3. H1/H2 compartilham a contradição do fato-âncora 1 (melonDS lê a saída do filtro por transfer no mesmo CB).

### Veredito
**Rejeitar como proposto, aproveitar a semente certa.** A seção 2 (refutação do race) é correta e vale
referenciar. E1 (readback same-frame) é barato e deve ser executado. E0 (no-op) e E2 (swapchain usage /
anti-solução 4) devem ser descartados; o equivalente útil de E2 é **blit para imagem dedicada** (padrão
melonDS), que converge com A-H2/B-E3.

---

## Síntese (família H1/H2 e o que sobrevive ao escrutínio do melonDS)

| aspecto | A | B | D |
|---|---|---|---|
| H1 "layout/oldLayout divergente" | refutada (melonDS usa CAO e funciona) | — | refutada (idem) |
| H2 "só copy-to-host comita / image→image não comita" | — | refutada (melonDS blita image→image) | refutada (idem) |
| "GENERAL é o layout de amostragem do melonDS" | não explorado | ✅ correto (2625, 3094–3142) | parcial |
| **Atlas intermediário + submit-and-wait** | ✅ (H2) | ✅ (E3) | fallback |
| **Barreira transfer larga pós-filtro (2258/2270)** | não isolado | não isolado (ver ressalva) | não isolado |
| Readback same-frame (E1) | diagnóstico indireto | ✅ | ✅ |
| Melhor experimento barato | log oldLayout do readback | E1 + barreira transfer larga | E1 |

**O que sobrevive (consenso a partir da referência que funciona):**
1. O melonDS **nunca** apresenta a saída do filtro diretamente; copia para um atlas dedicado no mesmo CB e
   amostra o atlas depois (fato-âncora 3). A topologia (intermediário) é o vetor com suporte empírico.
2. O layout de amostragem da referência é **GENERAL** (2625, 3094–3142) — nunca testado no GameNative.
3. O blit de saída do melonDS usa barreira com srcAccess incluindo `COLOR_ATTACHMENT_WRITE` e srcStage
   `ALL_COMMANDS` (2258/2270) — parâmetro **não testado** no caminho transfer do GameNative (3.11) e que
   **não** é a anti-solução 6 (3.8 era o caminho sampler).
4. Baratos e seguros: **E1 readback same-frame**, **logar oldLayout do readback que funciona**,
   **probe GENERAL**, **barreira transfer larga + blit para imagem dedicada** (não a swapchain).
5. Descartar: probe `UNDEFINED` (descarta conteúdo), E0 (no-op), E2→swapchain (usage / anti-solução 4).
