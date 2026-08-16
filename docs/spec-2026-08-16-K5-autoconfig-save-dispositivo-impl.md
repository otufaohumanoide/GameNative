# Impl doc — Spec 2026-08-16 K5 (autoconfig por device — "Salvar perfil deste controle")

**Data:** 2026-08-16
**Spec:** `docs/spec-2026-08-16-K5-autoconfig-save-dispositivo.md` (executor:
sub-agente autônomo; fase K5 do master roadmap universal input)
**Resultado:** implementado; gate completo verde (tests `*Autoconfig*
*DeviceMapping* *Gamepad*` = 126 testes, 0 falhas + `assembleModernDebug`);
commit `feat(gamepad): …` na §6. Verificação on-device pendente (protocolo humano
na §5).

## 1. O que foi feito (por seção do spec)

### §1.1 `DeviceMappingStore` + `DeviceAutoconfig` (formato próprio, política V1)

- `gamepad/mapping/DeviceAutoconfig.kt` (NOVO): `@Serializable data class
  DeviceAutoconfig` (file:line 28) com EXATAMENTE o contrato do spec —
  `mappingKey`/`deviceName` (display only)/`mapping: GamepadMapping`
  (RAW→LÓGICO)/`faceStyle`/`createdAtMs`/`schemaVersion: Int = 1` (default →
  arquivo sem o campo decodifica como 1; teste `schemaVersion defaults to 1 when
  absent from the file`). KDoc de atribuição clean-room (file:lines 4-26):
  RetroArch GPL-3 `reference/RetroArch/configuration.c:8137`
  (`config_save_autoconf_profile`) + `:8206-8233` (validação) +
  `tasks/task_autodetect.c:163` (affinity) — semânticas reimplementadas em
  Kotlin, NUNCA copiar código. O KDoc também registra o NÃO-confundir do spec
  §0: camada de BAIXO, não o `GamepadProfile` lógico do `GamepadProfileStore`;
  e o "não misturar formatos" do §1.4 (K6 consumirá o store, formato SDL).
- `gamepad/mapping/DeviceMappingStore.kt` (NOVO): um JSON por device em
  `<filesDir>/deviceMappings/<mappingKey>.json` — `fileFor` (file:line 88);
  API exata do spec: `load` (file:line 34), `save` (file:line 51), `delete`
  (file:line 75), `list` (file:line 82, ordenada por chave — futura tela de
  gestão; o card usa load/save/delete). Write atômico tmp+rename (file:lines
  68-74, padrão `GamepadProfileStore`); malformado degrada a vazio e se recupera
  no próximo save.
- **Política V1 (obrigatória)**: `Json { ignoreUnknownKeys = true }` (file:line
  91); `KNOWN_FIELDS` derivado do descriptor do `DeviceAutoconfig.serializer()`
  (file:lines 94-97); `save` LÊ o arquivo antes do write (file:line 55 — o
  `load` garante o raw no `rawCache` mesmo quando o save vem sem load prévio) e
  re-injeta as chaves desconhecidas no arquivo salvo (file:lines 60-64).
  Testes: `V1 - save preserves unknown keys from newer builds`, `V1 - unknown
  keys preserved across multiple saves`, `V1 - delete removes the unknown keys
  too`.
- Serialização dos tipos do mapping: `@Serializable` em `GamepadMapping.kt:8`,
  `RawBinding.kt:15,17,19,21` (sealed Key/Axis/Hat), `FaceStyle.kt:10`,
  `GamepadAxis.kt:9`, `GamepadButton.kt:10` (chaves enum serializam por nome —
  NUNCA por ordinal; o autoconfig sobrevive a reordenação de enum).
- Cache em memória por instância (mesmo padrão M1 do `GamepadProfileStore`):
  `cached`/`rawCache` (file:lines 29-32) — `load` serve do cache, `save`/`delete`
  atualizam cache E disco; teste `cache serves reads after the file is deleted`.

### §1.2 Tier USER na resolução

`gamepad/GamepadHub.kt`:

- `autoconfigStore = DeviceMappingStore(File(appContext.filesDir,
  "deviceMappings"))` (file:line 162) — single-instance no hub.
- `resolveBaseMapping` (file:line 1505) — a cadeia BASE extraída: USER
  (`autoconfigStore.load(mappingKey)?.mapping`, file:lines 1506-1507) >
  MODEL > SDL_DB > CAPABILITIES > DEFAULT. O tier USER é o PRIMEIRO da cadeia,
  como o spec §1.2 manda; a ORDEM É a prioridade (KDoc file:lines 1471-1490,
  regra de escalonamento do SDL).
- **Ordem quirk-DEPOIS documentada no KDoc** (file:lines 1481-1489, exigência
  do spec §1.2): o quirk aplica DEPOIS da cadeia em `resolveMapping`
  (file:lines 1490-1498) — o save captura o mapping PRÉ-quirk
  (`baseMappingCache`, file:line 1469); quirk é correção de TRANSPORTE, não
  preferência; firmware novo com quirk novo continua sendo corrigido por cima do
  USER.
- `mappingSource = USER` na resolução (file:line 1507) — o badge do card
  (`mappingSourceLabel`, `GamepadDevice.kt:57-60`) passa a mostrar
  `Mapping: USER` ao vivo; KDoc do enum atualizado (`GamepadDevice.kt:62-67`,
  USER deixou de ser "reservado").
- Cache invalidado em save/delete: `reResolveAutoconfig` (file:lines 428-440)
  remove `mappingCache`/`baseMappingCache` por deviceId dos devices com o
  mappingKey (mesmo padrão de invalidação do `invalidateProfiles` — invalida o
  que mudou); `baseMappingCache` morre junto nos ciclos existentes
  (`removeDevice` file:line 1584, `stop` file:line 266).

### §1.3 Ação "Salvar perfil deste controle" (UI)

`ui/screen/settings/DeviceDiagnosticsCard.kt` (mesma seção C do card — o spec §0
manda o botão entrar na seção do "Testar vibração"):

1. **Captura no addDevice, não no clique**: o save grava o mapping BASE
   PRÉ-quirk capturado DENTRO do `resolveMapping` do hotplug
   (`GamepadHub.kt:1496-1497` → `baseMappingCache`); o clique do card NUNCA
   re-deriva mapping — `hub.saveAutoconfig(deviceId)` (file:line 381) lê o
   cache e, só na falta dele (edge), `resolveBaseMapping`. O RetroArch grava o
   estado da CONEXÃO; aqui idem.
2. **Validação** (port de configuration.c:8206-8233): `AutoconfigValidation`
   (`DeviceAutoconfig.kt:48`), `validate` (file:line 64) — recusa sem
   FACE_BOTTOM com binding de TECLA (Key/Hat = o `joykey` do RA; eixo NÃO conta
   — configuration.c:8206-8209) OU sem nenhuma direção de dpad. Razões
   `MISSING_CONFIRM`/`MISSING_DIRECTION` (file:line 74, B checado primeiro como
   no RA). O card chama `hub.autoconfigCheck` ANTES de qualquer diálogo
   (`DeviceDiagnosticsCard.kt:348`); recusa → `AlertDialog` de erro com o
   motivo (file:lines 385-405), SEM salvar (o `saveAutoconfig` re-valida
   atomicamente no commit — `GamepadHub.kt:386-389`).
3. **Confirmação de sobrescrita**: autoconfig existente → diálogo mostrando
   nome + data do atual (file:lines 407-435, `gamepad_autoconfig_confirm_*`);
   sem existente → salva direto.
4. **Salva → invalida → re-resolve AO VIVO**: `autoconfigStore.save` +
   `reResolveAutoconfig` (`GamepadHub.kt:396-397`) — análogo ao reconect do
   RetroArch (após gravar o autoconf, o RA reconecta para o perfil salvo já
   valer): o device conectado passa a usar o USER na hora, SEM reconexão
   física; o `mappingSource` do device no StateFlow é atualizado e o card
   mostra `Mapping: USER` ao vivo (file:lines 428-440). O hub NÃO tem padrão de
   evento "mapping changed" (só Added/Removed/Input/Layer) — log
   `gncontrol: autoconfig <key> salvo/removido …` + invalidação + StateFlow,
   exatamente o fallback que o spec §1.3.4 autoriza.
5. **"Restaurar automático"**: `deleteAutoconfig(mappingKey)` (file:line 410) =
   `store.delete` + re-resolve (volta a MODEL/SDL_DB/CAPABILITIES/DEFAULT na
   hora). O card mostra o botão SÓ quando existe autoconfig salvo (file:lines
   358-365) e o subtítulo do botão salvar mostra `Salvo: <nome> · <data>`
   (`gamepad_autoconfig_saved_format`, file:lines 333-341).

### §1.4 Não-meta respeitada

Nada de formato de catálogo E / string SDL aqui — `DeviceAutoconfig.mapping` é o
modelo interno (`GamepadMapping`). K6 consumirá este store.

## 2. Arquivos alterados

| Arquivo | Mudança |
|---|---|
| `gamepad/mapping/DeviceAutoconfig.kt` | NOVO — modelo serializável + `AutoconfigValidation` + `AutoconfigCheck`/`AutoconfigSaveResult` |
| `gamepad/mapping/DeviceMappingStore.kt` | NOVO — store JSON por device (V1, atômico, cache M1) |
| `gamepad/GamepadHub.kt` | tier USER primeiro na cadeia; `baseMappingCache` (pré-quirk); save/delete/check + re-resolve ao vivo; limpeza de caches |
| `gamepad/GamepadDevice.kt` | KDoc do `MappingSource` (USER ativo, não mais reservado) |
| `gamepad/mapping/GamepadMapping.kt`, `RawBinding.kt`, `FaceStyle.kt`, `GamepadAxis.kt`, `GamepadButton.kt` | `@Serializable` (nomes estáveis, nunca ordinal) |
| `ui/screen/settings/DeviceDiagnosticsCard.kt` | botões salvar/restaurar + diálogos de erro/confirmação |
| `res/values*/strings.xml` | 13 chaves `gamepad_autoconfig_*` (EN + pt-rBR) |
| `app/src/test/.../DeviceMappingStoreTest.kt` | NOVO — 11 testes (round-trip, V1, delete, list, malformado) |
| `app/src/test/.../AutoconfigValidationTest.kt` | NOVO — 7 testes (regras 1.3.2) |

### Adaptação documentada (desvio consciente do §2 do spec)

O spec lista "`GamepadHub*Test` existentes — cadeia com USER (fake store)" —
**não existem testes JVM do `GamepadHub` no repo** (o hub importa `android.*`;
todo o histórico K3/K4 testa os objetos PUROS — `CapabilityMappingTest`,
`DeviceQuirksTest` — nunca o hub; `grep -rln GamepadHub app/src/test/java/` =
vazio). Em vez de introduzir Robolectric (novo harness de teste fora do padrão
do repo), a cobertura ficou: cadeia USER→…→DEFAULT como composição de dois
pedaços já testados (store + validação) no hub, verificada pelo gate de
compilação + assemble e registrada na §4 (on-device) — mesmo precedente do K4.
Nenhum teste existente foi alterado.

## 3. Testes (gate)

- `DeviceMappingStoreTest` (11): round-trip de TODO campo (inclusive binds
  Key/Axis/Hat e chaves enum por nome); `schemaVersion` default 1; isolamento
  por chave; cache M1; delete remove arquivo + no-op em chave ausente; `list`
  ordenada e honrando delete; malformado degrada e recupera; 3 testes V1
  (chave desconhecida sobrevive a save simples e múltiplo; delete remove os
  extras junto).
- `AutoconfigValidationTest` (7): mapping completo válido; sem FACE_BOTTOM →
  `MISSING_CONFIRM`; confirm sem dpad → `MISSING_DIRECTION`; vazio →
  `MISSING_CONFIRM` primeiro (ordem do RA); FACE_BOTTOM em EIXO não conta
  (joykey); 1 direção de dpad basta; Hat conta como direção.
- Gate exato do spec executado e verde:
  `JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew
  :app:testModernDebugUnitTest --tests "*Autoconfig*" --tests "*DeviceMapping*"
  --tests "*Gamepad*"` → **126 testes, 0 falhas, 0 erros** (12 classes) +
  `assembleModernDebug` BUILD SUCCESSFUL.
- Correções que o próprio gate pegou (registro de honestidade): (1) validação
  passou a exigir Key/Hat para FACE_BOTTOM (antes contava binding de eixo — o
  RA só aceita joykey); (2) `DeviceMappingStore.save` passou a LER o arquivo
  antes do write (sem isso, save sem load prévio perdia as chaves
  desconhecidas — bug real de V1).

## 4. On-device pendente (humano — Mi 11 + DS4 + Silksong)

Protocolo do spec §4, NÃO bloqueia o fechamento (regra §1.4 do roadmap):

1. DS4 + remap visual (fase B): trocar ✕↔○, "Salvar perfil deste controle" →
   reconectar BT → mapping persiste SEM perfil lógico (o raw já nasce certo);
   card mostra `Mapping: USER`.
2. "Restaurar automático" volta ao comportamento de fábrica (badge volta a
   `SDL_DB`/`MODEL`).
3. Validador: tentar salvar após limpar todos os bindings → diálogo de erro
   (`MISSING_CONFIRM`), nada salvo.
4. Silksong roda normal com o autoconfig ativo (regressão zero no jogo).
5. Extra (K5/K4 interação): DS4 BT com quirk ativo → salvar → badge
   `USER+QUIRK` e o quirk continua reaplicado por cima (o save gravou o
   PRÉ-quirk).

Logs de evidência: `gncontrol: autoconfig <key> salvo — tier USER ativo no
device <id> (<nome>)` / `… removido — cadeia MODEL/SDL_DB/CAPABILITIES/DEFAULT`
(`GamepadHub.kt:397-401, 414-417`).

## 5. Não-metas respeitadas (spec §5)

Multi-unidade por modelo (mappingKey = vid/pid, igual para as 2 unidades — fora
de escopo); autoconfig por descriptor (campo novo null-default se um dia
precisar); UI de listagem (o `list()` existe no store mas nenhuma tela o usa);
nuvem/sync.

## 6. Commits

| Commit | Conteúdo |
|---|---|
| `4af898ed` `feat(gamepad): autoconfig por device — "Salvar perfil deste controle" (spec 2026-08-16-K5-autoconfig-save-dispositivo)` | código + testes (§1-§3) |
| `docs(gamepad): checkpoint K5 — impl doc + tabela §7 do master roadmap` | este doc + linha K5 da §7 |
