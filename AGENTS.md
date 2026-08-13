# AGENTS.md

Fork do GameNative (Winlator-based) com suporte nativo a **shaders RetroArch (librashader)** no renderer Vulkan — cada frame passa por um filter chain antes da tela, trocável ao vivo pelo QuickMenu. O suporte a shaders **foi construído estudando os projetos ARMSX2 e melonDS/melonDS-android** (ver "Shaders" abaixo) — isso é parte da identidade técnica do fork, preserve a atribuição.

## Build

- App: módulo único `:app` (namespace `app.gamenative`). Flavors `modern`/`legacy` (flag `BuildConfig.MODERN_ANDROID`); task de assemble do fork: `assembleModernDebug`.
- **`JAVA_HOME` não está no PATH** — use `JAVA_HOME=/home/annapaula/android-studio/jbr` ao rodar gradle. `local.properties` aponta `sdk.dir=/home/annapaula/Android/Sdk`.
- Librashader é compilado de fonte no build (Rust): exige `cargo` em `~/.cargo/bin` + NDK 27.3.13750724, 3 ABIs. Erros de build podem vir da toolchain Rust, não do seu código.
- Submódulos que precisam existir: `app/src/main/cpp/extras/adrenotools` e `app/src/main/cpp/lsfg-vk-android` (`git submodule update --init` se ausentes).

## Testes

- **NUNCA rode a suíte completa `:app:testModernDebugUnitTest`** — é gigante e estoura 30 min. Use filtros (2–5 min):
  `JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:testModernDebugUnitTest --tests "*Shader*" --tests "*Gamepad*" --tests "*SearchField*"`
- Testes JVM em `app/src/test/java/...`; padrão do repo: **lógica pura em `object` sem dependência Android**, testável em JVM (`GamepadStickLogic`, `GamepadMoveDedupe`, `ShaderDoubleClickLogic`, `ShaderPagingLogic`, `ShaderFavorites` — estes dois últimos têm store/abstração fake para teste).

## Arquitetura de input de gamepad (o mais fácil de errar)

Pipeline: `MainActivity.dispatchKeyEvent/dispatchGenericMotionEvent` → `PluviaApp.events` (multicast; `EventDispatcher` com registry **por identidade `===`**, não por `toString`) → handlers do `XServerScreen` → navigators/bridges do bus.

- **Handlers do XServerScreen são registrados UMA vez em `DisposableEffect(Unit)`** — todo estado de roteamento consultado dentro deles deve ser lido **no momento do evento** (holder `OverlayInputState`), nunca capturado como `val` da composição (bug C1 do hardening: closure stale → jogo recebia input atrás do menu).
- Overlays in-game (QuickMenu/browser/edit) usam **navigators do bus** (`BusJoystickFocusNavigator` + `BusGamepadKeyBridge`) — listeners de view não funcionam na janela do jogo. Diálogos (janelas separadas) usam `GamepadFocusScope` (view-level). Nunca misture os dois na mesma superfície.
- Regras fixas: consumo do DPAD por `GamepadMoveDedupe` (janela 120 ms; repeat sempre passa); guardiões de foco com "gentileza" (skip < 600 ms desde o último move); bootstrap de foco com `clearFocus(true)` + retries + fallback (Compose 1.8 descarta `requestFocus` silenciosamente com alvo stale); IME do campo de busca só abre no X/A, nunca ao navegar com stick (via `GamepadNavigationClock`).
- Harness de teste no device (MIUI bloqueia `adb input`): `adb shell setprop debug.gamenative.input "key:110"|"stick:x:y"|"hat:x:y"`. **PS = `KEYCODE_BUTTON_MODE` = 110**, não 188 (188 é `BUTTON_1`). Protocolo em `app/src/main/java/app/gamenative/ui/component/DebugGamepadInput.kt`.

## Shaders

- Arquitetura: nada embarca no APK — `catalog.json` (manifest, 2.541 presets) navegável offline; o preset escolhido baixa **só a closure dele** do commit pinado do libretro/slang-shaders. Estado por-jogo em `PerGameShaderStore` (keyed por `container.id`/`appId`, JSON com `ignoreUnknownKeys`). UI em `ShaderBrowserOverlay` + `ShaderSectionState` (hoisted). Native em `app/src/main/cpp/winlator/VulkanLibrashader.h/cpp` + `VulkanRenderer.java`.
- **Atribuição (não remover):** os padrões vieram de ARMSX2 e melonDS — ARMSX2 contribuiu a aplicação de parâmetros só na render thread, fallback de chain com latch (preset quebrado degrada ao frame sem shader, nunca tela preta) e create-first swap (a troca só ocorre quando o chain novo compila); melonDS/melonDS-android contribuiu a topologia offscreen → sampler → swapchain present, o tracking de layout de imagem e as barreiras de memória largas (Adreno). Detalhes em `docs/ARMSX2-librashader-vulkan.md` e na seção "How it was built" do `README.md`.
- Regra nativa intocável: **todo acesso ao chain acontece na render thread** (lição do ARMSX2); parâmetros de preset não são expostos ainda (`VulkanLibrashader.h` não tem API de params — M6 do spec `2026-08-12-shader-ux-facilities.md`).
- Catálogo é gerado por `tools/shaders/sync_slang_shaders.py` (regenerar só quando o upstream muda; saída determinística).

## Workflow do repo

- **Docs e commits em PT-BR.** Commits no formato `tipo(escopo): descrição` (`fix(gamepad): ...`, `feat(shaders): ...`, `docs(...)`) referenciando o spec.
- Fluxo: **spec → revisão → implementação → impl doc → MILESTONES** (tag anotada via `tools/milestone.sh`). Specs em `docs/spec-*.md` (atuais) e `docs/superpowers/specs/` (antigos); planos em `docs/superpowers/plans/`. O spec vem ANTES do código; código não referenciado a spec é reprovado na revisão.
- Verificação on-device (Xiaomi Mi 11 / Adreno 650 + DS4, Silksong como jogo-teste): scripts `tools/quickmenu-verify.sh` e `tools/shader-test-loop/shader_test_loop.py`. Pendências on-device ficam registradas no spec (padrão "on-device pendente").

## Gotchas

- `XServerScreen.kt` (~6.000 linhas) está no limite do verifier (registros de método dex) — não adicione locals novas na função principal; suba para `remember`/objetos quando precisar (padrão já comentado em `QuickMenu.kt`).
- **`build/` pode ter classes compiladas stale que não correspondem ao source** (ex.: arquivo-fonte apagado mas `.class` antigo presente — compila "de mentira" até limpar). Confie no source; limpe `build/` quando o estado parecer inconsistente.
- `LibraryList.kt` é código morto (sem callers) — não "conserte" o que não é usado.
- Strings sempre EN (`values/`) + pt-rBR (`values-pt-rBR/`).
