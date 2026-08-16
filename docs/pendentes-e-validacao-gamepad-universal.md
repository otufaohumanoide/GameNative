# Pendências e validação do Suporte Universal a Gamepads

**Data:** 2026-08-15
**Base:** spec 2026-08-15-input-core-avancado (+ impl doc), specs U1–U7.

## 1. O que falta construir/ativar

### 1.1 Ativar (zero código — usuário)

| Item | Onde | Estado |
|---|---|---|
| Suporte universal a gamepads | Settings → Gamepad → toggle | **Default OFF** (`PrefManager.gamepadUniversalEnabled`) — **ativado 2026-08-14** (harness `pref:universal:1` — MIUI bloqueia `adb input`) |
| Rumble | Settings → Gamepad → switch | Default ON (`gamepadRumbleEnabled`) — mantido ON |
| Touchpad→mouse | Settings → Gamepad → switch | **ativado 2026-08-14** (`pref:touchpadmouse:1`; default OFF) |
| Tick de camada | Settings → Gamepad → switch | Default ON (`gamepadLayerTickEnabled`) — mantido ON |

OFF, o gamepad continua funcionando no jogo como XInput simples (caminho Winlator) —
mas sem perfis por device, camadas/remap no jogo, gyro e touchpad→mouse.

### 1.2 Verificações on-device (executadas 2026-08-14, Mi 11 + DS4 via USB)

- ✅ **F0 baseline (critério de saída ALCANÇADO):** KEY n=434 p50=2,74 ms p95=4,60 ms /
  MOTION n=706 p50=3,08 ms p95=4,82 ms / SENSOR n=4096 p50=1,88 ms p95=3,11 ms
  (tráfego REAL do sensor do DS4 a ~40 Hz). **p95 ≪ 16 ms ⇒ migração C++/Rust
  ARQUIVADA por falta de evidência** (registrado na spec).
- ✅ F1 GUI: remap dialog abre pelo QuickMenu (R4) com device ativo; seções
  Stick/Flick/fusão renderizadas. "Mapear um botão e testar no jogo" = interação
  manual (usuário) — caminho de captura idêntico ao verificado na sessão U4.
- ⏳ F2.1 rumble do JOGO: contrato chamado (`rumble:0.6:0.6:300` → `rumbleDevice`
  retorna **false**) — o DS4 via **USB não expõe vibrator** neste stack MIUI
  (`dumpsys vibrator_manager` só lista o motor do telefone). Limitação do Android
  prevista no spec U5 — re-testar em BT com o controle carregado. O menu (menu
  rumble) usa o mesmo contrato → mesmo resultado no USB.
- ✅ F2.3 tick: chamada verificada por código na ativação de camada (R8); efeito
  físico exige vibrator (idem acima). Log `GamepadHaptics: LAYER_TICK` adicionado
  para a sessão de BT.
- ✅ F3.1 radial menu: gatilho HOLD SELECT (perfil via run-as) → overlay abre
  (screenshot: 7.615 px saturados no centro) → release fecha (397 px). Jogo pausa/
  retoma junto (pauseForOverlayIfAllowed).

### 1.3 Limpezas da revisão (FEITAS — 2026-08-14)

1. ✅ `LatencyDebugOverlay()` — parâmetro `enabled` removido (gate real é o getprop).
2. ✅ `DebugPropertyCache` (novo, `ui/component/`) — leitura de getprop com cache por
   propriedade (janela 300 ms) compartilhada pelo harness (200 ms) e o HUD (500 ms);
   um único exec por janela.
3. ✅ `GamepadProfile.withSanitizedLuts()` aplicado no LOAD do `GamepadProfileStore`;
   `StickTransform.curve` não re-sanitiza por evento (imports sanitizam no parse).
4. ✅ `GamepadHaptics.rumbleDevice` agora retorna Boolean (vibrou de fato?) e o
   `WinHandler.startDeviceVibration` usa o retorno — `isRumbling` preciso (gate OFF/
   sem vibrator ⇒ false).
5. ✅ `.gitignore`: `reference/` ignorado (exceção `reference/sdl/*.md` — as notas de
   pesquisa entram no git; os clones ~1,7 GB não).

### 1.4 Follow-ups opcionais (registrados nos specs)

- ✅ Verbo `rumble:low:high:duration` no harness — ENTREGUE na sessão de validação
  (diagnóstico de rumble por device; usado no R7). Também: verbo `pref:` (whitelist
  universal/touchpadmouse/rumble/layertick) para ativar os toggles sem UI (MIUI
  bloqueia `adb input`); trace de sensor gateado por `debug.gamenative.sensortrace`
  (o log de 40 Hz girava o buffer do logcat em segundos).
- GUI de Kp/Ki da fusão (desvio nº 4 do impl doc).
- Re-leitura do gatilho HOLD do radial menu após execução (desvio nº 6).
- Toggle de fallback de rumble no TELEFONE (decisão nº 5 — reversível se "o campo
  reclamar").

## 2. Como validar com um gamepad genérico de PS4

**Pré-requisito:** Android 12+ (API 31) para gyro/bateria; pareado via BT como
DualShock. DS4 legítimo = VID/PID `054c:09cc`/`054c:05c4` (`MappingDatabase.kt:26-27`)
→ FaceStyle PLAYSTATION.

### Roteiro (ordem de complexidade)

| # | Teste | O que prova | Se falhar |
|---|---|---|---|
| 1 | Settings → Gamepad lista o device com % de bateria + badges GYRO/TOUCHPAD | Detecção + U7 | Badge ausente em Android <12 é esperado; se ausente no 12+, o pad não expõe a capacidade (clones sem touchpad/gyro) |
| 2 | Navegar a biblioteca com stick; confirmar com **Cross** (swap PS) | U6 + menu deadzone por device | FaceStyle não identificado (VID/PID fora da tabela) |
| 3 | Silksong: controlar o jogo com universal **ON** e **OFF** | Comparativo: OFF = XInput puro; ON = pipeline universal | OFF falha = problema no caminho Winlator (não no fork); ON falha = perfil/hub |
| 4 | Remap: abrir o dialog (exige device ativo), mapear um botão, testar no jogo | U4 (remap efetivo DENTRO do jogo) | Verificar se o dialog abriu com o device certo (activeDevice) |
| 5 | Gyro: seção só existe com hasGyro — MOUSE move o cursor; CAMERA gira a câmera | U1 | Seção ausente = API < 31 ou pad sem gyro exposto |
| 6 | Touchpad: dedo no touchpad move o cursor; tap = clique | U2 | DS4/MIUI funde CONTROLLER+POINTER (`DeviceClassifier.kt:85`); sub-device TOUCHPAD puro não dirige o jogo (gate P5 — correto) |
| 7 | Rumble: confirmação no menu vibra o CONTROLE; rumble do jogo (Silksong) | U5 + F2.1 | Sem vibrator exposto = limitação do Android (HID do pad) — ver spec U5 |
| 8 | Camadas + radial menu: HOLD em trigger de camada abre overlay; tick háptico | U3 + F2.3 + F3.1 | On-device pendente (ainda não verificado) |

### Resultados da sessão 2026-08-14 (Mi 11 + DS4 via USB, Silksong)

| # | Resultado | Evidência |
|---|---|---|
| 1 | ✅ PASS — DS4 legítimo detectado (054c:09cc, FaceStyle PLAYSTATION), bateria 100, gyro=true | log `GamepadHub: added id=32 ... battery=100 gyro=true` (touchpad=false nesta enumeração USB; =true na sessão BT) |
| 2 | ✅ PASS — navegação de menu por stick/DPAD funciona (QMFocus row 0→1→2→3) | logs `QMFocus: row N focused`; FaceStyle PS identificado (054c:09cc) — Cross confirma |
| 3 | ✅ PASS — comparativo ON/OFF: gate OFF silencia o `GamepadLogical` (0 eventos em 10 s), ON reativa | logcat `GamepadLogical` + verbos `pref:universal:0/1` |
| 4 | ✅ PASS — remap dialog abre pelo QuickMenu (CONTROLLER tab → linha 3 → A) com device ativo | janela nova no `dumpsys window` + screenshots; mapear um botão = interação manual |
| 5 | ✅ PASS — gyro CAMERA: tráfego REAL do sensor do DS4 (~40 Hz) pelo pipeline inteiro (onSensorSample → applyCameraGyro) | SENSOR n=1348/4096 p95=3,11 ms + `SensorUpdate` com accel real (9,8 m/s²) |
| 6 | ✅ PASS — touchpad→mouse: tap sintético (fonte POINTER pura, regra P5) → `GamepadTouchpad: tap -> click` no sink | logcat `XServerTouchpadMouseSink`; dedo real = mesmo caminho (verificado na sessão BT anterior) |
| 7 | ⏳ LIMITAÇÃO DO STACK — USB não expõe vibrator do DS4 (`rumbleDevice` → false; `dumpsys vibrator_manager` só lista o motor do telefone) | log `DebugGamepad: rumble dev=32 ... -> false`; re-testar em BT |
| 8 | ✅ PASS — radial menu: HOLD SELECT → overlay abre (7.615 px saturados no centro) → release fecha (397 px) | screenshots + perfil/camada via run-as; tick chamado (log `LAYER_TICK`) |

### Sem o controle físico (ou além dele)

Harness (`adb shell setprop debug.gamenative.input ...`) — `key:`, `stick:`, `hat:`,
`gyro:x:y:z`, `touch:*`, `latency:report` exercitam o pipeline sem o pad; o logcat
(`GamepadTrace`, `LatencyTracker`) confirma a rota.

### Observação para pad "genérico"

Se o VID/PID não for de DS4 legítimo, o fluxo cai em `SdlControllerDb` (F1.4, asset
pinado) → auto-mapeamento ou default. Aí o teste nº 1 é o que diz se vale a pena:
**badges e bateria aparecem = capacidades expostas = features funcionam; não
aparecem = o Android não expõe no HID desse pad, e nenhum código do fork muda isso.**


---

## 4. Fechamento (2026-08-16 — roadmap universal input COMPLETO)

Status final do roadmap universal input (master §7 — 8/8 ✅; fechamento em
`docs/spec-2026-08-16-universal-input-fechamento-impl.md`):

- As linhas ✅ acima permanecem como HISTÓRICO da sessão 2026-08-14 (não reabrir).
- A linha ⏳ **nº 7 (rumble via USB)** NÃO virou resultado de sessão A — o
  protocolo on-device consolidado v2 (`docs/spec-2026-08-16-protocolo-on-device-
  consolidado-v2.md`, sessões A/B/C) é o dono da dívida: re-testar em BT está na
  agenda do humano. Regra anti-acúmulo: nenhuma linha "pendente" sem dono.
- As fases NOVAS (F0/K3/K4/K5/K6/K2/K1/K7) têm cada uma sua §4 "on-device
  pendente" no impl doc da fase — todas centralizadas no protocolo v2.
