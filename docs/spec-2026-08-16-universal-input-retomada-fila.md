# Retomada da fila — Universal Input (fases K6 → K2 → K1 → K7)

**Data:** 2026-08-16
**Contexto:** as fases 0/K3/K4/K5 estão commitadas e auditadas (verificação de
meio-termo `docs/spec-2026-08-16-universal-input-meio-termo-verificacao.md` — zero
correções pendentes). Este doc é o plano executável das QUATRO fases restantes.
Os specs originais ficam INALTERADOS: `docs/spec-2026-08-16-K6-intercambio-mapping-
sdl.md`, `-K2-modo-mouse-universal.md`, `-K1-gamepad-virtual-toque.md`,
`-K7-calibracao-stick-visual.md`. Guia: `docs/spec-2026-08-16-guia-universal-
input-fechamento.md` (V2).

## 1. Pré-condições (gate de entrada)

1. **V0 verde**: `universal-input-meio-termo-verificacao.md` §4 (gate
   independente pós-`clean`) executado e registrado.
2. **V1 estado conhecido**: sessões A/B/C do protocolo on-device v2 rodadas; as
   falhas de mapping/hub tiveram fix-commit ANTES de K6 (bugs de UI/cosméticos
   podem seguir paralelos). Se o humano decidir paralelizar (regra do guia §4),
   a ressalva é: fix tardio de mapping/hub invalida o trabalho das fases
   seguintes — risco aceito conscientemente.

## 2. Ordem e porquê (herdada do master §4, inalterada)

```
K6 → K2 → K1 → K7
```

- **K6 primeiro**: já destravada (dependia do parser completo K3 + store K5 —
  ambos ✅); fecha a trilha de DETECÇÃO inteira (detectar → corrigir → salvar →
  compartilhar) com uma fase só de encode+UI, sem tocar no hub.
- **K2 depois**: independe da trilha de mapping; pendura no flush do hub e em
  `GamepadProfile` (4 campos null-default). Médio risco, zero
  `com/winlator/inputcontrols`.
- **K1 depois de K2**: a maior (legacy winlator + XServerScreen); compõe com o
  K2 (device virtual pode usar o modo mouse) e herda o pipeline já estabilizado.
- **K7 fecha**: UI isolada + 2 campos no `StickTransform` (`antiDeadzone`,
  `maxOutput`) — nenhum hotspot compartilhado; absorve backlog #1 e #12.
- **Commit de fronteira obrigatório** entre fases vizinhas (nunca duas abertas
  no working tree). Uma fase = checkpoint atômico idempotente: atualizar a
  tabela §5 (status + hash) e commitar o impl doc junto.

## 3. Regras invariantes (resumo do master §2 — o spec de cada fase repete)

- `JAVA_HOME=/home/annapaula/android-studio/jbr` em todo gradlew.
- NUNCA a suíte de testes inteira — sempre `--tests "<Filtro>"`.
- Clean-room nos ports (moonlight/SDL/PPSSPP): semânticas em Kotlin com origem
  citada no KDoc; nunca copiar código.
- Degradação byte-identical (null/OFF/fixup-null = caminho atual exato).
- Perfil: campos novos null-default + `isDefault()`/`merged()`; store V1.
- `XServerScreen.kt`: ZERO locals novas na função principal (dex) — K2/K1 no
  máximo 1 holder `remember` em componente próprio.
- Handlers: estado lido NO MOMENTO do evento (holders vivos, lição C1).
- Sanity build em todo gate: `:app:assembleModernDebug`.
- Commits em PT-BR no formato `feat(gamepad): ... (spec 2026-08-16-KX-...)`;
  commit local apenas, nunca `git push`, nunca reescrever histórico.

## 4. Gates por fase (comandos exatos, herdados do master §7)

| Fase | Tests | Turn budget | Dica de execução |
|---|---|---|---|
| K6 | `--tests "*Sdl*" --tests "*Mapping*" --tests "*Gamepad*"` + assemble | 18–22 | começar pelo round-trip `decode(encode(x))` ANTES da UI; o GUID exige as masks de capabilities (K3) — reler `reference/sdl/gamecontrollerdb-notes.md` primeiro |
| K2 | `--tests "*MouseMode*" --tests "*Gamepad*"` + assemble | 20–25 | `MouseModeProcessor` 100% puro; o gate de 50 ms é por timestamp (sem timer — o flush da fase I já roda por evento); reusar a janela do `GamepadMoveDedupe` no scroll |
| K1 | `--tests "*Virtual*" --tests "*TouchGamepad*" --tests "*Gamepad*"` + assemble | 35–45 | flag `virtualGamepadPipeline` default OFF; a ponte emite na main thread; editor de layout do winlator NÃO se mexe; delegável a sub-agente por seção numerada do spec |
| K7 | `--tests "*Stick*" --tests "*Curve*" --tests "*Gamepad*"` + assemble | 15–20 | a aba é UI pura sobre campos que JÁ existem (F1.1/G2); os 2 campos novos do `StickTransform` são o único núcleo — ordem anti-deadzone→curve→maxOutput testada antes da view |

## 5. Tabela de status (atualizar após cada fase — checkpoint idempotente)

| Fase | Spec | Gate | Status | Commit |
|---|---|---|---|---|
| K6 | `spec-2026-08-16-K6-intercambio-mapping-sdl.md` | §4 acima | ✅ 2026-08-16 | `99609ae1` |
| K2 | `spec-2026-08-16-K2-modo-mouse-universal.md` | §4 acima | ✅ 2026-08-16 | `f64df99e` |
| K1 | `spec-2026-08-16-K1-gamepad-virtual-toque.md` | §4 acima | ✅ 2026-08-16 | `f33fc076` |
| K7 | `spec-2026-08-16-K7-calibracao-stick-visual.md` | §4 acima | ✅ 2026-08-16 | `7792c986` |

(Espelhar o resultado na §7 do master `spec-2026-08-16-master-roadmap-universal-
input.md` — as duas tabelas andam juntas.)

## 6. Hotspots entre as 4 (da matriz do master §5 — não paralelizar)

- `gamepad/mapping/*` — K6 edita (codec) e consome K3/K5; nada mais das 4 toca.
- `gamepad/GamepadHub.kt` — K2 (hook pós-remap) e K1 (registro do virtual).
- `gamepad/profiles/GamepadProfile.kt` — K2 (4 campos) e K7 (4 campos).
- `gamepad/processing/` — K2 (NOVO) e K7 (StickTransform): arquivos distintos,
  mas a fila sequencial elimina risco.
- `gamepad/remap/GamepadRemapDialog.kt` — K2 (switch/sliders) e K7 (tab nova) —
  É o motivo de K2 vir ANTES de K7.

## 7. O que NÃO é desta fila

Correções de bug das sessões on-device (protocolo v2 — entram antes de K6 se
forem de mapping/hub); milestone/backlog (fechamento); especificar features
novas não previstas nos specs K (backlog-ux-follow-ups ou spec própria).
