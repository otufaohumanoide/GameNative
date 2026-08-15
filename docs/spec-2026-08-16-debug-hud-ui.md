# Spec 2026-08-16 — Debug HUD de latência ligável pela UI (sem adb)

**Data:** 2026-08-16
**Origem:** relato do usuário (HUD de latência F0 ficou ligado por propriedade de
sistema e não havia como desligar sem adb) — o overlay de debug deve ser
controlável pela UI para testadores que não usam `setprop`.
**Escopo:** apenas o HUD de latência (`LatencyDebugOverlay`) — `sensortrace`/
`GamepadTrace` são logcat-only (não renderizam) e ficam fora.

## 0. Estado anterior

- `LatencyDebugOverlay` mostrava quando `debug.gamenative.latency == "1"`
  (propriedade lida por poll de 500 ms via `DebugPropertyCache`); gate
  `BuildConfig.DEBUG`; `LatencyTracker.enabled` seguia a mesma condição.
- Sem toggle de usuário; desligar exigia `adb shell setprop`.

## 1. Design

1. `PrefManager.debugLatencyHudEnabled: Boolean` (default false) — interruptor de
   USUÁRIO. A propriedade continua valendo para automação/harness: o overlay
   mostra com **`prop == "1" || pref`**; `LatencyTracker.enabled` segue o MESMO
   OR (desligar pela UI também para a coleta).
2. QuickMenu → tab HUD: `QuickMenuToggleRow` "HUD de latência de input" abaixo do
   toggle do Performance HUD, gate `BuildConfig.DEBUG` (em release seria morto).
   Estado LOCAL na `PerformanceHudQuickMenuTab` (`remember` + pref) — sem
   QuickMenuAction e sem estado novo no XServerScreen (o overlay lê o pref via
   poll, ≤500 ms; sem efeito colateral de view para o host).
3. Settings → Debug: `SettingsSwitch` "HUD de latência de input" (gate DEBUG)
   com subtítulo explicativo — persistência no mesmo pref.

## 2. Arquivos afetados

| Arquivo | Mudança |
|---|---|
| `PrefManager.kt` | `debugLatencyHudEnabled` (1) |
| `ui/component/LatencyDebugOverlay.kt` | OR pref ∥ prop (1) |
| `ui/component/QuickMenu.kt` | toggle row na tab HUD + estado local (2) |
| `ui/screen/settings/SettingsGroupDebug.kt` | switch gate DEBUG (3) |
| `res/values*/strings.xml` | 4 chaves (EN + pt-rBR) |

## 3. Verificação

- Gate: `--tests "*Gamepad*"` + `assembleModernDebug`.
- On-device (humano): QuickMenu → HUD → ligar → overlay aparece em ≤1 s sem
  adb; desligar → some; Settings→Debug persiste entre sessões; com prop `1`
  (harness) o overlay continua funcionando mesmo com pref OFF.

## 4. Fora de escopo

Toggles para `sensortrace`/`GamepadTrace` (logcat-only), UI do harness de
injeção sintética, HUD de latência em builds release.
