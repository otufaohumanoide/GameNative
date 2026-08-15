# Spec 2026-08-16 F — Radial Menu v2 (submenus, ícones, hold) + Mode Shift + Turbo

**Data:** 2026-08-16
**Origem:** follow-ups registrados (deviação nº 6 do impl doc — execução fecha o
menu mesmo com HOLD seguro) + roadmap UX (Steam Deck: submenus/ícones; Steam
Input: mode shift e turbo/rapid-fire).
**Executor:** sub-agente autônomo. Leia `AGENTS.md` + master roadmap §2.
**DEPENDÊNCIA: executa DEPOIS de C** (liberou o hotspot radial). Spec autocontido.

## 0. Estado atual

- `gamepad/radial/RadialMenuCore.kt`: `RadialMenuConfig(triggerLayer, sectors
  2..8, schemaVersion=1)`; `RadialSector(label, keys: List<RadialMacroKey>,
  colorIndex)`; `RadialMacroKey(keyCode, holdMs=60, gapMs=40)`;
  `RadialMenuPlan` (timing puro); `RadialMenuGeometry` (ângulo→setor, angleOf).
- `RadialMenuOverlay`: touch-first (slide→release executa; toque curto cancela),
  fallback stick (janela 120 ms + tick por setor), gatilho por `GamepadLayerEvent`
  (camada U3 HOLD/TOGGLE/DOUBLE_TAP — `LayerTriggerSpec`/`LayerResolver`).
- `RadialMenuExecutor`: KeyEvents sintéticos via `dispatchKeyEvent` + Handler.
- `RadialMenuStore` por jogo (appId, JSON, write atômico).
- Bindings de camada: `Binding` (tecla/eixo) via `GamepadBindingCodec`;
  `layerBindingFor` no hub; `PhysicalControllerHandler` aplica no jogo (U4).

## 1. Design

### 1.1 Schema v2 (RadialMenuCore — retrocompatível)

`schemaVersion: Int = 2` + campos novos com default (v1 lê normal —
`ignoreUnknownKeys` já preserva extras):
- `RadialSector.children: List<RadialSector> = emptyList()` — submenu aninhado
  (1 nível v2; filho não tem `children` — parser zera recursivamente).
- `RadialSector.iconKey: String? = null` — allowlist de ícones (map nome→
  Material icon NO OVERLAY, ~16 ícones: sword, potion, map, bag, run, gear,
  heart, star, home, save, load, camera, chat, trade, craft, fight). Nome fora
  da allowlist = ícone nulo (label só) — nunca crash.
- `RadialMenuConfig.executeMode: ExecuteMode = TAP_RELEASE` — enum
  `TAP_RELEASE | HOLD`: HOLD = o setor destacado executa SEM FECHAR enquanto o
  gatilho de camada estiver seguro (repetindo o macro a cada ativação de setor
  nova); fecha no release do gatilho (resolve a deviação nº 6 — hoje toda
  execução fecha; em HOLD vira painel persistente).

### 1.2 Overlay/Host/Editor (arquivos radial existentes)

- Overlay: setor com `children` → selecionar ABRE sub-roda (re-render com os
  filhos; voltar = cancelar o gesto/B); executar macro de filho fecha (ou segue
  o executeMode). Ícone desenhado acima do label no setor.
- `executeMode == HOLD`: seleção (touch-slide contínuo ou stick) já executa via
  `onExecute` porém o host NÃO fecha nem retoma; `GamepadLayerEvent(false)` é
  quem fecha (já existe). Anti-repeat: executa só na MUDANÇA de setor (mesma
  janela de 120 ms do stick; touch = mesmo critério).
- Editor (`RadialMenuEditorDialog`): seletor de ícone (grid da allowlist),
  botão "transformar em submenu" (promove setor a pai), toggle do executeMode.

### 1.3 Mode Shift (chord por camada — camada de shift sem tick e sem radial)

`LayerTriggerSpec` ganha `isShift: Boolean = false` (serializável, default
preserva v1):
- Shift ativo (enquanto seguro, igual HOLD) → `effectiveBindings` resolve pela
  camada shift (mecânica U3 existente, NENHUMA mudança no resolver).
- Diferenças de uma camada comum: NÃO emite `GamepadLayerEvent` (não abre
  radial, não compete com triggers reais), NÃO dá tick háptico, e o botão físico
  do shift é CONSUMIDO (não chega ao jogo — camada comum é pass-through).
- Hub (`resolveLayerTriggers`/`emitLogical`): branch por `isShift`; estado morto
  no `removeDevice` (V6) igual às camadas.
- UI: no editor de camadas do `GamepadRemapDialog`, toggle "Camada de shift".

### 1.4 Turbo/rapid-fire em bindings (DS4Windows-style) — PURA

- `Binding` ganha `turbo: Boolean = false` (codec atualizado; default =
  byte-identical).
- Novo `gamepad/processing/TurboScheduler.kt` puro:
  `fun nextToggleAt(nowMs: Long, periodMs: Long, phase: Int): Long` +
  `PERIOD_DEFAULT_MS = 80L` — decide o próximo down/up sintético; o handler
  mantém `turboStates: MutableMap<deviceId, MutableMap<axisOuBotao, phase>>`.
- `PhysicalControllerHandler`: binding turbo no DOWN físico → agenda toggles
  lógicos (down/up alternados, `Handler` main, mesmo caminho de injeção do U4);
  UP físico → cancela agendamentos e garante release lógico limpo (mesma
  disciplina do `remappedAxisBindings`). Perfis V6: estados morrem no remove.
- UI: no chip de binding da camada, toggle "Turbo" (+ período fixo 80 ms v2).

## 2. Arquivos afetados

| Arquivo | Mudança |
|---|---|
| `gamepad/radial/RadialMenuCore.kt` | schema v2 (1.1) + parser zera children recursivos |
| `ui/component/radial/RadialMenuOverlay.kt` | submenus, ícones, executeMode HOLD (1.2) |
| `ui/component/radial/RadialMenuHost.kt` | ciclo HOLD (não fecha; layer-off fecha) (1.2) |
| `ui/component/radial/RadialMenuEditorDialog.kt` | UI ícone/submenu/executeMode (1.2) |
| `gamepad/layers/LayerTriggerSpec.kt` + `GamepadHub.kt` | `isShift` (1.3) |
| `gamepad/remap/GamepadBindingCodec.kt` + `GamepadRemapDialog.kt` | `turbo` no binding + toggles UI (1.3/1.4) |
| `gamepad/processing/TurboScheduler.kt` | NOVO puro (1.4) |
| `ui/screen/xserver/PhysicalControllerHandler.kt` | agendamento turbo (1.4) — sem locals novas |
| `res/values*/strings.xml` | chaves |
| `app/src/test/...` | `RadialMenuCoreTest` (schema v2 roundtrip, children ignorados >1 nível), `TurboSchedulerTest`, `LayerResolverTest` (isShift branch) |

## 3. Verificação (gate)

```
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:testModernDebugUnitTest --tests "*Radial*" --tests "*LayerResolver*" --tests "*Turbo*"
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:assembleModernDebug
```
On-device (humano, "on-device pendente"): config v1 antiga carrega normal;
submenu abre/retorna; HOLD mantém o menu aberto executando por mudança de
setor; shift consume o botão físico e remapeia sem abrir radial; turbo alterna
no jogo enquanto segura e solta limpo.

Consolidado (fechamento 2026-08-16, §2 linha F — protocolo único do roadmap):
perfil v1 antigo carrega; submenu abre/volta; HOLD executa sem fechar
(anti-repeat 120 ms); shift consome o botão sem abrir radial; turbo pulsa e
solta limpo — evidência: screenshots + logcat `gncontrol`. Consumo de touch do
HOLD confirmado NO CÓDIGO na revisão (fechamento §1.2: `change.consume()` no
overlay + `radialState.open` no contexto OVERLAY do bus — impl doc §5);
confirmação física segue no protocolo humano. **Status: on-device pendente.**

Nota do impl (condições de entrega, registradas no impl doc 2026-08-16): HOLD é
um painel persistente — o host NÃO pausa o jogo ao abrir (os macros executados
no meio do jogo precisam chegar ao jogo) e NÃO retoma ao executar;
`GamepadLayerEvent(false)` é quem fecha. Turbo é onda quadrada digital (o ciclo
é dono da fonte enquanto ativo; período fixo 80 ms). Em HOLD/turbo o
stick/trigger continuam pass-through ao jogo (semântica de painel — a deviação
nº 6 do impl doc 2026-08-15 segue valendo para camada comum).

## 4. Fora de escopo

Mais de 1 nível de submenu, ícones custom do usuário (allowlist v2), turbo com
período configurável por binding na UI, macros com eixos/hold variável (o plano
só repete o mesmo macro), radial em telas fora do jogo.
