# Spec 2026-08-12 — Shader closure fix: LUT fora do closure + self-heal UX

## Sintoma (regressão reportada)

"Seleciono o shader, faço download e ele não é aplicado ao jogo" — o download do
preset completava, mas o shader nunca aparecia na tela. Regressão percebida após a
série de UX do browser de shaders (2026-08-12).

## Causa raiz

`film/technicolor.slangp` (upstream libretro/slang-shaders, commit pinado `a7f04a06`)
tem uma linha de texturas MALFORMADA com aspa não terminada:

```
textures = "SamplerLUT;noise1;
```

O parser do `sync_slang_shaders.py` usava `KEY_VALUE`
(`^\s*([A-Za-z0-9_]+)\s*=\s*(?:"([^"]*)"|([^\s]+))\s*$`). Nessa linha o grupo
quotado falha (sem aspa de fechamento) e o fallback `([^\s]+)` capturava o token
**com a aspa líder**: `"SamplerLUT;noise1;`. O split em `;` produzia o nome de
textura `"SamplerLUT` (com aspa) — que nunca casava com a key `SamplerLUT`
(`elif key in texture_names`). Resultado: a textura LUT (`cmyk-16.png`) ficava FORA
dos `deps` do preset no `catalog.json`.

Consequência em cadeia:
1. O download por-preset baixava a closure INCOMPLETA (sem a LUT).
2. `loadRetroArchShaderPreset` falhava no chain create do librashader **silenciosamente**.
3. UI mostrava o preset como baixado/ativo, mas nada era aplicado.

Presets afetados pela mesma classe de bug (linhas de textura malformadas):
`film/technicolor`, `crt/hyllian` (LUTs Sony_Wega), `downsample/multiLUT`
(grade-composite/rgb), `pal` (nes_lut) e mais um com grade-composite/rgb.

## Correções

### 1. Parser de textures endurecido (`tools/shaders/sync_slang_shaders.py`)

- Nova regex dedicada `TEXTURES_LINE = ^\s*textures\s*=\s*(.*)$` captura o RESTO DA
  LINHA cru, antes do `KEY_VALUE` — tolerante a aspas não terminadas, espaços após
  separadores e comentários inline.
- Parse: `replace('"', '').split('#')[0].rstrip(';').split(';')` + strip + filtro de
  vazios; token com whitespace interno gera WARNING (nunca drop silencioso).
- Acumulação de múltiplas linhas `textures` (semântica de união, como antes).
- `catalog.json` regenerado: technicolor ganhou `cmyk-16.png` (bytes 49592→54665);
  outros 4 presets recuperaram LUTs; `packBytes` inalterado (LUTs compartilhadas).

### 2. Resolução closure-aware (`ShaderConfigStore.kt`)

- `resolveShaderConfig(config, packDir, catalog)` agora verifica `closureComplete`:
  o preset só é considerado carregável quando TODOS os arquivos de `deps` estão no
  cache. Closure incompleta resolve para "seleção visível, nada carregado" (§6) —
  a classe do bug (chain create falho silencioso) vira impossível.
- Fallbacks: catalog ausente ou preset desconhecido → comportamento antigo
  (existência do arquivo).
- Call sites: `ShaderSectionState` (menu) e `XServerScreen` (boot, que também
  persiste o path limpo).

### 3. Self-heal UX (`ShaderToggleSubtitle.kt` + `ScreenEffectsPanel.kt`)

- Nova função pura `shaderToggleSubtitle(enabled, name, path)` (padrão
  ShaderDoubleClickLogic/GamepadStickLogic) com 4 estados: `ActivePreset`,
  `SelectedNotDownloaded`, `PickPreset`, `Off`.
- EFFECTS tab: preset com closure incompleta mostra "Não baixado — re-selecione no
  navegador" (EN: "Not downloaded — re-pick in browser") em vez do nome do preset
  como se estivesse ativo. Re-pick no browser baixa SÓ os arquivos faltantes.

## Testes

- `ShaderToggleSubtitleTest`: 4 casos.
- `ShaderConfigResolveTest`: +4 casos closure-aware (incompleta não carrega /
  não re-resolve, completa carrega, sem catálogo = legado).

## Verificação

- `:app:testModernDebugUnitTest --tests "*Shader*"` verde (sem warnings Kotlin).
- Re-sync do catálogo determinístico (mesma saída em 2 execuções; 7 broken presets
  inalterados).
- On-device pendente: cenário de self-heal (technicolor baixado com catálogo antigo
  → indicador "não baixado" → re-pick baixa só a LUT → aplica).
