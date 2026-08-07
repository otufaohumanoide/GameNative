# Cross-review — AGENTE B (internals librashader v0.12.0 / filter_chain_vk / layout de saída)

**Autor:** AGENTE B. Revisa as hipóteses de A, C e D (a própria proposta — `agent_b_librashader.md` —
fica fora desta revisão). PESQUISA apenas.
**Bases de verificação usadas nesta revisão (re-lidas e conferidas, não apenas citadas):**
- `librashader.h:1705-1720` (contrato do pass final) — conferido no header gerado.
- `librashader-runtime-vk/src/filter_chain.rs` (tag `librashader-v0.12.0`) — pass final via
  `pass.draw(…, QuadType::Final, false)` **sem** `end_pass`; `push_history(input)` é o último comando.
- `librashader-runtime-vk/src/framebuffer.rs` — `begin_pass` = `UNDEFINED→CA`, srcAccess=0,
  `ALL_GRAPHICS→COLOR_ATTACHMENT_OUTPUT`; `end_pass` = `CA→SHADER_READ_ONLY_OPTIMAL`.
- `librashader-runtime-vk/src/filter_pass.rs` — `draw()` = `begin_pass → begin_rendering → draw → end_rendering`.
- melonDS `VulkanSurfacePresenter.cpp` (referência) — blit `topOutput/bottomOutput → atlasOutput` por
  `vkCmdBlitImage` no **mesmo** CB do `recordFrame` (2353→2357-2382), atlas → `GENERAL` (2383), presente do
  atlas em `GENERAL` (2625, 3091-3142), submit-and-wait dedicado (2228-2243), swapchain só
  `COLOR_ATTACHMENT_BIT` (1484), atlas `TRANSFER_SRC|TRANSFER_DST|COLOR_ATTACHMENT|SAMPLED` (1995-1998).
- Blob WIP `6a648093` — transição do GameNative pós-`applyFrame` em `VulkanRendererContext.cpp:1051-1054`.

**Três fatos de verificação que pesam sobre TODAS as hipóteses:**

1. **A transição CA→SRO do GameNative é spec-correta.** WIP:1051-1054:
   `transition(processedImage, CA→SRO, COLOR_ATTACHMENT_WRITE→SHADER_READ, COLOR_ATTACHMENT_OUTPUT→FRAGMENT_SHADER)`.
   Como o render pass final do librashader tem `finalLayout=COLOR_ATTACHMENT_OPTIMAL`, o layout pós-pass
   **é** CA por construção (é exatamente para isso que o driver rastreia `finalLayout`), e a barreira
   emitida satisfaz o modelo de memória Vulkan (torna os writes do pass visíveis a um sample de fragment
   shader, no mesmo CB ou após fence). "A barreira é uma no-op porque o driver não reconhece o estado" é
   uma alegação de comportamento de driver **não estabelecida**, e contraria a semântica mais básica do
   Vulkan (layout pós-render-pass = `finalLayout`).

2. **melonDS lê a saída do render pass do librashader por `vkCmdBlitImage` no mesmo CB, no mesmo frame,
   e funciona no Adreno.** `topOutput` é escrito pelo `recordFrame` sob o MESMO contrato "sem barreira
   final" (o mesmo runtime librashader), e é lido por blit de transferência imediatamente depois (2357-2382).
   Isto **refuta diretamente** qualquer mecanismo do tipo "a leitura da saída do render pass do librashader
   é incoerente no Adreno quando feita por caminho de textura/blit no mesmo frame". Se fosse, o melonDS
   estaria preto. A diferença operacional entre melonDS (funciona) e GameNative (falha) é o **destino** da
   leitura (imagem dedicada/atlas vs swapchain), não o caminho de leitura.

3. **A leitura que falha sempre tem destino = swapchain; a que funciona (melonDS, readback) nunca lê para
   o swapchain no mesmo ciclo.** Isto torna "destino/presentação da swapchain" a variável residual não
   testada — nenhuma das três hipóteses explica de forma conclusiva *por que* o destino importa, e os
   experimentos que isolariam isso (readback in-frame, CB one-shot dedicado, atlas) são exatamente os que
   A/C/D propõem.

---

## Revisão — AGENTE A (`agent_a_vulkan.md`)

### Consistência com a evidência
- **H1 (divergência de layout pós-frame):** explica o quadro (offscreen OK, processed preto em todo método,
  readback frame-seguinte OK) se concedermos que "o readback funciona por tolerância do copy engine /
  forçar resolve" — mas essa concessão é exatamente o mecanismo não demonstrado. O padrão completo é
  *narrativamente* coberto, porém com duas alavancas ad-hoc (estado divergente + copy engine tolerante).
- **H2 (atlas intermediário / submit-and-wait):** consistente com o quadro e, sobretudo, **com a referência
  que funciona** (melonDS nunca apresenta a saída do filtro direto). É a hipótese que melhor sobrevive ao
  fato 2/3 acima.

### Falha técnica
- **H1 contradiz o básico do Vulkan (fato 1):** o estado pós-render-pass é o `finalLayout` do render pass
  (`COLOR_ATTACHMENT_OPTIMAL`, `render_pass.rs`); o driver **rastreia** isso deterministicamente. Chamar a
  transição CA→SRO de "incompatibilidade de oldLayout no Adreno" é especular sobre um driver que, por
  construção, não tem divergência a reconhecer aqui. Adicionalmente, o readback que funciona transiciona a
  partir do **mesmo** estado assumido (CA, por contrato) — se CA fosse "errado", o readback sofreria o mesmo
  no-op; A explica isso por "tolerância do copy engine", o que **colapsa H1 em H2** (resolve/commit, não
  divergência de layout).
- **H1 não usa o controle melonDS (fato 2):** o melonDS faz a leitura in-frame por blit da saída do
  librashader e funciona; H1, se verdadeira, prediz o contrário.
- A generalização "o fix 3.5 provou que esta família é sempre layout" é uma heurística; o caso offscreen era
  um bug de transição **ausente em código do próprio app**; o caso `processedImage` é de saída de código de
  terceiros (library). Conflar os dois superestima a analogia.

### Testabilidade
- O passo 1 (logar `oldLayout`/`srcImageLayout` do readback que funciona) é um diagnóstico **barato e
  genuinamente discriminante** — o melhor investimento da proposta. Recomendado.
- O probe "defensivo" `UNDEFINED→SRO` é um discriminador **sujo**: `oldLayout=UNDEFINED` significa que o
  conteúdo anterior é indefinido e pode ser descartado; se o driver implementar como "não preserva", o probe
  lê preto por motivo alheio à hipótese (não se sabe o que está no GMEM até o resolve). Vale como sonda
  rápida, mas não é prova de layout.
- H2 (atlas) é plenamente testável e tem observável claro (apareceu com atlas + preto direto ⇒ caminho de
  leitura direta é o problema).

### Conformidade anti-solução
- ✅ Completa e disciplinada (tabela das 12): não mexe em alpha, `TRANSFER_DST` da swapchain, barreiras
  alargadas, TEST MODE, `use_dynamic_rendering`. H1/H2 não reintroduzem nenhuma anti-solução. O probe H1 é
  diagnóstico, não fix.

### Objeções fortes
1. H1 é especulação de driver que contraria o `finalLayout` (fato 1) e o controle melonDS (fato 2).
2. O probe `UNDEFINED` pode dar falso-negativo por descartar conteúdo (fato de espec).
3. H1 e H2 são apresentados como alternativas, mas o mecanismo de H1 (se verdadeiro) também explicaria o
   atlas; a distinção causal H1×H2 fica turva.

### Veredito
**Apoiar com ressalvas.** H2 (atlas) é forte, alinhada à referência que funciona e testável. H1 é o
elo fraco: mecanismo tecnicamente incorreto segundo a spec, mas o **diagnóstico de log** que A propõe (passo 1)
é valioso e deve ser feito primeiro, independentemente da causa.

---

## Revisão — AGENTE C (`agent_c_melonds.md`)

### Consistência com a evidência
- **H1 (leitura por caminho de textura incoerente; copy engine coerente):** explica o quadro apenas na
  superfície (readback copy-engine ✅, sampler/blit ❌). Mas entra em choque com a própria referência:
  melonDS lê a saída do filtro por `vkCmdBlitImage` (o "caminho de textura" que C diz que falha) no mesmo
  CB do filtro e funciona (fato 2). A tabela de evidências mostra *transfer blit → swapchain* falhando —
  isso não demonstra "caminho de textura incoerente"; demonstra "leitura da saída do filtro com destino
  swapchain falha". O mecanismo de C (cache L1 de textura) não diferencia o caso que funciona do que falha.
- **H2 (GENERAL):** plausível como contribuinte e honestamente autolimitado por C (não explica o transfer
  blit sozinho). Consistente com a evidência sem pretender ser completo.

### Falha técnica
- **H1 é refutada pelo controle melonDS (fato 2), que é a própria base de C.** O mesmo `vkCmdBlitImage`
  sobre a saída do librashader, mesmo frame, mesmo CB, funciona no Adreno (melonDS 2357-2382). A dicotomia
  "textura vs copy engine" como causa é insustentável: o caminho que C marca como frágil é o que a
  referência usa com sucesso.
- **Tensão interna na própria solução:** C anota no §5 que o blit `filterOutput→atlas` no mesmo CB "já é a
  leitura via textura do output — exatamente o ponto que a evidência marca como frágil". Se H1 fosse
  verdadeira, a solução proposta (atlas) falharia no primeiro passo. C mitiga com o fallback copy-engine
  (`vkCmdCopyImage`), o que é honesto, mas revela que H1 não é o mecanismo certo.
- A afirmação de que `vkCmdBlitImage` no Adreno passa pela cache de textura é especulativa (pode ser blitter
  dedicado ou textura dependendo do filtro); C a trata como estabelecida.

### Testabilidade
- A topologia atlas (mesmo CB, submit-and-wait, presente do atlas em `GENERAL`) é **a** solução testável
  mais fiel à referência e com observável claro (READBACK-atlas + tela visível). Alto valor.
- Os três diagnósticos do §7 são bons: H2 minimalista (GENERAL sem atlas), readback in-frame (fato a ser
  estabelecido), e copy-engine no lugar do blit (discriminante do mecanismo H1).

### Conformidade anti-solução
- ✅ Tabela completa, correta: swapchain volta a ser só `COLOR_ATTACHMENT` (remove `TRANSFER_DST`, anti-4),
  barreiras no mesmo receituário de 3.8 (não "alarga"), TEST MODE só como diagnóstico. Nenhuma violação.

### Objeções fortes
1. O mecanismo de H1 é refutado pela própria referência de C (fato 2) — a solução pode estar certa, mas o
   mecanismo invocado não.
2. A topologia atlas **não discrimina** a causa (funcionará sob múltiplos mecanismos: destino, submit-and-wait,
   GENERAL, escrita por TRANSFER). Isso a torna um ótimo *fix* robusto, mas um pobre *experimento de causa* —
   C não deve concluir H1/H2 a partir do sucesso do atlas.
3. Swapchain: C acerta o diagnóstico estrutural (o melonDS nunca blita para a swapchain; a swapchain é só
   render target), mas não oferece mecanismo para *por que* o destino swapchain falha — a mesma lacuna de
   todos.

### Veredito
**Apoiar com ressalvas.** A solução (topologia de imagens do melonDS) é a recomendação prática mais forte
entre as três e deve ser executada. Mas a **H1 é rejeitada como mecanismo** (refutada pelo controle
melonDS), e o sucesso do atlas não deve ser lido como confirmação de H1. H2 fica como contribuinte
plausível, não suficiente.

---

## Revisão — AGENTE D (`agent_d_present.md`)

### Consistência com a evidência
- **Seção 2 (ordenação/execução):** excelente e correta. Enterra o "race de execução Vulkan-legal" com o
  argumento do fence: fence esperado (3.10) garante execução completa e visibilidade de memória; se mesmo
  assim o blit lê preto, não é ordenação. Este é o raciocínio mais rigoroso das três propostas e está certo.
- **H1 (no-op por oldLayout divergente):** mesma fraqueza de A (fato 1) — `finalLayout` do render pass
  define o estado; oldLayout=CA é canônico.
- **H2 (visibilidade postergada; só copy engine→host materializa):** é o mecanismo mais sofisticado, mas
  **parcialmente contradito pelo melonDS (fato 2):** o melonDS força materialização por um blit imagem→imagem
  (`topOutput→atlas`) no mesmo CB. "Leitura imagem→imagem não comita; só copy para host comita" é amplo
  demais — o caso que funciona no melonDS é imagem→imagem. A variável real observada é destino/posse
  (imagem dedicada do app, apresentada depois) + submit-and-wait, não "imagem→imagem".

### Falha técnica
- H1 carrega o problema de spec de A (fato 1), sem o controle melonDS para temperá-lo.
- H2 não reconcilia o caso melonDS (fato 2): segundo H2, o blit do melonDS para o atlas **deveria** ler o
  estado não-resolvido (é imagem→imagem) — mas funciona. A qualificação de D de que o fence não força o
  commit é consistente, mas a previsão "imagem→imagem não comita" é falsa no caso que D cita como referência.
- D trata o atlas como **fallback** sob H2, não como candidato primário — posição mais fraca que a de A/C
  (que o colocam como fix principal), dado que o atlas é o único padrão não tentado com evidência de sucesso.

### Testabilidade
- Melhor conjunto de experimentos baratos das três:
  - **E0** (`vkQueueWaitIdle` antes do blit): ~30 min; discrimina formalmente race de execução. Resultado
    esperado (preto) já antecipado por 3.10, mas barato e fecha a questão.
  - **E1** (readback in-frame): discrimina H1×H2 e é o fato mais faltante na tabela de evidências.
  - **E2** (CB one-time dedicado blit→swapchain + wait): o experimento mais próximo de isolar "destino
    swapchain / contexto de presente". Se falhar mesmo dedicado, combinado com o controle melonDS (atlas
    funciona) aponta diretamente para o destino/presente da swapchain.
- ⚠️ E2 não testa o atlas (o provável fix); para isso A/C cobrem a lacuna. E2 + atlas executados em sequência
  fechariam o problema de causa e de solução.

### Conformidade anti-solução
- ✅ Tabela correta e rigorosa: E0 usa sincronização host (não barreira alargada, anti-6 ok); E2 não muda
  `imageUsage` da swapchain (usa layout `TRANSFER_DST_OPTIMAL` no CB, não `TRANSFER_DST_BIT` — ok vs anti-4);
  nada de alpha, TEST MODE só como diagnóstico.

### Objeções fortes
1. H2 prevê que o blit imagem→imagem do melonDS falharia — o controle contradiz (fato 2).
2. D não dá um mecanismo para o destino swapchain (a mesma lacuna de todos), mas é quem chega mais perto de
   um experimento que a isola (E2).
3. Classificar o atlas como fallback, e não como fix primário, subestima a única evidência positiva
   disponível (melonDS).

### Veredito
**Apoiar com ressalvas.** A análise de ordenação (seção 2) é a melhor das três e deve ser mantida. H1 é fraca
(pela spec); H2 é o mecanismo mais plausível da família, porém contradito no caso melonDS e não reconciliado.
Os experimentos E0/E1/E2 são os diagnósticos mais baratos e discriminantes — executar em sequência, e
**elevar o atlas (A-H2/C) a experimento de fix imediato** após E1/E2.

---

## Síntese da revisão (o que é sólido, o que não é)

**Sólido e convergente nas três:**
- A análise de ordenação de D (fence refuta race de execução) está correta e é base comum.
- O padrão atlas/topologia do melonDS (A-H2, C, D-fallback) é o único caminho não tentado com evidência
  positiva (a referência funciona). É o fix de maior probabilidade e deve ser priorizado.
- O **readback in-frame** (E1 de A/C/D) é o experimento de maior informação faltante na tabela de evidências
  (isola "visibilidade in-frame" de "caminho de leitura") — executar primeiro.
- O **log do `oldLayout`/`srcImageLayout` do readback que funciona** (passo 1 de A) é barato e revelador.

**Frágil nas três:**
- Todo mecanismo do tipo "a transição CA→SRO é no-op porque o driver não rastreia CA" (A-H1, D-H1) contraria
  a semântica de `finalLayout` (fato 1) e a própria transição do readback que funciona.
- Todo mecanismo do tipo "leitura da saída do librashader por textura/blit é incoerente no Adreno"
  (C-H1) é refutado pelo melonDS, que faz exatamente isso com sucesso (fato 2).
- A variável "destino swapchain vs imagem dedicada" permanece sem mecanismo em nenhuma das três; E2 (D) e o
  atlas (A/C) são os instrumentos que a fecham.

**Não escrito pelos colegas e que esta revisão registra:** a barreira do GameNative (WIP:1051-1054) é
spec-correta; portanto o diagnóstico "barreira insuficiente" não sobrevive, e a pista mais produtiva é a
**combinação fonte=saída do filtro + destino=swapchain** — que o padrão atlas contorna ao trocar o destino,
e que o E2 de D isola. Qualquer solução deve manter o tracking de layout por imagem (melonDS
`resource.layout`) e o submit-and-wait do CB do filtro.
