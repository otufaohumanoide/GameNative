# Spec 2026-08-16 K7 — Calibração visual de stick (raw vs. calibrado ao vivo, port PPSSPP)

**Data:** 2026-08-16
**Origem:** PPSSPP `reference/ppsspp/UI/ControlMappingScreen.cpp` —
`AnalogCalibrationScreen` (**linhas 487–585**): DOIS `JoystickHistoryView`
lado a lado (**entrada crua vs. saída calibrada** ao vivo), sliders de deadzone
radius, **inverse deadzone (anti-deadzone)**, sensibilidade, **shape** da
deadzone (Circle/Square/Cross), **response curve** (Linear/Aggressive/Relaxed/
Wide — comentário do próprio PPSSPP: "Steam Input-style"); a screen bypassa o
foco de UI para eixos (`axis()` não chama `UIScreen::axis`) para não roubar o
evento do usuário. Absorve backlog `spec-2026-08-16-backlog-ux-follow-ups.md`
#12 (calibração no mock visual) e #1 (GUI de Kp/Ki — anexa à mesma tela).
**Executor:** sub-agente autônomo. Leia `AGENTS.md` + master roadmap universal §1/§2.
**Posição na fila:** fase K7 (última — UI isolada, fecha o roadmap).
**Turn budget sugerido:** 15–20 turns.

## 0. Estado atual (anchors)

- `gamepad/processing/StickTransform.kt` — pipeline JÁ EXISTE e é rico:
  `StickTransformConfig(deadzone, mode RADIAL/AXIAL, hysteresis, curve
  LINEAR/EXPO/SCURVE/LUT, lut)` (:28-35), `apply` (:44-69). **FALTA
  anti-deadzone (inverse deadzone) e "sensitivity/max output" explícito.**
- `GamepadProfile` (F1.1): `leftStickDeadzoneMode/Curve/Lut` etc. já são
  per-stick null-default; G2: OneEuro do gyro com minCutoff/beta; F1.3: Mahony
  `gyroFusionKp/Ki`. Tudo configurável hoje só por SLIDERS soltos na lista do
  remap — sem visualização do efeito.
- `ui/component/remap/ControllerVisualView.kt` + `gamepad/mapping/
  ControllerVisualLayout.kt` (fase B): mock clicável com flash — a aba nova
  mora no MESMO dialog.
- Flash ao vivo por bus (`GamepadInputEvent` do deviceId — padrão do B §1.2):
  a MESMA fonte alimenta os history views.

O que FALTA: o usuário ajusta deadzone às cegas. A tela do PPSSPP resolve:
você VÊ o círculo cru do stick (com drift/redeada) e a saída calibrada
respondendo em tempo real.

## 1. Design

### 1.1 `StickTransform` — 2 campos novos (o resto é UI)

```kotlin
data class StickTransformConfig(
    ...,
    val antiDeadzone: Float = 0f,     // 0 = OFF (byte-identical). PPSSPP "inverse deadzone"
    val maxOutput: Float = 1f,        // teto da saída (sensitivity) — 1 = atual
)
```
Aplicação na ordem (documentar no KDoc do `apply`): deadzone (como hoje) →
**anti-deadzone: rescala a magnitude pós-deadzone para começar em
`antiDeadzone`** (o jogo tem deadzone interna D → o usuário compensa para o
output nunca cair no limbo abaixo de D; padrão PPSSPP/DS4Windows) → curve
(como hoje) → clamp `maxOutput`. Perfil: `leftStickAntiDeadzone` /
`rightStickAntiDeadzone` / `*StickMaxOutput` null-default + `isDefault()`/
`merged()` (política V1).

### 1.2 `JoystickHistoryView` — Compose (arquivo próprio)

Novo `ui/component/remap/JoystickHistoryView.kt`:
- Canvas com TRILHA (história das últimas N posições, decaimento de alpha —
  rastro mostra drift/redeada do stick), PONTO atual, círculo da deadzone e
  anel do anti-deadzone desenhados por cima (visual imediato do que o slider
  faz).
- Params: `mode: RAW | CALIBRATED`, `config: StickTransformConfig`,
  `samples: State<List<StickSample>>`. Puro desenho — sem lógica de input.
- Duas instâncias lado a lado (RAW | CALIBRATED) — a comparabilidade É a feature.

### 1.3 Aba "Calibração" no `GamepadRemapDialog`

- Nova tab no dialog por device (mesma navegação gamepad do dialog —
  `gamepadSelectable`/foco, spec focus-feedback-v2).
- Fonte de dados: listener bus de `GamepadInputEvent` de EIXOS do deviceId
  (padrão do flash do B) → acumula `StickSample` num holder (1 `remember` no
  componente da tab — dialog não tem restrição dex).
- Sliders/segmenteds LIGADOS aos campos do perfil (escopo Este jogo/Todos os
  jogos do B §1.4 já existe — herdar): deadzone, mode (RADIAL/AXIAL),
  anti-deadzone, curve (+ LUT picker existente permanece na lista avançada),
  max output, hysteresis.
- Os eixos NÃO chegam ao jogo enquanto a tab está aberta (calibrar durante o
  jogo rodando é o caso de uso; consumo no listener do dialog — o evento foi
  consumido pelo remap, igual captura do B §1.3). O botão FACE do gamepad
  continua navegando o dialog (só EIXOS são capturados) — igual PPSSPP
  (`axis()` bypass, `ControlMappingScreen.cpp:585`).
- Seção anexa (fecha backlog #1): sliders `gyroFusionKp/Ki` com preview do
  pitch da fusão (linha 1D `JoystickHistoryView` reusado como gráfico de
  série temporal — mesma view, eixo único). Se o preview de fusão complicar,
  entregar só os sliders com valores atuais + nota no impl doc (stretch
  declarado).

### 1.4 Não-duplicação

A lista avançada existente (sliders soltos) PERMANECE (usuários de teclado/
acessibilidade); a aba nova é a interface visual. Campos são OS MESMOS do
perfil — sem estado paralelo.

## 2. Arquivos afetados

| Arquivo | Mudança |
|---|---|
| `gamepad/processing/StickTransform.kt` | antiDeadzone + maxOutput (1.1) |
| `gamepad/profiles/GamepadProfile.kt` | 4 campos null-default + isDefault/merged |
| `ui/component/remap/JoystickHistoryView.kt` | NOVO — view de trilha (1.2) |
| `gamepad/remap/GamepadRemapDialog.kt` | tab Calibração (1.3) |
| `res/values*/strings.xml` | chaves EN + pt-rBR |
| `app/src/test/.../StickTransformTest.kt` | + anti-deadzone/maxOutput (ordem, clamps, 0/1 = identidade) |
| `app/src/test/.../GamepadProfileStoreTest.kt` | +campos novos (V1) |

## 3. Verificação (gate)

```
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:testModernDebugUnitTest \
  --tests "*Stick*" --tests "*Curve*" --tests "*Gamepad*"
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:assembleModernDebug
```

## 4. On-device (humano — "on-device pendente")

1. DS4 com stick saudável: aba aberta no Mi 11, mover o stick → trilha crua
   (círculo pleno) vs. calibrada (com deadzone 0.2 → buraco central visível).
2. Anti-deadzone 0.3: trilha calibrada NUNCA entra no anel interno.
3. Fechar a aba → jogo recebe eixos normalmente; valores persistem por escopo
   (Este jogo vs Todos os jogos).
4. Backlog #1: ajustar Kp/Ki com drift visível no preview de fusão (se stretch
   entregue).

## 5. Não-metas

Calibração automática (medir centro e ajustar sozinho — follow-up); desenhar
curva LUT editável por gesto; usar a tab como EDITOR de layout do overlay
(é do winlator); Notch/gates (fase do Dolphin `getGateRadiusAtAngle` — outro
dia).
