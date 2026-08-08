# Effects tab reorder — RetroArch shaders first (2026-08-08)

> **Problema (feedback do usuário):** para trocar um shader é preciso: abrir a sidebar → botão
> "varinha mágica" (ScreenEffectsPanel) → **arrastar até o fim** da lista. O painel abre com
> DisplayBrightness, Scaling/FSR, Brightness/Contrast/Gamma, Toon/FXAA/Vivid/CRT/NTSC e Reset —
> os **RetroArch Shaders ficam por último** (linha ~791 de `ScreenEffectsPanel.kt`). As seções
> colapsáveis por categoria já reduziram a rolagem interna, mas a seção inteira ainda está no fim.

## Design (objetivo: reduzir toques para chegar aos shaders)

1. **RetroArch Shaders vira a PRIMEIRA seção do painel** (0 rolagem ao abrir): toggle + busca +
   lista agrupada sobem para o topo do `Column` do painel (hoje em `DisplayBrightnessRow`).
2. **Efeitos nativos entram numa seção colapsável "Renderer-native effects"** (padrão:
   **recolhida**), com chevron + subtitle que expressa a ontologia do projeto: *"Built into the
   renderer · complement RetroArch shaders · FSR · Scaling · Toon · FXAA · Vivid · CRT · NTSC"*.
   A semântica é **complementaridade, não herança**: FSR/DLS/scaling/brilho/FXAA são tecnologias
   implementadas no próprio renderer Vulkan e **coexistem** com os shaders (ex.: não há FSR no
   RetroArch — são famílias diferentes de processamento de imagem). Um toque expande. Nada é
   removido — apenas deprioritizado na hierarquia visual.
3. **Reset** permanece dentro da seção legada (ele reseta os efeitos legados).
4. **Foco de controle**: `firstItemFocusRequester` passa para o toggle de RetroArch Shaders
   (novo param opcional `focusRequester` em `ScreenEffectToggleRow`) — ao abrir o painel, o
   controle cai direto no toggle de shaders.
5. Divisor entre as duas seções mantido; espaçamento final inalterado.

## Checklist

| # | Arquivo | Mudança | Aceite |
|---|---|---|---|
| 1 | `docs/superpowers/specs/2026-08-08-effects-tab-reorder-design.md` | este doc | — |
| 2 | `ScreenEffectsPanel.kt` | mover bloco shaders para o topo do Column; envolver bloco nativo em `NativeEffectsHeader` colapsável (default collapsed) + estado `nativeEffectsExpanded` | painel abre mostrando shaders; nativos recolhidos; 1 toque expande |
| 3 | `ScreenEffectToggleRow` | param opcional `focusRequester` | foco inicial cai no toggle de shaders |
| 4 | Build + device | `assembleModernDebug` + install | sem crash; painel abre na seção de shaders |

## Fora de escopo
- Remover efeitos legados (devem ser mantidos — vieram do fork original).
- Mudar o fluxo da sidebar/botão da varinha.
- Ordenação interna da lista de presets (já feita no spec do picker).
