# Impl doc — Spec 2026-08-16 K6 (intercâmbio de mapping no formato SDL)

**Data:** 2026-08-16
**Spec:** `docs/spec-2026-08-16-K6-intercambio-mapping-sdl.md` (fase K6 do master
roadmap universal input; retomada da fila §4)
**Resultado:** implementado; gate completo verde (tests `*Sdl* *Mapping*
*Gamepad*` + `assembleModernDebug`); commit `feat(gamepad): …` na §6.
Verificação on-device pendente (spec §4 — protocolo humano).

## 1. O que foi feito (por seção do spec)

### §1.1 `SdlMappingCodec` — encode (novo arquivo puro)

`gamepad/mapping/SdlMappingCodec.kt` (NOVO, 300 linhas, zero android.*):

- `guidFor(device)` (file:line 43) — GUID bus-style 32 hex do SDL2 ANDROID
  (o formato das entries `platform:Android` do DB pinado e o que o
  `SdlControllerDb.mappingKeyFromGuid` lê): `05 00 | crc16 0000 | vid LE |
  0000 | pid LE | 0000 | button_mask LE | axis_mask LE`. **Desvio de layout
  documentado (importante):** o spec §1.1 descreve o GUID como
  `vid LE | pid LE | version | driver sig | masks`; a leitura da fonte
  (SDL2 2.0.16 `SDL_joystick.c:2480-2510` + `SDL_sysjoystick.c:347-353`)
  mostrou que o SDL2 espaça `vendor | 0 | product | 0 | version` e o backend
  Android SOBRESCREVE os dois últimos Uint16 com as masks — o product fica nos
  bytes 8..9 (não 6..7). O SDL3 mudou para product em 6..7, mas o DB pinado e o
  parser do fork (F1.4) usam o layout SDL2 — seguir o SDL3 quebraria o
  round-trip interno e geraria strings incompatíveis com o ecossistema. KDoc
  com a citação completa (file:lines 27-42).
- Masks de capability (spec §1.1, "Masks das capabilities (K3)"):
  - `buttonMaskFor` (file:line 147) — port da tabela do
    `SDLControllerManager.getButtonMask` (SDL3 `:485-535`, mesma do SDL2 2.0.16):
    bit N = enum SDL_CONTROLLER_BUTTON do backend Android (b0..b14 padrão,
    b15/16/17/18 = L2/R2/C/Z, b20..31 = BUTTON_1..12); aliases
    BACK≡SELECT(b4), MENU≡START(b6), DPAD_CENTER→A(b0) como no getButtonMask;
    BUTTON_13..16 → sentinela 0xFFFFFFFF ("out of room", truncado para 0xFFFF
    no campo Uint16); hats → bits 11-14 de DPAD (`SDL_sysjoystick.c:340-344`).
  - `axisMaskFor` (file:line 191) — `getAxisMask` (`:449-481`): ≥2 eixos →
    0x0003, ≥4 → +0x000C, ≥6 → +0x0030 (ordem canônica X,Y,Z,RZ,LT,RT) + bit
    0x8000 de "ordem de sort mudou" (AXIS_Z presente E eixo entre Z e RZ —
    RX/RY). `capabilities == null` → masks 0x0000 (degradação: GUID válido,
    sem bits; o decode usa só vid/pid).
- `encode(device, mapping, faceStyle)` (file:line 76) — campos na ordem:
  GUID, nome, botões (ordem do enum), eixos (ordem do enum), hint NINTENDO,
  `platform:Android,` (vírgula final do formato do DB). Botões/eixos SEM
  binding são OMITIDOS (formato SDL); binding com keycode/eixo fora do
  vocabulário bN/aN do backend Android também é omitido (não há expressão SDL
  — KDoc file:lines 19-22). `encodeRawBinding` (file:line 109) público (diff/
  preview): Key→`bN` (inverso exato de `sdlButtonKeyCode` + aliases
  BACK/MENU, file:line 213), Axis→`aN`/`aN~` (direção -1 = invertido, file:line
  239), Hat→`hN.M`.
- `diff(current, imported)` (file:line 122) — `MappingDiff(semantic, from, to)`
  (file:line 292) por botão/eixo que muda; a UI renderiza
  `+ X: b1` / `− X: b0` / `± X: b0 → b1` (formato do spec §1.2).
- Hint `SDL_GAMECONTROLLER_USE_BUTTON_LABELS:=1` quando `faceStyle == NINTENDO`
  (simétrico ao decode do K3 §1.3).

`SdlControllerDb.kt` (consumido):

- `platformOf(line)` (file:line 130) — campo `platform:` da string crua para a
  validação do import (ausente = desktop → bloqueio).

`SdlMappingCodecTest.kt` (NOVO, 15 testes):

- GUID: DS4 (com máscaras 0xFFFF/0x003F), caps null (masks zero), hat → bits de
  DPAD, BUTTON_13..16 → sentinela truncada, bit 0x8000.
- Round-trips: linha DS4 real do DB; inversão `a1~`; hint NINTENDO; omissões;
  extras MISC1/PADDLE_1..2/TOUCHPAD; trigger-como-botão (`lefttrigger:b15`);
  keycode fora da tabela (DPAD_CENTER) omitido.
- **Propriedade obrigatória do spec §1.1**: `decode(encode(x)) == mapping`
  sobre TODAS as entradas do asset `gamecontrollerdb.txt` (299 linhas Android
  parseadas — teste file:line 227: buttons/axes/name/mappingKey/faceStyle
  iguais por entry).
- Diff (novo/removido/alterado) e platformOf.

### §1.2 Import (UI) + §1.3 Export (UI)

`ui/component/SdlMappingDialogs.kt` (NOVO — compartilhado entre o device card e
o remap dialog):

- `SdlMappingExportDialog` (file:line 52): preview da string em
  `SelectionContainer` (mono), botões Copiar (clipboard), Compartilhar
  (`ACTION_SEND` + chooser — padrão `BaseAppScreen.kt:495`), Salvar `.txt` via
  SAF (`CreateDocument("text/plain")`, padrão do export de perfil do remap
  F3.3), rodapé com atribuição do formato/DB comunitária (spec §1.3 KDoc).
- `SdlMappingImportDialog` (file:line 160): TextField de colar + carregar
  arquivo (SAF `OpenDocument`); parse AO VIVO por keystroke
  (`SdlControllerDb.parseLine`, remember(input)); preview
  `Nome · Plataforma · N botões · N eixos`; DIFF contra o mapping atual
  (`SdlMappingCodec.diff`, até 12 linhas + contagem); validações do spec §1.2:
  `platform:Android` ausente/diferente → **bloqueio** com explicação (desktop);
  GUID de outro controle (`mappingKeyFromGuid` ≠ `device.mappingKey`) → aviso
  NÃO-bloqueante (affinity vid/pid, análogo ao RetroArch
  `task_autodetect.c:163`); GUID legado (sem vid/pid) → sem aviso (não há o que
  comparar). Confirmar → `onImport(mapping)`.

`gamepad/GamepadHub.kt` (novos hooks — o spec §2 não lista o hub, mas o
re-resolve vivo (K5 §1.3.4) é privado; a UI não poderia escrever o tier USER
sem estes 3 métodos):

- `importAutoconfig(deviceId, mapping)` (file:line 429) — valida com a MESMA
  regra do save (K5 §1.3.2 — string de fórum sem botão de confirmação/
  navegação deixaria o controle inutilizável), monta o `DeviceAutoconfig`
  (deviceName = nome do DEVICE; o nome da string fica no `mapping.name`),
  salva no `DeviceMappingStore` (tier USER) e re-resolve ao vivo. Retorna
  `AutoconfigSaveResult` (a UI reusa o diálogo de erro do K5).
- `baseMappingFor(deviceId)` (file:line 458) — mapping BASE pré-quirk (o
  export serializa a identidade do controle, não a correção de transporte —
  mesmo racional do K5).
- `effectiveMappingFor(deviceId)` (file:line 468) — mapping EFETIVO pós-quirk
  (referência do DIFF do preview).

UI:

- `ui/screen/settings/DeviceDiagnosticsCard.kt` — dois `DiagButtonRow` novos
  ("Compartilhar mapping (formato SDL)" / "Importar mapping (formato SDL)",
  file:lines 383-390) na seção do K5 + status transitório auto-limpo (~3 s,
  file:lines 121-126) e os diálogos no fim (file:lines 474-527). O resultado
  inválido do import reaproveita o `autoconfigError` do K5.
- `gamepad/remap/GamepadRemapDialog.kt` — linha própria de botões SDL no footer
  (file:lines 1490-1501, separada do export/import do perfil LÓGICO que é
  outro formato) + diálogos no fim (file:lines 1564-1603); o diff usa o
  `mapping` EFETIVO que o dialog já recebe; erro de validação vira `status` do
  dialog.
- `res/values*/strings.xml` — 28 chaves novas EN + pt-rBR (file:lines 2510+).

## 2. Decisões de design (desvios aceitos)

1. **Layout do GUID = SDL2 Android** (product nos bytes 8..9), não o SDL3 do
   spec §1.1 — ver §1.1 acima. O spec diz "GUID bus-style SDL2.0.16+" e a
   leitura da fonte mandou o layout SDL2; sem isso o round-trip interno (gate)
   quebra e o export não conversa com o DB comunitário (on-device §4.1).
2. **Import valida com `AutoconfigValidation`** (spec §1.2 não menciona
   validação explícita, mas o store K5 tem contrato de validação; uma string
   sem B/dpad importada quebraria menus — mesma regra do RetroArch no save).
3. **`mappingKeyFromGuid` vazio no import** (GUID legado hex-do-nome) → sem
   aviso de affinity (não há vid/pid para comparar) e o parse já rejeita
   (mappingKey vazio) — o preview mostra "inválida".
4. **Export usa o BASE pré-quirk** (hub.baseMappingFor) nos DOIS lugares (card
   e remap) — consistência com o K5; o quirk é correção de transporte, não
   identidade do controle.
5. **Scroll horizontal não é exportado/importado** — o sink não tem hscroll
   (fora de escopo; o DPAD_LEFT/RIGHT do moonlight usa hscroll, mas o fork
   não expõe — documentado, não-meta).
6. **Keycodes aliases (BACK=4/MENU=82) encodam como b4/b6** (o SDL expressa os
   dois como BACK/START), DPAD_CENTER=23 é OMITIDO (sem bN); o decode devolve
   o keycode canônico do fork (109/108) — o round-trip do asset/default não é
   afetado (nenhum usa esses keycodes).

## 3. Verificação (gate)

```
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:testModernDebugUnitTest   --tests "*Sdl*" --tests "*Mapping*" --tests "*Gamepad*" --offline
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:assembleModernDebug --offline
```

- Resultado: VERDE (tests = 192, 0 falhas, 0 erros — filtros `*Sdl* *Mapping*
  *Gamepad*`; assemble OK em 6 m 48 s; gradle em background SEM timeout) —
  registrado na §6 com a data.

## 4. On-device (humano — spec §4, "on-device pendente")

1. Exportar o mapping do DS4 → colar num editor → conferir contra a entry
   equivalente do `gamecontrollerdb.txt` (sanidade: mesmos bindings semânticos
   + GUID com vid/pid iguais).
2. Editar a string (trocar `a:b0` por `a:b1`), importar de volta → preview
   mostra o diff; aplicar → jogo respeita (Silksong).
3. Colar uma string desktop (`platform:Windows`) → bloqueio com explicação.
4. Import em controle desconhecido genérico → autoconfig USER criado, badge
   `USER` no card.

## 5. Não-metas (spec §5 — confirmadas)

Parser de mapping desktop completa (só Android); sincronização com o repo
upstream (follow-up declarado: contribuir autoconfigs de volta ao
SDL_GameControllerDB — o export É o formato de PR); edição visual da string;
suporte a `crc:` no matcher (exibido no preview, ignorado no match).

## 6. Commit e checkpoint

- Commit da fase: `feat(gamepad): intercâmbio de mapping no formato SDL —
  encode/guid com masks de capability, import/export no card e no remap
  (spec 2026-08-16-K6)`.
- Impl doc: este arquivo.
- Tabela §5 da retomada + §7 do master atualizadas (checkpoint idempotente).
