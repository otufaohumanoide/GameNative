# Spec 2026-08-14 — U5: Rumble por device + efeitos de menu (ponte do jogo dimensionada)

**Data:** 2026-08-14
**Origem:** doc de intuito (spec 2026-08-14-gamepad-intuito-validacao-upgrades.md, U5).
**Restrições (verificadas):** `InputDevice.getVibrator()` = API **16** (deprecado em 31);
`getVibratorManager()` = API 31+. Rumble por device funciona em TODAS as configurações
do fork (modern 29, legacy 26) — caminho legado com `@Suppress("DEPRECATION")`.
**Decisão de escopo (r2 do intuito):** a ponte Wine/XInput → Vibrator do JOGO é
dimensionada aqui e fica como follow-up (JNI no caminho do ExternalController — não
entra nesta rodada); esta missão entrega rumble por DEVICE + efeitos de PERFIL no menu
e a arquitetura para a ponte.

---

## 0. Estado atual
- `GamepadHaptics.vibrate(context, durationMs)` — vibrator do SISTEMA (não do device),
  view-level, chamado nos bridges de confirmação (menu).
- Sem efeitos por perfil; sem rumble do jogo.

## 1. Design

### 1.1 `GamepadHaptics` por device (API 16+/31+)

- `fun vibrateDevice(deviceId: Int, effect: HapticEffect)`:
  - API 31+: `InputDevice.getVibratorManager()` → `getDefaultVibrator()`? NÃO —
    `vibratorManager.getVibrator(deviceId)`? A API: `VibratorManager.getVibrator(int
    vibratorId)`; os ids vêm de `getVibratorIds()`. Decisão: `getVibratorIds()` contém
    o id do device? (não garantido) — usar `InputDevice.getVibrator()` (API 16) em
    TODAS as versões (deprecado em 31 mas funcional) e `getVibratorManager()` apenas
    como FALLBACK quando `getVibrator()` não tem `hasVibrator()` (raro). Simples,
    funciona em tudo (V11: runtime check + degradação silenciosa).
  - `HapticEffect`: `enum class HapticEffect { ACTIVATE, BACK }` → padrões
    `VibrationEffect.createOneShot(18ms)` / `createWaveform([0,12,8], -1)` (API 26+;
    < 26 → `vibrate(ms)` deprecado).
- `GamepadHub.confirmKeyCodeFor` já identifica o device; os bridges passam a chamar
  `GamepadHaptics.vibrateDevice(event.deviceId, ACTIVATE)` quando o device é do hub;
  fallback para o `vibrate(context)` atual quando deviceId desconhecido (byte-identical).

### 1.2 Efeitos por perfil

`GamepadProfile` ganha:
```kotlin
val rumbleOnActivate: Boolean? = null,  // null = global
val rumbleOnBack: Boolean? = null,
```
- PrefManager: `gamepadRumbleEnabled` (default true — comportamento atual do menu).
- Perfil vence global. `isDefault()` + merge atualizados (V1 já garante round-trip).

### 1.3 Ponte do JOGO — dimensionamento (não implementa)

O rumble do jogo exigiria: XInput `XINPUT_VIBRATION` no Wine → callback JNI →
`Vibrator.vibrate(VibrationEffect.createWaveform(...))` do device, com conversão de
intensidade (0..65535) → amplitude (0..255) e sincronização com o loop do jogo
(60 Hz). Estimativa: 1 arquivo JNI novo + modificação no caminho do ExternalController
+ teste de conversão puro (JVM). **Follow-up com spec próprio** (o intuito U5(b) manda
dimensionar ANTES de estimar — feito aqui).

## 2. Arquivos afetados

| Arquivo | Mudança |
|---|---|
| `ui/component/GamepadHaptics.kt` | `vibrateDevice(deviceId, effect)` (1.1) |
| `gamepad/profiles/GamepadProfile.kt` | `rumbleOnActivate/rumbleOnBack` (1.2) |
| `ui/component/GamepadBusInput.kt`, `GamepadKeyBridge.kt`, `JoystickFocusNavigator.kt` | confirm haptics por device (1.1) |
| `PrefManager.kt` | `gamepadRumbleEnabled` (1.2) |
| `ui/screen/settings/SettingsGroupGamepad.kt` | switch rumble do menu (1.2) |
| `res/values*/strings.xml` | chave do switch |
| Testes: `GamepadHapticsTest`? (Android — não); teste puro de conversão se houver (1.3 é follow-up — sem teste agora) | |

## 3. Verificação
- JVM: suíte filtrada (nada de lógica nova pura além do merge — coberto pelo
  `GamepadProfileStoreTest`).
- On-device (pendente): confirmação no menu vibra o CONTROLE (não só o telefone);
  com rumble OFF nada vibra; perfil com rumbleOnActivate=false silencia; API < 31
  funciona (caminho legado).

## 4. Fora de escopo
- Ponte Wine/XInput → Vibrator (dimensionada; spec próprio).
- Padrões de rumble avançados (trigger rumble do DualSense — follow-up).
