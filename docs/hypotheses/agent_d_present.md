# AGENTE D — Hipóteses revisadas: lado da LEITURA/APRESENTAÇÃO (swapchain, readback, ordenação de execução)

**Papel:** AGENTE D — especialista em apresentação/swapchain/Android (SurfaceFlinger, composition) e nos
caminhos de leitura de imagem no Vulkan (sampler vs transfer, readback, command buffer one-time).
**Alvo:** Xiaomi Mi 11 (alioth), Adreno 650, Vulkan 1.1.128, driver stock.
**Modo:** PESQUISA apenas — nenhum código de implementação proposto como definitivo; apenas hipóteses,
experimentos baratos e conformidade com anti-soluções.
**Versão:** REVISADA na Fase 3, à luz do cross-review (`cross-review.md`) e das revisões de A, B e C.
Mudanças em relação à v1: seção 2 mantida intacta; H1/H2 reformuladas (spec-corretas, sem "só copy comita");
E0 rebaixado a sanity check; E2 corrigido para blit → imagem dedicada; probe GENERAL adicionado (E3);
atlas elevado a fix imediato.
**Síntese:** `processedImage` contém o shader (READBACK-P ✅), mas TODA leitura de `processedImage` para
apresentação (sampler blit e transfer blit, mesmo submission e cross-submission) dá preto; apenas o
readback por transfer num CB one-time separado, no frame seguinte, lê o conteúdo.

---

## 1. Quadro de evidências a explicar (perspectiva leitura/present)

| leitura | método | onde/submission | resultado |
|---|---|---|---|
| `processedImage` → host buffer | transfer readback (`vkCmdCopyImageToBuffer`) | **CB one-time separado, frame seguinte** | ✅ conteúdo (`ff0c1028`) |
| `processedImage` → swapchain | sampler blit | mesmo submission do `applyFrame` | ❌ preto |
| `processedImage` → swapchain | sampler blit | cross-submission (Sub-A applyFrame+fence, Sub-B blit) | ❌ preto |
| `processedImage` → swapchain | transfer blit (`vkCmdBlitImage`) | mesmo submission | ❌ preto |
| `offscreenImage` → swapchain | sampler / transfer blit | TEST MODE | ✅ jogo visível |
| swapchain | transfer readback | TEST MODE | ✅ jogo visível |

Fatos já descartados (doc seção 5): não é librashader (renderiza), não é alpha, não é formato, não é
sampler-vs-transfer isolado, não é cross-submission isolado, não é external sync, não é composição/present.

**Ambiguidade crítica na evidência (afeta TODAS as hipóteses, cross-review §0.2):** `processedImage` é
reescrito todo frame. Um READBACK-P "no frame seguinte" lê **ou** o conteúdo recém-gerado do frame N+1
(se rodar após o `applyFrame` de N+1) **ou** o conteúdo do frame N (se rodar antes da transição
`UNDEFINED→CA` de N+1, que descarta). **A posição do readback na linha do tempo não foi logada** — logo,
"o atraso materializa o resolve" **não está estabelecido**; pode ser apenas "caminho copy-engine + CB
dedicado". Correção barata e prioritária: ver E1.

---

## 2. O que a evidência JÁ descarta sobre execução/ordenação (rigor)

> **Mantida intacta da v1 — contribuição principal do AGENTE D (elogiada nas 4 revisões).**

Antes de propor hipóteses, é preciso enterrar a explicação "race de execução" ingênua:

- **Não é "a leitura roda antes de o GPU terminar de escrever" no sentido Vulkan-legal.** A tentativa 3.10
  submeteu Sub-A (applyFrame), **esperou `filterFence`** (`WaitForFences`, `VulkanRendererContext.cpp:475`
  padrão de `endOneTime`) e só então submeteu Sub-B (blit). Fence esperado = execução de Sub-A completa e
  memória visível ao device por garantia do modelo de memória Vulkan. Mesmo assim o blit leu preto.
- **Não é reuso ilegal de command buffer.** No path inline (3.1) o blit é gravado no **mesmo** CB do
  `applyFrame`, antes de `EndCommandBuffer` — ordenado por barreira intra-CB (`transition`, WIP:1051-1054).
  No split (3.10) o `filterCmdBuf` é re-gravado **após** `WaitForFences` de Sub-A — legal.
- **Não é "submission boundary" por si só.** O readback que funciona também cruza uma submission
  (CB dedicado, separado) — e o split que falha também cruza. Submission boundary é condição necessária
  mas não suficiente; o que diferencia é o **caminho de leitura** e o **estado de layout de onde ele parte**.

Conclusão de ordenação: **se um fence entre escrita e leitura não resolve, a explicação "o GPU ainda não
terminou" está refutada para o caminho de presente.** A assimetria "mesmo frame = preto / frame seguinte =
conteúdo" precisa de outro mecanismo — estado de layout/visibilidade de memória dependente de ciclo de
frame, não de ordenação por fence.

---

## 3. Hipóteses de causa raiz (revisadas na Fase 3)

### Posição geral após o cross-review

O cross-review e o melonDS (referência que **funciona no mesmo Adreno 650**) estabeleceram dois fatos que
**refutam as formas fortes** das minhas hipóteses v1:

1. **melonDS lê a saída do render pass do librashader (`topOutput`) por `vkCmdBlitImage` imagem→imagem,
   no MESMO command buffer do filtro (2357-2383), e funciona.** Logo, "leitura in-frame da saída do filtro
   é incoerente no Adreno" é **falsa como regra geral** — refuta a cláusula "só copy-to-host comita o
   resolve; imagem→imagem não comita" da minha v1-H2.
2. **A transição `CA→SRO` do GameNative (WIP:1051-1054) é spec-correta**: o `finalLayout` do pass final
   do librashader é `COLOR_ATTACHMENT_OPTIMAL` por contrato (`librashader.h:1705-1720`), logo `oldLayout=CA`
   é canônico. A forma forte "o driver não rastreia CA → transição no-op" é **especulação de driver** que
   contradiz a semântica de `finalLayout` e o próprio melonDS (que parte do mesmo CA assumido em 2350/2358).
   Essa forma forte foi **retirada** na revisão.

O que **sobrevive** e motiva as hipóteses revisadas: o veneno é **específico da configuração do caminho
de leitura do `processedImage` no GameNative** (triangulação do review A §0.1). As hipóteses abaixo foram
reescritas para serem spec-corretas e refutáveis — o mecanismo preciso fica **declarado em aberto** onde
não é demonstrável a partir das fontes disponíveis.

### H1 (revisada) — Divergência de estado/layout específica da SEQUÊNCIA do GameNative, não do CAO em geral

**Enunciado (forma fraca, spec-correta).** A transição `CA→SRO` emitida pelo GameNative tem `oldLayout`
canônico, mas pode não produzir o efeito esperado porque o **estado efetivo** que o driver rastreia para o
`processedImage` diverge do estado que o GameNative assume **no contexto específico da sequência do
GameNative** — não porque "o driver não rastreia CA" (retirado), e não como propriedade geral do Adreno
(melonDS a contradiz). A divergência, se existe, vem de um desvio na cadeia concreta do GameNative:
`processedImage` é escrito por um runtime de terceiros (librashader) com passes internos (`use_dynamic_rendering=false`,
render passes internos próprios), o GameNative **não mantém tracking manual de layout** (diferente do
melonDS, `resource.layout`, 2036/2260/2279), e a transição de leitura é gravada no mesmo CB do `applyFrame`
sem conhecimento do histórico de layout real. O mecanismo preciso (estado GMEM/UBWC, descriptor `GENERAL`
vs `SHADER_READ_ONLY`, transição que não materializa na prática) é **declarado em aberto**.

**Por que não é mais a forma forte da v1.** A v1 afirmava "a transição é no-op porque o `oldLayout` assumido
não é o que o driver rastreia". Isso foi refutado por dois lados: (a) `finalLayout` garante que o layout
pós-pass **é** `CA` por construção; (b) o melonDS parte do mesmo CA assumido (`imageBarrier(topOutput,
TRANSFER_SRC_OPTIMAL)` em 2358, com `oldLayout = resource.layout = COLOR_ATTACHMENT_OPTIMAL` após 2350) e
o blit **funciona**. A divergência, portanto, **não é do `oldLayout` da transição**; se existe, é do estado
interno pós-resolve/clean do driver para essa sequência específica — e é isso que os experimentos E2 (probe
GENERAL) e E3 (blit → imagem dedicada) separam de "timing/visibilidade".

**Previsões falsificáveis (o que testa):**
- E2 (probe GENERAL): se `CAO→GENERAL` + descriptor `GENERAL` mostrar conteúdo, o problema é o
  layout/estado de sample assumido na transição/descriptor do CB de presente (suporta H1 fraca), **não** o
  timing.
- E1 (readback in-frame): se o readback in-frame lê conteúdo, o dado está visível in-frame via copy engine →
  o problema é restrito ao **caminho de leitura do CB de presente** (família H1), e o timing está descartado.

### H2 (revisada) — Visibilidade in-frame restrita ao caminho de leitura do presente, mecanismo em aberto

**Enunciado (substitui a v1; a cláusula "só copy-to-host comita" foi REMOVIDA).** Na configuração do
GameNative, o conteúdo do `processedImage` é legível por copy engine (readback) mas não pelo caminho de
leitura do presente (sampler/blit → swapchain) dentro do ciclo do frame — **sem afirmar qualquer propriedade
geral sobre imagem→imagem** (o melonDS a contradiz como regra). A v1 postulava que "leituras imagem→imagem
não acionam o commit do resolve". Isso é **falso no Adreno** (blit `topOutput→atlasOutput` do melonDS
funciona). A reformulação honesta: **ou** a visibilidade in-frame via copy engine existe (e então o problema
é só do caminho do presente — colapsa em H1), **ou** não existe in-frame mesmo via copy engine (e então há
uma dependência de ciclo de frame a caracterizar, específica da sequência do GameNative, com mecanismo em
aberto). **Qual destas prevalece é o que E1 discrimina** — este é o valor central de E1.

**Relação com a evidência.** O único caminho de leitura que provadamente funcionou é o readback num CB
one-time dedicado, submetido e esperado com `UINT64_MAX`, no ciclo seguinte — mas a **posição relativa ao
`applyFrame` de N+1 nunca foi logada** (ambiguidade §1). Sem isso, "o frame seguinte materializa" não está
estabelecido: pode ser apenas o caminho copy-engine + CB dedicado, sem nenhum atraso causal. A hipótese H2
**depende de E1 com log de posição** para ser confirmada ou descartada; hoje ela é um mecanismo em aberto,
não uma afirmação.

> **Posição frente ao enunciado do usuário:** NÃO é um race de execução Vulkan-legal (§2 o refuta), e NÃO é
> "resolve não-comitado para imagem→imagem" (melonDS o contradiz). O que resta testar é se a visibilidade
> in-frame do `processedImage` existe via copy engine (H1 fraca: o problema é o caminho do presente) ou só
> após um ciclo (H2: dependência de ciclo específica do GameNative) — distinguíveis com E1, com o probe
> GENERAL (E2) separando layout-assumido de timing.

---

## 4. Ponto específico: destino como variável residual (padrão melonDS)

Do lado do presente, a lição estrutural (independe de H1 vs H2) é:

| leitura que funciona | leitura que falha |
|---|---|
| fonte: imagem com layout 100% controlado pelo app (`offscreenImage`, finalLayout+transição explícita) | fonte: imagem com layout **assumido por contrato** (`processedImage`, sem barreira final do autor) |
| destino: **buffer host-visível** (readback) **ou imagem intermediária dedicada do app** (melonDS atlas) | destino: **swapchain** lido no mesmo CB/ciclo da escrita |
| estrutura: **CB one-time dedicado**, transição própria, submit + `WaitForFences(UINT64_MAX)`, ciclo seguinte | estrutura: blit gravado no CB do `applyFrame` ou em split do **mesmo frame** |

A referência que funciona (melonDS, `VulkanSurfacePresenter.cpp:2127-2393`) **nunca lê o output do filtro
para o presente diretamente**: copia `topOutput`/`bottomOutput` → `atlasOutput` por `vkCmdBlitImage` no
**mesmo CB do filtro** (2361-2382), deixa o atlas em `GENERAL` (2383) e amostra o atlas num submission
posterior (2390-2391) em `GENERAL` (descriptor 2625, barreira de presente `GENERAL→GENERAL` 3094-3142).
A imagem que o sampler toca foi produzida por **transfer write** (não por render pass) e o app é dono do
layout dela. O GameNative tenta o oposto: amostrar/blitar a **saída do render pass do librashader** direto →
swapchain, no mesmo ciclo — o único caso que falha. **A variável residual que nenhuma hipótese explica
conclusivamente é o destino** (swapchain no mesmo ciclo vs imagem dedicada do app) — e é exatamente a
variável que o padrão melonDS troca.

**Nota de anti-solução 4:** a swapchain do GameNative tem **apenas `COLOR_ATTACHMENT_BIT`**
(`VulkanRendererContext.cpp:279`) — o estado correto para render pass. **Nenhum experimento abaixo blita
para a swapchain**, e o fix (atlas) a mantém como `COLOR_ATTACHMENT` apenas, apresentando via render pass
(como o próprio presente de `offscreenImage` já faz, que funciona). Não há conflito com a anti-solução 4.

---

## 5. Experimentos (ordem de prioridade — parar no primeiro informativo; cada passo é independente e reversível)

### E1 — Readback in-frame do `processedImage` + log de posição/oldLayout (PRIORIDADE MÁXIMA) — ~1-2 h

Reutilizar `diagnoseProcessedReadback()`, mas chamá-lo **no mesmo frame**, imediatamente após `applyFrame`,
antes do presente — num CB one-time separado (estrutura idêntica à do readback que funciona). **Obrigatório
logar:**
- a **posição** do readback relativa ao `applyFrame` do frame corrente (antes/depois da transição
  `UNDEFINED→CA` de N+1) — corrige a ambiguidade §1 que afeta todas as hipóteses;
- o `srcImageLayout`/`oldLayout` usado na transição dele.

**Interpretação (corrigida da v1 — o mapeamento impreciso foi apontado pelo review A §3.2):** E1 discrimina
**timing/visibilidade** (o dado está visível in-frame via copy engine?), **não** o mecanismo dentro do caminho
do CB de presente (transição no-op vs cache de sampler — isso é o que E2/E3 separam).
- **Lê conteúdo no mesmo frame** → o dado está visível in-frame; o problema é **restrito ao caminho de
  leitura do CB de presente** (família H1: layout/descriptor/estado de sample) — reforça H1 fraca, mata H2.
- **Lê preto no mesmo frame** → ou (a) há uma dependência de ciclo de frame (H2, mecanismo em aberto — só
  confirmável com o log de posição mostrando conteúdo N+1 **fresco**), ou (b) o copy engine também lê preto
  in-frame, o que **isola a variável destino/estado**, não o timing. Continuar com E2/E3.

### E0 — Sanity check do fence (5 min, NÃO é discriminante) — rebaixado

**Correção da v1:** a v1 propôs `vkQueueWaitIdle` entre `applyFrame` e o blit. Isso é **no-op no path
inline** (nada foi submetido ainda — o CB só é submetido depois), e no path split é exatamente a tentativa
3.10 (que falhou). Não acrescenta informação de ordenação (o fence de 3.10 já garante a execução completa).

**Papel revisado:** verificação de sanidade de 5 min — confirmar que o `filterFence` de 3.10 estava **de
fato associado** à submission do `applyFrame` (fence errada/desatualizada seria a única leitura que faria o
resultado de 3.10 não ser conclusivo). Se a associação estiver correta, a race de execução fica enterrada
definitivamente. **Resultado esperado: o fence está associado e o blit continua preto.**

### E2 — Probe GENERAL: `CAO→GENERAL` + descriptor `GENERAL` no sampler (~30 min-1 h) — NOVO

**Motivação:** o melonDS amostra o atlas **sempre em `GENERAL`** (descriptor `imageLayout=GENERAL`, 2625;
barreira de presente `GENERAL→GENERAL`, 3102-3103). Esse parâmetro nunca foi testado no GameNative. É o
probe mais barato que discrimina "layout-assumido/estado de sample" de "timing" — replicando o receituário
da referência que funciona, sem depender de tracking manual que o GameNative não tem (crítica do review A
§1.2/B: não partir de `UNDEFINED`, que descarta conteúdo).

**Mecânica:** transição `processedImage CA→GENERAL` + sampler/descriptor apontando `imageLayout=GENERAL`
(no blit sampler do presente). **Sem tocar a swapchain** (anti-4 ✅).
- **Se aparecer conteúdo** → o problema é o layout/estado de sample assumido na transição/descriptor do CB
  de presente (suporta H1 fraca). O fix mínimo = apresentar amostrando em `GENERAL` (padrão melonDS 2625).
- **Se continuar preto** → o layout de sample não é o fator; seguir para E3 (destino imagem dedicada) e o
  atlas.

### E3 — Blit do `processedImage` para IMAGEM DEDICADA, com barreira transfer-larga do melonDS (2-4 h) — E2 v1 corrigido

**Correção da v1 — por que o E2 original era inválido.** A v1 propôs `vkCmdBlitImage(processedImage →
swapchain, TRANSFER_DST_OPTIMAL)` num CB one-time dedicado. Isso é **ilegal/UB** com a swapchain atual:
blit de transferência exige que o destino tenha `VK_IMAGE_USAGE_TRANSFER_DST_BIT`, e a swapchain tem
**apenas `COLOR_ATTACHMENT_BIT`** (`VulkanRendererContext.cpp:279`). Adicionar `TRANSFER_DST_BIT` violaria a
anti-solução 4 **e** re-testaria a tentativa 3.11 (que já falhou). O equivalente **útil** (cross-review §3,
review C §3.3) é blitar para uma **imagem dedicada criada pelo app** — exatamente o padrão melonDS.

**Mecânica (réplica melonDS):** criar `dedicatedImage` pelo app com `usage = TRANSFER_SRC|TRANSFER_DST|SAMPLED`,
tiling OPTIMAL, `initialLayout=UNDEFINED` (padrão `createRetroArchImage`, 1986-2000). Gravar num CB dedicado
one-time (`beginOneTime`/`endOneTime`, `VulkanRendererContext.cpp:462-477`):
1. **Barreira pós-filtro transfer-larga** (parâmetro do melonDS 2258/2270 **nunca testado** no caminho
   transfer do GameNative — 3.11 testou o caminho sampler/estreito): `srcAccess = MEMORY_WRITE|TRANSFER_WRITE|
   COLOR_ATTACHMENT_WRITE`, `srcStage = ALL_COMMANDS`, `dst = TRANSFER_READ`, `dstStage = TRANSFER`,
   transição `processedImage CA→TRANSFER_SRC_OPTIMAL`;
2. `vkCmdBlitImage(processedImage → dedicatedImage, TRANSFER_DST_OPTIMAL, FILTER_NEAREST)`;
3. transição `dedicatedImage → GENERAL`;
4. submit + `WaitForFences(UINT64_MAX)`; então ler o `dedicatedImage` (readback ou sampler blit → swapchain
   via render pass) — **nunca blit para a swapchain** (anti-4 ✅).
- **Se aparecer conteúdo** → a leitura do output do filtro funciona quando o destino é uma imagem dedicada do
  app (padrão melonDS), mesmo in-frame → o problema é a combinação **fonte output-do-filtro + destino
  swapchain**, não o caminho de leitura nem o timing. Reforça o atlas como fix imediato.
- **Se continuar preto** → o problema persiste com intermediária dedicada; então a variável é a fonte
  (`processedImage` escrito pelo librashader) com **a barreira transfer-larga testada** — elimina a família
  "barreira insuficiente" e aponta para a topologia/submission completa (submit-and-wait dedicado do CB do
  filtro, tracking de layout).

---

## 6. Fix imediato adotado: padrão melonDS completo (atlas intermediário) — NÃO é fallback

**Mudança de posição da v1:** na v1 o atlas era o "fix sob H2" / fallback. Após o cross-review, o atlas é a
**receita da referência que funciona** e deve ser o **fix imediato após E1/E2/E3**, sob qualquer mecanismo
(cross-review §6; review B: "elevar o atlas a experimento de fix imediato"). Reimplementação fiel do melonDS:
- imagem intermediária dedicada (`atlasOutput`) criada pelo app com `TRANSFER_SRC|TRANSFER_DST|SAMPLED`,
  tiling OPTIMAL, `initialLayout=UNDEFINED`, layout **100% rastreado pelo app** (`resource.layout`, iniciado
  `UNDEFINED` — padrão `createRetroArchImage`, 1986-2036);
- cópia da saída do filtro (`processedImage`) para o atlas **no mesmo command buffer** do `applyFrame`:
  `vkCmdCopyImage` (copy engine) como caminho primário, `vkCmdBlitImage` (transfer) como variante de
  diagnóstico (mitiga a auto-contradição da leitura por textura da saída — review C §2.5);
- barreira transfer-larga pós-filtro (E3 item 1);
- `submitAndWait` dedicado (End → `QueueSubmit` sob lock → `WaitForFences(UINT64_MAX)`, melonDS 2228-2243);
- **present do atlas em `GENERAL`** num submission posterior (melonDS 2390-2391, 2625, 3094-3142);
- swapchain permanece `COLOR_ATTACHMENT` apenas (279) — present via render pass, como o `offscreenImage` que
  já funciona (3.5).

**Ressalva do cross-review (§6, correta e assumida):** o sucesso do atlas **NÃO deve ser lido como
confirmação de H1 nem de H2** — o atlas é um fix robusto sob múltiplos mecanismos (destino, submit-and-wait,
GENERAL, escrita por transfer). Os experimentos E1-E3 existem para identificar a causa; o atlas é o remédio.

---

## 7. Observável esperado (leitura de log específica)

- **E1:** no mesmo frame, `READBACK-P processed WxH: [ff0c1028 ...]` (conteúdo) ⇒ **H1 fraca** (problema no
  caminho do CB de presente); `READBACK-P processed WxH: [00000000 ...]` no mesmo frame, com o log de posição
  decidindo se o conteúdo do frame seguinte é **fresco** (N+1, depois do `applyFrame`) ⇒ **H2** (dependência
  de ciclo) ou apenas caminho copy-engine. Log do `srcImageLayout` revela de qual estado a leitura boa parte.
- **E0:** confirma associação `filterFence` ↔ submission do `applyFrame`; blit continua preto ⇒ race de
  execução definitivamente enterrada.
- **E2:** `PRESENT-P: blit GENERAL → visible=1` ⇒ layout/estado de sample é o fator (H1 fraca);
  `visible=0` ⇒ não é o layout de sample.
- **E3:** `READBACK-D dedicated WxH: [ff0c1028 ...]` (conteúdo na imagem dedicada) ⇒ fonte OK quando o destino
  é imagem do app → reforça atlas como fix; `READBACK-D ... [00000000 ...]` com barreira transfer-larga ⇒
  elimina a família "barreira insuficiente".
- Critério de sucesso final: imagem filtrada visível no Mi 11 (pixels ≠ 0 na swapchain, sem flicker).

---

## 8. Esforço / risco

| experimento | esforço | risco |
|---|---|---|
| **E0** (sanity check do fence) | ~5 min | baixo (verificação de log; confirmação esperada = fence ok, preto) |
| **E1** (readback in-frame + log de posição) | ~1-2 h | baixo (código de diagnóstico já existe; só muda o momento da chamada + logs) |
| **E2** (probe GENERAL) | ~30 min-1 h | baixo (1 transição + layout de descriptor) |
| **E3** (blit → imagem dedicada + barreira transfer-larga) | ~2-4 h | médio (nova imagem; reutiliza `beginOneTime`/`endOneTime`, `CmdBlitImage`, `CmdCopyImage` na `VkTable`) |
| Fix atlas (padrão melonDS completo) | ~1-2 dias | médio-alto (topologia de imagens + reescrita do submission + tracking de layout) |

Nenhum experimento mexe em alpha, em `TRANSFER_DST` da swapchain (que permanece só `COLOR_ATTACHMENT`),
em render passes do compositor, em ABI do librashader, nem em `use_dynamic_rendering`.

---

## 9. Conformidade com as 12 anti-soluções (doc seção 6)

| # | anti-solução | conformidade desta proposta (revisada) |
|---|---|---|
| 1 | não rediagnosticar "librashader renderiza?" | ✅ aceita READBACK-P como prova; E1 mede **timing/visibilidade**, não se o filtro renderiza |
| 2 | não testar sampler vs transfer como causa raiz | ✅ os dois falham; a hipótese é sobre **estado/destino da fonte**, não sobre o método. E3 usa transfer como **estrutura de teste** (réplica melonDS), não como causa |
| 3 | não testar split de submissions sozinho | ✅ E0 é sanity check (verifica associação de fence, não "só dividir"); E3 replica o **padrão one-time dedicado** do readback que funciona |
| 4 | não mexer em `TRANSFER_DST` da swapchain | ✅ swapchain permanece `COLOR_ATTACHMENT_BIT` apenas (279). **E2 corrigido (E3) blita para imagem dedicada, NUNCA para a swapchain** — a v1 (E2→swapchain) violava a anti-4 e foi removida |
| 5 | não mexer em alpha/compositeAlpha | ✅ intocado |
| 6 | não alargar mais barreiras `ALL_COMMANDS`/`MEMORY_WRITE` | ✅ E3 usa a barreira transfer-larga do melonDS (2258/2270) **no caminho transfer**, que 3.8/3.11 nunca testaram com esse receituário; não é "empilhar barreiras largas" e fica explícito que é a receita da referência, não o fix (cross-review §7). E0 usa sincronização host (sanity) |
| 7 | não readicionar `queueMtx` como solução | ✅ não usa `queueMtx` como fix; o submit-and-wait do atlas usa o lock existente (como o melonDS usa `GetQueueLock`, 2237) |
| 8 | não reintroduzir prebuilts em `jniLibs` | ✅ intocado |
| 9 | não descomentar target `winlator` no CMake | ✅ intocado |
| 10 | não reativar TEST MODE como resposta | ✅ E1-E3 são instrumentação de diagnóstico no path real, não TEST MODE |
| 11 | não assumir problema no layout do `offscreenImage` | ✅ `offscreenImage` intocado (fix 3.5 mantido); alvo é o estado pós-frame do `processedImage` |
| 12 | não mudar `use_dynamic_rendering` para `true` | ✅ `use_dynamic_rendering=false` mantido (3.3); issue #225 fora de escopo |

---

## 10. Declaração de revisão da Fase 3

Reviso minha proposta da Fase 2 à luz do cross-review consolidado e das revisões de A, B e C, e declaro o
seguinte:

1. **Mantido:** a seção 2 (refutação da race de execução via fence da tentativa 3.10) permanece intacta e é
   a contribuição principal — considerada o argumento mais rigoroso do conjunto por todas as revisões.
2. **Corrigido (spec):** retirei a forma forte de H1 ("transição no-op porque o driver não rastreia CA"),
   que contradiz a semântica de `finalLayout` e o melonDS. H1 agora é a forma fraca "divergência específica
   da sequência do GameNative", com mecanismo declarado em aberto.
3. **Corrigido (mecanismo):** removi a cláusula "só copy-to-host comita o resolve; imagem→imagem não comita"
   de H2, refutada pelo blit imagem→imagem do melonDS. H2 agora é uma hipótese de timing/visibilidade a ser
   decidida por E1 (com log de posição), sem propriedade geral sobre o Adreno.
4. **Corrigido (experimentos):** E0 rebaixado de discriminante para sanity check de 5 min (o
   `vkQueueWaitIdle` no meio da gravação do mesmo CB é no-op; no split é 3.10). E2 original (blit
   `processedImage → swapchain`) foi declarado inválido (swapchain tem só `COLOR_ATTACHMENT_BIT`, 279;
   adicionar `TRANSFER_DST_BIT` viola a anti-solução 4 e re-testa 3.11) e substituído por E3: blit para
   **imagem dedicada** com a barreira transfer-larga do melonDS (padrão da referência).
5. **Adicionado:** E2 novo (probe GENERAL — `CAO→GENERAL` + descriptor `GENERAL`, replicando melonDS 2625),
   o teste mais barato que separa layout-assumido de timing.
6. **Elevado:** E1 (readback in-frame + log de posição relativa ao `applyFrame` e `srcImageLayout`) à
   prioridade máxima — é o experimento mais informativo faltante e corrige a ambiguidade que afeta todas as
   hipóteses.
7. **Mudança de posição:** o atlas intermediário (padrão melonDS completo) deixa de ser fallback e passa a
   ser o **fix imediato** após E1-E3 — a única receita não tentada com evidência positiva (a referência
   funciona no mesmo GPU), assumindo a ressalva de que seu sucesso não confirma mecanismo específico.

---

## 11. Referências

- `docs/librashader-failed-attempts.md` — seções 3 (tentativas), 4 (evidências), 5 (fatos), 6 (anti-soluções), 7 (pistas).
- `docs/hypotheses/cross-review.md` — fatos 1-7, verdicts, rejeições técnicas, experimentos P1-P4, fix de consenso.
- `app/src/main/cpp/winlator/VulkanRendererContext.cpp` — `beginOneTime`/`endOneTime` (462-477), `transition` (479-487), swapchain (244-302, `imageUsage = COLOR_ATTACHMENT_BIT` na 279), `renderFrame`/present (965-985).
- Integração WIP (commit dangling `6a648093`) — `renderFrame` libraPath (1012-1117: applyFrame 1042, transição 1051-1054, blit 1056, submit/present 1104-1116); `recordCompositorPass` (1652-1803: transições offscreen 1725-1738 e processed 1783-1796).
- Referência melonDS `VulkanSurfacePresenter.cpp` — `runRetroArchFilter` (2127-2393), `createRetroArchImage` (1980-2038: usage 1995-1998, `initialLayout=UNDEFINED` 2000, `resource.layout` 2036), `submitAndWait` (2228-2243), `imageBarrier` (2255-2280: srcAccess 2258, `oldLayout=resource.layout` 2260, srcStage 2270), blit `topOutput/bottomOutput → atlasOutput` no mesmo CB (2357-2383), atlas → `GENERAL` (2383), descriptor `GENERAL` (2625), barreira de presente `GENERAL→GENERAL` (3094-3142), swapchain só `COLOR_ATTACHMENT_BIT` (1484).
- Contrato do librashader (sem barreira no pass final; saída permanece em CAO; caller faz a transição final): `app/build/generated/librashader/include/librashader.h:1705-1720`.
- Documentos dos colegas: `agent_a_vulkan.md`, `agent_b_librashader.md`, `agent_c_melonds.md`, e revisões `review_a_vulkan.md`, `review_b_librashader.md`, `review_c_melonds.md`.
