# Milestones do projeto

Mapa dos pontos importantes da história do projeto. Cada milestone é uma **tag anotada**
do git — permanente, nomeada e à prova de GC/reflog. Para voltar a um milestone:

```bash
git switch -c <nome-do-branch> milestone-<data>-<nome>   # ex.:
git switch -c retorno-milestone milestone-2026-08-07-librashader-funcional
```

Novos milestones: `tools/milestone.sh <nome-curto> "<descrição>"` (cria tag + registra aqui + push).

---

| Milestone (tag) | Commit (HEAD na época) | Data | O que marca |
|---|---|---|---|
| `milestone-2026-08-07-librashader-funcional` | `12de2797` | 2026-08-07 | **Shaders RetroArch (librashader) FUNCIONANDO no device** (Xiaomi Mi 11/Adreno 650) **+ pull do upstream utkarshdalal:master incluído** (merge `12de2797`: SteamOverlayClient, SteamWishlistService, traduções, libredirect, gradle). Tela preta corrigida (7 bugs: import AHB, finalLayout/transições, atlas→filterOutput, split submissions, cursor loadOp=CLEAR, config não aplicada no launch, crash cold-start). 131 presets libretro embarcados, default `film/technicolor` verificado por pixel-stats. Loop de teste automatizado `tools/shader-test-loop/shader_test_loop.py` (setprop `debug.gamenative.preset` + screencap + logcat; 16/17 shaders OK; `misc/glass` falha compile). Deferred preset reload na render thread (sem SIGSEGV). Commits-chave: `06aef179` (fix), `cf8085aa` (shaders+loop), `fc95cb4b` (docs), `12de2797` (pull upstream). |
| `milestone-2026-08-08-armsx2-inspired-librashader-hardening` | `bff50170` | 2026-08-08 | Shaders librashader endurecidos inspirados no ARMSX2 (emulador de PS2 p/ Android): port dos 3 padrões superiores — params aplicados só na render thread (geração atômica), falha de chain -> fallback pro frame sem shader + latch, e create-first swap (chain nova antes de liberar a antiga; create falho mantém o shader anterior). Sistema de feedback cibernético: renderLoop loga exceções (era catch mudo), waits com timeout 500ms (anti-freeze WaitForFences), logs de path por frame, hook debug.gamenative.preset valida arquivo, loop de teste cena-independente (delta-vs-baseline). Verificado no Mi 11: invert STRONG_CHANGE (+250), technicolor CHANGED (+37), 17/17 sem erros de pipeline. Commits: 9c622426 (feat), bff50170 (docs). Referência: docs/ARMSX2-librashader-vulkan.md |
| `milestone-2026-08-09-quickmenu-gamepad-100pct` | `92f0c4b4` | 2026-08-09 | QuickMenu 100% navegável por joystick no device (bus-level input, padrão LibraryScreen). Causa raiz encontrada e corrigida: menu nascia sem foco (aba sem itens focáveis, ex. TOOLS sem processos) + analógico via event bus (PluviaApp.events) em vez de listener de view (que a view GL da janela do jogo tornava não confiável). Framework compartilhado (GamepadModifiers.kt + GamepadFocus.kt: gamepadSelectable/gamepadAdjustableRow/gamepadBackHandler/GamepadFocusScope + linguagem visual Focada/Selecionada/Travada), 19/19 testes JVM verdes. QuickMenu migrado + L1/R1 abas + L2/R2 página + GamepadActionBar + TOOLS persiste aba + remember-selection. Diálogos (ElementEditor/TouchGesture/ShooterMode/PhysicalController/Binding/pickers) com GamepadFocusScope + B fecha + sliders com A-lock. Strings EN+pt-rBR. Verificado no Mi 11 via harness setprop debug.gamenative.input pelo pipeline real: menu abre com foco (fallback rail), stick 1 linha/gesto, A aplica bead.slangp, A-lock 60->70 FPS, B hierárquico, L1/R1 cíclico, L2/R2 página. Bugs pegos na verificação: crash plurals (pluralStringResource), crash coerceIn lista vazia, orderedTabs congelado. Física (T5-T7 + GamepadActionBar) pendente do usuário. Commits: 92f0c4b4 |
| *(próximo)* | | | |
