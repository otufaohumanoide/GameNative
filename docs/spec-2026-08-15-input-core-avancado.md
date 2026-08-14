# Spec 2026-08-15 — Input Core Avançado (rev.3)

**Data:** 2026-08-15
**Origem:** avaliação externa ("Missing Link", Qwen) + verificação das features
U1–U7/V12 + crítica da arquitetura in-process (Wine + X server + evshim).
**Decisões-chave:** nada de uinput//dev/input raw/rewrite C++ (sem medição);
foco em transformação pura Kotlin (padrão do repo), ponte Wine existente e UX.
**Regra de execução:** cada fase tem critério de aceite; a próxima só começa com
a anterior verificada. F0 é pré-requisito de qualquer otimização.

**Status geral (2026-08-14, sessão de implementação — detalhes em
`spec-2026-08-15-input-core-avancado-impl.md`):**
- F0 — ✅ instrumentação entregue e verificada (HUD + `latency:report` no device);
  ⏳ baseline p95 pendente (DS4 desligado — religar manualmente e rodar o protocolo
  da seção F0).
- F1 — ✅ completo (StickTransform, FlickStick, GyroFusion/Mahony, SdlControllerDb
  com asset pinado 42f28e22, 12 campos de perfil, GUI no remap dialog, 36 testes
  novos). On-device pendente (exige controle físico).
- F2 — ✅ F2.1 (rumble consolidado no contrato P2-5), F2.2 (decisão (c) registrada
  acima — sem código), F2.3 (LAYER_TICK + toggle), F2.4 (auditoria feita + gap de
  foco fechado).
- F3 — ✅ F3.1 (radial menu: core/store/executor/overlay/editor + item no QuickMenu),
  F3.2 (setActiveAppId com re-emissão de bindings), F3.3 (schemaVersion + export/
  import por arquivo SAF). On-device pendente (exige controle físico).

---

## 0. Estado verificado (features já entregues — não reimplementar)

| Item | Status | Evidência |
|---|---|---|
| U1 gyro→mouse/câmera | ✅ | `GamepadSensorSource.kt` + `GyroProcessor.kt:94` + `GamepadHub.kt:339-419` + `PhysicalControllerHandler.kt:339` + GUI `GamepadRemapDialog.kt:360-430` |
| U2 touchpad→mouse | ✅ | `TouchpadProcessor.kt` (drag 650 ms, duplo-toque→direito opt-in) + `GamepadTouchpadForwarder` no gate do MainActivity |
| U3 camadas HOLD/TOGGLE/DOUBLE_TAP | ✅ | `LayerTriggerSpec.kt:25` + `LayerResolver.kt` + triggers `GamepadHub.kt:251-252` |
| U4 remap no JOGO | ✅ | `PhysicalControllerHandler.kt:123,170-177,469-552` |
| U5 rumble por device | ✅ | `GamepadHaptics.kt:105` contrato SDL low/high/duration/cancel, dual-motor API 31, fallback API 16 |
| U6 OK/Cancel FaceStyle + deadzone menu por device | ✅ | `LibraryScreen.kt:851-861,1191-1192` + `menuDeadzoneFor` (`GamepadHub.kt:159`) |
| U7 bateria + badges GYRO/TOUCHPAD | ✅ | `GamepadHub.kt:509,537-567` + `SettingsGroupGamepad.kt:270-279` |
| V12 harness `touch:*`/`gyro:x:y:z` | ✅ | `DebugGamepadInput.kt:39-42,166-213` |

**Achado:** a ponte Wine→Java de rumble **já existe** (`evshim.c:196-209` +
`WinHandler.java:774-879`), mas NÃO usa o contrato P2-5 (usa `getVibrator()`
legacy + `max(low,high)`). F2.1 consolida.

## 1. Veredito sobre as recomendações externas

| Recomendação | Decisão | Motivo |
|---|---|---|
| uinput / HID virtual | ❌ Rejeitada | Exige root; o jogo roda in-process (Wine + X server próprio) — o gamepad XInput já é emulado pelo xserver; HID no kernel = loop do próprio evento, +latência |
| /dev/input raw + C++/Rust (JNI) | ⏸ Arquivada salvo F0 | Alegação "20 ms→5 ms" sem medição; DS4/BT reporta ~60–250 Hz, Kotlin na main thread dá conta. Medir primeiro |
| Instrumentation/Accessibility | ❌ Rejeitada | Ferramenta p/ apps externos; a superfície do jogo é nossa (XServerScreen) |
| DualSense HD haptics (voice coil) | ❌ Rejeitada | API pública Android só expõe vibrators ERM; raw HID L2CAP a controle pareado é bloqueado. Não-meta |
| Madgwick/Mahony | ✅ Aceita (Mahony) | Accel do controle já é lido (`GamepadSensorSource.kt:107,142`); Mahony é leve e tunável (Kp/Ki). **Honestidade técnica:** fusão corrige só pitch/roll (accel referencia gravidade); yaw segue no recenter+calibração existentes |
| Response curve LUT/Bezier | ✅ Aceita | LUT de N pontos + interpolação, JSON export/import |
| Flick Stick threshold + snap | ✅ Aceita | activationRadius 0.85 + snapAngle 15° |
| Gyro como eixo "perde precisão 16-bit" | ❌ Rebate | ±10 rad/s sobre 32767 steps ≈ 0,0006 rad/s < ruído DS4 (~0,002 rad/s). Obra real: `evshim.c:241` tem `naxes=6, axis_mask=0x3F` — todos ocupados; ver F2.2 |
| Radial menu só por touch | ⚠️ Híbrido | Touch-first (padrão Backbone) + fallback stick (usuário gamepad-only/HDMI) |
| Tick háptico em camadas | ✅ Aceita | Novo `HapticEffect.LAYER_TICK` (`EFFECT_CLICK` API 30+) |
| WindowInsets/PointerCapture/Auto-Pause | ✅ Já existe | `MainActivity.kt:744`, `AppUtils.java:90`, `XServerScreen.kt:1003,2391-2398`. Vira auditoria F2.4 |
| Rumble low/high dual-motor | ✅ Já existe | Contrato `rumbleDevice` (motor0=low, motor1=high, mix, cancel). Falta só o WinHandler usar (F2.1) |

## 2. Arquitetura alvo (3 camadas, todas já com casa no código)

| Camada | Onde vive | Regra |
|---|---|---|
| Ingestão | `MainActivity` (gate) → `GamepadHub` | Multicast por identidade `===`; estado lido no momento do evento (nunca closure stale) |
| Transformação | `app/gamenative/gamepad/processing/*` | **Objetos puros Kotlin, zero android.\*, JVM-testáveis** (padrão `GyroProcessor`/`TouchpadProcessor`) |
| Saída | `PhysicalControllerHandler` → xserver/evshim → Wine | Remap efetivo DEFAULT+ativa (U4); rumble via `GamepadHaptics` |

## 3. Fases (ordem obrigatória de implementação)

### F0 — Medição de latência (PRÉ-REQUISITO)

**Objetivo:** baseline antes de discutir qualquer migração de linguagem.

- `LatencyDebugOverlay` (arquivo próprio — limite dex do XServerScreen):
  t0 no `dispatchKeyEvent`/`onSensorSample` → t1 no `PhysicalControllerHandler`;
  HUD com p50/p95; toggle `debug.gamenative.latency 1`.
- Verbo novo no harness V12: `latency:report` (dump agregado no logcat).
- **Critério de saída:** baseline Mi 11/DS4 BT (Silksong). Se p95 < 16 ms ⇒
  migração C++/Rust é **arquivada por falta de evidência** (registrar na spec).

**Status de implementação (2026-08-14):** ✅ instrumentação entregue e verificada —
`LatencyTracker` (processing/, puro, anel 4096/fonte, correlação begin/end por slot
pendente + janela de frescor 100 ms, 11 testes JVM verdes), stamps em
`MainActivity.dispatchKeyEvent/dispatchGenericMotionEvent` (t0 KEY/MOTION),
`GamepadHub.onSensorSample` (t0 SENSOR) e `PhysicalControllerHandler.onKeyEvent/
onGenericMotionEvent/applyCameraGyro` (t1); HUD verificado no device (bbox verde
no topo-esquerdo, linhas `KEY/MOTION/SENSOR: no samples`); verbo `latency:report`
confirmado no logcat. **On-device pendente:** baseline Mi 11/DS4 — o DS4 está
pareado mas DESLIGADO e não há como religá-lo via adb (sem root); a medição com
amostras reais exige o controle ligado. Nada de migração C++/Rust até o baseline.

### F1 — Input Core (Kotlin puro + GUI por device)

Ordem interna: 1→2→3→4.

1. **`StickTransform`** (novo, `processing/`):
   - Deadzone **radial** (rescale normalizado, anti-drift) e **axial** (por eixo,
     p/ gates de andar) — o perfil escolhe.
   - Response curves: LINEAR / EXPONENTIAL / SCURVE + **LUT custom** (N pontos,
     interpolação linear, JSON no `GamepadProfile`, clamp + `ignoreUnknownKeys`).
2. **`FlickStickProcessor`** (novo, modo do stick DIREITO por perfil):
   - Flick: ângulo do stick → yaw instantâneo; deflexão radial acima de
     `activationRadius` (0.85) → taxa contínua; `snapAngle` (15°) descarta
     micro-giros acidentais; saída rad/s → `applyCameraGyro` (mesma unidade U1).
3. **`GyroFusion`** (Mahony, opt-in por perfil):
   - 6-DOF (gyro+accel do device, já coletado); Kp/Ki tunáveis; corrige pitch/roll
     no modo CAMERA; yaw permanece recenter+calibração contínua (P2-2).
   - OPT-IN porque stacks BT distintos expõem accel com qualidade variável;
     fallback = caminho atual (byte-identical quando desligado).
4. **`SdlControllerDb`** (asset `gamecontrollerdb.txt` pinado por commit):
   - GUID→layout para auto-mapear controles genéricos; fallback = `DeviceClassifier`.

**GUI (todas na seção Gamepad por device — `GamepadRemapDialog`):**
- Stick: seleção deadzone radial/axial + seleção de curva + preview LUT
  (read-only, Canvas) + import/export JSON (SAF);
- Flick Stick: toggle "Stick direito = Flick" + sliders threshold/snap;
- Gyro: switch "Fusão de sensor (experimental)".
- Strings EN + pt-rBR; persiste no `GamepadProfile` (JSON, `schemaVersion`).

**Testes JVM (filtros, nunca a suíte inteira):**
`--tests "*StickTransform*" --tests "*FlickStick*" --tests "*GyroFusion*" --tests "*SdlControllerDb*"`
- LUT roundtrip JSON; Mahony converge sem drift de pitch acumulado; Flick
  respeita snapAngle; fallback do DeviceClassifier byte-identical.

### F2 — Ponte Wine (evshim/xserver)

1. **Consolidar rumble do jogo:** `WinHandler.startDeviceVibration` passa a chamar
   `GamepadHaptics.rumbleDevice(deviceId, low, high, duration)` — herda
   dual-motor (DualSense), cancel, mix 1-motor e o gate `gamepadRumbleEnabled`.
   Remover o `max(low,high)` custom do WinHandler.
2. **Gyro→eixo XInput (INVESTIGAÇÃO ANTES DE CÓDIGO):** evshim declara
   `naxes=6, axis_mask=0x3F`. Avaliar, nesta ordem: (a) 7º/8º eixo no SDL
   virtual joystick e como o Wine enumera (DirectInput vs XInput — mudar `naxes`
   muda a identidade do device, risco de quebra de mapeamento); (b) "gyro mouse"
   como device evdev virtual adicional in-process; (c) se nada for limpo:
   manter CAMERA mode (rate→câmera) como caminho canônico — **precisão 16-bit
   NÃO é o bloqueio** (0,0006 rad/s < ruído do sensor). Registrar decisão na spec.

   **Decisão (2026-08-14, investigação feita — código NÃO alterado): (c) CAMERA
   mode permanece o caminho canônico; evshim intocado.** Fatos levantados:
   - `evshim.c:238-249` cria UM SDL virtual joystick por player identificado como
     **Xbox 360 (045E:028E)** — identidade XInput, não DirectInput. O contrato
     XINPUT_GAMEPAD tem EXATAMENTE 6 valores analógicos (lx/ly/rx/ry/lt/rt); eixos
     7/8 não existem nesse ABI — jogos XInput (a maioria; Silksong incluso)
     IGNORARIAM o eixo extra mesmo que o SDL o expusesse.
   - (a) `naxes=8` só alcançaria jogos DirectInput (DIJOYSTATE tem 8 eixos) ao
     custo de RE-CRIAR o device virtual no SDL (attach/detach muda o device index/
     instance id — risco real de quebra de mapeamento/identidade em jogos que
     cacheiam o pad, risco §6 do spec). Custo-benefício inviável para um eixo que
     o público-alvo XInput não lê.
   - (b) "gyro mouse" como device virtual adicional: SDL não tem API de mouse
     relativo virtual; e o fork JÁ tem o caminho in-process equivalente — o U1
     MOUSE mode injeta no xserver direto (`xServerMouseSink` →
     `injectPointerMoveDelta`), sem device novo. Redundante.
   - Precisão: ±10 rad/s em 32767 steps ≈ 0,0006 rad/s por step < ruído do DS4
     (~0,002 rad/s) — o 16-bit do stick virtual NÃO é o gargalo (rebate do spec).
   - Conclusão: o dado do gyro chega ao jogo pelas DUAS saídas que já existem
     (MOUSE = cursor; CAMERA = right stick do virtual pad via applyCameraGyro),
     sem tocar no evshim. F2.2 fecha sem código.
3. **Tick háptico de camada:** `HapticEffect.LAYER_TICK` (`EFFECT_CLICK` API 30+,
   fallback one-shot 10 ms) disparado na ativação de layer (U3) e no setor do
   radial menu; toggle global em `SettingsGroupGamepad` (`gamepadRumbleEnabled`
   guarda tudo).
4. **Auditoria de foco (já existe — verificar gaps):** TRANSIENT_BARS_BY_SWIPE,
   pointer capture + release em overlays, auto-pause em background. Gap a fechar:
   ativação de layer/tick durante perda de foco = no-op (nunca input fantasma).

   **Auditoria (2026-08-14):** ✅ TRANSIENT_BARS_BY_SWIPE presente
   (`MainActivity.kt:748` + `AppUtils.java:90`); ✅ pointer capture guardado por
   overlay states (`XServerScreen.kt:1003` tryCapturePointer nega com
   editor/QuickMenu/edit-mode; `releasePointerCapture` no gameBack); ✅ auto-pause
   em background (`XServerScreen.kt:2391-2398` pausa pós-setup com app em
   background; MainActivity.onPause suspende sensores). **Gap fechado:** `hub.windowFocused`
   (MainActivity.onWindowFocusChanged) — `resolveLayerTriggers` é no-op sem foco ⇒
   ativação de camada E tick háptico nunca disparam com a janela sem foco.

### F3 — UX next-gen

1. **Radial Menu** (overlay no XServerScreen; arquivos próprios):
   - Ativação: trigger de camada existente (U3) — ex. hold Select/L3;
     seleção **touch-first** (slide no overlay; pausa o input do jogo + dim,
     reusa `pauseForOverlayIfAllowed`), fallback stick com `GamepadMoveDedupe`
     no bus; até 8 setores/macros (sequência de teclas com timing); tick
     háptico por setor (F2.3).
   - Editor de setores/macros: componente novo (estilo `GamepadRemapDialog`),
     aberto pelo QuickMenu; binding do gatilho no editor de camadas existente.
2. **Auto-profile switching:** re-resolver perfil por `activeAppId` na troca de
   container em foreground, SEM reconectar (re-emissão de bindings). Zero GUI nova.
3. **Perfis JSON cloud-ready:** `schemaVersion` + export/import por arquivo
   (share intent/SAF) na tela de perfis; estrutura pronta p/ repositório futuro
   (sem download no escopo).

## 4. GUI por feature (resumo)

| Feature | Superfície | Padrão a seguir |
|---|---|---|
| Deadzone/curva/LUT | Seção Stick, `GamepadRemapDialog` | Sliders da seção Gyro (`:360-430`) |
| Flick Stick | Seção Stick (toggle + 2 sliders) | Switch duplo-toque (`:457`) |
| Mahony | Switch na seção Gyro | Idem |
| Tick de camada | Toggle em `SettingsGroupGamepad` | `gamepad_touchpad_mouse_title` (`:174`) |
| Radial menu | Overlay + editor novo + gatilho no editor de camadas | Overlays do XServerScreen + `GamepadMoveDedupe` |
| Latency HUD | Overlay debug (arquivo próprio) | `DebugGamepadInput` |
| Auto-profile | Nada | — |
| Perfis export/import | Tela de perfis | Padrão de export do fork |

## 5. Verificação

- **JVM:** filtros por fase (F1 acima); F2/F3 só lógica pura (`*LayerTick*`,
  `*RadialMenu*` se houver store — usar abstração fake, padrão `ShaderPagingLogic`).
- **Build:** `assembleModernDebug` (librashader de fonte — ver AGENTS.md p/ toolchain).
- **On-device (Mi 11 + DS4, Silksong):** `tools/quickmenu-verify.sh` + harness
  `touch:`/`gyro:`/`latency:`; pendências registradas aqui como "on-device pendente".
- **Workflow:** spec → revisão → implementação → impl doc → MILESTONES
  (`tools/milestone.sh`). Commits `feat(gamepad): ...` referenciando esta spec.

## 6. Riscos

- XServerScreen no limite do verifier dex → overlay/editor em arquivos próprios,
  zero locals novas na função principal.
- Mahony com accel de BT ruim → opt-in + fallback + teste de degradação.
- evshim `naxes>6` muda identidade do device no Wine → gate pela investigação F2.2.
- LUT JSON malformada → clamp + `ignoreUnknownKeys`; nunca crash no boot do jogo.
- `build/` com classes stale (gotcha do repo) — limpar ao menor indício.

## 7. Fora de escopo (não-metas)

uinput; leitura raw de `/dev/input`; rewrite C++/Rust do pipeline (arquivado
salvo F0 contradiga); HD haptics voice-coil do DualSense; correção de yaw por
fusão (sem magnetômetro); remapeamento de apps externos ao fork.
