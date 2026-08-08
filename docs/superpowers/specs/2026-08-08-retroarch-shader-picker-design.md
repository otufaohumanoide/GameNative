# RetroArch Shader Picker — UX redesign (2026-08-08)

> **Problema:** o painel de efeitos lista os 131 presets embarcados como uma lista plana e sem
> estrutura (`ScreenEffectsPanel.kt` ~linha 800: `shaderOptions.forEach`). O usuário não consegue
> achar nada: nomes de arquivo, sem separação por família, sem informação sobre o que cada preset
> faz. (A referência de UI "boa" é o picker por famílias do ARMSX2 — ver
> `docs/ARMSX2-librashader-vulkan.md` §3.5.)

## Design

### 1. Agrupamento por categoria (família de shaders)
- Os presets vivem em subpastas de `assets/retroarch/` (`crt/`, `misc/`, `film/`, `cel/`, `hdr/`,
  `ntsc/`, `interpolation/`, …) — o `categoryOf(key)` já extrai o 1º segmento.
- A lista vira **seções com cabeçalho de categoria**: nome amigável + contagem + chevron
  (colapsável por toque, estado em memória por sessão).
- Ordem fixa e legível: `crt`, `lcd`, `interpolation`, `misc`, `film`, `cel`, `hdr`, `ntsc`,
  `reshade`, demais em ordem alfabética, `outros` (presets na raiz) por último.

### 2. Nomes amigáveis de categoria
- Mapa `crt -> CRT`, `lcd -> LCD`, `interpolation -> Upscaling`, `ntsc -> NTSC / Composite`,
  `hdr -> HDR`, `misc -> Effects & Misc`, `film -> Film`, `cel -> Cel Shading`,
  `reshade -> ReShade`, `nearest/bilinear -> Scaling`, `stock -> Stock`.

### 3. Contagem de passes por preset
- Cada linha mostra `N pass(es)` no subtitle, junto da categoria.
- `passCountOf(File)`: lê o `.slangp`, soma entradas `shaderN = ...` (não comentadas); segue
  `#reference <path>` (1 nível) e soma os passes do arquivo referenciado. Cacheado em memória
  (calculado 1x no IO, não por frame).

### 4. Busca
- Campo de texto "Search presets…" acima da lista (visível sempre que shaders estão ligados).
- Filtra por nome amigável, categoria ou caminho (case-insensitive). Quando há busca ativa, a
  lista vira plana (sem seções) e o subtitle mostra `categoria · N passes`.

### 5. Estado atual
- Mantém "No filter" no topo; o preset ativo continua destacado (fundo + check) e o toggle
  principal mostra o nome do preset ativo. Ao ligar/desligar nada muda de comportamento.

## Checklist de implementação

| # | Arquivo | Mudança | Aceite |
|---|---|---|---|
| 1 | `docs/superpowers/specs/2026-08-08-retroarch-shader-picker-design.md` | este doc | — |
| 2 | `app/src/main/java/app/gamenative/ui/component/dialog/RetroArchShaderDialog.kt` | `friendlyCategoryName(cat)` + `passCountOf(file)` + `resolvePassCount(presetKey, bundledDir)` | compila; count correto p/ `misc/invert` (1), `film/technicolor` (2), presets com `#reference` |
| 3 | `app/src/main/java/app/gamenative/ui/component/ScreenEffectsPanel.kt` | seção shader: busca + seções colapsáveis + subtitle `categoria · N passes` | compila; lista agrupada; busca filtra; chevron colapsa |
| 4 | Build + device | `assembleModernDebug` + install | sem crash ao abrir o painel; seções visíveis |

## Fora de escopo
- Editor de parâmetros de shader (Tarefa 4 do doc ARMSX2 — sessão futura).
- Download de packs de shaders (UI do ARMSX2 — sessão futura).
- Preview visual em tempo real dos presets.
