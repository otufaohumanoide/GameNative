# Spec 2026-08-14 — U7: bateria e capacidades por device

**Data:** 2026-08-14
**Origem:** doc de intuito (spec 2026-08-14-gamepad-intuito-validacao-upgrades.md, U7) —
custo mínimo; infra compartilhada com o capability check do U1 (V11).
**Restrições:** `InputDevice.getBatteryState()` = API **31+**; modern+legacy com runtime
guard; ausência = ícone oculto (degradação silenciosa, V11).

---

## 0. Estado atual
- `GamepadDevice` (deviceId, descriptor, vendor/product, name, class, faceStyle) + hub
  com `connectedDevices` StateFlow (seletor reativo) e `activeDevice`.
- U1 já adiciona `hasGyro`/`hasTouchpad` ao device (mesmo mecanismo).

## 1. Design

### 1.1 Modelo e coleta

- `GamepadDevice` ganha `batteryPercent: Int?` (null = desconhecido/sem bateria) —
  além de `hasGyro`/`hasTouchpad` (U1).
- `GamepadHub.addDevice` coleta (fora do hot path — só hotplug, V3):
  - `hasGyro`: API 31+ → `InputDevice.getSensorManager(deviceId)?.getDefaultSensor(
    Sensor.TYPE_GYROSCOPE) != null`; API < 31 → false.
  - `hasTouchpad`: `(sources and SOURCE_CLASS_POINTER) != 0` (qualquer API).
  - `batteryPercent`: API 31+ → `InputDevice.getBatteryState(deviceId)` →
    `isPresent && !isCharging` → `(capacity * 100).toInt()`; `isCharging` → exibe 100
    (ou mantém null? decisão: null quando desconhecido; capacity < 0 → null).
    API < 31 → null.
- `refreshDevice` (hotplug change) re-coleta (já re-classifica).

### 1.2 UI — seletor de controles

Onde o usuário vê os devices: a seção Gamepad (SettingsGroupGamepad). Adicionar um
bloco "Controles conectados" com uma linha por device do `connectedDevices`:
- nome + classe + FaceStyle;
- ícone de bateria + % quando `batteryPercent != null` (API 31+; hidden otherwise);
- badges de capacidade: "GYRO" quando `hasGyro`, "TOUCHPAD" quando `hasTouchpad`;
- o device ATIVO destacado (mesmo critério do hub).
- Guard de preview (LocalInspectionMode) → sem lista.

### 1.3 Strings

EN + pt-rBR: `gamepad_devices_title`, `gamepad_battery_format` ("%1$d%%"),
`gamepad_cap_gyro`, `gamepad_cap_touchpad`, `gamepad_devices_empty`
("Nenhum controle conectado").

## 2. Arquivos afetados

| Arquivo | Mudança |
|---|---|
| `gamepad/GamepadDevice.kt` | `batteryPercent` (1.1) |
| `gamepad/GamepadHub.kt` | coleta de capacidades + bateria (1.1) |
| `ui/screen/settings/SettingsGroupGamepad.kt` | bloco de devices (1.2) |
| `res/values*/strings.xml` | chaves (1.3) |
| Testes: nenhum puro novo (coleta é adapter Android) — `DeviceClassifierTest` permanece | |

## 3. Verificação
- JVM: suíte filtrada.
- On-device (pendente): DS4 via BT mostra % e badges; via USB (sem bateria) esconde %;
  API < 31 não crasha e esconde; hotplug atualiza sem travar (coleta fora do hot path).

## 4. Fora de escopo
- Alertas de bateria fraca — follow-up.
- Bateria por device no overlay do jogo (QuickMenu) — follow-up.
