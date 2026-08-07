# Hipótese — AGENTE A (Vulkan/pipeline, Adreno 650, layout & GMEM) — **VERSÃO REVISADA (Fase 3)**

**Escopo:** explicar por que `processedImage` (saída do librashader) é legível apenas por
readback por transfer num command buffer one-time separado, no frame seguinte, e **preto** em
TODA leitura no mesmo frame (sampler blit e transfer blit `vkCmdBlitImage`, mesmo ou split
submission). Nenhuma implementação é proposta aqui — apenas hipóteses, experimentos baratos e
conformidade com as anti-soluções. Esta é a revisão da Fase 3, feita à luz das críticas de B, C e D.

**Fontes lidas na Fase 3 (re-verificadas por nome):**
- `docs/hypotheses/cross-review.md` — fatos 1–7, rejeições técnicas (§3), correções de evidência (§4), experimentos aprovados (§5), fix de consenso (§6).
- `docs/hypotheses/review_b_librashader.md`, `review_c_melonds.md`, `review_d_present.md` (seções "AGENTE A").
- `docs/librashader-failed-attempts.md` (tabela de evidências §4, fatos §5, anti-soluções §6, pistas §7).
- `app/src/main/cpp/winlator/VulkanRendererContext.cpp/.h` (estado base pós-revert).
- Integração reverter: blobs do commit dangling `6a648093` (WIP com `applyFrame`, `recordCompositorPass`, transições). **Nota:** esse blob é ANTERIOR ao fix 3.5 (o `offscreenRenderPass` nele tem `finalLayout=SHADER_READ_ONLY_OPTIMAL`; o doc 3.5 descreve o estado corrigido `COLOR_ATTACHMENT_OPTIMAL`). Cito o estado atual conforme o doc, e a estrutura conforme o blob.
- `app/build/generated/librashader/include/librashader.h` (C API v0.12, ABI 2). **Nota F3:** o diretório `/home/annapaula/GameNative/librashader/` (citado antes como fonte de internals) **não existe mais** no repo — só o header gerado é verificável; nenhuma afirmação de internals do librashader é tratada como fato nesta revisão.
- Referência melonDS: `VulkanSurfacePresenter.cpp` (blob `tool_fbaacb4a9001U7qREoNHruNWS9`), `runRetroArchFilter`.

---

## 0. O que mudou nesta versão (resumo da revisão Fase 3)

1. **H1 forma forte ("o driver não rastreia CA → transição CA→SRO é no-op") foi REMOVIDA como mecanismo.** Contraria a semântica de `finalLayout` (o estado pós-render-pass É o `finalLayout`, e o driver o rastreia deterministicamente) e é contradita pelo controle melonDS (que parte do mesmo CA assumido e funciona). A transição WIP:1051-1054 é spec-correta.
2. **H1 foi rebaixada a pergunta falsificável (H1')**, fundida na família H2/atlas: o que precisa ser testado para sustentá-la é declarado explicitamente (log da barreira e do readback), sem alegação de comportamento de driver não fundamentada.
3. **O probe `UNDEFINED` foi rebaixado a sanity check**, com o falso-negativo declarado (`oldLayout=UNDEFINED` instrui a não preservar conteúdo; "preto" é inconclusivo). Deixou de ser discriminador.
4. **O log da posição do readback e o readback in-frame foram elevados a prioridade máxima** (P1 e P2).
5. **Hipótese primária eleita: a família H2/atlas** (padrão melonDS), que é a única com evidência positiva (referência que funciona) e contorna a variável residual "destino".

---

## Quadro de evidências a explicar (doc §4, com duas correções F3)

| leitura | resultado |
|---|---|
| `offscreenImage` → sampler blit → swapchain | ✅ (após fix 3.5) |
| `offscreenImage` → transfer blit → swapchain | ✅ |
| `processedImage` → transfer readback (one-time CB, frame seguinte) | ✅ conteúdo real (`ff0c1028`) — **⚠️ F3: posição relativa ao `applyFrame` do frame N+1 NÃO foi logada** |
| `processedImage` → sampler blit (mesmo submission) | ❌ preto |
| `processedImage` → sampler blit (cross-submission/split) | ❌ preto |
| `processedImage` → transfer blit (mesmo submission) | ❌ preto |
| `processedImage` → transfer blit (split) | ❌ (relato de usuário, SEM log — **⚠️ F3: não verificado**) |

Fatos estabelecidos (doc §5): não é librashader (renders OK), não é alpha, não é formato,
não é sampler-vs-transfer isolado, não é split isolado, não é external sync, não é a composição.

**Diferença estrutural-chave:** `offscreenImage` é escrito pelo compositor do GameNative
(`offscreenRenderPass`, `finalLayout = COLOR_ATTACHMENT_OPTIMAL`) e, no fluxo que funciona,
a escrita é sempre **submetida + fenced** (`filterFence` + `WaitForFences`, WIP:1023-1032)
antes de qualquer leitura. `processedImage` é escrito **internamente pelo librashader** nos
render passes dele, e a leitura no fluxo que falha ocorre **no mesmo `filterCmdBuf` da escrita**
(WIP:1042-1056).

**Correção de evidência F3 (cross-review §4.1):** "o frame seguinte materializa o resolve" **não
está estabelecido**. `processedImage` é reescrito todo frame; sem logar a posição do READBACK-P
relativa ao `applyFrame` do frame N+1 (antes/depois da transição de reescrita), não se sabe se o
conteúdo lido é do frame N ou do frame N+1, nem se o atraso é causa ou consequência. Este é o
primeiro log a fazer.

---

## Fatos-âncora de revisão que qualquer hipótese precisa explicar (F3)

1. **melonDS lê a saída do filtro (`topOutput`) por `vkCmdBlitImage` (transfer) no MESMO command buffer, contiguamente após `recordFrame`** (2353-2383) e **funciona no Adreno 650**. Isto refuta a família "leitura in-frame da saída do filtro é incoerente" e "só copy-to-host comita o resolve" como afirmações gerais.
2. **A barreira desse blit usa `oldLayout = resource.layout` (rastreado, 2260), `srcAccessMask = MEMORY_WRITE|TRANSFER_WRITE|COLOR_ATTACHMENT_WRITE` (2258) e `srcStage = ALL_COMMANDS` (2270)** — parâmetro **nunca testado** no caminho transfer do GameNative (3.11 testou o caminho sampler em 3.8 com máscaras estreitas). Não é a anti-solução 6.
3. **melonDS NUNCA amostra a saída do filtro diretamente**: blita para um **atlas dedicado** (`atlasOutput`, imagem própria do app, 1995-1998), apresenta o atlas em **`GENERAL`** (2625, 3094-3142) num CB posterior a um `submitAndWait` (fence UINT64_MAX na CPU, 2241-2242). Swapchain do melonDS: `COLOR_ATTACHMENT` apenas (1484).
4. **Variável residual não explicada: destino.** Toda leitura que falha tem destino = swapchain; toda leitura que funciona (melonDS atlas, READBACK-P) nunca lê para a swapchain no mesmo ciclo.
5. **`processedImage` é reescrito todo frame** — a ambiguidade do "frame seguinte" (item acima) não está resolvida.
6. **`offscreenImage` é lido in-frame com sucesso** (TEST MODE, 3.5/3.6) — o problema é específico do `processedImage`, não da composição/present.

---

## Hipótese 1 REVISADA — H1': o estado do `processedImage` no momento da leitura (pergunta falsificável, não alegação de driver)

### 1.1 O que foi removido (refutado na Fase 3)

A forma forte — "a transição CA→SRO é no-op porque o driver não reconhece/rastreia
`COLOR_ATTACHMENT_OPTIMAL`" — é **insustentável** por três razões:

1. **Semântica de `finalLayout`:** o layout de uma imagem após um render pass É o `finalLayout`
   do pass (spec Vulkan); o driver o rastreia deterministicamente. Como o pass final do librashader
   tem `finalLayout = COLOR_ATTACHMENT_OPTIMAL` por contrato (librashader.h:1705-1720), o estado
   pós-pass é CA por construção, e a barreira WIP:1051-1054
   (`CA→SRO`, `COLOR_ATTACHMENT_WRITE→SHADER_READ`, `COLOR_ATTACHMENT_OUTPUT→FRAGMENT_SHADER`)
   é **spec-correta**. "O driver não rastreia o estado" é alegação de comportamento de driver
   **não fundamentada** e contraria a definição do modelo de memória.
2. **Controle melonDS (fato-âncora 1):** o melonDS transiciona a saída do filtro
   `CA→TRANSFER_SRC` partindo do **mesmo** CA assumido e **funciona** no mesmo Adreno. Se houvesse
   divergência estrutural de layout pós-frame no Adreno, o blit do melonDS leria preto também.
3. **Analogia 3.5 superestimada:** "3.5 provou que esta família é sempre layout" é heurística.
   3.5 corrigiu uma transição **ausente em código do próprio app** (offscreenRenderPass); o caso
   `processedImage` é saída de biblioteca de terceiros sob contrato diferente. Conflar os dois
   superestima a analogia.

### 1.2 O que permanece (espec-correto e falsificável)

Não há divergência de layout **por construção**. O que sobra é a pergunta sobre a **eficácia da
transição na sequência exata do GameNative** — propriedades verificáveis, não especulação de driver:

- A barreira WIP:1051-1054 foi gravada no `filterCmdBuf`, mas: (a) foi **realmente gravada** (sem
  early return que a pule)? (b) o `srcStageMask`/`dstStageMask` **efetivamente registrados** são os
  pretendidos e cobrem o stage de escrita do último pass do filtro? (c) está na posição/scope certo
  relativo ao `applyFrame` e ao blit?
- O readback que **funciona** (frame seguinte) faz a sua **própria** transição até
  `TRANSFER_SRC_OPTIMAL` num CB one-time separado com fence + wait `UINT64_MAX`
  (VulkanRendererContext.cpp:470-477). De que `oldLayout` ele parte?

A H1' só se sustenta na forma **fraca**: "divergência **específica da sequência do GameNative**"
(ainda não articulada — ex.: barreira gravada em posição/scope errado, ou um parâmetro de barreira
que o driver aceita mas interpreta como não-cobrindo a escrita do filtro). Para sustentá-la é
preciso o teste do 1.3; sem ele, H1' não explica o padrão de evidências.

### 1.3 O que precisa ser testado para sustentar (ou matar) H1' — experimentos

1. **P1 — log da posição e do `oldLayout`/`srcImageLayout` do readback que funciona** (~30 min).
   Logar: em que ponto do frame N+1 o READBACK-P executa (antes/depois da transição de reescrita do
   `processedImage`), o `oldLayout` usado na transição dele e o `srcImageLayout` passado ao
   `vkCmdCopyImageToBuffer`. Interpretação:
   - readback parte de `oldLayout = COLOR_ATTACHMENT_OPTIMAL` e funciona → o estado pós-filtro É CA
     → **H1' (divergência de layout) cai**, e o atraso de frame é a variável a explicar (família H2).
   - readback parte de `UNDEFINED`/outro layout → a interpretação do readback muda (ver nota 1.4).
2. **P2 — readback do `processedImage` no MESMO frame** (~1 h), imediatamente após `applyFrame`,
   antes de qualquer transição de reescrita. Separa "estado da fonte no momento da leitura"
   (H1'/layout) de "visibilidade postergada por ciclo" (família H2). Se lê conteúdo in-frame → o
   estado pós-filtro é coerente e o problema é o caminho/arquitetura de leitura (família H2). Se lê
   preto in-frame → problema de visibilidade in-frame, mas NÃO por divergência de layout (seria por
   outra causa, ex. cache/UBWC não-drenado — fora da alegação de driver removida).
3. **Logar a barreira WIP:1051-1054 real** (srcStageMask/dstStageMask registrados, ordem dos
   comandos no `filterCmdBuf`) para descartar ineficácia por escopo.

### 1.4 Nota sobre o readback e o probe `UNDEFINED`

- Se o readback que funciona usa `oldLayout=CAO`, ele **contradiz a forma forte de H1** de novo
  (o CAO estaria correto na transição dele) — vale como teste barato e discriminante.
- **Probe `UNDEFINED` rebaixado a sanity check:** `oldLayout=UNDEFINED` instrui o driver a **não
  preservar o conteúdo** da imagem; resultado "mostrou conteúdo" é informativo, mas resultado "preto"
  é **inconclusivo** (pode ser descarte, não causa). Falso-negativo declarado. Se usado, só como
  sonda rápida para confirmar que "a família transição/estado importa" — nunca como discriminador
  H1'×H2, e nunca para "refutar H1' se continuar preto" (não é um teste válido).

### 1.5 Observável esperado

P1 + P2 juntos fecham H1': se o readback in-frame parte de `CAO` e lê conteúdo, H1' morre e a
família H2/atlas é a única explicação estrutural restante. Se o readback in-frame lê preto, o
problema é de visibilidade in-frame (família H2), não de estado de layout.

---

## Hipótese 2 REVISADA — família atlas/H2: a hipótese primária eleita (F3)

### Mecanismo proposto (espec-correto, sem alegação de driver)

Dado que o estado CA é correto por contrato e que o melonDS (mesma GPU/stack) lê a saída do filtro
por transfer blit no mesmo CB e funciona, a diferença operacional entre melonDS (✅) e GameNative
(❌) é a **topologia de destino**, não o layout nem o caminho de leitura:

- **melonDS:** blita a saída do filtro (`topOutput`) para um **atlas intermediário dedicado**
  (imagem própria do app, layout 100% rastreado via `resource.layout`, iniciado `UNDEFINED`) **no
  mesmo CB do filtro** (2357-2383), com barreira de srcAccess largo (fato-âncora 2); submete e
  **espera o fence na CPU** (2241-2242); e **apresenta o atlas** (nunca a saída do filtro) num CB
  posterior, amostrando em **`GENERAL`** (2625, 3094-3142).
- **GameNative:** lê `processedImage` (saída do filtro) **direto → swapchain** — sampler
  (3.1/3.10) ou transfer (3.11/3.12) — sem intermediário e sem o padrão submit-and-wait do atlas.
  Toda leitura com **destino = swapchain** falha (fato-âncora 4).

Hipótese: **a leitura da saída do filtro com destino direto a swapchain é a configuração
problemática**; a topologia "atlas intermediário + submit-and-wait + present em GENERAL" é o padrão
que materializa o conteúdo de forma comprovada na mesma GPU. **Ressalva metodológica (cross-review
§6): NENHUM mecanismo específico (resolve GMEM postergado, cache/UBWC não-drenado, janela de
execução, destino/posse) foi provado como causa única** — o atlas é um fix robusto sob múltiplos
mecanismos, e o sucesso dele NÃO deve ser lido como confirmação de um mecanismo específico. Os
experimentos P1-P4 existem para identificar a causa; o atlas é o remédio.

### Componentes testáveis (em ordem de custo)

1. **P3 — probe GENERAL** (barato, teste de contribuinte, não discriminador isolado): transição
   `CAO→GENERAL` + descriptor com `imageLayout = VK_IMAGE_LAYOUT_GENERAL` no sampler do blit de
   `processedImage` (replicando melonDS:2625). Não cobre o caminho transfer (3.11), então se o blit
   continuar preto não refuta nada — só testa se o layout de amostragem contribui.
2. **P4 — barreira transfer-larga + blit para imagem dedicada** (barato, **NÃO é a anti-solução 6**):
   após `applyFrame`, barreira com `srcAccess = MEMORY_WRITE|TRANSFER_WRITE|COLOR_ATTACHMENT_WRITE`,
   `srcStage = ALL_COMMANDS`, `dst = TRANSFER_READ`, `dstStage = TRANSFER`; depois
   `vkCmdBlitImage(processedImage → imagem dedicada)` (NÃO a swapchain); por fim amostrar/apresentar
   a imagem dedicada. É o parâmetro exato do melonDS (2258/2270), **nunca testado** no caminho
   transfer do GameNative (3.8 foi o caminho sampler). Isola "destino dedicado" + "barreira larga
   pós-filtro" como variáveis do padrão melonDS.
3. **Atlas completo (fix de consenso, ~1 dia):** reimplantação fiel do padrão melonDS —
   - imagem intermediária dedicada com `TRANSFER_SRC|TRANSFER_DST|SAMPLED`, tiling OPTIMAL, layout
     rastreado pelo app (`resource.layout`, iniciado `UNDEFINED`);
   - cópia filtro→atlas **no mesmo CB** do `applyFrame`; `vkCmdCopyImage` (copy engine) como caminho
     **primário**, `vkCmdBlitImage` (transfer) como **variante de diagnóstico** (mitiga a
     auto-contradição: o fix não depende de uma "leitura frágil" da saída do filtro);
   - `submitAndWait` dedicado (End → QueueSubmit com queue lock → WaitForFences UINT64_MAX);
   - **present do atlas em `GENERAL`** num submission posterior;
   - swapchain volta a `COLOR_ATTACHMENT` apenas — **desvio consciente da letra da anti-solução 4**
     (reverter, não re-testar; ver tabela).

### Observável esperado

- `processedImage`→swapchain direto continua preto e o atlas/imagem dedicada mostra conteúdo → a
  família (destino/topologia) está confirmada. ⚠️ Isso confirma a família, NÃO um mecanismo específico.
- Se mesmo com a intermediária continuar preto → a família H2 é refutada e a investigação sai do
  escopo desta hipótese (resta: `use_dynamic_rendering`/issue upstream #225, fora das anti-soluções;
  não tocar sem investigação dedicada).

---

## Conformidade com as 12 anti-soluções (doc §6) — atualizada F3

| # | Anti-solução | Como esta proposta evita |
|---|---|---|
| 1 | Não rediagnosticar "librashader renderiza?" | Não re-testo isso; aceito `READBACK-P` como prova de que renderiza. |
| 2 | Não testar sampler vs transfer como causa raiz | Não trato sampler-vs-transfer como causa; ambas falham e explico via família de destino/topologia (H2), com H1' apenas como pergunta falsificável. |
| 3 | Não testar split de submissions sozinho | Não proponho split como solução; H2 é **intermediário + submit-and-wait**, não apenas split. |
| 4 | Não mexer em `TRANSFER_DST` da swapchain | Não adiciono/removo `TRANSFER_DST_BIT` da swapchain como experimento; a intermediária é uma imagem própria. **F3 — nota:** o fix de consenso prevê remover `TRANSFER_DST` da swapchain (voltar a só `COLOR_ATTACHMENT`, como melonDS:1484) — declaro como **desvio consciente da letra** desta anti-solução no contexto do fix de topologia (reverter, não re-testar), justificado por melonDS:1484. |
| 5 | Não mexer em alpha/compositeAlpha | Não toco em alpha nem `compositeAlpha` (já OPAQUE). |
| 6 | Não alargar mais barreiras `ALL_COMMANDS`/`MEMORY_WRITE` | **F3 —** não proponho alargar barreiras no caminho sampler (3.8 já falhou). P4 é o caminho **transfer** com o receituário exato da referência (melonDS:2258/2270), nunca testado — não é a anti-solução 6. |
| 7 | Não re-adicionar `queueMtx` como solução | Não uso `queueMtx` como solução; serialização já existe e é mantida por segurança. |
| 8 | Não reintroduzir prebuilts em `jniLibs` | Não mexo em `jniLibs`/prebuilts. |
| 9 | Não descomentar target `winlator` no CMake | Não toco no CMake targets. |
| 10 | Não reativar TEST MODE como resposta | Não reativo `gLibraTestBlitOffscreen` como resposta; uso readback/log/probe como **diagnóstico**, não fix. |
| 11 | Não assumir problema no layout do `offscreenImage` | Não toco no layout do `offscreenImage` (já corrigido em 3.5); o alvo é o estado pós-frame do `processedImage` (H1') e a topologia de leitura dele (H2). |
| 12 | Não mudar `use_dynamic_rendering` para `true` sem investigação | Não mudo `use_dynamic_rendering`; fica `false` como está (issue upstream #225 é fora de escopo). |

---

## Prioridade de experimentos (revisada Fase 3)

1. **P1 — log da posição + `oldLayout`/`srcImageLayout` do readback que funciona** (~30 min).
   Estabelece ou mata "o frame seguinte materializa o resolve"; discrimina H1' vs H2.
2. **P2 — readback do `processedImage` no MESMO frame** (~1 h). O experimento mais informativo
   faltante: separa estado/layout da fonte de visibilidade postergada por ciclo.
3. **P3 — probe GENERAL** (contribuinte; não discrimina isolado).
4. **P4 — barreira transfer-larga + blit para imagem dedicada** (parâmetro melonDS nunca testado;
   isola destino dedicado dentro da família H2).
5. **Atlas completo** (fix de consenso, ~1 dia): intermediária dedicada + submit-and-wait + present
   em GENERAL; copy engine como primário filtro→atlas.

P1 e P2 são independentes e podem rodar juntos; P3/P4 são desambiguações dentro da família H2;
o atlas é o remédio independente do mecanismo.

---

## Declaração de revisão da Fase 3

**O que mudou:**
- Removi a forma forte de H1 ("o driver não rastreia CA → transição é no-op") como mecanismo — ela
  contraria a semântica de `finalLayout` e é contradita pelo controle melonDS (mesmo CA assumido,
  mesma leitura da saída do filtro, funciona). Substituí por H1', uma pergunta falsificável sobre a
  eficácia da transição na sequência exata do GameNative, com o teste declarado (P1/P2/log da
  barreira).
- Rebaixei o probe `UNDEFINED` a sanity check, com o falso-negativo declarado; deixou de ser
  discriminador.
- Elevei a prioridade máxima o log da posição do readback (P1) e o readback in-frame (P2), que os
  três revisores apontaram como os experimentos mais informativos e que eu não havia priorizado.
- Incorporei P4 (barreira transfer-larga no caminho transfer, melonDS:2258/2270) e o aviso de que o
  sucesso do atlas não confirma mecanismo específico.
- Corrigi a tabela de evidências (posição do READBACK-P não logada; 3.12 sem log) e a nota de que
  `librashader/` não existe no repo (não cito internals como fato).

**O que mantive e por quê:**
- A **família H2/atlas** como hipótese central — é a única com evidência positiva disponível
  (melonDS funciona na mesma GPU) e contorna a variável residual "destino"; o consenso dos três
  revisores converge para ela.
- O diagnóstico "logar o `oldLayout` do readback que funciona" — os três revisores o elogiaram como
  o ponto forte da minha proposta e o melhor investimento barato; mantenho-o como primeiro
  experimento (P1).
- A **conformidade com as 12 anti-soluções** — mantida e atualizada (P4 ≠ anti-solução 6; nota do
  desvio consciente da anti-solução 4 no fix de topologia, alinhado ao consenso).
- A estrutura do documento (tabela de evidências, mecanismo, solução testável, observável,
  esforço/risco) — preservada para comparabilidade entre as fases.

**Posicionamento final (hipótese primária eleita por mim):** **H2/família atlas** — a leitura da
saída do filtro com destino direto a swapchain é a configuração problemática; o padrão melonDS
(intermediária dedicada + submit-and-wait + present em GENERAL, com copy engine como primário
filtro→atlas) é o fix de maior probabilidade. H1' permanece apenas como pergunta falsificável de
diagnóstico (P1/P2), fundida na mesma família, e não como hipótese concorrente de mecanismo.
