# Pontuação (AGENTE A)

**Rubric:** C1-C3 = peso 3; C5 = peso 2; C6 = peso 1; C4 = gate (5 = conforme, desqualifica se violar).
Total ponderado = 3*C1 + 3*C2 + 3*C3 + 2*C5 + 1*C6.

## B

- C1: 4/5 — Explica o padrão completo (offscreen OK / processed preto in-frame / READBACK-P OK no frame
  seguinte / fence 3.10 e transfer 3.11 sem efeito / melonDS lê in-frame) ancorando em `librashader.h:1705-1720`
  [V] e no blob melonDS [V]; perde um ponto porque a maior parte dos internals citados é [NV] (não verificável
  no repo) e a lacuna melonDS×3.11 é resolvida por *parâmetro não testado* (inferência, não evidência).
- C2: 4/5 — E1 (readback in-frame + log de posição, ~1-1,5h) e E2 (probe GENERAL, ~1-2h) são baratos e
  discriminam layout-assumido vs timing vs caminho de leitura; E1 é exatamente o P1/P2 de consenso do
  cross-review. Perde um ponto pela ordem mantida E1→E2→E3 (não isola destino/barreira primeiro, que é a
  variável residual mais barata).
- C3: 4/5 — Explica a assimetria offscreen/processed (app é dono do histórico de offscreen; processed tem
  histórico não-dono) e por que o melonDS escapa (nunca amostra a saída do filtro direto; blita para atlas e
  apresenta em GENERAL, [V] 2353-2383/2625); mas H2 admite explicitamente que GENERAL pode ser sintoma OU
  apenas a configuração do melonDS — o mecanismo causal fica em aberto.
- C4: 5/5 — Conforme às 12; E4 (barreira transfer-larga no caminho transfer + destino dedicado) é explicitamente
  distinguido da anti-solução 6 (que falhou no caminho *sampler* 3.8); nada mexe na swapchain, alpha,
  dynamic rendering, prebuilts, CMake ou TEST MODE.
- C5: 5/5 — Menor risco do trio: E1-E4 são instrumentação aditiva e reversível; não re-testam anti-soluções;
  E3 (atlas) replica o padrão que funciona no mesmo Adreno.
- C6: 4/5 — E1/E2/E4 são baratos (~1-2h cada); o fix completo E3 ~1 dia e adiciona imagem + fence + tracking.
  Total ponderado: **50**

## C

- C1: 4/5 — Reimplantação fiel e linha-a-linha do melonDS (1995-1998, 2228-2243, 2255-2280, 2357-2383, 2625),
  cobre o padrão completo e dá a explicação de *topologia* para offscreen OK vs processed preto; perde um ponto
  porque H1 admite que o mecanismo exato está em aberto e que o destino (swapchain no mesmo ciclo) é uma
  variável residual que *nenhuma* hipótese explica conclusivamente.
- C2: 4/5 — §7.4 "minimal diff vs 3.11" é o melhor isolamento de variável única do conjunto (destino → GENERAL →
  barreira, uma por vez, para no primeiro visível) e §7.1 (probe GENERAL, ~1 dia) precede a topologia; mas
  experimentos individuais são ligeiramente mais pesados que os de D e exigem a topologia/reimplantar a base
  revertida antes de rodar.
- C3: 5/5 — Mais forte do trio: explica a assimetria por posse integral do histórico de layout (app dono do
  offscreen e do atlas; processed sem dono) e por que o melonDS escapa (imagem apresentada é escrita por
  TRANSFER do app, em GENERAL, nunca a saída do render pass do filtro direto); a auto-contradição da H1 anterior
  foi retirada e a dicotomia rebaixada a diagnóstico.
- C4: 5/5 — Conforme; a borda anti-solução 4 (remoção do TRANSFER_DST da swapchain, revertendo a `COLOR_ATTACHMENT`
  apenas) é declarada como *desvio consciente* ("reverter, não re-testar", melonDS:1484) e aceita pelo
  cross-review §7 — não é violação, é tensão documentada.
- C5: 4/5 — Caminho primário copy engine (independente da leitura "frágil") é robusto; risco residual real e
  identificado: race presente×filtro se o submitAndWait não for replicado, e dependência 1:1 de formato/extent
  para vkCmdCopyImage.
- C6: 2/5 — Mais pesado: +2 imagens dedicadas + campo layout, reescrita do bloco de submission, barreira
  GENERAL→GENERAL no presente, reaplicar a base revertida; ~1-2 dias.
  Total ponderado: **49**

## D

- C1: 4/5 — Tabula o quadro completo de evidências (§1), incorpora o fence 3.10 (seção 2, contribuição mais
  rigorosa), o melonDS e a ambiguidade de posição do readback; perde um ponto porque a explicação do melonDS
  escapar é herdada (destino/atlas) e o próprio D declara destino como variável residual não explicada.
- C2: 5/5 — Melhor do trio: E0 (5 min, sanity), E1 (readback in-frame + log, 1-2h, prioridade máxima e
  discriminante entre H1/H2), E2 (probe GENERAL, 30min-1h) e E3 (blit → imagem dedicada, 2-4h) com árvore de
  decisão explícita e barata, todos reutilizando código existente (`beginOneTime`/`endOneTime`, `VkTable`).
- C3: 4/5 — Explica a assimetria por controle de layout (offscreen dono do app) e especificidade da sequência
  do GameNative (seção 2 enterra a race); mas a resposta "por que o melonDS escapa?" é a menos desenvolvida —
  apoia-se na variável destino (mesa §4) sem mecanismo próprio, rebaixando a força explicativa da assimetria.
- C4: 5/5 — Conforme às 12; a v1 que blitava para a swapchain (violava anti-4) foi *removida* na revisão e
  substituída por E3 com imagem dedicada; swapchain permanece `COLOR_ATTACHMENT_BIT` apenas (279); anti-6
  explicitamente distinguida (barreira transfer-larga = receita do melonDS no caminho transfer, não 3.8).
- C5: 5/5 — Baixo risco: E0-E2 são diagnósticos/sanity (nada estrutural), E3 e o atlas replicam o padrão
  melonDS que funciona no mesmo GPU; sem mudança em render passes, ABI ou compositor.
- C6: 5/5 — Menor esforço: experimentos curtos (~5 min a 4h) com código existente; o fix atlas (~1-2 dias) é
  o mesmo de B/C mas D não exige reaplicar a base revertida antes dos diagnósticos (E1-E2 rodam no path atual).
  Total ponderado: **54**

## Nota de transparência

C3 foi o critério mais difícil de julgar: nenhum mecanismo causal foi provado como único (consenso do
cross-review §6), então a nota mede *quão completa* a explicação da assimetria offscreen/processed/melonDS é —
C vence por posse integral do histórico de layout, enquanto B e D deixam o mecanismo em aberto (B) ou
rebaixam à variável residual destino (D). C4 foi o segundo mais difícil: C não viola a anti-solução 4, mas a
borda declarada ("reverter, não re-testar") só passa porque o cross-review §7 a aceitou como tensão
documentada — sem esse veredito prévio eu marcaria C4 como borderline e o desqualificaria por risco de
re-teste. C2 e C6 foram diretos (experimentos e esforços todos explícitos e coincidentes com o consenso
P1-P4). C5 para B/D exigiu juízo sobre risco "aditivo/diagnóstico" vs "estrutural"; C4 de C foi ponderado
com o desvio anti-4.
