# Impl doc — Spec 2026-08-16 K4 (tabela declarativa de quirks por vid/pid/transport)

**Data:** 2026-08-16
**Spec:** `docs/spec-2026-08-16-K4-quirks-vidpid.md` (executor: sub-agente autônomo;
fase K4 do master roadmap universal input)
**Resultado:** implementado; gate completo verde (tests `*Quirk* *Gamepad*` +
`assembleModernDebug`); commit `feat(gamepad): …` na §6. Verificação on-device
pendente (protocolo humano na §5).

## 1. O que foi feito (por seção do spec)

### §1.1 `DeviceQuirks` — tabela pura (JVM-testável)

`app/src/main/java/app/gamenative/gamepad/mapping/DeviceQuirks.kt` (NOVO):

- `object DeviceQuirks` (file:line 21) com a tabela `table: List<DeviceQuirk>`
  (file:line 29) na ordem de prioridade (específicos antes, catch-all por último).
- `data class DeviceQuirkMatcher` (file:line 308) — exatamente o contrato do spec:
  `vendorId`/`productId`/`nameContains` (case-insensitive, `matches(...)`
  file:line 348)/`bluetoothOnly` — null = critério não exigido.
- `data class DeviceQuirkFixup` (file:line 318) — `replaceButton`/`replaceAxis`/
  `scanCodeAliases` como no spec.
- `data class DeviceQuirk` (file:line 331) — entry da tabela: `name` + `source`
  (origem ControllerHandler.java:NNN) para o diagnóstico do §1.4 + `needed:
  (GamepadCapabilities?) -> Boolean` (gate de capabilities K3 — "capability decide
  se o quirk é necessário").
- `firstMatch(vendorId, productId, name, isBt)` (file:line 263) — matcher puro
  (contrato de teste do §2); devolve a ENTRY (e não só o fixup) porque o §1.4
  precisa de nome/origem para o log e o card.
- `resolve(..., caps)` (file:line 271) — matcher + gate de capabilities; caps null
  = conservador (entries gateadas por capability NÃO ativam — degradação
  byte-identical).
- `apply(mapping, fixup)` (file:line 286) — pura: CÓPIA com `mapping.copy(...)`
  (file:lines 296-304, a entry da DB nunca é mutada); fixup null ou sem
  substituições de button/eixo → retorna o MESMO objeto (file:lines 288-291,
  identidade de referência, degradação zero, sem alocação).

### §1.2 Seed da tabela (casos documentados no moonlight, valores extraídos da fonte)

Entradas em `DeviceQuirks.table` (todas com KDoc citando a origem):

| Entry (name) | Matcher | Fixup | Origem |
|---|---|---|---|
| `8BitDo mode button` | vid `0x2dc8` | alias 306→BUTTON_MODE | :1341-1344 |
| `Switch Pro (pre-hid-nintendo)` | vid `0x057e`+pid `0x2009`, BT | aliases 304-315, 317→A/B/X/Y/L1/R1/L2/R2/SELECT/START/THUMBL/THUMBR/MODE | :1349-1379 |
| `DS4 non-standard (RX/RY triggers)` | vid `0x054c`, BT | LEFT_TRIGGER→AXIS_RX(+1), RIGHT_TRIGGER→AXIS_RY(+1); TOUCHPAD→Key(BUTTON_1); alias 317→BUTTON_1 | :818-878, :1335-1339, :72-105 |
| `Xbox Wireless Controller (old BT firmware)` | nome `Xbox Wireless Controller`, BT | GUIDE→Key(MENU); aliases 306-313→X/Y/L1/R1/SELECT/START/THUMBL/THUMBR, 139→MODE | :979-993, :1440-1468 |
| `ASUS ROG Kunai (USB)` | vid `0x0b05`+pid `0x7900` | aliases 264/266→START, 265/267→SELECT | :1469-1485 |
| `ASUS ROG Kunai (BT)` | vid `0x0b05`+pid `0x7902` | idem | :1469-1485 |
| `ASUS Gamepad (back=start, mode=select)` | vid `0x0b05`+nome `ASUS Gamepad` | START→Key(BACK), SELECT→Key(MODE) | :943-959 |
| `SHIELD Controller v01.03/v01.04 (search=mode)` | vid `0x0955`+nome exato | GUIDE→Key(SEARCH) | :961-968, :1541-1543 |
| `Raw d-pad scancodes (704-707)` (catch-all) | qualquer | aliases 704-707→DPAD esq/dir/cima/baixo + bindings DPAD_*→Key(DPAD_*) | :1487-1509 |

Divergências documentadas da tabela-resumo do spec (a regra do próprio spec §1.2:
"os valores exatos devem ser EXTRAÍDOS da leitura do moonlight — o spec não finge
precisão que só a fonte tem"):
- **8BitDo**: a fonte NÃO gateia transporte nem nome (:1341-1344) — o matcher é
  só vid; o guard de keyCode UNKNOWN do §1.3.2 impede falso positivo.
- **Switch Pro**: HOME é scanCode 0x13D = **317** (não 316 — 316/0x13C nem aparece
  no switch da fonte); o moonlight só re-mapeia com SDK < Q; aqui o guard de
  keyCode UNKNOWN torna a entry inerte com o .kl moderno (hid-nintendo, Android
  11+) — mesma degradação, sem checar SDK na tabela pura.
- **ASUS Gamepad**: back→start NÃO é alias de scanCode na fonte — são os flags
  `backIsStart`/`modeIsSelect` (:943-959), implementados como replaceButton
  (START→BACK, SELECT→MODE) com gate de capabilities (sem BUTTON_START e sem
  MENU).
- **Raw d-pad**: além dos aliases, o fixup garante os bindings DPAD_*→Key(DPAD_*)
  no mapping — o tier CAPABILITIES de um device desconhecido não emite bindings de
  dpad sem a capability de keycodes DPAD, e o tradutor casa por `mapping.buttons`
  (sem isso o alias corrigiria o keycode mas não haveria binding para casar).

### §1.3 Aplicação — dois pontos, ambos fora do hot path

1. **Mapping-level**: `GamepadHub.kt` — `quirkCache` (file:line 1361, `Map<Int,
   DeviceQuirk?>`, mesma vida do `mappingCache` K3); `resolveMapping` aplica
   `DeviceQuirks.apply(base.first, quirkCache[...]?.fixup)` DEPOIS da cadeia
   MODEL→SDL_DB→CAPABILITIES→DEFAULT (file:line 1388) — pós-processamento do
   mapping escolhido, cacheado por deviceId (uma vez por hotplug, nunca por
   evento). `addDevice` resolve o quirk UMA vez (file:lines 1501-1508);
   `removeDevice`/`stop` limpam o cache (file:lines 1533, 251).
2. **Event-level (só scanCodeAliases)**: `onKey` (file:lines 1095-1107) — guard
   curto-circuita por `raw.keyCode == KEYCODE_UNKNOWN` (um int compare; sem
   keycode desconhecido nada é consultado — caminho atual byte-identical) e,
   quando casa, substitui o keycode ANTES de `EventTranslator.translateKey`
   (portanto antes do remapEvent). O scanCode cru chega ao hub via
   `RawKeyInput.scanCode: Int = 0` (NOVO campo default — `RawInput.kt`
   file:line 23; `AndroidInputAdapter.toRawKey` file:line 23) — chamadores
   antigos intactos.

### §1.4 Diagnóstico

- `GamepadDevice.quirkName: String? = null` (NOVO — `GamepadDevice.kt`
  file:line 47) + label derivada `mappingSourceLabel` com sufixo `+QUIRK`
  (file:lines 53-59 — ex.: `SDL_DB+QUIRK`); o card do device usa a label derivada
  (`DeviceDiagnosticsCard.kt` file:line 203) e mostra a linha do quirk ativo
  (`R.string.gamepad_diag_quirk`, file:line 216; strings EN
  `res/values/strings.xml:2491` + pt-rBR `res/values-pt-rBR/strings.xml:2359`).
- Log único no addDevice: `gncontrol: quirk <nome> aplicado (<origem>)`
  (`GamepadHub.kt` file:line 1524) — nada de log por evento.

### Suporte (fora da lista do §2, necessário para os casos do §1.2 funcionarem)

- `AndroidConstants.kt`: `SEARCH` (84), `KEYCODE_UNKNOWN` (0) — file:lines 33-34;
  `AXIS_RX`/`AXIS_RY` (12/13) — file:lines 63-64.
- `AndroidInputAdapter.toRawAxis`: coleta RX/RY (file:lines 73-74) — sem isso o
  quirk de trigger em RX/RY do DS4 não chegaria ao tradutor.
- `EventTranslator.emitTrigger` (`EventTranslator.kt` file:line 138): triggers
  ligados a RX/RY (eixos CENTRADOS, neutro em −1) são normalizados com
  `(v + 1) / 2` — o `triggersIdleNegative` do moonlight (ControllerHandler.java:
  875-876, 1613-1620). Eixos 0..1 (LTRIGGER/RTRIGGER/BRAKE/GAS) intactos.
- `DebugGamepadInput.kt`: verbo novo `scan:<scancode>[:down|up]` (file:line 226) —
  KeyEvent com keyCode KEYCODE_UNKNOWN + scanCode explícito (o protocolo `key:`
  congelado não leva scanCode; o spec §4.2 autoriza "se o protocolo aceitar
  scanCode" — o verbo próprio É o caminho aceito).
- Heurística de transporte BT (`GamepadHub.isBluetoothInput`, file:line 1611):
  nome "wireless"/"bluetooth" → descriptor "bluetooth" → vendor 0x057e (Nintendo
  BT-only). Conservadora: sem sinal → false → quirks gateados por BT não ativam
  (degradação ao caminho atual). O Android não expõe API pública de transporte.

## 2. Arquivos alterados

| Arquivo | Mudança |
|---|---|
| `gamepad/mapping/DeviceQuirks.kt` | NOVO — matcher/fixup/apply puros + seed (10 entries) |
| `gamepad/GamepadHub.kt` | quirkCache + apply em resolveMapping; alias de scanCode em onKey; resolve+log no addDevice; heurística BT |
| `gamepad/GamepadDevice.kt` | `quirkName` + `mappingSourceLabel` (`+QUIRK`) |
| `gamepad/mapping/AndroidConstants.kt` | SEARCH, KEYCODE_UNKNOWN, AXIS_RX, AXIS_RY |
| `gamepad/mapping/RawInput.kt` | `RawKeyInput.scanCode` (default 0) |
| `gamepad/mapping/AndroidInputAdapter.kt` | scanCode no toRawKey; RX/RY em toRawAxis |
| `gamepad/mapping/EventTranslator.kt` | domínio idle-negative de trigger em RX/RY |
| `ui/screen/settings/DeviceDiagnosticsCard.kt` | label `+QUIRK` + linha do quirk |
| `ui/component/DebugGamepadInput.kt` | verbo `scan:` do harness (§4.2 do spec) |
| `res/values*/strings.xml` | `gamepad_diag_quirk` (EN + pt-rBR) |
| `app/src/test/.../DeviceQuirksTest.kt` | NOVO — 22 testes |

## 3. Testes (gate)

`DeviceQuirksTest.kt` (NOVO, 22 testes, JVM): match por vid/pid/nome/BT
(case-insensitive, bluetoothOnly, catch-all); gate de capabilities (DS4 com
triggers LTRIGGER/RTRIGGER → sem quirk = regressão zero do §4.1; DS4 sem triggers
mas com RX/RY → quirk com triggers RX/RY + TOUCHPAD→BUTTON_1 e entry original
intacta; caps null → sem quirk; Xbox gateado por GAS ausente; raw d-pad gateado
por !hasHat); apply idempotente; fixup null/só-alias = mesma referência; conteúdo
do seed (aliases 306/317/264-267/704-707); domínio idle-negative de trigger em RX
(neutro −1 não emite; 1 emite > 0.9).

```
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:testModernDebugUnitTest --tests "*Quirk*" --tests "*Gamepad*"
→ BUILD SUCCESSFUL (DeviceQuirksTest: 22/22, 0 failures; suíte filtrada completa verde)
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:assembleModernDebug
→ BUILD SUCCESSFUL
```

## 4. Limitações conhecidas (documentadas, não bloqueiam)

- **Xbox BT/Switch Pro por scanCode**: o moonlight re-mapeia INCONDICIONALMENTE
  por scanCode; aqui os aliases disparam só com keyCode UNKNOWN (contrato do spec
  §1.3.2) — em firmware onde o .kl entrega keycode ERRADO (não UNKNOWN) o alias
  não dispara. É a escolha segura do spec; o caso moonlight completo exigiria
  override incondicional (fora do desenho).
- **fullAxis + trigger em RX/RY**: se um perfil user ligar um token `a12`/`a13`
  com mod fullAxis a um trigger de um device com o quirk DS4 ativo, a conversão de
  domínio roda duas vezes (`preApplyFullAxis` e a normalização do emitTrigger).
  Caso teórico (token fora do espaço de eixos SDL 0-7); nenhum caminho default
  afeta.
- **Heurística BT**: sem API pública; DS4 USB ("Wireless Controller") é lido como
  BT — inofensivo, porque o quirk do DS4 é gateado pelas capabilities (triggers),
  igual ao moonlight (que não distingue transporte no DS4).

## 5. Verificação on-device (humano — "on-device pendente")

Protocolo no spec §4 (Mi 11 / DS4 BT / Silksong):

1. **DS4 por BT — regressão zero**: comportamento idêntico ao atual. O quirk do
   DS4 só ativa se os triggers NÃO vierem em LTRIGGER/RTRIGGER (capabilities K3).
   Evidência: logcat sem `gncontrol: quirk`; card sem "Quirk:".
2. **Alias de scanCode**: `adb shell setprop debug.gamenative.input scan:704`
   (verbo novo — o protocolo `key:` não leva scanCode; registrado o caminho aceito
   pelo spec §4.2) — com um device sem hat conectado, o d-pad esquerdo deve
   chegar ao jogo; `scan:0` é neutro.
3. **8BitDo/Switch Pro se disponíveis**: um botão por fixup, Silksong
   (ex.: 8BitDo: botão guide/mode via scanCode 306 quando o .kl não mapeia).
4. **Card do device**: com quirk ativo, `Mapeamento: <TIER>+QUIRK` + linha
   `Quirk: <nome>`; logcat `gncontrol: quirk <nome> aplicado (<origem>)` UMA vez
   no addDevice.

## 6. Commit

`feat(gamepad): …` — ver §7 do master roadmap (checkpoint idempotente).
