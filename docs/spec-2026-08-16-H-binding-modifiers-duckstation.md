# Spec 2026-08-16 H — Modificadores por binding (port DuckStation)

**Data:** 2026-08-16
**Executor:** agente autônomo (Prime Agent). Leia `AGENTS.md` e o roadmap mestre
`docs/spec-2026-08-16-master-roadmap-input-avancado.md` (§1 loop, §2 regras) ANTES.
**Posição na fila:** fase H (primeira). Spec AUTOCONTIDO — pode ser delegado a
sub-agente com `await rlm("Implemente docs/spec-2026-08-16-H-... do repo /home/annapaula/GameNative. Siga o spec ao pé da letra; o gate no fim DEVE passar antes do commit.")`.
**Turn budget sugerido:** 20–25 turns.

## 0. Origem e onde ler a fonte

Feature EXCLUSIVA do DuckStation entre os references (nenhum outro projeto tem
modificador + tuning POR BINDING). Portar os SEMÂNTICAS, não o código (clean-room:
DuckStation é GPL-3 — reimplementar em Kotlin, citar como referência no KDoc, padrão
já usado pelo repo em `MappingParser.kt` com o SDL).

Leia (nessa ordem, só o necessário):
1. `reference/duckstation/src/util/input_manager.h` **linhas 59–95** — `InputBindingKey`
   + `enum InputModifier { None, Negate, FullAxis }` + bits `invert`. É o modelo:
   cada BINDING carrega seus modificadores independentes do global.
2. `reference/duckstation/src/util/input_manager.cpp` **linhas 944–988** —
   `ApplySingleBindingScale`: por binding, `{Name}Scale` multiplica o valor e
   `{Name}Deadzone` zera abaixo do limiar — INDEPENDENTE das configurações globais
   do pad.
3. `reference/duckstation/src/duckstation-qt/inputbindingdialog.cpp` **linhas 42–57** —
   os sliders por binding na UI (sensibilidade + deadzone dentro do dialog de captura).
4. Contexto (não portar): `src/core/controller_helpers.h` (`MergeHalfAxes` — o
   DuckStation funde meias-axes lá; no GameNative o equivalente é a direção ±1 do
   `RawBinding.Axis`, que JÁ existe).

## 1. Estado atual (anchors do fork)

- `gamepad/mapping/RawBinding.kt` — `Key(keyCode)`, `Axis(axis, direction ±1)`,
  `Hat(hat, mask)`. A direção ±1 do `Axis` JÁ cobre o "Negate" para STICKS.
- `gamepad/remap/GamepadBindingCodec.kt` — token do perfil:
  `key:<kc>` / `axis:<axis>:<dir>` / `hat:<hat>:<mask>` + sufixo opcional `:turbo`.
  `LayerBinding(raw, turbo)`. Round-trip testado.
- `gamepad/mapping/EventTranslator.kt` — `translateAxis(raw, mapping, deadzones)`:
  aplica `DeadzoneConfig` GLOBAL (leftStick/rightStick/triggers do perfil) no caminho
  de eixo; é onde o override por-binding entra.
- `gamepad/profiles/GamepadProfile.kt` — deadzones por STICK/TRIGGER do perfil
  (`leftStickDeadzone` etc.), NÃO por binding.
- Aplicação: `gamepad/GamepadHub.kt` `emitLogical`→`remapEvent` decodifica o token
  via `GamepadBindingCodec.decode(token)` (GamepadHub.kt:460) para remapear botão
  lógico → fonte física em camadas.

O que FALTA (este spec): (a) **FullAxis** — usar o eixo INTEIRO (−1..1) como um
trigger 0..1 (pads cujo gatilho reporta eixo centrado, ex.: ABS_BRAKE/ABS_THROTTLE
ou DualShock por HID); (b) **invert por binding** (trocar o sinal do eixo NAQUELE
binding sem afetar os outros usos do eixo); (c) **scale por binding** (50–200% —
sensibilidade fina daquele binding); (d) **deadzone por binding** (limiar daquele
binding, vencendo o global do stick/trigger).

## 2. Design

### 2.1 Modelo puro — `gamepad/processing/BindingModifiers.kt` (NOVO)

```kotlin
@Serializable
data class BindingModifier(
    val invert: Boolean? = null,          // null = false
    val fullAxis: Boolean? = null,        // null = false — só faz sentido em Axis
    val scale: Float? = null,             // null = 1.0 (0.5..2.0)
    val deadzone: Float? = null,          // null = sem override (0.0..0.5)
)

object BindingModifiers {
    // Ordem FIXA (documentada e testada): fullAxis → invert → scale → deadzone.
    fun apply(value: Float, mod: BindingModifier?): Float
    // fullAxis: v * 0.5f + 0.5f  (Fórmula exata do InputModifier::FullAxis do
    //   DuckStation — input_manager.cpp, "value * 0.5 + 0.5".)
    // invert:   -v
    // scale:    v * scale
    // deadzone: |v| < dz ⇒ 0 (sem rescale — o DeadzoneProcessor do repo já faz o
    //   rescale radial para sticks; AQUI o zero é limiar de binding único)
}
```

PURO (JVM-testável, zero android.*). `apply` com `mod == null` retorna `value`
intacto — base da degradação byte-identical.

### 2.2 Codec — sufixo no token

`GamepadBindingCodec.encode/decode` ganha parâmetro/campo `mod: BindingModifier?`.
Formato do sufixo (depois de `:turbo`, se houver): `:m=<full,inv,s<%>,dz<%>>` —
um ÚNICO campo `:` (o decode já separa por `:`; vírgulas separam subcampos DENTRO
do bloco). Exemplos: `key:96:m=inv`, `axis:17:1:m=full,s130,dz5`,
`axis:17:1:turbo:m=inv`.
- Encode: só escreve campos não-default (null/false/1.0 → omitidos). Sem
  modificadores ⇒ token IDÊNTICO ao atual (round-trip preservado).
- Decode: LENIENTE — campos desconhecidos entre vírgulas são IGNORADOS (política V1
  do store: nunca quebrar perfil futuro). Token inválido ⇒ null (comportamento atual).
- `LayerBinding(raw, turbo, mod = null)` — campo novo com default.

### 2.3 Aplicação — onde o valor passa

- **Eixo (sticks/triggers):** `EventTranslator.translateAxis` hoje recebe
  `DeadzoneConfig` global. Sem alterar a assinatura do hot path: o hub resolve o
  modificador DO BINDING daquele eixo (o `mapping.axes` + override de camada via
  `layerBindingFor`) e aplica `BindingModifiers.apply` DEPOIS do processamento
  existente do eixo — o override por-binding VENCE o global (último a aplicar).
  `mod == null` = caminho atual exato.
- **Botão lógico de eixo** (trigger como botão): no `EventTranslator`, o limiar de
  conversão eixo→botão usa `deadzone` do binding quando presente.
- **FullAxis:** aplica-se SOMENTE quando o binding `Axis` alimenta um
  `GamepadAxis.LEFT_TRIGGER/RIGHT_TRIGGER` (ou botão lógico trigger) — o eixo
  centrado −1..1 vira 0..1 antes do resto do pipeline.

### 2.4 Perfil + catálogo

- Os modificadores vivem NO TOKEN do binding (dentro de `layers` do
  `GamepadProfile`) — NENHUM campo novo no `GamepadProfile` (o mapa de layers já é
  `Map<String, Map<String, String>>`; o token carrega o payload). `isDefault()` e
  `merged()` inalterados.
- `tools/profiles/sync_profile_repo.py`: a allowlist valida o FORMATO dos tokens —
  se ela rejeitar o sufixo `|m=`, ensinar o parser do validador a aceitar
  (regenerar e confirmar determinismo: 2× → diff vazio).
- `ProfileCatalog.summaryOf`: contabilizar modificadores como categoria STICK
  (já existente) — sem categoria nova.

### 2.5 UI — `GamepadRemapDialog.kt`

Na linha de binding capturado (onde hoje aparece o token + turbo), botão "⋯"
abre painel colapsável do binding: Switch "Inverter", Switch "Eixo completo
(trigger)" (só visível para `Axis` alvo trigger), Slider "Sensibilidade" 50–200%,
Slider "Zona morta" 0–50%. Salvar re-encoda o token com `:m=...`. Gamepad-navegável
(padrão `gamepadSelectable` + `GamepadFocusScope` do dialog). Strings EN + pt-rBR.

## 3. Arquivos

| Arquivo | Mudança |
|---|---|
| `gamepad/processing/BindingModifiers.kt` | NOVO (2.1, puro) |
| `gamepad/remap/GamepadBindingCodec.kt` | sufixo `:m=` encode/decode leniente (2.2) |
| `gamepad/mapping/EventTranslator.kt` | aplicação pós-pipeline + limiar botão (2.3) |
| `gamepad/GamepadHub.kt` | resolve mod do binding efetivo e aplica (2.3) |
| `gamepad/remap/GamepadRemapDialog.kt` | painel de modificadores por binding (2.5) |
| `tools/profiles/sync_profile_repo.py` | aceitar sufixo no validador (2.4) |
| `app/src/test/.../BindingModifiersTest.kt` + `GamepadBindingCodecTest` | NOVO/estendido (§4) |
| `res/values*/strings.xml` | chaves EN + pt-rBR |

## 4. Testes (JVM, puros)

- `BindingModifiersTest`: null = identidade; fullAxis (−1→0, 0→0.5, 1→1 — fórmula
  exata do DuckStation); invert; scale; deadzone (abaixo zera, acima passa); ordem
  das operações (full→inv→scale→dz compõe corretamente); clamp do scale/dz.
- `GamepadBindingCodecTest` (estender): round-trip com cada modificador e
  combinados; token sem mod = byte-identical ao atual; decode ignora campo
  desconhecido (`:m=future,inv` preserva inv); token malformado ⇒ null.
- `EventTranslatorTest` (estender, se existir; senão cobrir via BindingModifiers):
  override por-binding vence o global.

## 5. Gate

```
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:testModernDebugUnitTest --tests "*Binding*" --tests "*Gamepad*" --offline
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:assembleModernDebug --offline
python3 tools/profiles/sync_profile_repo.py && python3 tools/profiles/sync_profile_repo.py && git diff --exit-code -- tools/profiles/
```
On-device pendente (humano): trigger FullAxis num pad HID; sensibilidade 50% num
stick doente.

## 6. Não-metas

Chords/suppression (é o spec J), remap de axis→axis arbitrária, hats com
modificador, tocar em `XServerScreen.kt` (nada deste spec chega lá), migrar a UI
de captura de binding.

## 7. Critério de conclusão (para o goal)

Gate verde + commit `feat(gamepad): modificadores por binding — FullAxis/invert/scale/deadzone no token (spec 2026-08-16-H-binding-modifiers-duckstation)` + impl doc `docs/spec-2026-08-16-H-binding-modifiers-duckstation-impl.md` (evidências file:line) + checkpoint na tabela §6 do roadmap mestre.
