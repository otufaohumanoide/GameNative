# Spec 2026-08-14 (r2) — Intuito do suporte avançado a gamepads + validação de prontidão para os upgrades

**Data:** 2026-08-14 (r2 no mesmo dia)
**Origem:** pedido do usuário — documento que declara o INTUITO do fork (suporte
avançado a gamepads estudado nos projetos da pasta `reference/`, feito melhor, sem
reproduzir os vícios de código legado deles) e VALIDA a implementação atual como base
de prontidão para os upgrades futuros (gyro, touchpad→mouse, layers completas, remap no
jogo, rumble).
**Natureza:** documento de direção + auditoria de prontidão. NÃO implementa nada;
cada upgrade terá seu próprio spec de implementação quando aprovado.
**Status da base hoje:** Ondas 1 e 2 + correção mergeadas (specs
`2026-08-13-gamepad-universal`, `-onda2`, `-correcao`, `-controller-touchpad-ghost-input`,
`-home-button-overlay-exit`); camada universal completa, mas com
`gamepadUniversalEnabled` **default OFF** (PrefManager.kt:1457-1460) — kill-switch
ativo até verificação on-device ampla. Todo upgrade que consome eventos lógicos
depende desse gate ligar (ou de decisão explícita de ligá-lo por upgrade).
**Referências lidas:** `reference/SDL` (zlib), `SDL_GameControllerDB` (zlib),
`retroarch-joypad-autoconfig`/`RetroArch` (GPL — conceitos), `dolphin` (GPL),
`duckstation` (GPL), `ppsspp` (GPL), `key-mapper` (GPL), `moonlight-android` (GPL),
`DS4Windows`, `androidx` (Apache-2.0; games-controller ausente no checkout atual).

**Revisão r2 — o que mudou e por quê:**
1. Correções factuais de API verificadas contra `platforms/android-36/data/api-versions.xml`:
   sensores por device = API **31** (não 26); `InputDevice.getVibrator()` = API **16**
   (deprecado em 31 por `getVibratorManager()`). Impacto: gyro exige runtime guard em
   ambas as flavors (modern minSdk 29, legacy 26) e é indisponível no Android 9–11;
   rumble por device funciona em tudo que o fork suporta.
2. U6 reescrito com os números reais do código (thresholds 0.5/0.6, raw keycodes,
   confirm implícito do Compose) — a redação anterior citava "0.45 hardcoded", incorreto.
3. U5 completado: rumble DO JOGO exige ponte Wine/XInput → Vibrator (hoje só existe
   haptic de menu, view-level).
4. Adicionados: não-objetivos, matriz U×V, V11/V12, decisão de política para V1
   (preservar chaves desconhecidas), riscos novos (lifecycle de sensor, gyro ausente
   via USB), U7 (bateria) e status do gate no cabeçalho.

---

# PARTE I — O INTUITO

O fork deve ter o **melhor suporte a gamepad do ecossistema Android para rodar jogos de
PC em container** — não apenas "funcionar", mas: qualquer controle conecta e funciona
out-of-the-box (mapeamento consistente por vendor/product, não por nome), navegação de
menu completa, deadzones e remapeamento por dispositivo E por jogo, e os recursos
avançados que a comunidade pede (gyro→mouse, touchpad→mouse, camadas de ação estilo
Steam Input, rumble).

**Princípio fundador:** estudamos os projetos da `reference/` para extrair o QUE fazem
de bom, e evitamos explicitamente o COMO legado — a tabela da Parte II registra cada
vício recusado e a decisão do fork. A régua de qualidade é a do repo: lógica pura
testável em JVM, hot path síncrono, estado vivo (nunca capturado), zero libs de gamepad
de terceiros, strings EN/pt-rBR.

**Não-objetivos (tão importantes quanto os objetivos):**
- NUNCA injeção via accessibility service (padrão key-mapper): lag de um frame é
  inaceitável para jogo.
- NUNCA driver virtual (padrão ViGEm/DS4Windows): não existe nem é desejado em Android.
- NUNCA copiar código das referências — conceito sim, código não, mesmo onde a licença
  permitir. A régua é reimplementação limpa com os testes do repo.
- NÃO importar `gamecontrollerdb.txt` inteiro nem embarcar DB grande no APK (mesma
  filosofia do catálogo de shaders: o framework `.kl` já normaliza o comum).
- Macros/gravação de sequências: fora de escopo até U3 existir (reavaliar depois).

---

# PARTE II — Lições das referências: o que adotamos e os vícios que NÃO copiamos

| Projeto | Contribuição adotada | Vício recusado (e o que o fork faz melhor) |
|---|---|---|
| **SDL / SDL_GameControllerDB** | Gramática de mapping (`a:b0,leftx:a0,dpup:h0.1`), bitmask de hat, half-axes `+aN/-aN/~aN`, `SDL_GamepadFaceStyle` (abxy/bayx/sony) | GUID de 16 bytes com CRC/versão (frágil entre firmware); DB de 2.256 entradas desktop (95% irrelevante no Android — o framework já normaliza via `.kl`); detecção de face style por listas hardcoded de USB IDs. **Fork:** chave = vendor+product hex (estável; hoje 12 entradas em `MappingDatabase`), DB enxuta, FaceStyle declarado no mapping, não inferido |
| **RetroArch / joypad-autoconfig** | Perfil por vendor/product, labels por botão, `menu_swap_ok_cancel_buttons`, remap por core (per-jogo) | Matching por NOME do device em vários drivers (frágil entre revisões); múltiplos dialetos de config por driver (udev/linuxraw/winraw/android); remap com índices opacos (`input_player1_btn_0`) — propenso a erro humano. **Fork:** um único formato JSON por escopo, chaves semânticas (`FACE_BOTTOM`, nunca índices) |
| **Dolphin** | Merge device→game (perfil por jogo sobre perfil do controle), expressões por device | DSL de expressões complexo e frágil; heurísticas de detecção acumuladas em anos de casos especiais. **Fork:** bindings tipados (`RawBinding.Key/Axis/Hat`), sem DSL textual no hot path |
| **DuckStation / PPSSPP** | Abstração limpa de controller (DuckStation); chords/combos do ControlMapper (PPSSPP — inspiração para layers) | UI de mapping intrincada; state global C++. **Fork:** layers modeladas em dados puros desde o dia 1 (`ActionLayer`), motor de ativação é follow-up |
| **key-mapper** | UX de remap (capture mode, conflito, por-app) — a melhor UX das referências | Injeção via accessibility service (LAG de um frame inteiro — inaceitável para jogo); matching por nome em partes. **Fork:** captura direto no evento cru (mesma thread do dispatch), por vendor/product |
| **moonlight-android** | Abstração de controle para streaming, per-device configs | Polling e emulação de mouse com quirks; GPL. **Fork:** eventos push do InputManager, injeção de mouse já existente via XServer (`injectPointerMoveDelta`) |
| **DS4Windows** | Action sets/layers por perfil, gyro, deadzones por device, macros — a ferramenta mais avançada do mercado | Depende de driver virtual (ViGEm, Windows-only); sistema de perfis XML confuso com conflitos de toggle. **Fork:** mesmo conceito de layers, mas em Android nativo, sem driver, persistência JSON atômica |
| **Lemuroid / AndroidX games-controller** | Kotlin limpo; API oficial de eventos de gamepad (AndroidX) | Sem perfis/persistência/remap — só normalização. **Fork:** a camada universal é um superconjunto: tudo do AndroidX + mapping + perfis + FaceStyle |

**Decisões de arquitetura que o fork toma e NENHUMA referência toma bem:**
1. Lógica pura em `object` sem `android.*` (testes JVM reais de tradução/deadzone/merge).
2. Estado vivo no momento do evento (holder — lição C1 do hardening); zero estado global
   de roteamento.
3. Dedupe entre canais (hat×tecla DPAD) e gate de ghost input do touchpad — bugs que
   RetroArch/Dolphin/moonlight manifestam no Android e nós já resolvemos.

---

# PARTE III — VALIDAÇÃO DA IMPLEMENTAÇÃO ATUAL (prontidão por upgrade)

Regra de leitura: cada upgrade lista (a) o que JÁ existe e o torna barato,
(b) o que FALTA, (c) o que validar AGORA para não refatorar depois,
(d) restrições de plataforma verificadas.

## U1 — Gyro → mouse/câmera (pedido nº 1 da comunidade)

**(a) Existe:**
- Stub `InputEvent.SensorUpdate` (gamepad/InputEvent.kt:19-27), já com acel — o
  vocabulário lógico nasce com o evento; nenhum consumidor refatora quando o gyro chegar.
- `GamepadDevice` com identidade estável (descriptor) para associar sensor→device.
- Perfil `@Serializable` com `ignoreUnknownKeys` (GamepadProfile.kt + store) — adicionar
  campos `gyroMode/gyroSensitivity/gyroDeadzone` NÃO quebra arquivos antigos.
- `refreshDevice` já re-classifica em `onInputDeviceChanged` (mesma máquina que
  reavaliará capacidades de sensor ao religar o controle).

**(b) Falta:**
- Fonte de sensores: `InputDevice.getSensorManager()` (API 31) → `registerListener` no
  manager DO device. Eventos chegam por callback em THREAD PRÓPRIA — nunca no dispatch;
  exige lifecycle completo (register no início do container; unregister em pause/exit/
  screen-off — vazamento = bateria drenando com o app "fechado").
- `GyroProcessor` puro (calibração, recenter explícito — drift é inerente —,
  yaw/pitch→delta, deadzone angular) + modos OFF/MOUSE/CAMERA no perfil.
- Regra de ativação (DS4Windows usa "gyro activate button" — segurar um botão liga o
  gyro): definir no spec do U1 se entra junto ou como follow-up.

**(c) Validar agora:** documentar que a fonte de sensores NUNCA entra em `onKey/onAxis`
(hot path) — decisão registrada aqui; manter `SensorUpdate` como o único contrato entre
a fonte e os consumidores; rate default `SENSOR_DELAY_GAME` (~50 Hz — suficiente para
cursor; FASTEST ~200 Hz+ só por opt-in no perfil, custo de bateria).

**(d) Restrições (verificado em api-versions.xml):** API **31+**. Modern (minSdk 29) e
legacy (26) exigem `Build.VERSION.SDK_INT >= 31` + capability check (`sensorList` do
device vazio = sem gyro). Conexão importa: DS4/DualSense frequentemente só expõem
sensores via Bluetooth — a UI ESCONDE a opção quando ausente (V11), nunca mostra erro.

## U2 — Touchpad DS4/DualSense → mouse (pedido nº 2)

**(a) Existe:**
- Stub `InputEvent.TouchpadMotion` (gamepad/InputEvent.kt:29).
- Gate de ghost input (MainActivity.kt:582-587 e 636-641 — `PrefManager.ignoreControllerTouchpad`)
  é o PONTO DE PLUG declarado: o touchpad de um controle passa por ali antes do bus.
- `DeviceClass.TOUCHPAD` classificado pelo hub (DeviceClassifier.kt:34-48).
- Precedente de injeção de mouse: `PhysicalControllerHandler.createMouseMoveTimer` →
  `xServer.injectPointerMoveDelta` (timer 60 FPS, PhysicalControllerHandler.kt:231-247).

**(b) Falta:**
- `TouchpadProcessor` puro (absoluto normalizado [0..1] → delta, modo mouse/gestos,
  deadzone de toque, tap = clique).
- Captura dos eixos de toque — `AndroidInputAdapter.toRawAxis` (linhas 24-47) lista só
  eixos de gamepad; touchpad precisa de um `RawTouchInput` próprio (AXIS_X/Y absolutos
  do device TOUCHPAD).

**(c) Validar agora — o gate vira ROTEADOR, não some:** com U2 ativo, o consume do gate
continua valendo para navegação/jogo (fantasmas nunca chegam ao foco nem ao jogo), mas o
consumidor do touchpad→mouse lê o MESMO ponto ANTES do consume. Nenhum outro caminho é
criado (V7). O touchpad usa fluxo separado do `onAxis` (device TOUCHPAD já é excluído do
lógico — decisão da Onda 2, correção 3).

**(d) Restrições:** nenhuma de API (fontes e injeção existem desde API 9-14). O risco é
de roteamento (V8), não de plataforma.

## U3 — Action Layers completas (chords/toggles — estilo Steam Input/DS4Windows)

**(a) Existe:**
- Modelo: `GamepadProfile.layers: Map<String, Map<String, String>>` + `ActionLayer`
  DEFAULT/MENU (GamepadProfile.kt:12, 32) — o armazenamento de N camadas já é
  suportado pelo store SEM mudança de schema.
- Serialização de binding por camada: `GamepadBindingCodec` (key/axis/hat, round-trip
  testado).
- Merge de camadas definido: game substitui device quando não-vazio
  (GamepadProfileStore.kt:91).

**(b) Falta:**
- Motor de ativação (hold/toggle/duplo-toque) — precisa de um `LayerResolver` puro
  (estado por device: camada ativa + condições, morto em `removeDevice` como
  `buttonStates` — V6) e de regras no perfil (campo futuro `layerTriggers`).
- UI de edição de camadas além de DEFAULT (o GamepadRemapDialog edita só DEFAULT).

**(c) Validar agora:** `layers` como `Map<String, ...>` com nomes arbitrários já permite
adicionar camadas sem migração; decisão registrada para o spec do U3: merge por camada
vira GRANULAR (jogo sobrescreve só as camadas que define; hoje é por bloco,
GamepadProfileStore.kt:91) — alinhado a Steam Input/Dolphin. Mudança de SEMÂNTICA
versionada no spec, não de schema.

**(d) Restrições:** nenhuma — dado puro + lógica JVM.

## U4 — Remap aplicado ao JOGO (hoje o remap é só do menu)

**(a) Existe:**
- Todo o modelo de remap (codec, conflito, store, UI) e o caminho do jogo com override
  de deadzone (`PhysicalControllerHandler.applyProfileDeadzone`, linhas 199-227 —
  precedente do ponto de aplicação no caminho do jogo).

**(b) Falta:**
- `PhysicalControllerHandler` consultar as `layers` do perfil universal nos eventos de
  tecla/eixo ANTES de injetar — hoje só deadzones passam; os bindings do jogo continuam
  no `ExternalControllerBinding` (com.winlator).
- Precedência entre os DOIS sistemas. Proposta registrada aqui (spec do U4 confirma):
  **binding explícito na camada universal vence; sem binding → `ExternalControllerBinding`
  (caminho byte-identical)**. Gate ON obrigatório para o caminho novo; default = OFF.

**(c) Validar agora:** o lookup de remap por evento usa o cache de perfil (M1,
GamepadHub.kt:66, 148-153) — nunca disco no caminho do jogo; precedência documentada no
spec do U4 com os dois kill-switches (gate universal + ControlsProfile do jogo).

**(d) Restrições:** nenhuma de API.

## U5 — Rumble avançado (do menu E do jogo)

**(a) Existe:** `GamepadHaptics.vibrate` (view-level, confirmação de menu) — ponto de
chamada único nos bridges.

**(b) Falta:**
- Rumble por device: `InputDevice.getVibratorManager()` em API 31+; **`getVibrator()`
  funciona desde API 16** (deprecado em 31) — caminho legado disponível em TODAS as
  configurações suportadas pelo fork.
- Efeitos por perfil (`rumbleOnActivate` etc.).
- **Ponte do JOGO (o ponto que faltava):** nada hoje leva rumble do jogo ao device —
  requer ponte Wine/XInput → `VibrationEffect` (JNI no caminho do ExternalController).
  Dimensionar essa ponte ANTES de estimar U5.

**(c) Validar agora:** haptics de menu permanece no helper único; a evolução é interna.
**(d) Restrições:** API 16+/31+ conforme caminho — sem bloqueio real.

## U6 — OK/Cancel + glyphs em TODAS as superfícies

**(a) Existe:** FaceStyle confirm nos bridges (bus + view), glyphs no GamepadActionBar
(com guard de preview), strings EN/pt-rBR.

**(b) Falta — o quadro real (auditado nesta r2):** **LibraryScreen** (fora da janela do
jogo) não consulta a camada universal:
- confirm fica no default do Compose (BUTTON_A/DPAD_CENTER ativam o item focado) —
  ignora FaceStyle/`swapOkCancel`/`hub.confirmKeyCodeFor`;
- atalhos em raw keycodes (`KEYCODE_BUTTON_B/Y/X/L1/R1/SELECT/START`,
  LibraryScreen.kt:820-944) — sem tradução lógica;
- thresholds de bootstrap hardcoded: **0.5** (hat) e **0.6** (stick) nas linhas 781-784
  — não usam `hub.menuDeadzoneFor` (a global do menu é 0.45,
  `PrefManager.gamepadMenuStickDeadzone`).

**(c) Validar agora:** U6 é independente do gate (LibraryScreen é janela normal —
view-level é o nível CERTO ali, sem bus navigator); o spec do U6 escolhe entre consumir
`GamepadInputEvent` do hub ou manter Compose focus + helpers do hub
(`confirmKeyCodeFor`/`menuDeadzoneFor`); o mecanismo de bootstrap de foco (retries +
fallback) é preservado como está.

**(d) Restrições:** nenhuma.

## U7 — Bateria e capacidades por device (adição da r2 — custo mínimo)

**(a) Existe:** `GamepadDevice` + seletor de device reativo (`connectedDevices` StateFlow).
**(b) Falta:** `InputDevice.getBatteryState()` (**API 31**) exposto no model + UI (%
no seletor de controles); capacidades (tem gyro? tem touchpad?) derivadas da mesma
coleta — infra compartilhada com o capability check do U1 (V11).
**(c) Validar agora:** coleta é pull e fora do hot path (só em hotplug/abertura de UI).
**(d) Restrições:** API 31 — modern+legacy com runtime guard; Ausência = ícone oculto.

---

# PARTE IV — CRITÉRIOS DE PRONTIDÃO (checklist de validação, por item)

Validação da implementação atual — rodar antes de qualquer upgrade começar:

- [ ] **V1 — Schema de perfil forward-compatível:** carregar um `device_profiles.json`
  atual num build com campos novos fictícios (teste JVM com JSON contendo chaves
  extras) → `ignoreUnknownKeys` mantém os campos conhecidos. **Política DECIDIDA
  (r2):** o store preserva chaves desconhecidas por entrada no save (parse/serialize
  via JsonObject mantendo os extras) — implementação obrigatória no primeiro spec que
  adicionar campo novo (U1). Justificativa: downgrade de build é real (canais beta);
  perda silenciosa de config do usuário é pior que o custo do passthrough. Hoje o save
  REGRAVA o mapa inteiro (GamepadProfileStore.kt:58-72) e perderia as extras.
- [ ] **V2 — Hot path auditado:** nenhum `file.readText`/`decodeFromString` no caminho
  `onKey/onAxis` (cache M1 — já auditado; re-verificar após cada upgrade).
- [ ] **V3 — Fontes novas fora do dispatch:** qualquer fonte nova (sensores por callback,
  timers de touchpad) documenta o loop/lifecycle próprio; nenhum coroutine entra no
  `dispatchKeyEvent`/`dispatchGenericMotionEvent`; listener de sensor é UNREGISTERADO
  em pause/exit (vazamento = bateria).
- [ ] **V4 — Vocabulário lógico suficiente:** `InputEvent` cobre o upgrade sem
  refatoração (SensorUpdate/TouchpadMotion já cobrem U1/U2; U3–U5 são internos ao
  perfil/processors).
- [ ] **V5 — Pureza JVM:** cada processor novo nasce `object` puro + adapter fino
  (padrão EventTranslator/AndroidInputAdapter); teste JVM cobre as decisões.
- [ ] **V6 — Estados por device:** todo estado entre amostras vive no hub keyed por
  deviceId e morre em `removeDevice` (padrão `buttonStates`, GamepadHub.kt:83).
- [ ] **V7 — Ghost gate protegido:** nenhum upgrade cria caminho que bypassa o gate de
  ghost input do MainActivity (regra permanente). Exceção única: consumidor do U2
  plugado ANTES do consume, no mesmo ponto.
- [ ] **V8 — Invariante de overlay:** com qualquer overlay aberto, o jogo não recebe
  input — re-testado (T1–T9/V1–V10) após cada upgrade tocar o roteamento.
- [ ] **V9 — Strings:** toda label nova em EN + pt-rBR; glyphs via
  `GamepadGlyphProvider`.
- [ ] **V10 — Suites:** `*gamepad*` + `*Gamepad*` + `*Shader*` + `*SearchField*`
  verdes após cada upgrade (nunca a suíte completa).
- [ ] **V11 — Capability gating (novo):** toda feature API 31+ (gyro, bateria,
  VibratorManager) tem runtime check + degradação silenciosa (UI esconde, log diz por
  quê). MinSdk modern=29/legacy=26 < 31 torna isso regra, não exceção.
- [ ] **V12 — Harness estendido (novo):** `debug.gamenative.input` aceita novos
  verbos conforme os upgrades (`gyro:x:y:z`, `touch:x:y`, `touchtap`) — sem isso a
  verificação on-device de U1/U2 fica bloqueada no MIUI (adb input bloqueado).
  Protocolo documentado em `DebugGamepadInput.kt`.

---

# PARTE V — RISCOS DE REGRESSÃO E PROTEÇÕES

| Risco | Proteção |
|---|---|
| Perda cross-version de campos novos no save de build antigo (V1) | política r2: store preserva chaves desconhecidas (implementar no spec do U1) |
| Upgrade novo tocando o roteamento de janela (C1/C2 reciclado) | V8 + gate `gamepadUniversalEnabled` como kill-switch até verificação on-device |
| XServerScreen no limite do dex | nenhum upgrade adiciona locals na função principal; lógica vive no pacote `gamepad/` |
| Fontes pull/callback entrando no hot path | V3 — regra registrada neste documento |
| Listener de sensor vazando após exit/screen-off (bateria) | V3 (unregister em pause/exit) + verificação on-device no spec do U1 |
| Gyro ausente por conexão (USB × BT) | capability check por conexão + UI esconde (V11); `refreshDevice` já re-classifica |
| Dois sistemas de remap no jogo (universal × Winlator) | precedência proposta no U4 (universal explícito vence; senão byte-identical); default = comportamento atual até lá |
| Rumble do jogo subestimado (ponte Wine→Vibrator) | dimensionar no spec do U5 antes de estimar |
| Dead code acumulando | auditoria de callers a cada upgrade (padrão D1/M7 do hardening) |

---

## Matriz U×V (pré-requisitos por upgrade)

| Upgrade | V-critérios obrigatórios no spec de implementação |
|---|---|
| U1 gyro | V1, V2, V3, V4, V5, V6, V11, V12 |
| U2 touchpad | V2, V4, V5, V7, V8, V12 |
| U3 layers | V1, V4, V5, V6, V8 |
| U4 remap no jogo | V2, V8, V10 |
| U5 rumble | V5, V11 |
| U6 OK/Cancel Library | V8, V9, V10 |
| U7 bateria | V11 |

## Ordem sugerida dos upgrades (por demanda + custo)

1. **U6** (LibraryScreen — fecha a inconsistência de OK/Cancel; custo baixo;
   independente do gate universal).
2. **U2** (touchpad→mouse — 2º pedido mais votado; plug point pronto).
3. **U1** (gyro→mouse/câmera — 1º pedido; maior custo: fonte API 31 + lifecycle +
   calibração; a política V1 entra junto).
4. **U3** (layers completas — depois de U1/U2, pois chords com gyro/touchpad é onde o
   conceito brilha).
5. **U4** (remap no jogo), **U5** (rumble) e **U7** (bateria — pode carona com U1) —
   por demanda.

Cada upgrade gera spec próprio (padrão do repo: spec → revisão → implementação → impl
doc → MILESTONES) referenciando este documento como declaração de intuito.
