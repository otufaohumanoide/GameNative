# Spec 2026-08-16 K6 — Intercâmbio de mapping no formato SDL (import/export comunitário)

**Data:** 2026-08-16
**Origem:** SDL3 `reference/SDL/src/joystick/SDL_gamepad.c` (**formato da mapping
string :1682-1849**; prioridades DEFAULT < API < USER :94-96, :2214-2221;
`SDL_SetGamepadMapping` :2927); GUID Android montado em `src/joystick/android/
SDL_sysjoystick.c:385,434` (bus 0x05, vid/pid LE, masks de capability) com as
masks construídas em Java (`SDLControllerManager.java:449` getAxisMask,
`:485` getButtonMask, `RangeComparator :228`). Formato documentado em
`reference/SDL_GameControllerDB/README.md:21-25`. **Dependência dura: K3**
(parser completo com misc1/paddles + capabilities) **e K5** (store USER para onde
o import escreve).
**Executor:** sub-agente autônomo. Leia `AGENTS.md` + master roadmap universal §1/§2.
**Posição na fila:** fase K6 (depois de K5 — commit de fronteira obrigatório).
**Turn budget sugerido:** 18–22 turns.

## 0. Estado atual (anchors)

- `gamepad/mapping/SdlControllerDb.kt`: DECODE já existe e é sólido (F1.4 do
  input-core): `mappingKeyFromGuid` (:99-108), `sdlButtonKeyCode` (:135-157,
  bN→AKEYCODE), sinais `+/-/~`, trigger-como-botão, `faceStyleForVendor`
  (:111-116). **Não existe ENCODE.**
- `gamepad/mapping/GamepadCapabilities.kt` + `GamepadDevice.capabilities` (K3):
  keycodes/axes/masks necessários para montar o GUID bus-style.
- `gamepad/mapping/DeviceMappingStore.kt` (K5): destino natural de um import
  (tier USER); origem natural de um export.
- Análise prévia: `reference/sdl/gamecontrollerdb-notes.md` (semântica dos campos
  Android do GUID e do enum bN — LEIA PRIMEIRO, economiza meia fase).

O que FALTA: fechar o ciclo com o ecossistema — usuário com controle exótico
encontra uma mapping string SDL em fórum/GitHub (ferramentas `controllermap`,
`testcontroller`, sdl2-gamepad-tool citadas no README do DB) e cola no GameNative;
e o inverso (usuário do GameNative gera a string para compartilhar).

## 1. Design

### 1.1 `SdlMappingCodec` — encode (novo arquivo, decode fica onde está)

Novo `gamepad/mapping/SdlMappingCodec.kt` (puro):

```kotlin
object SdlMappingCodec {
    fun encode(device: GamepadDevice, mapping: GamepadMapping, faceStyle: FaceStyle): String
    fun guidFor(device: GamepadDevice): String
}
```

- `guidFor`: GUID bus-style SDL2.0.16+ — 32 hex:
  `05 00 | crc16=0000 | vid LE | pid LE | version 0000 | driver sig | button_mask LE | axis_mask LE`.
  Masks das capabilities (K3): button_mask = OR de 1<<n para cada keycode mapeado
  na tabela inversa de `sdlButtonKeyCode`; axis_mask igual à regra do
  `getAxisMask` (≥2 eixos→0x0003; ≥4→0x000C; ≥6→0x0030 — ordem canônica
  X,Y,Z,RZ,LT,RT).
- `encode`: `guid,name,<semantic>:<binding>,...,platform:Android,` — semantic a
  partir do `GamepadButton`/`GamepadAxis` invertendo as tabelas existentes;
  binding `bN`/`aN`/`+aN`/`-aN`/`~aN` conforme `RawBinding`; hint
  `SDL_GAMECONTROLLER_USE_BUTTON_LABELS:=1` quando `faceStyle == NINTENDO`
  (simétrico ao decode do K3 §1.3). Botões sem binding são OMITIDOS (formato SDL).
- Round-trip obrigatório: `decode(encode(x))` == mapping (teste de propriedade
  sobre os entries do asset + os defaults).

### 1.2 Import (UI)

No device card (mesma seção do K5) + no remap dialog (menu overflow):
1. Colar string (TextField do dialog) OU arquivo via SAF (padrão do import de
   perfil do `GamepadRemapDialog`).
2. **Parse ao vivo**: reusa `SdlControllerDb` — mostra preview decodificado
   (nome, plataforma, N botões, N eixos) e DIFF contra o mapping atual
   (só botões/eixos que mudam — lista "+ FACE_BOTTOM: b1 / − FACE_BOTTOM: b0").
3. Validações: `platform:Android` (ausente = desktop → aviso bloqueante com
   explicação); GUID incompatível com o device conectado → aviso
   NÃO-bloqueante ("string de outro controle — aplicar mesmo assim?" — affinity
   vid/pid igual ao RetroArch :163: aplicamos se o usuário confirmar).
4. Confirmar → escreve no `DeviceMappingStore` (tier USER, K5) como
   `DeviceAutoconfig` (name = nome da string) → re-resolve vivo (K5 §1.3.4).

### 1.3 Export (UI)

- Device card → "Compartilhar mapping (formato SDL)": gera a string, mostra
  preview copiável + `ACTION_SEND` (clipboard/share intent) + salvar `.txt` via
  SAF. KDoc/crédito: formato SDL/zlib, DB comunitária
  `SDL_GameControllerDB` (atribuição pedida pela licença ao reutilizar o DB — o
  share de string própria não exige, mas o rodapé do preview cita o formato).

### 1.4 Convergência com o DB asset

Um dia: contribuir autoconfigs dos usuários de volta ao
`SDL_GameControllerDB` upstream — a string exportada É o formato de PR deles.
Fora de escopo aqui (documentar no impl doc como follow-up).

## 2. Arquivos afetados

| Arquivo | Mudança |
|---|---|
| `gamepad/mapping/SdlMappingCodec.kt` | NOVO — encode + guid (1.1) |
| `gamepad/mapping/SdlControllerDb.kt` | expor decode unitário p/ preview (se ainda não público) |
| `gamepad/mapping/DeviceMappingStore.kt` | (K5) — consumed; sem mudança se API já cobre |
| `ui/screen/settings/SettingsGroupGamepad.kt` + `gamepad/remap/GamepadRemapDialog.kt` | import/export UI (1.2/1.3) |
| `res/values*/strings.xml` | chaves de import/export/validações |
| `app/src/test/.../SdlMappingCodecTest.kt` | NOVO — round-trip, GUID, hint, omissões |

## 3. Verificação (gate)

```
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:testModernDebugUnitTest \
  --tests "*Sdl*" --tests "*Mapping*" --tests "*Gamepad*"
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:assembleModernDebug
```

## 4. On-device (humano — "on-device pendente")

1. Exportar o mapping do DS4 → colar num editor → conferir contra a entry
   equivalente do `gamecontrollerdb.txt` (sanidade: mesmos bindings semânticos).
2. Editar a string (trocar `a:b0` por `a:b1`), importar de volta → preview
   mostra o diff; aplicar → jogo respeita (Silksong).
3. Colar uma string desktop (`platform:Windows`) → bloqueio com explicação.
4. Import em controle desconhecido genérico → autoconfig USER criado, badge
   `USER` no card.

## 5. Não-metas

Parser de mapping desktop completa (só Android); sincronização com o repo
upstream; edição visual da string; suporte a `crc:` no matcher de import
(exibido no preview, ignorado no match — documentar).
