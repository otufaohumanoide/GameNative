# Master Roadmap Universal Input 2026-H2 — guia de orquestração para agente autônomo

**Data:** 2026-08-16
**Executor:** agente autônomo (Prime Agent, modo goal/autonomous). Este arquivo é o
ÚNICO ponto de entrada: o humano define o goal apontando para cá; o agente segue
sozinho até o fim, fase a fase, e só chama `goal.complete()` quando a tabela §7
estiver 100% ✅ (ou BLOQUEADA com motivo documentado).

**Meta:** experiência mobile de gamepad universal "estilo Steam Input" — fruto da
avaliação dos `reference/` (SDL3, SDL_GameControllerDB, RetroArch, moonlight,
PPSSPP, Dolphin, DuckStation, key-mapper, DS4Windows). Dois eixos:
1. **Detecção universal** (K3→K4→K5→K6): qualquer controle mapeia certo —
   conhecido (DB), desconhecido (capabilities), corrigido (quirks),
   compartilhado (formato SDL).
2. **Universalidade de interface** (K2→K1→K7): stick vira mouse, overlay de
   toque vira gamepad no pipeline rico, calibração visual.

Antecessores já CONCLUÍDOS (não reabrir): roadmap UX A–F
(`spec-2026-08-16-master-roadmap-ux.md` — rumble, remap visual, device card,
radial v2/turbo, touchpad swipes/macros, catálogo de perfis) e roadmap input
H/I/J1/J2 (`spec-2026-08-16-master-roadmap-input-avancado.md` — modificadores por
binding, trigger engine, expressões, chords).

## 1. Loop do agente (a cada iteração)

1. Leia `AGENTS.md` (JAVA_HOME, testes filtrados, gotchas) e a tabela de status
   (§7). Implemente a próxima fase INCOMPLETA, NA ORDEM da §4. Nunca pule fase;
   nunca paralelize fases que dividem hotspot (matriz §5).
2. Abra o spec da fase (`docs/spec-2026-08-16-K*.md`). Cada spec é AUTOCONTIDO
   (origem + onde ler a fonte reference com linhas, estado atual file:line,
   design, arquivos, testes, gate, não-metas) e pode ser delegado a sub-agente:
   `await rlm("Implemente docs/spec-2026-08-16-KX-....md do repo /home/annapaula/GameNative. Siga o spec ao pé da letra; leia AGENTS.md primeiro; o gate no fim DEVE passar antes do commit.", name="fase-kx")`
3. **Clean-room obrigatório**: references GPL (Dolphin GPL-2; DuckStation,
   key-mapper, RetroArch, moonlight GPL-3) e zlib (SDL) — portar SEMÂNTICAS
   documentadas nos specs, reimplementadas em Kotlin, citando a origem no KDoc
   (padrão do repo: `MappingParser.kt`, `GyroProcessor.kt`). NUNCA copiar código.
4. Gate: o comando da fase DEVE passar antes de commitar. Falhou 2× seguidas →
   PARE, marque BLOQUEADA na §7 com o erro exato. On-device é do humano —
   registre "on-device pendente" no impl doc, NÃO bloqueie.
5. Fechou a fase: commit (`feat(gamepad): ... (spec 2026-08-16-KX-...)`), impl
   doc (`<spec>-impl.md`, evidências file:line), atualize a §7 (checkbox + hash
   + data), commit checkpoint. Fase = checkpoint atômico idempotente.
6. Se `/refine` existir no harness: promover gotchas (JAVA_HOME; suíte de testes
   inteira NUNCA; XServerScreen dex; build/ stale; V1 store).

## 2. Regras globais (invariantes de TODA fase)

- Todo gradlew com `JAVA_HOME=/home/annapaula/android-studio/jbr`.
- NUNCA `:app:testModernDebugUnitTest` sem `--tests` (suíte inteira ≈ 30 min).
- Docs e commits em PT-BR; strings EN (`values/`) + pt-rBR (`values-pt-rBR/`).
- Lógica pura em `object`/data classes SEM `android.*` (`gamepad/mapping|
  processing|virtual`), JVM-testável.
- Degradação byte-identical: campo null / flag OFF / fixup null = caminho atual
  exato — TODOS os specs K foram desenhados assim; preserve nos detalles
  (identidade de referência quando nada muda, sem alocação no hot path).
- Perfis: campos novos null-default; atualizar `isDefault()`/`merged()`; store
  preserva chaves desconhecidas (V1).
- `XServerScreen.kt`: ZERO locals novas na função principal (limite dex). K2/K1
  podem tocar a screen com NO MÁXIMO 1 holder `remember` em componente próprio;
  se precisar mais, o design está errado — pare e registre.
- Handlers de evento: estado lido NO MOMENTO do evento (holders vivos, lição C1).
- Sanity build em TODO gate: `:app:assembleModernDebug`.
- Não rode `git push`; commit local apenas. Nunca reescreva histórico.
- MIUI bloqueia `adb input` — harness `adb shell setprop debug.gamenative.input`
  (protocolo em `ui/component/DebugGamepadInput.kt`); on-device é HUMANO.

## 3. Fase 0 — Correções residuais J1 (pré-requisito, spec já existe)

Aplicar os 2 achados do `docs/spec-2026-08-16-master-roadmap-input-avancado-
verificacao.md` §5: (1) eixo `expr:` end-to-end em `ExprBindingProcessor`
(Correção A); (2) `remapEvent` token malformado volta a pass-through (null →
`listOf(event)`). É ANTES de tudo porque K2/K1 pendem hooks no MESMO
`GamepadHub.remapEvent` — correções primeiro, features depois. Commit de
fronteira após fechar.

## 4. Ordem de implementação e porquê (interferência)

```
Fase 0 → K3 → K4 → K5 → K6 → K2 → K1 → K7
```

- **K3 primeiro (detecção)**: introduz `capabilities`/`mappingSource`/tiers e
  completa o parser (misc1/paddles) — K4, K5 e K6 CONSUMEM esses alicerces.
- **K4 depois de K3**: quirks aplicam SOBRE o mapping escolhido pela cadeia nova
  (inclusive usando capabilities para decidir se o quirk é necessário).
- **K5 depois de K4**: o tier USER salva o mapping efetivo — precisa da cadeia
  completa (com quirks documentados como reaplicáveis) para não congelar
  correções de transporte.
- **K6 por último na trilha de detecção**: encode precisa do parser COMPLETO
  (K3) + masks de capabilities (K3) + store USER como destino (K5).
- **K2 depois da detecção**: independente da trilha de mapping, mas pendura no
  flush do hub (que a Fase 0/K3 tocaram) e no `GamepadProfile` (que K5 não
  mexe, K6 não mexe — janela limpa). Médio risco, zero inputcontrols.
- **K1 depois de K2**: a maior fase (legacy winlator + XServerScreen); compõe
  com K2 (o device virtual pode usar o modo mouse) e herda o pipeline já
  estabilizado por tudo que veio antes.
- **K7 fecha**: UI isolada + 2 campos no `StickTransform` — nenhum hotspot
  compartilhado com as anteriores; fecha o roadmap sem risco.
- Commit de fronteira OBRIGATÓRIO entre fases vizinhas (nunca duas abertas no
  working tree).

## 5. Matriz de hotspots (quem toca o quê)

| Área | F0 | K3 | K4 | K5 | K6 | K2 | K1 | K7 |
|---|---|---|---|---|---|---|---|---|
| `gamepad/GamepadHub.kt` (remapEvent/flush/mappingFor) | ● | ● | ● | ● | ○ | ● | ● | |
| `gamepad/expressions/*` (ExprBindingProcessor) | ● | | | | | | | |
| `gamepad/mapping/*` (MappingParser, SdlControllerDb, GamepadButton, MappingDatabase) | | ● | ● | ○ | ● | | ○ | |
| `gamepad/mapping/DeviceQuirks.kt` (NOVO K4) | | | ● | ○ | | | | |
| `gamepad/mapping/DeviceMappingStore.kt` (NOVO K5) | | | | ● | ● | | | |
| `gamepad/mapping/CapabilityMapping.kt` (NOVO K3) | | ● | ○ | ○ | ○ | | | |
| `gamepad/processing/*` | | | | | | ● (NOVO MouseMode) | | ● (StickTransform) |
| `gamepad/virtual/*` (NOVO K1) + `com/winlator/inputcontrols/*` | | | | | | ○ | ● | |
| `gamepad/profiles/GamepadProfile(+Store)` | | | | | | ● | ○ | ● |
| `gamepad/remap/GamepadRemapDialog.kt` | | | | ○ | ● | ● | | ● |
| `ui/screen/settings/SettingsGroupGamepad.kt` (device card) | | ● | ● | ● | ● | ● | ● | |
| `ui/screen/xserver/XServerScreen.kt` | | | | | | ○ (≤1 holder) | ○ (≤1 holder) | |
| `res/values*/strings.xml` | | ● | ● | ● | ● | ● | ● | ● |

(● = edita; ○ = consome/lê.) As colunas K3–K6 são estritamente sequenciais pela
coluna `gamepad/mapping/*`; K2/K1 dividem `GamepadHub.kt`; K7 não colide com nada.

## 6. Atribuição (preservar — identidade técnica do fork)

Os ports têm origem e devem citá-la no KDoc: SDL3 (capabilities/positional/
virtual joystick — zlib), SDL_GameControllerDB (asset — manter LICENSE junto ao
asset), moonlight (quirks, modo mouse, virtual controller — GPL-3 clean-room),
RetroArch (autoconfig save — GPL-3 clean-room), PPSSPP (calibração visual — GPL-2
clean-room). Nota de casa: `reference/androidx` está um sparse-checkout quebrado
(só arquivos raiz; `games/controller` não existe no branch) — sem uso; limpar ou
corrigir o checkout é housekeeping opcional, não fase.

## 7. Status (O AGENTE ATUALIZA após cada fase — checkpoint idempotente)

| Fase | Spec | Gate | Status | Commit |
|---|---|---|---|---|
| 0 | `spec-2026-08-16-master-roadmap-input-avancado-verificacao.md` §5 | tests `*Expr* *Gamepad*` + assemble | ✅ 2026-08-16 | `d1c7f600` |
| K3 | `spec-2026-08-16-K3-deteccao-universal-capacidades.md` | tests `*CapabilityMapping* *Mapping* *Sdl* *Gamepad*` + assemble | ✅ 2026-08-16 | `aa0132c2` |
| K4 | `spec-2026-08-16-K4-quirks-vidpid.md` | tests `*Quirk* *Gamepad*` + assemble | ✅ 2026-08-16 | `5a12bd7e` |
| K5 | `spec-2026-08-16-K5-autoconfig-save-dispositivo.md` | tests `*Autoconfig* *DeviceMapping* *Gamepad*` + assemble | ✅ 2026-08-16 | `4af898ed` |
| K6 | `spec-2026-08-16-K6-intercambio-mapping-sdl.md` | tests `*Sdl* *Mapping* *Gamepad*` + assemble | ✅ 2026-08-16 | `99609ae1` |
| K2 | `spec-2026-08-16-K2-modo-mouse-universal.md` | tests `*MouseMode* *Gamepad*` + assemble | ✅ 2026-08-16 | `f64df99e` |
| K1 | `spec-2026-08-16-K1-gamepad-virtual-toque.md` | tests `*Virtual* *TouchGamepad* *Gamepad*` + assemble | ✅ 2026-08-16 | `f33fc076` |
| K7 | `spec-2026-08-16-K7-calibracao-stick-visual.md` | tests `*Stick* *Curve* *Gamepad*` + assemble | ✅ 2026-08-16 | `7792c986` |

Comandos de gate (prefixar sempre):
```
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:testModernDebugUnitTest --tests "*<FILTRO>*"
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:assembleModernDebug
```


> **FECHADO (2026-08-16):** 8/8 fases ✅ — roadmap universal input completo.
> Checkpoint final: `docs/spec-2026-08-16-universal-input-fechamento-impl.md`;
> milestone `milestone-2026-08-16-universal-input-completo` (docs/MILESTONES.md);
> dívida on-device consolidada no protocolo v2 (sessões A/B/C — humano).
## 8. Como rodar (sugestão para o humano — Prime Agent)

**Opção A — UM goal para tudo (recomendado):**
```
goal: "Execute docs/spec-2026-08-16-master-roadmap-universal-input.md do repo /home/annapaula/GameNative até o fim: siga o loop §1, implemente as fases NA ORDEM §4 (Fase 0 → K3 → K4 → K5 → K6 → K2 → K1 → K7), gate de cada fase §7 DEVE passar antes do commit, atualize a tabela §7 após cada fase. Chame goal.complete() somente quando TODAS as fases estiverem ✅ com commit (ou BLOQUEADA com erro documentado na §7)."
--autonomous-max-turns 150   # F0≈8 + K3≈25 + K4≈12 + K5≈18 + K6≈18 + K2≈20 + K1≈40 + K7≈15 + margem de gates
```

**Opção B — um goal por fase (mais controle, retomável):** repita por fase (o loop
§1 é idempotente — a §7 diz de onde retomar):
```
prime-agent --autonomous \
  --autonomous-gate "git -C /home/annapaula/GameNative log --oneline -1 | grep -q 'spec 2026-08-16-K3'"
  "Siga docs/spec-2026-08-16-master-roadmap-universal-input.md do repo /home/annapaula/GameNative: execute a próxima fase INCOMPLETA da tabela §7. Uma fase por invocação."
```

## 9. Verificação on-device consolidada (humano — Mi 11 + DS4 + Silksong)

| Fase | Protocolo (harness `setprop debug.gamenative.input` quando aplicável) | Evidência |
|---|---|---|
| 0 | expressões de eixo do §6.3 do doc de verificação H/I/J | eixo `expr:` afeta o jogo; perfil corrompido passa o botão adiante |
| K3 | DS4 = regressão zero; generic DInput 1-stick sem binding fantasma; card mostra origem do mapping | screenshots do card + logcat `gncontrol` |
| K4 | DS4 BT idêntico; quirk só ativa quando capability indica | log `quirk aplicado` |
| K5 | remap → salvar → reconectar BT → persiste; restaurar volta | badge USER no card |
| K6 | export DS4 ≈ entry do DB; editar string → importar → diff aplicado; string desktop bloqueada | vídeo curto |
| K2 | hold START 750 ms → haptic → stick=cursor/A-B cliques/dpad scroll; QuickMenu suspende; KB/M-only jogável | vídeo curto |
| K1 | flag OFF regressão zero; flag ON → camada/chord do PERFIL afeta o overlay; `touchtap` chega ao hub | log deviceId virtual + viewer do card |
| K7 | trilha raw vs calibrada ao vivo; anti-deadzone respeitado; persistência por escopo | screenshots |

Registrar cada linha no padrão "on-device pendente" do spec da fase (não bloqueia
o fechamento — regra §1.4).

## 10. Não-metas globais (todas as fases)

uinput//dev/input raw, remap de apps externos, HD haptics/lightbar/adaptive
triggers (API pública não expõe — LED via `LightsManager` é reabrível se pedido),
migração C++/Rust (baseline F0 p95 3–5 ms), tocar em `LibraryList.kt` (código
morto), substituir camadas/triggers/expressões existentes (as fases COMPÕEM),
sincronizar autoconfigs com o upstream `SDL_GameControllerDB` (follow-up do K6),
reescrever o editor de layout do winlator (K1 preserva), calibração automática
de stick (follow-up do K7).
