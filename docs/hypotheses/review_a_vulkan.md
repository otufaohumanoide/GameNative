# Cross-review — AGENTE A (Vulkan/pipeline, Adreno 650) sobre as hipóteses de B, C e D

**Papel:** revisão técnica das propostas de B (`agent_b_librashader.md`), C (`agent_c_melonds.md`) e D
(`agent_d_present.md`) contra o quadro de evidências (`docs/librashader-failed-attempts.md`, seções 3-7).
**Não reviso** minha própria proposta (`agent_a_vulkan.md`). Pesquisa apenas — nenhum código.

---

## 0. Pré-verificação técnica independente (fatos que fundam esta revisão)

Verifiquei as afirmações mais carregadas contra as fontes primárias disponíveis:

- **Contrato do librashader** (`app/build/generated/librashader/include/librashader.h:1705-1720`): "A
  pipeline barrier **will not** be created for the final pass. The output image must be in
  `VK_COLOR_ATTACHMENT_OPTIMAL`, and will remain so after all shader passes. The caller must transition…"
  — **confirmado** (B/C/D citam corretamente).
- **melonDS** (blob `tool_fbaacb4a9001U7qREoNHruNWS9`, único arquivo C++, não diretório):
  - `swapchainInfo.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT` (1484) — **confirmado** (C cita).
  - `createRetroArchImage`: `usage = TRANSFER_SRC|TRANSFER_DST|COLOR_ATTACHMENT|SAMPLED`, tiling OPTIMAL,
    `initialLayout=UNDEFINED` (1986-2000) — **confirmado** (C cita 1995-1998).
  - Campo `resource.layout` por imagem, iniciado `UNDEFINED` (2036) — **confirmado**.
  - `submitAndWait`: End → `vkQueueSubmit` sob `GetQueueLock()` → `vkWaitForFences(…, UINT64_MAX)`
    (2228-2243) — **confirmado** (C/D citam).
  - `imageBarrier`: `srcAccess = MEMORY_WRITE|TRANSFER_WRITE|COLOR_ATTACHMENT_WRITE`, `srcStage =
    ALL_COMMANDS`, `oldLayout = resource.layout`, atualiza `resource.layout` após cada barreira
    (2255-2280) — **confirmado** (C cita).
  - **`recordFrame` (filtro) seguido imediatamente, no MESMO command buffer, por `vkCmdBlitImage`
    `topOutput → atlasOutput`** (2353-2382, transições `CAO→TRANSFER_SRC`/`→TRANSFER_DST` via
    `imageBarrier` rastreada), atlas → `GENERAL` (2383), depois `submitAndWait` (2387) e present do atlas
    em `GENERAL` num submission posterior (2390-2391, 2625, 3102-3103) — **confirmado, e é o achado
    central desta revisão** (ver §0.1).
- **Fonte local do librashader** (`/home/annapaula/GameNative/librashader/`): **diretório vazio** — as
  citações internas de B (`filter_chain.rs`, `framebuffer.rs`, `render_pass.rs`…) **não são verificáveis**
  no estado atual do repositório. São plausíveis e coerentes com o header, mas a ressalva fica registrada.

### 0.1 Achado que muda a avaliação (triangulação)

O melonDS — referência que **funciona no mesmo Adreno 650** — faz exatamente a leitura que as hipóteses
H1 de B, C e D marcam como "frágil": **lê a saída dos render passes do librashader via `vkCmdBlitImage`
no mesmo command buffer da escrita do filtro** (2353-2382), e isso **funciona**. Adicionalmente, o
`offscreenImage` do GameNative — também escrito por render pass — é amostrado in-frame com sucesso
(TEST MODE, doc 3.5/3.6).

Triangulação resultante:

| fonte | escrita | leitura in-frame | resultado |
|---|---|---|---|
| `offscreenImage` (GameNative) | render pass (dono do layout) | sampler blit | ✅ |
| `topOutput` (melonDS, librashader) | render pass (librashader) | `vkCmdBlitImage` transfer | ✅ |
| `processedImage` (GameNative) | render pass (librashader) | sampler / transfer blit | ❌ |

Conclusão: **não** é "leitura in-frame", **não** é "saída de render pass", **não** é "escrita pelo
librashader", **não** é "sampler vs transfer" (melonDS lê via transfer in-frame OK; offscreen é lido via
sampler OK). O veneno é **específico da configuração do caminho de leitura do `processedImage` no
GameNative** — mais plausivelmente (a) divergência do `oldLayout` assumido vs. estado rastreado pelo
driver para essa imagem específica (família H2/layout), (b) ausência de intermediária dedicada, (c)
destino swapchain, ou (d) uso/estado da imagem. Isto **estreita o espaço de hipóteses e favorece a
família "layout/estado da fonte"** sobre a família "cache de textura / resolve preguiçoso da saída".

### 0.2 Ambiguidade do "frame seguinte" (afeta todas as hipóteses)

`processedImage` é **reescrito todo frame** pelo `applyFrame`. Um READBACK-P "no frame seguinte" lê
**ou** o conteúdo recém-gerado do frame N+1 (se rodar depois do `applyFrame` de N+1) **ou** o conteúdo do
frame N (se rodar antes da transição `UNDEFINED→CA` de N+1, que descarta). A tabela de evidências (doc
§4) chama de "frame seguinte" sem registrar a posição exata do readback na linha do tempo. B, C e D
apoiam argumentos pesados nesse dado ("o atraso materializa o resolve"); **sem logar a posição do
readback relativa ao `applyFrame`, a interpretação de "delay causal" não está estabelecida** — pode ser
apenas "caminho copy-engine + CB dedicado", não "um frame de espera". Este é o logging mais barato e de
maior valor informativo de toda a investigação (afeta B-H1, C-H1, D-H2 e a minha própria).

---

## 1. Revisão de B (`agent_b_librashader.md`)

### 1.1 Consistência com a evidência
- Explica o padrão completo: `offscreenImage` OK (dono do render pass/estado, fix 3.5), `processedImage`
  preto em todo método in-frame (estado da fonte), readback no frame seguinte OK (caminho copy-engine).
  A observação de que o hello_triangle oficial **nunca re-lê a saída como textura** (present direto na
  swapchain) é correta e relevante — o padrão oficial não exercita o caminho que falha.
- Estrutura de H2 (divergência de layout efetivo; `GENERAL` como layout tolerado) é a mais alinhada com
  a evidência e com o melonDS verificado (present em `GENERAL`, 2625/3102-3103).

### 1.2 Falha técnica
- **H1 não é coerente como mecanismo independente de H2.** O mecanismo de H1 ("a leitura devolve o
  estado do tile = clear `[0,0,0,0]`) exige que a leitura execute **antes** da escrita do pass — mas a
  tentativa 3.10 (fence entre Sub-A e Sub-B) prova que a escrita terminou. Per o modelo de memória
  Vulkan, uma barreira válida `CA→SRO` com `srcAccess=COLOR_ATTACHMENT_WRITE` **garante** que a leitura
  subsequente veja o conteúdo de `storeOp=STORE`; o driver não pode "adiar um resolve" através de uma
  barreira que o solicita. Ou H1 presuppõe que a barreira é no-op (= H2), ou postula um bug
  fora-de-contrato. Como H1 está enunciada ("resolve pendente independente de layout"), é refutada; a
  versão coerente de H1 **é** H2. B deveria fundi-las.
- **E1 não discrimina H1×H2 como mapeado.** O mapeamento "preto no mesmo frame + válido no seguinte →
  reforça H1" depende da ambiguidade do §0.2: se o readback do frame seguinte lê conteúdo **fresco** de
  N+1, "válido no frame seguinte" não diz nada sobre delay. E1 só discrimina timing se a posição do
  readback for logada.
- **E2 pressupõe tracking.** "(estado rastreado) → GENERAL" presume um campo de layout que o GameNative
  **não tem**; sem tracking, o probe tem de partir do `oldLayout` assumido (re-testando a hipótese que
  falhou) ou de `UNDEFINED` (descarta). A formulação correta do probe mínimo é: transição
  `CAO→GENERAL` + descriptor `GENERAL` (replicando 2625) — se aparecer, o problema é a transição
  assumida + layout de sample, não a necessidade de tracking.
- Citações internas do librashader (filter_chain.rs/framebuffer.rs/render_pass.rs) **não verificáveis**
  (fonte local vazia, §0).

### 1.3 Testabilidade
- E1/E2/E3 são baratos na ordem certa (parar no primeiro que der visível). E2 (probe `GENERAL`) é o
  teste de maior valor de todo o conjunto de B: barato, discrimina layout-assumido vs caminho de
  leitura, e é respaldado pelo melonDS. E1 precisa do log de posição/timeline (§0.2). E3 replica o
  padrão melonDS (alto valor, médio custo).

### 1.4 Conformidade anti-solução
- Conforme nas 12. Única tensão sutil: o passo 3 usa `imageBarrier` com `srcStage=ALL_COMMANDS`
  (melonDS 2270) — isso toca o padrão da anti-solução 6 (3.8 falhou com `ALL_COMMANDS|MEMORY_WRITE`).
  Aceitável porque é a receita exata da referência + tracking + `dstStage` pontual (não é "empilhar
  barreiras largas para ver se resolve"), mas deve ficar explícito que ALL_COMMANDS aqui não é o fix.

### 1.5 Objeção forte
O melonDS **lê a saída do filtro in-frame com sucesso** (2357-2382) no mesmo GPU. A premissa comum a
B-H1 ("a saída do render pass do librashader não é legível in-frame no Adreno") é **falsa como
afirmação geral** — a leitura funciona quando o restante da receita (layout rastreado, intermediária,
present posterior em GENERAL) está correto. B não aponta qual desvio específico do GameNative é o
causal; seu E3 testa indiretamente, mas o teste não decompõe o que mudou.

### 1.6 Veredito: **apoiar com ressalvas**
- Ressalva 1: fundir H1 em H2 (o mecanismo do "clear" está tecnicamente errado).
- Ressalva 2: E1 só discrimina com log da posição do readback relativa ao `applyFrame`.
- Ressalva 3: corrigir E2 para "probe CAO→GENERAL + descriptor GENERAL" sem depender de tracking que não existe.
- Ressalva 4: revalidar citações internas do librashader quando a fonte for restaurada.

---

## 2. Revisão de C (`agent_c_melonds.md`)

### 2.1 Consistência com a evidência
- **O mapeamento estrutural é o mais preciso dos quatro docs**: todas as linhas do melonDS que cita
  foram verificadas (§0) e estão exatas. A tese central — "melonDS **nunca** apresenta a saída do filtro
  diretamente; copia para atlas no mesmo CB do filtro e apresenta o atlas depois" — está correta e é o
  fato mais importante da investigação.
- Explica offscreen OK (dono do histórico de layout), processed preto (saída de render pass do
  librashader, histórico não-dono) e readback OK (copy engine, frames completos drenaram o estado).

### 2.2 Falha técnica
- **H1 é contradita pela própria referência que a sustenta.** H1 diz que a leitura por **textura** da
  saída do filtro é "incoerente no Adreno" — mas o melonDS faz exatamente `vkCmdBlitImage` dessa saída,
  no mesmo CB, com sucesso (2357-2382). Pior: **o fix primário de C (blit `filterOutput→atlas` no mesmo
  CB) É essa mesma leitura por textura** — sob H1, o próprio fix de C deveria falhar. C reconhece isso
  no §5 ("o blit filterOutput→atlas no mesmo CB já é a leitura via textura… exatamente o ponto que a
  evidência marca como frágil"), o que torna a proposta internamente contraditória: o mecanismo de H1
  prevê o fracasso do seu próprio remédio.
- **O racional "transfer write = caminho de textura mais limpo" é enfraquecido pelo `offscreenImage`:**
  o offscreen é escrito por render pass (não por transfer) e é amostrado com sucesso (TEST MODE). A
  variável operativa não é "transfer write vs render pass" — é **quem é dono do layout/estado da
  escrita** (app vs librashader), ponto que C tem parcialmente certo em H1, mas com a ênfase errada
  (cache de textura em vez de estado de layout).
- H2 sozinho não explica o transfer blit (3.11/3.12) — C admite. Como o fix é "replicar o melonDS
  integralmente", ele é **robusto a qualquer sub-mecanismo** — essa é a força; a fraqueza é que as
  hipóteses não fazem previsão falsificável e o sucesso do fix não identificará o mecanismo.
- Cita swapchain "só COLOR_ATTACHMENT (1484)" — verificado e correto.

### 2.3 Testabilidade
- Os diagnósticos do §7 são os melhores do conjunto: (1) probe GENERAL minimalista (barato, decisivo
  para H2), (2) readback no mesmo frame (timing), (3) copy engine no lugar do blit (mecanismo). A
  ordem está certa. O fix completo é caro (~2 imagens + reescrita do submission) mas é o de maior
  confiança, por ser a receita verificada da referência.

### 2.4 Conformidade anti-solução
- Conforme nas 12. Duas tensões menores:
  - **Anti-solução 4:** C **remove** `TRANSFER_DST` da swapchain. O doc diz "não adicionar/remover
    TRANSFER_DST — não resolve (3.11)". A remoção em C é parte da topologia melonDS (e correta), mas
    deve ficar explícito que a remoção em si **não é o fix** — C quase faz isso, mas a tabela do §3
    ("a swapchain perde o TRANSFER_DST") pode ser lida como remédio. Ajuste de narrativa.
  - Anti-solução 6: usa o `imageBarrier` do melonDS com `srcStage=ALL_COMMANDS` (mesmo padrão de 3.8).
    Igual à ressalva de B: aceitável como receita da referência, não como fix.

### 2.5 Objeção forte
H1 é refutada pela própria evidência que C traz: o melonDS faz a leitura "frágil" com sucesso. A
consequência prática é que **o fix primário de C deve ser o de menor risco garantido sob qualquer
mecanismo: a variante copy-engine (§7.3), `vkCmdCopyImage` do `filterOutput → atlas`** — que evita a
leitura de textura da saída do librashader — e o blit por transfer deve ser tratado como teste
intermediário, não como fix primário. O probe GENERAL (§7.1) deve rodar ANTES da topologia completa
(barato, pode tornar o atlas desnecessário — o próprio C reconhece).

### 2.6 Veredito: **apoiar com ressalvas (a solução, não o mecanismo)**
- Ressalva 1: reescrever H1 para "divergência de estado/layout específico do `processedImage` no
  GameNative", não "leitura in-frame da saída é frágil" (refutada pelo melonDS).
- Ressalva 2: promover a variante copy-engine (§7.3) a fix primário; o blit transfer `→atlas` como passo
  de teste/diagnóstico.
- Ressalva 3: rodar o probe GENERAL (§7.1) antes do atlas — economia real se H2 for suficiente.
- Ressalva 4: clarificar que a remoção de `TRANSFER_DST` da swapchain não é parte do mecanismo do fix.
- O **padrão completo** (atlas + submit-and-wait + present em GENERAL, com tracking de layout) é a
  receita mais confiável e deve ser o alvo final.

---

## 3. Revisão de D (`agent_d_present.md`)

### 3.1 Consistência com a evidência
- O §2 de D (refutação da "race de execução" via 3.10) é o argumento mais rigoroso do conjunto: fence
  esperado entre escrita e leitura mata "o GPU ainda não terminou" como explicação para o caminho de
  present. Correto e necessário.
- H1 de D (transição no-op por `oldLayout` incompatível) é a formulação mais limpa da família de layout
  e é consistente com o padrão completo, incluindo o porquê do fence não ajudar (a transição continua
  errada mesmo com ordenação).
- H2 de D (resolve postergado) é observacionalmente consistente, mas com as mesmas reservas de B-H1.

### 3.2 Falha técnica
- **E0 é quase redundante com 3.10.** `vkQueueWaitIdle` após esperar o `filterFence` de Sub-A não
  acrescenta ordenação (fence já garante a execução completa). O único valor marginal: confirmar que o
  fence de 3.10 estava de fato associado à submission do `applyFrame` (fence errada/desatualizada
  explicaria o resultado de 3.10). Como discriminador é ~zero-informação — o próprio D diz que o
  esperado é continuar preto. Barato e inofensivo, mas deve ser rotulado como "verificação de sanidade",
  não "experimento discriminante".
- **Mecanismo de H2 é tecnicamente frágil.** "Leitura imagem→imagem pode ser satisfeita com dados em
  GMEM/estado de tile" é implausível: um sampler/blit não lê "os tiles de outro pass"; lê a memória da
  imagem após o resolve (ou conteúdo velho). A formulação coerente é "o driver não comita o resolve para
  esses caminhos de leitura na configuração do GameNative" — e, dado o melonDS (leitura imagem→imagem
  in-frame que **funciona**), essa afirmação não vale como regra geral, só como propriedade da
  configuração específica.
- **E1 mapeado de forma imprecisa.** "Lê conteúdo no mesmo frame → H1" confunde dois sub-mecanismos de
  H1 (transição no-op vs cache do sampler). E1 discrimina **timing** (visibilidade in-frame via copy
  engine), não o mecanismo dentro do caminho do CB de present. O que resolve isso são E2/GENERAL.
- Apoia-se no "frame seguinte" (§0.2) sem o log de posição — mesma ressalva de B/C.

### 3.3 Testabilidade
- E1 (readback in-frame + log do `oldLayout`/`srcImageLayout`) é o experimento-chave barato e deve ser
  o primeiro de todo o programa. E2 (CB one-time dedicado, blit `processedImage→swapchain` com espera
  própria, present depois) é útil como re-verificação **com log** do que 3.12 relatou sem log (relato de
  usuário, doc §3.12 — evidência fraca): a estrutura de E2 testa o contexto de submission do present de
  forma isolada. E0: manter como sanity check de 5 min, não como discriminante.

### 3.4 Conformidade anti-solução
- Conforme nas 12. Notável: E0 usa `vkQueueWaitIdle` (sync host), não alarga barreiras (anti-6 OK); E2
  usa o layout `TRANSFER_DST_OPTIMAL` **no CB** sem alterar `imageUsage` da swapchain (anti-4 OK) —
  distinção tecnicamente correta e bem articulada.

### 3.5 Objeção forte
- H2 de D (leituras imagem→imagem nunca comitam o resolve) é refutada pelo melonDS: blit imagem→imagem
  da saída do filtro, in-frame, funciona (2357-2382). E E0 é redundante com 3.10. O que resta de D é
  forte — H1 + o §2 de refutação de race + E1/E2 — mas os dois primeiros itens precisam de correção.

### 3.6 Veredito: **apoiar com ressalvas**
- Ressalva 1: reformular H2 sem a história "imagem satisfeita por GMEM"; usar "o resolve só comita via
  caminhos que materializam (buffer host / intermediária) na configuração do GameNative".
- Ressalva 2: rebaixar E0 a sanity check (5 min) e deixar claro que 3.10 já discriminou execução.
- Ressalva 3: E1 deve logar a posição relativa ao `applyFrame` (§0.2).
- Ressalva 4: o mapeamento de E1 não deve "confirmar H1" sem o probe GENERAL/E2 para separar transição
  no-op de cache de sampler.

---

## 4. Síntese da revisão

- **Convergência (fato positivo):** os quatro docs apontam para a mesma família — estado/layout do
  `processedImage` pós-frame + caminho de leitura/arquitetura — e para a mesma receita final (melonDS).
  Convergência em hipóteses independentes é evidência de direção correta.
- **Correção necessária de evidência (afeta todos):** logar a posição do READBACK-P na linha do tempo
  (antes/depois do `applyFrame` do frame N+1) e o `oldLayout`/`srcImageLayout` usado. Sem isso, o "frame
  seguinte = resolve materializado" não está estabelecido. Custo: minutos. Prioridade máxima.
- **Refutação técnica (melhorada nesta revisão):** "a saída do render pass do librashader não é legível
  in-frame no Adreno" é **falsa** — o melonDS a lê in-frame via `vkCmdBlitImage` com sucesso. O problema
  é específico do GameNative; a explicação sobrevivente mais forte é a divergência de layout/estado
  rastreado do `processedImage` (família H2) e/ou a ausência de intermediária + present em `GENERAL`.
- **Ordem recomendada de experimentos (consenso das 4 + esta revisão):**
  1. Log da posição/oldLayout do readback (§0.2) — minutos, corrige a base de evidência.
  2. Probe `GENERAL` no `processedImage` (B-E2 corrigido / C-§7.1 / D-H1) — barato, decisivo para layout.
  3. Readback in-frame (B-E1 / C-§7.2 / D-E1) — discrimina timing/visibilidade.
  4. Receita melonDS completa (atlas + submit-and-wait + present em GENERAL + tracking), com
     `vkCmdCopyImage` (copy engine) como caminho primário do filtro→atlas e blit transfer como passo de
     teste (B-E3 / C-fix + fallback §7.3).
- **Anti-soluções:** B, C e D estão conformes nas 12; registrar as tensões menores (anti-4 na remoção do
  `TRANSFER_DST` por C; anti-6 no `ALL_COMMANDS` do `imageBarrier` replicado de B/C), todas aceitáveis
  quando apresentadas como "receita da referência" e não como "o fix".
