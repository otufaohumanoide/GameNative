# Spec 2026-08-16 K4 — Tabela declarativa de quirks por vid/pid/transport (port moonlight)

**Data:** 2026-08-16
**Origem:** moonlight-android `reference/moonlight-android/app/src/main/java/com/
limelight/binding/input/ControllerHandler.java` — `handleRemapping` (**linhas
1312–1547**, tabela de fixup por device), cascata de eixos de trigger
(`createInputDeviceContextForDevice` **710–930**: LTRIGGER/RTRIGGER → BRAKE/GAS →
BRAKE/THROTTLE → RX/RY (DS4 BT não-padrão) ou Z/RZ), scancodes crus de dpad
**704–707** quando não há `.kl`. Clean-room: reimplementar as SEMÂNTICAS como
tabela declarativa Kotlin; moonlight é GPL-3 — NUNCA copiar código.
**Executor:** sub-agente autônomo. Leia `AGENTS.md` + master roadmap universal §1/§2.
**Posição na fila:** fase K4 (depois de K3 — commit de fronteira obrigatório).
**Turn budget sugerido:** 12–15 turns. Esforço S.

## 0. Estado atual (anchors)

- `gamepad/mapping/MappingDatabase.kt`: quirks hoje HARD-CODED em entradas
  específicas (ex.: `genericDInput(dpadViaHat)`, comentário "quirks de trigger
  BRAKE/GAS"). Não há mecanismo geral; cada caso novo vira código.
- `gamepad/GamepadHub.kt:1325-1331`: cadeia `mappingFor` (K3 introduziu
  `capabilities` + `mappingSource`); fixups precisam rodar DEPOIS da resolução,
  como pós-processamento do `GamepadMapping` escolhido.
- `gamepad/mapping/RawBinding.kt`: `Key(keycode)` / `Axis(axis, signal)` — o
  alvo das substituições.
- `gamepad/mapping/GamepadMapping.kt`: buttons/axes maps — imutável; quirks geram
  uma CÓPIA com bindings substituídos (não mutam a entry da DB).

O que FALTA: mecanismo declarativo (a tabela) + cobertura dos casos clássicos que
o moonlight documenta, aplicado em TODOS os tiers (MODEL/SDL_DB/CAPABILITIES).

## 1. Design

### 1.1 `DeviceQuirks` — tabela pura (JVM-testável)

Novo `gamepad/mapping/DeviceQuirks.kt`:

```kotlin
data class DeviceQuirkMatcher(
    val vendorId: Int? = null,        // null = qualquer (precisa de outro critério)
    val productId: Int? = null,
    val nameContains: String? = null, // case-insensitive no nome do InputDevice
    val bluetoothOnly: Boolean? = null,
)
data class DeviceQuirkFixup(
    val replaceButton: Map<GamepadButton, RawBinding> = emptyMap(), // ex.: SELECT→START
    val replaceAxis: Map<GamepadAxis, RawBinding> = emptyMap(),     // ex.: trigger em RX/RY
    val scanCodeAliases: Map<Int, Int> = emptyMap(),                // ex.: 704→DPAD_LEFT
)
object DeviceQuirks {
    fun firstMatch(vendorId: Int, productId: Int, name: String, isBt: Boolean): DeviceQuirkFixup?
    fun apply(mapping: GamepadMapping, fixup: DeviceQuirkFixup?): GamepadMapping
}
```

`apply` é pura: mapping in → mapping out (cópia); fixup null → retorna o MESMO
objeto (identidade de referência — degradação zero, sem alocação).

### 1.2 Seed inicial da tabela (casos documentados no moonlight)

| Matcher | Fixup | Origem (ControllerHandler.java) |
|---|---|---|
| 8BitDo (vid `0x2dc8`) em BT, nome `*Pro*`/`*SN30*` | botões A/B trocados em alguns firmwares; guide→`BUTTON_15` quando presente | :1312-1360 (vários `8BitDo` cases) |
| Switch Pro (vid `0x057e`) BT | HOME→`BUTTON_MODE` via scanCode 316; stick offsets | :1360-1400 |
| DS4 BT (vid `0x054c`) sem `.kl` | triggers em `AXIS_RX/RY`; touchpad click = `KEYCODE_BUTTON_1` | :1400-1470, mapa :72-105 |
| Xbox BT genérico | `BUTTON_1/2` → bumpers em alguns stacks | :1470-1500 |
| ASUS Gamepad (vid `0x0b05`) | back→start (scanCode alias) | :1500-1520 |
| SHIELD (vid `0x0955`) | search→mode | :1520-1547 |
| Qualquer sem `.kl` com scancodes 704–707 | scanCodeAliases 704–707 → DPAD esq/dir/cima/baixo | :1500+ (raw d-pad) |

Valores exatos (keycode/scanCode) devem ser EXTRAÍDOS da leitura do moonlight
durante a implementação — a tabela acima define a ESTRUTURA e os casos; o spec não
finge precisão que só a fonte tem. Cada entry cita `ControllerHandler.java:NNN` no
KDoc (padrão de atribuição do repo).

### 1.3 Aplicação — dois pontos, ambos fora do hot path

1. **Mapping-level** (o comum): em `GamepadHub.mappingFor`, após escolher o
   mapping do tier vencedor → `DeviceQuirks.apply(mapping, fixup)`. Cache junto
   (`profileFor`-like): o fixup é resolvido uma vez por hotplug/addDevice, não
   por evento.
2. **Event-level** (só scanCodeAliases): no caminho raw de `KeyEvent` do hub,
   `keyCode == 0` (ou keycode genérico `KEYCODE_UNKNOWN`) + scanCode na tabela →
   substitui o keycode ANTES de `remapEvent`. Guard: só se o matcher do device
   ativo pediu aliases (lookup O(1) no mapa pequeno).

### 1.4 Diagnóstico

`GamepadDevice.mappingSource` (K3) ganha sufixo quando quirk ativo: `SDL_DB+QUIRK`.
Log único no addDevice: `gncontrol: quirk <nome> aplicado (<origem>)`. Nada de log
por evento.

## 2. Arquivos afetados

| Arquivo | Mudança |
|---|---|
| `gamepad/mapping/DeviceQuirks.kt` | NOVO — matcher/fixup/apply puros + seed (1.1/1.2) |
| `gamepad/GamepadHub.kt` | apply em `mappingFor` + scanCode alias no caminho de KeyEvent |
| `gamepad/GamepadDevice.kt` | sufixo de diagnóstico (se K3 já não cobrir) |
| `res/values*/strings.xml` | label de quirk ativo no device card |
| `app/src/test/.../DeviceQuirksTest.kt` | NOVO — match por vid/pid/nome/BT; apply idempotente; fixup null = mesma ref; alias de scanCode |

## 3. Verificação (gate)

```
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:testModernDebugUnitTest \
  --tests "*Quirk*" --tests "*Gamepad*"
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:assembleModernDebug
```

## 4. On-device (humano — "on-device pendente")

1. DS4 por BT (o device-teste primário): comportamento idêntico ao atual
   (regressão zero — o quirk do DS4 BT só deve ativar se os triggers NÃO vierem
   em LTRIGGER/RTRIGGER, decidido pelas capabilities de K3).
2. Harness `adb shell setprop debug.gamenative.input "key:0:704"` (se o protocolo
   aceitar scanCode; senão registrar pendência e testar com controle físico
   afetado).
3. 8BitDo/Switch Pro se disponíveis: um botão por fixup, Silksong.

## 5. Não-metas

Heurística fuzzy de nome; quirk editável pelo usuário (a tabela é código);
calibração de stick por quirk ( StickTransform cobre); contribuir fixups
upstream ao moonlight.
