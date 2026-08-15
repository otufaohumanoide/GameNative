# Spec 2026-08-16 J — Linguagem de expressões de binding (port Dolphin, subset)

**Data:** 2026-08-16
**Executor:** agente autônomo (Prime Agent). Leia `AGENTS.md` e o roadmap mestre
`docs/spec-2026-08-16-master-roadmap-input-avancado.md` (§1 loop, §2 regras) ANTES.
**Posição na fila:** fase J (ÚLTIMA, depois de H e I — commits de fronteira).
Espec sugerido em DUAS entregas: **J1** (linguagem + bindings) e **J2** (chords com
suppression). J1 sozinho já é entregável completo; J2 é stretch (ver §7).
**Turn budget sugerido:** J1 30–40 turns; J2 15–20 turns.

## 0. Origem e onde ler a fonte

Feature EXCLUSIVA do Dolphin entre os references (nenhum outro projeto tem linguagem
de expressões sobre inputs). É o coração do `ControlReference`: cada binding é uma
EXPRESSÃO avaliada por poll, não uma tecla única. Clean-room OBRIGATÓRIO: Dolphin é
GPL-2.0 — portar GRAMÁTICA e SEMÂNTICAS documentadas, nunca código. Padrão do repo:
KDoc citando `reference/dolphin/...` (como `GyroProcessor` cita `IMUGyroscope.cpp`).

Leia (nessa ordem):
1. `reference/dolphin/Source/Core/InputCommon/ControlReference/ExpressionParser.h`
   — o modelo (ParseStatus, ExpressionParsingError) e a divisão parser/funções.
2. `reference/dolphin/Source/Core/InputCommon/ControlReference/FunctionExpression.cpp`
   (~700 linhas — leia inteiro com calma; é o ALVO principal do port). 25 funções.
   Subset a portar (decisão deste spec): `not`, `if`, `abs`, `min`, `max`, `clamp`,
   `deadzone`, `smooth`, `toggle`, `hold`, `tap`, `pulse`, `timer`, `relative`.
   NÃO portar: trigonométricas (`sin..atan2`), `sqrt`, `pow`, `mod` aritmético
   avançado (o divisor `/` fica), variáveis `$` e comentários `/* */` (J3 futuro).
3. `reference/dolphin/Source/Core/InputCommon/ControlReference/ExpressionParser.cpp`
   — leia SELETIVO: precedência de operadores (Seção `ParseBinops`: `and or xor` <
   comparações não existem < `+ -` < `* / mod`), ternário `? :`, literais, o
   `CONDITION_THRESHOLD = 0.5` (bool = `lround(v) > 0`... use o threshold 0.5 do
   FunctionExpression.cpp), e — SÓ PARA J2 — `HotkeyExpression`/`HotkeySuppressions`
   (**linhas 67–113 e 555–635**): a sintaxe `A + B` e a regra "enquanto o chord
   está ativo, o binding simples do botão final é suprimido; chords que compartilham
   tecla resolvem por superconjunto".
4. Semânticas das funções (todas em FunctionExpression.cpp — leia o `GetReturnValue`
   de cada): `deadzone(input, amount)` REESCALA (não clipa); `smooth(input, sec_up,
   sec_down)` rampa de attack/release; `toggle(input[, clear])` latch na borda de
   subida; `hold(input, sec)` segura o valor `sec` após o press; `tap(input, sec,
   taps=2)` multi-tap; `pulse(input, sec)` one-shot; `timer(sec)` pulso periódico;
   `relative(input, speed, max, shared)` integra rate-of-change com estado
   compartilhado entre pares up/down.

## 1. Estado atual (anchors do fork)

- Bindings do perfil: token plano (`GamepadBindingCodec` — `key:96` etc.; a fase H
  adiciona `:m=...`). Um binding = UMA fonte física.
- Camadas: `GamepadProfile.layers: Map<layer, Map<GamepadButton.name, token>>`;
  remap aplicado em `GamepadHub.remapEvent` (hub:438+) via `effectiveBindings`.
- Estado vivo de botões/eixos no hub: `buttonStates` (por device) e eventos
  `InputEvent.ButtonDown/Up/AxisMotion` (vocabulário em `mapping/RawInput.kt`).
- O fork NÃO tem: binding que COMPUTA um valor a partir de vários inputs, funções
  temporais (toggle/hold/tap/smooth), nem chord com suppression.

## 2. Design (J1 — linguagem + bindings)

### 2.1 Pacote NOVO `gamepad/expressions/` (tudo PURO, JVM-testável)

| Arquivo | Conteúdo |
|---|---|
| `ExprLexer.kt` | tokens: identificadores, números (Float), `+ - * / ( ) , ? : > < >= <= == != and or not` |
| `ExprAst.kt` | data classes sealed: `NumberLit`, `InputRef(name, axis: Boolean)`, `Unary`, `Binary`, `Ternary`, `Call(nome, args)`, `Not` |
| `ExprParser.kt` | parser descendente com precedência DO DOLPHIN: `or < and < not < comparação < + - < * /` ; ternário acima de tudo; parênteses. Erros com posição (col) — a UI mostra |
| `ExprFuncs.kt` | implementações puras das 14 funções: cada uma é `(args: List<Float>, state: FuncState, dtMs: Long, nowMs: Long) -> Float` + assinatura de aridade |
| `ExprEvaluator.kt` | `eval(ast, inputReader: (String, Boolean) -> Float, state: ExprState, dtMs, nowMs): Float`; reader devolve 0..1 do botão/eixo lógico do device |
| `ExprState.kt` | por (device, expressão): mapa `nomeFunção+indice -> FuncState` (toggle latch, smooth ramp, hold deadline, tap counter, relative integrator, pulse deadline). `reset()` total (borda de ativação, padrão GyroProcessor) |

Entradas nomeadas: botões = `GamepadButton.name` case-insensitive (`face_bottom`,
`l1`…), eixos = prefixo `axis:` (`axis:left_x` devolve −1..1). `and/or/not` usam
`CONDITION_THRESHOLD = 0.5f` (Dolphin: `FunctionExpression.cpp`).

### 2.2 Bindings de expressão — token `expr:`

- `GamepadBindingCodec.decode`: token com prefixo `expr:` ⇒ `LayerBinding(raw =
  ExprPlaceholder(source), …)`? NÃO — decisão: o codec fica AGNÓSTICO. O decode de
  `expr:` retorna `LayerBinding(raw = null-marker, …)` é feio; em vez disso o codec
  ganha `sealed` adicional: `LayerBinding.ExprBinding(source: String)`. Encode
  escreve `expr:<source>`. Round-trip testado; tokens legados inalterados.
- Aplicação: NO `remapEvent`/caminho de emissão do hub, binding `expr:` do botão
  lógico NÃO é usado como fonte física — em vez disso, o hub mantém uma lista de
  expressões ATIVAS do perfil efetivo (cache M1 por perfil) e as avalia no FLUSH de
  eventos (mesmo ponto do flush da fase I: onKey/onAxis/onSensorSample):
  transição 0↔1 (threshold 0.5) ⇒ emite `InputEvent.ButtonDown/Up` lógico do botão
  dono do binding; para eixos (binding de expressão em `GamepadAxis`) ⇒ AxisMotion
  com o valor contínuo.
- Estados de expressão: `exprStates: MutableMap<Int deviceId, ExprState>` (V6,
  morto no removeDevice; reset na troca de perfil `invalidateProfiles`).
- Degradação: SEM `expr:` no perfil ⇒ zero parsing no hot path, zero alocação —
  byte-identical.

### 2.3 UI — editor de expressão

No remap dialog, na linha do binding: opção "Expressão…" abre `ExprEditorDialog`
(arquivo próprio, `GamepadFocusScope`): campo multiline + parse ao vivo (erro com
linha/col), botão "inserir entrada" com a lista de nomes válidos do device
(`ControllerVisualLayout` dá os nomes), preview numérico ao vivo do valor avaliado
(reusa o fluxo de eventos do bus como o input viewer da fase C faz). Strings EN+pt-rBR.

### 2.4 Catálogo

`sync_profile_repo.py`: tokens `expr:` validados por gramática mínima (só
caracteres permitidos, tamanho ≤ 256) — não re-implementar o parser no validador.
`ProfileCatalog.summaryOf`: expressões = categoria nova EXPR no resumo.
Regenerar 2× → diff vazio.

## 3. Design (J2 — chords com suppression, STRETCH)

- Sintaxe portada do `HotkeyExpression`: `A + B` (o `+` aqui é CHORD, não soma —
  dentro de `expr:` só; conflito com soma resolvido como no Dolphin: chord exige
  operandos `InputRef` puros).
- Semântica: enquanto `A` (o modificador) está ativo (>0.5), o binding SIMPLES de
  `B` é suprimido (não emite); o chord emite quando B também ativa. Chords que
  compartilham teclas: o de MAIOR conjunto vence (superset — Dolphin
  `HotkeySuppressions`).
- Implementação: registro de chords por perfil (parse-time); no flush de eventos,
  antes de emitir ButtonDown lógico de B, checa se B é final de chord ativo ⇒
  suprime. Estado por device (V6). 
- Se J2 estourar o budget: registrar como follow-up no roadmap mestre e NÃO
  commitar J2 parcial — J1 fechado é entregável.

## 4. Testes (JVM, puros)

- `ExprParserTest`: precedência (`1+2*3`, `or`<`and`<`not`), ternário, parênteses,
  aridade das calls, erro com coluna, `expr:` vazio/lixo ⇒ erro.
- `ExprEvaluatorTest`: literais/entradas; and/or/not no threshold 0.5; cada função:
  `deadzone` REESCALA (0.1 com dz 0.5 → 0, 0.75 → 0.5), `toggle` latch na borda e
  clear, `hold` segura e expira, `tap` 2 taps na janela e 3º reset, `smooth` rampa
  up≠down, `pulse` one-shot por borda, `timer` período, `relative` integra e satura
  no max; aridade errada ⇒ erro de parse.
- `GamepadBindingCodecTest`: round-trip `expr:...`; token legado byte-identical.
- Integração pura (fake do hub, padrão `GamepadFavorites` fake): perfil com
  `expr:face_bottom and axis:left_x > 0.5` em FACE_TOP ⇒ transições emitem
  Down/Up do FACE_TOP; sem expr ⇒ nada muda.

## 5. Arquivos

| Arquivo | Mudança |
|---|---|
| `gamepad/expressions/{ExprLexer,ExprAst,ExprParser,ExprFuncs,ExprEvaluator,ExprState}.kt` | NOVO (2.1) |
| `gamepad/remap/GamepadBindingCodec.kt` | `expr:` encode/decode (2.2) |
| `gamepad/GamepadHub.kt` | lista de exprs do perfil + avaliação no flush + exprStates (2.2) |
| `gamepad/remap/ExprEditorDialog.kt` | NOVO (2.3) |
| `gamepad/profiles/ProfileCatalog.kt` + `tools/profiles/sync_profile_repo.py` | (2.4) |
| `app/src/test/.../expressions/*` | NOVO (§4) |
| `res/values*/strings.xml` | EN + pt-rBR |

## 6. Gate

```
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:testModernDebugUnitTest --tests "*Expr*" --tests "*Gamepad*" --offline
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:assembleModernDebug --offline
python3 tools/profiles/sync_profile_repo.py && python3 tools/profiles/sync_profile_repo.py && git diff --exit-code -- tools/profiles/
```
On-device pendente (humano): `expr:toggle(face_bottom)` num botão de camada;
`expr:deadzone(axis:left_y, 0.3) > 0.5` como botão de sprint.

## 7. Não-metas

Variáveis `$`, comentários, trigonométricas/pow/sqrt (J3 se pedido), bindings de
SAÍDA (OutputReference do Dolphin — rumble/LED são non-goal do repo), UI de mapear
por expressão em TODOS os lugares (só remap dialog), tocar `XServerScreen.kt`,
substituir o sistema de camadas/triggers (expressões COMPÕEM com I, não substituem).

## 8. Critério de conclusão (para o goal)

J1: gate verde + commit `feat(gamepad): linguagem de expressões de binding — 14 funções Dolphin em gamepad/expressions (spec 2026-08-16-J-expressions-dolphin)` + impl doc + checkpoint. J2 (se executado): idem com sufixo `-J2`. Se J2 abortado por budget: marcar `FOLLOW-UP` no roadmap mestre §6 e concluir J1.
