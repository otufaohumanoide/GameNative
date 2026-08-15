# Spec 2026-08-16-G — Gyro v2: sub-pixel, smoothing, per-axis, shaping, toggle, grip angle

Origem: modo MOUSE perde movimento fracionário (`.toInt()` descarta sub-pixel — giro
lento pode nunca mover o cursor); sem smoothing; sensibilidade escalar única; CAMERA
sem shaping; ativação só hold/sempre; DS4 segurado inclinado mistura pitch/yaw.
Fontes: DS4Windows (`MouseCursor.sixaxisMoved`, `Mouse.cs`, `GyroMouseInfo`,
`SixMouseStick`, `IsGyroTriggerActive`), JoyShockLibrary (grip angle).

## 0. Estado atual

- `GamepadHub.kt:531-537`: MOUSE = `(deltaXRad * PIXELS_PER_RAD * sens).toInt()` → sink;
  CAMERA = sink `(yawRadS, pitchRadS, sensitivity)` → `PhysicalControllerHandler` →
  `GyroStickMapping.deflection` (linear + clamp).
- `GyroProcessor`: deadzone c/ histerese, recenter na borda, calibração contínua
  (3 s, stillness gyro+accel). Eixos fixos: yaw = −Z, pitch = −X (assume pad plano).
- Ativação: `gyroActivateButton` (hold) ou null = sempre. Fusão Mahony opt-in
  (pitch only).
- Harness `gyro:x:y:z` injeta em `onSensorSample` — G1/G2/G6 testáveis sem hardware.

## 1. Design (ordem de implementação)

- **G1 — Acumulador sub-pixel (MOUSE).** Estado por device (V6):
  `GyroMouseState { remX, remY }`. `object GyroPixelAccumulator.accumulate(deltaPxF,
  state): Pair<Int,Int>` — soma float, emite parte inteira, guarda fração (padrão
  DS4Windows `horizontalRemainder`). Sem flag: correção pura, não há caminho antigo
  a preservar.
- **G2 — OneEuro opt-in (MOUSE).** NOVO `processing/OneEuroFilter.kt` (puro; defaults
  DS4Windows minCutoff 1.0 / beta 0.7, dCutoff 1.0). Filtra deltaXRad/deltaYRad por
  eixo ANTES da conversão em pixels; compõe com G1. Perfil: `gyroSmoothMinCutoff:
  Float?`, `gyroSmoothBeta: Float?` — ambos null = OFF (byte-identical).
- **G3 — Sensibilidade por eixo + inversão.** Perfil: `gyroSensitivityY: Float?`
  (null = usa `gyroSensitivity`), `gyroInvertX/gyroInvertY: Boolean?` (null = false).
  MOUSE aplica no hub; CAMERA estende o contrato do sink para `(yawRadS, pitchRadS,
  sensX, sensY)` — único call site é o `PhysicalControllerHandler` (atualizar junto).
- **G4 — Shaping do CAMERA.** `GyroStickMapping.deflection` ganha config: `maxOutput`
  (default 1.0), `antiDeadzone` (default 0) — acima da deadzone remapeia
  `(dz..1) → (adz..maxOutput)` (semântica `SixMouseStick`). Perfil:
  `gyroStickMaxOutput: Float?`, `gyroStickAntiDeadzone: Float?`. Defaults = linear
  atual (byte-identical).
- **G5 — Ativação TOGGLE.** Perfil: `gyroActivateToggle: Boolean?` (null = hold
  atual). No caminho onde `gyroActivateHeld` é escrito hoje, borda de descida do
  botão de ativação flipa latch por device (recenter na borda off→on já existe no
  processor, sai de graça).
- **G6 — Grip angle.** Perfil: `gyroGripAngleDeg: Float?` (null = 0 = eixos atuais).
  Rotação do par (X, Z) calibrado por θ em torno do eixo longitudinal ANTES da
  extração yaw/pitch e da deadzone (offset subtraído por eixo raw, depois rotaciona —
  janela de calibração inalterada, stillness usa magnitude). Botão "Calibrar grip" no
  `DeviceDiagnosticsCard`: θ = atan2 do accel corrente (`state.lastSample`, já
  coletado P2-3) — função pura `gripAngleFromAccel(ax, az)`.

## 2. Arquivos

| Arquivo | Mudança |
|---|---|
| `gamepad/processing/OneEuroFilter.kt` | NOVO (G2, puro) |
| `gamepad/processing/GyroStickMapping.kt` | config shaping (G4) |
| `gamepad/processing/GyroProcessor.kt` | rotação grip (G6) |
| `gamepad/GamepadHub.kt` | G1 estado+acumulador, G2 filtro, G3 wiring+sink, G5 latch, G6 config |
| `gamepad/profiles/GamepadProfile(+Store).kt` | 9 campos null-default + `isDefault()`/`merged()` |
| `gamepad/remap/GamepadRemapDialog.kt` | UI: sliders/toggles/dropdown da seção Gyro |
| `ui/screen/settings/DeviceDiagnosticsCard.kt` | botão calibrar grip (G6) |
| `XServerScreen.kt`/`PhysicalControllerHandler` | contrato do sink (G3) — sem locals novas |
| `res/values*/strings.xml` | EN + pt-rBR |

## 3. Testes (JVM, objetos puros)

`*OneEuroFilter*` (constante/escada/ruído), `*GyroPixelAccumulator*` (frações
acumulam, reset), `*GyroStickMapping*` (pontos da curva com antiDeadzone/maxOutput),
`*GyroProcessor*` (grip θ=0 identical; θ≠0 mistura eixos; calibração contínua
intacta), latch toggle (G5), merge do store com campos novos.

## 4. Gate

`--tests "*Gyro*" "*OneEuro*" "*GamepadProfileStore*"` + `assembleModernDebug`.
On-device pendente (DS4 + Silksong): CAMERA+toggle+grip na prática; sinais do MOUSE.

## 5. Não-metas

Taxa FASTEST (bateria — follow-up), gyro swipes (extensão fase D, spec próprio),
remap por eixo de sensor (RetroArch, pads exóticos), calibração de fábrica (sem HID
público), yaw absoluto (DS4 sem magnetômetro).
