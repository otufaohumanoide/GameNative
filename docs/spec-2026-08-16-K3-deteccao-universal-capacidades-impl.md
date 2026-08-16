# Impl doc — Spec 2026-08-16 K3 (detecção universal por capacidades + botões extras do DB)

**Data:** 2026-08-16
**Spec:** `docs/spec-2026-08-16-K3-deteccao-universal-capacidades.md` (executor: sub-agente
autônomo; fase K3 do master roadmap universal input)
**Resultado:** implementado; gate completo verde (tests `*CapabilityMapping* *Mapping* *Sdl*
*Gamepad*` + `assembleModernDebug`); commit `feat(gamepad): …` na §6. Verificação
on-device pendente (protocolo humano na §4 do spec).

## 1. O que foi feito (por seção do spec)

### §1.1 `GamepadCapabilities` — data class puro + coleta no hotplug

`app/src/main/java/app/gamenative/gamepad/mapping/GamepadCapabilities.kt` (NOVO):

- `data class GamepadCapabilities(keycodes: Set<Int>, axes: List<Int>, hasHat:
  Boolean, isGamepadSource: Boolean)` — file:line 16. Zero `android.*`, JVM-testável;
  KDoc cita a origem SDL3 (zlib — `SDLControllerManager.java` `getAxisMask`:449 /
  `getButtonMask`:485).

`app/src/main/java/app/gamenative/gamepad/mapping/AndroidConstants.kt`:

- `ALL_CANDIDATE_KEYCODES: IntArray` — file:line 42: a MESMA lista de keycodes do
  `getButtonMask` do SDL3 (A/B/X/Y, BACK, MENU, MODE, START, THUMBL/R, L1/R1, DPAD,
  SELECT, DPAD_CENTER, L2/R2, C/Z, BUTTON_1..16) + constantes novas `BACK` (4),
  `MENU` (82), `DPAD_CENTER` (23) — file:lines 15-23.

`app/src/main/java/app/gamenative/gamepad/GamepadHub.kt` (`addDevice`, file:line 1403):

- Coleta no MESMO ponto do `hasGyro`/`hasTouchpad` (V11, fora do hot path): UMA
  chamada binder `inputDevice.hasKeys(*AndroidConstants.ALL_CANDIDATE_KEYCODES)`
  (file:line 1413) → `keycodes` presente; `motionRanges` com `SOURCE_JOYSTICK`
  ordenados por axis id (file:lines 1419-1423); `hasHat` = HAT_X e HAT_Y presentes
  (file:lines 1426-1428); `isGamepadSource` do flag SOURCE_GAMEPAD (file:line 1429).
- Guardada em `GamepadDevice.capabilities: GamepadCapabilities? = null`
  (`GamepadDevice.kt` file:line 37 — null = não coletado → degradação para o default
  estático atual, byte-identical).

### §1.2 `CapabilityMapping` — síntese pura (regras 1-6)

`app/src/main/java/app/gamenative/gamepad/mapping/CapabilityMapping.kt` (NOVO):

- `object CapabilityMapping` com `synthesize(caps, faceStyle): GamepadMapping?`
  (file:line 71) e `classify(caps): DeviceShape` (file:line 57);
  `enum class DeviceShape { GAMEPAD, DINPUT_GENERIC, REMOTE, KEYBOARD }` (file:line 179).
- KDoc cita a origem: `SDL_CreateMappingForAndroidGamepad` (SDL3 zlib,
  `SDL_gamepad.c:705-831`). Reimplementação limpa em Kotlin, sem `android.*`.
- Regra 1 (só o que existe): `key(button, candidates)` só emite se o keycode está em
  `caps.keycodes` (file:lines 97-101); eixos só se o AXIS_* está em `caps.axes`
  (`axis(...)` file:lines 151-153). Sem R3 → sem RIGHT_STICK; pad de 1 stick sem
  Z/RZ → sem RIGHT_X/RIGHT_Y (regra 6, file:lines 154-157).
- Regra 2 (recusa por shape): sem botão E sem eixo → `null` (file:line 72,
  espelho de SDL_gamepad.c:745-750/753-755); sem face e sem dpad → REMOTE com
  `BACK → FACE_BOTTOM` e resto vazio (file:lines 77-91 — o análogo do BACK→"b" do
  SDL_gamepad.c:778-781 na posição de confirmação do fork); REMOTE sem BACK/SELECT
  → `null` (fica o default estático + log).
- Regra 3 (guide gateado): `key(GUIDE, [BUTTON_MODE])` (file:line 122) — a
  capability É o gate (o SDL gateia por API, aqui o keycode é o gate).
- Regra 4 (triggers): eixo LTRIGGER/RTRIGGER quando presentes (file:lines 162-173);
  senão keycode L2/R2 como botão — nunca ambos, eixo preferido (SDL prioriza axis).
- Regra 5 (dpad): keycodes DPAD_* se existirem (file:lines 139-144); senão `hasHat`
  → `RawBinding.Hat(0, máscara)` (file:lines 145-150 — mesmas máscaras do caminho
  `genericDInput(dpadViaHat = true)` da MappingDatabase).
- Fallback de EAST do SDL (SDL_gamepad.c:773-776): BUTTON_B ausente usa BACK como
  FACE_RIGHT, com o clear de máscara (`button_mask &= ~BACK` → BACK não vira SELECT
  também; file:lines 105-117), gateado por `hasFace` (o SDL só chega nesse branch com
  a máscara de face ≠ 0 — um remote só-dpad não ganha face fantasma).
- `classify`: KEYBOARD (sem botão e sem eixo) → GAMEPAD (SOURCE_GAMEPAD) →
  DINPUT_GENERIC (face/dpad/hat) → REMOTE (file:lines 57-64).

### §1.3 Hint do DB (botões posicionais vs. rotulados)

`app/src/main/java/app/gamenative/gamepad/mapping/SdlControllerDb.kt`:

- `usesButtonLabels` lido ANTES do loop de bindings (file:lines 60-67): a forma
  positiva `hint:SDL_GAMECONTROLLER_USE_BUTTON_LABELS:=1` conta; a forma negada
  `!NOME:=1` do SDL (mapping posicional, usado como está) não.
- `faceStyleForVendor(mappingKey, usesButtonLabels = false)` (file:line 130): o hint
  VENCE para vendor não-identificável (→ NINTENDO), PERDE para Sony/MS (054c/045e —
  inequívocos). Análogo do `SDL_ConvertMappingToPositionalBAXY` (SDL_gamepad.c:2535)
  na camada de rótulo que o fork já tem, sem mudar o formato interno (nenhum binding
  é reescrito). O asset atual (`gamecontrollerdb.txt`, 299 entradas Android) NÃO tem
  hint — comportamento byte-identical hoje; o parse fica pronto para entries futuras.

### §1.4 Botões extras do DB: MISC1/PADDLE/TOUCHPAD

- `GamepadButton` ganha `MISC1, PADDLE_1..4, TOUCHPAD` em APPEND no fim do enum
  (`GamepadButton.kt` file:lines 32-37 — nomes estáveis serializam no perfil por
  `.name`, nunca por ordinal). KDoc cita o enum SDL3 (zlib, SDL_gamepad.h).
- `MappingParser.buttonByName` casos novos (`MappingParser.kt` file:lines 183-190);
  `SdlControllerDb.buttonByName` casos novos (`SdlControllerDb.kt` file:lines
  251-258). `touchpad:b13`/`misc1:b14` usam o espaço genérico `bN` existente
  (`BUTTON_1 + N`); no `SdlControllerDb`, `b31` (touchpad click de algumas entries)
  e `BUTTON_15/16` já eram cobertos pela tabela `sdlButtonKeyCode` (b20..35) —
  nenhuma mudança lá.
- NÃO entram no `ControllerVisualLayout` (fase B) nem no `defaultAndroidMapping` —
  são fonte para camadas/radial/expressões (o `ExprParser` resolve
  `GamepadButton.entries` por nome — `ExprParser.kt:204` — então PADDLE_1 vira
  gatilho de camada sem mudança extra).
- `GamepadGlyphProvider` ganha `else -> gamepad_glyph_generic_other` nos 3 whens
  exaustivos (file:lines 47, 68, 89 — labels novas fora do escopo desta fase).
- Perfil: sem campo novo (bindings são mapa por nome — spec §1.4 confirma que
  `isDefault()`/`merged()` não mudam).

### §1.5 Tiers de prioridade formais

`app/src/main/java/app/gamenative/gamepad/GamepadDevice.kt`:

- `enum class MappingSource { USER, MODEL, SDL_DB, CAPABILITIES, DEFAULT }` (file:line
  52) — ordem de declaração = prioridade (USER reservado para a K5); campo
  `GamepadDevice.mappingSource: MappingSource? = null` (file:line 42).

`app/src/main/java/app/gamenative/gamepad/GamepadHub.kt`:

- `resolveMapping(device)` (file:line 1346): a ORDEM da cadeia É a prioridade —
  MODEL → SDL_DB → **CAPABILITIES** → DEFAULT (regra de escalonamento do SDL,
  SDL_gamepad.c:2214-2221, citada no KDoc). `CAPABILITIES` inserido ANTES do DEFAULT
  com a origem registrada em par.
- `mappingCache` por deviceId (file:line 1337): o resultado é determinístico por
  device (vid/pid + capabilities imutáveis) — o hot path (~120 Hz por stick + hats)
  NÃO aloca mapping por evento (a síntese alocaria mapas novos a cada chamada);
  invalidado em addDevice (file:line 1469), removeDevice (file:line 1488) e stop
  (file:line 248) — deviceId é efêmero. Acesso só na main thread (contrato M1).
- `addDevice` resolve a origem UMA vez no hotplug e guarda no device
  (`device.copy(mappingSource = ...)`, file:lines 1470-1473); o
  `GamepadDeviceAddedEvent` carrega a origem.
- Log do hotplug estendido com `shape=` e `mapping=` (file:lines 1482-1487 — o
  diagnóstico em logcat, padrão `gncontrol`/`GamepadHub: added`).

`app/src/main/java/app/gamenative/ui/screen/settings/DeviceDiagnosticsCard.kt`:

- Linha `Mapping: <origem>` (monoespaçada) no card de diagnóstico da fase C
  (file:lines 198-209), com `device.mappingSource` — null → linha escondida
  (byte-identical). String EN `gamepad_diag_mapping_source` =
  "Mapping: %1$s" (`values/strings.xml` file:line 2490) e pt-rBR
  "Mapeamento: %1$s" (`values-pt-rBR/strings.xml` file:line 2358).
- **Nota de desvio mínimo da tabela §2 do spec**: a tabela aponta
  `SettingsGroupGamepad.kt` para a linha, mas o card de device da fase C vive no
  arquivo próprio `DeviceDiagnosticsCard.kt` (movido na fase C — o
  `SettingsGroupGamepad` só o COMPÕE via `key(deviceId)`). A linha foi adicionada ao
  card (o lugar que o §1.5 descreve: "o card de diagnóstico da fase C passa a
  mostrar a origem"); nenhuma edição no `SettingsGroupGamepad.kt` foi necessária.

## 2. Testes

- `app/src/test/java/app/gamenative/gamepad/mapping/CapabilityMappingTest.kt` (NOVO):
  16 testes cobrindo as regras 1-6 + classify — gamepad completo (≡ default menos os
  triggers duplos, regra 4), R3 ausente, stick direito só com Z/RZ, triggers
  eixo→botão (nunca ambos), dpad keycode > hat > nada, GUIDE gateado por MODE,
  REMOTE (BACK→FACE_BOTTOM, resto vazio; sem BACK → null; classificação), KEYBOARD
  (vazio → null), remote só-dpad sem face fantasma (BACK vira SELECT), fallback de
  EAST (BACK→FACE_RIGHT com clear de máscara; sem B e sem BACK → EAST vazio).
- `app/src/test/java/app/gamenative/gamepad/mapping/SdlControllerDbTest.kt`:
  +hint (positivo vira NINTENDO para vendor genérico; perde para Sony/MS; forma
  negada não muda; `faceStyleForVendor` com parâmetro novo) e +misc1/paddle/touchpad
  (b7/b17/b18/b31 → keycodes do enum SDL; paddles 1-4 via b20-23).
- `app/src/test/java/app/gamenative/gamepad/mapping/MappingParserTest.kt`:
  teste de tolerância atualizado (touchpad/misc1/paddle1 AGORA parseiam; paddle4:b16
  continua fora do espaço genérico b0..b15) + teste dedicado dos 6 botões extras.

## 3. Gate (executado verde)

```
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:testModernDebugUnitTest \
  --tests "*CapabilityMapping*" --tests "*Mapping*" --tests "*Sdl*" --tests "*Gamepad*"
→ BUILD SUCCESSFUL (163 tests)
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:assembleModernDebug
→ BUILD SUCCESSFUL
```

## 4. On-device (HUMANO — "on-device pendente")

Protocolo da §4 do spec (Mi 11 + DS4 + generic DInput DragonRise se disponível +
remote BT se houver + Silksong):

1. DS4 conecta → card mostra `MODEL` (054c09cc está no MappingDatabase) e
   comportamento idêntico ao atual (regressão zero em pad conhecido). Logcat:
   `GamepadHub: added … shape=GAMEPAD mapping=MODEL`.
2. Generic DInput 1-stick → card mostra `CAPABILITIES`, sem binding fantasma de
   R3/face ausente; harness `adb shell setprop debug.gamenative.input "key:96"`
   acende só o que existe.
3. Remote BT (se houver) → `shape=REMOTE mapping=CAPABILITIES`, BACK navega
   (FALLBACK para FACE_BOTTOM).
4. Paddle em DualSense/Elite (se houver) → aparece como fonte de camada no editor
   (o `ExprParser` resolve por nome — PADDLE_1..4/MISC1/TOUCHPAD).
5. Device desconhecido com entrada no gamecontrollerdb → `mapping=SDL_DB` (tier
   novo visível no card).

## 5. Não-metas respeitadas

Os 234 entries legacy continuam ignorados (GUID sem vid/pid — `mappingKeyFromGuid`
intocado); nenhum botão extra desenhado no mock visual; nenhum mapping in-DB
editado; K5 (save USER) e K6 (interchange) não foram tocados (o tier `USER` está
SÓ reservado no enum). Nenhuma cópia de código SDL: semânticas reimplementadas em
Kotlin com origem citada no KDoc de cada arquivo novo.

## 6. Commit

- `feat(gamepad): detecção universal por capacidades — síntese CAPABILITIES
  (port clean-room do SDL_CreateMappingForAndroidGamepad), hint de rótulos do DB,
  botões extras MISC1/paddles/touchpad e tiers MODEL/SDL_DB/CAPABILITIES/DEFAULT/USER
  (spec 2026-08-16-K3-deteccao-universal-capacidades)` — `aa0132c2`.
