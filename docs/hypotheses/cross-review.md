# Cross-review consolidado — docs/hypotheses

**Fase 2 do processo de hipóteses.** Cada um dos 4 agentes (A=Vulkan, B=librashader, C=melonDS,
D=apresentação) revisou as propostas dos outros 3 (nunca a própria). Fontes primárias re-verificadas:
`docs/librashader-failed-attempts.md`, melonDS `VulkanSurfacePresenter.cpp` (blob
`tool_fbaacb4a9001U7qREoNHruNWS9`), `librashader.h` gerado em `app/build/generated/`, WIP da integração.

Arquivos de revisão individuais: `review_a_vulkan.md`, `review_b_librashader.md`,
`review_c_melonds.md`, `review_d_present.md`.

---

## 1. Fatos verificados que todas as hipóteses precisam explicar

1. **O melonDS lê a saída do filtro do librashader (`topOutput`) por `vkCmdBlitImage` (transfer) no MESMO
   command buffer, contiguamente após `recordFrame`** (linhas 2353-2383), e **funciona no Adreno 650**. Isto
   refuta a família "leitura da saída do filtro por textura/transfer é incoerente no Adreno" (C-H1, D-H2)
   e "só copy-to-host comita o resolve" (B-H1) como afirmações gerais.
2. **O blit de saída do melonDS usa barreiras com `oldLayout = resource.layout` (rastreado, 2260),
   `srcAccessMask = MEMORY_WRITE|TRANSFER_WRITE|COLOR_ATTACHMENT_WRITE` (2258) e `srcStage =
   ALL_COMMANDS` (2270)** — parâmetro **nunca testado** no caminho transfer do GameNative (3.11 testou o
   caminho sampler em 3.8 com máscaras estreitas). Não é a anti-solução 6.
3. **O melonDS NUNCA amostra a saída do filtro diretamente**: blita para um **atlas dedicado** (`atlasOutput`,
   imagem própria do app, 1995-1998), apresenta o atlas em **`GENERAL`** (2625, 3094-3142) num CB posterior
   a um `submitAndWait` (fence UINT64_MAX na CPU, 2241-2242). Swapchain melonDS: `COLOR_ATTACHMENT` apenas
   (1484); nada é blitado para ela.
4. **A variável residual que nenhuma hipótese explica conclusivamente: destino.** Toda leitura que falha tem
   destino = swapchain; toda leitura que funciona (melonDS atlas, READBACK-P copy engine) nunca lê para a
   swapchain no mesmo ciclo. O padrão atlas contorna o problema trocando o destino.
5. **A transição `CA→SRO` do GameNative (WIP:1051-1054) é spec-correta**: `finalLayout` do render pass final
   do librashader é `COLOR_ATTACHMENT_OPTIMAL` por contrato (`librashader.h:1705-1720`), logo `oldLayout=CA`
   é canônico. A forma forte de "transição no-op por layout divergente" (A-H1, D-H1) contraria a semântica de
   `finalLayout` e é contradita pelo melonDS (que parte do mesmo CA assumido e funciona).
6. **`processedImage` é reescrito todo frame** — a ambiguidade do "frame seguinte" na tabela de evidências
   (doc §4) não está resolvida: sem logar a posição do READBACK-P relativa ao `applyFrame` do frame N+1,
   "o atraso materializa o resolve" **não está estabelecido**. Log barato e prioritário.
7. **`offscreenImage` é lido in-frame com sucesso** (TEST MODE, 3.5/3.6) — o problema é específico do
   `processedImage`, não da composição/present.

## 2. Verdicts por agente

| hipótese | A | B | C | D |
|---|---|---|---|---|
| A-H1 (divergência layout pós-frame / GMEM) | — | apoiar com ressalvas (mecanismo fraco; diagnóstico de log forte) | rejeitar como causa (contradita melonDS), manter diagnóstico | apoiar com ressalvas (forma forte vulnerável) |
| A-H2 (atlas intermediário + submit-and-wait) | — | apoiar (forte) | apoiar (padrão melonDS) | apoiar com ressalvas |
| B-H1 (só copy-to-host comita resolve) | apoiar com ressalvas (mecanismo colapsa em H2) | — | rejeitar como mecanismo | apoiar com ressalvas (contradito melonDS) |
| B-H2 (GENERAL é layout tolerado) | apoiar (mais alinhada à evidência) | — | apoiar (verificado 2625, 3094-3142) | apoiar com ressalvas |
| C-H1 (textura incoerente vs copy engine) | rejeitar como mecanismo (auto-contradição: fix primário é a mesma leitura) | rejeitar como mecanismo (refutado melonDS) | — | apoiar com ressalvas (auto-contradição primário/fallback) |
| C-H2 (GENERAL contribuinte) | apoiar | apoiar | — | apoiar (limitado) |
| D-H1 (transição no-op) | apoiar (formulação mais limpa) | apoiar com ressalvas (herda defeito de spec) | rejeitar como proposto | — |
| D-H2 (visibilidade postergada; só copy comita) | rejeitar (GMEM implausível; refutado melonDS) | apoiar com ressalvas (contradito melonDS) | rejeitar | — |

**Convergência:** todos apontam para a mesma família (estado/layout do `processedImage` + caminho de
leitura/arquitetura) e para a mesma receita final (padrão melonDS: atlas intermediário + submit-and-wait +
present em GENERAL + layout tracking manual).

## 3. Rejeições técnicas duras (não re-propôr)

- "A transição CA→SRO é no-op porque o driver não rastreia CA" (forma forte de A-H1/D-H1): contraria
  `finalLayout` e o melonDS. Aceitável apenas como "divergência específica da sequência do GameNative",
  ainda não articulada.
- "Leitura da saída do librashader por textura/blit é incoerente no Adreno" (C-H1/D-H2/B-H1): **refutada pelo
  melonDS** (fato 1). O problema é específico da config GameNative.
- "Só copy-to-host comita o resolve / imagem→imagem não comita" (B-H1/D-H2): refutado (fato 1 e 3 — o atlas
  do melonDS é imagem→imagem e funciona).
- **E0 de D** (`DeviceWaitIdle` no meio da gravação do mesmo CB): no-op (nada foi submetido); se fosse split,
  é a tentativa 3.10. Descartar como discriminante; no máximo sanity check.
- **E2 de D** (blit `processedImage → swapchain`): inválido sem `TRANSFER_DST_BIT` na swapchain (279), que
  viola a anti-solução 4. Equivalente útil: blit para **imagem dedicada** (padrão melonDS).
- **Probe `UNDEFINED`** (A-H1): falso-negativo — descarta conteúdo; resultado "preto" é inconclusivo.
  Descartar como discriminador.
- **Citações de internals do librashader** (`filter_chain.rs`, `framebuffer.rs`, etc.): **não verificáveis**
  no repo (dir `/librashader/` não existe; só o header gerado é fonte). Marcar como plausíveis, não fatos,
  até o fonte ser restaurado.

## 4. Correções de evidência (baratas, prioridade máxima, afetam todas)

1. **Logar a posição do READBACK-P relativa ao `applyFrame`** (antes/depois da transição do frame N+1) e o
   `oldLayout`/`srcImageLayout` usado. ~30 min. Estabelece ou mata "o frame seguinte materializa o resolve".
2. **Readback do `processedImage` no MESMO frame** (E1 de B/D, §7.2 de C): separa "estado da fonte no
   momento da leitura" (layout) de "visibilidade postergada" (timing). O experimento mais informativo
   faltante. ~1h.

## 5. Experimentos baratos aprovados (em ordem de prioridade)

- **P1** — log da posição/oldLayout do readback (item 4.1).
- **P2** — readback in-frame do `processedImage` (item 4.2).
- **P3** — **probe GENERAL**: transição `CAO→GENERAL` + descriptor `GENERAL` no sampler (replicando melonDS
  2625), sem depender de tracking que não existe. Barato, discrimina layout-assumido vs caminho de leitura.
- **P4** — **barreira transfer-larga pós-filtro** (`srcAccess = MEMORY_WRITE|TRANSFER_WRITE|COLOR_ATTACHMENT_WRITE`,
  `srcStage = ALL_COMMANDS`, `dst = TRANSFER_READ`, `dstStage = TRANSFER`) + `vkCmdBlitImage` de
  `processedImage` → **imagem dedicada** (NÃO a swapchain). Parâmetro do melonDS (2258/2270) nunca testado no
  caminho transfer do GameNative; não é a anti-solução 6.

## 6. Fix de maior probabilidade (consenso)

**Reimplantação fiel do padrão melonDS** (A-H2 / B-E3 / C-fix / D-fallback):
- imagem intermediária dedicada (`atlasOutput`) criada pelo app com `TRANSFER_SRC|TRANSFER_DST|SAMPLED`,
  tiling OPTIMAL, layout 100% rastreado pelo app (`resource.layout`, iniciado `UNDEFINED`);
- blit da saída do filtro (`processedImage`/`topOutput`) para o atlas **no mesmo command buffer** do
  `applyFrame` (com barreira transfer-larga do item P4);
- `submitAndWait` dedicado (End → QueueSubmit com queue lock → WaitForFences UINT64_MAX);
- **present do atlas em `GENERAL`** num submission posterior;
- `vkCmdCopyImage` (copy engine) como caminho primário filtro→atlas, com blit transfer como variante de
  diagnóstico (mitiga a auto-contradição C: o fix não depende da "leitura frágil").
- swapchain volta a `COLOR_ATTACHMENT` apenas — **declarado como desvio consciente da letra da anti-solução
  4** (reverter, não re-testar), justificado pelo melonDS:1484.

**Nenhuma das hipóteses de mecanismo foi provada como causa única** — o atlas é um fix robusto sob múltiplos
mecanismos, mas o sucesso dele NÃO deve ser lido como confirmação de mecanismo específico (C-H1, B-H1, D-H2).
Os experimentos P1-P4 existem para identificar a causa; o atlas é o remédio.

## 7. Anti-soluções — status após cross-review

- B, C, D conformes às 12; tensões menores registradas: anti-4 (remoção do `TRANSFER_DST` da swapchain por
  C/consenso) e anti-6 (`ALL_COMMANDS` do `imageBarrier` replicado de melonDS) — ambas aceitáveis quando
  apresentadas como "receita da referência" e não como "o fix".
- P4 (barreira transfer-larga) **não** é anti-solução 6: 3.8 testou o caminho sampler; P4 é o caminho
  transfer com o receituário exato da referência que funciona.
