# Fechamento — Universal Input (checklist V3 do guia)

**Data:** 2026-08-16
**Contexto:** checklist executável do FECHAMENTO do roadmap universal input —
roda DEPOIS de V0 (verificação de meio-termo), V1 (protocolo on-device v2) e V2
(retomada da fila K6→K2→K1→K7). Modelo: `spec-2026-08-16-roadmap-ux-fechamento.md`
(fase E do roadmap UX anterior). Guia: `docs/spec-2026-08-16-guia-universal-
input-fechamento.md`.

## 1. Pré-condições (só abrir o fechamento quando)

- [ ] `universal-input-meio-termo-verificacao.md` §4: gate independente VERDE e
      registrado (resultado escrito na seção).
- [ ] `protocolo-on-device-consolidado-v2.md` §4: sessões A (8/8), B (7/7) e
      C (4/4) com tabela preenchida e falhas convertidas em fix-commits.
- [ ] `universal-input-retomada-fila.md` §5: K6, K2, K1, K7 ✅ com commits,
      impl docs e gates verdes.
- [ ] Master `spec-2026-08-16-master-roadmap-universal-input.md` §7: as 8 linhas
      ✅ (Fase 0 + K3..K7) espelhadas com a retomada.
- [ ] On-device das fases NOVAS (K6/K2/K1/K7) executado e registrado no §on-device
      de cada spec (K6: round-trip de export/import no DS4; K2: hold-START→mouse
      em jogo KB/M-only; K1: flag OFF regressão zero + camada/chord afetando o
      overlay; K7: trilha raw vs calibrada ao vivo).

## 2. Checklist de fechamento (ordem)

1. [ ] **Doc de fechamento**: criar `docs/spec-2026-08-16-universal-input-
      fechamento-impl.md` (o ESPELHO deste checklist preenchido): tabela de
      commits por fase (F0/K3/K4/K5/K6/K2/K1/K7 + fix-commits de bug das
      sessões), estado on-device final (o que passou, o que ficou registrado
      como limitação do stack — ex.: rumble USB), desvios aceitos consolidados
      (os 2 da verificação de meio-termo + os que surgirem em K6–K7).
2. [ ] **Milestone**: `tools/milestone.sh universal-input-completo "Roadmap
      universal input completo — detecção universal (K3-K6), modo mouse (K2),
      gamepad virtual de toque no pipeline (K1), calibração visual (K7)"`
      (a tag é `milestone-<data>-universal-input-completo`; o script atualiza
      `docs/MILESTONES.md` e commita sozinho).
3. [ ] **Backlog**: atualizar `docs/spec-2026-08-16-backlog-ux-follow-ups.md` —
      remover #1 (GUI de Kp/Ki — absorvido pela seção de fusão da tab de
      calibração do K7, se o stretch foi entregue; senão marcar "parcial —
      sliders na tab K7") e #12 (calibração no mock visual — absorvido pelo K7);
      reavaliar os demais 10 itens à luz das fases K (ex.: #3 "gyro como
      ponteiro nos menus" ficou mais barato com o K2; #7 "turbo configurável"
      não foi tocado — segue).
4. [ ] **§7 do master**: última leitura — 8/8 ✅ + commits; adicionar no rodapé
      da seção a linha apontando para o fechamento (o checkpoint final).
5. [ ] **Pendentes v1**: fechar `docs/pendentes-e-validacao-gamepad-universal.md`
      com o status final (as linhas ⏳ de rumble/USB viram o resultado da sessão
      A; as ✅ antigas permanecem como histórico da sessão 2026-08-14).
6. [ ] **README/AGENTS**: se o fluxo mudou algo estrutural que merece registro
      (ex.: `reference/androidx` continua quebrado/removido — decisão de
      housekeeping; novos pacotes `gamepad/virtual`, `gamepad/mapping` ampliado).
      Sem mudança desnecessária — só o que o humano julgar.

## 3. Critérios de "fechado"

- Tabela §7 do master 100% ✅ com hash por linha; milestone anotada; impl doc de
  fechamento commitaado; backlog atualizado; protocolos on-device com estado
  explícito (aprovado ou limitação do stack documentada — nunca "pendente" sem
  dono e sem data).
- Nada fica "on-device pendente" aberto sem linha em ALGUMA agenda (backlog ou
  protocolo) — regra anti-acúmulo: a dívida on-device foi o que motivou o V1.

## 4. Fora do escopo do fechamento

Novas features não especificadas nos specs K (vão para backlog com spec
própria); re-abrir fases já verificadas (H/I/J etc. — têm verificação própria);
calibração automática de stick (follow-up declarado do K7); contribuir
autoconfigs ao upstream SDL_GameControllerDB (follow-up declarado do K6).
