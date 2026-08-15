# Spec 2026-08-14 — U6: OK/Cancel + atalhos lógicos + deadzones na LibraryScreen

**Data:** 2026-08-14
**Origem:** doc de intuito (spec 2026-08-14-gamepad-intuito-validacao-upgrades.md, U6) —
a LibraryScreen (janela normal, fora do jogo) é a ÚLTIMA superfície que ignora a camada
universal: confirm no default posicional, atalhos em raw keycodes e thresholds hardcoded.
**Decisão (per U6(c) do intuito):** manter Compose focus + helpers do hub
(`confirmKeyCodeFor`/`menuDeadzoneFor`) — NÃO consumir `GamepadInputEvent` nesta tela.
O mecanismo de bootstrap de foco (retries + fallback) é preservado como está.
**Natureza:** implementação; independente do gate universal (byte-identical com gate OFF —
o hub só devolve dados; nenhum comportamento muda para device desconhecido).

---

## 0. Estado atual (auditado no intuito r2)

| Ponto | Hoje | Problema |
|---|---|---|
| Confirm | `gamepadSelectable` ativa em BUTTON_A/DPAD_CENTER/ENTER posicional | Nintendo (confirm=FACE_RIGHT=BUTTON_B) e swap OK/Cancel ficam errados |
| Cancel/back | raw `KEYCODE_BUTTON_B` (LibraryScreen.kt:922-942) | com swap ou Nintendo, o botão de cancelar é OUTRO |
| Atalhos | raw L1/R1/SELECT/START/Y/X (LibraryScreen.kt:835-944) | sem tradução lógica (mapping por vendor/product ignorado) |
| Thresholds bootstrap | `0.5f` (hat) e `0.6f` (stick), LibraryScreen.kt:781-784 | ignoram `hub.menuDeadzoneFor` (perfil ?: global 0.45) |
| Glyphs | `GamepadActionBar` já segue FaceStyle do device ativo | os AÇÕES são fixos (A=select, B=back) — com swap/confirm≠A o glyph mostra o botão ERRADO |

## 1. Design

### 1.1 Helper puro novo: `LibraryGamepadKeys` (JVM-testável)

`gamepad/LibraryGamepadKeys.kt` — `object` puro que resolve o conjunto de keycodes
lógicos de uma superfície de biblioteca a partir do `GamepadMapping` + swap:

```kotlin
data class LibraryKeySet(
    val confirmKey: Int,   // keycode que CONFIRMA (FaceStyle + swap)
    val cancelKey: Int,    // keycode que CANCELA (o OUTRO face button)
    val yKey: Int, val xKey: Int,       // FACE_TOP / FACE_LEFT
    val l1Key: Int, val r1Key: Int,     // SHOULDER_LEFT / SHOULDER_RIGHT
    val selectKey: Int, val startKey: Int,
)
fun resolve(mapping: GamepadMapping?, swapOkCancel: Boolean): LibraryKeySet
```

- Sem mapping (device desconhecido — teclado, mouse, hub sem entrada): fallback nos
  raw keycodes atuais → **byte-identical** (regra V10).
- Com mapping: keycode = binding de tecla do botão lógico (`mapping.buttons[...]` como
  `RawBinding.Key`); botão sem binding de tecla → fallback raw do mesmo botão.
- `cancelKey` = `mapping.confirmButton(swap)` → o outro face button
  (FACE_BOTTOM↔FACE_RIGHT). Para Xbox/PS/Generic: cancel=B; Nintendo: cancel=A;
  swap inverte ambos.
- `GamepadMapping` ganha `cancelButton(swapOkCancel): GamepadButton` (o outro face button).

### 1.2 Hub: expor o conjunto por device

`GamepadHub` ganha `fun libraryKeySetFor(deviceId: Int): LibraryKeySet?` —
null quando o device não existe (superfícies caem no fallback puro);
resolve `mappingFor(device)` + `profile.swapOkCancel ?: PrefManager.gamepadSwapOkCancel`
e delega ao objeto puro. Hot path irrelevante (chamado por evento de tecla de UI,
fora do dispatch do jogo).

### 1.3 LibraryScreen: confirm/cancel lógicos + atalhos

No `onPreviewKeyEvent` do root (LibraryScreen.kt:820-949), ANTES do `when` atual:

1. `val keySet = hub.libraryKeySetFor(deviceId)` — deviceId do evento.
2. **Confirm:** se `keyCode == keySet.confirmKey`:
   - `confirmKey == BUTTON_A || confirmKey == DPAD_CENTER` → `false` (fluxo atual: o
     `gamepadSelectable` da linha ativa; DPAD_CENTER continua OK universal).
   - senão → no primeiro DOWN (repeat 0): `view.dispatchKeyEvent(DPAD_CENTER down/up)`
     (mesmo padrão do `GamepadKeyBridge` — Compose só ativa em Enter/DPAD_CENTER) e
     consome. UP também consumido.
3. **Swallow posicional:** se `keyCode == KEYCODE_BUTTON_A` e `confirmKey != BUTTON_A`
   → consumir (o A posicional NÃO é o confirm deste device; sem isso o
   `gamepadSelectable` ativaria no botão errado).
4. **Cancel/back:** o branch de B (linhas 922-942) passa a comparar `keySet.cancelKey`
   (fallback BUTTON_B). Ações idênticas às atuais.
5. **Atalhos:** L1/R1/SELECT/START/Y/X comparam `keySet.l1Key/r1Key/selectKey/
   startKey/yKey/xKey` (fallback raw = valores atuais). Mesmas condições de estado.
6. **Bootstrap de foco** (L2/R2/THUMB/DPAD + motion): intocado; só os THRESHOLDS do
   `onGlobalMotionEvent` mudam (1.4).

### 1.4 Thresholds do bootstrap (781-784)

`onGlobalMotionEvent` passa a usar `hub.menuDeadzoneFor(event.deviceId)` para o stick
(hat mantém 0.5 — o hat não é afetado por deadzone de stick; o doc U6 cita só os
thresholds de stick como hardcoded; hat 0.5 já é o padrão do repo em todos os
navigators). Device sem hub → global `gamepadMenuStickDeadzone` (comportamento atual).

### 1.5 ActionBar com confirm/cancel corretos

`LibraryScreen.kt:1150-1199`: os actions `select`/`back`/`menu` passam a usar o botão
POSICIONAL certo do device ativo:
- `confirmBtn` = A normalmente; B quando o confirm lógico do device ativo é
  FACE_RIGHT (Nintendo ou swap) — derivado de `hub.activeDevice` + FaceStyle + swap.
- `cancelBtn` = o oposto.
O `GamepadActionBar` já renderiza glyph por FaceStyle — o glyph agora bate com o botão
real. Guard de preview (`LocalInspectionMode`) → A/B (padrão atual).

### 1.6 Strings

Sem strings novas (ações reutilizam `action_select`/`back`/`menu`/`options`/`search`/
`action_add_game`).

## 2. Arquivos afetados

| Arquivo | Mudança |
|---|---|
| `gamepad/LibraryGamepadKeys.kt` (novo) | objeto puro + `LibraryKeySet` (1.1) |
| `gamepad/mapping/GamepadMapping.kt` | `cancelButton(swap)` (1.1) |
| `gamepad/GamepadHub.kt` | `libraryKeySetFor(deviceId)` (1.2) |
| `ui/screen/library/LibraryScreen.kt` | confirm/cancel/atalhos lógicos + thresholds (1.3, 1.4, 1.5) |
| Testes: `gamepad/LibraryGamepadKeysTest.kt` (novo) | casos: Xbox, PS, Nintendo, swap on/off, sem mapping (fallback), botão sem binding de tecla |
| `GamepadMappingTest.kt` | + casos de `cancelButton` |

## 3. Verificação

### 3.1 JVM
- Suíte filtrada (`*Gamepad*` + `*Shader*` + `*SearchField*`).
- Novos: `LibraryGamepadKeysTest` (Xbox → confirm=A/cancel=B; Nintendo → confirm=B/
  cancel=A; swap inverte; sem mapping → fallbacks raw; binding de eixo → fallback raw).

### 3.2 On-device (pendente — sem dispositivo; cenários no `quickmenu-verify.sh`)
- DS4: A confirma, B cancela (sem mudança); swap ON na UI → B confirma e A cancela na
  biblioteca; glyphs acompanham.
- Nintendo Pro: botão da direita (B posicional) confirma; botão de baixo (A posicional)
  cancela; Y/X/L1/R1/SELECT/START funcionam.
- Thresholds: deadzone do menu 0.45 → stick 0.40 não move foco; 0.50 move.
- Bootstrap de foco intacto (retries + fallback) — T1-T9 da bateria.

## 4. Fora de escopo
- `LibraryAppScreen` (tela de detalhe — mesmo padrão, spec futuro).
- Consumo de `GamepadInputEvent` na biblioteca (decisão 2026-08-14: manter focus).
- Remap no jogo (U4), layers (U3) — specs próprios.
