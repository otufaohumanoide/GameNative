# Master Roadmap Input Avançado 2026-H2 — guia de orquestração para agente autônomo (goal)

**Data:** 2026-08-16
**Executor:** agente autônomo (Prime Agent, modo goal/autonomous). Este arquivo é o
ÚNICO ponto de entrada: o humano define o goal apontando para cá; o agente segue
sozinho até o fim, fase a fase, e só chama `goal.complete()` quando a tabela §6
estiver 100% ✅ (ou BLOQUEADA com motivo documentado).

**Fases (specs autocontidos em `docs/spec-2026-08-16-*.md`):**
H modificadores por binding (DuckStation) → I trigger engine LONG_PRESS/SEQUENCE
(key-mapper) → J linguagem de expressões (Dolphin, J1 obrigatório + J2 stretch).

## 1. Loop do agente (a cada iteração)

1. Leia `AGENTS.md` (regras do repo — JAVA_HOME, testes filtrados, gotchas) e a
   tabela de status (§6). Implemente a próxima fase INCOMPLETA, NA ORDEM da §4.
   Nunca pule fase; nunca paralelize fases que dividem hotspot (matriz §5).
2. Abra o spec da fase. Cada spec é AUTOCONTIDO (origem + onde ler a fonte
   reference com linhas, estado atual com file:line do fork, design, arquivos,
   testes, gate, não-metas) e pode ser delegado a sub-agente:
   `await rlm("Implemente docs/spec-2026-08-16-X-....md do repo /home/annapaula/GameNative. Siga o spec ao pé da letra; leia AGENTS.md primeiro; o gate no fim DEVE passar antes do commit.", name="fase-x")`
   — sub-agente é RECOMENDADO para as fases grandes (I, J): o REPL do Prime Agent
   ganha isolamento de contexto e o plano do spec substitui hand-holding.
3. **Clean-room obrigatório**: os references são GPL (Dolphin GPL-2, DuckStation e
   key-mapper GPL-3). Portar SEMÂNTICAS/gramáticas documentadas nos specs,
   reimplementadas em Kotlin; citar a origem no KDoc (padrão do repo:
   `MappingParser.kt`, `GyroProcessor.kt`). NUNCA copiar código dos references.
4. Gate: o comando de verificação da fase DEVE passar antes de commitar. Falhou
   2× seguidas → PARE, marque a fase `BLOQUEADA` no §6 com o erro exato, e siga
   apenas se o bloqueio for documental (on-device é do humano — registre
   "on-device pendente" no impl doc, NÃO bloqueie a fase por isso).
5. Fechou a fase: commit no formato do repo (`feat(gamepad): ... (spec
   2026-08-16-X-...)`), escreva o impl doc (`<spec>-impl.md`, evidências file:line),
   atualize a §6 ABAIXO (checkbox + hash do commit + data) e commite o checkpoint.
   Uma fase = checkpoint atômico e idempotente: se o worker cair, o próximo loop
   relê a §6 e retoma exatamente de onde parou (commits já feitos NÃO são refeitos).
6. `/refine` após cada fase (se disponível no harness): promova a memória os
   gotchas recorrentes do repo — JAVA_HOME obrigatório; NUNCA a suíte de testes
   inteira (estoura 30 min — sempre `--tests "*Filtro*"`); `XServerScreen.kt` no
   limite dex (zero locals novas na função principal); `build/` pode ter classes
   stale (confie no source); store preserva chaves desconhecidas (V1).

## 2. Regras globais (invariantes de TODA fase)

- Todo gradlew com `JAVA_HOME=/home/annapaula/android-studio/jbr`.
- NUNCA rode `:app:testModernDebugUnitTest` sem filtros `--tests` (suíte inteira
  estoura 30 min). Nunca rode `--offline` se o build reclamar de rede.
- Docs e commits em PT-BR; strings EN (`values/`) + pt-rBR (`values-pt-rBR/`).
- Lógica pura em `object`/data classes SEM `android.*` (pacotes `gamepad/`),
  testável em JVM — os specs apontam os pacotes.
- Degradação byte-identical: campo null / sem opt-in = caminho atual exato
  (todos os specs foram desenhados assim — preserve).
- Perfil: `GamepadProfileStore` preserva chaves desconhecidas no save (V1 — nunca
  regravar mapa perdendo extras); campos novos null-default; atualizar
  `isDefault()`/`merged()` quando criar campos NOVOS no perfil (H e J não criam;
  I cria).
- `XServerScreen.kt`: ZERO locals novas na função principal (limite de registros
  dex). Nenhuma fase destas precisa tocá-lo — se achar que precisa, o design está
  errado: pare e registre.
- Sanity build em TODO gate: `:app:assembleModernDebug`.
- Catálogo: `tools/profiles/sync_profile_repo.py` determinístico — rodar 2× e
  exigir `git diff --exit-code` (os specs lembram no gate).
- Não rode `git push`; commit local apenas. Nunca reescreva histórico.
- MIUI bloqueia `adb input` — teste de input no device é pelo harness
  `adb shell setprop debug.gamenative.input "key:110"` (protocolo em
  `ui/component/DebugGamepadInput.kt`). On-device é HUMANO; registre pendência.

## 3. Por que ESTA ordem (H → I → J)

- **H primeiro**: menor e autocontida; estabelece o payload de modificadores NO
  TOKEN do binding (`:m=...`) que J reutiliza conceitualmente (expressões são
  outra forma de binding no MESMO campo). Valor imediato (hair trigger, eixo
  invertido por binding).
- **I depois de H**: não depende de H, mas as duas evoluem o MESMO spec de perfil
  (`LayerTriggerSpec` ganha campos; H não mexe nele) e o MESMO dialog — commit de
  fronteira obrigatório entre elas.
- **J por último**: maior (parser + evaluator + editor UI), constrói sobre o
  modelo de binding enriquecido por H (codec estendido) e compõe com I
  (expressões avaliadas no MESMO flush de eventos da fase I — mesmo ponto do hub).
  Fazer J antes obrigaria rework em H (formato do token) e I (flush). J1
  obrigatório; J2 (chords) é stretch — abortar J2 por budget NÃO falha a fase.

## 4. Ordem de implementação

```
H → I → J1 → (J2 stretch)
```

## 5. Matriz de hotspots (quem toca o quê)

| Arquivo/área | H | I | J |
|---|---|---|---|
| `gamepad/mapping/*` (RawBinding, EventTranslator) | ● | | ○ |
| `gamepad/layers/*` (LayerTriggerSpec, LayerResolver) | | ● | ○ |
| `gamepad/layers/TriggerEngine.kt` (NOVO) | | ● | |
| `gamepad/expressions/*` (NOVO) | | | ● |
| `gamepad/remap/GamepadBindingCodec.kt` | ● | | ● (`expr:`) |
| `gamepad/remap/GamepadRemapDialog.kt` | ● | ● | ● |
| `gamepad/GamepadHub.kt` (emitLogical, flush de eventos) | ○ | ● | ● |
| `gamepad/profiles/GamepadProfile(+Store).kt` | (sem campos) | ● (campos) | (sem campos) |
| `gamepad/profiles/ProfileCatalog.kt` + `tools/profiles/sync_profile_repo.py` | ● | ● | ● |
| `res/values*/strings.xml` | ● | ● | ● |

(● = edita; ○ = consome/lê.) `GamepadRemapDialog.kt` e o codec são hotspots de TODAS
— por isso a fila é ESTRITAMENTE sequencial, com commit de fronteira entre fases
(nunca duas fases abertas no working tree ao mesmo tempo).

## 6. Status (O AGENTE ATUALIZA após cada fase — checkpoint idempotente)

| Fase | Spec | Gate | Status | Commit |
|---|---|---|---|---|
| H | `spec-2026-08-16-H-binding-modifiers-duckstation.md` | tests `*Binding* *Gamepad*` + assemble + sync determinístico | ⬜ PENDENTE | — |
| I | `spec-2026-08-16-I-trigger-engine-keymapper.md` | tests `*Trigger* *Layer* *Gamepad*` + assemble + sync determinístico | ⬜ PENDENTE | — |
| J1 | `spec-2026-08-16-J-expressions-dolphin.md` (§2) | tests `*Expr* *Gamepad*` + assemble + sync determinístico | ⬜ PENDENTE | — |
| J2 | `spec-2026-08-16-J-expressions-dolphin.md` (§3, stretch) | tests `*Expr* *Gamepad*` + assemble | ⬜ PENDENTE (opcional) | — |

Comandos de gate (prefixar sempre):
```
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:testModernDebugUnitTest --tests "*<FILTRO>*" --offline
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:assembleModernDebug --offline
python3 tools/profiles/sync_profile_repo.py && python3 tools/profiles/sync_profile_repo.py && git diff --exit-code -- tools/profiles/
```

## 7. Como rodar (sugestão para o humano — Prime Agent)

**Opção A — UM goal para tudo (recomendado):**
```
goal: "Execute docs/spec-2026-08-16-master-roadmap-input-avancado.md do repo /home/annapaula/GameNative até o fim: siga o loop §1, implemente as fases NA ORDEM §4 (H→I→J1, J2 stretch), gate de cada fase §6 DEVE passar antes do commit, atualize a tabela §6 após cada fase. Chame goal.complete() somente quando TODAS as fases obrigatórias estiverem ✅ com commit (ou BLOQUEADA com erro documentado no §6)."
--autonomous-gate "git -C /home/annapaula/GameNative log --oneline -30 | grep -c 'spec 2026-08-16-[HIJ]' | grep -v '^0$'"
--autonomous-max-turns 90   # H≈20 + I≈30 + J1≈35 + margem de gates
```

**Opção B — um goal por fase (mais controle, retomável):** repita por fase, o loop
§1 é idempotente (a §6 diz de onde retomar):
```
prime-agent --autonomous \
  --autonomous-gate "git -C /home/annapaula/GameNative log --oneline -1 | grep -q 'spec 2026-08-16-H'" \
  --autonomous-max-turns 25 \
  "Siga docs/spec-2026-08-16-master-roadmap-input-avancado.md do repo /home/annapaula/GameNative: execute a fase H (a próxima INCOMPLETA da tabela §6). Uma fase por invocação."
```
(Troque `H`/turns por `I`/35, `J1`/40 na sequência.)

## 8. Não-metas globais (todas as fases)

uinput//dev/input raw, remap de apps externos, HD haptics/lightbar/adaptive
triggers (API pública não expõe), tocar em `LibraryList.kt` (código morto),
substituir o sistema de camadas/triggers existentes (as fases COMPÕEM com
LayerResolver/TriggerEngine, não substituem), variáveis `$`/comentários/trigono-
métricas nas expressões (J3 se um dia for pedido), bindings de saída (rumble/LED).
