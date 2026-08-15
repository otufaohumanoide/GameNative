# Spec de implementação — Fase A: rumble fallback no telefone + USAGE_MEDIA + teste de vibração

**Data:** 2026-08-14
**Base:** spec 2026-08-16-A-rumble-fallback-usage-media.md (master roadmap UX 2026-H2, fase A).
**Status:** implementado — 1.1 (fallback no telefone), 1.2 (USAGE_MEDIA em tudo),
1.3 (curva pura), 1.4 (botão testar por device), 1.5 (toggle). Gate passou:
28 testes JVM (`*RumblePhoneCurve*` 9 + `*GamepadProfileStore*` 19, 0 falhas) +
`assembleModernDebug` OK.
**Verificação on-device:** pendente (padrão do repo — "on-device pendente" no spec §3):
DS4 USB (sem vibrator do device) → testar vibração → treme o TELEFONE; toggle OFF →
nada; rumble de jogo (Silksong) idem; `dumpsys vibrator_manager` confirma `USAGE_MEDIA`.

---

## 1. O que foi implementado (evidências file:line)

### 1.1 — Fallback no TELEFONE

| Peça | Arquivo | Detalhe |
|---|---|---|
| Desvio para o fallback | `ui/component/GamepadHaptics.kt:154-168` (`rumbleDevice`) | `deviceVibrators` vazio/null + `gamepadPhoneRumbleFallback` ON → `rumblePhoneFallback`; toggle OFF → `false` (byte-identical com o estado atual) |
| `rumblePhoneFallback` | `GamepadHaptics.kt:190-216` | vibrator do SISTEMA (`systemVibrator`) com `durationMs`; cancel idem (low=high=0 → cancel no sistema); amplitude pela curva pura; `appContext` null → no-op silencioso (V11) |
| `appContext` | `GamepadHaptics.kt:30-35` + `PluviaApp.kt:103-106` | contexto de aplicação atribuído 1× no `onCreate` (sem isso o contrato P2-5 não tem como alcançar o vibrator do sistema) |
| Tick SEM fallback | `GamepadHaptics.kt:119-135` (`tickDevice`) | comentário explícito: tick é feedback do DEVICE (V11 preservado) |
| Menu effects herdados | `GamepadHaptics.kt` (`vibrateDevice` → `rumbleDevice`) | device existente sem vibrator agora cai no fallback via `rumbleDevice` (antes no-op silencioso); device == null mantém o fallback legado histórico |

### 1.2 — USAGE_MEDIA em todas as vibrações

| Peça | Arquivo | Detalhe |
|---|---|---|
| `vibrateWithAttributes` | `GamepadHaptics.kt:306-330` | API 33+ `VibrationAttributes.USAGE_MEDIA`; API 26–32 `AudioAttributes.USAGE_MEDIA` como 2º arg de `vibrate(effect, attrs)`; API <26 one-shot legado sem atributo. Efeito criado por LAMBDA (API <26 nunca avalia `VibrationEffect`) |
| One-shot menu | `GamepadHaptics.kt:263-267` (`vibrate`) | passa por `vibrateWithAttributes` |
| Amplitude do contrato | `GamepadHaptics.kt:277-293` (`vibrateWithAmplitude`) | idem + fallback defensivo de amplitude default (SDL) |
| Tick com attrs | `GamepadHaptics.kt:124-128` | `createPredefined(EFFECT_CLICK)` com attrs (33+); fallback one-shot também com attrs |
| Fallback 1.1 com attrs | `GamepadHaptics.kt:212-214` | a vibração do telefone também carrega USAGE_MEDIA (MIUI não suprime) |

### 1.3 — Curva pura do telefone

| Peça | Arquivo | Detalhe |
|---|---|---|
| `RumblePhoneCurve` (novo) | `gamepad/processing/RumblePhoneCurve.kt` | objeto PURO (zero android.*): `amplitudeFor(mix)` = `mix^0.6 * 255`, round, `<= 1 → 0`, clamp 255 — fórmula do `getPhoneRumbleAmplitude` original (WinHandler.java do commit `2c184243^:843-850`) |
| Consumo | `GamepadHaptics.kt:211` | `RumblePhoneCurve.amplitudeFor(mixIntensity(low, high))` — o mix 0.6/0.4 do contrato P2-5 alimenta a curva |
| Testes | `gamepad/processing/RumblePhoneCurveTest.kt` (9) | 0→0, 1→255, pontos da fórmula, monotonia, clamp, equivalência 1..255, destino |

### 1.4 — Botão "Testar vibração" por device

| Peça | Arquivo | Detalhe |
|---|---|---|
| Decisão pura | `RumblePhoneCurve.kt:38-44` (`rumbleTargetFor(hasDeviceVibrators, phoneFallbackEnabled)`) | CONTROLLER / PHONE / NONE — a MESMA lógica do 1.1 |
| Resolução Android | `GamepadHaptics.kt:218-224` (`rumbleTargetFor(deviceId)`) | resolve `deviceVibrators` + toggle e delega à decisão pura |
| Botão por device | `ui/screen/settings/SettingsGroupGamepad.kt:139-166` | `GamepadSettingsButtonRow` abaixo de cada `ConnectedDeviceRow`; dispara `rumbleDevice(deviceId, 0.6f, 0.6f, 300)`; resultado = o que ACONTECEU (sem vibração de fato → "nada"); oculto sem device (o loop só roda com devices conectados) |
| Auto-limpa ~3 s | `SettingsGroupGamepad.kt:124-129` | `LaunchedEffect(rumbleTestResults)` + `delay(3000)` — cliques reiniciam a janela |

### 1.5 — Toggle na UI

| Peça | Arquivo | Detalhe |
|---|---|---|
| Pref nova | `PrefManager.kt:1499-1508` | `gamepadPhoneRumbleFallback` default ON (comportamento do original) |
| Switch | `SettingsGroupGamepad.kt:245-257` | `GamepadSettingsSwitchRow` após o rumble master, subtítulo explicando o fallback |

## 2. Desvios do spec (decisões registradas)

1. **`round` vs truncamento do original.** O `getPhoneRumbleAmplitude` do commit
   `2c184243^` usa `(int)(curved * 255)` (trunca); o spec §1.3 define `round`. Segui a
   fórmula do spec (diferença ≤ 1/255 da escala — inaudível; o teste de equivalência
   tolera ±1 justamente por isso).
2. **Nome do enum em PascalCase** (`RumbleTarget`), não `RUMBLE_TARGET` — convenção
   Kotlin do repo (ex.: `GamepadFocusState.Focused`). Valores exatamente como no spec:
   CONTROLLER | PHONE | NONE.
3. **`vibrateDevice` com device == null mantém o fallback legado** SEM consultar o novo
   toggle — o spec só muda o caso "device existe mas sem vibrator"; o caminho histórico
   (nenhum gamepad identificado) continua byte-identical.
4. **Resultado "nada" quando o master está OFF**: o botão mostra o que ACONTECEU
   (rumbleDevice respeita `gamepadRumbleEnabled`), não o alvo teórico — honesto com o
   usuário e com o contrato P2-5.

## 3. Verificação

- **JVM:** `--tests "*RumblePhoneCurve*" --tests "*GamepadProfileStore*"` → 28 testes, 0 falhas.
- **Build:** `assembleModernDebug` → OK (librashader de fonte).
- **On-device pendente:** DS4 USB → testar vibração → telefone treme; toggle OFF → nada;
  rumble de jogo idem; `dumpsys` com `USAGE_MEDIA`. Registrado no spec §3 (padrão do repo).

## 4. Riscos acompanhados

- `VibrationAttributes` é API 33: uso guardado por `SDK_INT` (ART carrega classes sob
  demanda) + lambda de efeito para API <26 — mesmo padrão do Dolphin referenciado no spec.
- Contrato P2-5 intocado: assinatura `rumbleDevice(deviceId, low, high, durationMs)`
  inalterada (WinHandler/DebugGamepadInput não mudaram).
- Byte-identical com toggle OFF: `vibrators.isNullOrEmpty()` + OFF → `false` (caminho exato atual).
