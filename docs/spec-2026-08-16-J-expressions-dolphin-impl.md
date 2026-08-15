# Impl doc — Spec 2026-08-16 J1 (Linguagem de expressões de binding — port Dolphin)

**Data:** 2026-08-16
**Spec:** `docs/spec-2026-08-16-J-expressions-dolphin.md` (entrega J1, §2)
**Executor:** Prime Agent (o sub-agente delegado parou sem escritas — assumido
inline pelo orquestrador).
**Base:** fases H (`10526ba5`) e I (`92512ed4`) commitadas.
**Resultado:** implementado, gate completo verde. J2 (chords, §3) executado em
seguida (stretch). On-device pendente (humano).

## 1. O que foi feito (por seção do spec)

### §2.1 Pacote `gamepad/expressions/` (NOVO, puro, JVM)

- `ExprAst.kt` (file:9): nós selados — NumberLit, InputRef(name, axis), Unary,
  Not, Binary(ExprOp), Ternary, Call(name, args, index) — o índice do call-site
  é parte da CHAVE do estado das funções com memória (duas chamadas de `toggle`
  na mesma expressão têm estados independentes).
- `ExprLexer.kt` (file:18): tokens com COLUNA (1-based) para os erros que a UI
  mostra; números Float; `+ - * / ( ) , ? : > < >= <= == !=`; `and or not`
  case-insensitive.
- `ExprParser.kt` (file:46): descendente com a precedência do spec
  (Dolphin `OperatorPrecedence`): ternário ACIMA DE TUDO → `or < and < not <
  comparação < + - < * /` < menos unário < primário; parênteses; ARIDADE
  validada no parse via `ExprFuncs.arityError`; entradas = GamepadButton.name
  case-insensitive + alias l1/r1/l2/r2/l3/r3 + eixos `axis:<nome>` validados
  contra GamepadAxis; desconhecido ⇒ `ExprParseException` com coluna.
- `ExprFuncs.kt` (file:45): as 14 funções do subset com as semânticas de
  `FunctionExpression.cpp` (KDoc cita cada classe-fonte): not (1−x), if (threshold),
  abs, min, max, clamp, deadzone (REESCALA copysign(max(0,|v|−dz)/(1−dz), v)),
  smooth (rampa up≠down por dt), toggle (latch na borda + clear), hold (conta a
  partir da BORDA de pressão), tap (multi-tap com reset pós-janela), pulse
  (one-shot com extensão), timer (rampa periódica floor-reset), relative
  (integra rate-of-change, satura no max, slot compartilhado).
- `ExprEvaluator.kt` (file:15): `eval(ast, reader, state, dtMs, nowMs)` —
  and/or/not no threshold 0.5 (CONDITION_THRESHOLD do Dolphin, FunctionExpression.h),
  comparações 1/0, div por zero/inf → 0 (BinaryExpression CalculateValue).
- `ExprState.kt` (file:12): por DEVICE; `reset()` total (borda/troca de perfil).
- `ExprBindingProcessor.kt` (file:25): camada PURA hub↔avaliador (o fake do hub
  testa isto — spec §4): extrai tokens `expr:` dos bindings efetivos (parse com
  erro ⇒ binding pulado, nunca crash), avalia e converte: transição 0↔1 no
  threshold ⇒ ButtonDown/Up do botão dono; eixo (chave que nomeia um GamepadAxis)
  ⇒ AxisMotion contínuo clampado 0..1.

### §2.2 Bindings `expr:` — codec + hub

- **Codec** (`GamepadBindingCodec.kt`): `LayerBinding` vira SELADO (file:47) —
  `Physical(raw, turbo, mod)` + `ExprBinding(source)`; decode de `expr:<source>`
  (file:99); acessores top-level `raw`/`turbo`/`mod` devolvem null/default para
  ExprBinding — os call sites de remap FÍSICO tratam null = a expressão é a DONA
  do botão. Tokens legados byte-identical (testado).
- **Hub** (`GamepadHub.kt`): estado em file:375 (cache do parse por device M1,
  exprStates, exprLastEvalMs, logicalInputState); `flushExpressions` (file:541)
  chamado NO MESMO flush de eventos da fase I (onKey/onAxis/onSensorSample):
  - o evento ATUAL é dobrado no estado lógico ANTES da avaliação (sem lag de 1
    evento para o próprio input);
  - cache do parse por (bindings efetivos) — sem token `expr:` o flush retorna
    imediato (zero parse/alocação no hot path — byte-identical);
  - emissão DIRETA no bus (o valor da expressão É o valor lógico — não passa
    pelo remapEvent, que consumiria o próprio token);
  - `remapEvent` e a injeção física (PhysicalControllerHandler) CONsomem o evento
    físico do botão com binding `expr:` (a expressão substitui a fonte física);
  - V6: estados mortos no removeDevice; reset no `invalidateProfiles` (troca de
    perfil — padrão GyroProcessor).
- dt da avaliação = (now − última avaliação).coerceIn(1..100 ms) — mesmo clamp
  do GyroProcessor.

### §2.3 UI — `gamepad/remap/ExprEditorDialog.kt` (NOVO) + linha do binding

- Chip `ƒx` em TODA linha do binding (aceso quando o token é `expr:`) abre o
  editor (janela própria — GamepadFocusScope, um dono do input por janela;
  GamepadRemapDialog.kt file:1099).
- Editor (file:64): campo multiline com parse AO VIVO (erro com coluna no rodapé),
  chips "Inserir entrada" com os nomes válidos do device (`ExprParser.INPUT_NAMES`
  — botões + alias + `axis:…`), preview numérico AO VIVO (listener de
  GamepadInputEvent no bus — padrão do input viewer da fase C — + relógio de
  50 ms para as funções temporais) e Salvar (re-encoda `expr:<source>`;
  vazio = remover o binding).

### §2.4 Catálogo

- `ProfileCatalog.summaryOf`: categoria NOVA `EXPR` (file:134) — tokens `expr:`
  contam nela; rótulo `profile_summary_expr` EN/pt-rBR + branch no
  ProfileCatalogBrowser.
- `sync_profile_repo.py`: tokens `expr:` validados por gramática MÍNIMA
  (file:107) — fonte não-vazia, ≤ 256 chars, só ASCII imprimível (o parser Kotlin
  é quem valida a linguagem — não reimplementado no validador). Seed
  `expr-sprint-button.json` (FACE_TOP = `expr:face_bottom and axis:left_y > 0.7`)
  — asset regenerado com 8 perfis, determinístico (2× runs → md5 `267a4982…`).

## 2. Testes

- `ExprParserTest` (12): precedência 1+2*3, or<and<not, not vs comparação,
  ternário acima de tudo, parênteses, refs de eixo/botão/alias, aridade errada =
  erro com coluna, função/entrada desconhecida, coluna exata do erro, expr
  vazia/lixo ⇒ erro, call sem parênteses inválida.
- `ExprEvaluatorTest` (15): literais/entradas; threshold 0.5; comparações;
  div/0→0; ternário; not/if/abs/min/max/clamp; deadzone REESCALA; toggle latch+
  clear; hold (borda, expira); tap (2 taps, 3º não dispara, reset pós-janela);
  smooth up≠down; pulse one-shot; timer rampa periódica; relative integra+satura;
  relative com slot compartilhado. (Testes com estado reusam o MESMO AST — o
  índice do call-site é parte da chave do estado; o hub faz o parse UMA vez.)
- `ExprBindingProcessorTest` (5, integração pura — fake do hub): expr em FACE_TOP
  emite Down/Up; trigger emite eixo contínuo; sem expr nada muda; expr inválido
  é pulado; detecção de tokens.
- `GamepadBindingCodecTest` (15): round-trip `expr:`, variante selada, legado
  byte-identical (Physical + turbo + m=).
- `ProfileCatalogTest` (11): EXPR no resumo; token comum não conta EXPR.

## 3. Gate (comandos e resultados)

```
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:testModernDebugUnitTest --tests "*Expr*" --tests "*Gamepad*" --offline
  → BUILD SUCCESSFUL (ExprParser 12 + ExprEvaluator 15 + ExprBindingProcessor 5 + codec 15 + demais Gamepad*)
+ ProfileCatalogTest (11/11 — extra)
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:assembleModernDebug --offline
  → BUILD SUCCESSFUL
python3 tools/profiles/sync_profile_repo.py ×2 → md5 267a4982… idêntico (determinístico)
```

## 4. On-device pendente (humano — spec §6)

1. `expr:toggle(face_bottom)` num botão de camada — cada pressão alterna a camada.
2. `expr:deadzone(axis:left_y, 0.3) > 0.5` como botão de sprint — dispara quando o
   stick passa de ~30% (com rescale do deadzone, o 0.5 cai perto de 0.65 cru).

## 5. Desvios do spec (registrados)

1. **`not(...)` é o OPERADOR `not`** (threshold 0.5) — o lexer trata `not` como
   palavra-chave (o spec lista o token `not`); a forma de FUNÇÃO `not(x)` (1−x do
   NotExpression) permanece em ExprFuncs mas é inalcançável pela gramática.
   Semântica do spec §2.1 preservada (not usa threshold).
2. **`relative(..., shared)` com 4º argumento NUMÉRICO** — variáveis `$` são
   non-goal (spec §7); o slot numérico substitui o `$var` para compartilhar
   estado entre pares up/down (Documentado no KDoc).
3. **`and`/`or` com threshold 0.5** (1/0), não o min/max analógico do Dolphin —
   decisão literal do spec §2.1 ("and/or/not usam CONDITION_THRESHOLD").
4. **Preview do editor reusa o fluxo do bus** com relógio de 50 ms (sem clock
   dedicado no hot path — o relógio do JOGO continua sendo os eventos de input).
5. **RemapRow**: o chip ƒx aparece em TODAS as linhas (inclusive sem binding — o
   token `expr:` é materializado na camada); Turbo/⋯ continuam só para bindings
   físicos.
