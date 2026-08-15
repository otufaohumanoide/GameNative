# Spec de implementação — Input Core Avançado (F0–F3)

**Data:** 2026-08-14
**Base:** spec 2026-08-15-input-core-avancado.md (rev.3, aprovado).
**Status:** implementado (F0 instrumentação, F1 completo, F2 consolidado, F3 completo);
187 testes JVM verdes (0 falhas, filtros); `assembleModernDebug` OK; app + Silksong
rodando no device sem crash (dex do XServerScreen sustentou).
**Verificação on-device:** F0 HUD + `latency:report` verificados no Mi 11. Baseline
de latência (p95) **pendente** — DS4 pareado mas desligado (sem religamento via adb).
GUI/radial/flick/fusão exigem o controle físico — "on-device pendente" (padrão do repo).

---

## 1. O que foi implementado (evidências file:line)

### F0 — Medição de latência

| Peça | Arquivo | Detalhe |
|---|---|---|
| Tracker puro | `gamepad/processing/LatencyTracker.kt` (novo) | anel 4096/fonte (KEY/MOTION/SENSOR), correlação begin/end por slot pendente + janela de frescor 100 ms, sentinela -1, percentis método 7 |
| t0 ingestão | `MainActivity.kt` (dispatchKeyEvent/dispatchGenericMotionEvent) | `LatencyTracker.begin` dentro do bloco GamepadTrace |
| t0 sensor | `GamepadHub.kt` (`onSensorSample` topo) | par do t1 em applyCameraGyro |
| t1 handler | `PhysicalControllerHandler.kt` (onKeyEvent/onGenericMotionEvent/applyCameraGyro) | `LatencyTracker.end` no topo |
| HUD | `ui/component/LatencyDebugOverlay.kt` (novo) | p50/p95/max por fonte; toggle `debug.gamenative.latency 1`; verde < 16 ms |
| Verbo harness | `DebugGamepadInput.kt` (`latency:report`/`latency:reset`) | dump agregado no logcat |
| Testes | `LatencyTrackerTest` (11) | sobrescrita de pendência órfã, frescor, percentis, anel |

### F1 — Input Core

| Peça | Arquivo | Detalhe |
|---|---|---|
| F1.1 StickTransform | `gamepad/processing/StickTransform.kt` (novo) | deadzone radial/axial (reusa DeadzoneProcessor) + curvas LINEAR/EXPONENTIAL/SCURVE/LUT (interpolação linear, sanitização clamp — risco §6) |
| F1.2 FlickStick | `gamepad/processing/FlickStickProcessor.kt` (novo) | activationRadius 0.85, snapAngle 15° (protege a DIREÇÃO no hold; flick reto é legítimo), burst 120 ms, saída rad/s (unidade U1) |
| F1.3 GyroFusion | `gamepad/processing/GyroFusion.kt` (novo) | Mahony (Kp/Ki), correção SÓ pitch/roll (yaw permanece GyroProcessor — honestidade técnica), bootstrap por accel, degradação sem accel/em translação, deadzone+histerese própria |
| F1.4 SdlControllerDb | `gamepad/mapping/SdlControllerDb.kt` (novo) + `assets/gamecontrollerdb.txt` (pin 42f28e22) + `reference/sdl/gamecontrollerdb-notes.md` | entradas Android bus-style indexadas por (vid,pid); enum SDL→AKEYCODE; eixos a0..a5→X/Y/Z/RZ/LT/RT; fallback DeviceClassifier preservado |
| Perfil | `profiles/GamepadProfile.kt` | 12 campos novos + `schemaVersion`; `DeadzoneMode`/`ResponseCurve` @Serializable; merged/isDefault atualizados; store V1 preserva chaves |
| Integração jogo | `PhysicalControllerHandler.kt` (applyProfileDeadzone/processJoystickInput) | curva/modo/LUT custom assumem o pipeline do stick; flick escreve a deflexão e pula Z/RZ; CAMERA vence flick; sem override = byte-identical |
| Integração lógica | `GamepadHub.kt` (onAxis — modo radial/axial do perfil) | modo do LEFT stick vale para a navegação de menu |
| Fusão no hub | `GamepadHub.kt` (onSensorSample CAMERA) | opt-in substitui SÓ o pitch; desligado = byte-identical; estados V6 no removeDevice |
| Fallback DB no hub | `GamepadHub.kt` (`mappingFor`/`sdlDb()`) | asset lido UMA vez, preguiçoso, na primeira incógnita |
| GUI | `GamepadRemapDialog.kt` (seções Stick/Flick/fusão) | chips de modo/curva, preview Canvas read-only, LUT import/export SAF, sliders flick, switch fusão; strings EN + pt-rBR |
| Testes | `StickTransformTest` (10), `FlickStickProcessorTest` (9), `GyroFusionTest` (7), `SdlControllerDbTest` (10) | LUT roundtrip, Mahony converge sem drift, snap respeitado, fallback byte-identical |

### F2 — Ponte Wine

| Peça | Arquivo | Detalhe |
|---|---|---|
| F2.1 rumble consolidado | `WinHandler.java` (startDeviceVibration/stopDeviceVibration) | chama `GamepadHaptics.rumbleDevice` (P2-5): dual-motor, mix 1-motor, cancel, gate `gamepadRumbleEnabled`; `max(low,high)` E fallback de telefone removidos |
| F2.2 gyro→XInput | **sem código** — decisão registrada na spec | (c) CAMERA mode canônico: evshim é XInput (045E:028E, 6 eixos fixos do ABI); naxes>6 só alcançaria DInput ao custo de re-criar o device; mouse já existe in-process (U1) |
| F2.3 tick de camada | `GamepadHaptics.kt` (`HapticEffect.LAYER_TICK`/`tickDevice`) + `GamepadHub.kt` (resolveLayerTriggers) + `PrefManager.gamepadLayerTickEnabled` + toggle em `SettingsGroupGamepad` | EFFECT_CLICK API 30+ (fallback 10 ms); rumble global guarda tudo |
| F2.4 auditoria de foco | `GamepadHub.kt` (`windowFocused`) + `MainActivity.onWindowFocusChanged` | gap fechado: ativação de camada/tick = no-op sem foco; TRANSIENT_BARS_BY_SWIPE/capture/auto-pause verificados presentes |

### F3 — UX next-gen

| Peça | Arquivo | Detalhe |
|---|---|---|
| F3.1 core | `gamepad/radial/RadialMenuCore.kt` (novo) | geometria ângulo→setor, plano de macro com timing (hold/gap), JSON schemaVersion |
| F3.1 store | `gamepad/radial/RadialMenuStore.kt` (novo) | por jogo (appId), write atômico, degrade a vazio |
| F3.1 executor | `gamepad/radial/RadialMenuExecutor.kt` (novo) | KeyEvents sintéticos via dispatchKeyEvent (mesmo caminho do harness) com Handler postDelayed |
| F3.1 overlay | `ui/component/radial/RadialMenuOverlay.kt` (novo) | touch-first (slide → release executa; toque = cancela), fallback stick com janela 120 ms + tick por setor (F2.3), dim |
| F3.1 host | `ui/component/radial/RadialMenuHost.kt` (novo) | holder único (dex), listener de GamepadLayerEvent, pause/resume par-e-par via callbacks do XServerScreen, render do overlay + editor |
| F3.1 editor | `ui/component/radial/RadialMenuEditorDialog.kt` (novo) | gatilho (camada do perfil), 2–8 setores, captura de macro via bus cru, aberto pelo QuickMenu (item novo) |
| F3.2 auto-profile | `GamepadHub.kt` (`setActiveAppId`) + `XServerScreen.kt` | re-resolve por appId SEM reconectar + RE-EMISSÃO dos botões lógicos segurados pelo perfil novo (gyro re-arma) |
| F3.3 perfis cloud-ready | `GamepadProfile.schemaVersion` + export/import por ARQUIVO (SAF) no `GamepadRemapDialog` | estrutura pronta p/ repositório futuro |
| Testes | `RadialMenuCoreTest` (10) | geometria, plano, JSON roundtrip, store |

## 2. Desvios do spec (decisões registradas — com justificativa)

1. **F1.4: só entradas Android bus-style do DB** (65 de 299). As 234 legado
   (hex-do-nome, SDL ≤ 2.0.5) não carregam vendor/product — sem chave estável para o
   lookup do fork (que é por vid/pid, não por GUID). Documentado em
   `reference/sdl/gamecontrollerdb-notes.md`; o asset mantém as linhas para um
   futuro lookup por nome.
2. **Flick reto NÃO é suprimido pelo snapAngle.** A primeira leitura do spec podia
   sugerir "percurso angular ≥ 15° para o flick disparar" — isso mataria o gesto
   principal (empurra-e-solta sem girar). O snap protege a DIREÇÃO durante o hold
   contínuo (estabilidade contra micro-giros), não o gesto do flick.
3. **Flick em vertical puro = yaw 0** (cos(90°)=0). O contrato de saída é só-yaw
   (`yawRadS` → applyCameraGyro); pitch de Flick Stick é follow-up registrado.
4. **Fusão: Kp/Ki ficam nos defaults** (0.5/0) — a GUI do spec pede só o switch
   ("experimental"); os campos existem no perfil para afinar por JSON/follow-up.
5. **F2.1: fallback de vibrar o TELEFONE removido** (não só o max(low,high)). A
   consolidação pedida é caminho único no contrato P2-5; rumble é por device (U5) —
   sem vibrator no controle = no-op silencioso (V11). Reversível se o campo reclamar.
6. **F3.1: execução fecha o menu mesmo com o gatilho HOLD segurado** — reabrir exige
   soltar e segurar de novo (v1; releitura do gatilho é follow-up). O gatilho de
   camada continua pass-through (semântica U3: o botão também chega ao jogo).
7. **RadialMenuHost pausa via callbacks do XServerScreen** (`pauseForOverlayIfAllowed`/
   `resumeIfAllowedAfterOverlay`) em vez de tocar o xEnvironment direto — respeita
   neverSuspend/manualResume; par-e-par: só retoma o que o host pausou.

## 3. Verificação

### 3.1 JVM (feita)
`--tests "*StickTransform*" --tests "*FlickStick*" --tests "*GyroFusion*" --tests "*LatencyTracker*" --tests "*SdlControllerDb*" --tests "*RadialMenu*" --tests "*Gamepad*" --tests "*LayerResolver*" --tests "*Deadzone*" --tests "*Touchpad*" --tests "*GyroStick*" --tests "*ProfileStore*"` → **187 testes, 0 falhas** (nunca a suíte inteira — AGENTS.md).

### 3.2 Build (feita)
`JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:assembleModernDebug` → OK (librashader de fonte).

### 3.3 On-device (parcial — registrado como pendente no spec)
- ✅ App instala, Silksong roda (~70 fps), sem crash/VerifyError (limite dex do
  XServerScreen sustentou as chamadas novas).
- ✅ HUD de latência renderiza no topo-esquerdo; `latency:report`/`latency:reset`
  respondem no logcat (KEY/MOTION/SENSOR = no-samples sem controle conectado).
- ⏳ Baseline F0 (p95) — DS4 pareado mas DESLIGADO; religamento remoto impossível
  (sem root). Medir com o controle ligado: ligar coleta, jogar, `latency:report`.
- ⏳ GUI F1 (modo/curva/LUT/flick/fusão), rumble F2.1, tick F2.3 e Radial Menu F3.1 —
  exigem o controle físico (só o remap dialog abre com device ativo).

## 4. Riscos acompanhados (spec §6)

- Dex do XServerScreen: só +1 local (holder `radialState`) + 3 chamadas — sem locals
  novas na função principal (padrão respeitado).
- Mahony com accel ruim: opt-in + correção zerada fora da janela de 1g (testada) +
  fallback byte-identical desligado.
- evshim intocado (F2.2 decisão (c)) — zero risco de identidade do device.
- LUT malformada: sanitização no uso (clamp/descarte) — nunca exceção no boot.
- `build/` stale: build limpo usado nas verificações (sem indício de classe fantasma).
