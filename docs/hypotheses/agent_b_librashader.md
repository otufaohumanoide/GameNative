# Agente B — Hipóteses REVISADAS (Fase 3): estado de layout do `processedImage` após `libra_vk_filter_chain_frame`

**Autor:** Agente B (internals do librashader v0.12.0, ABI 2, `filter_chain_vk::frame()`).
**Escopo:** pesquisa apenas; nenhum código de implementação proposto como definitivo, apenas experimentos e padrões.
**Fase:** revisão da própria proposta à luz do cross-review (A/C/D) e do `cross-review.md`. Alterações sinalizadas
por **REVISADO** quando diferem da versão anterior.

**Fatos de ancoragem (não são hipóteses; verificados por log — `librashader-failed-attempts.md` §4):**
- READBACK-P (transfer readback, one-shot, submission separada, frame seguinte) lê conteúdo válido do
  `processedImage` (`ff0c1028`…). O librashader **está renderizando**.
- Nenhuma leitura no mesmo frame/pipeline exibiu a imagem: sampler blit (mesmo e cross-submission) preto,
  transfer blit preto.
- `offscreenImage` (escrito pelo compositor GameNative, render pass com `finalLayout=COLOR_ATTACHMENT_OPTIMAL`)
  é lido perfeitamente pelos mesmos caminhos (3.5/3.6).
- Barreiras alargadas (`ALL_COMMANDS|MEMORY_WRITE→SHADER_READ`, 3.8, caminho **sampler**) não mudaram nada →
  não é fraqueza de dependência/ordering genérica.

---

## 0. Marco metodológico: verificado vs. não verificável **REVISADO**

O cross-review apontou que o diretório `/home/annapaula/GameNative/librashader/` **não existe** no repo atual
(verificado: vazio/ausente). Portanto **toda citação de internals do `librashader-runtime-vk/src/*.rs` é
plausível, mas não verificável** até o fonte ser restaurado. Deste documento, apenas estas fontes são **verificadas**:

- **[V] `app/build/generated/librashader/include/librashader.h:1705-1720`** (header gerado, no repo): "A pipeline
  barrier **will not** be created for the final pass. The output image must be in `VK_COLOR_ATTACHMENT_OPTIMAL`,
  and will remain so after all shader passes. The caller must transition the output image to the final layout."
  (1705-1707); entrada em `VK_SHADER_READ_ONLY_OPTIMAL` (1717); saída em `VK_COLOR_ATTACHMENT_OPTIMAL` (1719).
- **[V] melonDS `VulkanSurfacePresenter.cpp`** (blob local `tool_fbaacb4a9001U7qREoNHruNWS9`, único arquivo):
  swapchain `COLOR_ATTACHMENT` apenas (1484); `createRetroArchImage` `TRANSFER_SRC|TRANSFER_DST|COLOR_ATTACHMENT|SAMPLED`,
  OPTIMAL, `initialLayout=UNDEFINED` (1986-2000); tracking manual `resource.layout` (2036, 2255-2280); `submitAndWait`
  End→QueueSubmit sob lock→`WaitForFences(UINT64_MAX)` (2228-2243); `imageBarrier` `srcAccess =
  MEMORY_WRITE|TRANSFER_WRITE|COLOR_ATTACHMENT_WRITE`, `srcStage = ALL_COMMANDS`, `oldLayout = resource.layout`
  (2258/2270); **blit `topOutput → atlasOutput` no MESMO CB do filtro** (2353-2382); atlas → `GENERAL` (2383);
  present do atlas em `GENERAL` em submission posterior (2390-2391, 2625, 3102-3103).

- **[NV] Não verificável no repo atual** (marcado em cada menção): `filter_chain.rs` (pass final via
  `pass.draw(…, QuadType::Final, false)` sem `out.output.end_pass`; `push_history` como último comando;
  `residuals[frame % N].dispose()`), `framebuffer.rs` (`begin_pass` `UNDEFINED→CA`, `end_pass` `CA→SRO`),
  `render_pass.rs` (`initial=final=CA`, `loadOp=CLEAR`, `storeOp=STORE`), `graphics_pipeline.rs` (clear
  `[0,0,0,0]`), `filter_pass.rs` (`draw()`), e `librashader-runtime-vk/tests/hello_triangle/mod.rs`.
  São **inferências coerentes com o contrato do header**, não fatos; revalidar quando o fonte for re-obtido.

---

## 1. Hipóteses de causa raiz (revisadas)

### H2 — **mantida como principal**: divergência de layout/estado efetivo da fonte; `GENERAL` é o layout de amostragem verificado da referência que funciona

**Mecanismo:** a saída do pass final permanece em `COLOR_ATTACHMENT_OPTIMAL` por contrato ([V] 1705-1720) — mas o
estado **efetivo** que o driver Adreno reconhece para essa imagem, na janela pós-`frame()` do GameNative, não se
comporta como "legível por textura" com a transição assumida. O melonDS — a referência que **funciona no mesmo
Adreno 650** — nunca amostra a saída do filtro direto no estado pós-filtro: blita para um atlas dedicado e
apresenta **100% das imagens em `VK_IMAGE_LAYOUT_GENERAL`**, com descriptor `imageLayout=GENERAL` ([V] 2625) e
tracking manual de layout por imagem ([V] `resource.layout`, 2255-2280). `GENERAL` é o layout mais permissivo do
Vulkan; o GameNative só amostra `SHADER_READ_ONLY_OPTIMAL` e nunca experimentou `GENERAL` nem um intermediário
dedicado.

**Status após cross-review:** A, C e D concordam que é a observação mais alinhada à evidência e verificável no
melonDS. **Ressalva aceita (D):** o mecanismo exato continua em aberto — `GENERAL` pode ser o sintoma do layout
efetivo divergente **ou** apenas o layout que o melonDS usa junto de outras variáveis (destino/intermediária).
Por isso H2 é testada por E2 **e** isolada por E1/E4, sem afirmar exclusividade.

### H1 — **REBAIXADA a "variação não distinguida" da família de layout** (não é mais causa independente)

A versão anterior afirmava: "o resolve do attachment final fica pendente e a leitura in-frame devolve o tile
clearado `[0,0,0,0]`; só a cópia device→host num CB one-shot comita o resolve". Após o cross-review, esta
formulação **não é spec-defensável** e **é refutada pela referência**:

1. **O mecanismo do "clear" está tecnicamente frágil.** Com `storeOp=STORE` (não há por que duvidar do contrato
   Vulkan) e uma barreira spec-válida `CA→SRO` com `srcAccess=COLOR_ATTACHMENT_WRITE`, o conteúdo de `storeOp`
   **é garantido** ao leitor — o driver não pode "adiar um resolve" através de uma barreira que o solicita. O
   preto é mais plausivelmente **cache/estado UBWC não-drenado ou layout efetivo divergente**, não "resolve
   pendente literal". (Consenso A §1.2, D objeção 3.)
2. **"Só copy-to-host comita" é refutado como afirmação geral.** O melonDS lê a saída do filtro (`topOutput`) por
   **`vkCmdBlitImage` image→image no MESMO command buffer** do filtro, contiguamente após `recordFrame`, e
   **funciona** ([V] 2353-2382, cross-review fato 1). "Imagem→imagem não comita" false: o atlas do melonDS é
   imagem→imagem e é legível.
3. **Status declarado em aberto (REVISADO):** a observação observacional permanece — leitura in-frame preta,
   readback one-shot válido, offscreen OK. Mas o **mecanismo causal exato não está estabelecido** e é
   instrumentado por E1 (timing), E2 (layout) e E4 (receituário de barreira + destino). Nada aqui é causa
   única provada; o cross-review registra o mesmo para todas as hipóteses dos 4 agentes.

### Lacuna melonDS×3.11 — **declarada explicitamente (REVISADO)**

A versão anterior não explicava por que o transfer blit do melonDS (mesma janela, mesma ausência de barreira
final do autor) funciona e o do GameNative (3.11) falha. A resposta de trabalho agora é **uma lacuna de
parâmetro não testado + destino**:

- 3.11 = `vkCmdBlitImage(processedImage → swapchain, transfer)`; o melonDS = `vkCmdBlitImage(topOutput → atlas,
  transfer)` para uma **imagem dedicada do app** ([V] 1986-2000), nunca a swapchain ([V] 1484).
- A barreira do blit que funciona usa **`srcAccess = MEMORY_WRITE|TRANSFER_WRITE|COLOR_ATTACHMENT_WRITE` e
  `srcStage = ALL_COMMANDS`, `oldLayout = resource.layout`** ([V] 2258/2270) — **parâmetro nunca testado** no
  caminho transfer do GameNative (3.11 não documenta o receituário usado; 3.8 alargou o caminho **sampler**,
  não transfer). Não é a anti-solução 6. → **E4** testa exatamente isto.

---

## 2. Solução testável (revisada)

**Ordem de implementação mantida: E1 → E2 → E3** (parar no primeiro passo que ficar visível; cada passo é
independente e reversível). **E1 continua prioridade máxima.** E4 é um experimento barato e suplementar — não
reordena o caminho primário — que responde à lacuna melonDS×3.11 e é também o receituário de blit embutido em E3.

1. **E1 — Readback in-frame + log de posição (prioridade 1, REVISADO):** `diagnoseProcessedReadback` **no mesmo
   frame**, logo após `applyFrame` e antes do present, no mesmo CB one-shot/submission do presente — *não* no
   frame seguinte. **Obrigatório logar a posição do readback relativa ao `applyFrame`** (antes/depois da
   transição `UNDEFINED→CA` do frame N+1) e o `oldLayout`/`srcImageLayout` usado — sem isso, "válido no frame
   seguinte" pode apenas refletir conteúdo **fresco** de N+1 e não diz nada sobre delay (cross-review §4.1).
   Preto no mesmo frame + válido no seguinte → visibilidade depende de ciclo/flush completo; válido no mesmo
   frame → problema restrito ao caminho blit/sampler.

2. **E2 — Probe `GENERAL` (REVISADO: sem pressupor tracking):** após `applyFrame`, transição
   `processedImage (CAO → GENERAL)` partindo do `oldLayout` **assumido pelo contrato** (`COLOR_ATTACHMENT_OPTIMAL`,
   [V] 1719 — não de um "estado rastreado" que o GameNative **não possui**) e descriptor do `processedView` com
   `imageLayout=GENERAL` (replicando melonDS [V] 2625). Apresentar pelo sampler blit usual. Visível → problema é
   layout-assumido + layout de sample; preto → seguir. (Correção acatada de A §1.2 ressalva 3 / cross-review P3.)

3. **E3 — Atlas intermediário (padrão melonDS completo; fix robusto):**
   - Criar `atlasImage/atlasView` dedicado (`TRANSFER_SRC|TRANSFER_DST|SAMPLED`, OPTIMAL, `R8G8B8A8_UNORM`,
     dimensões do alvo final), espelhando `createRetroArchImage` ([V] 1986-2000).
   - No **mesmo command buffer dedicado** do filtro, contiguamente:
     1. transição `offscreenImage → SHADER_READ_ONLY_OPTIMAL`;
     2. `libra_vk_filter_chain_frame(offscreenImage → processedImage)`;
     3. barreira transfer-larga `processedImage CA→TRANSFER_SRC_OPTIMAL` (receituário [V] 2258/2270) +
        `atlas → TRANSFER_DST_OPTIMAL`;
     4. `vkCmdBlitImage(processedImage→atlas, TRANSFER_SRC→TRANSFER_DST, VK_FILTER_NEAREST)` (igual [V] 2361-2380);
     5. barreira `atlas → GENERAL` ([V] 2383);
     6. `vkCmdEndCommandBuffer` → submit + fence + `WaitForFences` (igual `submitAndWait` [V] 2228-2243).
   - Apresentar o **atlas** (não o `processedImage`) no CB do frame, descriptor `imageLayout=GENERAL` e barreira
     `GENERAL→GENERAL` ([V] 3091-3142), cross-submission.
   - Tracking manual de layout por imagem (campo `layout` por recurso, como [V] `resource.layout`), nunca assumir
     layout sem transição explícita.
   - Manter `use_dynamic_rendering=false` e `frames_in_flight=3`, esperando a fence apropriada antes de
     reescrever `offscreenImage` (atenção ao descarte de `intermediates` em `frame()` — **[NV]** `residuals[frame%3].dispose()`).

4. **E4 — Barreira transfer-larga pós-filtro + blit para imagem dedicada (NOVO; NÃO é a anti-solução 6):**
   após `applyFrame`, no caminho **transfer**, emitir barreira de saída com
   `srcAccess = MEMORY_WRITE|TRANSFER_WRITE|COLOR_ATTACHMENT_WRITE`, `srcStage = ALL_COMMANDS`,
   `dstAccess = TRANSFER_READ`, `dstStage = TRANSFER` (receituário melonDS [V] 2258/2270) e então
   `vkCmdBlitImage(processedImage → imagem dedicada)` — **nunca a swapchain**. Esta é a variável de diferença
   melonDS×3.11: 3.8 alargou o caminho **sampler**; E4 é o caminho **transfer** com o receituário exato da
   referência que funciona, logo **não re-testar a anti-solução 6**. Barato; executável assim que o harness de
   E1 existir; se a imagem dedicada ficar legível → a causa é a barreira de transfer-read larga e/ou o destino
   (swapchain), não o estado do resolve.

---

## 3. Observável esperado (experimentos que confirmam/descartam)

| Experimento | Se confirmar | Se descartar |
|---|---|---|
| **E1** readback one-shot de `processedImage` no MESMO frame (logo após applyFrame), antes do present, com **log de posição relativa ao applyFrame** e do `oldLayout` | Visibilidade depende de ciclo completo (leitura por `vkCmdCopyImageToBuffer` é caminho que comita); o "frame seguinte" da tabela §4 é delay real, não conteúdo fresco de N+1 | Problema restrito a blit/sampler; "delay materializa o resolve" morto (cross-review §4.1) |
| **E2** `processedImage CAO → GENERAL` + sample com descriptor `GENERAL` no mesmo frame (sem tracking) | **H2** (estado de layout efetivo ≠ contrato; `GENERAL` é o layout tolerado) → fix mínimo sem atlas | H2 enfraquece; ir para E3/E4 |
| **E4** barreira transfer-larga (2258/2270) + blit `processedImage → imagem dedicada` | A lacuna melonDS×3.11 é parâmetro de barreira e/ou destino; leitura da saída do filtro é coerente quando o receituário é o da referência | Nem a barreira nem o destino explicam; foco volta para a topologia/intermediária (E3) |
| **E3** atlas intermediário + blit no mesmo CB + submit-and-wait + present do atlas em `GENERAL` | Fix robusto sob H1/H2/E4 (caminho transfer-write→sample later é o do melonDS) — **imagem visível** | Se E3 também ficar preto, a causa está **fora** do padrão de leitura do output (reabrir investigação no presente/swapchain/descriptor do atlas) |

Logs a capturar: posição do READBACK-P relativa ao `applyFrame` + `oldLayout`/`srcImageLayout` (E1); presença/
ausência de imagem em E2 e E4; log do present lendo o atlas (E3). Critério de sucesso: imagem filtrada visível
no dispositivo alvo (Mi 11 / Adreno 650).

---

## 4. Esforço / risco

- **E1 (diagnóstico, com log de posição):** ~1–1,5 h, sem risco; só muda o momento de chamada e adiciona logging.
  Não altera o caminho de present.
- **E2 (probe GENERAL):** ~1–2 h; baixo risco; toca apenas transição + descriptor de `processedView`; reversível
  em uma linha. Sem tracking novo.
- **E4 (barreira transfer-larga):** ~1–2 h; baixo risco; requer só uma imagem dedicada de destino (que E3 reusaria).
- **E3 (atlas, padrão melonDS):** ~1 dia. Risco moderado: nova imagem + fence + tracking de layout manual; mexe no
  caminho de present. Mitigações: é o padrão exato da referência que funciona; pode coexistir com o blit direto
  atual por flag. Não há mudança em render passes do compositor nem em ABI do librashader.

---

## 5. Declaração de conformidade (anti-soluções da seção 6 de `librashader-failed-attempts.md`)

- (1) Não re-diagnostica "o librashader está renderizando?" — parte de READBACK-P como fato.
- (2) Não trata "sampler vs transfer" como causa raiz — ambos falham; a solução é estrutural (intermediária/layout/receituário), não mecânica.
- (3) Não testa "split de submissions" como fix — E3 replica o melonDS (submission única do filtro + submit-and-wait, depois present separado); E1 apenas *mede*.
- (4) Não adiciona/remove `TRANSFER_DST_BIT` da swapchain — E4/E3 usam **imagem dedicada**, não a swapchain.
- (5) Não mexe em alpha/compositeAlpha.
- (6) Não alarga barreiras `ALL_COMMANDS`/`MEMORY_WRITE` **no caminho sampler** — **E4 declara explicitamente que NÃO é a anti-solução 6**: 3.8 (que falhou) alargou o caminho sampler; E4 é o caminho **transfer** com o receituário exato do melonDS (2258/2270) + `dstAccess/dstStage` pontuais + destino dedicado, replicado como receita da referência e não como "empilhar dependências".
- (7) Não reintroduz `queueMtx` como solução.
- (8) Não reintroduz prebuilts em `jniLibs`.
- (9) Não mexe no target `winlator` do CMake.
- (10) Não reativa TEST MODE como resposta — E1/E2/E3/E4 são instrumentação, não substituem o path real.
- (11) Não assume que o problema é o layout de `offscreenImage` — offscreen está correto desde 3.5; investiga `processedImage`.
- (12) Não muda `use_dynamic_rendering` — mantém `false`; issue #225 citado só como contexto já documentado.

---

## 6. Declaração de revisão da Fase 3

**O que mudou (esta revisão):**
1. **H1 rebaixada a "variação não distinguida" da família de layout** — o mecanismo "resolve pendente literal /
   leitura do clear `[0,0,0,0]`" foi **retirado** (spec-frágil: `storeOp=STORE` + barreira válida garantem conteúdo;
   preto aponta cache/UBWC ou estado efetivo divergente) e "só copy-to-host comita" foi **descartado como afirmação
   geral** (refutado pelo blit image→image do melonDS no mesmo CB). O mecanismo exato fica **declarado em aberto**,
   instrumentado por E1/E2/E4.
2. **Citações de internals do librashader marcadas [NV]** (não verificáveis no repo; dir `/librashader/` não
   existe); só `librashader.h:1705-1720` [V] e o blob melonDS [V] são fontes verificadas. A observação do
   hello_triangle (padrão oficial nunca re-lê a saída) foi mantida como **inferência coerente com o header**, não fato.
3. **E1 reforçada com log da posição do readback** relativa ao `applyFrame` e do `oldLayout` (estabelece ou mata o
   "frame seguinte materializa o resolve").
4. **E2 corrigida para não pressupor tracking** de layout (probe `CAO→GENERAL` + descriptor `GENERAL`, partindo do
   contrato [V] 1719).
5. **E4 adicionado** (barreira transfer-larga pós-filtro + blit para imagem dedicada, receituário melonDS 2258/2270),
   declarado **NÃO anti-solução 6**, respondendo à **lacuna melonDS×3.11** que agora é explícita.

**O que manteve:**
1. **Ordem E1→E2→E3 e prioridade de E1** (readback in-frame) — confirmada como a melhor das três propostas (D) e o
   experimento mais informativo faltante (cross-review §4.2).
2. **H2 (`GENERAL`)** como hipótese principal — a observação mais acertada (C) e mais alinhada à evidência (A).
3. **E3 (atlas intermediário + submit-and-wait + present em `GENERAL` + tracking manual)** como fix robusto sob
   múltiplos mecanismos — consenso do cross-review (§6).
4. Conformidade às 12 anti-soluções, agora com a nota explícita sobre E4/anti-6.

**Posicionamento final:** alinhado ao cross-review. Nenhum mecanismo de causa foi provado como único; o atlas (E3)
é o remédio robusto, e E1/E2/E4 são a instrumentação que identifica a causa — o sucesso do atlas **não deve ser
lido como confirmação de mecanismo específico**. A lacuna melonDS×3.11, apontada por todos, é endereçada por E4/E3
(parâmetro de barreira transfer-read larga + destino imagem dedicada) e é a direção mais barata a validar a seguir.

---

## Referências citadas

- **[V] `app/build/generated/librashader/include/librashader.h:1705-1720`** — contrato de `libra_vk_filter_chain_frame`
  ("no barrier for the final pass; output remains `VK_COLOR_ATTACHMENT_OPTIMAL`; caller transitions"; entrada `SRO`,
  saída `CAO`). Única fonte do librashader verificada no repo.
- **[NV] `librashader-capi/src/runtime/vk/filter_chain.rs`** — C API mapeia direto para `FilterChainVulkan::frame`.
  Não verificável no repo.
- **[NV] `librashader-runtime-vk/src/filter_chain.rs`** — `frame()`: pass final sem `out.output.end_pass`;
  `push_history(input)` como último comando; `residuals[frame%N].dispose()`. Não verificável no repo.
- **[NV] `librashader-runtime-vk/src/framebuffer.rs`** — `begin_pass` (`UNDEFINED→CA`, `srcAccess=0`); `end_pass`
  (`CA→SRO`, só passes intermediários). Não verificável no repo.
- **[NV] `librashader-runtime-vk/src/render_pass.rs`** — `initial=final=COLOR_ATTACHMENT_OPTIMAL`, `loadOp=CLEAR`,
  `storeOp=STORE`. Não verificável no repo.
- **[NV] `librashader-runtime-vk/src/graphics_pipeline.rs`** — clear `[0,0,0,0]`. Não verificável no repo.
- **[NV] `librashader-runtime-vk/tests/hello_triangle/mod.rs`** (tag `librashader-v0.12.0`) — filtro renderiza direto
  na swapchain; transição `CA→PRESENT_SRC_KHR` no mesmo CB; nunca re-lê a saída como textura. **Inferência coerente
  com o contrato [V], não verificada no repo.**
- **[V] melonDS `VulkanSurfacePresenter.cpp`** (blob `tool_fbaacb4a9001U7qREoNHruNWS9`) — `runRetroArchFilter`
  (blit `topOutput→atlasOutput` no mesmo CB 2353-2382; atlas→`GENERAL` 2383; `submitAndWait` 2228-2243);
  `createRetroArchImage` 1986-2000; `imageBarrier` `srcAccess=MEMORY_WRITE|TRANSFER_WRITE|COLOR_ATTACHMENT_WRITE`,
  `srcStage=ALL_COMMANDS`, `oldLayout=resource.layout` 2255-2280; `updateDescriptorSets` `imageLayout=GENERAL` 2625;
  `recordSurfaceCommands` (`GENERAL→GENERAL`) 3091-3142. Tracking manual `resource.layout`.
