# Spec 2026-08-16 B — Remap visual estilo PPSSPP/DS4Windows (mock clicável + captura + AUTO)

**Data:** 2026-08-16
**Origem:** relato do usuário — a tela de remap atual (lista) não é intuitiva.
Referências verificadas nos clones locais: PPSSPP `reference/ppsspp/UI/
ControlMappingScreen.cpp` (mock clicável + flash ao vivo, linhas 660–800 —
`MockButton.NotifyPressed`), DS4Windows (regiões clicáveis na imagem do
controle). O Dolphin ATUAL é lista (`AdvancedMappingDialog.kt`) — não copiar.
**Executor:** sub-agente autônomo. Leia `AGENTS.md` + regras globais do master
roadmap §2. Spec autocontido. DEPENDÊNCIA: nenhuma (primeira fase de UI nova).

## 0. Estado atual (o que existe e é REUSADO, não reescrito)

- `gamepad/remap/GamepadRemapDialog.kt`: dialog por device com seções (camadas,
  gyro, stick/flick/fusão, LUT c/ SAF), `editorProfile()` com política
  null-collapse (default = REMOVE a entrada), export/import de perfil.
- Perfis em 3 camadas: AUTO → global do device → override do JOGO (appId);
  `GamepadProfileStore.merged()` resolve por null-coalescência; cache
  `GamepadHub.profileFor(deviceId, appId)`.
- Mapeamento automático: `MappingDatabase` (modelos populares) → `SdlControllerDb`
  (asset pinado) → `defaultAndroidMapping(faceStyle)`. `FaceStyle`:
  PLAYSTATION/XBOX/NINTENDO/GENERIC (por VID/PID).
- Captura de input por bus: padrão do `RadialMenuEditorDialog.kt` (espera o
  próximo evento cru do deviceId via `PluviaApp.events`).
- Linguagem de foco: `gamepadSelectable`/`gamepadFocus` (spec focus-feedback-v2).

## 1. Design

### 1.1 `ControllerVisualLayout` — PURA (JVM-testável)

Novo `gamepad/mapping/ControllerVisualLayout.kt`:
```kotlin
data class VisualHotspot(
    val control: String,      // chave lógica: "FACE_BOTTOM","LEFT_STICK","L1","DPAD_UP","LEFT_TRIGGER",...
    val cx: Float, val cy: Float, val r: Float,   // normalizados 0..1 (bounding box do desenho)
    val kind: HotspotKind,    // BUTTON_ROUND, STICK, TRIGGER, BUMPER, DPAD_DIR, SMALL
)
object ControllerVisualLayout {
    fun layoutFor(faceStyle: FaceStyle): List<VisualHotspot>   // PS/XBOX/NINTENDO/GENERIC
    fun hitTest(x: Float, y: Float, hotspots: List<VisualHotspot>): VisualHotspot?  // mais próximo dentro do raio; null fora
}
```
- Controles cobertos (todos os layouts): FACE_BOTTOM/RIGHT/LEFT/TOP,
  DPAD_UP/DOWN/LEFT/RIGHT, L1/R1, L2/R2 (triggers), LEFT_STICK/RIGHT_STICK,
  SELECT, START, GUIDE. Labels de face por FaceStyle (✕○□△ / ABXY / BAYX).
- Coordenadas: desenho-canônico 16:9-ish (corpo ~480×220); hotspots NÃO se
  sobrepõem (raio ≤ 45% da distância mínima entre centros).

### 1.2 `ControllerVisualView` — Compose (arquivo próprio)

Novo `ui/component/remap/ControllerVisualView.kt`:
- Canvas vetorial (SEM assets PNG): corpo = rounded rect; sticks = 2 círculos
  (externo + base); botões de face = 4 círculos com glyph-texto; dpad = cruz;
  bumpers/triggers = arcos/trapézios nas bordas superiores. Cores do tema
  (`MaterialTheme`), dark/light.
- Estado por controle: **AUTO** (neutro + mini-badge "A") vs **OVERRIDE**
  (accent). Derivado do perfil EFETIVO (`merged`) — campo null = AUTO.
- **Flash ao vivo**: listener bus `GamepadInputEvent` (Button/Axis do deviceId) →
  hotspot acende ~600 ms com decaimento (holders vivos, lição C1 —
  `rememberUpdatedState` nos callbacks).
- Cada hotspot: `gamepadSelectable` (foco por gamepad) + `onClick` (touch).
- Parâmetros: `faceStyle`, `hotspots`, `stateOf(control): AUTO|OVERRIDE`,
  `flash: State<Set<String>>`, `onHotspotTap(control)`.

### 1.3 Interação de mapeamento (reuso da captura do radial editor)

Tap/click no hotspot → modo captura: chip flutuante "Pressione o botão para
**{controle}**… (B = cancelar)" → espera próximo evento cru do deviceId (mesmo
caminho do `RadialMenuEditorDialog`) → callback `onBinding(control, input)` para
o dialog pai salvar no perfil. B cruzeiro/hardware back cancela.

### 1.4 Escopo e restauração

- Seletor segmented no topo do tab CONTROLLER do dialog: **"Este jogo" /
  "Todos os jogos"** — escreve no override do appId atual ou no global do device
  (o `merged` já resolve a precedência JOGO > GLOBAL > AUTO).
- Botão "Restaurar automático" por controle (limpa o override daquele controle —
  política isDefault) e geral (limpa todos os bindings do escopo selecionado).
- Indicador de herança v1: badge AUTO/OVERRIDE por controle (granular
  "veio de GLOBAL/JOGO" é follow-up).

### 1.5 Integração híbrida (PPSSPP)

`GamepadRemapDialog`, tab CONTROLLER: NO TOPO a seção colapsável "Mapa visual"
(com `ControllerVisualView` + captura + escopo); EMBAIXO a lista avançada
existente (camadas/gyro/flick/LUT) permanece intacta. Nada é removido.

## 2. Arquivos afetados

| Arquivo | Mudança |
|---|---|
| `gamepad/mapping/ControllerVisualLayout.kt` | NOVO — hotspots + hit-test puros (1.1) |
| `ui/component/remap/ControllerVisualView.kt` | NOVO — desenho + flash + seleção (1.2) |
| `gamepad/remap/GamepadRemapDialog.kt` | seção "Mapa visual" + escopo + restaurar (1.4/1.5) |
| `res/values*/strings.xml` | chaves (captura, escopo, restaurar, badges) |
| `app/src/test/.../ControllerVisualLayoutTest.kt` | NOVO — hit-test de todos os controles por FaceStyle; sem ambiguidade (1 ponto não acerta 2 hotspots); fora do desenho = null |

## 3. Verificação (gate)

```
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:testModernDebugUnitTest --tests "*ControllerVisual*" --tests "*Gamepad*"
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:assembleModernDebug
```
On-device (humano, "on-device pendente"): abrir remap pelo QuickMenu → visual
renderiza com FaceStyle do device; tap em ✕ → captura → apertar R1 → override
salvo; flash acende ao apertar botões físicos; escopo Este jogo vs Todos os
jogos persiste e o jogo respeita.

## 4. Fora de escopo

Desenho por VID/PID exato (só FaceStyle), editor arrastar-e-soltar, animação
3D, remap do overlay de toque, indicador granular de herança (follow-up),
perfil de calibração de stick no visual (já existe na lista).
