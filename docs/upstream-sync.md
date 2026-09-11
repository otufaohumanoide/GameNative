# Upstream sync — mapa de costuras e processo

Documento vivo do fork GameNative. Objetivo: não redescobrir a causa dos conflitos
a cada sync com o upstream (`utkarshdalal/GameNative`). Criado junto com o sync
`5cdc906a` (2026-09-11) — ver `docs/spec-2026-09-11-upstream-sync-5cdc906a.md`.

## Política de resolução (P1–P5)

1. **P1** — Em qualquer hunk, a versão do **upstream vence**.
2. **P2** — O fork só reescreve código upstream quando for **necessário** para uma
   feature do fork sem equivalente (lista P3 do spec).
3. **P3** — Features do fork **mantidas**: pipeline universal (K1), camadas/remap
   (U3/U4), expressões (J), trigger engine (H/I), turbo/mode shift/radial (F),
   touchpad→mouse (U2), swipes/macros (D), perfis/catálogo (E/K3–K6), card de
   device (C), rumble (A/U5), bateria (U7), HUD de latência, navegação gamepad do
   QuickMenu/browser e shaders/librashader.
4. **P4** — Features do fork **removidas** quando o upstream vira dono (ex.: gyro do
   controle no sync 2026-09-11 — o upstream #1882 virou o dono do gyro do aparelho).
5. **P5** — Nunca reformatar, renomear ou "melhorar de passagem" código upstream.

## Mapa de costuras (21 arquivos tocados pelos dois lados)

Os 9 arquivos em conflito no sync `5cdc906a` e a regra de resolução:

| Arquivo | O fork faz ali | Regra de resolução |
|---|---|---|
| `PrefManager.kt` | bloco de prefs de gamepad (gamepad*, virtualGamepadPipeline, touchpad, rumble, HUD) | manter os dois blocos (fork + upstream `preferred_family_lenders`) |
| `SettingsGroupInterface.kt` | switches "Home direto para o jogo" e "ignorar touchpad" | manter os dois switches; versão upstream das recomendações |
| `XServerScreen.kt` | hotplug via bus (`GamepadDeviceAddedEvent`/`RemovedEvent`); teardown do sensor do fork | listener `InputManager` upstream emite os eventos do bus; remover listener duplicado |
| `ControlElement.java` | wrapper `emitBinding` (K1 — overlay de toque vira device virtual) | upstream integral + só o wrapper reancorado na API nova |
| `PhysicalControllerHandler.kt` | remap universal, L2/R2, flick stick, touchpad puro, listener virtual, turbo | upstream em todos os hunks + reancorar hooks mínimos (§3.5 do spec) |
| `ControllerBindingDialog.kt` | foco gamepad (`gamepadSelectable`, `FocusRequester`) | lógica upstream + reaplicar foco com guarda de `enabled` |
| `TouchGestureSettingsDialog.kt` | `GamepadFocusScope` + `gamepadSelectable` | picker upstream + reaplicar foco |
| `ShooterModeSettingsDialog.kt` | dropdown/slider inline com foco gamepad | upstream (helpers compartilhados); remover cópias inline |
| `SettingsDialogBlocks.kt` | A-lock (`gamepadAdjustableRow`) nos blocos compartilhados | upstream + reaplicar A-lock UMA vez no bloco compartilhado |

Os 12 arquivos auto-mesclados (revisar call sites, não reimplementar):

- `app/build.gradle.kts`
- `MainActivity.kt`, `PluviaApp.kt`
- `ElementEditorDialog.kt`, `PhysicalControllerConfigSection.kt`, `QuickMenu.kt`
- `LibraryScreen.kt`
- `ContainerUtils.kt`
- `VulkanRenderer.java`, `WinHandler.java`
- `values/strings.xml`, `values-pt-rBR/strings.xml`

## Log de syncs

| Data | Hash upstream | Arquivos em conflito | Decisões novas |
|---|---|---|---|
| 2026-09-11 | `5cdc906a` | 9 (36 hunks) — lista acima | gyro do fork removido em favor do #1882; combos #1833 adotados; shaders seguem só no fork |

## Receita de emergência

- Abortar: `git merge --abort` (restaura o estado antes do merge).
- Ponto de retorno: tag `pre-sync-<data>` criada antes de cada sync.
- No merge, **`--ours` é o fork (`master`) e `--theirs` é o upstream**.
- Para arquivos onde P1 vale e não há costura: `git checkout --theirs -- <arquivo>`.
- `rerere` fica ligado (`rerere.enabled true`, `rerere.autoupdate true`) — resoluções
  repetidas viram automáticas.
- Cadência recomendada: 1–2 semanas (`git fetch upstream && git merge upstream/master`).
