# Verificação final — Master Roadmap Input Avançado (H/I/J1/J2)

**Data:** 2026-08-16
**Roadmap:** `docs/spec-2026-08-16-master-roadmap-input-avancado.md`
**Executor das fases:** agente autônomo (Prime Agent, modo goal) — commits `10526ba5`,
`92512ed4`, `e9b7fc19`, `be4bc6ec` + impl docs + checkpoints na tabela §6.
**Verificador:** humano (revisão de código) + re-execução independente do gate
após `clean` total. Este doc registra a verificação INDEPENDENTE e consolida os
achados residuais e o roteiro on-device.

## 1. Gate independente (após `./gradlew clean` completo)

Executado do zero (build limpo — elimina a suspeita de classes stale, gotcha do
AGENTS.md):

```
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:testModernDebugUnitTest --tests "*Expr*" --tests "*Binding*" --tests "*Trigger*" --tests "*Layer*" --tests "*Gamepad*" --offline
  → BUILD SUCCESSFUL (5m34s) — 176 testes em 16 classes, 0 falhas
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:assembleModernDebug --offline
  → BUILD SUCCESSFUL (6m17s)
python3 tools/profiles/sync_profile_repo.py ×2 + git diff --exit-code -- tools/profiles/
  → SYNC_EXIT=0 (determinístico)
```

Incidente de build registrado: um `classes.jar` corrompido em `build/intermediates`
gerou 1052 erros de compilação em cascata durante a verificação intermediária
(causa: `timeout` matando gradle no meio da escrita do jar — NÃO era código; o
`clean` acima resolveu). Lição registrada para o humano: nunca mate gradle com
`timeout`; use background sem limite.

## 2. Estado por fase

| Fase | Commit | Gate independente | Revisão |
|---|---|---|---|
| H — modificadores por binding | `10526ba5` | ✅ | ✅ (ver §3.1) |
| I — trigger engine | `92512ed4` | ✅ | ✅ (ver §3.2) |
| J1 — expressões | `e9b7fc19` | ✅ | ✅ com 2 achados residuais (ver §5) |
| J2 — chords com suppression | `be4bc6ec` | ✅ | ✅ (ver §3.3) |

## 3. Revisão técnica por fase

### 3.1 H — `BindingModifiers` + sufixo `:m=` no token

- `gamepad/processing/BindingModifiers.kt`: ordem full→invert→scale→deadzone,
  FullAxis com a fórmula exata do DuckStation (`v*0.5+0.5`), clamps, `null` =
  identidade (base da degradação byte-identical). ✅
- `GamepadBindingCodec`: sufixo `:m=` em UM campo `:` (split por vírgulas dentro do
  bloco), encode omite defaults (token legado byte-identical), decode LENIENTE
  (campos desconhecidos ignorados — política V1). ✅
- Hub: `bindingModsFor` (por eixo), `preApplyFullAxis` (só triggers, pré-pipeline),
  aplicação pós-pipeline com `fullAxis=null` no re-apply (ordem preservada,
  hub:1282). ✅
- UI `BindingModifierPanel` (fullAxis só em triggers) + strings EN/pt-rBR. ✅
- Testes: `BindingModifiersTest` 8 + `GamepadBindingCodecTest` (atualizado para o
  selado do J1) 15 — verdes no gate independente. ✅

### 3.2 I — `TriggerEngine` LONG_PRESS/SEQUENCE

- `gamepad/layers/TriggerEngine.kt`: LONG_PRESS (arma/consome, clock no limiar,
  up cedo = nada), SEQUENCE com timeout POR PASSO, `dieSequence` (ReleaseDelay/
  ConsumeDelay), overlap com `pendingActivate` (mais longa vence; curta ativa se a
  longa morrer), botão alheio passa ao jogo. `LayerResolver` intocado
  (HOLD/TOGGLE/DOUBLE_TAP byte-identical). ✅
- Hub: `PendingEmit` guarda Down+Up juntos (nunca Down fantasma), flush SEM timer
  no topo de onKey/onAxis/onSensorSample, V6 completo, zero custo sem specs novas. ✅
- UI: captura encadeada de sequência (máx 3 botões), chips, sliders, aviso de
  overlap (não-bloqueante). ✅
- Catálogo: `LAYER_TRIGGER_MODES` + seed determinístico. ✅

### 3.3 J1 + J2 — linguagem de expressões + chords

- **J1 núcleo puro:** lexer com colunas; parser com precedência Dolphin
  (or<and<not<comparação<+-<*/); 14 funções fiéis (`deadzone` rescalona, `timer`
  floor-reset, `relative` clamp+slot compartilhado, `toggle`/`hold`/`tap`/`pulse`
  com estado por call-site); threshold 0.5; div/0→0. ✅
- **J1 integração:** `flushExpressions` no mesmo flush de eventos da fase I, cache
  M1 do parse por (device, bindings efetivos), `logicalInputState` dobrando o
  evento atual antes da avaliação (sem lag de 1 evento), consumo do evento físico
  no `remapEvent` e na injeção U4 (`PhysicalControllerHandler`). ✅
- **J1 UI:** `ExprEditorDialog` com parse ao vivo (coluna do erro), chips de
  entrada, preview numérico via bus. ✅
- **J2 `ChordLogic`:** parse de chord (cadeia top-level de `+` com InputRef de
  BOTÃO puros — eixo desqualifica), `chordValue` com supressão por SUPERCONJUNTO
  (maior conjunto vence — `HotkeySuppressions`), `suppressFinal` para o binding
  simples. Semântica do Dolphin portada clean-room. ✅
- Testes: ExprParser 12 + ExprEvaluator 15 + ExprBindingProcessor 5 + ChordLogic
  (arquivo próprio) + codec 15 + ProfileCatalog 11 — todos no gate independente. ✅

## 4. J2 — decisões registradas (impl doc `7147ab8c`)

- Chord só com botões (eixo desqualifica — conflito `+` soma vs chord resolve
  como no Dolphin: operandos InputRef puros).
- Supressão só do binding SIMPLES do botão final; chords entre si não se
  suprimem (exceto superset totalmente segurado).
- Estado do chord derivado do perfil (parse-time, cache M1) — só o conjunto de
  segurados é por device.

## 5. Achados residuais (correções recomendadas — follow-up pequeno)

Dois pontos menores identificados na revisão de J1, confirmados presentes no
código commitado de J2 (não bloqueiam — os gates verdes cobrem o comportamento
funcional dos casos normais):

1. **Eixo `expr:` é código morto.** `ExprBindingProcessor.parseBindings`
   (ExprBindingProcessor.kt:33): `GamepadButton.entries.firstOrNull { it.name ==
   name } ?: continue` descarta chaves com nome de eixo ANTES do lookup de
   `GamepadAxis` (linha seguinte, sempre null para nomes de botão). O branch
   AxisMotion de `evaluate()` nunca é alcançado em produção e nenhum teste cobre
   (o teste até assere `axis == null`). O impl doc J1 (linhas 42-43) afirma
   suporte a eixo que não existe end-to-end.
   **Correção A (implementar):** `Parsed.button: GamepadButton?` + aceitar chave
   de eixo (button null, axis setado); evaluate emite só AxisMotion nesse caso.
   **Correção B (mínima):** remover o branch morto e corrigir a alegação do impl
   doc. Recomendação: A — é pouca mudança e fecha o spec §2.2 literal.
2. **Token malformado passou a ser CONSUMIDO.** `GamepadHub.remapEvent`
   (GamepadHub.kt:748): `GamepadBindingCodec.decode(token)?.raw ?: return
   emptyList()` — antes de J o fallback era `return listOf(event)` (pass-through).
   O intent de J era consumir SÓ `ExprBinding` (decode válido, raw null); token
   com decode null (perfil corrompido) agora engole o botão em vez de passar.
   **Correção:** `when (decoded) { is Physical -> …, is ExprBinding -> emptyList(),
   null -> listOf(event) }`.

## 6. On-device pendente (humano, DS4 + Silksong, Mi 11)

Consolidado dos impl docs H/I/J:

1. **H:** FullAxis num trigger HID (−1..1 → 0..1); sensibilidade 50% num stick
   doente; deadzone por binding vencendo a global.
2. **I:** SELECT→SELECT→FACE_TOP abre o radial sem "vazar" o primeiro SELECT
   (retardo liberado só se a sequência morrer); long-press de L3 = camada sniper;
   overlap de duas sequências com o mesmo 1º botão.
3. **J1:** `expr:toggle(face_bottom)` num botão de camada; `expr:deadzone(axis:left_y,
   0.3) > 0.5` como botão de sprint.
4. **J2:** `expr:face_bottom + face_right` num botão — com FACE_BOTTOM segurado,
   FACE_RIGHT NÃO chega ao jogo (suppression) e o chord emite; soltar FACE_BOTTOM
   restaura o binding simples.

## 7. Estado final e próximos passos

- Roadmap mestre §6: H/I/J1/J2 todas ✅ com commits e checkpoints — o goal do
  Prime Agent foi cumprido na íntegra (incluindo o stretch J2).
- Próximos passos sugeridos (em ordem): (1) aplicar as 2 correções do §5 (spec
  próprio pequeno ou commit direto documentado); (2) rodar o roteiro on-device
  §6; (3) follow-ups já mapeados em specs futuros se pedidos: variáveis `$`/
  trigonométricas (J3), gyro swipes (extensão fase D), taxa de sensor FASTEST.
