# Spec 2026-08-14 — U3+U4: Action Layers completas (chords/toggles) + remap aplicado ao jogo

**Data:** 2026-08-14
**Origem:** doc de intuito (spec 2026-08-14-gamepad-intuito-validacao-upgrades.md, U3 e
U4). U3 = motor de ativação de camadas (hold/toggle/duplo-toque) + UI além de DEFAULT;
U4 = o remap passa a valer no JOGO (PhysicalControllerHandler consulta o perfil
universal ANTES de injetar). U4 depende do gate ON (kill-switch) — com gate OFF o
caminho do jogo fica byte-identical (V10).

---

## 0. Estado atual

- Modelo: `GamepadProfile.layers: Map<String, Map<String, String>>` (nomes arbitrários
  de camada; serialização via GamepadBindingCodec) + `ActionLayer.DEFAULT/MENU`.
- Merge por BLOCO: `GamepadProfileStore.merged` — jogo substitui `layers` inteiro quando
  não-vazio (decisão do intuito U3(c): vira GRANULAR — jogo sobrescreve só as camadas
  que define; mudança de SEMÂNTICA versionada aqui, não de schema).
- Remap só edita DEFAULT (GamepadRemapDialog) e nada aplica no jogo: o
  `PhysicalControllerHandler` aplica só deadzone (`applyProfileDeadzone`); bindings do
  jogo continuam no `ExternalControllerBinding` (com.winlator).

## 1. Design — U3 (motor)

### 1.1 Regras no perfil (V1 já implementado — campos novos preservados)

```kotlin
@Serializable
data class LayerTriggerSpec(
    val button: String,          // GamepadButton.name
    val mode: LayerTriggerMode,  // HOLD / TOGGLE / DOUBLE_TAP
    val doubleTapMs: Int = 250,  // janela do duplo-toque
)
enum class LayerTriggerMode { HOLD, TOGGLE, DOUBLE_TAP }
// GamepadProfile:
val layerTriggers: Map<String, LayerTriggerSpec> = emptyMap(),  // layerName → trigger
```
`layerTriggers` é o ÚNICO ponto de ativação (nada de heurística por nome).

### 1.2 Motor puro: `LayerResolver` (gamepad/layers/LayerResolver.kt)

`object` puro (V5), estado por device (V6):
```kotlin
class LayerState {  // mutável, vive no hub keyed por deviceId, morto em removeDevice
    var activeLayer: String? = null        // null = DEFAULT
    var heldAt: Long = 0; var holdArmed = false
    var lastTapAt: Long = 0
}
fun onButtonDown(state, trigger, nowMs): LayerChange?   // ativa/desativa
fun onButtonUp(state, trigger, nowMs): LayerChange?
data class LayerChange(val layer: String, val active: Boolean)
```
- **HOLD:** down → ativa; up → desativa (e recenter de gyro se a camada desativada... não — fora de escopo).
- **TOGGLE:** down (repeat 0) → inverte.
- **DOUBLE_TAP:** primeiro down arma; segundo down dentro de `doubleTapMs` → ativa
  (toggle); up após 2 taps → mantém ativa até próximo toggle (ou up do segundo tap
  desativa? decisão: TOGGLE — 2 taps = liga/desliga alternado).
- Regra: uma camada ativa por vez; ativar outra desativa a anterior. DEFAULT nunca é
  uma "camada" no resolver (é o estado sem camada).

### 1.3 Hub aplica layers aos eventos lógicos

`GamepadHub.onKey` (caminho lógico, gate ON):
1. Resolve o trigger da camada cujo `button` casou (ButtonDown/Up) → `LayerResolver`
   atualiza `activeLayer` do device.
2. Depois, TODO ButtonDown/Up/AxisMotion é REMAPEADO pela camada ativa ANTES do emit:
   `layers[activeLayer]` (ou DEFAULT quando sem camada) contém
   `buttonName → bindingCodec`; binding de tecla → o ButtonDown vira o botão ALVO
   (tradução `RawBinding.Key` → `GamepadButton` via mapping reverso); binding de eixo
   → emit `AxisMotion` do eixo alvo com valor ±1 (pressionado); sem binding na camada
   → evento original (a camada só sobrepõe o que define).
3. O estado de ativação do botão do gyro (U1) usa o botão PÓS-remap (consistência:
   o usuário remapeou o botão físico).
- `GamepadProfileStore.merged`: merge de `layers` GRANULAR — `base.layers + override.layers`
  (jogo adiciona/substitui só as camadas que define); `layerTriggers` idem.

### 1.4 UI — GamepadRemapDialog com camadas

- Seletor de camada no topo (DEFAULT + camadas existentes + "Nova camada…" — prompt
  textual simples via diálogo de texto existente? decisão: nome padrão "Camada N" com
  rename follow-up).
- Botão "Trigger" na linha da camada selecionada (abre capture mode para escolher o
  botão + escolha de modo HOLD/TOGGLE/DOUBLE_TAP via 3 linhas `gamepadSelectable`).
- Binding por camada: a lista de botões edita a camada selecionada (mesmo fluxo atual).
- Remover camada (botão na linha da camada; DEFAULT não removível).

## 2. Design — U4 (remap no jogo)

### 2.1 `PhysicalControllerHandler` consulta o perfil universal (gate ON)

Em `onKeyEvent` e `onGenericMotionEvent`, ANTES de injetar o binding do
`ExternalControllerBinding`:
1. `hub.profileFor(deviceId, activeAppId)` + camada ativa do resolver (U3) → binding
   serializado `layers[activeLayer][buttonName]` (ou DEFAULT) via
   `GamepadBindingCodec.decode`.
2. **Precedência (decisão do intuito U4(b)):** binding EXPLÍCITO na camada universal
   vence; sem binding → `ExternalControllerBinding` (caminho byte-identical).
3. Binding universal de TECLA: `RawBinding.Key(keyCode)` → resolve o `Binding` do
   `ExternalController` para AQUELE keycode (`controller.getControllerBinding(keyCode)`)
   e injeta via `handleInputEvent` (mesmo fluxo); `RawBinding.Axis(axis, dir)` → o
   binding do eixo correspondente (`ExternalControllerBinding.getKeyCodeForAxis`).
4. O lookup de remap usa o CACHE do hub (M1) — nunca disco no caminho do jogo (V2).
5. Aplicação: o evento físico ORIGINAL é consumido quando o binding universal existe
   (o remap substitui); sem remap → fluxo atual.
- `hub.activeLayerFor(deviceId): String?` exposto (o resolver vive no hub).

## 3. Arquivos afetados

| Arquivo | Mudança |
|---|---|
| `gamepad/layers/LayerResolver.kt` (novo) | motor puro (1.2) |
| `gamepad/layers/LayerTriggerSpec.kt` (novo) | modelo serializável (1.1) |
| `gamepad/profiles/GamepadProfile.kt` | `layerTriggers` (1.1) |
| `gamepad/profiles/GamepadProfileStore.kt` | merge granular (1.3) |
| `gamepad/GamepadHub.kt` | resolver por device + remap lógico + `activeLayerFor` (1.3) |
| `gamepad/remap/GamepadRemapDialog.kt` | seletor de camada + trigger + remover (1.4) |
| `ui/screen/xserver/PhysicalControllerHandler.kt` | lookup universal antes do inject (2.1) |
| `res/values*/strings.xml` | chaves (camada, trigger, modos) |
| Testes: `LayerResolverTest.kt` (novo), `GamepadProfileStoreTest` (merge granular) | |

## 4. Verificação

### 4.1 JVM
- `LayerResolverTest`: HOLD ativa/desativa; TOGGLE inverte; DOUBLE_TAP na janela;
  fora da janela não ativa; uma camada por vez; estado morto em remove.
- Remap lógico: camada remapeia FACE_BOTTOM→FACE_LEFT; sem camada = original;
  binding de eixo vira AxisMotion.
- Merge granular: jogo com 1 camada não apaga as do device.
- `PhysicalControllerHandler` não é testável em JVM (Android) — auditoria de código
  (V2: sem disco no caminho) + suíte filtrada.

### 4.2 On-device (pendente)
- Camada HOLD (ex.: segurar L2 → camada "Sprint" remapeia A→B no jogo); toggle;
  duplo-toque; UI de camadas navegável por gamepad; gate OFF = jogo byte-identical.

## 5. Fora de escopo
- Expressões/condições por camada (Steam Input full) — dados puros hoje, DSL textual
  recusada (intuito Parte II).
- Rename de camada (follow-up cosmético).
- Macro/sequências — fora de escopo até U3 existir (intuito não-objetivos; U3 existe agora — reavaliar em spec futuro).
