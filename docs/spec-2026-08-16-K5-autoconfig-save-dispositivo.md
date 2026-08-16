# Spec 2026-08-16 K5 — "Salvar perfil deste controle" (autoconfig por device, port RetroArch)

**Data:** 2026-08-16
**Origem:** RetroArch `reference/RetroArch/configuration.c` —
`config_save_autoconf_profile` (**:8137**) grava o perfil efetivo do port com
identidade (driver/device/display_name/vid/pid); **validação mínima em :8206-8233**
(recusa perfil sem ao menos B + uma direção); após salvar, limpa binds manuais e
**reconecta** para o perfil salvo já valer. Matching por vid/pid + affinity
(`tasks/task_autodetect.c:163`: +30 vid+pid, +20 nome exato). Clean-room
(RetroArch é GPL-3): semânticas em Kotlin, atribuição no KDoc.
**Executor:** sub-agente autônomo. Leia `AGENTS.md` + master roadmap universal §1/§2.
**Posição na fila:** fase K5 (depois de K4; antes de K6, que reusa este store).
**Turn budget sugerido:** 18–22 turns.

## 0. Estado atual (anchors)

- `gamepad/GamepadHub.kt:1325-1331` + K3/K4: cadeia
  `USER > MODEL > SDL_DB > CAPABILITIES > DEFAULT` — o tier USER está RESERVADO
  no enum mas não existe armazenamento. Este spec o implementa.
- `gamepad/profiles/GamepadProfileStore.kt`: perfis LÓGICOS (remap por botão
  lógico, camadas, gyro) em 3 camadas AUTO→global do device→override do jogo,
  política V1 (preserva chaves desconhecidas no save). **Não confundir**: este
  spec salva o mapping RAW→LÓGICO (a camada de baixo), não o perfil lógico.
- Device card da fase C (`ui/screen/settings/SettingsGroupGamepad.kt`): já mostra
  diagnóstico (bateria, capacidades, viewer); botão "Testar vibração" da fase A
  vive aqui — o "Salvar perfil deste controle" entra na mesma seção.
- `gamepad/GamepadDevice.kt:31`: `mappingKey = "%04x%04x"` (vid/pid) — a chave
  natural do autoconfig.

O que FALTA: depois de remapear um controle desconhecido (ou corrigir um quirk à
mão), o usuário não consegue dizer "deste dia em diante, este controle sempre
mapeia assim" — o remap lógico por perfil cobre parte, mas o mapeamento base
(raw→lógico) continua sendo decidido só pela DB/quirks a cada boot.

## 1. Design

### 1.1 `DeviceMappingStore` — NOVO store (formato próprio, política V1)

Novo `gamepad/mapping/DeviceMappingStore.kt` (+ `DeviceAutoconfig.kt` para o modelo):

```kotlin
@Serializable
data class DeviceAutoconfig(
    val mappingKey: String,          // "054c09cc"
    val deviceName: String,          // display only
    val mapping: GamepadMapping,     // RAW→LÓGICO efetivo no momento do save
    val faceStyle: FaceStyle,
    val createdAtMs: Long,
    val schemaVersion: Int = 1,
)
```

- Arquivo JSON por device em `<filesDir>/deviceMappings/<mappingKey>.json` (um por
  controle — deletar/resetar é trivial). `ignoreUnknownKeys` + preservar chaves
  desconhecidas no save (política V1 do repo, obrigatória).
- API: `load(mappingKey)`, `save(DeviceAutoconfig)`, `delete(mappingKey)`,
  `list(): List<DeviceAutoconfig>` (para uma futura tela de gestão — aqui só o
  que o card usa: load/save/delete do device corrente).

### 1.2 Tier USER na resolução

`GamepadHub.mappingFor`: `DeviceMappingStore.load(device.mappingKey)` vira o
PRIMEIRO da cadeia (USER > MODEL > SDL_DB > CAPABILITIES > DEFAULT). Quirks (K4)
continuam aplicados DEPOIS — um quirk é correção de transporte, não preferência;
documentar essa ordem no KDoc (save captura o mapping pré-quirk, quirks
reaplicam por cima: firmware novo com quirk novo continua sendo corrigido).
- Cache invalidado em save/delete (mesmo padrão de `invalidateProfiles`).
- `mappingSource = USER`.

### 1.3 Ação "Salvar perfil deste controle" (UI)

No device card (seção C), botão que:
1. Monta `DeviceAutoconfig` do mapping EFETIVO atual (o que o tier vencedor +
   event-level state produzem — capturar no addDevice, não no clique).
2. **Valida** (port de configuration.c:8206-8233): recusa se o mapping não tem
   `FACE_BOTTOM` E não tem nenhuma direção de dpad (perfil inútil/navegável
   quebrado). Diálogo de erro com o motivo, SEM salvar.
3. Confirmação (sobrescreve autoconfig existente? mostra nome + data do atual).
4. Salva → invalida cache → **re-resolve AO VIVO** (análogo ao reconect do
   RetroArch: o device conectado passa a usar o USER na hora, sem reconectar
   fisicamente — chamar o re-resolve do hub e emitir o evento de "mapping
   changed" se o padrão de evento do hub existir; senão log + invalidação).
5. Ação irmã "Restaurar automático" = `delete(mappingKey)` (volta para a cadeia
   MODEL/SDL_DB/CAPABILITIES/DEFAULT).

### 1.4 Não-meta explícita: formato do catálogo E

O catálogo comunitário de perfis (fase E, `tools/profiles/`) é de perfis
LÓGICOS. O compartilhamento de autoconfig raw usa o formato SDL string — isso é
a fase K6 (import/export), que consumirá este store. Não misturar formatos aqui.

## 2. Arquivos afetados

| Arquivo | Mudança |
|---|---|
| `gamepad/mapping/DeviceAutoconfig.kt` | NOVO — modelo serializável |
| `gamepad/mapping/DeviceMappingStore.kt` | NOVO — store JSON (1.1) |
| `gamepad/GamepadHub.kt` | tier USER na cadeia + invalidação + re-resolve vivo (1.2/1.3.4) |
| `ui/screen/settings/SettingsGroupGamepad.kt` | botões salvar/restaurar + validação + confirmação |
| `res/values*/strings.xml` | chaves (salvar perfil do controle, restaurar, erros de validação) |
| `app/src/test/.../DeviceMappingStoreTest.kt` | NOVO — round-trip, V1 (chave desconhecida preservada), delete |
| `app/src/test/.../AutoconfigValidationTest.kt` | NOVO — regras 1.3.2 |
| `app/src/test/.../GamepadHub*Test` existentes | cadeia com USER (fake store) |

## 3. Verificação (gate)

```
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:testModernDebugUnitTest \
  --tests "*Autoconfig*" --tests "*DeviceMapping*" --tests "*Gamepad*"
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:assembleModernDebug
```

## 4. On-device (humano — "on-device pendente")

1. DS4 + remap visual (fase B): trocar ✕↔○, "Salvar perfil deste controle" →
   reconectar BT → mapping persiste SEM perfil lógico (o raw já nasce certo).
2. "Restaurar automático" volta ao comportamento de fábrica.
3. Validador: tentar salvar após limpar todos os bindings → erro, nada salvo.
4. Silksong roda normal com o autoconfig ativo (regressão zero no jogo).

## 5. Não-metas

Multi-unidade por modelo (mesmo vid/pid, 2 controles — o mapping é igual mesmo);
autoconfig por descriptor (unidade) — se um dia precisar, campo novo null-default;
UI de listagem/gerenciamento de todos os autoconfigs salvos; nuvem/sync.
