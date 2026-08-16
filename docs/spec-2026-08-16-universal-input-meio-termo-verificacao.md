# Verificação de meio-termo — Universal Input (F0/K3/K4/K5) + estado da fila

**Data:** 2026-08-16
**Contexto:** o humano executou as fases F0–K5 do roadmap universal input e se
perdeu no estado; este doc registra a auditoria INDEPENDENTE (código + git, sem
confiar só na tabela §7 do master) e o gate que resta re-executar.
**Master:** `docs/spec-2026-08-16-master-roadmap-universal-input.md`
**Guia do fluxo residual:** `docs/spec-2026-08-16-guia-universal-input-fechamento.md`

## 1. Estado real auditado (git log + código, 2026-08-16)

Working tree LIMPO; branches `feat/joystick-avancado` e `feat/retroarch-shaders`
já mergeadas no master; nenhum trabalho K6+ esquecido em branch.

| Fase | Commit | Status | Evidência auditada (file:line) |
|---|---|---|---|
| 0 — correções J1 | `d1c7f600` | ✅ | eixo `expr:` parseado em `ExprBindingProcessor.kt:44-47` (button E axis; `continue` só se ambos null); token malformado volta a pass-through em `GamepadHub.kt:854-856` (`null -> listOf(event)`) |
| K3 — detecção | `aa0132c2` | ✅ | `CapabilityMapping`/`GamepadCapabilities` em `gamepad/mapping/`; enum `GamepadButton.kt:30-39` +MISC1/PADDLE_1..4/TOUCHPAD; hint de rótulos em `SdlControllerDb.kt:60-67` (forma negada `!NOME:=1` também); cadeia USER>MODEL>SDL_DB>CAPABILITIES>DEFAULT em `GamepadHub.kt:1505-1515`; coleta de capabilities no addDevice (`GamepadHub.kt:1583-1649`) |
| K4 — quirks | `5a12bd7e` | ✅ | `DeviceQuirks.kt:21/29/263/271/286/308-331` (tabela ordenada, matcher com `bluetoothOnly`, gate por capabilities com null=conservador, `apply` devolve a MESMA ref quando nada muda); aliases de scanCode no caminho do KeyEvent com gate `KEYCODE_UNKNOWN` (`GamepadHub.kt:1191-1198`); `quirkName` no device p/ card |
| K5 — autoconfig | `4af898ed` | ✅ | `DeviceAutoconfig.kt:28/48/64` (modelo + validação RA); `DeviceMappingStore.kt:34/51/75/82/88/91-97` (V1 com re-injeção de chaves desconhecidas, write atômico tmp+rename, cache M1); USER primeiro na cadeia (`GamepadHub.kt:1505-1507`); `reResolveAutoconfig` ao vivo (`:428-440`); `baseMappingCache` pré-quirk (save captura mapping sem quirk); UI no `DeviceDiagnosticsCard.kt` (linha `Mapping: %s` :198-209, botões salvar/restaurar, diálogos de erro/confirmação :348-435) |
| K6 — intercâmbio | — | ⬜ NÃO INICIADA | sem `SdlMappingCodec.kt` em `gamepad/mapping/` |
| K2 — modo mouse | — | ⬜ NÃO INICIADA | sem `MouseModeProcessor.kt` em `gamepad/processing/`; sem campos `mouseMode*` no `GamepadProfile`; sink sem `scroll()` (`GamepadTouchpadForwarder.kt:39-44`) |
| K1 — virtual de toque | — | ⬜ NÃO INICIADA | sem `gamepad/virtual/`; `com/winlator/inputcontrols/*` intocado desde a fase B |
| K7 — calibração | — | ⬜ NÃO INICIADA | sem `JoystickHistoryView.kt` em `ui/component/remap/` (só `ControllerVisualView.kt`); `StickTransform` sem `antiDeadzone`/`maxOutput` |

## 2. Auditoria por fase (o que foi conferido, não só confirmado)

### F0
As DUAS correções do §5 do doc de verificação H/I/J estão no código (tabela §1).
Nenhuma regressão visível nos caminhos vizinhos (`PendingEmit` da fase I
preservado; `GamepadBindingCodec.decode` com `when` completo).

### K3
- Síntese por capacidades com as 6 regras do spec (botão só se existe; recusa por
  shape REMOTE/KEYBOARD; guide gateado; trigger eixo-ou-botão; dpad keycode>hat;
  stick direito só com Z/RZ) — `CapabilityMappingTest` (16) cobre.
- Hint do DB honrado com precedência correta (NINTENDO para vendor genérico;
  perde para Sony/MS).
- Botões extras no enum por NOME (nunca ordinal — serialização estável).
- Tier CAPABILITIES inserido ANTES do DEFAULT; origem exposta no card.

### K4
- Aplicação em DOIS pontos (mapping-level pós-cadeia + event-level scanCode) com
  cache por device (resolvido uma vez no hotplug, não por evento).
- `apply` puro com identidade de referência quando fixup vazio (degradação zero).

### K5
- Store V1 CORRETO no save (lê antes de escrever — bug de perda de chaves
  desconhecidas que o próprio gate pegou está corrigido e testado).
- Tier USER com invalidação e re-resolve ao vivo; ordem quirk-DEPOIS documentada
  no KDoc (quirk = correção de transporte, não preferência).
- Validação idêntica à do RetroArch (FACE_BOTTOM em Key/Hat + uma direção).

## 3. Desvios encontrados — ACEITOS (documentados nos impl docs, benignos)

1. **K3:** linha de origem do mapping foi para `DeviceDiagnosticsCard.kt` (lugar
   do card da fase C), não `SettingsGroupGamepad.kt` — o spec §2 apontava o
   arquivo errado; o card é o lugar descrito no §1.5.
2. **K5:** não existem testes JVM do `GamepadHub` (hub importa `android.*`; o
   repo testa os objetos puros — precedente K4). A cadeia é composição de partes
   já testadas; cobertura via gate de compilação + assemble + on-device.

**Conclusão: NENHUMA correção de código pendente nas fases commitadas.**

## 4. Gate independente (a executar — V0 do guia)

O padrão do repo exige re-execução pós-`clean` (classes stale podem compilar "de
mentira"). Antes de qualquer fase nova:

```
cd /home/annapaula/GameNative && ./gradlew clean
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:testModernDebugUnitTest \
  --tests "*Expr*" --tests "*CapabilityMapping*" --tests "*Mapping*" --tests "*Sdl*" \
  --tests "*Quirk*" --tests "*Autoconfig*" --tests "*DeviceMapping*" --tests "*Gamepad*" \
  --offline
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:assembleModernDebug --offline
```

- Verde → registrar aqui (§4 resultado) e seguir o guia.
- Vermelho → NÃO mate o gradle com timeout (incidente do jar corrompido
  registrado na verificação H/I/J §1); repita UMA vez pós-clean; persistindo,
  abra fix-commit referenciando esta seção ANTES de qualquer fase nova.
- Esperado: ≥ 163 testes (K3) + 126 (K5) + *Expr*/*Quirk* — contagem final
  registrada no resultado.

**Resultado: ✅ VERDE (2026-08-16, agente).** `clean` + gate completo pós-clean
(`--offline`): **239 testes, 0 falhas, 0 erros** em 21 suites (Expr* 35 +
CapabilityMapping 16 + SdlControllerDb 16 + DeviceQuirks 22 + DeviceMappingStore
12 + AutoconfigValidation 7 + demais gamepad/mapping — acima do esperado) + `:app:assembleModernDebug` OK (12 m 53 s testes; 8 m 55 s assemble;
gradle em background SEM timeout — regra do guia §5.1). Nenhuma correção de
código pendente nas fases commitadas.

> Nota do agente (pré-K6): durante a preparação da fase K6 encontrou-se UM bug
> de mapping/hub REAL (categoria "fix ANTES de K6" do guia §2): o modelo do
> universal usava `AXIS_Z=2/AXIS_RZ=3` (ordem do driver da SDL `a2`/`a3`) onde o
> `AndroidInputAdapter` chaveia pelos ids REAIS do MotionEvent (`AXIS_Z=11`,
> `AXIS_RZ=14` — javap platforms/android-36) — o stick direito ficava mudo no
> pipeline universal (default/DB/capabilities). Fix-commit próprio referenciando
> esta seção, com teste de regressão que falha antes e passa depois.

## 5. Residuais que seguem para os docs do guia

1. **Dívida on-device acumulada** — A–F, H/I/J1/J2, F0, K3/K4/K5 todos
   "on-device pendente" → `docs/spec-2026-08-16-protocolo-on-device-consolidado-v2.md`
   (sessões A/B/C, humano).
2. **Fases restantes da fila** → `docs/spec-2026-08-16-universal-input-retomada-fila.md`
   (K6→K2→K1→K7; specs originais INALTERADAS — a auditoria confirmou que os
   anchors delas continuam válidos; K6 destravada: dependia do parser K3 e do
   store K5, ambos entregues).
3. **Milestone do roadmap** (padrão do repo: tag no fechamento, não por fase) →
   `docs/spec-2026-08-16-universal-input-fechamento.md`.
4. Backlog UX (`spec-2026-08-16-backlog-ux-follow-ups.md`): #1 (Kp/Ki GUI) e
   #12 (calibração no mock) serão absorvidos pelo K7 — atualizar no fechamento.

## 6. Não-metas deste doc

Re-auditar H/I/J1/J2 (já têm verificação independente própria); refazer gates já
verdes sem o `clean`; mexer em specs K (o que está commitado está aprovado).
