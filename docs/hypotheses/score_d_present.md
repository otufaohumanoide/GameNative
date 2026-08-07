# Pontuação (AGENTE D)

Escopo: AGENTE D (apresentação/swapchain/Android, caminhos de leitura Vulkan) pontua as hipóteses
REVISADAS de A, B e C (Fase 3) contra a rubrica do processo. Pesquisa apenas; não pontuo minha
própria proposta (agent_d). C4 é gate: se violasse qualquer uma das 12 anti-soluções (doc §6), a
proposta seria desqualificada. Total ponderado = 3·C1 + 3·C2 + 3·C3 + 2·C5 + 1·C6.

## A

- C1: 5/5 — Cobre o padrão completo (offscreen OK; processed preto in-frame em sampler e transfer,
  mesmo/cross submission; READBACK-P no frame seguinte OK; fence 3.10 e transfer 3.11 sem efeito;
  melonDS lê a saída do filtro in-frame) via família H2/atlas, e corrige a base de evidência (posição
  do READBACK-P não logada; 3.12 sem log), sem superalegação.
- C2: 5/5 — P1 (log de posição/oldLayout, ~30 min) e P2 (readback in-frame, ~1 h) são baratos,
  rápidos e discriminam H1' vs H2; P3/P4 isolam contribuintes na mesma família.
- C3: 4/5 — Explica as três assimetrias (offscreen: escrita própria + fenced antes da leitura;
  processed: escrita interna do librashader, leitura no mesmo CB, destino swapchain; melonDS: atlas +
  submit-and-wait + GENERAL), mas o mecanismo exato fica em aberto e o próprio A admite que a variável
  residual "destino" não está explicada (fato-âncora 4); H1' é pergunta de diagnóstico, não explicação.
- C4: 5/5 (gate OK) — Conforme às 12; único desvio (anti-4: reverter a swapchain a só
  COLOR_ATTACHMENT) é declarado como "reverter, não re-testar", aceito pelo consenso (cross-review §6/§7).
- C5: 4/5 — Fix atlas com copy-engine primário mitiga a auto-contradição (não depende da "leitura
  frágil"); P4 é diagnóstico com receita melonDS, não anti-6; risco residual moderado se o atlas
  também falhar (fix sai de escopo).
- C6: 3/5 — P1~30 min, P2~1 h, P3/P4 baratos, atlas completo ~1 dia; boa proporção "baratos primeiro",
  esforço total médio.
- Total ponderado: 53

## B

- C1: 5/5 — Cobre o padrão completo e trata a evidência com rigor: H1 ("só copy-to-host comita o
  resolve") foi corretamente rebaixada à luz do melonDS (blit image→image in-frame funciona), internals
  do librashader marcados [NV] e só fontes [V] citadas como fato.
- C2: 5/5 — E1 (readback in-frame + log de posição) é o experimento mais informativo faltante; E2
  (probe GENERAL) e E4 (barreira transfer-larga + destino dedicado) são baratos e discriminam
  estado/layout vs caminho de leitura.
- C3: 4/5 — Explica offscreen (histórico de layout do app) vs processed (estado efetivo no Adreno
  diverge do CAO assumido; app sem tracking) e o escape do melonDS (nunca amostra a saída direto;
  atlas rastreado + GENERAL); porém H2 isolado não cobre o caminho transfer (3.11) — fraqueza
  auto-reconhecida, coberta só pela família como um todo.
- C4: 5/5 (gate OK) — Conforme às 12 sem desvios; E4 declara explicitamente que NÃO é a anti-solução 6
  (3.8 alargou o caminho sampler; E4 é o caminho transfer com a receita exata da referência).
- C5: 3/5 — E3 usa blit (transfer) como cópia primária filtro→atlas, sem o fallback copy-engine que o
  consenso (cross-review §6) adotou justamente para não depender da "leitura frágil"; se essa leitura
  for real nesta config, E3 pode reintroduzir o preto (o padrão é provado pelo melonDS, logo risco
  moderado, não alto).
- C6: 3/5 — E1 1–1,5 h, E2/E4 1–2 h, E3 ~1 dia (atlas + tracking manual + fence); esforço total médio,
  comparável ao de A.
- Total ponderado: 51

## C

- C1: 5/5 — Cobre o padrão completo com o mapeamento mais detalhado e verificado do melonDS (linhas
  1484, 1995–1998, 2228–2243, 2255–2280, 2353–2383, 2625, 3094–3142); H1 é específica à config
  GameNative, mecanismo declarado em aberto, sem afirmações não verificáveis.
- C2: 5/5 — A escada 7.0→7.4 (log de posição → probe GENERAL → readback in-frame → minimal diff vs
  3.11, uma variável por vez: destino, GENERAL, barreira) é o desenho experimental mais rigoroso e
  barato por passo para discriminar a hipótese das alternativas.
- C3: 5/5 — Explica as três assimetrias com o mecanismo-candidato mais unificado: o app não é dono do
  histórico de layout do processedImage (oldLayout assumido vs rastreado), toda leitura que falha tem
  destino swapchain e falta intermediária dedicada; o melonDS escapa porque controla o caminho de
  ponta a ponta (atlas dedicado, tracking, submit-and-wait, present em GENERAL).
- C4: 5/5 (gate OK) — Conforme às 12; desvio anti-4 declarado e justificado (§3.2: reverter a swapchain
  a só COLOR_ATTACHMENT, ancorado em melonDS:1484, "não re-testa"), aceito pelo consenso; as barreiras
  largas ficam só no experimento diagnóstico 7.4-3, não no fix.
- C5: 5/5 — Copy-engine primário explicitamente para não depender da "leitura frágil" (blit só como
  variante de diagnóstico), mais submit-and-wait como mitigação do race presente×filtro; menor risco de
  reintroduzir o preto entre os três.
- C6: 2/5 — Mais passos de diagnóstico (7.1 probe GENERAL ~1 dia antes do atlas; 7.4 com 3 variáveis) +
  2 imagens dedicadas (filterOutput, atlasOutput) + reescrita do bloco de submission; maior esforço
  total dos três.
- Total ponderado: 57

## Nota de transparência

- Pontuei apenas A, B e C; não pontuei agent_d. C4 passou (gate OK) para os três após verificação das
  12 anti-soluções do doc §6 — os desvios anti-4 declarados por A e C (reverter a swapchain a só
  COLOR_ATTACHMENT) são "reverter, não re-testar", expressamente aceitos pelo consenso (cross-review
  §6/§7), não violações.
- As três convergem para a mesma família (padrão melonDS: atlas + submit-and-wait + present em GENERAL
  + tracking), e nenhum mecanismo de causa foi provado como único — por isso os escores ficam
  agrupados; a diferenciação está em C3 (profundidade da explicação da assimetria), C5 (risco do
  caminho filtro→atlas) e C6 (esforço).
- O ponto mais fraco encontrado é B-E3: cópia primária por blit (transfer) sem o fallback copy-engine
  que o consenso adotou, o que eleva levemente o risco de reintroduzir a falha conhecida (C5=3).
- C pontua mais alto por unificar a explicação (posse/tracking do layout + destino) e minimizar o risco
  do fix, ao custo do maior esforço de implementação (C6), de peso baixo.
