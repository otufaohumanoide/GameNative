# Spec 2026-08-16 A — Rumble: fallback no telefone + USAGE_MEDIA + teste de vibração

**Data:** 2026-08-16
**Origem:** relato do usuário (DS4 via USB/BT não treme no MIUI — o Android não
expõe o vibrator do controle; o GameNative ORIGINAL vibrava o telefone e isso foi
perdido). Decisão nº 5 do impl doc do input core ("reversível se o campo
reclamar") — o campo reclamou.
**Executor:** sub-agente autônomo. Leia `AGENTS.md` e as regras globais do
`spec-2026-08-16-master-roadmap-ux.md` §2. Spec autocontido.

## 0. Estado atual

- `GamepadHaptics.rumbleDevice(deviceId, low, high, durationMs): Boolean`
  (`ui/component/GamepadHaptics.kt`): dual-motor API 31+ (`vibratorManager`),
  fallback `getVibrator()` API 16, mix 1 motor `low*0.6+high*0.4`, cancel quando
  low=high=0. Retorna `true` se vibrou (desde d19ae234).
- `WinHandler.startDeviceVibration` (ponte Wine/XInput) chama esse contrato.
- **SEM fallback de telefone**: removido no commit `2c184243` (o original usava
  curva `pow(amplitude, 0.6)` — recuperável via `git show 2c184243^:
  app/src/main/java/com/winlator/winhandler/WinHandler.java`,
  `getPhoneRumbleAmplitude`).
- **Sem `VibrationAttributes`**: OEMs (MIUI) tratam vibração sem atributo como
  "notificação" e podem suprimir. O Dolphin vibra com `USAGE_MEDIA`
  (`reference/dolphin/.../features/input/model/ControllerInterface.kt:186-204`:
  `VibrationAttributes` API 33+, `AudioAttributes` API <33).
- Toggles existentes: `PrefManager.gamepadRumbleEnabled` (guarda tudo),
  `gamepadLayerTickEnabled`.
- Harness: verbo `rumble:low:high:duration` (`DebugGamepadInput.kt`) já existe.

## 1. Design

### 1.1 Fallback no TELEFONE (restaura comportamento original)

Em `rumbleDevice`: quando `deviceVibrators(deviceId)` for vazio/nulo E
`PrefManager.gamepadPhoneRumbleFallback == true` (novo, default **ON** =
comportamento do GameNative original):
- amplitude = curva pura sobre `mixIntensity(low, high)` (ver 1.3);
- vibra o vibrator do SISTEMA (mesmo caminho do `systemVibrator` existente) com
  `durationMs` (cancel idem: low=high=0 → `cancel()` também no do sistema);
- retorna `true` (vibrou — no telefone).
- `tickDevice` NÃO ganha fallback (tick é feedback do DEVICE; sem device =
  silêncio, V11 — comportamento atual preservado).
- `vibrateDevice` (efeitos de menu) já tem fallback de sistema quando
  `device == null`; quando device existe mas sem vibrator, passa a cair no mesmo
  fallback SE o toggle estiver ON (hoje é no-op silencioso).

### 1.2 `USAGE_MEDIA` em TODAS as vibrações (mímese do Dolphin)

Novo helper privado `vibrateWithAttributes(vibrator, effect)`:
- API 33+: `VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_MEDIA).build()`;
- API 26–32: `AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()`
  como 2º arg de `vibrate(effect, attrs)`;
- API <26: sem atributo (deprecado, comportamento atual).
Aplicar em: `vibrateWithAmplitude`, `vibrate` (one-shot), `tickDevice`
(`createPredefined` com attrs no 33+; antes sem), e no fallback de 1.1.

### 1.3 Curva do telefone — PURA (JVM-testável)

Novo `gamepad/processing/RumblePhoneCurve.kt` (objeto puro, zero android.*):
```kotlin
object RumblePhoneCurve {
    /** mix 0..1 → amplitude 0..255 com curva pow 0.6 (Winlator original) e clamp. */
    fun amplitudeFor(mix: Float): Int
}
```
Fórmula exata do original: `norm = mix; curved = norm^0.6; amp = round(curved*255);
amp<=1 → 0; clamp 255`. `GamepadHaptics` consige o mix de `mixIntensity(low, high)`.

### 1.4 Botão "Testar vibração" por device

Na seção Gamepad dos settings, `GamepadSettingsButtonRow` novo abaixo do
`ConnectedDeviceRow`: dispara `rumbleDevice(deviceId, 0.6f, 0.6f, 300)` e mostra
o resultado por ~3 s usando nova função PURA de destino:
`fun rumbleTargetFor(deviceId): RUMBLE_TARGET` — enum `CONTROLLER | PHONE | NONE`
(decisão = `deviceVibrators` vazio? toggle ON? — a mesma lógica de 1.1, extraída
para função testável/compartilhada). Strings: resultado "Vibrou: controle /
telefone / nada". Sem device conectado: botão oculto.

### 1.5 Toggle na UI

`gamepadPhoneRumbleFallback` (default ON) + `GamepadSettingsSwitchRow` na seção
Gamepad com subtítulo explicando ("quando o controle não suporta vibração").
Mantém `gamepadRumbleEnabled` como master (OFF desliga TUDO, inclusive fallback).

## 2. Arquivos afetados

| Arquivo | Mudança |
|---|---|
| `gamepad/processing/RumblePhoneCurve.kt` | NOVO — curva pura (1.3) |
| `ui/component/GamepadHaptics.kt` | fallback (1.1), attrs USAGE_MEDIA (1.2), `rumbleTargetFor` (1.4) |
| `PrefManager.kt` | `gamepadPhoneRumbleFallback` (1.5) |
| `ui/screen/settings/SettingsGroupGamepad.kt` | toggle + botão testar (1.4/1.5) |
| `res/values/strings.xml` + `values-pt-rBR/strings.xml` | chaves novas |
| `app/src/test/.../RumblePhoneCurveTest.kt` | NOVO — curva (0→0, 1→255, monotônica, clamp) |

## 3. Verificação (gate)

```
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:testModernDebugUnitTest --tests "*RumblePhoneCurve*" --tests "*GamepadProfileStore*"
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:assembleModernDebug
```
On-device (humano, "on-device pendente"): DS4 USB (sem vibrator do device) →
testar vibração → treme o TELEFONE; toggle OFF → nada; rumble de jogo
(Silksong) idem; `dumpsys` confirma `USAGE_MEDIA` nos attrs.

Consolidado (fechamento 2026-08-16, §2 linha A — protocolo único do roadmap):
`setprop debug.gamenative.input rumble:0.6:0.6:300` + botão Testar vibração no
card → log `rumble → true/false` + destino CONTROLLER/PHONE/NONE; `dumpsys`
com USAGE_MEDIA; toggle OFF silencia. **Status: on-device pendente.**

## 4. Fora de escopo

HD haptics/voice-coil, trigger effects, lightbar, waveforms compostas, rumble
por sub-device de mesmo VID/PID (moonlight probing) — reavaliar só se o BT
re-teste do DS4 mostrar vibrator enumerado.
