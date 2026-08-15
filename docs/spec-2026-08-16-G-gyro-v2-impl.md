# Impl doc — Spec 2026-08-16 G (Gyro v2: sub-pixel, smoothing, per-axis, shaping, toggle, grip angle)

**Data:** 2026-08-16
**Spec:** `docs/spec-2026-08-16-G-gyro-v2.md` (executor: agente; ordem G1→G6)
**Resultado:** implementado, gate completo verde (testes `*Gyro*` `*OneEuro*`
`*GamepadProfileStore*` + `assembleModernDebug`). Verificação on-device pendente
(protocolo humano na §6).

## 1. O que foi feito (por item do spec)

### G1 — Acumulador sub-pixel (MOUSE)

`app/src/main/java/app/gamenative/gamepad/processing/GyroPixelAccumulator.kt` (NOVO):

- `data class GyroMouseState(remX, remY)` (file:line 16) + `object
  GyroPixelAccumulator.accumulate(deltaXPx, deltaYPx, state): Pair<Int, Int>`
  (file:lines 23-37): soma em float, emite a parte inteira, guarda a fração — padrão
  DS4Windows `horizontalRemainder` (MouseCursor.sixaxisMoved). Sem flag: correção
  pura (o spec não prevê caminho antigo).
- Hub: `gyroMouseStates: MutableMap<Int, GyroMouseState>` (GamepadHub.kt:712, V6 —
  morto no removeDevice:959); aplicado no branch MOUSE de `onSensorSample`
  (file:lines 584-599). O `.toInt()` antigo (GamepadHub.kt:531 pré-G) descartava a
  fração — giro lento abaixo de 1 px/amostra nunca movia o cursor.
- Correção G-v2-revisão: o estado também morre na DESATIVAÇÃO (branch `!active`,
  junto do OneEuro — mesmo padrão `SixMouseReset` do DS4Windows).

### G2 — OneEuro opt-in (MOUSE)

`app/src/main/java/app/gamenative/gamepad/processing/OneEuroFilter.kt` (NOVO):

- `class OneEuroFilter(minCutoff, beta, dCutoff)` (file:line 23) — implementação
  padrão do paper (Casiez et al. 2012): low-pass do valor + predição pela derivada
  filtrada. Params `var` públicos (o hub re-lê por amostra — o usuário pode salvar
  perfil novo com smoothing ligado, padrão DS4Windows `SetupLateOneEuroFilters`).
  `reset()` (file:line 55) re-ancora. Defaults DS4Windows
  `DEFAULT_MINCUTOFF = 1.0` / `DEFAULT_BETA = 0.7` (ProfilePropGroups.cs:196-197) +
  dCutoff 1.0. Puro: nenhum android.*.
- Hub: `GyroSmoothState` privado (file:1022 — par de filtros + `lastSampleMs` +
  `rateHz`) keyed por device em `gyroSmoothStates` (file:713, V6 — removido no
  removeDevice:960). `gyroSmoothFor(deviceId, profile, nowMs)` (file:753): null
  quando AMBOS os campos do perfil são null (OFF → sem alocação, byte-identical);
  rateHz = 1/dt com o MESMO clamp 1..100 ms do GyroProcessor. Aplica nos deltas EM
  RADIANOS por eixo ANTES da conversão em pixels (file:lines 580-589) e compõe com
  G1 (o acumulador recebe o delta filtrado). O estado morre quando o gyro desativa
  (file:line 560 — mesmo padrão do `SixMouseReset` do DS4Windows, que empurra zeros
  no filtro com o trigger inativo).

### G3 — Sensibilidade por eixo + inversão

- Perfil: `gyroSensitivityY: Float?`, `gyroInvertX/gyroInvertY: Boolean?`
  (GamepadProfile.kt:80-82) — null = usa `gyroSensitivity` / false; `isDefault()`
  (file:142-144) e `merged()` (GamepadProfileStore.kt, null-preserva) atualizados.
- Hub: `sensX`/`sensY` calculados uma vez por amostra em `onSensorSample`
  (file:lines 552-555) com a inversão absorvida no sinal.
- Contrato do sink estendido para `(yawRadS, pitchRadS, sensX, sensY, maxOutput,
  antiDeadzone)` — o G4 entra no MESMO contrato (ver §2.2). Declaração no hub
  (file:693-700), única lambda no XServerScreen.kt:2545-2550 (sem locals novas —
  só os parâmetros da lambda) e único call site `PhysicalControllerHandler.
  applyCameraGyro` (file:450-467), que aplica `deflection(yaw, sensX, ...)` /
  `deflection(pitch, sensY, ...)` por eixo. O call site do Flick Stick
  (`deflection(yaw, 1f)`, file:580) fica nos defaults — intacto.

### G4 — Shaping do CAMERA

`app/src/main/java/app/gamenative/gamepad/processing/GyroStickMapping.kt`:

- `DEFAULT_MAX_OUTPUT = 1.0` / `DEFAULT_ANTI_DEADZONE = 0.0` (file:lines 29-32);
  `deflection(angularVelRadS, sensitivity, scale, maxOutput, antiDeadzone)`
  (file:line 44): `out = anti + (maxOut − anti)·|linear|` sobre a deflexão linear
  pós-deadzone (file:lines 56-58) — remap afim `(dz..1] → (anti..maxOut]`: floor
  logo acima da deadzone (salto mínimo, semântica SixMouseStick) e teto maxOutput.
  Defaults = linear antigo EXATO (testado byte-identical). Zero continua zero
  (repouso nunca gera deflexão).
- Perfil: `gyroStickMaxOutput: Float?`, `gyroStickAntiDeadzone: Float?`
  (GamepadProfile.kt:89-90) — hub repassa no sink (file:lines 562-566, 630-634).

### G5 — Ativação TOGGLE

- `app/src/main/java/app/gamenative/gamepad/processing/GyroActivation.kt` (NOVO,
  puro): `onPressButton(latch, toggle)` (borda de PRESS flipa o latch) e
  `active(held, latch, toggle)` (toggle lê latch; hold lê botão) — testado em JVM.
- Hub: `gyroActivateLatches` por device (file:709, V6 — morto no removeDevice:961 e
  no `setActiveAppId`:168, junto do re-arme do perfil novo). Escrito NO caminho onde
  `gyroActivateHeld` já é escrito (emitLogical, file:lines 415-426): ButtonDown do
  botão de ativação com `gyroActivateToggle == true` inverte o latch.
  `gyroActivateHeld(deviceId, profile)` (file:lines 732-748) decide via
  `GyroActivation.active`. O recenter da borda off→on é o existente do
  GyroProcessor — sai de graça.
- **Correção G-v2-revisão (decisão de revisão):** o spec original dizia "borda de
  descida", mas o flip acontece no PRESS — padrão DS4Windows `IsGyroTriggerActive`
  (edge no press), eliminando a surpresa "segurar mantém ligado, SOLTAR desliga".
  Ver §2.7.
- **Bugfix descoberto no caminho** (ver §2.3): a amostra inativa agora emite o
  REPOUSO do stick CAMERA (file:lines 556-572) — sem isto o toggle deixaria a
  deflexão congelada a cada desligada.

### G6 — Grip angle

- `GyroProcessor`: `GyroConfig.gripAngleDeg: Float = 0f` (file:line 64);
  `gripAngleFromAccel(accelX, accelZ)` pura (file:line 128) = `atan2(ax, az)` em
  GRAUS. Em `process`, o par (X, Z) CALIBRADO (raw − offset) é rotacionado por
  R(−θ) em torno do eixo longitudinal (Y) ANTES da extração yaw/pitch e da deadzone
  (file:lines 165-181): `rotX = x·cosθ − z·sinθ`, `rotZ = x·sinθ + z·cosθ`. θ=0
  degenera exatamente para os eixos atuais (branch dedicado — byte-identical). A
  calibração contínua NÃO foi tocada: continua usando raw−offset por eixo (a
  rotação só afeta o delta) e a stillnes usa magnitude — a rotação preserva a
  norma do par (testado).
- Hub: `calibrateGrip(deviceId)` (file:673-687) — lê `state.lastSample` (accel já
  coletado P2-3), computa θ e salva `gyroGripAngleDeg` no perfil do DEVICE (a
  pegada é propriedade física do usuário + hardware; o override por-jogo continua
  vencendo no merge). Sem lastSample ou sem accel (0,0,0 — harness/device sem
  accel) = no-op (nunca grava θ=0 por cima de um grip real). Config wired no
  `GyroConfig` do hub (file:lines 524-528).
- `DeviceDiagnosticsCard`: botão "Calibrar grip" ao lado do "Recentrar giroscópio"
  (só com `hasGyro`) chamando `hub.calibrateGrip` (file:lines 271-286).

### Perfil / Store

- `GamepadProfile`: 9 campos novos null-default (file:lines 75-95) com política V1
  do store (downgrade preserva chaves desconhecidas — `KNOWN_FIELDS` deriva do
  serializer, então o passthrough sai de graça); `isDefault()` (file:lines 138-151);
  `GamepadProfileStore.merged()` null-preserva os 9 (file:lines 178-190 do store).
- `ProfileCatalog.summaryOf`: os 9 campos contam como categoria GYRO
  (ProfileCatalog.kt:130-139).
- `tools/profiles/sync_profile_repo.py`: allowlist espelho atualizada (campos,
  floats finitos, bools) — a validação do catálogo não rejeita perfis com gyro v2;
  saída determinística verificada (regeneração byte-identical).

### UI (GamepadRemapDialog, seção Gyro)

- Slider "Sensibilidade vertical" (G3) sob o de sensibilidade (file:lines 1032-1039);
- Switch "Alternar" (G5, só com botão de ativação configurado — sem botão o gyro é
  sempre ativo e o switch não aparece) (file:lines 1098-1106);
- Switches "Inverter X/Y" (G3) (file:lines 1107-1116);
- Switch "Suavização (One Euro)" + sliders corte mínimo/beta quando ON (G2)
  (file:lines 1117-1142);
- Sliders "Saída máxima"/"Anti-zona morta" (G4, só no modo CAMERA)
  (file:lines 1143-1161);
- Slider "Ângulo de pegada" −90..+90° + hint apontando o botão de calibração do
  card de diagnóstico (G6) (file:lines 1162-1179).
- Helper `GyroToggleRow` (file:lines 1732-1778): mesmo padrão de navegação da
  linha de fusão (`gamepadSelectable` + Switch sem foco próprio).
- Estado/`editorProfile()`/`applyImportedProfile()` com os 9 campos (defaults
  colapsam em null — política do store; import/export clip+arquivo herdam de graça).

### Strings

13 novas por idioma (EN `values/` + pt-rBR `values-pt-rBR/`): sensibilidade
vertical, toggle+subtítulo, inverter X/Y, suavização+subtítulo, corte mínimo, beta,
saída máxima, anti-zona morta, ângulo de pegada+subtítulo, calibrar grip+subtítulo.

## 2. Decisões e desvios

### 2.1 Curva do G4 (afim vs fórmula exata do SixMouseStick)

O spec descreve `(dz..1) → (adz..maxOutput)` — um remap AFIM do intervalo. A
fórmula exata do DS4Windows (`xNorm = (1−anti)·ratio + anti` com `ratio` clampado em
`maxOutRatio`) produz, com anti>0 E maxOut<1, um teto de `anti + (1−anti)·maxOut`
(≠ maxOut). Implementei o afim (`out = anti + (maxOut − anti)·|linear|`) porque é o
que a notação de intervalo do spec diz e dá a semântica limpa "teto = maxOutput";
a semântica SixMouseStick preservada é o floor anti-deadzone (salto mínimo logo
acima da deadzone) e o teto. Defaults (1.0, 0) = linear antigo EXATO nos dois.

### 2.2 Contrato do sink (G3+G4 juntos)

O G3 define o contrato como `(yawRadS, pitchRadS, sensX, sensY)`; o G4 precisa que
`maxOutput`/`antiDeadzone` cheguem ao ÚNICO call site (`applyCameraGyro`), que não
tem acesso ao `GamepadProfile` (o `profile` do handler é `ControlsProfile` do
winlator). O contrato final ficou `(yawRadS, pitchRadS, sensX, sensY, maxOutput,
antiDeadzone)` — os dois itens entram no mesmo sink (o hub já resolve o perfil por
amostra; resolver de novo no handler seria acoplamento e custo duplicado). Sem
locals novas no XServerScreen (só os parâmetros da lambda).

### 2.3 Bugfix: repouso do CAMERA em amostra inativa (era código morto)

O branch `else { gyroCameraSink?.invoke(0f, 0f, sensitivity) }` do CAMERA existia
com a intenção documentada de garantir o repouso do stick ao soltar o botão, mas
ficava ATRÁS do `if (!output.active) return` — morto: a deflexão congelava no
último valor enquanto o botão ficava solto (e o recenter da próxima ativação só
mascarava). O toggle do G5 tornaria o defeito visível a cada desligada. Corrigido
no caminho inativo (file:lines 556-572): CAMERA emite `(0,0,…)` no release/toggle
off; G1+G2 removem estado no mesmo ponto (o resto sub-pixel também morre — padrão
`SixMouseReset`). Registrado como divergência deliberada (bugfix habilitado pelo
G3/G5, verificação on-device na §6).

### 2.4 Sinal do grip angle

`gripAngleFromAccel = atan2(ax, az)` (literal do spec). Com o pad inclinado por φ
em torno do eixo longitudinal, o accel lê `ax = −g·sinφ` ⇒ θ = −φ — o ângulo
armazenado é o INVERSO da rotação física; a compensação no processor é R(−θ)
(`rotX = x·cosθ − z·sinθ`, `rotZ = x·sinθ + z·cosθ`). Verificado nos DOIS sentidos
com o caso do pad de lado: direito para baixo ⇒ θ=−90° e a rotação vertical do
mundo (chegando no eixo X do sensor) vira yaw puro de sinal correto (testes
`grip minus 90 compensates a pad held on its side` / `grip angle from accel reads
the side tilt` — o par fecha o loop calibração→compensação). A verificação
on-device pode inverter o sinal se a convenção do accel do DS4 divergir do modelo.

### 2.5 Escopo do grip angle = device

O botão de calibração salva no perfil do DEVICE (não do jogo): a pegada é
propriedade física do usuário + hardware. O slider da seção Gyro do remap continua
editando o escopo do dialog aberto (device por padrão). Merge: jogo vence — um
override por-jogo não é sobrescrito pela calibração.

### 2.6 Smoothing "ambos null = OFF"

Um campo só com o outro null: liga com o default DS4Windows no ausente (o spec
define "ambos null = OFF"; a UI liga/desliga os dois juntos, então o caso meio-ligado
só aparece via import/edição manual — comportamento definido, não exceção).

### 2.7 Correções da revisão (commit de fix, mesma spec)

Revisão pós-implementação (avaliação do plano G) apontou 3 ajustes + 1 nota, todos
aplicados neste commit:

1. **G4 — curva sempre monotônica**: com `antiDeadzone > maxOutput` (configurável
   na UI: anti 0..1.0 × maxOut 0.1..1.0) o remap afim ficava NÃO-monotônico (mais
   rotação = menos deflexão). Fix em dois níveis: processor clampa
   `anti ≤ maxOutput` (GyroStickMapping.kt — perfis importados do catálogo também
   passam por lá, a UI não é a única porta de entrada) e o slider da UI ganha range
   dinâmico `0..gyroStickMaxOutput` (GamepadRemapDialog.kt). Teste novo cobre o
   caso.
2. **G1 — estado morre na desativação**: o commit G dizia "morre na desativação"
   mas só o OneEuro era removido; o resto sub-pixel persistia (salto <1 px possível
   na reativação — invisível, mas inconsistente com o `SixMouseReset` citado). Agora
   `gyroMouseStates.remove(deviceId)` roda junto do `gyroSmoothStates.remove`
   (hub, branch `!active`).
3. **G5 — flip no PRESS, não no release**: o texto original do spec (§1 G5) dizia
   "borda de descida"; a fonte citada no próprio spec (DS4Windows
   `IsGyroTriggerActive`) flipa no press. Press-flip elimina a surpresa "segurar
   mantém ligado, soltar desliga" e é o comportamento esperado por quem já usou
   DS4Windows/JoyShock. Implementação: `GyroActivation.onPressButton` chamado no
   ButtonDown (emitLogical); `active()` inalterado. Divergência deliberada do texto
   original do spec, registrada aqui.
4. **Nota (sem código)**: com o botão de ativação consumido por uma camada SHIFT
   (F §1.3), o `emitLogical` não roda e o latch não flipa — limitação herdada do
   tracking pós-remap do hold (pré-existente ao G), documentada apenas.

## 3. Testes (JVM, objetos puros)

- `OneEuroFilterTest` (NOVO): constante passa intacta; escada converge com lag e
  overshoot limitado (predição da derivada — o overshoot ~0.7 em degrau 10 é o
  comportamento clássico do paper); senoide 20 Hz @ 100 Hz atenuada < 10% da
  amplitude de entrada; `reset()` re-ancora.
- `GyroPixelAccumulatorTest` (NOVO): 0.4 px × 5 → [0,0,1,0,1] com resto 0; 0.5 × 4
  → resto exato 0 (reset); 0.3 px/amostra move no 4º (o defeito original nunca
  movia); negativos simétricos; eixos independentes.
- `GyroStickMappingTest` (novas): defaults byte-identical ao linear antigo; floor
  anti-deadzone ±; teto maxOutput ±; pontos da curva afim; zero continua zero com
  shaping; anti > maxOutput clampa e permanece monotônico (fix §2.7.1).
- `GyroProcessorTest` (novas): θ=0 idêntico ao legado (deltas E velocidades); θ=90°
  mistura os eixos (X do sensor vira yaw); θ=−90° compensa pad de lado (yaw puro);
  calibração contínua intacta com θ=45° (janela absorve bias); `gripAngleFromAccel`
  flat/virado/lados.
- `GyroActivationTest` (NOVO): latch flipa no PRESS só com toggle (fix §2.7.3 — UP
  não flipa); hold lê botão; ciclo completo de press/release em toggle.
- `GamepadProfileStoreTest` (novas): roundtrip dos 9 campos; merge jogo-vence +
  null-preserva; detecção de default inclui os 9.

## 4. Gate

- `./gradlew :app:testModernDebugUnitTest --tests "*Gyro*" --tests "*OneEuro*"
  --tests "*GamepadProfileStore*"` → verde (85+ testes; 1 falha inicial foi
  asserção errada do teste de atan2(0, −g) = 180°, corrigida — código intacto).
- `./gradlew :app:assembleModernDebug` → verde.
- `tools/profiles/sync_profile_repo.py` → catálogo regenerado byte-identical
  (allowlist ampliada não muda o seed atual).

## 5. Não-metas respeitados

Taxa FASTEST, gyro swipes, remap por eixo de sensor, calibração de fábrica, yaw
absoluto — intocados.

## 6. On-device pendente (DS4 + Silksong, Mi 11)

1. **MOUSE**: giro MUITO lento move o cursor em passos de 1 px (G1 — antes não
   movia); sem smoothing, byte-identical com a build anterior.
2. **MOUSE + smoothing ON**: movimentos lentos suaves, rápidos sem atraso
   perceptível (minCutoff 1.0/beta 0.7); jitter parado some.
3. **Sinais por eixo**: sensibilidade vertical ≠ horizontal; inversão X/Y refletem
   no cursor (MOUSE) e na câmera (CAMERA).
4. **CAMERA + shaping**: maxOutput < 100% satura o stick cedo; anti-deadzone dá o
   salto mínimo acima da deadzone; defaults idênticos ao comportamento anterior.
5. **CAMERA + toggle**: aperta liga (flip no press), aperta de novo desliga; stick
   volta ao centro a cada desligada (bugfix §2.3); recenter na borda off→on (sem
   salto).
6. **Grip**: segurar o pad na pegada natural → "Calibrar grip" no card de
   diagnóstico → yaw vira horizontal de verdade no CAMERA/MOUSE (sinal confirmado
   ou invertido — §2.4). Slider manual idem.
7. Harness `gyro:x:y:z` cobre G1/G2/G6 sem hardware (protocolo do AGENTS.md).
