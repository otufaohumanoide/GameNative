# Spec 2026-08-13 — Correção da implementação de gamepads (ontologia + defeitos + contratos)

**Data:** 2026-08-13
**Origem:** validação da Onda 1 do spec 2026-08-13-gamepad-universal.md. A auditoria
(leitura de código + comparação com SDL, SDL_GameControllerDB, RetroArch autoconfig e
Dolphin ControllerInterface) concluiu que a implementação **não compila** e **violou os
contratos congelados** porque a **Onda 0 (Foundation) nunca foi implementada**. Este spec
define, de forma didática, a ontologia do domínio (o que cada conceito É), o porquê de
cada defeito e o contrato final que a implementação DEVE cumprir.
**Decisão do usuário:** reescrever conforme este documento; o spec original continua
válido como arquitetura (ondas, ownership, fases).

---

# PARTE I — ONTOLOGIA (o que cada conceito é, e por quê)

A camada de gamepad existe para responder a UMA pergunta: "qual **ação lógica** este
**evento físico** representa?". Para isso ela precisa de um vocabulário preciso. Cada
termo abaixo é uma entidade com responsabilidade única.

## 1. Dispositivo

**`GamepadDevice`** — a IDENTIDADE de um controle conectado. O Android entrega três
identificadores diferentes e cada um serve a um propósito:

| Identificador | O que é | Uso |
|---|---|---|
| `deviceId` (Int) | Índice efêmero do `InputDevice` no sistema | Roteamento de eventos NAQUELA sessão. **Volátil**: pode mudar ao reconectar. |
| `descriptor` (String) | Identidade estável do hardware (rota do bus + identificador do device) | Chave de persistência de perfil. É o que `ExternalController` já usa como `id`. |
| `vendorId`+`productId` (Int) | IDs USB/BT do fabricante e do produto | Chave do **MappingDatabase** — identifica o MODELO (ex.: 054c:09cc = DS4), não a unidade. |

Por que isso importa: o defeito da implementação foi justamente misturar esses três
papéis — o `ProfileResolver` usou `deviceId` como chave de perfil de jogo (volátil!) e o
`MappingDatabase` usou pares como chave de mapa de forma que não compilava.

`vendorId+productId` viram `mappingKey` — string hex de 8 caracteres minúsculos
(`"%04x%04x"`), formato idêntico ao usado pelo RetroArch autoconfig e pela SDL no GUID.

**Classificação** (`DeviceClass`): um `InputDevice` do Android pode ser muitas coisas
(teclado, mouse, touchpad, sensor, gamepad). Antes de tratar eventos, a camada precisa
classificar. A regra vem de `ExternalController.isGameController` (com/winlator/
inputcontrols/ExternalController.java:363-391), que o repo já usa com sucesso:

```
CONTROLLER  = (SOURCE_GAMEPAD e tem teclas BUTTON_A/B/X/Y) OU (SOURCE_JOYSTICK e tem eixos X/Y)
TOUCHPAD    = fonte com SOURCE_CLASS_POINTER em device de controle (o gate de ghost input do
              MainActivity já depende dessa distinção)
SENSOR      = device virtual que só reporta sensores (nunca emitido no hot path)
UNKNOWN     = resto
```

## 2. Eventos — crus vs lógicos

Existem DOIS níveis de evento, e o spec os separa por princípio:

**Evento cru** (`RawKeyInput`/`RawAxisInput`) — o que o Android entrega:
`KeyEvent`/`MotionEvent` com `deviceId`, `source`, `keyCode`/`axisValues`.
É dependente de hardware, de `.kl` file e de fabricante.

**Evento lógico** (`InputEvent` sealed) — o vocabulário da APLICAÇÃO:
`ButtonDown/ButtonUp(deviceId, GamepadButton)`, `AxisMotion(deviceId, GamepadAxis, value)`,
`DeviceAdded/DeviceRemoved`. Só ele aparece para a UI/jogo.

Por que a separação: a regra de ouro do repo é "lógica pura testável em JVM, sem
android.*". O tradutor (que converte cru → lógico) é a peça pura; o adapter Android
(que converte `KeyEvent` → `RawKeyInput`) é um arquivo fino que só roda no device.

**Erro na implementação:** `InputEvent` virou classe ANINHADA dentro de
`EventTranslator` (mapping/EventTranslator.kt:2-8) — o que impede qualquer outro pacote
(UI, hub, perfis) de referenciá-lo sem acoplar ao tradutor — e perdeu os stubs
`SensorUpdate`/`TouchpadMotion` que o spec pediu para evitar refatoração futura.

## 3. Botões e eixos — o vocabulário semântico

`GamepadButton`/`GamepadAxis` devem ser **semânticos** (o que o botão É no controle),
não **físicos** (o que o Android chama ele). É o modelo da SDL:

```
FACE_BOTTOM / FACE_RIGHT / FACE_LEFT / FACE_TOP   ← posição no controle (A/B/X/Y no Xbox,
                                                     ✕/◯/▢/△ no PlayStation, B/A/Y/X no
                                                     Nintendo — as LABELS variam, a posição não)
DPAD_UP/DOWN/LEFT/RIGHT                           ← cross digital
LEFT_BUMPER/RIGHT_BUMPER, LEFT_TRIGGER/RIGHT_TRIGGER
LEFT_STICK/RIGHT_STICK                            ← clique dos analógicos (L3/R3)
START, SELECT, GUIDE                              ← sistema
```

O FaceStyle (XBOX/PLAYSTATION/NINTENDO/GENERIC) responde "como desenhar/rotular" —
nunca muda a posição física. Por isso o spec define `FACE_BOTTOM` etc.: um jogo quer
saber "botão de confirmar está embaixo", e a UI desenha "A" ou "✕" conforme o FaceStyle.

**Erro na implementação:** o enum foi criado como `A, B, X, Y, LB, RB...`
(GamepadButton.kt:4) — são LABELS de um fabricante (Xbox), não posições. Resultado: o
`GamepadGlyphProvider` (que referencia `FACE_BOTTOM` etc.) não compila, e o OK/Cancel por
FaceStyle da Fase 6 fica impossível de modelar.

## 4. Mapping — a ponte cru ↔ semântico

**`RawBinding`** — a descrição de ONDE um controle físico emite um botão/eixo:
`Key(keyCode)`, `Axis(axis, direction)`, `Hat(hat, mask)`.

**`GamepadMapping`** — o dicionário que traduz um MODELO de controle (vendor+product)
para o vocabulário semântico: `buttons: Map<GamepadButton, RawBinding>` +
`axes: Map<GamepadAxis, RawBinding>` + `faceStyle`.

**Formato de string (inspirado no SDL_GameControllerDB, licença zlib):**

```
GUID,nome,a:b0,b:b1,leftx:a0,lefty:a1,dpup:h0.1,dpdown:h0.4,...,platform:Windows,
```

Regras da gramática (SDL_gamepad.c:1682-1846 — `SDL_PrivateParseGamepadElement`):

| Sintaxe | Significado |
|---|---|
| campo 0 (antes da 1ª vírgula) | GUID — descartado pelo parser (a chave de lookup é vendor+product, não o GUID) |
| campo 1 | nome de display — descartado da tabela de bindings |
| `a:bN` | botão semântico `a` vem do botão físico N |
| `leftx:aN` / `leftx:+aN` / `leftx:-aN` / `leftx:~aN` | eixo N inteiro / metade positiva / metade negativa / invertido |
| `dpup:hN.1`, `dpdown:hN.4`, `dpleft:hN.8`, `dpright:hN.2` | hat N em bitmask (1=up, 2=right, 4=down, 8=left) |
| `platform:X` | ignorado |
| campo desconhecido | ignorado (tolerância — a DB real tem `hint:` etc.) |

**Erros na implementação (MappingParser.kt):** tratou GUID e nome como bindings (:14-37);
inventou literais `"buttons"`/`"axes"` que não existem na gramática (:23-25); exigiu
inteiro para o valor de eixo (`a0` nunca parseia — :88-89); quebrou o nome do eixo com
`dropLast(4)` ("leftx" → "l" — :67); mapeou hat para keycodes inexistentes 272-275
(:56-62). Ou seja: o parser não aceita NENHUMA linha real do gamecontrollerdb.txt.

## 5. Keycodes Android — a tabela física verdadeira

O tradutor precisa conhecer os keycodes REAIS do Android (verificados no SDK e no
backend Android da SDL — SDL_sysjoystick.c:38-160):

| Semântico | Keycode | Valor |
|---|---|---|
| FACE_BOTTOM (A/✕) | KEYCODE_BUTTON_A | 96 |
| FACE_RIGHT (B/◯) | KEYCODE_BUTTON_B | 97 |
| FACE_LEFT (X/▢) | KEYCODE_BUTTON_X | 99 |
| FACE_TOP (Y/△) | KEYCODE_BUTTON_Y | 100 |
| LEFT_BUMPER / RIGHT_BUMPER | KEYCODE_BUTTON_L1 / R1 | 102 / 103 |
| LEFT_TRIGGER / RIGHT_TRIGGER (quando vêm como botão) | KEYCODE_BUTTON_L2 / R2 | 104 / 105 |
| LEFT_STICK / RIGHT_STICK | KEYCODE_BUTTON_THUMBL / THUMBR | 106 / 107 |
| START / SELECT / GUIDE | KEYCODE_BUTTON_START / SELECT / MODE | 108 / 109 / 110 |
| Botões genéricos (DInput) | KEYCODE_BUTTON_1..16 | 188..203 |
| DPAD | KEYCODE_DPAD_UP/DOWN/LEFT/RIGHT | 19..22 |

**Erros na implementação:** o parser usou 304/305/307/308 para A/B/X/Y e o
`defaultAndroidMapping` usou 289-300 — **nenhum desses keycodes existe no Android**
(288 é o último definido; 304+ não são atribuídos). Consequência: nenhum evento real
casaria com o mapping, e o `translateKey` ainda fazia `ordinal == keyCode - 289`
(:16-17), que lança `NoSuchElementException` para qualquer keyCode real.

**Insight de plataforma (por que a DB é pequena):** para os pads POPULARES (DS4,
DualSense, Xbox, Switch Pro), o framework Android JÁ entrega os keycodes semânticos
(96-110) via arquivos `.kl` — não precisamos de binding. A DB só precisa de:
(a) **FaceStyle** do modelo; (b) genéricos **DInput** (SOURCE_JOYSTICK sem GAMEPAD, que
chegam como BUTTON_1..16 + AXIS_X/Y/Z/RZ + HAT_X/Y); (c) quirks de **trigger**
(BRAKE/GAS vs LTRIGGER/RTRIGGER — caso que `ExternalController.processTriggerButton` já
cobre). Por isso o spec proíbe importar o gamecontrollerdb.txt inteiro (2.256 entradas
desktop, ~95% irrelevante em Android).

## 6. Deadzone e histerese

**Deadzone** — a região em torno do centro do analógico que o sistema IGNORA (drift,
poeira, desgaste). Dois modos (padrão DuckStation/SDL):

- **RADIAL**: ignora se `√(x²+y²) < deadzone` — o natural para sticks.
- **AXIAL**: ignora eixo a eixo se `|v| < deadzone` — o natural para triggers e para
  sticks com drift assimétrico.

**Rescalonamento** — depois de subtrair a deadzone, o valor é renormalizado para 0..1
(`(|v|−dz)/(1−dz)`) para o jogo não perder a faixa útil do stick. **Erro:** o
`DeadzoneProcessor` implementado calcula o rescalonamento na variável `out` e **nunca
usa** (DeadzoneProcessor.kt:36-40) — devolve o valor cru, e os testes inclusive
"congelaram" esse comportamento errado (esperam 0.5 cru).

**Histerese** — a assimetria entrada/saída que mata o "jitter" em torno do limiar: o
valor entra quando cruza `deadzone` e só SAI quando cai abaixo de `deadzone − hysteresis`.
Sem ela, um stick parado exatamente no limiar liga/desliga a ação dezenas de vezes por
segundo. Exige ESTADO entre amostras (lembrar se está dentro/fora) — o padrão do
DuckStation/Dolphin. **Erro:** a implementação trata histerese como constante numérica
sem estado, referencia a variável `hysteresis` como variável livre (não compila —
:47) e divide por `(1 − dz − hysteresis)` sem proteção (divisão por zero quando
dz+hyst ≥ 1).

## 7. Perfil e camadas

**`GamepadProfile`** — as preferências do usuário sobre um controle. Escopos:

- **per-device** (chave = `mappingKey`, o modelo): "meu DS4 tem drift no stick esquerdo,
  deadzone 0.25".
- **per-jogo/container** (chave = `appId`): "neste jogo, troque A↔B".
- Merge **device → game, game vence** (padrão Steam Input/Dolphin). Erro: o
  `ProfileResolver` usou `activeDeviceId` (volátil) como chave de jogo em vez de appId.

**`ActionLayer`** — conceito Steam Input adotado só no MODELO de dados: `layers:
Map<String, Map<String, String>>` onde a chave é o nome da camada (`DEFAULT`, `MENU`) e
o valor é `GamepadButton.name → binding serializado`. A camada `MENU` é resolvida de
graça pelo `OverlayInputState` existente (menu aberto vs jogo). Camadas completas
(chords/toggles) são follow-up; o modelo nasce pronto para elas.

**Persistência** — o padrão do repo é o `PerGameShaderStore`
(shaders/PerGameShaderStore.kt): JSON único keyed por id, **write atômico
(tmp + rename)**, conteúdo malformado degrada a vazio. **Erro:** a implementação
chamou APIs de serialização inexistentes (`kotlinx.serialization.JSON.decodeFromString`
com `Class` — a API real é `Json.decodeFromString<T>(text)`), usou sintaxe inválida
(`it.isDefault() ? null : it`), misturou formato de arquivo (load lê perfil único,
save escreve mapa) e chamou `toJson()` que não existe.

## 8. Hub e hot path

**`GamepadHub`** — o ÚNICO dono da descoberta de devices (consolida os dois
`InputDeviceListener` duplicados: MainActivity.kt:144 e XServerScreen.kt:1464). Regras:

- **Hot path síncrono**: `onKey`/`onAxis` rodam na thread de dispatch do Android, sem
  coroutine, sem alocação em rajada (overhead alvo < 1 ms).
- **UI reativa fora do hot path**: `StateFlow<Map<Int, GamepadDevice>>` e
  `StateFlow<GamepadDevice?>` — a UI observa conexão/perfil SEM interceptar eventos.
- **Gate**: `gamepadUniversalEnabled` (PrefManager, default false) — eventos lógicos só
  são emitidos quando a Onda 2 ligar o consumo. Até lá, tradução roda em paralelo ao
  fluxo cru sem mudar NENHUM comportamento (o caminho do jogo — `PhysicalControllerHandler`
  — continua cru até a Onda 2, atrás do teste de regressão V10).

**Por que o hub NÃO existir é o defeito-mãe:** sem ele, cada agente da Onda 1 inventou
seu próprio `InputEvent`, seu próprio `DeadzoneConfig` (há DOIS no código — mapping/
EventTranslator.kt:62 e processing/DeadzoneProcessor.kt:10) e suas próprias chaves de
identificação. A Onda 0 existe justamente para congelar esse vocabulário ANTES do
paralelismo.

---

# PARTE II — DEFEITOS (auditoria completa, file:line)

## D0 — Onda 0 ausente (BLOQUEANTE, causa dos demais)

| Arquivo do spec §3 | Status |
|---|---|
| `gamepad/GamepadDevice.kt` | **não existe** — `ProfileResolver.kt:3` e `GamepadRemapDialog.kt:4` importam → não compila |
| `gamepad/InputEvent.kt` (sealed interface top-level) | **não existe** — virou classe aninhada em `mapping/EventTranslator.kt:2-8`, sem stubs Sensor/Touchpad, com `DeviceAdded(deviceId)` em vez de `DeviceAdded(device: GamepadDevice)` |
| `gamepad/DeviceClassifier.kt` | **não existe** — `DeviceClass.kt:4` tem valores errados (`GAMEPAD/KEYBOARD/MOUSE/UNKNOWN`; spec: `CONTROLLER/TOUCHPAD/SENSOR/UNKNOWN`) |
| `gamepad/GamepadBusEvents.kt` | **não existe** |
| `gamepad/GamepadHub.kt` | **não existe** |
| `PrefManager` keys do gate/deadzone/swap | **não existem** |

## D1 — Enum semântico virou label de fabricante

`gamepad/GamepadButton.kt:4` — `A, B, X, Y, LB, RB, LT, RT, SELECT, START, GUIDE,
DPadUp...`. Faltam `LEFT_STICK/RIGHT_STICK` e os nomes não são posições. Consequência
direta: `GamepadGlyphProvider.kt:33-46` referencia `FACE_BOTTOM`, `LEFT_BUMPER`,
`LEFT_STICK`, `RIGHT_STICK` — símbolos inexistentes → **não compila**. O FaceStyle
(Fase 6, OK/Cancel) fica sem modelo.

`gamepad/GamepadAxis.kt:4` — `LeftX/LeftY/...`; spec: `LEFT_X/LEFT_Y/RIGHT_X/RIGHT_Y/
LEFT_TRIGGER/RIGHT_TRIGGER`.

## D2 — Parser não entende a gramática SDL

| Erro | Evidência | Correção |
|---|---|---|
| GUID/name tratados como bindings | `MappingParser.kt:14-37` | pular campos 0 e 1; bindings a partir do 3º |
| Literais inventados `"buttons"`/`"axes"` | `MappingParser.kt:23-25` | remover (gramática SDL não tem isso) |
| Valor de eixo exige inteiro | `MappingParser.kt:88-89` — `a0` → null sempre | parsear `aN`/`+aN`/`-aN`/`~aN` |
| `dropLast(4)` quebra "leftx" | `MappingParser.kt:67` — vira `"l"` | comparar o nome completo |
| Hat ignora bitmask | `MappingParser.kt:48-64` — qualquer dp* → keycodes 272-275 | `hN.mask` com 1/2/4/8; no Android, hat chega como AXIS_HAT_X/Y → o TRADUTOR converte |
| Keycodes inventados | `MappingParser.kt:116-131` (304-315) | usar tabela real (96-110, 188-203, 19-22) |
| `platform:` não ignorado | — | ignorar + tolerar campos desconhecidos (`hint:` etc.) |

## D3 — Database não compila / chaves erradas

- `MappingDatabase.kt:3-13` — `0x054c to 0x09cc to buildMapping(...)`: precedência do
  `to` gera `Pair(Pair(vendor, product), GamepadMapping)` como chave; o lookup
  `mappings[vendorId to productId]` nunca casa. **Não compila** (map heterogêneo).
- `MappingDatabase.kt:31-36` — triggers como `RawBinding.Axis(4, 0)`: direção 0 é
  inválida (deve ser ±1) e os índices 4/5 não correspondem a triggers no Android
  (AXIS_LTRIGGER=17, RTRIGGER=18, BRAKE=23, GAS=22).
- `MappingDatabase.kt:39` — `mappingKey` dos built-ins é a string de bindings, não
  `vendor+product` hex (spec §3.1).
- `MappingDatabase.kt:46-68` — `defaultAndroidMapping` usa keycodes 289-300
  (inexistentes); a identidade correta é 96-110 + DPAD 19-22.

## D4 — Tradutor quebrado

- `EventTranslator.kt:13-19` — acha a entrada do mapping e DESCARTA; depois faz
  `GamepadButton.values().first { it.ordinal == rawKeyCode - 289 }` → exceção para
  qualquer keycode real.
- `EventTranslator.kt:20-23` — ação invertida: no Android `ACTION_DOWN=0`,
  `ACTION_UP=1`; o código trata 1 como Down.
- `EventTranslator.kt:27-58` — ignora `mapping.axes` (hardcoda eixos 0..5); não trata
  HAT_X/HAT_Y (15/16), BRAKE/GAS (23/22), LTRIGGER/RTRIGGER (17/18); não usa o
  `DeadzoneProcessor` do pacote processing; usa o `DeadzoneConfig` duplicado local.
- `DeadzoneConfig` duplicado: `mapping/EventTranslator.kt:62-69` vs
  `processing/DeadzoneProcessor.kt:10-17` — o spec define UM, em processing/.

## D5 — DeadzoneProcessor não compila / lógica incompleta

- `DeadzoneProcessor.kt:30-49` — `sqrt/abs/max/min` sem `import kotlin.math.*`.
- `process` (`:26-41`) — calcula `out` (rescalonamento) e NUNCA usa; retorna x/y crus.
- `processAxis` (`:44-49`) — usa `hysteresis` como variável livre (não definida) →
  **não compila**; divisão por zero quando `deadzone + hysteresis ≥ 1`; sem estado de
  histerese (spec: saída em `deadzone − hysteresis`, exige lembrar dentro/fora entre
  amostras — padrão DuckStation/Dolphin).
- Testes congelaram o comportamento errado: `DeadzoneProcessorTest.kt:44,79` esperam
  saída RAW (0.5/0.5) — o spec exige rescalonamento 0..1.

## D6 — AnalogToDpad

- `AnalogToDpad.kt:19,31` — `abs/maxOf` sem import → não compila.
- Assinatura diverge do contrato §3.2: implementada `(sampleX, sampleY, hatX, hatY,
  deadZone)`; o contrato (e o teste) usam `(sample: DeadzoneResult, hatX, hatY,
  deadZone)`.
- `AnalogToDpadTest.kt:44` — `` `fallback to stick magnitude when hat inactive`() ``
  sem `fun` → sintaxe inválida.

## D7 — Store de perfis

- `GamepadProfileStore.kt:13` — `kotlinx.serialization.JSON.decodeFromString(
  GamepadProfile::class.java, text)`: API inexistente. O plugin de serialização EXISTE
  no projeto (build.gradle.kts:9); a API correta é
  `kotlinx.serialization.json.Json.decodeFromString<Map<String, GamepadProfile>>(text)`
  — padrão real em `shaders/PerGameShaderStore.kt:67-79`.
- `GamepadProfileStore.kt:13` — `.let { it.isDefault() ? null : it }`: sintaxe inválida
  (elvis sobre Boolean).
- `GamepadProfileStore.kt:19-26,35-46` — formato inconsistente (load lê perfil único,
  save escreve mapa); `write` chama `value.toJson()` que não existe (o spec exige
  `toJson/fromJson` no companion do profile); sem write atômico tmp+rename.
- `ProfileResolver.kt:15,24` — `activeDeviceId` como chave de escopo de jogo (deveria
  ser `appId` do container); `merged` privado (spec: companion do store); depende de
  `GamepadDevice` inexistente.

## D8 — UI

- `GamepadGlyphProvider.kt` — tipo de retorno `Composable` (é annotation, não tipo);
  imports inexistentes (`androidx.compose.foundation.clickable.clickable`); usa
  `accompanist` icons, que NÃO é dependência do projeto (violaria "sem libs externas de
  gamepad" e não compila); labels hardcoded (spec §5: resources EN/pt-rBR); enum
  referenciado não existe (D1).
- `GamepadRemapDialog.kt` — imports de símbolos inexistentes (`GamepadDevice`,
  `GamepadMapping.RawBinding` — RawBinding é top-level, `GamepadProfileStore.
  GamepadProfile`, `app.gamenative.gamepad.inputEvent.InputEvent`,
  `androidx.compose.runtime.Var/Getter`); `remember`, `KeyEvent`, `TextButton`,
  `fillMaxSize` etc. não importados; `isSelected` indefinido; strings hardcoded.
  O padrão correto de diálogo está em `ui/component/dialog/ElementEditorDialog.kt:346`
  (`GamepadFocusScope` — janela de diálogo usa focus scope de VIEW, nunca navigator de
  bus; regra do AGENTS.md).

## D9 — Testes que não compilam

- `MappingParserTest.kt:23-24` — `MappingParser.parse(line)!!` seguido de
  `assertNull(mapping)` (contradição).
- `AnalogToDpadTest.kt` — chama a assinatura do CONTRATO enquanto a implementação tem
  outra; linha 44 sem `fun`.
- `EventTranslatorTest.kt` — usa keycodes inventados (304) e `DeadzoneConfig(lt=, rt=)`
  do tipo duplicado de mapping/.
- Nenhum teste cobre linha REAL do gamecontrollerdb (o critério de aceite do spec).

---

# PARTE III — CONTRATO FINAL (assinaturas exatas, re-congeladas)

## Modelo (`app/gamenative/gamepad/`)

```kotlin
enum class GamepadButton {
    FACE_BOTTOM, FACE_RIGHT, FACE_LEFT, FACE_TOP,
    DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT,
    LEFT_BUMPER, RIGHT_BUMPER, LEFT_TRIGGER, RIGHT_TRIGGER,
    LEFT_STICK, RIGHT_STICK, START, SELECT, GUIDE,
}

enum class GamepadAxis { LEFT_X, LEFT_Y, RIGHT_X, RIGHT_Y, LEFT_TRIGGER, RIGHT_TRIGGER }

enum class DeviceClass { CONTROLLER, TOUCHPAD, SENSOR, UNKNOWN }

enum class FaceStyle { XBOX, PLAYSTATION, NINTENDO, GENERIC }

data class GamepadDevice(
    val deviceId: Int,
    val descriptor: String,
    val vendorId: Int,
    val productId: Int,
    val name: String,            // display apenas — nunca como chave
    val deviceClass: DeviceClass,
    val faceStyle: FaceStyle,
) { val mappingKey: String get() = "%04x%04x".format(vendorId, productId) }

sealed interface InputEvent {
    data class ButtonDown(val deviceId: Int, val button: GamepadButton) : InputEvent
    data class ButtonUp(val deviceId: Int, val button: GamepadButton) : InputEvent
    data class AxisMotion(val deviceId: Int, val axis: GamepadAxis, val value: Float) : InputEvent
    data class DeviceAdded(val device: GamepadDevice) : InputEvent
    data class DeviceRemoved(val deviceId: Int) : InputEvent
    // stubs — nunca emitidos nesta missão (follow-ups gyro/touchpad):
    data class SensorUpdate(val deviceId: Int, val gyroX: Float, val gyroY: Float,
        val gyroZ: Float, val accelX: Float, val accelY: Float, val accelZ: Float) : InputEvent
    data class TouchpadMotion(val deviceId: Int, val x: Float, val y: Float) : InputEvent
}

// Bus (não editar AndroidEvent.kt):
class GamepadInputEvent(val input: InputEvent) : Event<Boolean>
class GamepadDeviceAddedEvent(val device: GamepadDevice) : Event<Unit>
class GamepadDeviceRemovedEvent(val deviceId: Int) : Event<Unit>
```

## Mapping (`gamepad/mapping/`)

```kotlin
sealed interface RawBinding {
    data class Key(val keyCode: Int) : RawBinding                 // keycode Android real
    data class Axis(val axis: Int, val direction: Int) : RawBinding  // direction ∈ {-1, +1} (half-axis); proibido 0
    data class Hat(val hat: Int, val mask: Int) : RawBinding      // mask bitfield SDL: 1=up,2=right,4=down,8=left
}

data class GamepadMapping(
    val mappingKey: String,    // vendor+product hex 8 (ex.: "054c09cc")
    val name: String,
    val faceStyle: FaceStyle,
    val buttons: Map<GamepadButton, RawBinding>,
    val axes: Map<GamepadAxis, RawBinding>,
)

object MappingParser { fun parse(line: String): GamepadMapping? }

object MappingDatabase {
    fun mappingFor(vendorId: Int, productId: Int): GamepadMapping?
    fun defaultAndroidMapping(faceStyle: FaceStyle): GamepadMapping
}

data class RawKeyInput(val deviceId: Int, val source: Int, val keyCode: Int,
    val action: Int, val repeatCount: Int)                       // action: ACTION_DOWN=0, ACTION_UP=1

data class RawAxisInput(val deviceId: Int, val source: Int, val action: Int,
    val axisValues: Map<Int, Float>)                             // chaves = constantes AXIS_* reais

object EventTranslator {
    fun translateKey(raw: RawKeyInput, mapping: GamepadMapping): List<InputEvent>
    fun translateAxis(raw: RawAxisInput, mapping: GamepadMapping,
        deadzones: DeadzoneConfig): List<InputEvent>
}
```

**Tabela de conversão obrigatória (constantes Android reais):**

```
BUTTON_A=96 → FACE_BOTTOM;  BUTTON_B=97 → FACE_RIGHT;  BUTTON_X=99 → FACE_LEFT;
BUTTON_Y=100 → FACE_TOP;    L1=102, R1=103;  L2=104, R2=105 (quando botão);
THUMBL=106, THUMBR=107 → LEFT_STICK, RIGHT_STICK;
START=108, SELECT=109, MODE=110 → START, SELECT, GUIDE;
BUTTON_1..16 = 188..203 → espaço de botões genéricos (indexado pelo mapping b0..b15);
DPAD_UP=19, DPAD_DOWN=20, DPAD_LEFT=21, DPAD_RIGHT=22.
Eixos: AXIS_X=0, AXIS_Y=1, AXIS_Z=2, AXIS_RZ=3, AXIS_LTRIGGER=17, AXIS_RTRIGGER=18,
AXIS_HAT_X=15, AXIS_HAT_Y=16, AXIS_BRAKE=23, AXIS_GAS=22.
```

## Processing (`gamepad/processing/`)

```kotlin
enum class DeadzoneMode { RADIAL, AXIAL }

data class DeadzoneConfig(
    val leftStick: Float = 0.15f,
    val rightStick: Float = 0.15f,
    val leftTrigger: Float = 0.08f,
    val rightTrigger: Float = 0.08f,
    val mode: DeadzoneMode = DeadzoneMode.RADIAL,
    val hysteresis: Float = 0.05f,     // saída em deadzone − hysteresis
)

data class StickSample(val x: Float, val y: Float)
data class DeadzoneResult(val x: Float, val y: Float, val inDeadzone: Boolean)

object DeadzoneProcessor {
    fun process(sample: StickSample, config: DeadzoneConfig): DeadzoneResult
        // rescalona: (|v| − dz) / (1 − dz), clip 0..1, preservando sinal e direção
    fun processAxis(value: Float, deadzone: Float): Float   // triggers: 0..1, rescalonado
}

object AnalogToDpad {
    fun sampleToDirection(sample: DeadzoneResult, hatX: Float, hatY: Float,
        deadZone: Float): GamepadStickDirection?
    // hat (|v|>0.5) vence; senão stick pós-deadzone; null = neutro.
    // A decisão com estado/cooldown continua em GamepadStickLogic.decide (RC1 preservado).
}
```

## Profiles (`gamepad/profiles/`)

```kotlin
enum class ActionLayer { DEFAULT, MENU }

@Serializable
data class GamepadProfile(
    val faceStyle: FaceStyle? = null,
    val swapOkCancel: Boolean? = null,
    val leftStickDeadzone: Float? = null,
    val rightStickDeadzone: Float? = null,
    val leftTriggerDeadzone: Float? = null,
    val rightTriggerDeadzone: Float? = null,
    val layers: Map<String, Map<String, String>> = emptyMap(),
) {
    fun isDefault(): Boolean
    fun toJson(): String
    companion object { fun fromJson(json: String): GamepadProfile? }
}

class GamepadProfileStore(private val file: File) {
    fun load(key: String): GamepadProfile?
    fun save(key: String, profile: GamepadProfile)   // default REMOVE a entrada; write atômico tmp+rename
    fun clear(key: String)
    companion object {
        fun merged(device: GamepadProfile?, game: GamepadProfile?): GamepadProfile  // game vence campo a campo
    }
}

object ProfileResolver {
    fun resolve(device: GamepadDevice, appId: String?, deviceStore: GamepadProfileStore,
        gameStore: GamepadProfileStore): GamepadProfile
}
```

## Hub (`gamepad/GamepadHub.kt`)

```kotlin
class GamepadHub(context: Context) {
    val connectedDevices: StateFlow<Map<Int, GamepadDevice>>
    val activeDevice: StateFlow<GamepadDevice?>
    fun start()   // registra o ÚNICO InputDeviceListener (remover os de MainActivity:144 e XServerScreen:1464 na Onda 2) + scan inicial
    fun stop()
    fun deviceFor(deviceId: Int): GamepadDevice?
    fun profileFor(deviceId: Int, appId: String?): GamepadProfile   // resolvido no momento do evento (holder vivo)
    fun onKey(raw: RawKeyInput): Boolean    // traduz e emite GamepadInputEvent (gate gamepadUniversalEnabled)
    fun onAxis(raw: RawAxisInput): Boolean
}
```

---

# PARTE IV — PLANO DE CORREÇÃO (ordem)

**Passo 1 — Onda 0 (Foundation), agora de verdade:** implementar os 5 arquivos do
modelo + `DeviceClassifier` + `GamepadBusEvents` + `GamepadHub` + keys do PrefManager
(`gamepadUniversalEnabled`=false, `gamepadStickDeadzone`=0.15f,
`gamepadMenuStickDeadzone`=0.45f, `gamepadSwapOkCancel`=false). Compilar.

**Passo 2 — Alinhar o que já existe aos contratos:** corrigir `GamepadButton.kt`,
`GamepadAxis.kt`, `DeviceClass.kt` (D1); remover o `DeadzoneConfig` duplicado de
`mapping/EventTranslator.kt` (usar o de processing/).

**Passo 3 — Reescrever `MappingParser` (D2) + `MappingDatabase` (D3)**: gramática real
com testes usando linhas reais do gamecontrollerdb.txt (ex.: a linha do PS4 Controller
citada na Parte I §4). Built-ins com os pares vendor/product do spec, usando
`defaultAndroidMapping` para o resto.

**Passo 4 — Reescrever `EventTranslator` (D4)**: inverter `mapping.buttons` por keyCode;
ACTION_DOWN=0/ACTION_UP=1; repeat → down só em repeatCount==0; hats via AXIS_HAT_X/Y;
triggers via BRAKE/GAS/LTRIGGER/RTRIGGER; deadzone via `DeadzoneProcessor`.

**Passo 5 — Corrigir `DeadzoneProcessor` (D5) e `AnalogToDpad` (D6)**: imports
kotlin.math; rescalonamento real; histerese com estado (assinatura com estado anterior
ou threshold documentado); assinatura do contrato; corrigir os 2 arquivos de teste.

**Passo 6 — Corrigir `GamepadProfileStore`/`ProfileResolver` (D7)**: serialização
correta (padrão `PerGameShaderStore`), write atômico, `toJson/fromJson`, `merged`
público, chave de jogo = appId.

**Passo 7 — Reescrever `GamepadGlyphProvider` e `GamepadRemapDialog` (D8)**: sem
accompanist; strings resources EN+pt-rBR (criar as chaves de uma vez — dono único do
strings.xml); padrão `GamepadFocusScope` do ElementEditorDialog; captura via eventos do
bus.

**Passo 8 — Testes verdes (D9)**: cada teste compila e valida o contrato; casos do spec
(DS4 X físico → FACE_BOTTOM; DInput axes 0/1/3/4 → sticks; trigger axis vs botão; linha
real do gamecontrollerdb). Comando (nunca a suíte completa):
`JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:testModernDebugUnitTest --tests "*gamepad*"`.

**Passo 9 — Onda 2 (integração)** fica para um spec de implementação posterior, conforme
o spec original (E1 menu → E2 jogo → E3 verificação on-device V1–V10/G1–G8).

## Critérios de aceite deste spec

- `assembleModernDebug` compila SEM warnings novos.
- Todos os testes `*gamepad*` compilam e passam.
- Uma linha REAL do `gamecontrollerdb.txt` parseia corretamente (campo GUID e name
  descartados, `h0.4` vira hat mask 4, `a0` vira Axis(0, ±1), `platform:` ignorado).
- `translateKey` com `keyCode=96` (BUTTON_A) e mapping DS4 retorna
  `ButtonDown(..., FACE_BOTTOM)`; com action 1 retorna ButtonUp.
- Nenhum arquivo fora do pacote `gamepad/` e dos testes é tocado (Onda 2 continua
  dona exclusiva dos arquivos quentes).
- Nenhum keycode inventado (auditoria: só as constantes da tabela da Parte I §5).

## Fora de escopo (follow-ups, como no spec original)

Gyro → mouse (pedido nº 1 da comunidade), touchpad DS4/DualSense → mouse (nº 2),
Action Layers completas (chords/toggles), rumble avançado. Os stubs `SensorUpdate`/
`TouchpadMotion` e o campo `layers` do perfil já preparam o modelo para esses.
