# Spec 2026-08-14 — U1: gyro → mouse/câmera (com política V1 + V12)

**Data:** 2026-08-14
**Origem:** doc de intuito (spec 2026-08-14-gamepad-intuito-validacao-upgrades.md, U1) —
pedido nº 1 da comunidade. Inclui a implementação OBRIGATÓRIA da política V1 (r2):
o store preserva chaves desconhecidas por entrada no save (downgrade de build real).
**Restrições (verificadas em api-versions.xml):** sensores por device = API **31+**
(`InputDevice.getSensorManager()`); modern (minSdk 29) e legacy (26) exigem runtime
guard + capability check (`sensorList` vazio = sem gyro) — degradação silenciosa (V11).
Conexão importa (DS4/DualSense: gyro comum só via BT) — a UI esconde a opção quando o
device não expõe sensor (V11), nunca mostra erro.

---

## 0. Estado atual

- Stub `InputEvent.SensorUpdate` (InputEvent.kt:19-27) — vocabulário pronto (V4).
- `GamepadDevice` com identidade estável (descriptor); `refreshDevice` re-classifica em
  `onInputDeviceChanged`.
- Perfil `@Serializable` com `ignoreUnknownKeys` — campos novos NÃO quebram arquivos
  antigos no LOAD; mas o SAVE regrava o mapa inteiro (GamepadProfileStore.kt:58-72) e
  PERDERIA campos de build novo ao salvar num build antigo → política V1.
- `GamepadHaptics.vibrate` (menu, view-level) — sem rumble por device (U5 separado).

## 1. Design

### 1.1 V1 — Store preserva chaves desconhecidas (obrigatório, r2)

`GamepadProfileStore`:
- `entries()` continua decodificando `Map<String, GamepadProfile>` para leitura, MAS
  mantém o `JsonObject` cru do arquivo (`private var rawJson: JsonObject?`).
- `save(key, profile)`/`clear(key)`: o write serializa o mapa conhecido (comportamento
  atual) e, para entradas PRESENTES no rawJson com chaves fora do schema conhecido,
  REINJETA as chaves extras no objeto serializado daquela entrada antes de escrever.
  Implementação: parse do arquivo como `JsonObject`; para cada chave desconhecida
  (não declarada em `GamepadProfile`), preserva o `JsonElement` na entrada salva;
  `ignoreUnknownKeys` no load ignora e o próximo save repassa (round-trip).
- Lista de chaves conhecidas: `GamepadProfile.serializer().descriptor` elements.
- Teste JVM: salvar perfil com campo fictício `"gyroMode":"MOUSE"` + `"futuro":123` →
  reload → save de outro campo → arquivo ainda contém `futuro`; `load` devolve o perfil
  conhecido.

### 1.2 Perfil: campos novos (U1) — com V1 no lugar

`GamepadProfile` ganha (todos null = default):
```kotlin
val gyroMode: GyroMode? = null,          // OFF/MOUSE/CAMERA
val gyroSensitivity: Float? = null,      // 1.0 default
val gyroDeadzone: Float? = null,         // rad/s, 0.05 default
val gyroActivateButton: String? = null,  // nome GamepadButton; null = sempre ativo
```
`enum class GyroMode { OFF, MOUSE, CAMERA }` (gamepad/GyroMode.kt).
`isDefault()` inclui os novos campos. Merge campo a campo no `GamepadProfileStore.merged`.

### 1.3 Processador puro: `GyroProcessor` (gamepad/processing/GyroProcessor.kt)

`object` puro (V5):
```kotlin
data class GyroSample(val gyroX: Float, val gyroY: Float, val gyroZ: Float, val nowMs: Long)
data class GyroOutput(val deltaX: Float, val deltaY: Float, val active: Boolean)
// estado: offset de calibração (recenter), último sample, ativo (activate button)
```
- **Recenter explícito:** `recenter()` armazena o offset atual (drift é inerente —
  decisão do intuito); o processador NÃO faz auto-calibração contínua.
- **Ativação:** se `activateButton != null`, o hub informa down/up; o gyro só gera
  delta quando ativo; na BORDA de ativação (off→on) → `recenter()` automático
  (padrão DS4Windows: recenter a cada ativação).
- **Delta:** yaw = -gyroZ, pitch = -gyroX (eixos Android: X lateral, Y frontal, Z
  vertical); delta = valor * `sensitivity` * `dt` escalado; deadzone angular
  (`abs < deadzone` → 0, com histerese simples: passa a valer acima de deadzone*1.2,
  volta a zero abaixo de deadzone*0.8).
- Saída em unidades de "pontos de mouse" por segundo de rotação (MOUSE) e mesma escala
  para CAMERA (o consumidor decide o alvo).

### 1.4 Fonte de sensores: `GamepadSensorSource` (gamepad/GamepadSensorSource.kt)

- API 31+: `InputDevice.getSensorManager(deviceId)` + `registerListener` com
  `SENSOR_DELAY_GAME` (~50 Hz default; FASTEST só por opt-in futuro — decisão do
  intuito U1(c)). Tipos: `Sensor.TYPE_GYROSCOPE` + `TYPE_ACCELEROMETER` (o modelo
  `SensorUpdate` tem accel; U1 usa gyro; accel fica disponível no evento).
- **Lifecycle (V3):** `setSensorsSuspended(suspend: Boolean)` — register quando
  `active` (container rodando) e `!suspended`; unregister em: container para
  (XServerScreen exit), activity pause (MainActivity.onPause), hub.stop().
  NUNCA coroutine no dispatch; callback vem em thread própria do sensor — o
  processamento é puro e a injeção vai direto ao sink (thread-safe por design:
  sink só enfileira no XServer).
- Callback → `GamepadHub.onSensorSample(deviceId, sample)`:
  - gate `gamepadUniversalEnabled` (mesmo kill-switch) + device CONTROLLER com gyro;
  - resolve perfil (cache M1) → modo != OFF;
  - `GyroProcessor` (estado por device, morto em `removeDevice` — V6);
  - emite `InputEvent.SensorUpdate` no bus (V4 — consumidores futuros) e injeta:
    - MOUSE → sink de mouse (mesmo `XServerTouchpadMouseSink` do U2 — move);
    - CAMERA → right-stick do virtual gamepad (`winHandler` + `gamepadState` do perfil
      do container — via `PluviaApp.inputControlsManager`? NÃO: o estado vivo vive no
      `PhysicalControllerHandler` ativo → `PluviaApp.activePhysicalControllerHandler`
      exposto; o handler injeta `state.thumbRX/thumbRY` acumulado com clamp e chama
      `winHandler.sendGamepadState()`).

### 1.5 Capability (V11) e UI

- `GamepadDevice` ganha `hasGyro: Boolean` (e `hasTouchpad: Boolean` para U7 — mesmo
  mecanismo): coletado no `addDevice` via `InputDevice.getSensorManager(deviceId)`
  (API 31+, null-safe; API < 31 → false) e `sources & CLASS_POINTER`.
- UI (remap dialog + settings): seção "Gyro" no `GamepadRemapDialog` (per-device):
  - modo OFF/MOUSE/CAMERA (linhas `gamepadSelectable`);
  - sensibilidade (slider 0.1–3.0, padrão A-lock);
  - deadzone (slider 0.0–0.3 rad/s);
  - botão de ativação (capture mode reutilizado: `gyroActivateButton`, "Nenhum =
    sempre ativo");
  - quando `!device.hasGyro`: a seção NÃO aparece (V11 — esconde, nunca erro).
- Settings globais: nenhum (per-device é o escopo do U1; global só o gate já existente).

### 1.6 Harness (V12 — verbo gyro)

`DebugGamepadInput.kt`: `gyro:x:y:z` — injeta `SensorUpdate` sintético DIRETO no hub
(`hub.onSensorSample` com deviceId do gamepadDeviceId(); sem sensor real, o harness
precisa de um canal — decisão: o harness chama o mesmo método do callback). Documentado.

### 1.7 Wiring

- `PluviaApp`: `gamepadTouchpad` (U2) + `gamepadSensorSource = GamepadSensorSource(hub)`.
- `MainActivity.onPause/onResume`: `gamepadSensorSource.setSensorsSuspended(true/false)`.
- `XServerScreen`: no exit do container → `setSensorsSuspended(true)`; no start →
  `false` + `hub.activeAppId` (já existe); expõe `PluviaApp.activePhysicalControllerHandler`
  (set no create, clear no exit) para CAMERA.
- `GamepadHub`: `onSensorSample`, `onActivateButton(deviceId, button, down)` (chamado
  pelo próprio `onKey` lógico quando `gyroActivateButton` está configurado — o hub
  conhece o estado de ativação por device).

## 2. Arquivos afetados

| Arquivo | Mudança |
|---|---|
| `gamepad/GyroMode.kt` (novo) | enum |
| `gamepad/processing/GyroProcessor.kt` (novo) | lógica pura (1.3) |
| `gamepad/GamepadSensorSource.kt` (novo) | fonte API 31 + lifecycle (1.4) |
| `gamepad/GamepadHub.kt` | `onSensorSample`, ativação, estado por device, capability |
| `gamepad/GamepadDevice.kt` | `hasGyro`, `hasTouchpad` (1.5) |
| `gamepad/profiles/GamepadProfile.kt` | campos U1 (1.2) |
| `gamepad/profiles/GamepadProfileStore.kt` | política V1 (1.1) |
| `gamepad/remap/GamepadRemapDialog.kt` | seção Gyro (1.5) |
| `PluviaApp.kt` | sensor source + activePhysicalControllerHandler |
| `MainActivity.kt` | suspend em pause/resume |
| `ui/screen/xserver/XScreen` (XServerScreen.kt) | suspend no exit, handler ativo |
| `ui/component/DebugGamepadInput.kt` | verbo `gyro:` (1.6) |
| `res/values*/strings.xml` | chaves da seção Gyro |
| Testes: `GyroProcessorTest.kt` (novo), `GamepadProfileStoreTest` (V1) | |

## 3. Verificação

### 3.1 JVM
- `GyroProcessorTest`: recenter zera drift; deadzone com histerese; ativação hold;
  recenter na borda; delta proporcional a sensibilidade; modo OFF não gera saída.
- `GamepadProfileStoreTest`: V1 (round-trip de chaves extras); merge com campos novos.
- Suíte filtrada completa.

### 3.2 On-device (pendente — sem dispositivo; DS4 via BT)
- Harness `gyro:` move cursor (MOUSE) / câmera (CAMERA) com gate ON.
- Sensor real: recenter + deadzone; unregister em pause (log; sem dreno de bateria);
  hotplug remove estado; UI esconde seção sem sensor (V11).

## 4. Fora de escopo
- `SENSOR_DELAY_FASTEST` por opt-in (decisão do intuito: follow-up).
- Calibração automática contínua (drift — recenter explícito é a decisão).
- Touchpad click como recenter (o activate button já cobre).
- Rumble (U5), layers (U3) — specs próprios.
