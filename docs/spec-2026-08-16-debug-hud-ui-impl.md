# Spec de implementação — Debug HUD de latência ligável pela UI

**Data:** 2026-08-16
**Base:** spec 2026-08-16-debug-hud-ui.md
**Status:** implementado. Gate verde: `--tests "*Gamepad*"` + `assembleModernDebug`.

## Evidências (file:line)

| Peça | Arquivo | Detalhe |
|---|---|---|
| Pref | `PrefManager.kt` | `debugLatencyHudEnabled` (default false; KDoc cita o OR com a propriedade) |
| Overlay | `ui/component/LatencyDebugOverlay.kt` | `on = PrefManager.debugLatencyHudEnabled \|\| DebugPropertyCache.read(LATENCY_PROPERTY) == "1"`; `LatencyTracker.enabled` segue o mesmo OR |
| QuickMenu | `ui/component/QuickMenu.kt` | `QuickMenuToggleRow` "HUD de latência de input" na `PerformanceHudQuickMenuTab`, gate `BuildConfig.DEBUG`, estado local `remember { PrefManager.debugLatencyHudEnabled }` — sem QuickMenuAction novo, sem estado no XServerScreen (limite dex preservado: 0 locals novas) |
| Settings | `ui/screen/settings/SettingsGroupDebug.kt` | `SettingsSwitch` gate DEBUG + `rememberSaveable` (padrão dos switches vizinhos) |
| Strings | `res/values*/strings.xml` | 4 chaves × 2 idiomas (quick_menu_latency_hud* + settings_debug_latency_hud*) |

## Desvios do spec

Nenhum — implementação conforme §1/§2.

## Verificação

- JVM/build: filtro `*Gamepad*` + `assembleModernDebug` → BUILD SUCCESSFUL (0 falhas).
- On-device (pendente — humano): QuickMenu→HUD liga/desliga o overlay sem adb;
  Settings→Debug persiste; prop `1` continua funcionando com pref OFF (OR).
