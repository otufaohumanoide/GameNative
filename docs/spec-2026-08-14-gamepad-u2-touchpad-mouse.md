# Spec 2026-08-14 — U2: touchpad DS4/DualSense → mouse (com V12)

**Data:** 2026-08-14
**Origem:** doc de intuito (spec 2026-08-14-gamepad-intuito-validacao-upgrades.md, U2) —
2º pedido mais votado; o gate de ghost input é o PONTO DE PLUG declarado
(MainActivity.kt:582-587, 636-641). O touchpad continua CONSUMIDO pelo gate
(fantasmas nunca chegam ao foco nem ao jogo — V7); o consumidor do touchpad→mouse lê o
MESMO ponto ANTES do consume (V7, exceção única).
**Natureza:** implementação; gate `gamepadTouchpadMouseEnabled` default OFF (opt-in,
mesma filosofia de kill-switch); com OFF o caminho é byte-identical (V10).

---

## 0. Estado atual

- Stub `InputEvent.TouchpadMotion` (gamepad/InputEvent.kt:29) — vocabulário pronto.
- `DeviceClass.TOUCHPAD` classificado (DeviceClassifier.kt:34-48).
- Gate no MainActivity: `PrefManager.ignoreControllerTouchpad` (default ON) consome
  key/motion com `SOURCE_CLASS_POINTER` em game controller ANTES do bus.
- Precedente de injeção: `PhysicalControllerHandler.createMouseMoveTimer` →
  `xServer.injectPointerMoveDelta` (60 FPS) + `injectPointerButtonPress/Release`.
- `AndroidInputAdapter.toRawAxis` NÃO coleta eixos do touchpad (só gamepad).

## 1. Design

### 1.1 Processador puro: `TouchpadProcessor` (gamepad/processing/TouchpadProcessor.kt)

`object` puro, JVM-testável (V5). Entrada por amostra:

```kotlin
data class TouchSample(
    val down: Boolean,      // ACTION_DOWN/POINTER_DOWN (finger presente)
    val x: Float, val y: Float,  // absoluto normalizado [0..1] (AXIS_X/AXIS_Y)
    val nowMs: Long,
)
data class TouchpadDecision(
    val deltaX: Float, val deltaY: Float,  // deltas a injetar (já escalados)
    val tap: Boolean,                       // tap completo → clique esquerdo
)
fun process(sample: TouchSample, state: TouchpadState): TouchpadDecision
```

- **Delta:** `(x,y) - (xPrev,yPrev)` com deadzone de toque (movimento < `touchDeadzone`
  é descartado — ruído de dedo parado); escala por `sensitivity` (multiplicador
  default 1.0; delta 1.0 de percurso do touchpad ≈ 1.0 → injetado * `cursorScale`).
- **Tap = clique:** finger-down → finger-up em até `tapWindowMs` (250) com deslocamento
  total < `tapMoveDeadzone` (0.08) → `tap=true` no UP. Repetição de toque mantém clique
  (toque duplo = 2 cliques, sem debounce — decisão registrada).
- **Estado por device** (V6): `TouchpadState` é mutável e vive no forwarder keyed por
  deviceId; `removeDevice` mata a entrada.
- Config via `TouchpadConfig(sensitivity: Float, deadzone: Float, tapWindowMs: Long)` —
  default vindo do PrefManager (global; per-device é follow-up — UI por device fica no
  remap, spec futuro).

### 1.2 Adapter: `RawTouchInput` (gamepad/mapping/RawTouchInput.kt)

Record puro: `data class RawTouchInput(deviceId, source, action, x, y)`.
`AndroidInputAdapter.toRawTouch(event: MotionEvent): RawTouchInput?` — retorna null
quando o evento não é de touchpad (sem AXIS_X/AXIS_Y válidos ou sem CLASS_POINTER);
x/y = `getAxisValue(AXIS_X/AXIS_Y)` normalizados `coerceIn(0f, 1f)`.

### 1.3 Forwarder app-scoped: `GamepadTouchpadForwarder` (gamepad/GamepadTouchpadForwarder.kt)

Classe app-scoped (par do hub), instanciada no `PluviaApp` (`gamepadTouchpad`):
- `fun onRawTouch(raw: RawTouchInput): Boolean` — chamado pelo MainActivity NO PONTO
  DO GATE (antes do consume); retorna true quando consumiu (sempre, se o device é
  TOUCHPAD do hub e o gate está ativo) para o dispatch não repassar.
- Sink de injeção: interface fina `TouchpadMouseSink { fun move(dx: Int, dy: Int);
  fun click(); }` — implementação Android em `XServerTouchpadMouseSink` (usa
  `PluviaApp.xServerView?.getxServer()`: `injectPointerMoveDelta` +
  `injectPointerButtonPress/Release(BUTTON1)`), trocada dinamicamente quando o
  container roda. Sem XServer → sink no-op (touchpad só atua no jogo).
- Gate lógico: `PrefManager.gamepadTouchpadMouseEnabled` (default false) + device do
  hub classificado TOUCHPAD (ou CONTROLLER com CLASS_POINTER no evento).
- Consumo do touchpad NUNCA toca `onKey/onAxis` do hub (V3: fonte própria, fora do
  dispatch lógico; chamada síncrona no dispatch — sem coroutine — só acumula estado e
  injeta delta; o timer de 60 FPS NÃO é usado aqui: o delta é injetado por evento
  (touchpad manda ~60-100 Hz) — decisão registrada, sem thread extra).

### 1.4 Wiring no MainActivity (V7 — o gate vira ROTEADOR)

Em `dispatchGenericMotionEvent` (636-641) e `dispatchKeyEvent` (582-587):
- ANTES do `return true` do gate: `PluviaApp.gamepadTouchpad.onRawTouch(...)` quando
  `PrefManager.gamepadTouchpadMouseEnabled`.
- O consume do gate CONTINUA para navegação/jogo — nenhum outro caminho é criado.
- O touchpad de um device TOUCHPAD já é excluído do lógico do hub (decisão Onda 2).

### 1.5 Prefs + UI

- `PrefManager.gamepadTouchpadMouseEnabled` (default false) + `gamepadTouchpadSensitivity`
  (default 1.0f, slider 0.25–3.0).
- Seção Gamepad (SettingsGroupGamepad): switch "Touchpad do controle → mouse" +
  slider de sensibilidade (padrão `GamepadSettingsSliderRow`).

### 1.6 Harness (V12 — verbos touch)

`DebugGamepadInput.kt`: verbos novos:
- `touch:x:y` — MotionEvent ACTION_MOVE com source `SOURCE_JOYSTICK|SOURCE_CLASS_POINTER`
  (exercita o caminho do gate ANTES do consume).
- `touchdown:x:y` / `touchup:x:y` — ACTION_DOWN/UP (tap).
- `touchtap` — shorthand `touchdown:0.5:0.5` + `touchup:0.5:0.5`.
Documentado no KDoc do arquivo (protocolo).

### 1.7 Strings

EN + pt-rBR: `gamepad_touchpad_mouse_title`, `gamepad_touchpad_mouse_subtitle`,
`gamepad_touchpad_sensitivity_title` (V9).

## 2. Arquivos afetados

| Arquivo | Mudança |
|---|---|
| `gamepad/processing/TouchpadProcessor.kt` (novo) | lógica pura (1.1) |
| `gamepad/mapping/RawTouchInput.kt` (novo) | record puro |
| `gamepad/mapping/AndroidInputAdapter.kt` | `toRawTouch` (1.2) |
| `gamepad/GamepadTouchpadForwarder.kt` (novo) | forwarder + sink (1.3) |
| `PluviaApp.kt` | instancia `gamepadTouchpad` |
| `MainActivity.kt` | ponto do gate chama o forwarder (1.4) |
| `PrefManager.kt` | keys (1.5) |
| `ui/screen/settings/SettingsGroupGamepad.kt` | switch + slider (1.5) |
| `ui/component/DebugGamepadInput.kt` | verbos touch (1.6) |
| `res/values*/strings.xml` | chaves (1.7) |
| Testes: `TouchpadProcessorTest.kt` (novo) | delta, deadzone, tap, janela de tap, estado por device |

## 3. Verificação

### 3.1 JVM
- `TouchpadProcessorTest`: delta com/sem deadzone; escala de sensibilidade; tap curto =
  clique, tap longo ≠; dedo parado = delta 0; sequência multi-amostra.

### 3.2 On-device (pendente — sem dispositivo)
- Harness `touch:` move o cursor no jogo (Silksong); `touchtap` clica; gate continua
  bloqueando fantasma na navegação (V7/V8); com pref OFF nada muda (byte-identical);
  remoção do device zera estado (V6).

## 4. Fora de escopo
- Gestos multi-dedo (2 dedos = scroll) — follow-up.
- Sensibilidade por device — follow-up (remap).
- `InputEvent.TouchpadMotion` continua stub (o forwarder injeta direto; o evento lógico
  fica para consumidores futuros — V4).
