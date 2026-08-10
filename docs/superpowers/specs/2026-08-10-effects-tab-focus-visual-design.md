# Visual de foco na aba EFFECTS (lista de shaders) — design (2026-08-10)

> **Problema:** com o joystick PS4, o usuário navega pelo stick pelas opções do QuickMenu.
> Na aba EFFECTS (lista de shaders) **não há nenhum feedback visual** de qual row está
> focada; a ativação (A) funciona perfeitamente — o problema é exclusivamente visual.
> Nas demais abas (HUD, Controller, TOOLS, INVITE) o feedback funciona perfeitamente.
>
> **Causa raiz:** as rows da lista de shaders (`ShaderPresetRow`, `ShaderCategoryHeader`,
> `NativeEffectsHeader` e o campo de busca) **não aplicam a linguagem visual de foco**
> (tint de fundo + título accent/SemiBold via `isFocused`) que todas as outras rows do
> QuickMenu usam. Elas dependem apenas do anel animado do framework — imperceptível no
> device (2 dp, `PluviaPrimary` #A21CAF sobre superfície escura #12121A, rotação de 5 s).
> Nas rows onde o feedback funciona (toggles, radios, ajustes), o que o usuário percebe é
> o tint de fundo + mudança de cor/peso do texto — dirigidos por `collectIsFocusedAsState`.
>
> **Escopo (decisão do usuário):** somente a aba EFFECTS do QuickMenu
> (`ScreenEffectsPanel.kt` — conteúdo Vulkan; o caminho GL não tem lista de shaders e
> reutiliza as mesmas row composables, logo é coberto automaticamente). Replicar
> exatamente a linguagem visual já em uso nas outras abas. **Nenhuma mudança de
> comportamento/ativação** — apenas visual.
>
> **Base:** linguagem visual de foco do spec `2026-08-09-quickmenu-joystick-navigation-design.md`
> (D7, §3.2 — `GamepadFocusState.Focused/Selected/Locked` + tints por row).

## 1. Contexto & princípios

- **Consistência (Norman):** uma única linguagem visual de foco em todo o QuickMenu —
  row focada = tint de fundo (gradiente accent 0.16→0.08) + título em accent/SemiBold.
- **Seleção ≠ foco:** a row de shader aplicado (`selected`) continua com seu check +
  título accent; o foco adiciona o tint + peso, sem confundir os dois estados.
- **Zero mudança de lógica:** ativação, navegação, remember-selection e foco-trap já
  funcionam na aba EFFECTS — este spec só torna o foco visível.
- **Padrão de referência (provado no device):** `ScreenEffectToggleRow`
  (ScreenEffectsPanel.kt:1246-1307), `QuickMenuItemRow` (QuickMenu.kt:2454-2495).

## 2. Estado atual (verificado)

| Row (ScreenEffectsPanel.kt) | Visual de foco hoje | Problema |
|---|---|---|
| `ShaderPresetRow` (:1439) | apenas o anel do `gamepadSelectable`; fundo (:1456) e título (:1485) só distinguem `selected` | sem tint, sem mudança de texto |
| `ShaderCategoryHeader` (:1386) | apenas o anel; sem background, label sempre `primary` | sem tint, sem mudança de texto |
| `NativeEffectsHeader` (:1333) | apenas o anel; ícone/título sempre `onSurfaceVariant`/`onSurface` | sem tint, sem mudança de texto |
| Campo de busca (`NoExtractOutlinedTextField`, :717-756) | nenhum — `searchFieldFocused` já é rastreado via `onFocusChanged` (:745) mas não tem efeito visual | campo invisível na navegação |

O anel animado (GamepadFocus.kt:94-173) é aplicado por `gamepadSelectable` em todas as
rows — igual nas abas que funcionam — logo **não é alterado**.

## 3. Design

### 3.1 `ShaderPresetRow` (presets + "No filter")

```kotlin
val accentColor = PluviaTheme.colors.accentCyan  // já existe (:1448)
// background (:1456):
when {
    isFocused -> Brush.horizontalGradient(
        colors = listOf(accentColor.copy(alpha = 0.16f), accentColor.copy(alpha = 0.08f)),
    )
    selected -> accentColor.copy(alpha = 0.15f)   // mantém
    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)  // mantém
}
// título (:1485):
color = if (selected || isFocused) accentColor else MaterialTheme.colorScheme.onSurface
fontWeight = if (selected || isFocused) FontWeight.SemiBold else FontWeight.Medium
```

Subtitle permanece `onSurfaceVariant` (padrão de `ScreenEffectToggleRow`).

### 3.2 `ShaderCategoryHeader`

- Adicionar background após `.clip(...)`: focada → `primary` gradiente 0.14→0.07; senão
  transparente (nenhum fundo hoje).
- Label: `fontWeight = if (isFocused) SemiBold else Medium` (cor `primary` mantida).

### 3.3 `NativeEffectsHeader`

- Adicionar background após `.clip(...)`: focada → `primary` gradiente 0.14→0.07.
- Título: `fontWeight = if (isFocused) SemiBold else Medium`.
- Ícone de colapso: tint `if (isFocused) MaterialTheme.colorScheme.primary
  else onSurfaceVariant`.

### 3.4 Campo de busca

Envolver o `NoExtractOutlinedTextField` (ScreenEffectsPanel.kt:739) num container com
`background` tint quando `searchFieldFocused` e aplicar `OutlinedTextFieldDefaults.colors`
com `focusedBorderColor` accent + `unfocusedBorderColor` padrão, para o campo mostrar o
foco mesmo quando o IME é suprimido (navegação por stick).

## 4. Arquivos afetados

| Arquivo | Mudança |
|---|---|
| `ui/component/ScreenEffectsPanel.kt` | `ShaderPresetRow` (:1456/:1485/:1486), `ShaderCategoryHeader` (:1398-1415), `NativeEffectsHeader` (:1343-1383), campo de busca (:717-756) — visual de foco |

**Novos arquivos:** nenhum.

**Testes:** JVM tests não cobrem visual (nenhuma infraestrutura de UI test no projeto);
verificação manual no device (T2 do spec anterior): navegar a lista de shaders com o stick
do PS4 e confirmar tint + título accent em presets/cabeçalhos/busca.

## 5. Aceite (device, joystick PS4)

| # | Tarefa | Critério |
|---|---|---|
| V1 | Abrir EFFECTS e descer a lista de shaders | cada `ShaderPresetRow` focada mostra gradiente cyan + título cyan/SemiBold |
| V2 | Passar pelos cabeçalhos de categoria | `ShaderCategoryHeader` focada mostra tint + label SemiBold |
| V3 | Colapsar/expandir categoria | `NativeEffectsHeader` focada mostra tint + ícone/título accent |
| V4 | Stick desce até o campo de busca | container do campo mostra tint + borda accent quando focado |
| V5 | Aplicar preset com A | comportamento inalterado; row `selected` mantém check + cyan |
| V6 | Regressão nas outras abas | HUD/Controller continuam com feedback atual (nenhuma mudança) |

## 6. Fora de escopo

- Anel animado global (biblioteca, demais abas) — funciona nas abas já verificadas.
- Outras abas do QuickMenu, diálogos, modo de edição de controles.
- Qualquer mudança de navegação/ativação/remember-selection.
