# Spec 2026-08-16 K1 — Gamepad virtual de toque unificado ao pipeline do fork (o "Steam Input mobile")

**Data:** 2026-08-16
**Origem:** SDL3 `reference/SDL/src/joystick/virtual/SDL_virtualjoystick.c`
(dispositivo virtual ANUNCIA seus próprios botões/eixos/sensores e entra no
mesmo pipeline dos físicos); moonlight `reference/moonlight-android/.../
virtual_controller/` (`VirtualController` injeta no MESMO `ControllerHandler`
dos pads físicos — OSC e físico dividem pipeline); RetroArch overlay
(`tasks/task_overlay.c:6680` no `input_driver.c` — overlay é "other input
source" sempre player 1); ppsspp (editor de layout). Atribuição obrigatória no
KDoc (identidade técnica do fork).
**Executor:** sub-agente autônomo. Leia `AGENTS.md` + master roadmap universal §1/§2.
**Posição na fila:** fase K1 (a MAIOR — depois de K2; commit de fronteira antes).
**Turn budget sugerido:** 35–45 turns. Esforço L.

## 0. Estado atual (anchors — os DOIS mundos de input que hoje não se falam)

**Mundo winlator (legado, direto no X):**
- `com/winlator/inputcontrols/ControlsProfile.java` — profile do overlay de
  toque: elementos posicionáveis; flag `virtualGamepad` (:30, setado em :306-310
  quando há binding de gamepad), persistência própria.
- `com/winlator/inputcontrols/ControllerManager.java` (562 linhas) — binds de
  controle EXTERNO por keycode direto, monta `GamepadState`, injeta no X.
- `TouchMouse.java` — touch como mouse (fica como está).
- `XServerScreen.kt:3602` `virtualGamepadActive` + `ControllerSlotUiState`
  (:3617) — a UI de slot já distingue virtual.

**Mundo fork (o pipeline rico):**
- `gamepad/GamepadHub.kt` — `addDevice`/hotplug, `remapEvent` (:726), flush da
  fase I, expressões J, camadas/radial/turbo, `profileFor(deviceId, appId)`
  (perfil por JOGO), haptics por device.
- `gamepad/InputEvent.kt` + `EventDispatcher` (`PluviaApp.events`, multicast por
  identidade `===`).

**O problema:** o overlay de toque injeta DIRETO no X — touch controls não têm
camadas, expressões, radial, turbo, remap por perfil, nada do que as fases
A–J/H–J construíram. Para jogos sem controle físico (o caso mobile puro), a
"linguagem Steam Input" do fork não existe. Este spec fecha isso: o overlay
passa a ser um **dispositivo virtual no hub**.

## 1. Design

### 1.1 Princípio (SDL virtual joystick)

O dispositivo virtual se REGISTRA no hub como um `GamepadDevice` de verdade —
com capabilities, mapping e perfil próprios — e emite `RawInput` eventos pelo
mesmo caminho dos físicos. Todo o pipeline (remap → expressões → camadas →
radial → turbo → injeção U4) funciona sem saber que é touch.

```kotlin
// gamepad/virtual/TouchGamepadSource.kt (NOVO)
object TouchGamepadConstants {
    const val DEVICE_ID = -0x7C0A1               // marcador estável (negativo = nunca InputDevice real)
    const val DESCRIPTOR = "gamenative-virtual-touch"
}
```

- `GamepadDevice(deviceId = DEVICE_ID, descriptor = DESCRIPTOR, vid/pid = 0,
  name = "Virtual touch gamepad", deviceClass = VIRTUAL, faceStyle = XBOX,
  capabilities = sintetizado do layout do ControlsProfile em uso)`.
- Registro: lazy no primeiro uso do overlay (não no boot — dispositivo fantasma
  na UI de settings é ruído); remoção quando o overlay fecha para sempre
  (XServer destruído).
- `MappingDatabase`: entry fixa `virtual` → mapping identidade (o layout do
  overlay já fala em botões LÓGICOS — FACE_BOTTOM etc. — então o mapping raw→
  lógico é identidade; quirks nunca aplicam).

### 1.2 Ponte do elemento de overlay → RawInput

Em vez de reescrever o overlay (NÃO-meta explícita), interceptar no ponto onde
`ControllerManager`/elementos de `ControlsProfile` geram estado:
- `ControlElement` de botão down/up → `hub` recebe `RawInput.Button(logical,
  isDown)` do `DEVICE_ID` virtual (analogicamente eixos de stick do overlay →
  `RawInput.Axis` com valor 0..1 normalizado do elemento).
- O caminho de injeção LEGADO do ControllerManager fica inibido quando o
  pipeline novo está ativo (flag 1.4) — nunca os dois.
- Sticks analógicos do overlay: o elemento já rastreia posição relativa —
  emitir no `RawInput.Axis` com a deadzone/curva do PERFIL do device virtual
  (usuário calibra como qualquer stick).

### 1.3 O que o device virtual HERDA de graça (a lista do porquê)

Camadas H/I/J (HOLD/LONG_PRESS/SEQUENCE), expressões/chords J1/J2, radial menu
F (o overlay no HOLD mode já cobre touch — agora o radial também responde ao
virtual), turbo F, macros D (se source disponível), perfil POR JOGO
(`profileFor(DEVICE_ID, appId)` — layouts diferentes por jogo continuam no
ControlsProfile, mas afinações de curva/turbo passam a ser perfil do fork),
diagnóstico do card C (o virtual aparece como device com viewer).

### 1.4 Migração/flag — degradação byte-identical

- Pref global (PrefManager): `virtualGamepadPipeline` **default false** —
  comportamento atual intacto (injeção legada). ON → registro no hub + injeção
  via `PhysicalControllerHandler` (o MESMO caminho U4 do físico).
- Migração de profiles existentes: nada obrigatório; ao ligar a flag pela
  primeira vez com um `ControlsProfile` ativo, criar `GamepadProfile` default
  para o device virtual (sem profile salvo = AUTO, como qualquer device).
- Toggle na seção Gamepad dos settings + um row no QuickMenu (quando overlay
  ativo) para ligar/desligar sem sair do jogo.

### 1.5 Restrições duras

- **`XServerScreen.kt`: ZERO locals novas na função principal** (limite dex —
  regra do master). Toda a ponte vive em `gamepad/virtual/` +
  `PhysicalControllerHandler`/`ControllerManager`; se algo precisar de estado
  na screen, é 1 holder `remember` num componente PRÓPRIO (padrão do
  QuickMenu).
- Hotplug/threading: hub é main-thread (doc em `GamepadHub.kt:149-150`) — a
  ponte emite na main thread (callbacks de touch já são).
- O editor de layout do winlator PERMANECE (movimentar/redimensionar
  elementos é dele); o que muda é para ONDE o elemento emite.

## 2. Arquivos afetados

| Arquivo | Mudança |
|---|---|
| `gamepad/virtual/TouchGamepadSource.kt` | NOVO — device virtual + registro + ponte (1.1/1.2) |
| `gamepad/GamepadDevice.kt`/`DeviceClass.kt` | `DeviceClass.VIRTUAL` |
| `gamepad/mapping/MappingDatabase.kt` | entry identidade `virtual` |
| `com/winlator/inputcontrols/ControllerManager.java` | inibir injeção legada sob a flag; emitir RawInput (1.2) |
| `com/winlator/inputcontrols/ControlsProfile.java` | (só leitura p/ capabilities) |
| `gamepad/GamepadHub.kt` | addDevice/removeDevice do virtual (reuso, sem mudança estrutural) |
| `ui/screen/settings/SettingsGroupGamepad.kt` + QuickMenu row | flag + toggle (1.4) |
| `ui/screen/xserver/XServerScreen.kt` | NO MÁXIMO 1 holder remember (1.5) |
| `res/values*/strings.xml` | chaves |
| `app/src/test/.../TouchGamepadSourceTest.kt` | NOVO — mapping identidade; elemento→RawInput; flag OFF = nada registrado |

## 3. Verificação (gate)

```
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:testModernDebugUnitTest \
  --tests "*Virtual*" --tests "*TouchGamepad*" --tests "*Gamepad*"
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:assembleModernDebug
```

## 4. On-device (humano — "on-device pendente"; harness cobre boa parte)

1. Flag OFF (default): overlay funciona como hoje (regressão zero — jogo roda,
   estado byte-identical).
2. Flag ON + profile de touch existente: botões do overlay controlam Silksong
   igual, MAS: uma camada HOLD configurada no perfil do device virtual
   remapeia os botões do overlay; um chord J2 no FACE_BOTTOM+FACE_RIGHT em vez
   do par simples — prova de pipeline único.
3. Harness `adb shell setprop debug.gamenative.input "touchtap"` + virtual
   ativo: eventos chegam ao hub (log `gncontrol` com deviceId virtual).
4. Card de diagnóstico mostra "Virtual touch gamepad" com viewer acendendo ao
   tocar o overlay.
5. Radial aberto pelo overlay (touch) navega por TOUCH; gatilho de camada do
   virtual por touch funciona.

## 5. Não-metas

Reescrever o editor de layout do winlator; overlay como fonte de MOUSE (o
TouchMouse fica); multi-virtual (2 overlays); sensors no virtual (gyro do
PHONE como fonte do virtual é follow-up natural — registrar no impl doc);
migrar os profiles `.xml` do winlator para o formato do fork.
