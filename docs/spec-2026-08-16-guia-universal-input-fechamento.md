# Guia — Fechamento do Universal Input (ponto único de entrada do fluxo residual)

**Data:** 2026-08-16
**Por que existe:** o humano executou F0/K3/K4/K5 do roadmap universal input e se
perdeu no estado. A auditoria independente (meio-termo) mostrou que NÃO há
correções pendentes — o que falta é VERIFICAR, VALIDAR no device e FECHAR as 4
fases restantes. Este guia é o mapa; os 4 docs satélites são os planos.

## 1. Mapa dos documentos

| Doc | Papel | Quem executa |
|---|---|---|
| `spec-2026-08-16-master-roadmap-universal-input.md` | o roadmap ORIGINAL (fases K1–K7 + Fase 0); §7 é a fonte da verdade de status | (referência) |
| `spec-2026-08-16-universal-input-meio-termo-verificacao.md` | **V0** — auditoria do que está commitado + gate independente pós-`clean` | agente (gate) |
| `spec-2026-08-16-protocolo-on-device-consolidado-v2.md` | **V1** — 3 sessões on-device (A/B/C) fundindo TODOS os "pendente" | humano |
| `spec-2026-08-16-universal-input-retomada-fila.md` | **V2** — plano executável das fases restantes K6→K2→K1→K7 | agente |
| `spec-2026-08-16-universal-input-fechamento.md` | **V3** — checklist de fechamento (doc final + milestone + backlog) | agente + humano |

## 2. O fluxo (uma imagem)

```
V0 gate verde ──► V1 sessões A/B/C (humano, BT)
        │                │ bug de mapping/hub? ──► fix-commit ──┐
        │                └────────────── (bug de UI? paralelo)  │
        ▼                                                       ▼
   V2 retomada: K6 → K2 → K1 → K7  ◄─────── entra ANTES de K6 se mapping/hub
        │
        ▼
   V3 fechamento: doc final + milestone + backlog + §7 100%
```

## 3. Regra de decisão V1 × V2 (a única decisão do humano)

- **Recomendado (sequencial):** V0 → V1 completo → V2. Fundação verificada antes
  de erguer K6/K2/K1 — um bug de cadeia de mapping achado na sessão A corrigido
  ANTES de K6 custa 1 fix; achado DEPOIS de K6/K2/K1 pode invalidar commits.
- **Alternativa (paralela, aceita):** V0 → V2 (agente) ENQUANTO o humano roda V1.
  Risco aceito conscientemente: fix tardio de mapping/hub invalida as fases
  novas. Só escolher se o humano confia na base (a sessão USB de 2026-08-14 já
  aprovou o núcleo U1–U7).

## 4. Goal pronto para o Prime Agent (V2 — uma fase por invocação, retomável)

```
prime-agent --autonomous \
  --autonomous-gate "git -C /home/annapaula/GameNative log --oneline -1 | grep -q 'spec 2026-08-16-K6'" \
  --autonomous-max-turns 25 \
  "Siga docs/spec-2026-08-16-universal-input-retomada-fila.md do repo /home/annapaula/GameNative: execute a próxima fase INCOMPLETA da tabela §5 (K6 primeiro). Uma fase por invocação; o gate da fase DEVE passar antes do commit; atualize a §5 e a §7 do master."
```

(Troque `K6`/turns por `K2`/25, `K1`/45, `K7`/20 na sequência.)

V0 (gate) e V3 (fechamento) também são executáveis por agente — apontar o goal
para o doc correspondente. V1 é HUMANO: o guia entrega o protocolo, não o executa.

## 5. Anti-armadilhas deste fluxo

1. **Nunca mate gradle com `timeout`** (incidente do jar corrompido na
   verificação H/I/J) — background sem limite.
2. **Nunca a suíte de testes inteira** (30 min) — só os filtros dos gates.
3. **JAVA_HOME obrigatório**: `JAVA_HOME=/home/annapaula/android-studio/jbr`.
4. **XServerScreen dex**: K1/K2 no máximo 1 holder `remember` em componente
   próprio — se o agente pedir mais, o design está errado.
5. **Duas tabelas de status andam juntas**: retomada §5 e master §7 — atualizar
   as duas no checkpoint de cada fase.
6. **Dívida on-device não pode terminar "pendente" sem dono**: toda linha aberta
   vai para o protocolo v2 ou para o backlog (regra do fechamento §3).
