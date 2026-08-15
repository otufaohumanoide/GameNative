# Spec 2026-08-13 — Suporte universal a gamepads (camada de abstração própria)

**Data:** 2026-08-13
**Origem:** pedido do usuário — suporte universal a qualquer joystick (DS4, DualSense,
Xbox, Switch Pro, 8BitDo, genéricos) com: mapeamento consistente por vendor/product,
hotplug robusto, deadzones por dispositivo, perfis por dispositivo e por jogo,
remapeamento com UI e navegação de menu por gamepad.
**Referências técnicas:** SDL (`reference/SDL/src/joystick/SDL_gamepad.c`, zlib —
adaptável), `SDL_GameControllerDB/gamecontrollerdb.txt` (formato de mapeamento, zlib),
retroarch-joypad-autoconfig (nomenclatura/conceitos), Dolphin/DuckStation/PPSSPP/
key-mapper/moonlight (conceitos, GPL — **só leitura, nada copiado**), AndroidX
games-controller (Apache-2.0; o checkout `reference/androidx` em androidx-main **não
contém mais** o módulo — a API nativa `InputManager.InputDeviceListener` já cobre).
**Decisões do usuário (registradas em sessão):**
1. Implementação do zero em Kotlin; nenhuma biblioteca de gamepad de terceiros.
2. Modelo de dados já nasce com `ActionLayer` (DEFAULT/MENU) — layers completas
   (chords/toggles editáveis), gyro e touchpad ficam como follow-ups fora deste spec.
3. `InputEvent` nasce com stubs `SensorUpdate`/`TouchpadMotion` (custo zero, evita
   refatoração futura).
4. Touchpad do DS4 fora de escopo (o gate de ghost input existente é o ponto de plug
   futuro).
5. Este spec **não implementa** — define a missão e o design de subagentes para o
   usuário validar antes do código.

---

## 0. Contexto — o que já existe e é REUSADO (não reinventar)

| Componente | Localização | Reuso |
|---|---|---|
| Bus síncrono multicast, registry por identidade (`===`), `listenerCount()` | `events/EventDispatcher.kt` | Transporte dos eventos lógicos; hot path sem coroutine |
| Gate de ghost input (touchpad) | `MainActivity.kt:592-604, 645-653` | Fica intacto; ponto de plug futuro do touchpad |
| `InputDeviceListener` duplicado | `MainActivity.kt:144-156` + `XServerScreen.kt:1464-1495` | **Consolidado** no `GamepadHub` (F0); removido do XServerScreen (alivia o dex) |
| Classificação de controller (descriptor id, trigger type, STICK_DEAD_ZONE 0.15f) | `com.winlator.inputcontrols.ExternalController` | Base conceitual do `DeviceClassifier`; fallback de deadzone do jogo |
| Holder de estado vivo (lido no call time, nunca capturado) | `ui/component/OverlayInputContext.kt` (`OverlayInputState`) | Padrão do hub e dos perfis (lição C1 do hardening) |
| Lógica pura de stick (re-arm < deadzone — RC1) | `ui/component/GamepadStickLogic.kt` | Núcleo do `AnalogToDpad` (delegação, não reimplementação) |
| Dedupe DPAD×hat + relógio de navegação | `ui/component/GamepadMoveDedupe.kt`, `GamepadNavigationClock` | Intactos; passam a operar sobre eventos lógicos |
| Consumidores de menu (bus + view) | `ui/component/GamepadBusInput.kt`, `JoystickFocusNavigator.kt`, `GamepadKeyBridge.kt` | Migram raw → lógico na Onda 2 (agente E, único dono) |
| Caminho do jogo | `ui/screen/xserver/PhysicalControllerHandler.kt` | Raw até a Onda 2; depois deadzone por device |
| Framework visual de foco | `GamepadFocus.kt`, `GamepadModifiers.kt`, `GamepadActionBar.kt`, `InputIcons.kt` | Reuso total na UI de remap/glyphs |
| Store JSON atômico por escopo | `shaders/PerGameShaderStore.kt` | Template do `GamepadProfileStore` |
| Global settings (DataStore) | `PrefManager.kt` | Deadzone padrão, swap OK/Cancel, feature gate |
| Testes JVM de lógica pura (`object` sem android.*) | `app/src/test/java/app/gamenative/ui/component/*` | Metodologia obrigatória para todas as fases |
| Verificação on-device | `DebugGamepadInput.kt` (harness `debug.gamenative.input`) + `tools/quickmenu-verify.sh` | Estendidos na Onda 2 |

**Insight de plataforma:** no Android, `KEYCODE_BUTTON_*`/`AXIS_*` já chegam
normalizados pelo framework (`.kl` files) para os pads populares. O MappingDatabase só
precisa cobrir onde a normalização falha: devices **SOURCE_JOYSTICK sem SOURCE_GAMEPAD**
(genéricos DInput), quirks de triggers (axis vs botão), variações de hat e **face
style** (Xbox/Nintendo/Sony) para OK/Cancel e labels. **NÃO importar
`gamecontrollerdb.txt` inteiro** (2.256 entradas desktop, ~95% irrelevante em Android).

---

## 1. Arquitetura alvo

```
Android (KeyEvent/MotionEvent)
  → MainActivity.dispatch*  [gate de ghost intacto]
  → PluviaApp.events (RAW) ──→ jogo/PhysicalControllerHandler (intocado até a Onda 2)
  └→ GamepadHub (NOVO — único InputDeviceListener)
       ├─ Registry: deviceId → GamepadDevice (id estável = descriptor)
       ├─ StateFlow<Map<Int, GamepadDevice>>   (UI reativa — NÃO toca o hot path)
       ├─ StateFlow<GamepadDevice?> (device preferido)
       ├─ Emite DeviceAdded/DeviceRemoved no bus
       └─ EventTranslator (puro): raw → InputEvent lógico
            ├─ MappingDatabase (vendor+product, estilo SDL, ~15 entradas)
            ├─ DeadzoneProcessor (radial/axial + histerese, por device)
            └─ AnalogToDpad (delega em GamepadStickLogic)
       → bus (LÓGICO: GamepadInputEvent/GamepadDeviceAddedEvent/...) → UI
```

**Regras de latência:** tradução síncrona na thread de dispatch; zero coroutine no
caminho tecla→ação; `StateFlow` apenas para estado observável da UI (conexão/perfil).
Overhead alvo de tradução < 1 ms (os < 16 ms end-to-end incluem polling BT, fora do
nosso controle).

---

## 2. Design de subagentes — ondas e ownership

**Princípio anti-interferência:** agentes da mesma onda NUNCA editam o mesmo arquivo.
Arquivos novos são de propriedade exclusiva de um agente; arquivos existentes têm dono
único declarado. A Onda 0 aterra primeiro e congela os contratos da §3 (qualquer desvio
de assinatura = rework de todos os agentes da Onda 1; desvios só via revisão do spec).

```
ONDA 0 (serial, 1 agente)      ONDA 1 (paralela, 4 agentes)     ONDA 2 (serial, 1 agente)
┌─────────────────────────┐    ┌──────────────────────────┐    ┌──────────────────────────┐
│ F0 — Foundation         │───▶│ A — Mapping & Translation│    │ E — Integração          │
│  (model, classifier,    │    │ B — Processing           │───▶│  (migração dos consumi- │
│   hub, wiring, gate)    │    │ C — Profiles & Storage   │    │   dores existentes +    │
└─────────────────────────┘    │ D — Remap UX & Glyphs    │    │   caminho do jogo)      │
        congela §3              └──────────────────────────┘    └──────────────────────────┘
                                (só arquivos NOVOS em          (único dono dos arquivos
                                 app/gamenative/gamepad/        quentes existentes)
                                 + testes próprios)
```

### Onda 0 — F0 (Foundation)

**Objetivo:** modelo de dados, classificação, hub e wiring mínimo no MainActivity.
Aterra, compila (`assembleModernDebug`), e congela a API da §3.

**Arquivos novos (dono exclusivo):**
- `app/src/main/java/app/gamenative/gamepad/GamepadButton.kt` — enum da §3.
- `app/src/main/java/app/gamenative/gamepad/GamepadAxis.kt` — enum da §3.
- `app/src/main/java/app/gamenative/gamepad/GamepadDevice.kt` — data class + `DeviceClass` + `FaceStyle` da §3.
- `app/src/main/java/app/gamenative/gamepad/InputEvent.kt` — sealed interface da §3 (com stubs Sensor/Touchpad).
- `app/src/main/java/app/gamenative/gamepad/GamepadBusEvents.kt` — wrappers de Event do bus: `GamepadInputEvent(input): Event<Boolean>`, `GamepadDeviceAddedEvent(device): Event<Unit>`, `GamepadDeviceRemovedEvent(deviceId): Event<Unit>`.
- `app/src/main/java/app/gamenative/gamepad/DeviceClassifier.kt` — adapta a lógica de `ExternalController.isGameController` (SOURCE_GAMEPAD+keys | SOURCE_JOYSTICK+axes), exclui `isVirtual`, classifica CONTROLLER/TOUCHPAD/SENSOR/UNKNOWN via sources + motionRanges. Lógica pura em `object` (entrada = dados puros, não `InputDevice`) + adapter fino.
- `app/src/main/java/app/gamenative/gamepad/GamepadHub.kt` — ver contrato §3.4.
- `app/src/test/java/app/gamenative/gamepad/DeviceClassifierTest.kt` — classificação pura (JVM).

**Arquivos existentes que F0 pode editar (somente F0):**
- `MainActivity.kt` — remove `controllerDeviceListener` (:144-156, :216, :385), cria/starta/stopping o hub no ciclo de vida, chama `hub.onKey`/`hub.onAxis` no fim de `dispatchKeyEvent`/`dispatchGenericMotionEvent` (após o emit raw, sem mudar a semântica de retorno), log `GamepadTrace` estendido (vendorId, productId, descriptor, class).
- `PrefManager.kt` — NOVAS keys globais (todas já criadas aqui, consumidas pelas outras ondas):
  `gamepadUniversalEnabled` (Boolean, default false — gate), `gamepadStickDeadzone` (Float, 0.15f), `gamepadMenuStickDeadzone` (Float, 0.45f), `gamepadSwapOkCancel` (Boolean, false).

**Não pode tocar:** `XServerScreen.kt`, `GamepadBusInput.kt`, strings, qualquer outro arquivo existente.

**Aceite F0:** hub registra listener único; `DeviceAdded`/`DeviceRemoved` emitidos no bus; `ControllerManager.onDeviceConnected/Disconnected` preservado; `listenerCount()` de KeyEvent/MotionEvent estável; gate `gamepadUniversalEnabled=false` ⇒ nenhum evento lógico é emitido no bus (consumidores ainda não existem); DeviceClassifierTest verde; `assembleModernDebug` OK.

---

### Onda 1 — paralela (A, B, C, D)

Regra comum: **somente arquivos novos** em `app/gamenative/gamepad/` (subpacotes
`mapping/`, `processing/`, `profiles/`, `glyphs/`) + testes próprios em
`app/src/test/java/app/gamenative/gamepad/...`. Nenhum agente edita arquivo de outro
agente, nem qualquer arquivo existente do app. Compilam contra a API congelada da §3.

#### A — Mapping & Translation

**Arquivos novos:**
- `gamepad/mapping/GamepadMapping.kt` — data class + `RawBinding` da §3.1.
- `gamepad/mapping/MappingParser.kt` — `object` puro, gramática SDL-like (`a:b0,leftx:a0,dpup:h0.1,...`, inspirado em `SDL_PrivateParseGamepadConfigString`, SDL_gamepad.c:1802 — zlib, adaptado).
- `gamepad/mapping/MappingDatabase.kt` — `object` puro com entradas built-in (formato de string SDL-like internamente, exportado como `Map<String, GamepadMapping>` keyed por `mappingKey` = vendor+product hex 8 minúsculo):
  - DS4: `054c:09cc`, `054c:05c4`; DualSense: `054c:0ce6`
  - Xbox 360/One/Series: `045e:028e`, `045e:02d1`, `045e:0b12`
  - Switch Pro: `057e:2009`
  - 8BitDo SN30 Pro/Pro+/Ultimate: `2dc8:6001`, `2dc8:9002`, `2dc8:3106`
  - Genéricos DInput (JOYSTICK-only, axes 0/1/3/4 + triggers ±): 2–3 perfis genéricos cobrindo variações de hat
  - `defaultAndroidMapping(faceStyle)`: identity dos KEYCODE_BUTTON_* (fallback)
  - Cada entrada declara `faceStyle` (XBOX/PLAYSTATION/NINTENDO/GENERIC).
- `gamepad/mapping/RawInput.kt` — records puros `RawKeyInput`/`RawAxisInput` da §3.1 (sem android.*).
- `gamepad/mapping/AndroidInputAdapter.kt` — conversão fina `KeyEvent`/`MotionEvent` → records (não testado em JVM; testado on-device).
- `gamepad/mapping/EventTranslator.kt` — `object` puro `translateKey`/`translateAxis` da §3.1.
- Testes JVM: `MappingParserTest` (gramática: campos completos, hat com direção, axis com direção, linha malformada ⇒ null, tolerância a campos extras), `EventTranslatorTest` ("DS4 X físico ⇒ FACE_BOTTOM", "DInput axes 0/1/3/4 ⇒ LEFT_X/LEFT_Y/RIGHT_X/RIGHT_Y", "trigger axis vs trigger botão", "hat up ⇒ DPAD_UP", "device desconhecido ⇒ defaultAndroidMapping").

**Aceite A:** testes verdes; `assembleModernDebug` OK; nenhum arquivo fora do subpacote `mapping/` + testes.

#### B — Processing

**Arquivos novos:**
- `gamepad/processing/DeadzoneProcessor.kt` — `object` puro da §3.2 (radial + axial, histerese, rescalonamento).
- `gamepad/processing/AnalogToDpad.kt` — `object` puro da §3.2; **delega a decisão de estado em `GamepadStickLogic.decide`** (preserva RC1/re-arm); converte amostra pós-deadzone → `(magnitude, GamepadStickDirection?)`.
- Testes JVM: `DeadzoneProcessorTest` (radial dentro/fora, histerese de saída < deadzone, rescalonamento 0..1, axial por eixo, triggers), `AnalogToDpadTest` (delegação: mesmos casos do GamepadStickLogicTest via wrapper, hat −1/+1, threshold).

**Aceite B:** testes verdes; `GamepadStickLogic` **não modificado**; `assembleModernDebug` OK.

#### C — Profiles & Storage

**Arquivos novos:**
- `gamepad/profiles/GamepadProfile.kt` — `@Serializable` data class da §3.3 (com `ActionLayer`).
- `gamepad/profiles/GamepadProfileStore.kt` — padrão `PerGameShaderStore` (JSON atômico tmp+rename, malformado degrada a vazio); dois escopos na mesma classe ou dois stores: device (key = vendor+product+descriptor) e game (key = appId); `merged(device, game)` função pura (game vence, null preserva o de baixo).
- `gamepad/profiles/ProfileResolver.kt` — resolve o perfil efetivo de um `GamepadDevice` no momento do evento (lê o holder vivo — padrão `OverlayInputState`).
- Testes JVM com **arquivo temporário real** (padrão dos testes de store existentes): save/load/remove, default remove entrada, merge device→game com e sem game, malformed ⇒ vazio.

**Aceite C:** testes verdes; API compatível com §3.3; `assembleModernDebug` OK.

#### D — Remap UX & Glyphs

**Arquivos novos:**
- `gamepad/glyphs/GamepadGlyphProvider.kt` — mapeia `GamepadButton` × `FaceStyle` → glyph/label EN (funções puras + `@Composable GamepadGlyph(...)` que reusa `InputIcons`/`GamepadModifiers` quando fizer sentido; na falta de ícone, text label).
- `gamepad/remap/GamepadRemapDialog.kt` — diálogo (janela separada, padrão `GamepadFocusScope` — NUNCA navigators de bus em janela de diálogo, regra do AGENTS.md): lista de bindings, tela "pressione para vincular" (captura botão E eixo via eventos lógicos do bus dentro do diálogo), detecção de conflito, preview do controle com destaque (`GamepadFocus`), botões de export/import.
- `gamepad/remap/GamepadProfileJson.kt` — export/import JSON (formato próprio declarado na §3.3; **não copiar** formato da key-mapper/GPL).
- `gamepad/remap/GamepadProfileStrings.kt` — catálogo central de TODAS as strings novas do spec (usado pelos demais agentes; evita disputa de strings.xml).
- Strings em `app/src/main/res/values/strings.xml` + `values-pt-rBR/strings.xml` — **D é o único agente que edita strings.xml**; adiciona todas as chaves deste spec (§5) de uma vez (EN + pt-rBR).
- Testes JVM: conflito de binding, round-trip export/import, label por face style.

**Aceite D:** diálogo compila e abre com `GamepadFocusScope`; round-trip JSON verde; strings em EN+pt-rBR; nenhum arquivo fora do subpacote + strings.xml.

---

### Onda 2 — E (Integração, serial, um agente só)

**Objetivo:** ligar tudo aos consumidores existentes. É serial porque todos os arquivos
aqui são quentes/compartilhados; dividir entre agentes criaria conflitos de merge e
regressões cruzadas. E executa em ordem: E1 (menu) → E2 (jogo) → E3 (verificação),
commitando ao fim de cada passo (commits PT-BR `feat(gamepad): ...`).

**E1 — Migração do menu (overlays e diálogos):**
- `ui/component/GamepadBusInput.kt` — `BusJoystickFocusNavigator` e `BusGamepadKeyBridge` passam a consumir `GamepadInputEvent` do bus (mantendo `GamepadNavigationClock` e `GamepadMoveDedupe`); deadzone lida do perfil resolvido (fallback `gamepadMenuStickDeadzone`); A→DPAD_CENTER passa a respeitar OK/Cancel (`FaceStyle` do device + `gamepadSwapOkCancel` + override do perfil) — o botão de confirmação vira DPAD_CENTER e o de cancelar continua raw (padrão B atual).
- `ui/component/JoystickFocusNavigator.kt` + `GamepadKeyBridge.kt` (view-level, diálogos) — mesmas regras, via estado do hub (`StateFlow`/getter síncrono), sem re-registro.
- `XServerScreen.kt` — REMOVE o listener duplicado (:1464-1495); assina `GamepadDeviceAddedEvent`/`GamepadDeviceRemovedEvent` do bus para `scanForExternalDevices()`/`evaluateDevice` (comportamento atual preservado, dex aliviado); **nenhuma local nova na função principal** — lógica em helpers/objetos.
- `QuickMenu.kt` — se necessário, apenas call-site de parâmetro novo (deadzone), sem lógica nova.

**E2 — Caminho do jogo (atrás do teste de regressão V10):**
- `PhysicalControllerHandler.kt` — aplica deadzone por device (resolvida via hub; `ExternalController.STICK_DEAD_ZONE` 0.15f vira fallback). Nada mais muda.
- Ativa o gate `gamepadUniversalEnabled` (ou remove o gate se a revisão preferir) somente após E1+E2 passarem na verificação on-device.

**E3 — Verificação e ferramentas:**
- `tools/quickmenu-verify.sh` — cenários novos (§6) + greps dos logs novos.
- `docs/MILESTONES.md` + `tools/milestone.sh` — entrada ao final.
- Spec de implementação (`docs/spec-2026-08-13-gamepad-universal-impl.md`) com evidências file:line.

**Aceite E:** V1–V10 do spec de hardening re-testados verdes; §6 completa; suítes existentes verdes; `assembleModernDebug` sem warnings novos.

---

## 3. Contratos congelados (a API que a Onda 1 compila contra)

> F0 entrega EXATAMENTE estas assinaturas. Mudanças exigem revisão do spec antes de
> qualquer agente da Onda 1 rodar.

### 3.1 Modelo + Mapping

```kotlin
enum class GamepadButton {
    FACE_BOTTOM, FACE_RIGHT, FACE_LEFT, FACE_TOP,          // A, B, X, Y (estilo Xbox, ordem SDL)
    DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT,
    LEFT_BUMPER, RIGHT_BUMPER, LEFT_TRIGGER, RIGHT_TRIGGER,
    LEFT_STICK, RIGHT_STICK, START, SELECT, GUIDE,
}

enum class GamepadAxis { LEFT_X, LEFT_Y, RIGHT_X, RIGHT_Y, LEFT_TRIGGER, RIGHT_TRIGGER }

enum class DeviceClass { CONTROLLER, TOUCHPAD, SENSOR, UNKNOWN }

enum class FaceStyle { XBOX, PLAYSTATION, NINTENDO, GENERIC }

data class GamepadDevice(
    val deviceId: Int,          // id Android (volátil entre sessões)
    val descriptor: String,     // id estável do device (InputDevice.getDescriptor)
    val vendorId: Int,
    val productId: Int,
    val name: String,           // somente display — NUNCA usado como chave
    val deviceClass: DeviceClass,
    val faceStyle: FaceStyle,   // do mapping ou heurística default
) {
    val mappingKey: String get() = "%04x%04x".format(vendorId, productId)
}

sealed interface InputEvent {
    data class ButtonDown(val deviceId: Int, val button: GamepadButton) : InputEvent
    data class ButtonUp(val deviceId: Int, val button: GamepadButton) : InputEvent
    data class AxisMotion(val deviceId: Int, val axis: GamepadAxis, val value: Float) : InputEvent
    data class DeviceAdded(val device: GamepadDevice) : InputEvent
    data class DeviceRemoved(val deviceId: Int) : InputEvent
    // Stubs (NUNCA emitidos nesta missão — follow-ups gyro/touchpad):
    data class SensorUpdate(val deviceId: Int, val gyroX: Float, val gyroY: Float,
        val gyroZ: Float, val accelX: Float, val accelY: Float, val accelZ: Float) : InputEvent
    data class TouchpadMotion(val deviceId: Int, val x: Float, val y: Float) : InputEvent
}

// --- mapping (agente A) ---
sealed interface RawBinding {
    data class Key(val keyCode: Int) : RawBinding
    data class Axis(val axis: Int, val direction: Int) : RawBinding   // direction ∈ {-1, 0, +1}
    data class Hat(val hat: Int, val direction: Int) : RawBinding     // hat ∈ {0..3} (SDL), direction ±1
}

data class GamepadMapping(
    val mappingKey: String,      // vendor+product hex 8
    val name: String,            // display
    val faceStyle: FaceStyle,
    val buttons: Map<GamepadButton, RawBinding>,
    val axes: Map<GamepadAxis, RawBinding>,
)

object MappingParser { fun parse(line: String): GamepadMapping? }  // null = linha inválida

object MappingDatabase {
    fun mappingFor(vendorId: Int, productId: Int): GamepadMapping?
    fun defaultAndroidMapping(faceStyle: FaceStyle): GamepadMapping  // identity KEYCODE_BUTTON_*
}

data class RawKeyInput(val deviceId: Int, val source: Int, val keyCode: Int,
    val action: Int, val repeatCount: Int)

data class RawAxisInput(val deviceId: Int, val source: Int, val action: Int,
    val axisValues: Map<Int, Float>)

object EventTranslator {
    fun translateKey(raw: RawKeyInput, mapping: GamepadMapping): List<InputEvent>
    fun translateAxis(raw: RawAxisInput, mapping: GamepadMapping,
        deadzones: DeadzoneConfig): List<InputEvent>
}
```

### 3.2 Processing (agente B)

```kotlin
enum class DeadzoneMode { RADIAL, AXIAL }

data class DeadzoneConfig(
    val leftStick: Float = 0.15f,
    val rightStick: Float = 0.15f,
    val leftTrigger: Float = 0.08f,
    val rightTrigger: Float = 0.08f,
    val mode: DeadzoneMode = DeadzoneMode.RADIAL,
    val hysteresis: Float = 0.05f,      // saída ocorre em deadzone − hysteresis
)

data class StickSample(val x: Float, val y: Float)

data class DeadzoneResult(val x: Float, val y: Float, val inDeadzone: Boolean)

object DeadzoneProcessor {
    fun process(sample: StickSample, config: DeadzoneConfig): DeadzoneResult  // sticks
    fun processAxis(value: Float, deadzone: Float): Float                     // triggers (axial)
}

// Wrapper de GamepadStickLogic (delegação — RC1 preservado):
object AnalogToDpad {
    fun sampleToDirection(
        sample: DeadzoneResult, hatX: Float, hatY: Float, deadZone: Float,
    ): GamepadStickDirection?   // hat vence; senão stick pós-deadzone
    // a decisão com estado/cooldown continua sendo GamepadStickLogic.decide(...)
}
```

### 3.3 Profiles (agente C)

```kotlin
enum class ActionLayer { DEFAULT, MENU }   // MENU resolve via OverlayInputState (graça)

@Serializable
data class GamepadProfile(
    val faceStyle: FaceStyle? = null,             // null = do mapping
    val swapOkCancel: Boolean? = null,            // null = PrefManager.gamepadSwapOkCancel
    val leftStickDeadzone: Float? = null,         // null = PrefManager.gamepadStickDeadzone
    val rightStickDeadzone: Float? = null,
    val leftTriggerDeadzone: Float? = null,
    val rightTriggerDeadzone: Float? = null,
    val layers: Map<String, Map<String, String>> = emptyMap(),
        // layerName → (GamepadButton.name → RawBinding serializado). Implementa DEFAULT
        // e MENU; chords/layers editáveis = follow-up fora deste spec.
) {
    fun isDefault(): Boolean
    fun toJson(): String
    companion object { fun fromJson(json: String): GamepadProfile? }   // null = inválido
}

class GamepadProfileStore(private val file: File) {
    fun load(key: String): GamepadProfile?
    fun save(key: String, profile: GamepadProfile)   // default REMOVE a entrada
    fun clear(key: String)
    companion object {
        fun merged(device: GamepadProfile?, game: GamepadProfile?): GamepadProfile  // game vence
    }
}
```

### 3.4 Hub (F0)

```kotlin
class GamepadHub(context: Context) {
    val connectedDevices: StateFlow<Map<Int, GamepadDevice>>
    val activeDevice: StateFlow<GamepadDevice?>      // device preferido (mesma heurística do harness gamepadDeviceId)
    fun start()   // registra InputDeviceListener + scan inicial; emite DeviceAdded no bus
    fun stop()    // unregister; emite DeviceRemoved dos vivos
    fun deviceFor(deviceId: Int): GamepadDevice?     // getter síncrono (hot path)
    fun profileFor(deviceId: Int): GamepadProfile    // resolvido (device+game+globais) no momento do evento
    fun onKey(raw: RawKeyInput): Boolean    // traduz e emite GamepadInputEvent no bus (gate-aware)
    fun onAxis(raw: RawAxisInput): Boolean  // idem; retorno NÃO altera o retorno do dispatch (multicast)
}
```

---

## 4. Regras de ownership (anti-interferência)

| Arquivo / área | Dono | Notas |
|---|---|---|
| `app/gamenative/gamepad/*` (raiz) | F0 | ninguém mais cria arquivo na raiz do pacote |
| `gamepad/mapping/**` + testes | A | exclusivo |
| `gamepad/processing/**` + testes | B | exclusivo |
| `gamepad/profiles/**` + testes | C | exclusivo |
| `gamepad/glyphs/**`, `gamepad/remap/**` + testes | D | exclusivo |
| `MainActivity.kt`, `PrefManager.kt` (novas keys) | F0 | E só CONSOME |
| `res/values/strings.xml`, `values-pt-rBR/strings.xml` | D | catálogo central em `GamepadProfileStrings.kt` (§5); E consome |
| `GamepadBusInput.kt`, `JoystickFocusNavigator.kt`, `GamepadKeyBridge.kt`, `XServerScreen.kt`, `QuickMenu.kt`, `PhysicalControllerHandler.kt`, `tools/quickmenu-verify.sh`, `docs/MILESTONES.md` | E | somente Onda 2 |
| `GamepadStickLogic.kt`, `GamepadMoveDedupe.kt`, `GamepadNavigationClock`, `GamepadHaptics.kt`, `GamepadFocus.kt`, `GamepadModifiers.kt`, `DebugGamepadInput.kt`, `EventDispatcher.kt`, `OverlayInputContext.kt`, `AndroidEvent.kt` | **ninguém** (protegidos) | leitura e consumo apenas; qualquer mudança = revisão de spec |

**Regras de processo:**
- Cada agente commita apenas seus arquivos: `feat(gamepad): ...` / `test(gamepad): ...` (PT-BR, formato do repo).
- Testes: **nunca** rodar `:app:testModernDebugUnitTest` completo (AGENTS.md — estoura 30 min). Comando por agente: `JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:testModernDebugUnitTest --tests "*gamepad*"` (+ suítes existentes `*Gamepad*` `*Shader*` `*SearchField*` no agente E).
- Compilação de gate: `JAVA_HOME=... ./gradlew :app:assembleModernDebug` após cada agente.
- Lógica pura sempre em `object` sem android.* (JVM-testável); adaptadores Android em arquivo separado fino.
- `XServerScreen.kt` está no limite do dex: E **reduz** (remove listener duplicado), nunca adiciona locals na função principal.
- Strings sempre EN (`values/`) + pt-rBR (`values-pt-rBR/`).

---

## 5. Catálogo de strings (D adiciona TODAS; chaves reservadas)

Grupos e prefixos (lista não exaustiva — D consolida em `GamepadProfileStrings.kt`):
- `gamepad_settings_*` — entrada de settings "Gamepad" (seção de deadzones, swap OK/Cancel, perfis).
- `gamepad_remap_*` — título do diálogo, "pressione para vincular", cancelar, salvar, conflito (`gamepad_remap_conflict`), exportar/importar.
- `gamepad_glyph_*` — labels por face style (ex.: `gamepad_glyph_ps_bottom` = "✕ Cross" / pt "✕ Cruz").
- `gamepad_profile_*` — por dispositivo, por jogo, aplicar, resetar.
- `gamepad_swap_ok_cancel` / `gamepad_swap_ok_cancel_subtitle` — opção RetroArch `menu_swap_ok_cancel_buttons`.
- `gamepad_deadzone_*` — sticks/triggers + subtítulo.

---

## 6. Verificação on-device (Onda 2, E3 — Mi 11 + DS4; harness `debug.gamenative.input`)

| # | Cenário | Critério |
|---|---|---|
| G1 | Conectar/desconectar 3 controles (DS4 + DualSense + 8BitDo ou genérico) durante o jogo e com menu aberto | `GamepadTrace` loga classificação correta; zero crash; `listenerCount()` estável; `ControllerManager` preservado |
| G2 | Genérico DInput (SOURCE_JOYSTICK só) | botões/axes traduzidos pelo MappingDatabase (log do tradutor); jogo recebe estado correto |
| G3 | Menu aberto com DS4 vs Xbox vs Nintendo | botão de confirmação correto por face style; swap OK/Cancel inverte; navegação 1 linha/gesto |
| G4 | Deadzone por device (perfil com 0.30 vs default) | stick do DS4 navega com 0.30; outro device segue default |
| G5 | Perfil por jogo: deadzone X no jogo A, default no B | aplicado ao trocar de container; persiste após restart do app |
| G6 | Remap: trocar FACE_BOTTOM↔FACE_TOP + conflito | "pressione para vincular" captura; conflito bloqueia com mensagem; export/import round-trip funciona |
| G7 | Regressões V1–V10 do spec 2026-08-12 (hardening) + T1–T9 do 2026-08-10 | verdes sem re-tuning |
| G8 | Latência: harness dispara botão e mede logcat (timestamps `GamepadTrace` → ação) | overhead de tradução < 1 ms (medição relativa no mesmo device) |

---

## 7. Fora de escopo / follow-ups (specs futuros)

1. **Gyro → mouse/câmera** (pedido nº 1 da comunidade) — `SensorUpdate` já modelado.
2. **Touchpad DS4/DualSense → mouse** (pedido nº 2) — `TouchpadMotion` modelado; gate de ghost input é o ponto de plug.
3. **Action Layers completas** (hold-chords, toggles, modos por jogo) — modelo `ActionLayer`/`layers` já no perfil.
4. Rumble avançado/HID (DualSense haptics) via `GamepadHaptics`.
5. Predictive Back / accessibility — caminho atual (P1/B→BackHandler) funciona; não mudar sem demanda.

---

## 8. Checklist de execução

1. Usuário valida este spec (design de subagentes + contratos §3).
2. Onda 0: F0 aterra, congela §3, compila, testes verdes → revisão rápida.
3. Onda 1: A, B, C, D em paralelo (arquivos novos apenas) → revisão de cada PR.
4. Onda 2: E1 (menu) → E2 (jogo) → E3 (verificação §6 + milestones) — serial.
5. Spec de implementação (`...-impl.md`) + entrada em `docs/MILESTONES.md` via `tools/milestone.sh`.
6. Follow-ups (§7) como specs próprios quando houver demanda.
