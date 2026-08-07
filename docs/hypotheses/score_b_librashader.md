# Pontuação (AGENTE B)

Especialista: internals do librashader v0.12.0 / filter_chain_vk (integração vulkan do filtro).
Base de verificação: `librashader-failed-attempts.md` §4-§6, `cross-review.md`, `librashader.h:1705-1720`
(sem barreira no pass final; saída permanece em CAO), blob melonDS `tool_fbaacb4a9001U7qREoNHruNWS9`.
NÃO pontuo a própria proposta (agent_b). Pesos: C1,C2,C3 = 3; C5 = 2; C6 = 1; C4 = gate (desqualifica se violar).
Total ponderado = 3*C1 + 3*C2 + 3*C3 + 2*C5 + 1*C6.

## A

- C1: 5/5 — Cobre a tabela completa de evidências com as duas correções F3 (posição do READBACK-P não logada; 3.12 sem log), refuta a forma forte de H1 por `finalLayout` + controle melonDS, e elege a família H2/atlas — a única com evidência positiva — explicando offscreen (escrita própria, fenced) vs processed (leitura no mesmo CB, destino swapchain) e o escape do melonDS (atlas + submit-and-wait + GENERAL).
- C2: 5/5 — P1/P2 (log da posição do readback + readback in-frame, ~30min-1h) são exatamente os discriminadores mais informativos do cross-review §4/§5 e separam H1'×H2; P3/P4 são probes baratos que desambiguam dentro da família H2.
- C3: 4/5 — Explica a assimetria de forma estrutural (destino/topologia; fenced-vs-mesmo-CB entre offscreen e processed) e o escape do melonDS, mas declara honestamente que nenhum mecanismo foi provado (consenso §6); H1' é pergunta falsificável, não explicação do preto in-frame.
- C4: PASSA (gate) — Declara as 12; P4 ≠ anti-6 por ser o caminho transfer com o receituário 2258/2270 nunca testado (3.8 foi sampler estreito; consenso §7); único desvio é a anti-4 no fix (voltar a swapchain a `COLOR_ATTACHMENT`), declarado como "reverter, não re-testar" e aceito pelo consenso §6/§7.
- C5: 4/5 — Caminho diagnostic-first: P1/P2 são só logging/leitura sem mudar o path de present; o atlas (~1 dia) é o único passo de risco moderado, mitigado por ser a receita exata da referência; nenhuma anti-solução é re-testada.
- C6: 4/5 — Total ~1-1,5 dia (P1 30min, P2 ~1h, P3/P4 baratos, atlas ~1 dia); nada de render passes do compositor nem de ABI.
- Total ponderado: **54**

## C

- C1: 4/5 — Reimplantação fiel e linha-a-linha do melonDS (1995-1998, 2228-2243, 2255-2280, 2357-2383, 2625), cobre o padrão completo e dá a explicação de topologia para offscreen OK vs processed preto e para o escape do melonDS; perde um ponto porque H1 admite o mecanismo exato em aberto e o destino (swapchain no mesmo ciclo) é variável residual que nenhuma hipótese explica conclusivamente.
- C2: 4/5 — §7.4 "minimal diff vs 3.11" é o melhor isolamento de variável única do conjunto (destino → GENERAL → barreira, uma por vez, para no primeiro visível) e §7.0/§7.2 são os discriminadores de consenso; mas o probe GENERAL é estimado em ~1 dia (vs ~30min-1h de A/D) e parte dos testes depende da base revertida reaplicada.
- C3: 5/5 — Mais forte do trio: explica a assimetria por posse integral do histórico de layout (app dono do offscreen e do atlas; processed sem dono) e por que o melonDS escapa (imagem apresentada é escrita por TRANSFER do app, em GENERAL, nunca a saída do render pass do filtro direto); a auto-contradição da H1 anterior foi retirada e a dicotomia rebaixada a diagnóstico.
- C4: PASSA (gate) — Conforme às 12; a borda anti-4 (remoção do TRANSFER_DST da swapchain, revertendo a `COLOR_ATTACHMENT` apenas) é declarada como desvio consciente ("reverter, não re-testar", melonDS:1484) e aceita pelo cross-review §7; a barreira transfer-larga aparece só como diagnóstico de variável (§7.4-3), não como fix — não é anti-6.
- C5: 4/5 — Caminho primário copy engine (independente da leitura "frágil" da saída do filtro) é robusto; risco residual real e identificado: race presente×filtro se o submitAndWait não for replicado, e dependência 1:1 de formato/extent para `vkCmdCopyImage`.
- C6: 2/5 — Mais pesado: +2 imagens dedicadas + campo de layout, reescrita do bloco de submission, barreira GENERAL→GENERAL no presente, remoção do TRANSFER_DST e reaplicação da base revertida; ~1-2 dias.
- Total ponderado: **49**

## D

- C1: 4/5 — Tabula o quadro completo de evidências (§1), incorpora o fence 3.10 na seção 2 (argumento mais rigoroso do conjunto: enterra a race de execução) e a ambiguidade de posição do readback corrigida por E1; perde um ponto porque a explicação do melonDS escapar é herdada (destino/atlas) e o próprio D declara destino como variável residual não explicada.
- C2: 5/5 — Melhor do trio: E0 (5 min, sanity), E1 (readback in-frame + log de posição, 1-2h, prioridade máxima, discriminante entre H1/H2), E2 (probe GENERAL, 30min-1h) e E3 (blit → imagem dedicada + barreira transfer-larga, 2-4h), com árvore de decisão explícita e barata, tudo reutilizando código existente (`beginOneTime`/`endOneTime`, `VkTable`).
- C3: 4/5 — Explica a assimetria por controle de layout (offscreen dono do app) e por especificidade da sequência do GameNative (seção 2 enterra a race; §4 articula a tabela estrutural destino×estado×estrutura de CB); mas a resposta "por que o melonDS escapa" é a menos desenvolvida — apoia-se na variável destino sem mecanismo próprio.
- C4: PASSA (gate) — Conforme às 12; a v1 que blitava para a swapchain (violava a anti-4) foi removida na revisão e substituída por E3 com imagem dedicada; swapchain permanece `COLOR_ATTACHMENT_BIT` apenas (279); anti-6 explicitamente distinguida (barreira transfer-larga = receita do melonDS no caminho transfer, não 3.8).
- C5: 5/5 — Baixo risco: E0-E2 são instrumentação de risco mínimo (nada estrutural) e E3/atlas replicam o padrão melonDS que funciona no mesmo GPU; sem mudança em render passes, ABI ou compositor; evita explicitamente re-testar 3.11 e as demais anti-soluções.
- C6: 4/5 — E1/E2 rodam no path atual sem reaplicar a base revertida (vantagem real); E3 (~2-4h, imagem dedicada nova) e o atlas (~1-2 dias) são os passos de esforço; total ~1,5-2 dias.
- Total ponderado: **53**

## Nota de transparência

- Não pontuei a proposta do agent_b; a pontuação cobre apenas A, C e D, nas versões REVISADAS da Fase 3.
- C4 tratado como gate: nenhum dos três violou qualquer das 12 anti-soluções (§6 do doc de falhas), então não houve desqualificação. As únicas tensões registradas: (i) o desvio declarado de A e C na anti-4 (remover `TRANSFER_DST` da swapchain no fix, voltando a `COLOR_ATTACHMENT` como melonDS:1484) — aceitei como "reverter, não re-testar", posição endossada pelo cross-review §6/§7; (ii) a barreira transfer-larga (2258/2270) nos três — considerada conforme à anti-6 porque atua no caminho transfer (o 3.8 falhou no caminho sampler), seguindo o consenso §7.
- A diferenciação A (54) vs D (53) vs C (49): A e D empatam na aderência (A mais completa em evidência/correções; D mais forte no rigor do fence), C fica atrás principalmente por C6 (esforço) — a topologia completa é a mais pesada e parte do diagnóstico depende de reaplicar a base revertida. C vence isoladamente em C3 (posse do histórico de layout), mas não compensa o esforço maior.
- Nenhum dos três propõe mecanismo provado como causa única (consenso §6); os três convergem para o mesmo fix (atlas + submit-and-wait + present em GENERAL + copy engine primário), e as notas refletem qualidade comparável da explicação estrutural, com a ressalva comum de que o sucesso do atlas não confirma mecanismo específico.
