# Impl doc — Spec 2026-08-16 B (remap visual estilo PPSSPP/DS4Windows)

**Data:** 2026-08-16
**Spec:** `docs/spec-2026-08-16-B-remap-visual-ppsspp.md` (executor: sub-agente autônomo)
**Resultado:** implementado, gate completo verde, commit `feat(gamepad): …` (ver §6 do
master roadmap). Verificação on-device pendente (protocolo humano na §5).

## 1. O que foi feito (por seção do spec)

### §1.1 `ControllerVisualLayout` — pura, JVM-testável

`app/src/main/java/app/gamenative/gamepad/mapping/ControllerVisualLayout.kt` (NOVO):

- `HotspotKind` (BUTTON_ROUND/STICK/TRIGGER/BUMPER/DPAD_DIR/SMALL) e `VisualHotspot`
  (control/cx/cy/r/kind, normalizados 0..1) — file:lines 17-47.
- `layoutFor(faceStyle)` — file:line 62. PS/XBOX/GENERIC compartilham a geometria
  canônica (só as LABELS mudam — regra do `GamepadButton`); NINTENDO tem geometria
  Switch Pro própria (stick esquerdo em cima, d-pad embaixo, faces em cima à direita,
  stick direito embaixo) — `canonicalLayout()` file:line 114, `nintendoLayout()`
  file:line 135. Corpo canônico 480×220 (`BODY_WIDTH/BODY_HEIGHT/BODY_ASPECT`,
  file:lines 51-54 — "16:9-ish" do spec).
- `hitTest(x, y, hotspots)` — file:line 73: mais próximo DENTRO do raio, null fora.
- `flashControlFor(input, deviceId)` — file:line 92: mapeamento PURO evento lógico →
  controle para o flash ao vivo (botão → próprio botão; eixos LEFT_X/Y/RIGHT_X/Y →
  sticks; LTRIGGER/RTRIGGER → gatilhos; deviceId errado → null). Bônus de testabilidade
  do §1.2, mantido no objeto puro.
- Invariante do spec §1.1 ("hotspots NÃO se sobrepõem; raio ≤ 45% da distância mínima
  entre centros") verificada por teste para TODOS os 17 controles de TODOS os FaceStyles
  (ver §3). Os layouts foram construídos para satisfazer a leitura GLOBAL da regra
  (todo raio ≤ 0.45 × menor distância centro-centro do layout): canônico d_min=0.1477
  (L1↔L2) vs raio máx 0.065; Nintendo d_min=0.145 (FACE_BOTTOM↔RIGHT_STICK) vs raio máx
  0.065.

### §1.2 `ControllerVisualView` — Compose, arquivo próprio

`app/src/main/java/app/gamenative/ui/component/remap/ControllerVisualView.kt` (NOVO):

- Canvas 100% vetorial, SEM assets: corpo = rounded rect (file:lines 158-176), sticks =
  2 círculos anel+base (file:line 209), botões de face = círculos com glyph-texto
  (file:line 215), d-pad = cruz com 4 braços desenhados do centro da cruz até a borda
  de cada direção (file:line 235), bumpers/gatilhos = pílulas arredondadas na borda
  superior (file:line 265), SELECT/START/GUIDE = pílulas pequenas com label
  (file:line 284). Cores 100% do `MaterialTheme.colorScheme` (dark/light).
- Estado por controle: `stateOf(control)` → AUTO (neutro + mini-badge "A",
  file:line 313) vs OVERRIDE (fill/borda accent, file:line 199-202); legenda
  AUTO/OVERRIDE abaixo do desenho (file:line 396).
- Flash ao vivo: o DIALOG mantém `State<Set<String>>` dos controles acesos (bus
  `GamepadInputEvent`, ver §1.5); a view deriva timestamps de entrada
  (file:lines 113-124) e decai o halo accent em ~600 ms via laço de frames
  (`FLASH_DURATION_MS = 600L`, file:line 73; `flashAlpha`, file:line 131; halo,
  file:line 207). Nada é capturado de composição antiga (lição C1 — holders vivos).
- Cada hotspot: `gamepadSelectable` (foco por gamepad, ring circular `CircleShape`,
  ativação A/DPAD_CENTER) + `onClick` de toque (file:lines 339-360); a11y
  `contentDescription` = glyph do controle. Toque = caixa quadrada 2r (mín. 20.dp)
  centrada no hotspot — aproximação retangular do círculo do hit-test puro (diferença
  < 0.41r, imperceptível em 20.dp).
- Parâmetros: `faceStyle`, `hotspots`, `stateOf`, `flash: State<Set<String>>`,
  `onHotspotTap` (spec) + `capturingControl`/`onCancelCapture`/`onRestoreControl`
  (chip flutuante §1.3 e faixa de contexto §1.4 — a view é o componente completo).

### §1.3 Captura (reuso do padrão do RadialMenuEditorDialog)

`GamepadRemapDialog.kt` file:lines 471-519: `DisposableEffect(visualCapture)` registra
handlers de `AndroidEvent.KeyEvent`/`MotionEvent` (bus CRU, mesmo caminho do
`RadialMenuEditorDialog.kt` file:lines 79-101) enquanto há captura visual; o primeiro
evento do `deviceId` do dialog vira `commitVisualBinding` (Key → `RawBinding.Key`;
eixo dominante → `RawBinding.Axis`). **Cancelamento:** `KEYCODE_BACK`, `KEYCODE_ESCAPE`
e **`BUTTON_B` (97 — o "B cruzeiro" do spec)** file:line 485; hardware back CANCELA a
captura em vez de fechar o dialog via `DialogProperties(dismissOnBackPress =
visualCapture == null)` file:line 558 (sem o consumo do Dialog, o BACK chega ao bus).
Chip flutuante "Pressione o botão para {glyph}… (B = cancelar)" sobre o desenho
(ControllerVisualView file:line 366). Mutuamente exclusiva com as capturas existentes:
todo início de captura antigo zera `visualCapture` (file:lines 644-647, 790, 853) e o
tap no hotspot zera `captureTarget`/`captureGyroActivate`/`captureLayerTrigger`
(file:lines 679-683); o escopo de foco e o `gamepadBackHandler` ficam OFF durante a
captura (file:lines 565, 572).

### §1.4 Escopo + restauração

- Seletor segmented "Este jogo" / "Todos os jogos" no topo da seção
  (`VisualScopeChip`, file:line 1718; `VisualScope.GAME/DEVICE`, file:line 1601).
  Escreve na camada DEFAULT do override do appId atual (`gameLayers`, salvo via
  `hub.saveGameProfile`) ou no global do device (`layers` existente — comportamento
  atual). O `merged` existente resolve a precedência JOGO > GLOBAL > AUTO sem mudanças.
- `appId` = `hub.activeAppId` lido ao abrir o dialog (file:line 147); fora de jogo o
  chip "Este jogo" fica desabilitado com hint (`gamepad_visual_scope_game_unavailable`).
  NOTA (documentada): `activeAppId` é o holder vivo que o hub já usa em TODO o hot
  path; ele persiste após sair do jogo (sem reset — comportamento existente), então o
  escopo "Este jogo" no Settings vale para o último jogo executado.
- Restaurar automático POR CONTROLE (`restoreVisualControl`, file:line 353): limpa o
  binding do controle na DEFAULT de ONDE ele vier (device + jogo) — de volta ao AUTO
  do mapping. Alcançável por toque e por gamepad na faixa de contexto sob o desenho
  (ControllerVisualView file:line 435).
- Restaurar automático GERAL (`restoreVisualScope`, file:line 368): limpa todos os
  bindings do escopo selecionado (device → `layers = emptyMap()`, mesma semântica do
  botão Reset existente; jogo → `gameLayers = emptyMap()`).
- Indicador de herança v1: badge AUTO/OVERRIDE por controle (granular
  GLOBAL/JOGO = follow-up, fora de escopo).

### §1.5 Integração híbrida no GamepadRemapDialog

- Seção colapsável "Mapa visual" (`VisualRemapSection`, file:line 1614) como PRIMEIRO
  item do conteúdo do tab CONTROLLER (file:line 669), com header gamepad-navegável
  (chevron ExpandLess/ExpandMore + "Restaurar tudo"); EMBAIXO, a lista avançada
  existente (camadas/gyro/flick/LUT) permanece INTACTA — nenhuma linha existente foi
  removida (diff = só inserção de blocos novos + 4 linhas de exclusão mútua de
  captura). O seletor de camadas fica fixado ACIMA da área rolável (chrome contextual
  pré-existente) — decisão de posicionamento documentada: a seção visual é o topo do
  CONTEÚDO do tab.
- Save: além do `onSave(editorProfile())` existente, o override do escopo JOGO é
  persistido na chave appId do `gameStore` via `hub.saveGameProfile` (file:lines
  1009-1020) — idempotente (perfil default remove a entrada; conteúdo igual re-salva
  igual, política V1 do store preserva chaves desconhecidas).
- Conflitos: `commitVisualBinding` (file:line 330) valida contra o DEFAULT EFETIVO
  (device+jogo) com `GamepadBindingCodec.conflicts` e bloqueia com o status de conflito
  existente.

## 2. Arquivos afetados

| Arquivo | Mudança |
|---|---|
| `gamepad/mapping/ControllerVisualLayout.kt` | NOVO — hotspots + hit-test + flash puros (§1.1) |
| `ui/component/remap/ControllerVisualView.kt` | NOVO — desenho vetorial + flash + seleção (§1.2/§1.3) |
| `gamepad/remap/GamepadRemapDialog.kt` | seção "Mapa visual" + escopo + restaurar + captura + flash (§1.3-§1.5) |
| `gamepad/GamepadHub.kt` | NOVOS métodos `gameProfileFor(appId)` (line 284) e `saveGameProfile(appId, profile)` (line 290) — enablers do escopo JOGO; nenhum método existente alterado |
| `res/values/strings.xml` + `values-pt-rBR/strings.xml` | 14 chaves novas `gamepad_visual_*` (captura, escopo, restaurar, badges AUTO/OVERRIDE) em EN + pt-rBR |
| `app/src/test/.../ControllerVisualLayoutTest.kt` | NOVO — 12 testes de hit-test/invariantes/flash (gate) |

## 3. Verificação (gate — passou ANTES do commit)

```
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:testModernDebugUnitTest --tests "*ControllerVisual*" --tests "*Gamepad*"
→ BUILD SUCCESSFUL (ControllerVisualLayoutTest: 12 testes, 0 falhas)
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:assembleModernDebug
→ BUILD SUCCESSFUL
```

Cobertura do teste (`ControllerVisualLayoutTest.kt`, file:line 18):

1. `layout covers every control exactly once for every face style` — os 17
   `GamepadButton` em cada um dos 4 FaceStyles.
2. `hit test at each hotspot center returns that control for every face style`.
3. `hit test rejects a point just outside the radius`.
4. `hit test outside the drawing is null for every face style` — fora do desenho = null.
5. `no two hotspot circles overlap in any face style` — d > rA+rB para todo par.
6. `every radius respects 45 percent of the nearest center distance` — invariante do
   spec §1.1 por hotspot.
7. `grid scan never hits two hotspots at a single point` — varredura 51×51 pontos ×
   4 estilos: 1 ponto NUNCA acerta 2 hotspots.
8. `all hotspots fit inside the normalized drawing box`.
9. `nintendo layout is the switch geometry…` — geometrias por estilo.
10-12. `flash control …` — mapeamento puro do flash (botões, eixos, deviceId, não-inputs).

Render de sanidade: as duas geometrias foram desenhadas em ASCII com a MESMA matemática
do Canvas (braços do d-pad, pílulas, círculos) e conferem visualmente (cruz à esquerda/
sticks simétricos no canônico; diagonais no Switch) — sem overlaps e sem sangria fora
do canvas (gatilhos com halfW ≤ cx).

## 4. Desvios e decisões (com justificativa)

1. **Chaves dos controles = `GamepadButton.name`** (não "L1"/"L2" do comentário do
   spec): é o mesmo vocabulário do campo `layers` do perfil — o bridge visual→perfil
   fica trivial e consistente com o store; o spec usa "L1" só como abreviatura no
   exemplo.
2. **Geometria NINTENDO própria** (Switch Pro) e PS/XBOX/GENERIC canônicas idênticas:
   o spec pede `layoutFor(faceStyle)` por estilo; posição física não muda entre
   PS/XBOX/GENERIC (regra do `FaceStyle` — só labels).
3. **Cancelamento da captura = BACK + ESCAPE + BUTTON_B(97)** ("B cruzeiro"): B de
   gamepad, nunca o raw B do Xbox que é o botão de CANCELAR do menu — na captura
   visual ele cancela por spec. Efeito: o B não pode ser capturado como binding no
   mapa visual (a lista avançada continua capturando B normalmente — nada existente
   muda).
4. **Escopo governa só a seção visual**; a lista avançada continua editando o perfil
   do DEVICE (comportamento existente). A precedência efetiva (JOGO > GLOBAL > AUTO)
   é resolvida pelo `merged` existente no hot path.
5. **Restaurar por controle limpa o binding de onde ele vier** (device E jogo): "de
   volta ao automático" exige limpar a fonte que fornece o override — com badges v1
   (sem herança granular) não dá para apontar uma fonte só.
6. **Restaurar geral limpa `layers` do escopo** (mesma semântica do botão Reset
   existente; `layerTriggers` intacto, como o Reset). É a leitura literal de "limpa
   todos os bindings".
7. **Posicionamento do seletor de camadas**: fica fixado acima da área rolável
   (chrome contextual pré-existente); "Mapa visual" é o TOPO do conteúdo rolável do
   tab. Nada existente foi movido/removido.
8. **Toque = caixa quadrada 2r por hotspot** (≈ círculo do hit-test puro): o
   `gamepadSelectable` padrão do repo usa `clickable` sem shape de recorte; 0.41r de
   diferença nos cantos é imperceptível em alvos de ~20.dp.
9. **`appId` do escopo = `hub.activeAppId`** (holder vivo existente): sem jogo ativo o
   chip "Este jogo" é desabilitado com hint. Como o hub nunca reseta `activeAppId`
   (comportamento pré-existente), após sair de um jogo o escopo vale para o último
   jogo — consistente com todo o resto do hot path, que lê o mesmo holder.
10. **Flash como observador**: o listener retorna `false` (nunca consome input) e é
    registrado UMA vez com `rememberUpdatedState` do deviceId (lição C1 explícita).
11. **Novos métodos no `GamepadHub`** (`gameProfileFor`/`saveGameProfile`): enablers
    mínimos e aditivos do escopo JOGO — nenhum método existente alterado; o arquivo
    não é hotspot de nenhuma fase vizinha no master roadmap §5.

## 5. Verificação on-device (humano — "on-device pendente", protocolo do spec §3)

1. Abrir remap (Settings → Gamepad → "Remap controls") com o DS4 conectado.
2. Visual renderiza com o FaceStyle do device (labels ✕○□△).
3. Tap em ✕ → chip "Pressione o botão para ✕… (B = cancelar)" → apertar R1 → override
   salvo (✕ fica accent; badge "A" some).
4. Flash: apertar botões físicos acende os hotspots ~600 ms com decaimento.
5. Escopo "Este jogo" (com um jogo executado antes — ver desvio 9) vs "Todos os jogos":
   salvar e relançar o jogo — o jogo respeita o remap do escopo escolhido.
6. "Restaurar automático" por controle e "Restaurar tudo" por escopo.
7. B/BACK cancela a captura sem fechar o dialog.
