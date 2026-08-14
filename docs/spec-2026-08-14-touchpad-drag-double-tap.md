# Touchpad: arrasto, duplo-toque = clique direito e thresholds calibrados (P2-6)

**Data:** 2026-08-14
**Origem:** pendência P2-6 do `spec-2026-08-14-gamepad-upgrades-pendencias.md`
(follow-up do U2, `spec-2026-08-14-gamepad-u2-touchpad-mouse.md`).
**Natureza:** spec próprio do follow-up — máquina de estados do TouchpadProcessor
(arresto + duplo-toque), thresholds calibrados pela prática e dead zone de pós-toque.
**Referências lidas (comportamento, não código):** moonlight-android
`RelativeTouchContext.java:87-90` / `AbsoluteTouchContext.java:60-67` — thresholds de
anos de streaming: tap ≤ 250 ms com ≤ 20–25 px; duplo-toque 250 ms/60 px; arrasto =
toque segurado ≥ 650 ms (`LONG_PRESS_TIME_THRESHOLD`) vira clique+arrasto contínuo;
dead zone de pós-toque 100 ms/20 px (`TOUCH_DOWN_DEAD_ZONE_*`).

## §1.1 — Máquina de estados (TouchpadProcessor, puro)

Estados: `idle` → `tapCandidate` → `dragging`. Transições:

| Evento | idle | tapCandidate | dragging |
|---|---|---|---|
| finger-down | âncora + `downAt` → tapCandidate (rejeitado se dentro da dead zone de pós-toque) | — (dedo já estava) | — |
| move | — | delta de mouse; se `now - downAt >= dragThresholdMs` (650 ms) → **dragPress** (BUTTON_LEFT down) + delta → dragging | delta de mouse (com botão segurado) |
| finger-up | — | tap curto e parado → **tap** (esquerdo) ou **duplo-toque → rightClick** (opt-in); senão nada; → idle | **dragRelease** (BUTTON_LEFT up) → idle |

- Tap: down→up ≤ 250 ms com deslocamento total ≤ `tapMoveDeadzone` (0.08 → **0.03**
  normalizado — equivalente aos 20–25 px do moonlight no touchpad do DS4 ~1000 px).
- Duplo-toque: 2 taps dentro de 250 ms/60 px-equivalente (0.06 normalizado) →
  BUTTON_RIGHT (gesto "botão direito" do moonlight). **Opt-in por perfil**
  (`touchpadDoubleTapRightClick` no GamepadProfile; default OFF = 2 cliques, o
  comportamento atual do U2). Após rightClick, o último tap é limpo (triplo não
  encadeia).
- Arrasto: finger-down ≥ 650 ms sem soltar (movimentação qualquer) → BUTTON_LEFT
  pressionado continuamente + deltas; finger-up → release. É o gesto que falta e o
  que mais dói em jogos (arrastar itens/objetos).
- Dead zone de pós-toque: 100 ms após um finger-up — downs dentro dela são
  REJEITADOS (bounce do touchpad gasto; o mesmo público do gate de ghost input,
  spec 2026-08-13-controller-touchpad-ghost-input).

## §1.2 — Plano de injeção

- `TouchpadDecision` ganha `dragPress`/`dragRelease`/`rightClick`.
- `GamepadTouchpadForwarder.TouchpadMouseSink` ganha `pressLeft()`/`releaseLeft()`/
  `rightClick()`; `XServerTouchpadMouseSink` injeta via `injectPointerButtonPress/
  Release` (BUTTON_LEFT/BUTTON_RIGHT) — tudo no ponto do gate (V7).
- O forwarder monta o `TouchpadConfig` com `doubleTapRightClick` do PERFIL efetivo
  do device (`profileFor(deviceId, activeAppId)`) — pref por perfil (especificação).
- Novo campo `touchpadDoubleTapRightClick: Boolean?` no GamepadProfile (null =
  default OFF), com `isDefault`, merge granular e preservação de chaves (V1).
- UI: switch "Double-tap = right click" na seção do remap (per-device).

## §1.3 — Aceite

1. Testes JVM da máquina de estados (tap / arrasto / duplo / direito / bounce).
2. Thresholds: tap com 0.03; duplo 250 ms/0.06; arrasto 650 ms; bounce 100 ms.
3. On-device (bateria §[H]): arrastar item no Silksong com o touchpad do DS4;
   duplo-toque abre menu de contexto; OFF = byte-identical (V10).

## §1.4 — Decisões registradas

- O arrasto ENGATA mesmo sem movimento (só o tempo segurando importa) — padrão
  moonlight (long-press vira drag).
- Duplo-toque default OFF (comportamento U2 preservado — 2 cliques).
- O bounce de pós-toque rejeita o DOWN inteiro (o toque fantasma não move o cursor).
- Thresholds em NORMALIZADO (padrão do processador); a conversão px→normalizado
  usa o percurso do touchpad do DS4 (~1000 px) como referência.
