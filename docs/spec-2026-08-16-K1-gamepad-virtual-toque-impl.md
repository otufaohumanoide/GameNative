# Impl doc — Spec 2026-08-16 K1 (gamepad virtual de toque no pipeline do fork)

**Data:** 2026-08-16
**Spec:** `docs/spec-2026-08-16-K1-gamepad-virtual-toque.md` (fase K1 da retomada
universal input — a MAIOR; depois de K2, commit de fronteira feito)
**Resultado:** implementado; gate completo verde (tests `*Virtual* *TouchGamepad*
*Gamepad*` + `assembleModernDebug`); commit `feat(gamepad): …` na §6.
Verificação on-device pendente (spec §4 — humano; o harness cobre parte).

## 1. O que foi feito (por seção do spec)

### §1.1 Device virtual + registro no hub

- `gamepad/virtual/TouchGamepadSource.kt` (NOVO):
  - `TouchGamepadConstants` (file:line 44): `DEVICE_ID = -0x7C0A1` (negativo —
    nunca um InputDevice real), `DESCRIPTOR`, `MAPPING_KEY = "00000000"`, nome.
  - `TouchGamepadBridge` (file:line 55) — conversões PURAS (JVM-testáveis):
    Binding do winlator ↔ RawInput do hub e InputEvent lógico → Binding da
    injeção final. `keyCodeFor` (file:line 60): GAMEPAD_BUTTON_A→96 ... DPAD→
    19-22 (o mapping do virtual é IDENTIDADE — os keycodes Android canônicos
    mapeiam para os próprios semânticos). `axisFor` (file:line 87): THUMB do
    overlay → eixos REAIS (LEFT_Y para UP/DOWN, LEFT_X para LEFT/RIGHT, RZ/Z
    para o direito) — o ControlElement emite o MESMO delta nas duas direções
    do par; o bridge usa o EIXO, não a direção (sem duplicar o eixo).
    `bindingFor`/`axisBindingsFor` (file:lines 129/153): inversa para a injeção
    final (GUIDE/extras → null — não existem no overlay legado).
  - `TouchGamepadSource` (file:line 170): registro LAZY no primeiro evento
    (`ensureRegistered`, file:line 176 — idempotente; "device fantasma na UI é
    ruído" do spec §1.1), remoção explícita (`unregister`, file:line 214),
    `emitBinding` (file:line 226, @JvmStatic — chamado do Java) → converte e
    injeta no hub (`onKey`/`onAxis` — o pipeline roda sem saber que é toque).
- `gamepad/GamepadHub.kt`: `registerVirtualDevice` (file:line 1909) — mesmo
  contrato do addDevice físico (caches invalidados, cadeia de mapping com a
  entry `00000000`, mappingSource no StateFlow, `GamepadDeviceAddedEvent` para o
  card de diagnóstico); `unregisterVirtualDevice` (file:line 1926) → removeDevice
  (todas as limpezas V6). Guardas do onKey/onAxis aceitam `DeviceClass.VIRTUAL`
  (antes só CONTROLLER).
- `gamepad/DeviceClass.kt`: +`VIRTUAL`.
- `gamepad/mapping/MappingDatabase.kt`: entry fixa `00000000` → identidade dos
  pads normalizados (`defaultAndroidMapping(XBOX)`, file:lines 43-47). Quirks
  nunca aplicam (quirkCache = null no registro).

### §1.2 Ponte do elemento de overlay → RawInput

`com/winlator/inputcontrols/ControlElement.java` (o ponto REAL onde o overlay
gera estado — o spec lista o ControllerManager, mas quem emite bindings é o
ControlElement; desvio documentado na §2):

- `emitBinding(binding, isDown, offset)` (file:line 1099): com
  `virtualGamepadPipeline` ON **E** `gamepadUniversalEnabled` ON (o pipeline é o
  universal; sem as duas o overlay volta ao caminho legado — nunca input morto)
  e o binding sendo GAMEPAD → `TouchGamepadSource.emitBinding`; senão →
  `handleInputEvent` legado (teclas/mouse do overlay seguem intactas —
  TouchMouse fica, não-meta §5). Todas as 16 chamadas de
  `inputControlsView.handleInputEvent` do ControlElement foram substituídas por
  `emitBinding` (overload sem offset).

### §1.3 Injeção final no jogo (caminho U4)

`ui/screen/xserver/PhysicalControllerHandler.kt`:

- `virtualGamepadListener` (file:line 99): registrado UMA vez no init
  (identity-registry do bus); para eventos do deviceId virtual, `injectVirtualEvent`
  (file:line 114) converte o LÓGICO do pipeline (ButtonDown/Up/AxisMotion) de
  volta em Binding do overlay e injeta pelo MESMO `injectBinding` do U4
  (handleInputEvent + sendGamepadState) — "injeção via PhysicalControllerHandler"
  do spec §1.3. Eixos: direção pelo sinal, magnitude clampada a 1, deadzone
  mínima de 0.01.
- cleanup (file:lines 197-199): listener desregistrado + `TouchGamepadSource.
  unregister()` — o virtual sai do hub quando o XServer é destruído; o próximo
  toque re-registra (lazy).

### §1.4 Flag + UI

- `PrefManager.virtualGamepadPipeline` (file:linha ~1466): default **false** —
  byte-identical (o ControlElement só roteia com a flag ON).
- `ui/screen/settings/SettingsGroupGamepad.kt`: switch na seção Gamepad
  (desligar remove o virtual do hub na hora).
- `ui/component/QuickMenu.kt`: seção "Virtual gamepad" com toggle sem sair do
  jogo (file:linha ~1858).
- `res/values*/strings.xml`: 3 chaves EN + pt-rBR.
- "criar GamepadProfile default para o device virtual" (spec §1.4): não é
  preciso — sem perfil salvo o `profileFor` resolve AUTO como qualquer device
  (o parêntese do próprio spec: "sem profile salvo = AUTO").

### Testes

`TouchGamepadSourceTest.kt` (NOVO, 11 testes): conversões de botões (faces/
dpad/l1-r2/sticks → keycodes canônicos; não-gamepad → null), down/up →
action 0/1 + deviceId virtual, sticks → eixos reais com valor cru (UP/DOWN
compartilham Y), release com offset → zero, inversa (lógico → Binding; GUIDE/
extras → null; pares de direção), entry identidade do MappingDatabase.

## 2. Decisões de design (desvios aceitos)

1. **Ponte no ControlElement, não no ControllerManager** (spec §1.2/§2 lista o
   ControllerManager): a auditoria do legado mostrou que quem emite bindings de
   GAMEPAD é o `ControlElement.handleTouch*` (o ControllerManager só gerencia
   slots/identidade). A mudança concentra-se em UM arquivo (16 chamadas → 1
   método).
2. **Flag dupla** (`virtualGamepadPipeline` AND `gamepadUniversalEnabled`): o
   virtual É o pipeline universal; ligar só o virtual deixaria o overlay mudo
   (o hub ignora com universal OFF). O ControlElement cai no legado sem as duas.
3. **Capacidades estáticas completas** do device virtual (spec §1.1: "sintetizado
   do layout do ControlsProfile"): o layout restringe o que EMITE (botões
   presentes no overlay), não o que o pipeline aceita — o mapping identidade é
   fixo e a síntese CAPABILITIES nem é alcançada (tier MODEL vence). Botões que
   não existem no overlay simplesmente nunca emitem.
4. **START do overlay não abre o QuickMenu** (o virtual não passa pelo
   `dispatchKeyEvent` — não há KeyEvent Android): o START do overlay vai ao jogo
   como qualquer botão. Documentado (o P1 do QuickMenu continua sendo físico).
5. **`InputEvent.deviceId` virou membro da interface** (era duplicado nas data
   classes): o listener do virtual precisava do id sem `when` por tipo — refactor
   mínimo, todas as data classes já tinham o campo.

## 3. Verificação (gate)

```
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:testModernDebugUnitTest   --tests "*Virtual*" --tests "*TouchGamepad*" --tests "*Gamepad*" --offline
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:assembleModernDebug --offline
```

- Resultado: VERDE (tests = 118, 0 falhas, 0 erros; assemble OK em 5 m) — §6.

## 4. On-device (humano — spec §4, "on-device pendente"; harness cobre)

1. Flag OFF (default): overlay funciona como hoje (regressão zero — jogo roda,
   estado byte-identical).
2. Flag ON + profile de touch existente: botões do overlay controlam Silksong
   igual, MAS uma camada HOLD configurada no perfil do device virtual remapeia
   os botões do overlay; um chord J2 no FACE_BOTTOM+FACE_RIGHT em vez do par
   simples — prova de pipeline único.
3. Harness `adb shell setprop debug.gamenative.input "touchtap"` + virtual
   ativo: eventos chegam ao hub (log `gncontrol` com deviceId virtual).
4. Card de diagnóstico mostra "Virtual touch gamepad" com viewer acendendo ao
   tocar o overlay.
5. Radial aberto pelo overlay (touch) navega por TOUCH; gatilho de camada do
   virtual por touch funciona.

## 5. Não-metas (spec §5 — confirmadas)

Reescrever o editor de layout do winlator; overlay como fonte de MOUSE (o
TouchMouse fica); multi-virtual (2 overlays); sensors no virtual (gyro do
PHONE como fonte do virtual é follow-up natural — registrar); migrar os
profiles `.xml` do winlator para o formato do fork.

## 6. Commit e checkpoint

- Commit da fase: `feat(gamepad): gamepad virtual de toque no pipeline universal
  — overlay vira device no hub (camadas/expressões/radial/turbo), injeção U4
  (spec 2026-08-16-K1)`.
- Impl doc: este arquivo.
- Tabela §5 da retomada + §7 do master atualizadas (checkpoint idempotente).
