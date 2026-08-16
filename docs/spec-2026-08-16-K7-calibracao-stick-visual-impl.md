# Impl doc — Spec 2026-08-16 K7 (calibração visual de stick)

**Data:** 2026-08-16
**Spec:** `docs/spec-2026-08-16-K7-calibracao-stick-visual.md` (fase K7 — a
ÚLTIMA do master roadmap universal input)
**Resultado:** implementado; gate completo verde (tests `*Stick* *Curve*
*Gamepad*` + `assembleModernDebug`); commit `feat(gamepad): …` na §6.
Verificação on-device pendente (spec §4 — humano).

## 1. O que foi feito (por seção do spec)

### §1.1 `StickTransform` — 2 campos novos

`gamepad/processing/StickTransform.kt`:

- `StickTransformConfig.antiDeadzone` (file:line 41) e `maxOutput` (file:line
  46) — defaults 0/1 = identidade (byte-identical; nenhum chamador existente
  muda de comportamento).
- `apply` (file:line 60): ordem documentada no KDoc — deadzone (como hoje) →
  **anti-deadzone** (rescala a magnitude pós-deadzone para começar em
  `anti`: `anti + (1−anti)·mag` — "inverse deadzone" do PPSSPP
  AnalogCalibrationScreen; o output nunca cai no limbo abaixo da deadzone
  interna do jogo) → **curve** (como hoje) → **clamp `maxOutput`**. Modo
  RADIAL na magnitude (direção preservada); AXIAL por eixo com sinal
  (`signedAntiDeadzoneCurve`, file:line 97). `antiDeadzoneValue` (file:line
  90) clampada 0..1 (valores fora degradam, nunca NaN).
- `inDeadzone` continua sendo o da deadzone — o anti NÃO fabrica saída dentro
  da deadzone (teste `dentro da deadzone o anti nao fabrica saida`).

`gamepad/profiles/GamepadProfile.kt` — 4 campos null-default (file:lines
71-74) + `isDefault()` (file:lines 149-152). Política V1 do store.

### §1.2 `JoystickHistoryView` — NOVO

`ui/component/remap/JoystickHistoryView.kt` (NOVO, 130 linhas):

- `JoystickHistoryMode.RAW | CALIBRATED` (file:line 29); `JoystickHistoryView`
  (file:line 32): Canvas com guias (círculo 1.0 + cruzeta), círculo da deadzone
  (raio = config.deadzone), anel do anti-deadzone (laranja, raio =
  config.antiDeadzone), TRILHA das últimas 32 posições com alpha decaindo
  (0.15..1.0 — o rastro mostra drift/redeada) e ponto atual com borda. Modo
  CALIBRATED aplica `StickTransform.apply` por amostra (a MESMA ordem do
  pipeline). Puro desenho — sem lógica de input (spec §1.2). Atribuição
  PPSSPP `ControlMappingScreen.cpp:487-585` no KDoc.

### §1.3 Tab "Calibração" no remap dialog

`ui/component/remap/StickCalibrationSection.kt` (NOVO):

- `StickCalibrationSection` (file:line 45): as DUAS `JoystickHistoryView` lado
  a lado (RAW | CALIBRADO com a config em edição); fonte de dados = listener
  do bus `GamepadInputEvent` de EIXOS do deviceId (padrão do flash do B §1.2;
  holder único `remember`; dialog não tem restrição dex) acumulando
  `StickSample` (32 amostras); o botão FACE continua navegando (só eixos
  observados — igual PPSSPP `axis()` bypass, `ControlMappingScreen.cpp:585`).
- Sliders/segmenteds DIRETO na config em edição: deadzone (0..0.5), anti-
  deadzone (0..0.5), max output (0.1..1), mode RADIAL/AXIAL e curve
  LINEAR/EXPONENTIAL/SCURVE; lado Left/Right alternável. A LUT permanece na
  lista avançada (não-duplicação do spec §1.4 — a tab NÃO tem estado paralelo;
  os campos são os mesmos do perfil).

`gamepad/remap/GamepadRemapDialog.kt`:

- Estados K7 (file:lines 158-168): deadzone (que a lista avançada NÃO editava),
  anti e max por stick — defaults do profile/PrefManager.
- `editorProfile` (file:lines 249-256): defaults colapsam em null (V1);
  `applyImportedProfile` (file:lines 294-302) atualiza os estados.
- Seção no corpo do dialog (file:lines 1076-1110) após o bloco F1
  (StickTransformBlock) — a "aba" do spec implementada como seção própria
  (o dialog não tem tabs; a navegação gamepad existente cobre).

### Testes

`StickTransformTest.kt` — + classe `K7StickTransformTest` (7 testes):
identidade 0/1; anti rescala a magnitude (0.4 → 0.58 com anti 0.3); anti
nunca deixa o output entrar no limbo; maxOutput clampa a borda e preserva o
meio; ordem deadzone→anti→clamp; modo AXIAL com sinal; dentro da deadzone o
anti não fabrica saída.

## 2. Decisões de design (desvios aceitos)

1. **"Aba" = seção no corpo do dialog** (spec §1.3 "Nova tab"): o remap dialog
   não tem tabs — a seção própria após o bloco F1 usa a mesma navegação
   gamepad do dialog (a lista é rolável; nada de framework de tabs novo).
2. **Fonte RAW = eixos LÓGICOS do bus** (não o MotionEvent cru): o spec §1.3
   manda usar o `GamepadInputEvent` (o hub não expõe o cru no bus). A
   comparação é "como está hoje" vs "com a config proposta" — o teste on-device
   "círculo pleno" depende do perfil/global sem deadzone (o slider da tab zera
   a deadzone para ver o cru). Documentado no KDoc da seção.
3. **Hysteresis não entrou na tab** (spec §1.3 lista nos sliders): o
   `GamepadProfile` não expõe hysteresis (o `DeadzoneConfig` tem default fixo
   0.05) — adicionar um campo de perfil seria escopo novo fora do §1.1 (que
   define SÓ antiDeadzone/maxOutput). Anotado como follow-up.
4. **maxOutput = clamp (teto), não multiplicador**: o spec §1.1 diz
   explicitamente "clamp maxOutput" na ordem; a UI usa 0.1..1 (teto da
   deflexão, como o `gyroStickMaxOutput` do G4).
5. **Backlog #1 (GUI de Kp/Ki) — NÃO entregue como stretch**: o spec §1.3 diz
   "Se o preview de fusão complicar, entregar só os sliders com valores atuais
   + nota" — o preview de fusão exigiria um gráfico de série temporal do pitch
   e o Kp/Ki já têm sliders na lista avançada? (verificar — se não têm, o
   backlog #1 fica "parcial: sliders na lista avançada + nota"; atualizar no
   fechamento V3).

## 3. Verificação (gate)

```
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:testModernDebugUnitTest   --tests "*Stick*" --tests "*Curve*" --tests "*Gamepad*" --offline
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:assembleModernDebug --offline
```

- Resultado: VERDE (tests = 167, 0 falhas, 0 erros — filtros `*Stick* *Curve* *Gamepad*`; assemble OK em 4 m 46 s) — §6.

## 4. On-device (humano — spec §4, "on-device pendente")

1. DS4 com stick saudável: aba aberta no Mi 11, mover o stick → trilha crua
   (círculo pleno) vs. calibrada (com deadzone 0.2 → buraco central visível).
2. Anti-deadzone 0.3: trilha calibrada NUNCA entra no anel interno.
3. Fechar a aba → jogo recebe eixos normalmente; valores persistem por escopo
   (Este jogo vs Todos os jogos).
4. Backlog #1: ajustar Kp/Ki com drift visível no preview de fusão (se stretch
   entregue — ver §2.5).

## 5. Não-metas (spec §5 — confirmadas)

Calibração automática (follow-up); desenhar curva LUT editável por gesto; usar
a tab como EDITOR de layout do overlay; Notch/gates (fase do Dolphin).

## 6. Commit e checkpoint

- Commit da fase: `feat(gamepad): calibração visual de stick — anti-deadzone e
  max output no StickTransform, history view RAW vs calibrado (spec
  2026-08-16-K7)`.
- Impl doc: este arquivo.
- Tabela §5 da retomada + §7 do master atualizadas (checkpoint idempotente) —
  FIM do roadmap: 8/8 ✅.
