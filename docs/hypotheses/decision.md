# Decisão final do processo de hipóteses — tela preta do librashader

**Agente:** INTEGRADOR/CRÍTICO (5º agente). **Fase:** consolidação das pontuações (Fase 4) + recomendação final.
**Alvo:** Xiaomi Mi 11 (Adreno 650, Vulkan 1.1.128, driver stock). **Contexto:** bug resistiu a 12 tentativas.
**Entradas:** 4 hipóteses revisadas (Fase 3), `cross-review.md`, 4 pontuações (Fase 4) e o log de tentativas.
**Regime:** C1-C3 = peso 3; C5 = peso 2; C6 = peso 1; C4 = gate (5 = conforme; desqualifica se violar).
Total = 3·C1 + 3·C2 + 3·C3 + 2·C5 + C6. **Pesquisa apenas — nenhum código de implementação.**

---

## 1. Matriz de pontuação agregada

> Totais ponderados re-computados dos arquivos `score_*.md` (conferidos: idênticos aos declarados por cada
> avaliador). Cada linha = avaliador; cada coluna = hipótese avaliada. C4 passou para TODAS (nenhuma
> desqualificação).

| avaliador ↓ / hipótese → | **A** (Vulkan/layout·GMEM) | **B** (librashader internals) | **C** (melonDS/topologia) | **D** (apresentação/leitura) |
|---|---|---|---|---|
| **A** (avaliou B, C, D) | — | 50 | 49 | 54 |
| **B** (avaliou A, C, D) | 54 | — | 49 | 53 |
| **C** (avaliou A, B, D) | 54 | 48 | — | 53 |
| **D** (avaliou A, B, C) | 53 | 51 | 57 | — |
| **TOTAL** | **161** | **149** | **155** | **160** |
| **MÉDIA** | **53,67** | **49,67** | **51,67** | **53,33** |
| amplitude (max−min) | 1 | 3 | 8 | 1 |
| C4 (gate) | PASSA (3/3) | PASSA (3/3) | PASSA (3/3) | PASSA (3/3) |

**Detalhe dos desvios declarados (gate, NÃO desqualificadores):** nenhuma das 4 hipóteses violou as 12
anti-soluções nas versões revisadas. As únicas tensões registradas, aceitas por consenso do cross-review
(§6/§7): (i) **desvio declarado da anti-solução 4** por A e C (no *fix*: reverter a swapchain a só
`COLOR_ATTACHMENT`, como melonDS:1484) — aceito como "reverter, não re-testar"; (ii) **barreira
transfer-larga** (2258/2270) em A (P4), B (E4) e D (E3) — considerada conforme à anti-solução 6 porque atua
no caminho **transfer** (o 3.8 que falhou era o caminho **sampler**).

---

## 2. Ranking final (melhor → pior)

1. **A — Vulkan/layout & GMEM (revisada: família H2/atlas como primária, H1' como pergunta falsificável).**
   **161 / média 53,67.** É a única hipótese que recebeu **C1 = 5/5 de TODOS os avaliadores** — cobertura
   completa do padrão de evidências com as duas correções de base da Fase 3 (posição do READBACK-P não logada;
   tentativa 3.12 sem log) e sem superalegação de mecanismo. Também recebeu **C2 = 5/5 de todos** — P1/P2 (log
   de posição + readback in-frame) são exatamente os discriminadores que o cross-review elegeu como os mais
   informativos, e P3/P4 desambiguam dentro da mesma família. A eleição da família H2/atlas (única com evidência
   positiva: o melonDS funciona no mesmo Adreno) e o caminho diagnostic-first (baratos primeiro, fix por último)
   foram reconhecidos pelos três colegas. Única nota mais baixa: C6=3 (D), por esforço do atlas (~1 dia) — não
   por correção.

2. **D — apresentação/leitura (revisada: visibilidade in-frame restrita ao caminho do presente; mecanismo em
   aberto).** **160 / média 53,33.** Praticamente **empata com A** (1 ponto). D vence isoladamente em C2
   (árvore de experimentos mais granular e barata: E0 sanity 5min → E1 readback in-frame → E2 probe GENERAL →
   E3 imagem dedicada) e tem a contribuição mais rigorosa do conjunto: a **seção 2 enterra a race de execução**
   via o fence da tentativa 3.10 — argumento elogiado nas 4 revisões. Perde para A porque C1 (cobertura da
   evidência) e C3 (por que o melonDS escapa) são ligeiramente menos completos — D herda a explicação do
   "destino" do atlas sem mecanismo próprio.

3. **C — melonDS/topologia (revisada: divergência de estado/layout específico da config GameNative; fix =
   reimplantação fiel do melonDS).** **155 / média 51,67.** A explicação da assimetria mais **unificada**
   (posse integral do histórico de layout: o app é dono do offscreen e do atlas; o `processedImage` não tem
   dono) e o fix de **menor risco de reintroduzir o preto** (copy engine primário; C5=5 de D — a maior nota
   isolada da matriz). Mas **C6=2 de todos** (maior esforço: +2 imagens dedicadas, reescrita do bloco de
   submission, reaplicar a base revertida) e experimentos individuais ligeiramente mais pesados (probe GENERAL
   estimado em ~1 dia vs ~30min–1h em A/D) derrubam a média.

4. **B — librashader internals (revisada: estado efetivo diverge do CAO assumido; GENERAL como layout
   tolerado).** **149 / média 49,67.** Última. Disciplina de fontes elogiada (só `librashader.h:1705-1720` [V] e
   o blob melonDS [V] como fato; internals marcados [NV]) e C2 forte (E1/E2/E4 baratos e discriminantes), mas o
   **mecanismo central é o menos ancorado causalmente**: "GENERAL é o layout tolerado" é extrapolado do melonDS,
   que **nunca amostra a saída do filtro direto** — logo a ligação causal "GENERAL toleraria o estado divergente"
   não é demonstrada (C3=3 de C, a nota mais baixa da matriz). Além disso, **B-E3 usa blit (transfer) como cópia
   primária filtro→atlas sem o fallback copy engine** que o consenso adotou para não depender da "leitura
   frágil" (C5=3 de D).

---

## 3. Análise de consenso / divergência

### 3.1 O que TODOS concordam (consenso duro)

1. **O librashader NÃO é o problema** — `processedImage` contém o shader (READBACK-P ≠ 0). Não re-diagnosticar.
2. **O fence não é a causa** — 3.10 (split com `WaitForFences`) leu preto; a race de execução está enterrada.
3. **A transição `CA→SRO` é spec-correta** — o pass final do librashader deixa a saída em `COLOR_ATTACHMENT_OPTIMAL`
   por contrato (`librashader.h:1705-1720`); `oldLayout=CA` é canônico. A forma forte "o driver não rastreia CA"
   foi retirada por todos.
4. **A família "leitura in-frame da saída do filtro é incoerente / só copy-to-host comita" é refutada** pelo
   melonDS, que blita `topOutput→atlas` no mesmo CB do filtro e funciona no mesmo Adreno.
5. **O readback in-frame do `processedImage` (P2/E1) é o experimento-chave faltante**, junto com o **log da
   posição do readback relativa ao `applyFrame` (P1)** — sem isso, "o frame seguinte materializa o resolve"
   não está estabelecido (o `processedImage` é reescrito todo frame).
6. **A variável residual "destino" (swapchain no mesmo ciclo vs imagem dedicada do app) não é explicada por
   nenhuma hipótese** — é a explicação estrutural que sobra, e é exatamente a variável que o padrão melonDS troca.
7. **O fix de maior probabilidade é o mesmo em todas as 4**: reimplantação fiel do padrão melonDS (atlas
   intermediário dedicado + cópia no mesmo CB do filtro + submit-and-wait + present do atlas em `GENERAL` +
   layout tracking manual + copy engine como caminho primário). **Nenhum mecanismo foi provado como causa única,
   e o sucesso do atlas NÃO deve ser lido como confirmação de mecanismo específico.**

### 3.2 Onde divergem

1. **Mecanismo causal exato** (todos o declaram "em aberto", mas com pesos diferentes):
   - **A** → **destino/topologia** (leitura da saída do filtro com destino direto a swapchain no mesmo ciclo é a
     configuração problemática; H1' rebaixada a pergunta falsificável).
   - **B** → **estado efetivo divergente** do driver para o `processedImage`; `GENERAL` como layout tolerado
     (mecanismo mais fraco — ver 3.3).
   - **C** → **posse/tracking do layout**: o app não é dono do histórico do `processedImage` (oldLayout assumido
     vs rastreado); falta intermediária dedicada.
   - **D** → **visibilidade in-frame restrita ao caminho do presente**; possível dependência de ciclo de frame
     (só decidível por E1).
2. **Segurança vs esforço**: C prioriza robustez (fix mais seguro, mas o mais caro e o único que exige
   reaplicar a base revertida antes dos diagnósticos); A e D priorizam caminho barato-primeiro com o mesmo fix
   ao final; B prioriza simplicidade mas arrisca a "leitura frágil" ao não adotar copy engine como primário.
3. **Estimativa do fix atlas**: ~1 dia (A, B) vs ~1-2 dias (C, D).
4. **Ordem de diagnóstico**: todos convergem para P1→P2 primeiro; C insere o probe GENERAL (7.1) e a escada
   minimal-diff (7.4) antes da topologia; A insere P3/P4 como desambiguação dentro da família H2. Divergência
   menor, sem impacto no resultado.

### 3.3 Discordância relevante sobre uma mesma hipótese

- **Hipótese C: 57 (D) vs 49 (A) e 49 (B)** — a maior amplitude da matriz (8 pts) e a maior nota isolada (57).
  O que revela: **D (especialista em apresentação) julgou a qualidade do plano** — C tem a explicação mais
  unificada (C3=5) e o menor risco de reintroduzir o preto (C5=5). **A e B julgaram o custo do plano** — C2=4
  e C6=2 (maior esforço, experimentos que exigem a base revertida). Como o fix é idêntico nas quatro, a
  discordância não é sobre *o que fazer*, e sim sobre **qual virtude pontua**: "melhor fix" (D) vs "caminho mais
  barato até o fix" (A/B). O resultado prático é neutro: o fix de C é o mesmo fix adotado por todos.
- **Hipótese B: 48 (C) vs 50 (A) vs 51 (D)** — a menor nota da matriz (48). Revela que o mecanismo
  "GENERAL tolerado" é o **menos ancorado causalmente**: o avaliador C — o que mais conhece o melonDS — apontou
  que a extrapolação não fecha (o melonDS nunca amostra a saída do filtro direto, então "GENERAL tolera o estado
  divergente" é inferência, não demonstração). É a prova de que a disciplina de fontes [V]/[NV] de B foi bem
  recebida (C1 ok) mas não compensou o mecanismo frágil.
- **Hipótese A: 54, 54, 53** e **Hipótese D: 54, 53, 53** — as duas mais **consistentes** (amplitude 1). A
  diferença A×D se concentra em **C1**: A recebeu 5/5 de todos; D recebeu 5/5 só de C (A e B deram C1=4 por D
  herdar a explicação do escape do melonDS). Isso indica que A é percebida como a que melhor **cobre** a
  evidência, e D como a que melhor **desenha experimentos** — o que explica o empate prático (ver §4.2).

---

## 4. HIPÓTESE ELEITA (recomendação final) — **A (família H2/atlas, Vulkan)**

### 4.1 Mecanismo proposto (formulação limpa)

> O librashader renderiza corretamente: a saída do pass final permanece em `COLOR_ATTACHMENT_OPTIMAL` por
> contrato (`librashader.h:1705-1720`) e `processedImage` contém os pixels do shader (READBACK-P). Toda leitura
> de `processedImage` com **destino = swapchain dentro do mesmo ciclo do frame** falha — sampler e transfer,
> mesmo e cross-submission (3.1/3.8/3.10/3.11/3.12). As únicas leituras que funcionam — o blit do melonDS
> `topOutput→atlasOutput` no mesmo CB do filtro e o READBACK-P por transfer num CB one-time dedicado com wait
> `UINT64_MAX` — **nunca leem a saída do filtro diretamente para a swapchain no mesmo ciclo**. Logo, a
> **configuração problemática é a topologia de destino** (leitura direta filtro→swapchain), não o layout, não o
> caminho sampler-vs-transfer, não a submission. O **mecanismo exato permanece em aberto** (candidatos:
> divergência de estado efetivo específica da sequência do GameNative; cache/UBWC não-drenado; posse do layout);
> o padrão melonDS é o **fix robusto sob múltiplos mecanismos**, e seu sucesso não confirma um mecanismo
> específico.

### 4.2 Por que venceu (escores + robustez + testabilidade)

- **Escore:** maior total (161) e média (53,67) da matriz. Recebeu **C1 e C2 = 5/5 de todos os três avaliadores**
  — o único caso de unanimidade em critério de peso 3 em toda a matriz. Não recebeu nenhuma nota < 4.
- **Robustez:** o fix (atlas) é a **receita da referência que funciona no mesmo Adreno**, e usa **copy engine
  como caminho primário** filtro→atlas (não depende da "leitura frágil" da saída do filtro) — a mesma
  mitigação que o consenso adotou.
- **Testabilidade:** experimentos P1-P4 explícitos, baratos, independentes e reversíveis, que mapeiam 1:1 aos
  P1-P4 do cross-review e rodam **no path atual, sem reaplicar a base revertida** antes do diagnóstico.
- **Honestidade metodológica:** A declara abertamente que nenhum mecanismo foi provado, corrige a base de
  evidências (READBACK-P sem posição logada; 3.12 sem log) e não faz afirmação não verificável de driver.

### 4.3 Ressalvas que o vencedor carrega e como mitigá-las

| ressalva | mitigação |
|---|---|
| **Mecanismo não provado** — o atlas pode resolver sob várias causas; não se saberá qual. | Rodar P1-P4 ANTES do fix; registrar no log qual experimento "acendeu" primeiro. NÃO ler o sucesso do atlas como confirmação de mecanismo. |
| **Empate prático com D (1 ponto)** — A e D são, na prática, duas molduras do mesmo plano. | A vence pelo critério de desempate (ver 4.4). No plano de execução, incorporar o E0 de D (sanity da associação do fence, 5 min) e o E3 de D (idêntico ao P4 de A) — o desempate não custa experimentos a mais. |
| **Desvio declarado da anti-solução 4** (reverter a swapchain a só `COLOR_ATTACHMENT` no fix). | Registrar como "reverter, não re-testar" (melonDS:1484), não como experimento. É o estado correto para o present via render pass do atlas. |
| **Risco de race presente×filtro** se o submit-and-wait não for replicado fielmente. | Replicar melonDS 2228-2243 (End → QueueSubmit sob queue lock → WaitForFences UINT64_MAX) — a CPU bloqueia até o filtro completar, antes de gravar o CB de presente. |
| **`vkCmdCopyImage` exige 1:1 formato/extent.** | Manter `R8G8B8A8_UNORM` e dimensões idênticas entre `filterOutput` e `atlasOutput` (como o `copyFilteredScreen` do melonDS, 2361-2380). Blit transfer permanece como variante de diagnóstico. |
| **Se o atlas também ficar preto**, o fix sai de escopo desta hipótese. | Reabrir investigação dedicada: `use_dynamic_rendering=true` (issue upstream #225) — fora das anti-soluções, não tocar sem investigação própria. |

### 4.4 Critério de desempate (A vs D, 161 × 160)

A vantagem de 1 ponto é **ruído estatístico**, não diferença de mérito. Decidi pelo seguinte critério, nesta
ordem: **(1)** maior total ponderado (161 > 160); **(2)** C1 — o critério de peso 3 mais decisivo — perfeito
para A e só de um avaliador para D: A cobre a evidência completa com as correções de base; D herda a
explicação do escape do melonDS; **(3)** A é a única formulação em que a hipótese primária (H2/atlas) coincide
exatamente com o fix de consenso e com o experimento-chave (P2), sem camada extra de interpretação;
**(4)** a nota mais baixa de A (53, de D) é por esforço, não por correção — erro não é fator de risco.
**Resultado: A eleita.** O desempate é nominal: o plano de Fase 6 absorve os pontos fortes de D (E0 sanity;
árvore de decisão granular) sem conflito.

### 4.5 Experimentos de verificação ANTES de implementar o fix (priorizado)

| # | experimento | custo | o que decide |
|---|---|---|---|
| **P1** | Log da **posição** do READBACK-P relativa ao `applyFrame` do frame N+1 + `oldLayout`/`srcImageLayout` usado | ~30 min | Estabelece ou mata "o frame seguinte materializa o resolve"; corrige a base de todas as hipóteses. |
| **P2** | **Readback in-frame** do `processedImage` (copy engine, imediatamente após `applyFrame`, antes de qualquer transição de reescrita) | ~1 h | Separa "estado da fonte no momento da leitura" (layout) de "visibilidade postergada por ciclo". O experimento mais informativo faltante. |
| **P3** | **Probe GENERAL**: transição `CAO→GENERAL` + descriptor `GENERAL` no sampler do blit de `processedImage` (replicando melonDS 2625) | ~30 min–1 h | Testa se o layout de amostragem contribui (H2 de B); se não mudar, não refuta nada (contribuinte, não discriminador isolado). |
| **P4** | **Barreira transfer-larga pós-filtro** (`srcAccess = MEMORY_WRITE\|TRANSFER_WRITE\|COLOR_ATTACHMENT_WRITE`, `srcStage = ALL_COMMANDS`, `dst = TRANSFER_READ`, `dstStage = TRANSFER`) + `vkCmdBlitImage(processedImage → imagem dedicada)` — NUNCA a swapchain | ~1–2 h | Parâmetro do melonDS (2258/2270) nunca testado no caminho transfer do GameNative; isola "destino dedicado" + "barreira larga". NÃO é a anti-solução 6. |

**Ordem:** P1 e P2 rodam juntos (independentes, puros diagnóstico no path atual); P3 e P4 desambiguam dentro
da família H2 (P4 primeiro, pois isola a variável causal "destino"). **Parar no primeiro passo informativo.**
Se P2 ler conteúdo in-frame → o problema é o caminho do presente (colapsa em H2/atlas); se P2 ler preto
in-frame → problema de visibilidade in-frame, mas não por divergência de layout.

### 4.6 Fix proposto em alto nível (topologia de imagens do padrão melonDS)

```
offscreenImage (inalterado — fix 3.5 mantido: transição CAO→SRO, escrita fenced)
    │
    ▼  [CB do filtro — pool/CB/fence DEDICADOS]
    libra_vk_filter_chain_frame(input=offscreen, out=filterOutput)      ← filterOutput NOVA, dedicada,
    │                                                                    usage TRANSFER_SRC|TRANSFER_DST|
    │                                                                    COLOR_ATTACHMENT|SAMPLED, OPTIMAL,
    │                                                                    layout rastreado pelo app (UNDEFINED inicial)
    ▼  MESMO CB do filtro (contiguamente, como 2353-2382):
    barreira transfer-larga (P4) filterOutput CAO→TRANSFER_SRC_OPTIMAL
    barreira atlas →TRANSFER_DST_OPTIMAL
    CAMINHO PRIMÁRIO: vkCmdCopyImage(filterOutput → atlasOutput)         ← copy engine; blit transfer só como
    barreira atlas TRANSFER_DST→GENERAL                                  variante de diagnóstico
    ▼
    submitAndWait: End → QueueSubmit sob queue lock → WaitForFences(UINT64_MAX)   ← 2228-2243
    ▼
  [CB de presente (existente)]
    barreira GENERAL→GENERAL no atlas + descriptor imageLayout=GENERAL    ← 2625, 3094-3142
    render pass → swapchain (swapchain volta a só COLOR_ATTACHMENT — desvio consciente anti-4, 1484)
```

Sem mudança em render passes do compositor, em alpha, em `use_dynamic_rendering` (mantém `false`), em ABI do
librashader, em prebuilts ou em CMake.

---

## 5. Menção honrosa — segunda colocada: **D (apresentação/leitura)**

**D seria preferível** no cenário em que a filosofia for **diagnostic-first máximo com salvaguarda barata**:
(a) se a equipe quiser o E0 (sanity da associação do fence, 5 min) como primeira verificação antes de qualquer
mudança estrutural; (b) se os resultados de P1/P2 mostrarem que o copy engine lê conteúdo **in-frame** — nesse
caso o problema está estritamente no caminho do CB de presente, e a moldura de D (visibilidade in-frame
restrita ao presente + árvore E0→E1→E2→E3 com decisões a cada passo) é a mais diretamente aplicável; e
(c) se o custo de reaplicar a base revertida for o gargalo crítico (os diagnósticos de D rodam no path atual
sem reaplicação). Em todos os demais cenários o plano é idêntico ao de A, e o desempate nominal não altera a
execução.

---

## 6. Conclusão — o que será implementado na Fase 6

**A hipótese eleita é a família A (H2/atlas)** — leitura da saída do filtro com destino direto a swapchain é a
configuração problemática; mecanismo em aberto; fix = padrão melonDS. A Fase 6 implementará, **nesta ordem**:
**(1)** instrumentação P1 (log de posição/oldLayout do readback) + P2 (readback in-frame do `processedImage`)
— diagnósticos puros no path atual, parando no primeiro resultado informativo; **(2)** P3 (probe GENERAL) e
**P4** (barreira transfer-larga + blit para imagem dedicada) como desambiguação dentro da família; **(3)** o
**fix por topologia**: imagem intermediária dedicada (`atlasOutput`) + `vkCmdCopyImage` (copy engine, primário)
filtro→atlas **no mesmo CB do `applyFrame`** + submit-and-wait dedicado (fence `UINT64_MAX`) + present do
atlas em `GENERAL` num submission posterior + layout tracking manual, com a swapchain revertida a
`COLOR_ATTACHMENT` apenas (desvio consciente e documentado da anti-solução 4). O sucesso do fix **não será
lido como confirmação de mecanismo específico** — será lido como o padrão da referência resolvendo o sintoma;
a causa exata fica registrada pelos experimentos que "acenderem" antes dele.
