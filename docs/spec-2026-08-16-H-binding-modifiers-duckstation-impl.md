# Impl doc — Spec 2026-08-16 H (Modificadores por binding — port DuckStation)

**Data:** 2026-08-16
**Spec:** `docs/spec-2026-08-16-H-binding-modifiers-duckstation.md`
**Executor:** Prime Agent (fase H do master roadmap input avançado 2026-H2).
**Resultado:** implementado, gate completo verde (testes filtrados + assemble +
sync 2× determinístico). Verificação on-device PENDENTE (humano — §6 do spec).

## 1. O que foi feito (por seção do spec)

### §2.1 Modelo puro — `gamepad/processing/BindingModifiers.kt` (NOVO)

- `@Serializable data class BindingModifier(invert, fullAxis, scale, deadzone)` —
  todos null-default (file:26); `isDefault()` (file:38) define o token canônico.
- `object BindingModifiers.apply(value, mod)` (file:67): ordem FIXA fullAxis →
  invert → scale → deadzone, com as fórmulas do spec:
  - fullAxis `v * 0.5f + 0.5f` — fórmula EXATA do `InputModifier::FullAxis` do
    DuckStation (`reference/duckstation/src/util/input_manager.cpp:944-948`,
    "value * 0.5 + 0.5");
  - deadzone `|v| < dz ⇒ 0` SEM rescale (o rescale radial continua sendo do
    DeadzoneProcessor; aqui o zero é limiar do binding único — §2.1).
- Clamp dos limites em `apply`: scale 0.5..2.0 (`SCALE_MIN/SCALE_MAX`, file:47),
  deadzone 0.0..0.5 (`DEADZONE_MIN/DEADZONE_MAX`, file:51) — os percentuais do
  token clampam no decode e a UI restringe os sliders ao mesmo intervalo.
- `apply(value, null)` retorna o valor intacto — base da degradação
  byte-identical. KDoc cita as três fontes DuckStation (input_manager.h:59-95,
  input_manager.cpp:944-948, inputbindingdialog.cpp:42-57) — clean-room: só
  semânticas portadas, zero código copiado (padrão `MappingParser.kt`/`GyroProcessor.kt`).

### §2.2 Codec — `gamepad/remap/GamepadBindingCodec.kt`

- `LayerBinding(raw, turbo, mod = null)` (file:37) — campo novo com default
  (construções existentes inalteradas).
- `encode(binding, turbo, mod)` (file:44) + `encodeModSuffix` (file:64):
  `:m=<full,inv,s<%>,dz<%>>` — ordem canônica fixa; só campos não-default
  (full/inv só quando true; `s100`/`dz0` omitidos). Percentuais INTEIROS mantêm o
  round-trip estável (decode→encode byte-identical, testado). Sem mod ⇒ token
  IDÊNTICO ao formato atual.
- `decode` (file:81): o bloco `m=` é o ÚLTIMO campo `:` (vírgulas separam
  subcampos dentro dele — o split por `:` existente continua seguro); a base
  continua RÍGIDA (token inválido ⇒ null, comportamento atual).
- `decodeModSuffix` (file:117): LENIENTE — campos desconhecidos entre vírgulas
  IGNORADOS (política V1: nunca quebrar perfil futuro); percentuais fora da faixa
  CLAMPAM (s50..s200, dz0..dz50); tudo default ⇒ `mod == null`.

### §2.3 Aplicação — `gamepad/GamepadHub.kt`

- Pares eixo semântico → botão porta-token no companion (file:97): sticks usam
  `LEFT_STICK`/`RIGHT_STICK` como porta-token (o nome do EIXO não existe como chave
  de layers); triggers usam o próprio botão trigger. Resolução central em
  `bindingModsFor` (file:892): o mod vale quando o binding do token referencia o
  MESMO eixo físico de `mapping.axes[axis]`.
- `onAxis` (file:845): sem layers → caminho byte-identical, ZERO alocação extra
  (gate `profile.layers.isEmpty()`). Com layers, `LayerResolver.effectiveBindings`
  é resolvido UMA vez (mesmo custo que o remapEvent já paga por evento) e:
  - **FullAxis ANTES do pipeline** — `preApplyFullAxis` (file:915) converte o valor
    CRU centrado −1..1 → 0..1 (`v*0.5+0.5`) só para triggers (eixo semântico L/R
    TRIGGER ou a meia-eixo do botão trigger — SDL half-axis a4/a5);
  - **Botão lógico de eixo** — `buttonDeadzonesFor` (file:946) +
    `applyButtonDeadzone` (file:966): o limiar de conversão eixo→botão do
    EventTranslator passa a ser o dz do binding (hair trigger); sem mod o limiar
    0.5 atual permanece;
  - **Eixo (sticks/triggers) DEPOIS do processamento existente** — `applyAxisMods`
    (file:1005): `BindingModifiers.apply(v, mod.copy(fullAxis = null))` no
    AxisMotion (fullAxis já pré-aplicado no domínio cru; invert → scale → deadzone
    rodam por último — o override por-binding VENCE o global). Zero cai fora
    (mesmo contrato do `emitTrigger`).
- **Assinatura do hot path INTACTA**: `EventTranslator.translateAxis(raw, mapping,
  deadzones)` não mudou — o spec pede explicitamente "sem alterar a assinatura do
  hot path" e os três pontos de aplicação vivem no hub.

### §2.4 Perfil + catálogo

- NENHUM campo novo no `GamepadProfile` — os mods vivem no token (mapa de layers
  já é `Map<String, Map<String, String>>`); `isDefault()`/`merged()` inalterados.
- `ProfileCatalog.summaryOf` (file:153/162): modificadores nos tokens contam como
  categoria STICK (sem categoria nova), via `GamepadBindingCodec.decode(...)?.mod`.
- `tools/profiles/sync_profile_repo.py`: `binding_token_ok` (file:95) — o validador
  agora valida o FORMATO dos tokens (espelho do codec) e ACEITA o sufixo
  `:m=<full,inv,s<%>,dz<%>>`; token fora da gramática descarta a entry (file:210).
  Seed novo `tools/profiles/seed/hair-trigger-invert.json` (triggers com
  `axis:17:1:m=full,s130,dz5` e `axis:18:1:m=inv`) — o asset regenerado
  (`app/src/main/assets/profile-catalog.json`, 6 perfis) comprova o sufixo
  end-to-end. Determinismo: 2× runs → md5 `aa2c64e2c90f895900b9d0cbb3894805`
  idêntico (gate do spec).

### §2.5 UI — `gamepad/remap/GamepadRemapDialog.kt`

- Estado `modifierPanelFor` (file:120) — um painel aberto por vez; a abertura fecha
  capturas em curso e vice-versa (mutuamente exclusivos).
- Chip "⋯" na linha de binding (file:1477) — aceso quando o token carrega bloco
  `:m=` ou o painel está aberto. **Decisão (desvio documentado): o chip só aparece
  para binding de EIXO** — os mods operam em valores de eixo (§2.3); num binding
  Key/Hat o painel seria inerte e o DuckStation não tem esse caso (Hat não tem
  modificador — não-meta do spec).
- `setModifier` (file:377): re-encoda o token com `:m=` preservando o turbo; sem
  token ainda (DEFAULT mostrando o binding do mapping), MATERIALIZA o token do
  binding do mapping; mod default sem token ⇒ nada a fazer (nenhum token fantasma —
  o estado original é exatamente "sem entrada na camada").
- `BindingModifierPanel` (file:1503): Switch Inverter; Switch "Eixo completo
  (trigger)" SÓ para `Axis` alvo LEFT/RIGHT_TRIGGER; Sliders Sensibilidade 50–200%
  e Zona morta 0–50% (`GyroSliderRow` — gamepad-navegável com A-lock);
  Salvar/Cancelar (Salvar re-encoda via `onApply` → `setModifier`). Draft local
  resetado por `remember(binding)`.
- `BindingModifierSwitchRow` (file:1586): padrão gamepadSelectable + Switch sem
  foco próprio (mesmo padrão do toggle de Flick Stick do dialog).

## 2. Strings

6 chaves novas EN + pt-rBR (coladas em `gamepad_binding_turbo_title` nos dois
arquivos): `gamepad_binding_modifier_more` ("⋯"), `_title` ("Binding modifiers" /
"Modificadores do vínculo"), `_invert_title` ("Invert axis" / "Inverter eixo"),
`_full_axis_title` ("Full axis (trigger)" / "Eixo completo (gatilho)"),
`_scale_title` ("Sensitivity" / "Sensibilidade"), `_deadzone_title` ("Dead zone" /
"Zona morta").

## 3. Testes

- `BindingModifiersTest` (NOVO, 8 testes, file:1): null/default = identidade;
  fullAxis fórmula exata (−1→0, 0→0.5, 1→1); invert; scale; deadzone sem rescale
  (|v|==dz passa); ORDEM fullAxis→invert→scale→deadzone composta; clamp de
  scale/dz nos limites do spec.
- `GamepadBindingCodecTest` (estendido, 13 testes — 5 novos a partir de file:78):
  round-trip de cada mod isolado e combinado com turbo; token sem mod
  byte-identical ao atual; round-trip ESTÁVEL decode→encode (percentuais
  inteiros); decode ignora campo desconhecido (`future` preserva `inv`, s0 clampa
  em 50); base rígida com bloco `m=` presente; `m=` no meio do token não é bloco.
- `ProfileCatalogTest` (estendido, 10 testes): mods contam como STICK (BINDINGS +
  STICK); token sem `:m=` continua só BINDINGS.
- **EventTranslatorTest não estendido** — o override por-binding vive no HUB
  (Android-bound, não JVM-testável); a semântica do override (ordem de composição
  "último a aplicar") é coberta por `BindingModifiersTest` — caminho autorizado
  pelo spec §4 ("senão cobrir via BindingModifiers").

## 4. Gate (comandos e resultados)

```
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:testModernDebugUnitTest --tests "*Binding*" --tests "*Gamepad*" --offline
  → BUILD SUCCESSFUL (BindingModifiersTest 8/8, GamepadBindingCodecTest 13/13)
+ ProfileCatalogTest (10/10, extra — summaryOf mudou)
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:assembleModernDebug --offline
  → BUILD SUCCESSFUL
python3 tools/profiles/sync_profile_repo.py ×2 + md5 idêntico (aa2c64e2…)
  → determinístico; diff vazio com o asset commitado
```

## 5. On-device pendente (humano — spec §5)

1. **Trigger FullAxis num pad HID** (trigger reportando eixo centrado −1..1):
   perfil do seed `hair-trigger-invert` ou remap manual → `LEFT_TRIGGER =
   axis:17:1:m=full` — o gatilho deve varrer 0..1 a partir do eixo centrado.
2. **Sensibilidade 50% num stick doente**: bind `LEFT_STICK` (botão de clique)
   ao eixo do stick com `:m=s50` no dialog → o caminho lógico (menus/navigators)
   deve mover com metade da amplitude.
3. Hair trigger (dz 5%) na conversão eixo→botão do trigger.

## 6. Desvios do spec (registrados)

1. **Painel de modificadores só para binding de eixo** (§2.5): os mods têm ponto
   de aplicação somente em valores de eixo (§2.3); para Key/Hat o painel seria
   inerte. O DuckStation mostra scale/deadzone para Button/HalfAxis/Axis, mas lá a
   escala é aplicada na saída do binding (inclusive botões) — caminho que o spec H
   não manda portar (a aplicação do spec é só o pipeline de eixo do hub).
2. **EventTranslatorTest não estendido** (§4): o override vive no hub (Android);
   coberto via BindingModifiersTest conforme o próprio "senão" do spec.
3. **Aplicação do mod limitada ao caminho LÓGICO (bus)**: o spec H lista só
   GamepadHub.kt na tabela de arquivos — o caminho de INJEÇÃO do jogo
   (PhysicalControllerHandler, U4) continua sem mods nesta fase (o token do
   trigger continua dirigindo a injeção U4 como antes, sem os mods). É o escopo
   literal da tabela §3; se a revisão quiser os mods também na injeção, é um
   follow-up com o MESMO `BindingModifiers.apply`.
