# Spec 2026-08-16 K3 — Detecção universal por capacidades (port SDL3) + botões extras do DB

**Data:** 2026-08-16
**Origem:** avaliação dos `reference/` para "experiência mobile universal estilo Steam
Input". Fonte principal: SDL3 `reference/SDL/src/joystick/SDL_gamepad.c` —
`SDL_CreateMappingForAndroidGamepad` (**linhas 705–831**, síntese de mapping pelas
capability masks do GUID), `SDL_ConvertMappingToPositionalBAXY` (**linha 2535**,
botões posicionais vs. rotulados). Secundária: `reference/SDL/android-project/.../
SDLControllerManager.java` (**getAxisMask :449, getButtonMask :485** — como as masks
são construídas no Java). Clean-room obrigatório: SDL é zlib, mas reimplementamos
semânticas em Kotlin citando a origem no KDoc (padrão `MappingParser.kt`).
**Executor:** sub-agente autônomo. Leia `AGENTS.md` + `docs/spec-2026-08-16-master-
roadmap-universal-input.md` (§1 loop, §2 regras) ANTES. **Posição na fila:** fase K3
(primeira fase de código — depois da Fase 0 de correções residuais).
**Turn budget sugerido:** 25–30 turns.

## 0. Estado atual (anchors do fork — REUSAR, não reescrever)

- Cadeia de resolução de mapping em `gamepad/GamepadHub.kt:1325-1331`:
  `MappingDatabase.mappingFor(vid, pid)` (curada, ~12 modelos, só FaceStyle para
  pads normalizados) → `sdlDb()[device.mappingKey]` (asset
  `app/src/main/assets/gamecontrollerdb.txt`, ~65 entries Android bus-style,
  parser `gamepad/mapping/SdlControllerDb.kt`) → `defaultAndroidMapping(faceStyle)`
  (estático, assume TODOS os 17 botões e 6 eixos existem).
- `gamepad/GamepadDevice.kt:15-31`: identidade + capacidades já coletadas no hotplug
  fora do hot path (padrão V11: `hasGyro`, `hasTouchpad`, `batteryPercent`).
- `gamepad/GamepadButton.kt:8-26`: enum com exatamente 17 botões — **não existem**
  MISC1/PADDLE_1-4/TOUCHPAD.
- `gamepad/mapping/MappingParser.kt:27`: `platform:` e **qualquer campo desconhecido
  (`hint:`, `type:`, `misc1:`, ...) são descartados** — o `hint:` do DB carrega
  informação de layout rotulado que hoje se perde.
- `FaceStyle` (PLAYSTATION/XBOX/NINTENDO/GENERIC) inferido por vendor
  (`SdlControllerDb.faceStyleForVendor`) e overridável por perfil
  (`GamepadProfile.faceStyle`).

O que FALTA (este spec): default **sintetizado das capacidades reais** do device
(um generic DInput sem R3 não deve ter binding fantasma de R3; um remote sem botões
de face precisa do fallback BACK→A), hint do DB honrado, botões extras parseados e
tiers de prioridade formais.

## 1. Design

### 1.1 `GamepadCapabilities` — coleta no hotplug (estende o padrão V11)

Novo data class puro em `gamepad/mapping/GamepadCapabilities.kt`:

```kotlin
data class GamepadCapabilities(
    val keycodes: Set<Int>,        // InputDevice.hasKeys(...) dos candidatos (AndroidConstants)
    val axes: List<Int>,           // motionRanges com SOURCE_JOYSTICK, ordenados por axis id
    val hasHat: Boolean,           // AXIS_HAT_X/HAT_Y presentes
    val isGamepadSource: Boolean,  // SOURCE_GAMEPAD
)
```

Coleta em `GamepadHub.addDevice` (mesmo ponto do `hasGyro`/`hasTouchpad`,
hub:1377+): `InputDevice.hasKeys(*AndroidConstants.ALL_CANDIDATE_KEYCODES)` é uma
chamada binder única — fora do hot path. Guardar em `GamepadDevice` como campo
`capabilities: GamepadCapabilities? = null` (null = não coletado → degradação para
o default estático atual).

### 1.2 `CapabilityMapping` — síntese PURA (o coração do spec)

Novo `gamepad/mapping/CapabilityMapping.kt` (JVM-testável, zero `android.*`):

```kotlin
object CapabilityMapping {
    fun synthesize(caps: GamepadCapabilities, faceStyle: FaceStyle): GamepadMapping?
    fun classify(caps: GamepadCapabilities): DeviceShape  // GAMEPAD, DINPUT_GENERIC, REMOTE, KEYBOARD
}
```

Semântica portada de `SDL_CreateMappingForAndroidGamepad` (SDL_gamepad.c:705-831):

1. **Emitir binding APENAS para o que existe** — botão no mapping só se o keycode
   está em `caps.keycodes`; eixo só se em `caps.axes`. Isso substitui o
   `defaultAndroidMapping` quando o device NÃO bateu em MappingDatabase nem no DB.
2. **Recusa por shape**: sem NENHUM botão de face e sem dpad → `REMOTE`:
   `BACK → FACE_BOTTOM` (o remote navega menus; SDL faz BACK→"b" em
   SDL_gamepad.c:778-781) e o resto vazio. Sem botão E sem eixo → retorna `null`
   (fica o default estático atual + log — device sem input não é pad).
3. **Guide gated**: `GUIDE` só entra se `KEYCODE_BUTTON_MODE` existir (o Android
   moderno entrega; SDL gateia por API — aqui a capability É o gate).
4. **Triggers**: se `AXIS_LTRIGGER/RTRIGGER` existem → binding de eixo; senão se
   keycode L2/R2 existem → binding de botão (nunca ambos, prefira eixo —
   SDL prioriza axis para trigger).
5. **Dpad**: keycodes DPAD_* se existirem; senão `hasHat` → bindings de hat
   (reusar o caminho do `genericDInput(dpadViaHat = true)` já existente na
   `MappingDatabase`).
6. **Right stick**: `AXIS_Z/RZ` se presentes; ausentes → sem RIGHT_X/RIGHT_Y
   (pad de 1 stick tipo NES/N64 não ganha stick fantasma).

### 1.3 Hint do DB: botões posicionais vs. rotulados (b)

`SdlControllerDb`: hoje `hint:` é descartado. Passa a ser lido — entry com
`hint:SDL_GAMECONTROLLER_USE_BUTTON_LABELS:=1` significa "layout físico rotulado ao
estilo Nintendo (BAYX posicional)". Semântica SDL: os bindings bN são POSITIONAIS
(sul/leste/oeste/norte) e o hint diz que o RÓTULO difere. Como o fork resolve por
`FaceStyle` (camada de rótulo), a tradução é: entry com o hint → `faceStyle`
do mapping vira `FaceStyle.NINTENDO` **se** o vendor não for Sony/MS (ou seja,
`faceStyleForVendor` vira `faceStyleForVendor(vendor, hint)` — o hint VENCE para
vendors não-identificáveis, perde para Sony/MS que são inequívocos). É o análogo do
`SDL_ConvertMappingToPositionalBAXY` (SDL_gamepad.c:2535) sem mudar o formato
interno: a conversão acontece na camada de rótulo que o fork já tem.

### 1.4 Botões extras do DB: MISC1/PADDLE/TOUCHPAD (c)

- `GamepadButton` ganha: `MISC1`, `PADDLE_1`, `PADDLE_2`, `PADDLE_3`, `PADDLE_4`,
  `TOUCHPAD` (enum APPEND — nomes estáveis serializam no perfil por `.name`).
- `MappingParser`/`SdlControllerDb.buttonByName`: casos novos
  (`misc1`, `paddle1..4`, `touchpad` — SDL: touchpad click = `b31`/`misc1`
  dependendo da entry; mapear `KEYCODE_BUTTON_1`/`BUTTON_15/16` já cobertos pela
  tabela `sdlButtonKeyCode` existente).
- Esses botões NÃO entram no `ControllerVisualLayout` (fase B) nem no
  `defaultAndroidMapping` — são fonte para **camadas/radial/expressões** (um paddle
  como gatilho de camada é o caso de uso). Fora de escopo: desenho no mock.
- Atualizar `isDefault()`/`merged()` do perfil se necessário (bindings por botão
  são mapa por nome — sem campo novo no `GamepadProfile`).

### 1.5 Tiers de prioridade formais (d)

Cadeia documentada e logada (o card de diagnóstico da fase C passa a mostrar a
origem: `MODEL` / `SDL_DB` / `CAPABILITIES` / `DEFAULT` / `USER` — USER é a fase
K5, reservar o tier no enum já):

```
USER (K5, futuro) > MappingDatabase (MODEL) > sdlDb (SDL_DB)
  > CapabilityMapping (CAPABILITIES) > defaultAndroidMapping (DEFAULT)
```

Regra de escalonamento SDL (SDL_gamepad.c:2214-2221): um tier só é sobrescrito por
tier de prioridade ≥. No fork isso é a própria ordem da cadeia em
`GamepadHub.mappingFor` — o que muda é: (1) `CAPABILITIES` inserido antes do
`DEFAULT`; (2) origem registrada em `GamepadDevice.mappingSource` para UI/log.

## 2. Arquivos afetados

| Arquivo | Mudança |
|---|---|
| `gamepad/mapping/GamepadCapabilities.kt` | NOVO — data class puro (1.1) |
| `gamepad/mapping/CapabilityMapping.kt` | NOVO — síntese + classify puros (1.2) |
| `gamepad/GamepadDevice.kt` | campo `capabilities` + `mappingSource` (defaults null) |
| `gamepad/GamepadHub.kt` | coleta no addDevice; cadeia `mappingFor` com CAPABILITIES + origem |
| `gamepad/GamepadButton.kt` | +6 valores (1.4) |
| `gamepad/mapping/MappingParser.kt` | casos misc1/paddle/touchpad |
| `gamepad/mapping/SdlControllerDb.kt` | hint → faceStyle (1.3); buttonByName novos |
| `ui/screen/settings/SettingsGroupGamepad.kt` | linha "Mapping: <origem>" no device card (C) |
| `res/values*/strings.xml` | chaves da origem do mapping |
| `app/src/test/.../CapabilityMappingTest.kt` | NOVO — shapes (1.2 regras 1–6) |
| `app/src/test/.../SdlControllerDbTest.kt` | +hint, +misc1/paddle |
| `app/src/test/.../MappingParserTest.kt` | +botões extras |

## 3. Verificação (gate)

```
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:testModernDebugUnitTest \
  --tests "*CapabilityMapping*" --tests "*Mapping*" --tests "*Sdl*" --tests "*Gamepad*"
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:assembleModernDebug
```

## 4. On-device (humano — registrar "on-device pendente")

Mi 11 + DS4 + um controle genérico DInput (DragonRise se disponível) + Silksong:
1. DS4 conecta → card mostra `SDL_DB`/`MODEL` e comportamento idêntico ao atual
   (regressão zero em pad conhecido).
2. Generic DInput 1-stick → sem binding fantasma de R3/face ausente; harness
   `adb shell setprop debug.gamenative.input "key:96"` acende só o que existe.
3. Remote BT (se houver) → BACK navega (fallback REMOTE).
4. Paddle em DualSense/Elite (se houver) → aparece como fonte de camada no editor.

## 5. Não-metas

Migrar os 234 entries legacy (hash de nome do SDL2 — Android normaliza esses pads);
desenhar botões extras no mock visual; editar mappings in-DB; K5 (save USER) e K6
(interchange) — fases próprias.
