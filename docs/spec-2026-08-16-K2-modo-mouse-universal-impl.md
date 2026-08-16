# Impl doc — Spec 2026-08-16 K2 (modo mouse universal por stick)

**Data:** 2026-08-16
**Spec:** `docs/spec-2026-08-16-K2-modo-mouse-universal.md` (fase K2 da retomada
universal input — depois de K6; commit de fronteira feito)
**Resultado:** implementado; gate completo verde (tests `*MouseMode* *Gamepad*`
+ `assembleModernDebug`); commit `feat(gamepad): …` na §6. Verificação
on-device pendente (spec §4 — humano).

## 1. O que foi feito (por seção do spec)

### §1.1 `MouseModeProcessor` — puro (NOVO)

`gamepad/processing/MouseModeProcessor.kt` (NOVO, 246 linhas, zero android.*):

- `MouseModeState` (file:line 34) — `startDownAtMs`/`armed`/`active` +
  `lastReportAtMs` (gate 50 ms) + `lastScrollAtMs` (anti-repeat 120 ms) +
  `dpadHeld` + `lastStickX/Y` (o hub atualiza antes do `onStick` — os eixos
  chegam em eventos separados) + `pixelState: GyroMouseState` (reuso do
  acumulador G1 do gyro).
- `MouseModeProcessor` (file:line 82):
  - `onKey` (file:line 97): START down arma (`startDownAtMs`), o cruzamento do
    limiar é observado em QUALQUER evento (`armIfCrossed`, file:line 229) e o
    flip acontece no KEY-UP confirmado (moonlight `START_DOWN_TIME_MOUSE_MODE_MS`
    :60 / :2371-2375) → `Activated`/`Deactivated`. Release antes do limiar →
    `None` (START segue o pipeline). Enquanto ativo: A→`MouseButton(left)`,
    B→`MouseButton(right)`, DPAD_UP/DOWN→`MouseScroll(±1)` na borda de down +
    `dpadHeld` para o repeat; DPAD_LEFT/RIGHT consumidos sem scroll (o sink não
    expõe hscroll — não-meta). Botões fora do vocabulário → `None` (seguem).
  - `onScrollRepeat` (file:line 149): repetição do scroll com a janela de
    120 ms (`SCROLL_REPEAT_MS`, file:line 89 — a MESMA do `GamepadMoveDedupe`).
    O Android repete o KeyEvent do dpad segurado; o tradutor descarta repeats,
    então o hub alimenta este método com os repeats CRUS.
  - `onStick` (file:line 169): rampa quadrática do moonlight
    (`convertRawStickAxisToPixelMovement` :1837) em px/s — `base + gain·mag²`,
    com gain default 80 px/s = 4 px/report (50 ms) na borda (derivação no
    `MouseModeSpeed` KDoc, file:line 59). **Gate de 50 ms por timestamp SEM
    timer** (o flush do hub roda por evento; sticks ~60 Hz — o gate basta);
    **dt FIXO de 50 ms** (não o dt real — o primeiro movimento após o stick
    parado nunca salta, mesmo comportamento do `postDelayed` do moonlight).
    Sub-pixel via `GyroPixelAccumulator` (padrão G1) — movimento lento nunca
    congela.

### §1.2 Sink `scroll`

`gamepad/GamepadTouchpadForwarder.kt`:

- Interface `TouchpadMouseSink` ganha `fun scroll(verticalSteps: Int)`
  (file:line 47); `NoopSink` no-op (file:line 154) — testes existentes intactos.
- `XServerTouchpadMouseSink.scroll` (file:line 206): o XServer do winlator só
  expõe botões de roda (`Pointer.Button.BUTTON_SCROLL_UP/DOWN` —
  `Pointer.java:9`), então cada passo é press+release (1 detent por passo; o
  repeat é a janela de 120 ms no hub).

### §1.3 Integração no hub (post-remap, pré-emissão)

`gamepad/GamepadHub.kt`:

- `mouseModeStates` por device (file:line 1197, V6 — morto no removeDevice,
  file:line ~243) + `overlayOpen` (file:line 1206, holder vivo escrito pelo
  XServerScreen na composição — lição C1).
- `routeMouseMode` (file:line 1348) — hook NO FLUSH do `onKey` (file:line 1328)
  e do `onAxis` (file:line ~1478): DEPOIS do pipeline lógico (tradução + remap +
  triggers de camada + chords), ANTES da emissão. Consome A/B (pressLeft/
  releaseLeft/rightClick), dpad (scroll) e o stick ESQUERDO (cursor) enquanto
  ativo; START arma/flipa e CONTINUA emitindo ("volta a ser START"); o stick
  direito/triggers passam (o modo usa só o esquerdo — decisão do spec §1.1).
  Suspenso com [overlayOpen]; perfil `mouseModeEnabled != true` → caminho
  byte-identical (sem estado criado).
- `onScrollRepeat`: repeats crus do dpad no `onKey` (file:line 1292) — o único
  canal de repetição (o tradutor descarta repeats).
- `onMouseModeToggle` (file:line 1411) — haptic curto
  (`GamepadHaptics.rumbleDevice(0.4, 0.4, 80 ms)` — o `vibrateDevice` atual exige
  Context, não é acessível no hub) + log `gncontrol` (padrão moonlight "OSD
  toast" adaptado a haptics).
- `mouseModeActive(deviceId)` (file:line 1417) — consulta pública para o
  `PhysicalControllerHandler` consumir os crus no JOGO.
- `deviceProfileFor` (file:line 369) — perfil BRUTO do device (sem merge) para a
  UI do card editar SÓ o campo dela (nunca congela o merge no save).

`ui/screen/xserver/XServerScreen.kt`:

- Espelho do contexto de overlay no hub (file:line 1604): UMA linha na
  composição (`PluviaApp.gamepadHub.overlayOpen = ...`) — ZERO locals novas na
  função principal (regra dex do master §2); holder vivo lido no momento do
  evento (C1). Com QuickMenu/radial/remap abertos o modo fica SUSPENSO (o dpad
  navega o menu) e `active` persiste.

`ui/screen/xserver/PhysicalControllerHandler.kt`:

- `MOUSE_MODE_CONSUMED_KEYS` (file:line 172) + gate no `onKeyEvent` (file:line
  194): com o modo ATIVO, A/B/dpad crus são consumidos ANTES do remap U4 —
  não chegam ao jogo (o handler só roda com overlay fechado por construção).
  START passa (toggle + "volta a ser START", moonlight também envia PLAY).

### §1.4 Perfil + UI

`gamepad/profiles/GamepadProfile.kt` — 4 campos null-default (file:lines
99-102) + `isDefault()` atualizado (file:lines 160-163). Política V1 do store.

UI:

- `gamepad/remap/GamepadRemapDialog.kt` — seção "Modo mouse" (file:lines
  1405-1458): switch (com `gamepadSelectable`) + sliders de base/gain
  (reuso do `GyroSliderRow`); estado local (file:lines 175-177) + `editorProfile`
  (file:lines 250-255 — defaults colapsam em null) + `applyImportedProfile`
  (file:lines 294-296).
- `ui/screen/settings/DeviceDiagnosticsCard.kt` — toggle rápido no card
  (file:lines 382-397): edita o perfil BRUTO via `deviceProfileFor` +
  `saveDeviceProfile` (o ON/OFF reflete o perfil EFETIVO do device).
- `res/values*/strings.xml` — 9 chaves EN + pt-rBR (file:lines 2539+).

### Testes

`MouseModeProcessorTest.kt` (NOVO, 16 testes):

- Toggle: START curto não ativa; 750 ms + release ativa; segundo toggle
  desativa; crossing observado por evento intermediário; desativar limpa
  dpad/sub-pixel.
- Cliques: A down/up → press/release left; B → right; A fora do modo → None.
- Scroll: borda UP/DOWN, repeat com janela de 120 ms (antes → None, depois →
  +1), LEFT/RIGHT consumidos sem scroll, release limpa o held.
- Rampa: mag=1 → 4 px/report; mag=0.5 → 0.5 px/report (acumula 1 em 2
  reports); base/gain configuráveis; gate de 50 ms; primeiro movimento após
  parado não salta; sub-pixel acumula; stick centrado/desativado → null.

## 2. Decisões de design (desvios aceitos)

1. **dt fixo de 50 ms** no `onStick` (não o dt real entre eventos): sem timer
   (spec §1.1.3), o primeiro movimento após o stick parado usaria um dt enorme
   e saltaria o cursor; o período fixo reproduz o postDelayed do moonlight
   (spec §4.1 espera "centro lento, borda rápida" — não "salto após pausa").
2. **Haptic via `rumbleDevice`** (não `vibrateDevice`): a assinatura atual do
   `vibrateDevice` exige Context — o hub é app-scoped sem Context próprio;
   `rumbleDevice` é o mesmo contrato do card de diagnóstico.
3. **Só o stick ESQUERDO** move o cursor (spec §1.1: "o stick" — o moonlight usa
   os dois/configurável; configurar é follow-up declarado do spec §5). O stick
   direito continua chegando ao jogo enquanto ativo.
4. **B = `rightClick()` no down** (o sink não expõe pressRight/releaseRight —
   spec §1.2 só pede `scroll`; arrasto com botão direito é follow-up).
5. **`routeMouseMode` roda também no `onAxis`** (o spec §1.3 menciona
   `remapEvent/flush` — o flush de eixos é o `onAxis`; o stick lógico nasce
   lá).
6. **Overlay suspende via holder `overlayOpen` no hub** (o spec cita
   `OverlayInputState` — que é local do XServerScreen; o espelho booleano no
   hub é o canal, escrito na mesma composição, lido no momento do evento).

## 3. Verificação (gate)

```
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:testModernDebugUnitTest   --tests "*MouseMode*" --tests "*Gamepad*" --offline
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:assembleModernDebug --offline
```

- Resultado: VERDE (tests = 123, 0 falhas, 0 erros — filtros `*MouseMode* *Gamepad*`; assemble OK em 4 m 30 s) — §6.

## 4. On-device (humano — spec §4, "on-device pendente")

1. Silksong: segurar START 750 ms → haptic → stick move o cursor (rampa
   percebida: centro lento, borda rápida); A/B clicam; dpad rola.
2. Repetir o chord → volta ao controle normal; START curto NUNCA vira toggle.
3. Com QuickMenu aberto, dpad navega o menu (suspensão); fechar → modo volta.
4. Jogo KB/M-only (point-and-click via Wine): modo mouse torna o jogo jogável
   sem touch. Evidência: vídeo curto.

## 5. Não-metas (spec §5 — confirmadas)

Configurar o botão/chord do toggle (v1 fixo em START — UI só expõe
enable/base/gain); velocidade por jogo; mouse absoluto (touch cobre); gyro→mouse
(G1 cobre — os dois COMPÕEM: gyro MOUSE + modo mouse somam no cursor).

## 6. Commit e checkpoint

- Commit da fase: `feat(gamepad): modo mouse universal por stick — toggle por
  hold de START, rampa quadrática, cliques/scroll, suspensão por overlay
  (spec 2026-08-16-K2)`.
- Impl doc: este arquivo.
- Tabela §5 da retomada + §7 do master atualizadas (checkpoint idempotente).
