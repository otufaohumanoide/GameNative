# Spec de implementação — Upgrades do doc de intuito (U1–U7 + V1/V12)

**Data:** 2026-08-14
**Base:** spec 2026-08-14-gamepad-intuito-validacao-upgrades.md (r2) — declaração de
intuito + auditoria de prontidão. Cada upgrade gerou spec próprio (padrão do repo):
`spec-2026-08-14-gamepad-u6-libraryscreen-ok-cancel.md`, `-u2-touchpad-mouse.md`,
`-u1-gyro.md`, `-u3-u4-layers-remap-jogo.md`, `-u5-rumble.md`, `-u7-battery.md`.
**Status:** implementado na ordem sugerida pelo intuito (U6 → U2 → U1 → U3+U4 → U5 →
U7); **186 testes JVM filtrados verdes (0 falhas)**; `assembleModernDebug` OK;
`compileLegacyDebugKotlin` OK (o assemble legacy falha por CMake pré-existente do
adrenotools em armeabi-v7a — não relacionado).
**Verificação on-device:** pendente — sem dispositivo (mesma bateria da Onda 2).

---

## 1. O que foi implementado (evidências)

### U6 — OK/Cancel + atalhos lógicos + deadzones na LibraryScreen
| Peça | Arquivo | Detalhe |
|---|---|---|
| `LibraryGamepadKeys` (puro) | `gamepad/LibraryGamepadKeys.kt` | `LibraryKeySet` (confirm/cancel/y/x/l1/r1/select/start); sem mapping → `FALLBACK` raw (byte-identical V10) |
| `cancelButton` | `gamepad/mapping/GamepadMapping.kt` | simétrico ao confirm (Xbox/PS → B; Nintendo → A; swap inverte) |
| Helpers do hub | `gamepad/GamepadHub.kt` (`libraryKeySetFor`, `confirmButtonFor`, `swapFor`) | perfil ?: global, lidos no call time (holder vivo) |
| Confirm por FaceStyle | `LibraryScreen.kt` (onPreviewKeyEvent) | confirm ≠ A/DPAD_CENTER → sintetiza DPAD_CENTER na view (padrão GamepadKeyBridge); A posicional não-confirm é roteado ao branch de cancel |
| Atalhos lógicos | `LibraryScreen.kt` (L1/R1/SELECT/START/Y/X/cancel) | `keySet.*` com fallback raw — device desconhecido = comportamento histórico |
| Thresholds do bootstrap | `LibraryScreen.kt` (onGlobalMotionEvent) | stick 0.6 hardcoded → `hub.menuDeadzoneFor` (global 0.45); hat mantém 0.5 |
| ActionBar | `LibraryScreen.kt` | actions select/back/menu usam o botão posicional do device ativo (glyph bate com a tecla) |

### U2 — Touchpad → mouse
| Peça | Arquivo | Detalhe |
|---|---|---|
| `TouchpadProcessor` (puro, 9 testes) | `gamepad/processing/TouchpadProcessor.kt` | delta com deadzone de toque + sensibilidade; tap curto/parado = clique; estado por device (V6) |
| `RawTouchInput` + adapter | `gamepad/mapping/RawTouchInput.kt`, `AndroidInputAdapter.toRawTouch` | absoluto normalizado 0..1 + presença de dedo |
| Forwarder app-scoped | `gamepad/GamepadTouchpadForwarder.kt` | lê NO PONTO DO GATE (V7, exceção única); sink `XServerTouchpadMouseSink` (injectPointerMoveDelta + BUTTON_LEFT; no-op sem jogo) |
| Wiring | `MainActivity.kt` (gate), `PluviaApp.kt` (init antes do hub), `GamepadHub.removeDevice` (V6) | gate vira ROTEADOR — consume continua; estado morre no hotplug |
| Prefs + UI | `PrefManager` (`gamepadTouchpadMouseEnabled` OFF, `gamepadTouchpadSensitivity`), `SettingsGroupGamepad` | opt-in; byte-identical com OFF |
| Harness (V12) | `DebugGamepadInput.kt` | `touch:x:y`, `touchdown`, `touchup`, `touchtap` (SOURCE_CLASS_POINTER — exercita o gate antes do consume) |

### U1 — Gyro → mouse/câmera (+ V1)
| Peça | Arquivo | Detalhe |
|---|---|---|
| **V1** (obrigatório r2) | `gamepad/profiles/GamepadProfileStore.kt` | save re-injeta chaves desconhecidas por entrada (rawJson + KNOWN_FIELDS do serializer); 3 testes (round-trip, remoção, múltiplos saves) |
| Campos do perfil | `GamepadProfile.kt` (`gyroMode/Sensitivity/Deadzone/ActivateButton`), `GyroMode.kt` | null = default; merge campo a campo; `isDefault` estendido |
| `GyroProcessor` (puro, 9 testes) | `gamepad/processing/GyroProcessor.kt` | recenter na borda de ativação (DS4Windows), deadzone com histerese 0.8/1.2, deltas rad×dt; sinais anotados p/ verificação on-device |
| Fonte API 31+ | `gamepad/GamepadSensorSource.kt` | `getSensorManager` por device, `SENSOR_DELAY_GAME` (~50 Hz); lifecycle V3 (suspend em pause/exit/screen-off; register só com container de pé); hotplug avisa o source |
| Hub | `GamepadHub.onSensorSample` | gate-aware → GyroProcessor → `SensorUpdate` emitido (V4) → MOUSE (`xServerMouseSink`) / CAMERA (`gyroCameraSink` → `PhysicalControllerHandler.applyCameraGyro` — right stick do virtual gamepad). **Corrigido pelo spec 2026-08-14-gamepad-upgrades-pendencias (P1-1/P1-2):** o sink era dead code e o modelo integral acumulava — agora o XServerScreen instala o sink e o mapeamento é velocidade→deflexão (controle de taxa) |
| Ativação por botão | `GamepadHub` (`gyroActivateHeld`) | hold no caminho lógico PÓS-remap (consistência U3); estado por device (V6) |
| UI | `GamepadRemapDialog` (seção Gyro) | modo/sensibilidade/deadzone/botão de ativação (capture mode) — SÓ com `device.hasGyro` (V11) |
| Capability | `GamepadDevice` (`hasGyro/hasTouchpad`), `GamepadHub.addDevice` | coleta no hotplug (pull, fora do hot path); API 31+ runtime guard |
| Harness (V12) | `DebugGamepadInput.kt` | `gyro:x:y:z` → `hub.onSensorSample` direto |

### U3+U4 — Layers completas + remap no jogo
| Peça | Arquivo | Detalhe |
|---|---|---|
| `LayerResolver` (puro, 7 testes) | `gamepad/layers/LayerResolver.kt` | HOLD por botão (bug multi-HOLD corrigido com teste de regressão), TOGGLE, DOUBLE_TAP com janela; uma camada por vez; `effectiveBindings` (DEFAULT + ativa) |
| Modelo | `gamepad/layers/LayerTriggerSpec.kt` | `layerTriggers: Map<layer, spec>`; merge GRANULAR no store (decisão do intuito U3(c)) |
| Hub | `GamepadHub` (`resolveLayerTriggers` + `emitLogical` + `remapEvent`) | triggers no botão FÍSICO; remap efetivo aplicado aos eventos lógicos (Key→botão alvo, Axis→AxisMotion ±1, sem binding→original); `activeLayerFor`/`layerBindingFor` |
| UI | `GamepadRemapDialog` | seletor de camadas (add/remove), trigger por camada (capture + modo), bindings por camada |
| U4 — jogo | `PhysicalControllerHandler` | `applyUniversalKeyRemap` (teclas), remap de triggers e sticks (`handleRemappedAxis` com release controlado + cleanup); precedência: binding explícito vence, senão byte-identical (V10); gate ON obrigatório |

### U5 — Rumble por device + efeitos de menu
| Peça | Arquivo | Detalhe |
|---|---|---|
| `GamepadHaptics.vibrateDevice` | `ui/component/GamepadHaptics.kt` | API 16 `getVibrator` (funciona em tudo) + fallback API 31 `VibratorManager`; efeitos ACTIVATE/BACK; respeita `gamepadRumbleEnabled` (default ON) e perfil `rumbleOnActivate/rumbleOnBack` |
| Bridges | `GamepadKeyBridge.kt`, `GamepadBusInput.kt` | confirm vibra o DEVICE (fallback `vibrate(context)` p/ deviceId desconhecido) |
| UI | `SettingsGroupGamepad` | switch de rumble do menu |
| Ponte do JOGO | — | DIMENSIONADA no spec U5 §1.3 (JNI Wine/XInput → VibrationEffect) — follow-up com spec próprio (decisão do intuito) |

### U7 — Bateria e capacidades por device
| Peça | Arquivo | Detalhe |
|---|---|---|
| Coleta | `GamepadHub.addDevice` (`batteryPercent`), `GamepadDevice.kt` | API 31+ `getBatteryState` (capacity/status; charging/full = 100); API < 31 → null (V11) |
| UI | `SettingsGroupGamepad` (`ConnectedDeviceRow`) | bloco "Controles conectados": nome, %, badges GYRO/TOUCHPAD, device ativo destacado; vazio → hint; % hidden quando desconhecida |

## 2. Desvios / decisões registradas
1. **U6**: mantido Compose focus + helpers do hub (decisão do próprio spec U6(c)); `LibraryAppScreen` (tela de detalhe) fica fora — mesmo padrão num spec futuro.
2. **U2**: sem timer de 60 FPS — o delta é injetado por evento (touchpad ~60-100 Hz); gestos multi-dedo follow-up; sensibilidade por device follow-up.
3. **U1**: ativação = hold com recenter na borda (padrão DS4Windows); sempre-ativo quando sem botão; `SENSOR_DELAY_FASTEST` opt-in follow-up; sinais de yaw/pitch a confirmar on-device.
4. **U3**: nomes de camada gerados ("LAYER_N") — rename follow-up; hats não são alvo de remap no caminho lógico nem no jogo (decisão registrada — dpad via canal de tecla).
5. **U4**: hats/DPAD do jogo não são remapeados por eixo (o canal de tecla cobre os devices que emitem KEYCODE_DPAD_*); sem binding no alvo → o evento é consumido (o remap explícito substitui o botão).
6. **U5**: ponte Wine/XInput → Vibrator dimensionada, não implementada (decisão do intuito r2).

## 3. Verificação
### 3.1 JVM (feita)
- 186 testes filtrados (`*Shader*` + `*Gamepad*` + `*SearchField*`): 0 falhas, 0 erros
  (baseline 173 → +13: LibraryGamepadKeys 8, TouchpadProcessor 9, GyroProcessor 9,
  LayerResolver 7, Store V1/merge/default 6 — alguns substituíram/estenderam).
- `assembleModernDebug` BUILD OK; `compileLegacyDebugKotlin` OK.

### 3.2 On-device (pendente — sem dispositivo; cenários no `tools/quickmenu-verify.sh` §[H])
- U6: DS4 A/B; swap ON (B confirma, A cancela); Nintendo Pro (direita confirma);
  deadzone 0.45 no bootstrap; glyphs acompanham.
- U2: `touch:` move o cursor no Silksong; `touchtap` clica; gate bloqueia fantasma
  (V7/V8); OFF = byte-identical.
- U1: `gyro:` com gate ON move cursor (MOUSE) / câmera (CAMERA); sensor real via BT
  (DS4) com recenter + deadzone; unregister em pause (log, sem dreno de bateria);
  seção Gyro some sem sensor (V11); sinais a confirmar.
- U3/U4: camada HOLD remapeia no jogo (gate ON); toggle; duplo-toque; UI navegável;
  gate OFF = jogo byte-identical (V10).
- U5: confirmação vibra o CONTROLE; OFF silencia; API < 31 funciona.
- U7: DS4 via BT mostra % + badges; via USB esconde %; API < 31 sem crash.

### 3.3 Pendências pós-verificação
- Flip do gate `gamepadUniversalEnabled` default → true (PrefManager.kt) — último
  passo da Onda 2, condicionado à bateria on-device.
- `tools/milestone.sh` + tag anotada (entrada já em `docs/MILESTONES.md`).

---

# Anexo — Correções do spec 2026-08-14-gamepad-upgrades-pendencias (P3-7)

A auditoria pós-implementação (spec `2026-08-14-gamepad-upgrades-pendencias.md`)
encontrou defeitos no que este doc descrevia como feito. Correções aplicadas em
2026-08-14 (commits `74ce5136..848480a0`, branch feat/joystick-avancado):

- **P1-1/P1-2 — CAMERA**: `gyroCameraSink` nunca era instalado (dead code) e
  `applyCameraGyro` INTEGRAVA deltas (o stick permanecia no último valor e brigava
  com o stick físico). Agora: sink instalado pelo XServerScreen junto do handler
  (holder vivo, limpo no exit/onDispose) e mapeamento VELOCIDADE (rad/s)→deflexão
  (`GyroStickMapping` puro, padrão DS4Windows/JoyShock); `processJoystickInput` pula
  o right stick físico com CAMERA ativo (um único escritor por modo).
- **P1-3 — lifecycle**: `setSuspended(false)` só tinha um chamador (MainActivity.
  onResume com `xServerView != null`); o XServerScreen agora é o dono da retomada
  (container sobe ⇒ registra; exit/onDispose ⇒ suspende). `registerAll` filtra por
  `gyroMode != OFF` (registro dirigido pelo uso, padrão SDL).
- **P1-4 — histerese**: entrada/saída estavam invertidas (0.8×/1.2× com estado pelo
  deadzone cru) — corrigido para entrada 1.2×/saída 0.8× com o threshold aplicado.
- **P2-1 — dt**: agora vem do `event.timestamp` do sensor (ns→ms com guarda de
  monotonicidade), não do relógio de processamento.
- **P2-2/P2-3 — calibração contínua + accel**: recenter-de-ativação vira bootstrap;
  janela estável de 3 s (Dolphin) atualiza o offset com a média do repouso
  (stillness absoluta + accel ≈ 1g quando conhecido); a fonte registra gyro+accel
  (padrão SDL).
- **P2-5 — rumble**: contrato único `rumbleDevice(deviceId, low, high, durationMs)`
  (SDL: ≥2 vibrators low/high por motor, 1 motor mix 0.6/0.4, cancel em 0); os
  efeitos de menu passam pelo mesmo contrato.
- **P2-6 — touchpad**: arrasto (≥650 ms = BUTTON_LEFT contínuo), duplo-toque =
  clique direito (opt-in por perfil) e dead zone de pós-toque (bounce) — spec
  próprio `2026-08-14-touchpad-drag-double-tap.md`.
- **P2-7 — threading**: entrega documentada como MAIN THREAD (decisão A — o
  `registerListener` sem Handler usa o Looper de quem registrou).
