# Categorias de shaders fechadas por padrão no QuickMenu — design (2026-08-10)

> **Problema:** no QuickMenu (menu aberto com o jogo rodando), aba EFFECTS, o seletor de
> shaders RetroArch agrupa os presets por categoria (CRT, LCD, interpolation, NTSC, etc.)
> em seções expansíveis. Hoje **todas as categorias nascem abertas por padrão**, o que
> estoura a lista e dificulta achar o shader desejado.
>
> **Escopo (decisão do usuário):** somente o QuickMenu (`ScreenEffectsPanel.kt` — conteúdo
> Vulkan; o caminho GL não tem lista de shaders). Todas as categorias devem **vir
> fechadas por padrão** e, por decisão do usuário, **sempre fechadas a cada abertura** do
> menu — expandir só vale enquanto o menu fica aberto.

## 1. Estado atual (verificado)

- `ScreenEffectsPanel.kt:600` — `collapsedCategories` inicia como set vazio ⇒ tudo aberto.
- `ScreenEffectsPanel.kt:798-831` — presets agrupados por `categoryOf(key) ?: "outros"`,
  renderizados na ordem `shaderCategoryOrder` (:1340); cada seção mostra os itens
  apenas se `cat !in collapsedCategories`; o header (`ShaderCategoryHeader`, :1435)
  recebe `collapsed` para inverter a seta.
- O painel inteiro está dentro de `AnimatedVisibility` (`QuickMenu.kt:605`): ao fechar o
  menu, a composição é descartada ⇒ o estado `remember` reseta naturalmente a cada
  abertura. Portanto, basta inverter o **default** para obter "sempre fechadas ao abrir"
  sem reset manual.

## 2. Solução escolhida

**Inverter a semântica do estado:** substituir `collapsedCategories` (set de colapsadas,
default vazio = tudo aberto) por `expandedCategories` (set de expandidas, default vazio
= tudo fechado).

- Abrir o menu ⇒ tudo fechado por padrão, sem depender do carregamento assíncrono dos
  presets (`LaunchedEffect(Unit)`, :605).
- Clicar no header expande/colapsa exatamente como hoje (toggle da membership no set).
- Categorias novas (ex.: shaders importados durante a sessão) nascem fechadas.
- Busca ativa continua achatando a lista (comportamento inalterado).
- Sem impacto no sistema de foco do gamepad (índices sequenciais por composição).

## 3. Não-escopo

- Sem auto-expandir a categoria do shader atualmente selecionado (o preset ativo já
  aparece no subtítulo do toggle principal).
- Sem persistência do estado expandir/colapsar entre aberturas (decisão do usuário:
  sempre fechadas ao abrir).
- Sem mudanças no caminho GL (`GLScreenEffectsTabContent` não tem lista de shaders).
- Sem mudanças no `RetroArchShaderDialog` (settings de container — fora do QuickMenu).

## 4. Alterações

| Arquivo | Mudança |
|---|---|
| `ScreenEffectsPanel.kt:600` | `collapsedCategories` → `expandedCategories`, default `emptySet()` |
| `ScreenEffectsPanel.kt:804-818` | ler `expanded` = `cat in expandedCategories`; toggle da membership |
