# CROSS-REVIEW — AGENTE D sobre as hipóteses A, B e C

**Revisor:** AGENTE D — apresentação/swapchain/Android, caminhos de leitura de imagem no Vulkan
(sampler vs transfer vs readback), ordenação de execução e visibilidade de memória.
**Modo:** PESQUISA apenas. Reviso as propostas dos colegas A, B e C; não reviso a minha (agent_d_present.md).

**Âncora comum de revisão (usada em todas as seções):**
A pista mais afiada dos fatos não é "sampler falha" nem "transfer falha" nem "split falha" — é a
**discrepância melonDS**: o melonDS (mesma GPU/stack, `VulkanSurfacePresenter.cpp:2357-2382`) faz um
**transfer blit da saída do librashader (`topOutput` → `atlasOutput`) no MESMO command buffer do filtro**
e **funciona**; o GameNative faz o análogo (`processedImage` → swapchain via transfer, 3.11, mesmo
submission) e fica **preto**. Se o mecanismo for "leitura in-frame da saída de render pass do librashader
é incoerente" ou "a transição do caller é no-op por divergência de layout", então o melonDS deveria falhar
igual. Qualquer hipótese que não explique por que o **mesmo tipo de leitura funciona no melonDS e falha
no GameNative** deixa um buraco no meio. O fence já foi testado (3.10) e não mudou nada — logo, nenhuma
hipótese pode apoiar-se em "ordenar/completar a execução" como mecanismo causal do fix.

---

## Revisão do AGENTE A — `agent_a_vulkan.md` (layout & GMEM, Adreno 650)

### Consistência com a evidência
- **H1 (divergência de layout pós-frame):** consistente com todo o quadro observacional — explica por que
  todas as leituras in-frame falham **independentemente do método** (a divergência está no estado da fonte,
  não no caminho de leitura) e por que o fence (3.10) não ajuda (o `oldLayout` errado continua errado após
  o fence). Consistente com `offscreenImage` OK (o app é dono do render pass e o estado é conhecido).
- **H2 (resolve GMEM não disparado / atlas intermediário):** consistente com o padrão melonDS e com o fato
  de o readback (copy engine → host) ser o único caminho que materializa.
- **Ponto forte:** A é o único que propõe como **primeiro** experimento logar o `oldLayout`/`srcImageLayout`
  do readback que FUNCIONA (H1.1). Isso é a ancoragem mais barata possível: se o readback que funciona parte
  de `srcImageLayout=COLOR_ATTACHMENT_OPTIMAL` sem transição própria, **H1 na forma forte cai** — e A mesmo
  reconhece isso (discriminação honesta, seção "Observável esperado").

### Falha técnica
- **Contradição melonDS contra a forma forte de H1:** H1 afirma que a transição CA→SRO do caller é no-op
  porque o estado real pós-`frame()` diverge do CA documentado. Mas o melonDS transiciona a saída do
  librashader `CA→TRANSFER_SRC` partindo do **mesmo** contrato (`librashader.h:1705-1720`, output permanece
  em CA) e **funciona**. Se a divergência de layout fosse estrutural (driver registra estado ≠ CA), a
  transição do melonDS também seria no-op e o blit dele leria preto. Portanto a forma forte de H1 só se
  sustenta se a divergência for **específica da sequência do GameNative** (ex.: `transition()` gravada em
  posição/scope errado, ou um `oldLayout` que o driver reconhece mas com barreira mal parametrizada) — o que
  não está articulado em A. O fix "defensivo" proposto (transição `UNDEFINED → SRO`) é um probe aceitável,
  mas **não discrimina H1 de H2**: `UNDEFINED` força o driver a "fazer tudo" (flush + resolve), e um
  resultado positivo só prova "a família transição/estado importa", não qual hipótese.
- **Explicação do readback (H1, item b) "cópia host-visível obriga o resolve":** é uma inferência, não
  evidência. O teste que realmente separa é o **readback no MESMO frame** (proposto por B e D, mas não
  priorizado por A). Sem ele, A não pode atribuir o sucesso do readback à "própria transição" vs "ciclo de
  frame vs copy engine".

### Testabilidade
- Boa. H1.1 (log `oldLayout`) é barato e discriminante; H1.2 (probe `UNDEFINED`) é barato. H2 é a reimplantação
  do padrão melonDS, testável e com resultado binário claro. Prioridade sugerida por A (diagnóstico → probe →
  H2) é correta, **exceto** que o experimento que A coloca como diagnóstico não é o mais discriminante:
  o readback in-frame (E1 de B/D) deveria vir antes ou junto.

### Conformidade anti-solução
- ✅ Conformidade limpa: não mexe em alpha, `TRANSFER_DST` da swapchain, split isolado, barreiras alargadas,
  `use_dynamic_rendering`, TEST MODE. A tabela (linhas 187-200) está correta.

### Objeções fortes
1. Forma forte de H1 contradita pela referência melonDS (mesmo `oldLayout` assumido, mesmo contrato, leitura
   funciona) — falta explicar o porquê do melonDS escapar do mecanismo.
2. O probe `UNDEFINED` promete discriminar H1×H2 mas só discrimina "transição/estado vs não" — superestimado.
3. Não prioriza o experimento decisivo (readback in-frame) que separaria "estado da fonte no momento da
   leitura" de "visibilidade postergada por ciclo".

### Veredito
**Apoiar com ressalvas.** Boa consistência e experimentos baratos e honestos; mas a forma forte de H1 é
vulnerável à contradição melonDS, e o discriminador real (readback in-frame) precisa subir na prioridade.

---

## Revisão do AGENTE B — `agent_b_librashader.md` (estado pós-`frame()`, resolve no Adreno)

### Consistência com a evidência
- **H1 (visibilidade do resolve do attachment final postergada; só a cópia device→host num CB one-shot
  comita):** consistente com o quadro inteiro e — o mérito principal de B — **fundamentada no código real
  do librashader v0.12.0**, não em invenção: `begin_pass` (`UNDEFINED→CA`, `srcAccess=0`), render pass
  `initial=final=CA`, `loadOp=CLEAR`, `storeOp=STORE`, pass final **sem** `end_pass` (o `push_history` sobre
  a entrada é o último comando de `frame()`). A previsão "o preto in-frame é o clear [0,0,0,0]" é falsificável
  e alinhada com o quadro (leitura de estado não-resolvido de tiles recém-clearados).
- **H2 (divergência de layout efetivo; `GENERAL` é o layout tolerado):** consistente; é a versão de A-H1 com
  a observação adicional (correta) de que o melonDS amostra **100% das imagens em `GENERAL`**.
- **B é o único que nota o detalhe importante do hello_triangle oficial:** o padrão oficial **nunca re-lê a
  saída do filtro como textura** — renderiza direto na swapchain e transiciona CA→PRESENT. Ou seja, o caminho
  que o GameNative usa (amostrar a saída do filtro in-frame) **não é exercitado pelo padrão oficial**. Isso
  é a justificativa mais forte para "não é óbvio que isso deva funcionar".

### Falha técnica
- **Contradição melonDS contra H1 e H2:** melonDS lê a saída do librashader **por transfer blit no mesmo CB
  do filtro, logo após `frame()`**, sem barreira final emitida pelo librashader — exatamente a janela que H1
  diz ser "resolve pendente". Se "in-frame + sem barreira final ⇒ leitura preta" fosse o mecanismo, o blit do
  melonDS falharia. O que salva B é que ele NÃO propõe H1/H2 como mecanismos exclusivos: a solução E3 é o
  padrão melonDS (atlas + submit-and-wait + `GENERAL`), robusto a ambas. Ainda assim, a explicação de por que
  o melonDS escapa do mecanismo (layout tracking manual + leitura só por transfer, nunca sampler, no mesmo CB)
  é afirmada mas não verificada contra o 3.11: o 3.11 **foi** transfer blit do output no mesmo submission e
  falhou — a tese de B/H1 ("transfer blit é caminho coerente porque... não, espera") fica ambígua: B diz que
  só a cópia para buffer host comita, mas o melonDS comita com blit para imagem. **O que difere o blit-do-
  melonDS do blit-do-3.11 não é respondido por B.**
- Nitpick técnico: a leitura de tiles "não-resolvidos com `loadOp=CLEAR`" deveria devolver lixo de memória,
  não o valor de clear — o preto mais provavelmente é leitura de cache/estado UBWC não-drenado. Não muda a
  hipótese, mas o detalhe do mecanismo ("lê o clear") é frágil.

### Testabilidade
- **Excelente.** E1 (readback in-frame) é **o** experimento discriminante H1×H2 e está no topo da prioridade
  de B. E2 (`GENERAL` + descriptor) é barato e desambiguaria H2 isolado. E3 (atlas) é a solução robusta.
  A ordem (E1 → E2 → E3) é a melhor das três propostas.

### Conformidade anti-solução
- ✅ Limpa; B cita explicitamente o fato 3.8 (barreiras alargadas já falharam) como premissa e não as propõe.
  Mantém `use_dynamic_rendering=false` e cita issue #225 só como contexto. Nada de `TRANSFER_DST` na swapchain.

### Objeções fortes
1. Mesma lacuna melonDS×3.11: B não explica por que o transfer blit do melonDS (mesma janela, mesma ausência
   de barreira final do autor) funciona e o do GameNative não. Sem isso, H1/H2 descrevem o sintoma, não a
   causa suficiente.
2. H1/H2 são apresentadas quase como causas exclusivas, mas o próprio B converge para E3 (atlas), que é o que
   a evidência já indica — o mecanismo exato fica em aberto. Isso não é defeito metodológico (E1/E2 testam
   exatamente isso), mas deve ser declarado como "em aberto" com mais ênfase.
3. O detalhe do "lê o clear [0,0,0,0]" é uma previsão falsificável boa, mas o mecanismo físico está mal
   especificado (resolve com `storeOp=STORE` deveria garantir o conteúdo em memória; o preto aponta cache/UBWC,
   não "resolve pendente" literal).

### Veredito
**Apoiar com ressalvas.** Melhor fundamentação de código e melhor ordem de experimentos das três; mas H1/H2
sofrem da mesma contradição melonDS×3.11 que A, e a explicação causal exata permanece em aberto (o que B
mesmo instrumenta com E1/E2).

---

## Revisão do AGENTE C — `agent_c_melonds.md` (fluxo de apresentação, topologia de imagens)

### Consistência com a evidência
- **H1 (leitura por textura/cache vs copy engine; atlas re-materializa por TRANSFER com layout 100%
  app-controlado):** é a hipótese mais fiel à referência que funciona e a mais consistente com a tabela de
  evidências: copy engine ✅ (readback), sampler blit ❌, transfer blit ❌ — C unifica "sampler e `vkCmdBlitImage`
  passam pela cache/textura; `vkCmdCopyImageToBuffer` não". O mapeamento linha-a-linha do melonDS (1.1–1.4) é
  o trabalho de referência mais completo dos quatro.
- **H2 (`GENERAL` como contribuinte):** C é honesto ao marcar a limitação (sozinho não explica o fracasso do
  transfer blit 3.11) e ao propor o teste minimalista de desambiguação (§7.1).
- **Ponto forte:** C estrutura a solução como **mudança de topologia** ("apresentar outra imagem"), não como
  "mais barreiras" — coerente com o fato de que barreiras (3.8), fence (3.10) e transfer (3.11) já falharam.

### Falha técnica
- **Auto-contradição na solução primária:** C mesmo reconhece (§5, risco H1) que o blit `filterOutput→atlas`
  **no mesmo CB do filtro já é a leitura via textura da saída do librashader — exatamente o ponto que a
  evidência marca como frágil**. Ou seja: a solução primária de C **reproduz a leitura que falhou no 3.11**
  (transfer blit do output), só que com destino = imagem dedicada em vez de swapchain. Se H1 de C estiver
  certa (leitura via textura da saída do librashader é incoerente), o atlas ficará **preto** e só o fallback
  (copy engine, §7.3) resolve. O fallback é na verdade a solução **consistente** com a própria análise de C;
  a primária é a inconsistente. Isso precisa ser invertido no documento (fallback = caminho principal).
- **Discrepância melonDS×3.11 não explicada:** o melonDS faz exatamente "transfer blit da saída do filtro no
  mesmo CB" e funciona. C atribui isso a "layout tracking manual + barreiras próprias (2258/2270)". Mas essas
  barreiras usam o **mesmo receituário do 3.8** (`MEMORY_WRITE|COLOR_ATTACHMENT_WRITE` → `TRANSFER_READ`,
  `ALL_COMMANDS` → `TRANSFER`), que o GameNative já tentou e falhou. A diferença real (topologia/layout
  tracking/`GENERAL`) é apontada, mas não há um experimento mínimo que **isole** qual dessas variáveis é a
  causal — o §7 mistura três testes, nenhum deles o "minimal diff vs 3.11" (mesmo caminho de leitura, mudando
  só o destino para imagem dedicada, ou só o layout para `GENERAL`).
- **Borda anti-solução 4:** C propõe **remover** `TRANSFER_DST_BIT` da swapchain (voltar a só
  `COLOR_ATTACHMENT`, como melonDS:1484). A anti-solução 4 diz "não adicionar/remover `TRANSFER_DST` da
  swapchain — não resolve (3.11)". Tecnicamente C toca na variável proibida; a justificativa ("remover junto
  com a mudança de topologia") é razoável e vai na direção de reverter, não de re-testar — mas deve ser
  declarada como desvio consciente da letra da anti-solução, com justificativa explícita.

### Testabilidade
- Boa, com três desambiguações (§7) de custo baixo a médio. A mais informativa (readback in-frame, §7.2) está
  presente, mas não priorizada no fluxo principal. Falta um experimento "minimal diff vs 3.11" para isolar a
  variável causal dentro do padrão melonDS.

### Conformidade anti-solução
- ✅ na substância, com a **exceção da borda anti-solução 4** (remoção de `TRANSFER_DST` da swapchain) e o
  risco de soar como "re-teste de sampler vs transfer" (que C nega corretamente ao explicar ambos pelo mesmo
  mecanismo). `use_dynamic_rendering`, alpha, TEST MODE, `queueMtx` intocados.

### Objeções fortes
1. **Auto-contradição:** a solução primária usa a leitura (blit via textura do output do filtro) que a própria
   análise de C marca como frágil; o fallback (copy engine) é o caminho consistente e deveria ser o principal.
2. **Lacuna melonDS×3.11:** mesma barreira (`MEMORY_WRITE|CA_WRITE` → `TRANSFER_READ`, `ALL_COMMANDS`) que o
   3.8/3.11 do GameNative já usou — C não isola qual variável do padrão melonDS (topologia, `GENERAL`, layout
   tracking, submit-and-wait) é a causal.
3. **Borda anti-solução 4** sem declaração explícita de desvio.

### Veredito
**Apoiar com ressalvas.** É a proposta mais fiel à referência que funciona e a mais consistente com a tabela
de evidências, mas tem uma auto-contradição interna (primário vs fallback) e não isola a variável causal do
padrão melonDS; a borda anti-solução 4 precisa ser declarada.

---

## Síntese transversal (o que a revisão das três revela em comum)

1. **Consenso forte e correto:** todas convergem para o padrão melonDS (imagem intermediária escrita por
   TRANSFER + apresentação da intermediária, layout rastreado/`GENERAL`, submit-and-wait) — e para a rejeição
   de fence/ordering como causa (3.10 já testado). A direção da solução é a mesma; a disputa é só o mecanismo.
2. **O experimento decisivo que nenhuma das três prioriza de verdade:** readback do `processedImage` **no
   MESMO frame** logo após `applyFrame` (E1 de B/D; §7.2 de C). Ele separa "estado da fonte no momento da
   leitura" (H1/layout) de "visibilidade postergada por ciclo/copy-engine" (H2) — e é barato. **Recomendo
   que A, B e C subam esse teste para primeiro lugar.**
3. **A anomalia central não resolvida pelas três:** melonDS transfere-blita a saída do librashader no mesmo CB
   e funciona; GameNative (3.11) não. A, B e C descrevem o sintoma com mecanismos plausíveis, mas nenhuma
   identifica por que o mesmo tipo de leitura escapa no melonDS. O teste que isola isso é o "minimal diff vs
   3.11": mesmíssimo caminho de leitura, variando **uma** variável do padrão melonDS por vez (destino dedicado;
   `GENERAL`; transição pré-filtro do estado de entrada). Isso não é anti-solução (não é "trocar sampler por
   transfer"); é instrumentação.
4. **Fence como mecanismo causal está morto** nas três (corretamente). Nenhuma usa fence/submit-and-wait como
   *mecanismo* de visibilidade; usam como *serialização* do padrão melonDS. Ok.

**Sugestão de caminho comum (não implementação, só prioridade de experimentos):**
**P1** readback in-frame (discrimina H1×H2) → **P2** minimal-diff vs 3.11 (destino dedicado / `GENERAL`) →
**P3** atlas melonDS com copy engine como primário (e blit como variante de diagnóstico).
