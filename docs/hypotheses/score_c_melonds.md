# Pontuação (AGENTE C)

Especialista: integração librashader do melonDS (referência que funciona em Adreno 650).
Base de verificação: blob melonDS `tool_fbaacb4a9001U7qREoNHruNWS9` (linhas 1484, 1986-2036, 2228-2280,
2353-2391, 2625, 3091-3142), `cross-review.md`, `librashader-failed-attempts.md` §3-§6, `librashader.h:1705-1720`.
NÃO pontuo a própria proposta (agent_c). Pesos: C1,C2,C3 = 3; C5 = 2; C6 = 1; C4 = gate.

## A

- C1: 5/5 — Cobre a tabela completa de evidências com as duas correções F3 (posição do READBACK-P não logada; 3.12 sem log), refuta a forma forte de H1 por `finalLayout` + controle melonDS, e elege a família H2/atlas, única com evidência positiva, explicando offscreen (escrita própria, fenced) vs processed (leitura no mesmo CB, destino swapchain) e o escape do melonDS (atlas + submit-and-wait + GENERAL).
- C2: 5/5 — P1/P2 (log da posição do readback + readback in-frame, ~30min-1h) são exatamente os discriminadores mais informativos apontados pelo cross-review §4/§5; P3/P4 são probes baratos que desambiguam dentro da família H2.
- C3: 4/5 — Explica a assimetria de forma estrutural (destino/topologia; fenced-vs-mesmo-CB entre offscreen e processed) e o escape do melonDS, mas declara honestamente que nenhum mecanismo foi provado; H1' é pergunta falsificável, não explicação do preto in-frame.
- C4: PASSA (gate) — Declara as 12; P4 ≠ anti-6 por ser o caminho transfer com o receituário 2258/2270 nunca testado (consenso §7); único desvio é a anti-4 no fix (voltar a swapchain a `COLOR_ATTACHMENT`), declarado como "reverter, não re-testar" e aceito pelo consenso §6/§7.
- C5: 4/5 — Caminho diagnóstico-first: P1/P2 são só logging/leitura sem mudar o path de present; o atlas (~1 dia) é o único passo com risco moderado, mitigado por ser a receita exata da referência; nenhuma anti-solução é re-testada.
- C6: 4/5 — Total ~1-1,5 dia (P1 30min, P2 ~1h, P3/P4 baratos, atlas ~1 dia); nada de render passes do compositor nem de ABI.
- Total ponderado: **54**

## B

- C1: 4/5 — Alinha-se à evidência e marca corretamente as fontes NV (só `librashader.h:1705-1720` e o blob melonDS são verificados), mas explica menos o braço "READBACK-P no frame seguinte funciona" sob a mesma alegação de divergência de layout efetivo; o mecanismo central (GENERAL tolerado) é extrapolado do melonDS, que nunca amostra o output direto.
- C2: 5/5 — E1 (readback in-frame + log de posição, ~1-1,5h), E2 (probe GENERAL) e E4 (barreira transfer-larga + imagem dedicada) são baratos e discriminam timing vs layout vs destino, com parada no primeiro passo visível.
- C3: 3/5 — A assimetria offscreen vs processed é atribuída a "estado efetivo divergente do driver", alegação não verificável e não articulada mecanicamente; o escape do melonDS é explicado por GENERAL + atlas, mas a ligação causal (por que GENERAL toleraria o estado divergente) não é demonstrada.
- C4: PASSA (gate) — Declara as 12; E4 declarado ≠ anti-6 (caminho transfer, receita da referência, consenso §7); não toca `TRANSFER_DST` da swapchain, nem alpha, nem `use_dynamic_rendering`.
- C5: 4/5 — Ordem E1→E2→E3 com parada no primeiro visível; E1/E2 são reversíveis em uma linha; o atlas é o único passo de risco moderado, seguindo o padrão exato da referência.
- C6: 4/5 — Total ~1-1,5 dia (E1 1-1,5h, E2 1-2h, E4 1-2h, E3 ~1 dia).
- Total ponderado: **48**

## D

- C1: 5/5 — Síntese mais completa da evidência: a seção 2 enterra a race de execução via fence (o argumento mais rigoroso do conjunto), trata cada fato (fence 3.10, transfer 3.11, READBACK-P com ambiguidade de posição corrigida por E1) e enquadra a variável residual "destino" exatamente como o consenso do cross-review.
- C2: 5/5 — E0 (sanity da associação do fence, 5min) + E1 (readback in-frame + posição, prioritário) + E2 (probe GENERAL, 30min-1h) + E3 (blit → imagem dedicada + barreira transfer-larga, 2-4h): os discriminadores do consenso com granularidade extra barata.
- C3: 4/5 — A seção 2 explica por que o fence NÃO resolve (refuta "o GPU ainda não terminou") e a seção 4 articula a assimetria estrutural (destino swapchain no mesmo ciclo vs imagem dedicada do app); o mecanismo raiz, porém, permanece declarado em aberto (H1/H2 fracas decididas por E1).
- C4: PASSA (gate) — As 12 cobertas; corrigiu explicitamente a violação da v1 (E2→swapchain removida, substituída por E3→imagem dedicada); swapchain permanece só `COLOR_ATTACHMENT`; E3 usa a barreira transfer-larga declarada ≠ anti-6 (consenso §7).
- C5: 4/5 — E0/E1/E2 são instrumentação de risco mínimo; E3 (nova imagem) e o atlas (médio-alto) são os únicos passos de risco; evita explicitamente re-testar 3.11 e as demais anti-soluções.
- C6: 3/5 — Caminho mais granular, com o atlas estimado em 1-2 dias (vs ~1 dia de A/B) e E3 em 2-4h; total ~1,5-2 dias.
- Total ponderado: **53**

## Nota de transparência

- Não pontuei a proposta do agent_c; a pontuação cobre apenas A, B e D, usando as versões REVISADAS da Fase 3.
- C4 foi tratado como gate: nenhum dos três violou qualquer das 12 anti-soluções (§6), então não houve desqualificação. A única tensão registrada é o desvio declarado de A na anti-4 (remover `TRANSFER_DST` da swapchain no fix, voltando a `COLOR_ATTACHMENT` como melonDS:1484) — aceitei como "reverter, não re-testar", posição que o próprio cross-review §6/§7 endossa.
- A barreira transfer-larga (`srcAccess=MEMORY_WRITE|TRANSFER_WRITE|COLOR_ATTACHMENT_WRITE`, `srcStage=ALL_COMMANDS`, melonDS 2258/2270) proposta por A (P4), B (E4) e D (E3) é idêntica à da referência e no caminho transfer — considerei conforme à anti-6 (que proíbe re-testar o caminho sampler, 3.8), seguindo o consenso §7.
- Verifiquei no blob melonDS os pontos citados pelas três propostas: swapchain `COLOR_ATTACHMENT` apenas (1484), atlas com `initialLayout=UNDEFINED` + tracking manual (1986-2036), `submitAndWait` com fence `UINT64_MAX` (2228-2243), blit `topOutput→atlasOutput` no mesmo CB do filtro (2353-2383), present em `GENERAL` (2625, 3102-3103). As citações dos três são fiéis.
- Diferenciação A vs D (54 vs 53) ficou na estimativa de esforço do fix (A ~1 dia, D 1-2 dias); B (48) ficou atrás por ter o mecanismo central (divergência de layout efetivo / GENERAL) menos ancorado em explicação causal.
