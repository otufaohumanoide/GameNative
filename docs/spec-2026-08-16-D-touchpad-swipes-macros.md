# Spec 2026-08-16 D — Touchpad: swipes direcionais → macros / abrir radial

**Data:** 2026-08-16
**Origem:** roadmap UX (o touchpad do DS4 vira 8 atalhos sem pausar o jogo —
gesto natural do Steam Deck/DualSense). Depende do modelo final de macros do
radial (fase F) — por isso vem DEPOIS.
**Executor:** sub-agente autônomo. Leia `AGENTS.md` + master roadmap §2.
Spec autocontido.

## 0. Estado atual

- `gamepad/processing/TouchpadProcessor.kt` (PURO, JVM-testado): amostras
  absolutas [0..1] → deltas + gestos. Máquina `idle → tapCandidate → dragging`;
  tap (≤250 ms, desloc ≤0.03); arrasto (segurar ≥650 ms → BUTTON_LEFT contínuo);
  duplo-toque opt-in (2 taps ≤250 ms, dist ≤0.06 → clique direito); dead zone de
  pós-toque 100 ms. `TouchpadDecision(deltaX, deltaY, tap, dragPress,
  dragRelease, rightClick)`.
- `GamepadTouchpadForwarder` (gate do MainActivity, P5-6) consome as decisões →
  `XServerTouchpadMouseSink`.
- `RadialMenuExecutor`/`RadialMenuPlan` (pós-fase F: macros com turbo/ícones) e
  `RadialMenuHost` (abre por `GamepadLayerEvent`).

## 1. Design

### 1.1 Swipe no `TouchpadProcessor` (PURO — decisão nova)

- Config nova (`TouchpadConfig`): `swipeEnabled: Boolean = true`,
  `swipeMinDistance: Float = 0.22f` (normalizado), `swipeMaxMs: Long = 300L`.
- Regra de decisão NO UP do dedo (antes das regras de tap/duplo-toque):
  **swipe** ⇔ duração down→up ≤ `swipeMaxMs` E deslocamento total ≥
  `swipeMinDistance`. Swipe **SUPRIME** tap/drag-release/rightClick daquele
  up (um gesto, uma decisão — princípio da máquina atual).
- Direção: 8 setores via ângulo do vetor (start→end) — reusar
  `RadialMenuGeometry.angleOf` + `sectorIndex(count=8)` (mesma convenção
  0°=cima). `SwipeDir` enum: UP, UP_RIGHT, RIGHT, DOWN_RIGHT, DOWN, DOWN_LEFT,
  LEFT, UP_LEFT.
- `TouchpadDecision` ganha `swipe: SwipeDir?` (null default — chamadores
  existentes byte-identical).
- Não interfere: drag (≥650 ms) nunca é swipe (janela ≤300 ms); duplo-toque
  exige 2 taps PARADOS (desloc ≤0.06 ≪ 0.22) — desambiguação estrutural,
  testada.

### 1.2 Perfil (schema — null default, V1 preservado)

`GamepadProfile` + `touchpadSwipes: Map<String, List<RadialMacroKey>>? = null`
(keys = nomes do `SwipeDir`; `null`/vazio = OFF). `isDefault()`/`merged()`
atualizados (merge: união por direção, override vence).

### 1.3 Forwarder → ação

`GamepadTouchpadForwarder`: `decision.swipe != null` → lookup no perfil do
device →
- valor = lista de `RadialMacroKey` → `RadialMenuExecutor.execute(keys,
  deviceId, activity)` (o jogo NÃO pausa — macros caem no caminho do jogo,
  igual ao radial pós-execução);
- valor = lista com 1 `RadialMacroKey(keyCode = KEYCODE RadicalOpen)` —
  constante especial `SWIPE_OPEN_RADIAL` → emite novo evento bus
  `GamepadSwipeEvent(deviceId)` consumido pelo `RadialMenuHost` (abre o menu
  com pause/resume par-e-par existente, sem exigir camada configurada).
- Sem binding para a direção: swipe vira nada (nem mouse delta — o dedo já
  levantou; deltas do percurso continuam fluindo normalmente DURANTE o move).

### 1.4 UI

No `GamepadRemapDialog`, se `device.hasTouchpad`: seção "Swipes" — 8 linhas
(direção → ação) com captura de macro (reuso do editor do radial) + opção
"Abrir radial". Strings EN+pt-rBR.

## 2. Arquivos afetados

| Arquivo | Mudança |
|---|---|
| `gamepad/processing/TouchpadProcessor.kt` | swipe puro (1.1) |
| `gamepad/profiles/GamepadProfile(+Store).kt` | `touchpadSwipes` (1.2) |
| `gamepad/GamepadTouchpadForwarder.kt` | dispatch de swipe (1.3) |
| `events/GamepadBusEvents.kt` | `GamepadSwipeEvent` (1.3) |
| `ui/component/radial/RadialMenuHost.kt` | listener do swipe→abrir (1.3) |
| `gamepad/remap/GamepadRemapDialog.kt` | seção Swipes (1.4) |
| `res/values*/strings.xml` | chaves |
| `app/src/test/.../TouchpadProcessorTest` | + casos: swipe 8 direções; swipe vs tap (rápido parado); swipe vs drag (lento longo); swipe suprime rightClick; swipe OFF = decisão idêntica à atual |

## 3. Verificação (gate)

```
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:testModernDebugUnitTest --tests "*Touchpad*"
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:assembleModernDebug
```
On-device (humano, "on-device pendente"): swipe rápido p/ direita = macro; lento
longo continua arrasto; duplo-toque parado continua clique direito; swipe
configurado p/ radial abre/fecha com pause par-e-par; harness `touch:`
sintético exercita os mesmos caminhos.

Consolidado (fechamento 2026-08-16, §2 linha D — protocolo único do roadmap):
`setprop debug.gamenative.input touch:0.5:0.5` + swipe sintético rápido vs
arrasto lento → macro/radial dispara; arrasto/duplo-toque intactos.
**Status: on-device pendente.**

## 4. Fora de escopo

Swipes de 2 dedos, swipe-during-drag (dedo que arrasta e acelera no fim),
sensibilidade de swipe configurável (constants v2), swipes no touch SCREEN do
celular.
