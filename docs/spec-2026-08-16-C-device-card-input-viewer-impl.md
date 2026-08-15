# Impl doc — Spec 2026-08-16 C (cartão de device com input viewer ao vivo)

**Data:** 2026-08-16
**Spec:** `docs/spec-2026-08-16-C-device-card-input-viewer.md` (executor: sub-agente autônomo)
**Resultado:** implementado, gate completo verde, commit `feat(gamepad): …` (ver §6 do
master roadmap). Verificação on-device pendente (protocolo humano na §5).

## 1. O que foi feito (por seção do spec)

### §1.1 `DeviceDiagnosticsCard` — Compose, arquivo próprio

`app/src/main/java/app/gamenative/ui/screen/settings/DeviceDiagnosticsCard.kt` (NOVO):

- `DeviceDiagnosticsCard(device, isActive)` — file:line 65. Header byte-identical ao
  `ConnectedDeviceRow` antigo quando recolhido: nome (peso/cor por `isActive`),
  bateria (hidden quando null), badges GYRO/TOUCHPAD — mesmos textos, estilos, cores
  e espaçamentos (file:lines 116-148); o toggle de colapso é o PRÓPRIO header via
  `gamepadSelectable` (A/DPAD_CENTER/toque; file:line 122). Sem chevron novo — o
  conteúdo do header fica idêntico ao estado atual (escolha do spec "header idêntico").
- Expansível SÓ com device ativo (spec §1.1): clique em device não-ativo é no-op
  (file:line 123) e `LaunchedEffect(isActive)` recolhe o card se o device PERDER a
  ativação (file:lines 72-75). `key(device.deviceId)` no chamador fixa a identidade
  do card por device (hotplug não reusa estado entre ids).
- **Input viewer** (reuso integral de B): `ControllerVisualView` com
  `faceStyle = device.faceStyle`, `hotspots = ControllerVisualLayout.layoutFor(...)`
  (file:lines 154-156, 185-195), `stateOf = { VisualControlState.AUTO }` (diagnóstico,
  não remap — badges neutros, hotspots sem ação de toque) e flash ao vivo por
  `GamepadInputEvent` do deviceId (filtro via `ControllerVisualLayout.flashControlFor`,
  file:line 160 — mesma pureza de B). Listener registrado UMA vez no
  `DisposableEffect(Unit)`, lê o deviceId via `rememberUpdatedState` no MOMENTO do
  evento (lição C1) e retorna `false` (observador, nunca consome input — file:lines
  155-169). Expiração do set em ~600 ms no laço do card (file:lines 173-182); o
  decaimento do alpha é do componente (deriva timestamps, igual ao dialog de B).
- **Readouts mono ~10 Hz** (file:lines 202-216): laço de 100 ms lê os StateFlows de
  preview NO MOMENTO da amostragem e mantém o último valor DESTE device (o StateFlow
  guarda a última amostra global — filtro por deviceId no card). Gyro só com
  `device.hasGyro` (file:lines 217-227); touchpad só com `device.hasTouchpad`
  (file:lines 228-243). Fonte monoespaçada (`FontFamily.Monospace`), formato
  `%.2f` Locale.US (mesmo padrão do slider de deadzone), placeholder "—" sem amostra.
- **Botões de teste**: "Testar vibração" (reuso de A — `GamepadHaptics.rumbleDevice
  (deviceId, 0.6f, 0.6f, 300L)` + `rumbleTargetFor`, resultado CONTROLLER/PHONE/NONE
  auto-limpo em ~3 s, file:lines 247-268; comportamento idêntico ao row antigo);
  "Recentrar giroscópio" só com gyro (file:lines 270-275, chama
  `hub.recenterGyro(deviceId)`); "Todos os botões" = só instruções + o viewer
  acescendo, sem lógica (file:lines 277-286).
- Hooks de preview ligam/desligam no `DisposableEffect(expanded)` (file:lines 77-87):
  ON quando o card está expandido/visível, desligados SEMPRE no dispose (collapse,
  saída da tela, hotplug — limpeza garantida). OFF ⇒ caminho byte-identical.

### §1.2 `GamepadHub.gyroPreview` + `recenterGyro` — hook de observação

`app/src/main/java/app/gamenative/gamepad/GamepadHub.kt`:

- `@Volatile var gyroPreviewEnabled: Boolean = false` (file:line 105) +
  `gyroPreview: StateFlow<GyroPreview?>` sobre `MutableStateFlow` interno
  (file:lines 107-108); `data class GyroPreview(deviceId, yawRadS, pitchRadS,
  timestampMs)` (file:lines 848-856).
- Write NO FIM de `onSensorSample` (file:lines 556-559), APÓS todo o processamento
  existente e SEM alterá-lo: `if (gyroPreviewEnabled) _gyroPreview.value =
  GyroPreview(deviceId, output.yawRadS, output.pitchRadS, nowMs)` — 1 write por
  amostra quando ON, ZERO quando OFF (byte-identical). As velocidades são as do
  `GyroProcessor` (morta pela deadzone) — recenterar zera o readout (verificação
  on-device). Nenhuma linha do pipeline foi tocada (diff = só inserções).
- `recenterGyro(deviceId)` (file:lines 569-573): reaplica a âncora de offset do
  `GyroState` do device com `state.lastSample`; sem lastSample (gyro nunca ativado/
  inativo) = no-op. Mesma operação da borda de ativação — extraída para função
  reutilizável em `GyroProcessor.recenter(state, sample)` (file:line 102) e chamada
  nos DOIS lugares (a borda em `process`, file:line 112 — refator pura, mesmas 3
  atribuições).

### §1.3 Touchpad readout

`app/src/main/java/app/gamenative/gamepad/GamepadTouchpadForwarder.kt`:

- `@Volatile var previewEnabled: Boolean = false` (file:line 57) +
  `touchpadPreview: StateFlow<TouchpadPreview?>` (file:lines 59-60);
  `data class TouchpadPreview(deviceId, x, y, down)` (file:lines 169-175).
- Write NO TOPO de `onRawTouch`, ANTES de todos os gates de pref (file:lines 66-69):
  `if (previewEnabled) _touchpadPreview.value = TouchpadPreview(raw.deviceId,
  raw.x, raw.y, raw.down)` — o readout funciona mesmo com o toggle mouse OFF; a
  escrita num StateFlow não muda NENHUMA decisão abaixo (o forwarder retorna nos
  mesmos gates de antes). 1 write por evento quando ON, ZERO quando OFF.
- O card coleta (ver §1.1); x/y normalizados 0..1 (mesmo contrato do
  `RawTouchInput`).

### §1.4 Strings

`res/values/strings.xml` file:lines 2430-2436 e `res/values-pt-rBR/strings.xml`
file:lines 2298-2304 — padrão `gamepad_diag_*`: readouts de gyro/touchpad, sufixo de
toque, botão recentrar (título + hint) e instruções "Todos os botões" (título + dica).
Testar vibração reusa as strings existentes `gamepad_rumble_test_*` (fase A).

## 2. Arquivos afetados

| Arquivo | Mudança |
|---|---|
| `ui/screen/settings/DeviceDiagnosticsCard.kt` | NOVO (§1.1) |
| `ui/screen/settings/SettingsGroupGamepad.kt` | troca o row pelo card (file:lines 120-128); `ConnectedDeviceRow`/`CapabilityBadge` migraram para o card; estado/efeito do teste de vibração migraram para o card (auto-limpeza idêntica); imports limpos. NADA mais foi tocado (switches/sliders/remap intactos) |
| `gamepad/GamepadHub.kt` | `gyroPreview` + `recenterGyro` (§1.2) — SEM tocar no pipeline |
| `gamepad/GamepadTouchpadForwarder.kt` | `touchpadPreview` (§1.3) — decisões intactas |
| `gamepad/processing/GyroProcessor.kt` | `recenter` reutilizável (§1.2, refator pura) |
| `MainActivity.kt` | 1 linha no ponto do gate do ghost input (desvio D1) |
| `res/values*/strings.xml` | chaves `gamepad_diag_*` |
| `app/src/test/.../GyroProcessorTest.kt` | 2 casos novos do recenter (§3) |

## 3. Testes

`GyroProcessorTest` (file:lines 181-216 — novos, JVM puros):
- `explicit recenter zeroes deltas for the held position`: movimento acima da
  deadzone → `recenter` com a amostra atual → a posição mantida vira âncora (deltas 0).
- `explicit recenter is equivalent to the activation edge anchor`: estado recentrado
  explicitamente com B produz outputs IDÊNTICOS ao estado ativado direto em B
  (offsets, delta e o próximo movimento — âncora E lastSample iguais ⇒ mesmo dt).
Os 13 casos existentes (incluindo `activation edge recenters the offset`) continuam
verdes — a refator não mudou o comportamento da borda de ativação.

## 4. Desvios (com justificativa)

- **D1 — `MainActivity.kt` (1 linha, fora da tabela §2 do spec).** O spec pede o
  preview "no processamento de amostra" e a instrução do executor exige que o readout
  funcione "mesmo com o mouse-toggle OFF". Porém o gate do ghost input só ALIMENTAVA
  o forwarder com `PrefManager.gamepadTouchpadMouseEnabled` ON (MainActivity:663
  antes) — com o toggle OFF o `onRawTouch` nunca era chamado. Mudança mínima
  (file:line 667): `if (gamepadTouchpadMouseEnabled || gamepadTouchpad.previewEnabled)`.
  Preview OFF ⇒ condição idêntica à anterior (byte-identical, zero alocação/call);
  preview ON ⇒ o forwarder recebe o evento e o write do topo alimenta o readout; o
  forwarder continua decidindo tudo nos próprios gates (retorna false no gate do
  mouse como antes). Nenhuma decisão de pipeline mudou.
- **D2 — Readout do gyro depende da entrega de amostras (limitação do pipeline,
  não tocada de propósito).** `GamepadSensorSource` só registra listeners com
  container rodando + perfil `gyroMode ≠ OFF` (registro dirigido pelo uso, P1-3) —
  com o app parado em Settings sem jogo, o hook de preview não recebe amostras (o
  readout segura "—"). O spec proíbe tocar no pipeline ("SEM tocar no pipeline",
  invariante byte-identical), então o card é um OBSERVADOR puro: funciona com o
  harness (`adb shell setprop debug.gamenative.input "gyro:x:y:z"` injeta direto em
  `onSensorSample`, com universal ON) e com jogo rodando com gyro habilitado.
  Registrado como condição do protocolo on-device (§5).
- **D3 — Sem chevron no header.** "Header idêntico" do spec = conteúdo colapsado
  byte-identical ao row atual (o row não tinha chevron); o toggle vive no próprio
  header via `gamepadSelectable` (toque/A), sem alterar os textos/badges.
- **D4 — Sufixo de toque sem espaço na string resource.** O aapt corta espaços nas
  pontas de strings; o separador " · " foi colocado no código (file:line 234-238 do
  card) e a resource guarda só a palavra ("touching"/"tocando").

## 5. Verificação

Gate (final, working tree completo):
```
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:testModernDebugUnitTest --tests "*Gamepad*"     → BUILD SUCCESSFUL
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:testModernDebugUnitTest --tests "*GyroProcessorTest" → BUILD SUCCESSFUL (15/15; o filtro *Gamepad* do gate não casa com GyroProcessorTest — rodado à parte por causa da refator do recenter)
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:assembleModernDebug                                    → BUILD SUCCESSFUL
```

On-device pendente (protocolo humano da §3 do spec): DS4 conectado → expandir card →
apertar botões acende o desenho; gyro readout varia girando o controle (condição do
D2: com jogo rodando com gyro ≠ OFF, ou via harness `gyro:x:y:z` com universal ON);
touchpad readout segue o dedo (independente do toggle mouse); testar vibração reporta
controle/telefone/nada; recenterar giroscópio zera o readout do gyro.

## 6. Invariantes respeitadas

- Byte-identical: preview OFF ⇒ ZERO writes no hot path e condição do MainActivity
  idêntica; card recolhido ⇒ header idêntico ao row antigo; `GyroProcessor.process`
  só passou a chamar `recenter()` (mesmas 3 atribuições da borda); pipeline de
  sensores/touchpad intacto (o forwarder retorna nos MESMOS gates).
- `SettingsGroupGamepad.kt` só no ponto pedido (row → card); helpers do card vivem
  no arquivo próprio; a função principal NÃO cresceu (pelo contrário, -117 linhas).
- Handlers leem estado no momento do evento (`rememberUpdatedState`, lição C1);
  listeners do card registrados uma única vez no DisposableEffect com `off` no dispose.
- Nenhuma suíte completa rodada (sempre `--tests`); gradlew sempre com o JAVA_HOME
  do repo; docs e commits em PT-BR.
