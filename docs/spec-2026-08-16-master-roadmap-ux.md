# Master Roadmap UX 2026-H2 — guia de orquestração para agente autônomo

**Data:** 2026-08-16
**Executor:** agente autônomo (Prime Agent). Este arquivo é o ÚNICO ponto de entrada:
o humano só aponta para cá; o agente segue sozinho até o fim, fase a fase.
**Fases (specs autocontidos em `docs/spec-2026-08-16-*.md`):**
F0 housekeeping → A rumble → B remap visual → C device card → F radial v2 →
D touchpad swipes → E catálogo de perfis.

## 1. Loop do agente (a cada iteração)

1. Leia `AGENTS.md` (regras do repo) e a tabela de status (§6). Implemente a
   próxima fase INCOMPLETA, na ordem da §4. Nunca pule fase; nunca paralelize
   fases que dividem hotspot (matriz §5).
2. Abra o spec da fase. Cada spec é AUTOCONTIDO (estado atual, design, arquivos,
   testes, gate, não-metas) e pode ser delegado: `await rlm("Implemente
   docs/spec-2026-08-16-X-....md do repo /home/annapaula/GameNative. Siga o spec
   ao pé da letra; o gate no fim DEVE passar antes do commit.", name="fase-x")`.
3. Gate: o comando de verificação da fase DEVE passar antes de commitar. Falhou
   2× seguidas → PARE, marque a fase como `BLOQUEADA` no §6 com o erro, e siga
   apenas se o bloqueio for documental (on-device é do humano — registre
   "on-device pendente" no spec, NÃO bloqueie a fase por isso).
4. Fechou: commit no formato do repo (`feat(gamepad): ... (spec
   2026-08-16-X-...)`), escreva o impl doc (`<spec>-impl.md`, evidências
   file:line), atualize o §6 deste arquivo (checkbox + hash do commit + data) e
   commite o checkpoint. Uma fase = checkpoint atômico e idempotente: se o
   worker cair, o próximo loop relê o §6 e retoma exatamente de onde parou.
5. `/refine` após cada fase: promova a memória os gotchas recorrentes — ex.:
   JAVA_HOME obrigatório; NUNCA a suíte de testes inteira (estoura 30 min);
   MIUI bloqueia `adb input` (usar harness `setprop debug.gamenative.input`);
   XServerScreen no limite dex (sem locals novas na função principal);
   `build/` pode ter classes stale (confie no source, limpe se inconsistente).

## 2. Regras globais (invariantes de TODA fase)

- Todo gradlew com `JAVA_HOME=/home/annapaula/android-studio/jbr`.
- NUNCA rode `:app:testModernDebugUnitTest` sem filtros `--tests`.
- Docs e commits em PT-BR; strings EN (`values/`) + pt-rBR (`values-pt-rBR/`).
- Lógica pura em `object`/data classes SEM `android.*` (pacotes
  `gamepad/processing|layers|radial|mapping`), testável em JVM.
- Degradação byte-identical: feature OFF / campo null = caminho atual exato.
- Perfis: campos novos null-default; atualizar `isDefault()` e `merged()`;
  store preserva chaves desconhecidas (V1 — nunca regravar perdendo extras).
- `XServerScreen.kt`: ZERO locals novas na função principal (limite de registros
  dex). Componente novo = arquivo próprio + no máximo 1 holder `remember`.
- Handlers de evento: estado lido NO MOMENTO do evento (holders vivos, lição C1);
  listeners de overlay in-game no bus `PluviaApp.events`, nunca view-focus.
- Sanity build em TODO gate: `:app:assembleModernDebug`.
- Não rode `git push`; commit local apenas. Nunca reescreva histórico.

## 3. Fase 0 — Housekeeping (calibração, docs-only)

Focus-feedback-v2 foi implementado e commitado (`73472c32`) mas ficou sem impl
doc nem MILESTONES. Escreva `docs/spec-2026-08-15-focus-feedback-v2-impl.md`
(evidências file:line: defaults 3dp/1200ms, base ring sólida + sweep 0.75,
overlay 0.08, Selected=tint 0.10+hairline, QuickMenuEmptyStateRow, collector no
escopo do chamador) e rode `tools/milestone.sh` (tag anotada). Gate: apenas
assemble (prova que o ambiente builda antes de qualquer código real).

## 4. Ordem de implementação e porquê (interferência)

```
F0 → A → B → C → F → D → E
```

- **A primeiro**: pequeno, valor imediato ao usuário, e limpa o hotspot
  `GamepadHaptics`/`SettingsGroupGamepad` que C também toca.
- **B antes de C**: C REUSA o desenho do controle criado em B (viewer = mesmo
  componente com flash ao vivo). Fazer C antes obrigaria duplicar o desenho.
- **C depois de A**: o card de diagnóstico REUSA o botão "testar vibração" de A.
- **F antes de D**: D (swipes → macros) referencia o modelo FINAL de
  radial/macros de F (iconKey/children/schemaVersion 2); D antes = rework.
- **E por último**: só cria arquivos novos + congela o formato do perfil DEPOIS
  das duas fases que evoluem o schema (F e D) — o catálogo serializa formato
  estável.
- Entre fases vizinhas que dividem hotspot há sempre commit de fronteira: nunca
  duas fases abertas no working tree ao mesmo tempo.

## 5. Matriz de hotspots (quem toca o quê)

| Arquivo/área | A | B | C | F | D | E |
|---|---|---|---|---|---|---|
| `ui/component/GamepadHaptics.kt` | ● | | ○ | | | |
| `ui/screen/settings/SettingsGroupGamepad.kt` | ● | | ● | | | |
| `gamepad/remap/GamepadRemapDialog.kt` | | ● | | | | ● |
| `gamepad/profiles/GamepadProfile(+Store).kt` | ○ | ○ | | ● | ● | ○ |
| `gamepad/processing/` (novos) | ●curve | ●layout | | ●turbo | ●swipe | |
| `ui/component/radial/*` + `gamepad/radial/*` | | | | ● | ○ | |
| `gamepad/processing/TouchpadProcessor.kt` | | | | | ● | |
| `gamepad/layers/Layer*(Spec/Resolver)` | | | | ● | | |
| `ui/screen/library/*Card.kt` (badge) | | | | | | ● |
| `tools/` + `assets/` | | | | | | ● |

(● = edita; ○ = consome/lê)

## 6. Status (O AGENTE ATUALIZA após cada fase — checkpoint)

| Fase | Spec | Gate | Status | Commit |
|---|---|---|---|---|
| F0 | (este arquivo §3) | assemble | INCOMPLETO | — |
| A | `spec-2026-08-16-A-rumble-fallback-usage-media.md` | tests `*RumblePhoneCurve* *GamepadProfileStore*` + assemble | INCOMPLETO | — |
| B | `spec-2026-08-16-B-remap-visual-ppsspp.md` | tests `*ControllerVisual* *Gamepad*` + assemble | INCOMPLETO | — |
| C | `spec-2026-08-16-C-device-card-input-viewer.md` | tests `*Gamepad*` + assemble | INCOMPLETO | — |
| F | `spec-2026-08-16-F-radial-v2-modeshift-turbo.md` | tests `*Radial* *LayerResolver* *Turbo*` + assemble | INCOMPLETO | — |
| D | `spec-2026-08-16-D-touchpad-swipes-macros.md` | tests `*Touchpad*` + assemble | INCOMPLETO | — |
| E | `spec-2026-08-16-E-profile-catalog-comunitario.md` | determinismo do sync (2× → diff vazio) + tests `*ProfileCatalog*` + assemble | INCOMPLETO | — |

Comandos de gate completos (prefixar sempre):
```
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:assembleModernDebug
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:testModernDebugUnitTest --tests "*<FILTRO>*"
```

## 7. Como rodar (sugestão para o humano)

```bash
prime-agent --autonomous \
  --autonomous-gate "git -C /home/annapaula/GameNative log --oneline -1 | grep -q 'spec 2026-08-16'" \
  --autonomous-max-turns 12 \
  "Siga docs/spec-2026-08-16-master-roadmap-ux.md do repo /home/annapaula/GameNative: execute a próxima fase INCOMPLETA da tabela §6 (loop do §1, regras do §2). Uma fase por invocação."
```

Turn budgets sugeridos por fase (uma invocação por fase, gate próprio):
F0=8, A=15, B=30, C=20, F=30, D=20, E=35. Para fases grandes (B, F, E) o agente
pode delegar sub-fases do spec a sub-agentes `rlm(...)` — cada seção numerada do
spec é uma unidade delegável com verificação própria.

## 8. Não-metas globais (todas as fases)

Migração C++/Rust (arquivada — baseline F0 p95 3–5 ms), uinput//dev/input raw,
HD haptics/adaptive triggers/lightbar (API pública não expõe), remap de apps
externos, redesenho da Library dos devs originais, tocar em `LibraryList.kt`
(código morto).
