# Spec 2026-08-13 — Portão anti-fantasma do touchpad de controle (correção obrigatória)

**Data:** 2026-08-13
**Origem:** perícia de logcat solicitada pelo usuário ("meu joystick parece estar enviando
inputs não desejados") — confirmou um vazamento real no pipeline de input, agravado por
hardware desgastado (DS4 com touchpad fantasma).
**Padrões obrigatórios (repo):** strings EN + pt-BR; zero regressão no input do guest;
consumo de eventos por fonte comprovada por logcat, não por adivinhação.

---

## Evidência (logcat 2026-08-13, aparelho do usuário)

- 63 eventos de **tecla** em ~9 minutos, TODOS do dispositivo "Wireless Controller
  Touchpad" (source mista `0x5002513` = JOYSTICK|GAMEPAD|TOUCH_NAVIGATION|CLASS_POINTER):
  DPAD_LEFT 20×, DPAD_RIGHT 18× (1 com repeat=1), DPAD_DOWN 9×, DPAD_UP 4×,
  BUTTON_B 6×, BACK 6× — em rajadas DOWN/UP com ms de intervalo, com o stick real
  ("Wireless Controller") **sem nenhum evento** no mesmo período.
- Motion contínuo do mesmo dispositivo (posição absoluta do dedo em AXIS_X/AXIS_Y lida
  como stick com magnitude 1.0) — o mesmo padrão do diagnóstico do QuickMenu morto.
- `ExternalController.isGameController` classifica o touchpad como controle (source
  mista passa; tem eixos e teclas) → as keys fantasma eram roteadas para o jogo
  (`winHandler.onKeyEvent`, XServerScreen.kt:1622), o motion fantasma para
  `winHandler.onGenericMotionEvent` (XServerScreen.kt:1697+), e o BACK fantasma (code 4)
  percorria o caminho de back do Android → menu abrindo/fechando "sozinho".
- O filtro `SOURCE_CLASS_POINTER` já existia só no navegador de foco do overlay
  (BusJoystickFocusNavigator) — o caminho de **keys** e o roteamento de **motion para o
  jogo** nunca tiveram filtro.

## Regra (uma, aplicada na porta)

Evento de um dispositivo classificado como controle cuja source contém
`SOURCE_CLASS_POINTER` é um fluxo de superfície de toque (touchpad) — **nunca** é botão
físico nem stick. Consumir na entrada, antes do bus. Botões físicos de gamepad nunca
carregam CLASS_POINTER na source, então a regra é sempre-correta.

## Design

1. **MainActivity.dispatchKeyEvent / dispatchGenericMotionEvent** (a porta — cobre o
   jogo, o overlay, o back do Android e a Library num único ponto): após o bloco do
   `GamepadTrace` (mantido como radar diagnóstico) e antes do `emit`:
   ```kotlin
   if (PrefManager.ignoreControllerTouchpad &&
       ExternalController.isGameController(event.device) &&
       (event.source and InputDevice.SOURCE_CLASS_POINTER) != 0
   ) return true
   ```
2. **PrefManager.ignoreControllerTouchpad** (default **ON**) + toggle em
   `SettingsGroupInterface` ("Ignorar touchpad do controle" / "Ignore controller
   touchpad"). O usuário decide: desligando, o comportamento antigo volta (touchpad
   exposto); uma futura feature touchpad→mouse pluga exatamente no ponto do gate.
3. **Defesa em diálogos** (janelas separadas não passam por MainActivity):
   `JoystickFocusNavigator` (view-level) e `GamepadKeyBridge` (view-level) repetem a
   regra (não navegam / não traduzem A→DPAD_CENTER para source pointer com a pref ON).

## Aceite

1. `GamepadTrace` continua logando os fantasmas (radar), mas **nenhum**
   `GamepadRoute: key/motion` do dispositivo Touchpad aparece — o jogo não recebe nada.
2. Menu abre/fecha só com PS/START reais; sem abertura fantasma por BACK.
3. DS4 real (stick, A/B/L1/R1, hat) funciona normalmente no jogo e nos overlays.
4. Diálogos navegáveis com stick real e imunes a fantasmas.
5. Pref OFF: comportamento antigo restaurado; pref persiste entre sessões.
