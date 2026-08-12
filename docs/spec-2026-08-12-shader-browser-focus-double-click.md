# Spec — Browser de shaders: foco de gamepad à prova de ida-e-volta + duplo-clique "aplica e fecha"

**Data:** 2026-08-12
**Complementa:** `docs/spec-2026-08-11-slang-shaders-on-demand.md` (não substitui; este documento é o conjunto de missões sobre input/UX).
**Escopo:** apenas QuickMenu ⇄ ShaderBrowserOverlay e seus efeitos colaterais de foco. Zero mudança em bus de input, `VulkanLibrashader.cpp`, renderer ou nos efeitos nativos.
**Status:** Missões 1–3 e 5 implementadas e commitadas (ver MILESTONES); Missão 4 sem código (verificação documentada); Missão 6.1 verde (101/101 JVM); **Missão 6.2 (F1–F10 on-device) pendente — sem dispositivo disponível**.

---

## 0. Contexto e veredito sobre a arquitetura de input

O app **já implementa o padrão centralizado** que a literatura recomenda (roteamento por bus + estado, não pelo foco de view do Android):

- `PluviaApp.events` entrega `AndroidEvent.KeyEvent`/`MotionEvent` diretamente aos overlays (`GamepadBusInput.kt:33-92` e `:138-193`) — o equivalente prático do "GameInputManager" com FSM: cada superfície instala **um único** escopo de cada vez e o consome por inteiro.
- Enquanto o browser de shaders está aberto, o escopo do QuickMenu **não é composto** (`QuickMenu.kt:584-606`); o browser instala o seu (`ShaderBrowserOverlay.kt:169-174`). Escritor único ⇒ sem disputa de evento.
- A → DPAD_CENTER sintético, filtro `SOURCE_JOYSTICK|SOURCE_DPAD`, `repeatCount == 0`, dead-zone com re-arm (`GamepadStickLogic`), lógica pura JVM-testável (`GamepadKeyLogic`).

Logo: **não reconstruir o sistema de input**. As missões abaixo fecham os 3 pontos onde o foco pode efetivamente se perder nas transições, e adicionam o gesto de duplo-clique.

---

## Missão 1 — Guardião de foco não trabalha enquanto o browser está aberto

**Problema:** o guardião contínuo (`QuickMenu.kt:1115-1127`) roda enquanto `isVisible` — inclusive com o browser aberto. A cada 400 ms ele chama `requestMenuFocus()`, que tenta requesters do menu que **não estão compostos** (o conteúdo foi trocado pelo browser). Não quebra (as exceções são capturadas), mas é desperdício, ruído de log e uma corrida desnecessária contra o bootstrap de foco do próprio browser.

**Mudança (`QuickMenu.kt`):**

```kotlin
// antes: LaunchedEffect(isVisible) { if (isVisible) { ...while(isVisible)... } }
LaunchedEffect(isVisible, shaderBrowserOpen) {
    if (isVisible && !shaderBrowserOpen) {
        delay(150)
        while (isVisible && !shaderBrowserOpen) {
            if (!menuHasFocus) requestMenuFocus()
            delay(400)
        }
    }
}
```

**Aceite:** com o browser aberto, nenhum log `QuickMenu guardian:` durante 10 s (logcat); ao fechar o browser, o guardião volta a proteger o menu.

---

## Missão 2 — Volta do browser para o menu restaura foco imediatamente (não esperar o guardião)

**Problema:** fechar o browser (B na raiz / PS) recompõe o conteúdo do menu; hoje o foco só volta quando o guardião percebe (até 400 ms de "menu morto").

**Mudança (`QuickMenu.kt`):** efeito dedicado para a transição browser → menu, reusando o bootstrap existente (`requestMenuFocus()` — clearFocus + retry + walk-down até `effectsFocusIndex`):

```kotlin
LaunchedEffect(shaderBrowserOpen) {
    if (!shaderBrowserOpen && isVisible) {
        requestMenuFocus()
    }
}
```

**Aceite:** B na raiz do browser ⇒ menu volta com foco na linha onde estava (walk-down restaura `effectsFocusIndex`); sem janela sem foco perceptível; T1 (foco nunca perdido) permanece verde.

---

## Missão 3 — Defesa contra menu fechado com o browser ainda aberto

**Problema:** `shaderBrowserOpen` é `remember` (não sobrevive a processo, ok), mas se qualquer caminho fechar o QuickMenu com o browser aberto, a reabertura mostraria o browser em vez da aba EFFECTS — quebrando o requisito "reabrir volta à mesma seção".

**Mudança (`QuickMenu.kt`):** reset defensivo sempre que o menu fecha:

```kotlin
LaunchedEffect(isVisible) {
    if (!isVisible) shaderBrowserOpen = false
}
```

(pode ser fundido ao `LaunchedEffect(isVisible)` do clearFocus em `QuickMenu.kt:1096-1106`).

**Aceite:** fechar o menu por qualquer caminho (PS, toque fora, BACK) e reabrir ⇒ aba EFFECTS, nunca o browser.

---

## Missão 4 — BackHandler empilhado: B no browser nunca fecha o menu inteiro

**Estado:** o browser registra `BackHandler(enabled = true)` (`ShaderBrowserOverlay.kt:276`) dentro do `BackHandler(enabled = isVisible)` do menu (`QuickMenu.kt:532`). O dispatcher entrega ao handler registrado por último ⇒ browser primeiro. Correto por construção, mas não testado.

**Mudança:** sem código (verificação):

1. Teste de regressão documentado: no browser com query ativa, B limpa a query (não desempilha, não fecha); na raiz do browser, B fecha só o browser; só então B fecha o menu.
2. Cobrir on-device no harness (`DebugGamepadInput.kt`: `key:4` BACK) + joystick.

**Aceite:** sequência B×3 no browser = limpar busca → fechar browser → fechar menu, um nível por pressionamento.

---

## Missão 5 — Duplo-clique: seleciona o shader E fecha o QuickMenu

### 5.1 UX (o gesto)

Objetivo: loop rápido de experimentação — **PS → escolher shader → A A (aplica e fecha) → ver o jogo → PS → repetir**.

Regras:

1. **Primeiro clique não tem atraso** — aplica o preset imediatamente (como hoje). Nada de debounce que atrase a ação primária.
2. **Segundo clique na MESMA linha, dentro de 300 ms** ⇒ confirma e fecha o QuickMenu inteiro (browser + menu).
3. O gesto de fechar **só existe se o primeiro clique aplicou o preset de verdade**:
   - linha em nuvem (primeiro clique dispara download do pack) → fora do gesto;
   - CTA de download, "Show more", chip Back → fora do gesto (componentes separados, nem passam pela lógica);
   - primeiro clique que falhou (`applyPreset` retornou `false`) → não arma o segundo.
4. Interação com o toggle existente ("clicar no preset ativo desliga só ele", commit `d73a83cc`): dentro da janela de duplo-clique o segundo pressionamento **não desliga** — ele confirma-e-fecha. Fora da janela (>300 ms), o clique no preset ativo continua desligando-o (comportamento intocado).
5. Dois cliques rápidos em **linhas diferentes** = troca de shader, sem fechar (cada clique aplica; o segundo rearma o gesto para a nova linha).
6. Toque e gamepad (A/DPAD_CENTER/ENTER) passam pelo MESMO `onClick` (`gamepadSelectable`, `GamepadModifiers.kt:102-137`) ⇒ um único caminho, sem bifurcação touch/gamepad.

### 5.2 Lógica pura (JVM-testável, padrão do codebase)

Novo arquivo `app/src/main/java/app/gamenative/shaders/ShaderDoubleClickLogic.kt`:

```kotlin
object ShaderDoubleClickLogic {
    const val WINDOW_MS = 300L

    enum class Action { Activate, ConfirmAndClose }

    /**
     * [armedPath]/[armedAtMs] = último preset que foi aplicado de verdade (null quando
     * nenhum, ex.: após clique em linha de nuvem ou aplicação falha).
     */
    fun decide(
        armedPath: String?,
        armedAtMs: Long,
        path: String,
        nowMs: Long,
    ): Action =
        if (armedPath == path && nowMs - armedAtMs in 0..WINDOW_MS) Action.ConfirmAndClose
        else Action.Activate
}
```

Testes (`app/src/test/java/app/gamenative/shaders/ShaderDoubleClickLogicTest.kt`, JUnit como os vizinhos):

| caso | entrada | saída |
|---|---|---|
| segundo clique rápido na mesma linha | armed=X@t, press X@t+200 | `ConfirmAndClose` |
| clique rápido em linha diferente | armed=X@t, press Y@t+200 | `Activate` |
| mesmo caminho fora da janela | armed=X@t, press X@t+350 | `Activate` |
| nada armado (primeiro clique) | armed=null, press X | `Activate` |
| fronteira exata | armed=X@t, press X@t+300 | `ConfirmAndClose` |

### 5.3 Integração no `ShaderBrowserOverlay.kt`

- Novo parâmetro: `onCloseQuickMenu: () -> Unit` (o QuickMenu passa `onDismiss`).
- Estado local: `var armedPath by remember { mutableStateOf<String?>(null) }` e `var armedAtMs by remember { mutableLongStateOf(0L) }`.
- `PresetRow.onClick` (hoje `ShaderBrowserOverlay.kt:398-404`) vira:

```kotlin
onClick = {
    when {
        broken -> Unit
        !local -> state.startInstall(preset)   // baixa SÓ a closure do preset; nunca arma o gesto
        else -> {
            val action = ShaderDoubleClickLogic.decide(armedPath, armedAtMs, preset.path, SystemClock.uptimeMillis())
            when (action) {
                ShaderDoubleClickLogic.Action.Activate ->
                    if (state.applyPreset(preset)) {       // só arma se aplicou de verdade
                        armedPath = preset.path
                        armedAtMs = SystemClock.uptimeMillis()
                    }
                ShaderDoubleClickLogic.Action.ConfirmAndClose -> {
                    armedPath = null
                    onClose()            // shaderBrowserOpen = false
                    onCloseQuickMenu()   // fecha o QuickMenu
                }
            }
        }
    }
}
```

- Linhas de Recents usam o mesmo `PresetRow` ⇒ herdam o gesto sem código extra.

### 5.4 Integração no `QuickMenu.kt`

```kotlin
ShaderBrowserOverlay(
    state = shaderSection,
    onClose = { shaderBrowserOpen = false },
    onCloseQuickMenu = onDismiss,
    modifier = Modifier.fillMaxSize(),
)
```

Ao fechar por duplo-clique, o `LaunchedEffect(isVisible)` da Missão 3 garante `shaderBrowserOpen = false` para a próxima abertura.

### 5.5 O que o usuário ganha na reabertura (já existe, só validar)

- PS reabre na aba EFFECTS: `selectedTab` inicializa de `PrefManager.quickMenuLastTab` (`QuickMenu.kt:365-374`), persistida toda vez que a aba é escolhida.
- Foco volta à mesma linha: `effectsFocusIndex` é `rememberSaveable` + walk-down no bootstrap (`QuickMenu.kt:1059-1064`) — a linha "Browse shaders" fica focada, pronta para outro A.

**Aceite da missão:** on-device — PS → A (abre browser) → descer até `crt/easymode` → A A ⇒ shader aplicado (pixel-stats do harness) e menu fechado; PS novamente ⇒ aba EFFECTS, linha Browse focada; A → browser reabre com foco restaurado.

---

## Missão 6 — Verificação

### 6.1 Testes JVM

- `ShaderDoubleClickLogicTest` (casos da §5.2).
- Rodar: `./gradlew :app:testDebugUnitTest --tests "*Shader*" --tests "*Gamepad*"`.

### 6.2 On-device (Mi 11; harness `tools/shader-test-loop/shader_test_loop.py` + `DebugGamepadInput.kt`)

| # | cenário | esperado |
|---|---|---|
| F1 | abrir browser, navegar 30 s, fechar com B | foco nunca some (logcat sem `guardian: restoring` durante browser aberto) |
| F2 | B na raiz do browser | menu volta com foco na linha anterior (≤1 frame de "menu morto") |
| F3 | B×3 com busca ativa | limpa busca → fecha browser → fecha menu |
| F4 | PS com browser aberto | fecha browser; PS de novo fecha menu; PS de novo reabre em EFFECTS |
| F5 | A A em preset local | aplica + fecha menu; frame com shader (delta-vs-baseline do harness) |
| F6 | A (espera 500 ms) A no preset ativo | segundo clique desliga o preset (toggle fora da janela preservado) |
| F7 | A A em linha de nuvem | primeiro clique inicia download; segundo não fecha o menu |
| F8 | A rápido em duas linhas diferentes | troca de shader duas vezes, menu continua aberto |
| F9 | toque duplo em preset | mesmo resultado do A A |
| F10 | T1–T6 do padrão `docs/quickmenu-joystick-audit-2026-08-11.md` | permanecem verdes |

---

## 7. Não fazer (proteções de escopo)

- **Não** reconstruir o sistema de input "à la GameInputManager" — o bus já é o roteador centralizado; reescrever regrediria o que foi auditado on-device.
- **Não** adicionar delay ao primeiro clique para detectar duplo-clique (mataria a sensação de resposta instantânea).
- **Não** fechar o QuickMenu em clique único (a experimentação requer ver o efeito E poder continuar ajustando).
- **Não** tocar em `GamepadBusInput.kt`, `GamepadModifiers.kt`, `VulkanLibrashader.cpp`, `VulkanRenderer.java` nem nos efeitos nativos do renderer.

## 8. Checklist de execução (ordem)

1. ✅ Missão 3 (reset defensivo de `shaderBrowserOpen` — fundido no `LaunchedEffect(isVisible)` de clearFocus).
2. ✅ Missão 1 (guardião gateado por `!shaderBrowserOpen`).
3. ✅ Missão 2 (restore explícito ao fechar browser, com latch `browserWasOpen` para não correr contra o bootstrap de abertura).
4. ✅ Missão 5.2 + testes (`ShaderDoubleClickLogic` puro, 6 testes).
5. ✅ Missão 5.3/5.4 (integração overlay + QuickMenu; `onCloseQuickMenu = onDismiss`).
6. ✅ Missão 6.1 (101/101 testes JVM verdes, incluindo `ShaderDoubleClickLogicTest`) + `assembleModernDebug` OK.
7. ⏳ Missão 6.2 (verificação on-device F1–F10) — **pendente, sem dispositivo**; protocolo pronto (harness `DebugGamepadInput` + `shader_test_loop.py`).
8. ✅ Commit com referência a esta spec + linha em `docs/MILESTONES.md`.
