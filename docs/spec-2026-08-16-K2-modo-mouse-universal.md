# Spec 2026-08-16 K2 — Modo mouse universal por stick (toggle por chord, port moonlight)

**Data:** 2026-08-16
**Origem:** moonlight-android `reference/moonlight-android/.../ControllerHandler.java`
— toggle por **hold START 750 ms confirmado no key-up** (`START_DOWN_TIME_MOUSE_MODE_MS`
**:60**, checagem **:2371-2375**); stick→cursor com **rampa quadrática**
(`convertRawStickAxisToPixelMovement` **:1837**); A/B = botões do mouse; dpad =
scroll; período de report **50 ms**; combos de saída com tolerância de release
(`MAXIMUM_BUMPER_UP_DELAY_MS` **:100**). DS4Windows análogo (modo Mouse do gyro
já coberto por G — este spec é o STICK como mouse).
**Executor:** sub-agente autônomo. Leia `AGENTS.md` + master roadmap universal §1/§2.
**Posição na fila:** fase K2 (depois de K6; antes de K1, que pode compor com ela).
**Turn budget sugerido:** 20–25 turns.

## 0. Estado atual (anchors)

- `gamepad/GamepadHub.kt:726` `remapEvent` + flush da fase I — ponto único onde
  botões/eixos LÓGICOS são decididos; `:880-915` gyro MOUSE já emite
  `PluviaApp.xServerMouseSink.move(dx, dy)` (acumulador sub-pixel G1 — REUSAR o
  padrão do `GyroPixelAccumulator` para o stick).
- Sink de mouse: interface em `gamepad/GamepadTouchpadForwarder.kt:39-44` —
  `move/click/pressLeft/releaseLeft/rightClick`. **FALTA scroll** (dpad do modo
  mouse precisa); implementação concreta ligada em `PluviaApp.kt:88/95/249`.
- `gamepad/processing/` — casa dos processors puros (`TouchpadProcessor`,
  `GyroStickMapping`); `PhysicalControllerHandler.kt:171` `onKeyEvent` +
  `:728` `injectBinding` — onde o estado lógico vira input do X/jogo.
- Lembrete arquitetural (AGENTS.md/lição C1): estado de roteamento consultado
  NO MOMENTO do evento (holder vivo), nunca capturado da composição.

O que FALTA: para jogos KB/M-only (inevitáveis no ecossistema Winlator) não há
"segurei START → virei mouse com o stick". Hoje só touch (`TouchMouse`) e gyro
(G1) movem o cursor; o stick, a interface mais universal do gamepad, não.

## 1. Design

### 1.1 `MouseModeProcessor` — PURO (JVM-testável)

Novo `gamepad/processing/MouseModeProcessor.kt`:

```kotlin
class MouseModeState(
    var startDownAtMs: Long = 0L,     // armando o toggle
    var armed: Boolean = false,       // START segurado ≥ toggleMs
    var active: Boolean = false,      // modo mouse ligado
    val accumulator = SubPixel(0f, 0f),
)
object MouseModeProcessor {
    fun onKey(state, button: GamepadButton, isDown: Boolean, nowMs: Long): MouseModeOutcome
    fun onStick(state, x: Float, y: Float, nowMs: Long, speed: MouseModeSpeed): MouseMove?
}
sealed interface MouseModeOutcome {
    data object None; data object Activated; data object Deactivated
    data class MouseButton(val left: Boolean, val down: Boolean)   // A/B enquanto ativo
    data class MouseScroll(val steps: Int)                          // dpad repetido
}
```

Semânticas portadas (todas com fonte no KDoc):
1. **Toggle**: START down marca `startDownAtMs`; cruzou `toggleMs` (default
   **750 ms**, moonlight :60) → `armed = true`; no **key-up**, `armed` → flip
   `active` (confirmação no release evita toggle acidental duplo, moonlight
   :2371-2375). Release antes do limiar → nada (START segue para o jogo).
2. **Rampa quadrática** do cursor (moonlight :1837): velocidade px/frame =
   `base + k * deflection²` por eixo (não linear — precisa de precisão no centro
   e velocidade na borda); `speed.base`/`speed.gain` configuráveis
   (`MouseModeSpeed(basePps, gainPps)` — defaults derivados do moonlight na
   leitura da fonte).
3. **Report de 50 ms**: o hub só EMITE movimento a cada 50 ms (também padrão
   moonlight) — sem timer: gate por timestamp no estado (o flush da fase I já
   roda por evento; sticks reportam ~60 Hz, o gate de 50 ms basta).
4. **Sub-pixel**: parte fracionária acumulada (padrão G1 do gyro) — movimento
   lento nunca "congela".
5. **A/B = cliques** (A=left, B=right), **dpad = scroll** (repetição com o
   anti-repeat de 120 ms do `GamepadMoveDedupe` — MESMA janela, reusar a
   constante), L1/R1 opcionais = back/forward (não-meta se complicar).
6. **Enquanto ativo**, botões/eixos consumidos pelo modo NÃO chegam ao jogo
   (exceto START que faz o toggle e volta a ser START).

### 1.2 Sink: `scroll`

Interface `MouseExecutorSink` (`GamepadTouchpadForwarder.kt:39`) ganha
`fun scroll(verticalSteps: Int)`; implementação concreta move a roda do X
(4-5 "detents" por passo — checar o que o XServer do winlator expõe; se só
houver botões de roda, emitir press/release). Defaults no-op na implementação
fake (linha ~146 do mesmo arquivo) para não quebrar testes existentes.

### 1.3 Integração no hub (post-remap, pré-injeção)

Em `remapEvent`/flush, DEPOIS do pipeline lógico e ANTES do
`PhysicalControllerHandler.injectBinding`:
`MouseModeProcessor.onKey(...)` → `Activated/Deactivated` (haptic curto de
confirmação via `GamepadHaptics.vibrateDevice` — feedback de que entrou/saiu,
padrão moonlight "OSD toast" adaptado a haptics) / `MouseButton` /
`MouseScroll` → sink. `onStick` no caminho de eixos LÓGICOS (pós
`StickTransform`/deadzone — o modo respeita a calibração do usuário).
- Overlay aberto (QuickMenu/radial/remap): modo mouse SUSPENSO (o dpad volta a
  navegar o menu — decisão no holder `OverlayInputState` NO MOMENTO do evento,
  lição C1). Ao fechar, `active` persiste.
- **Zero locals novas em `XServerScreen.kt`** (se algo tocar lá, é 1 holder
  `remember` — e provavelmente NÃO precisa tocar: tudo vive no hub/handler).

### 1.4 Perfil

`GamepadProfile` (política null-default, atualizar `isDefault()`/`merged()`):
```kotlin
val mouseModeEnabled: Boolean? = null,      // null = OFF (caminho atual byte-identical)
val mouseModeToggleMs: Int? = null,         // null = 750
val mouseModeBasePps: Float? = null,        // null = default da fonte
val mouseModeGainPps: Float? = null,
```
Sem switch global de tecla (o chord START é fixo no v1 — configurar o botão de
toggle é follow-up; documentar). Toggle rápido na UI do card/remap (switch liga
o modo para o device; o chord sempre disponível quando enabled).

## 2. Arquivos afetados

| Arquivo | Mudança |
|---|---|
| `gamepad/processing/MouseModeProcessor.kt` | NOVO — estado+semânticas puras (1.1) |
| `gamepad/GamepadTouchpadForwarder.kt` | `scroll` na interface + fake no-op |
| sink concreto (ligado em `PluviaApp.kt:88`) | implementar scroll (roda do X) |
| `gamepad/GamepadHub.kt` | hook pós-remap (1.3), gate 50 ms, suspensão por overlay |
| `gamepad/profiles/GamepadProfile.kt` | 4 campos null-default (1.4) |
| `gamepad/remap/GamepadRemapDialog.kt` + `SettingsGroupGamepad.kt` | switch + sliders (1.4) |
| `res/values*/strings.xml` | chaves EN + pt-rBR |
| `app/src/test/.../MouseModeProcessorTest.kt` | NOVO — toggle (arma/release cedo/flip), rampa², 50 ms, sub-pixel, cliques, scroll com dedupe |

## 3. Verificação (gate)

```
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:testModernDebugUnitTest \
  --tests "*MouseMode*" --tests "*Gamepad*"
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:assembleModernDebug
```

## 4. On-device (humano — "on-device pendente")

1. Silksong: segurar START 750 ms → haptic → stick move o cursor (rampa
   percebida: centro lento, borda rápida); A/B clicam; dpad rola.
2. Repetir o chord → volta ao controle normal; START curto NUNCA vira toggle.
3. Com QuickMenu aberto, dpad navega o menu (suspensão); fechar → modo volta.
4. Jogo KB/M-only (ex.: um point-and-click via Wine): modo mouse torna o jogo
   jogável sem touch. Evidência: vídeo curto.

## 5. Não-metas

Configurar o botão/chord do toggle (v1 fixo em START); velocidade por jogo;
mouse absoluto (touch já cobre); gyro→mouse (G1 já cobre — o modo COMPÕE: se
gyro MOUSE e modo mouse ativos, ambos somam no cursor, igual moonlight).
