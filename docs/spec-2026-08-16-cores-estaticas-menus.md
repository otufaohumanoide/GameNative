# Spec 2026-08-16 — Cores estáticas em menus internos (hierarquia de cor)

**Data:** 2026-08-16
**Origem:** relato do usuário — o QuickMenu usava fundos com gradientes de 2
stops, quando a linguagem do projeto reserva GRADIENTES para a tela principal
(Library) e scrims de artwork; menus internos usam cores ESTÁTICAS da escada de
elevação do tema.

## 0. Regra de hierarquia (evidências)

| Superfície | Regra | Evidência |
|---|---|---|
| Tela principal (Library) | Gradientes liberados | `LibraryTabBar.kt:153,437`, `LibraryDynamicBackdrop.kt:53`, `LibraryGridCard.kt:253,418` |
| Menus internos | Cores estáticas da escada | `SettingsScreen.kt:263-270` — row focada = `PluviaTheme.colors.surfaceElevated` estático; escada em `Color.kt` (`Background → Surface → SurfaceElevated → Secondary`) |

## 1. Mudanças

Fundo de foco/não-foco das rows dos menus internos:
- Focado: `accent.copy(alpha = 0.12f)` ESTÁTICO (média visual dos gradientes
  0.16→0.08 / 0.15→0.05 removidos).
- Não-focado: `surfaceVariant.copy(alpha = 0.14f)` estático (média de
  0.18→0.10) onde existia fundo neutro; `Color.Transparent` onde o gradiente
  já era transparente-transparente.

Pontos alterados:
- `QuickMenu.kt`: `QuickMenuSelectableRow`, `QuickMenuToggleRow`, row de
  chips/disabled (3 blocos).
- `ShaderBrowserOverlay.kt`: rows do browser (2 blocos, incluindo o back-row).
- `GamepadSearchField.kt`: fundo do campo (1 bloco).
- KDocs documentam a regra em cada ponto (evita regressão).
- Imports `Brush` órfãos removidos nos 3 arquivos.

O destaque do foco NÃO enfraquece: o ring animado + overlay do
focus-feedback-v2 continuam responsáveis pela sinalização (o gradiente era
redundante a eles).

## 2. Verificação

- Gate: `--tests "*Gamepad*"` + `assembleModernDebug` + install no device
  conectado (inspeção visual das telas QuickMenu/browser/busca).

## 3. Fora de escopo

Gradientes da Library (regra os permite), scrims sobre artwork dos dialogs
(`ProfileDialog`, `GameManagerDialog` etc. — legítimos).
