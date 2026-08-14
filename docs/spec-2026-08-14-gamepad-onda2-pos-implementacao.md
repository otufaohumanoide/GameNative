# Spec 2026-08-14 — Pós-implementação da Onda 2: perf no hot path + alcance do usuário

**Data:** 2026-08-14
**Origem:** revisão de lacunas da Onda 2 (spec 2026-08-13-gamepad-universal-onda2.md,
implementada em `spec-...-onda2-impl.md`). A integração compila (125 testes JVM, 0
falhas; `assembleModernDebug` OK), mas a revisão encontrou: (a) leitura de JSON do disco
**por amostra de motion** no hot path; (b) remap sem caller; (c) keys globais sem UI —
o gate é inalcançável pelo usuário; (d) parâmetros mortos e dead code novos.
**Decisões do usuário:** spec apenas; correções na próxima rodada de implementação,
na ordem M1 → M2 → M3 → M4.
**Referências:** spec-onda2 §1.1 (critério "< 1 ms, sem alocação em rajada"),
`PerGameShaderStore` (padrão de store), AGENTS.md (dead code reprovado, strings
EN/pt-rBR, XServerScreen no limite do dex).

---

## 0. Contexto — o que está em jogo

| # | Lacuna | Evidência | Consequência |
|---|---|---|---|
| L1 | `GamepadHub.onAxis`/`menuDeadzoneFor`/`confirmKeyCodeFor` chamam `profileFor` por evento; `GamepadProfileStore.load` faz `file.readText()` + decode JSON **por chamada** | `GamepadHub.kt:111-134, 159`; `GamepadProfileStore.kt:16, 36-41` | Disco + parse JSON a ~120 Hz por stick — viola o critério de latência da Onda 2 e escala mal com hats (2 eventos/amostra) |
| L2 | `GamepadRemapDialog` existe, compila, mas **nada o abre** e nada salva no `deviceStore` do hub | `gamepad/remap/GamepadRemapDialog.kt` (sem callers); hub não expõe save de perfil | Remapeamento (Fase 5 do spec universal) inacessível ao usuário |
| L3 | Keys globais novas (`gamepadUniversalEnabled`, `gamepadSwapOkCancel`, `gamepadStickDeadzone`, `gamepadMenuStickDeadzone`) existem só no DataStore — sem UI | `PrefManager.kt:1455-1474`; nenhuma seção em settings | O gate não pode ser ligado pelo usuário; OK/Cancel e deadzones inalcançáveis — o flip default=true do E3 fica sem suporte |
| L4 | `deadZone` virou parâmetro morto nos dois navigators (sombreado pelo lookup do hub) | `GamepadBusInput.kt` (navigator), `JoystickFocusNavigator.kt` | API enganosa; call sites que passem deadZone custom perdem o efeito silenciosamente |
| L5 | `GamepadViewBridge.bindingForKey` e `directionFromResult` só têm callers em teste | `GamepadViewBridge.kt` | Dead code em produção (AGENTS.md reprova) |
| L6 | `DeadzoneConfig()` alocado por MotionEvent no `onAxis` | `GamepadHub.kt:162-168` | Alocação desnecessária no hot path (parte da L1) |
| L7 | X/Y re-dispatchados crus no bridge sem consumidor nas superfícies | `GamepadBusInput.kt` (handledKeys) | Consumo sem efeito — documentar a intenção ou reverter para não-consumo |

**Não são lacunas novas (já registradas no impl spec):** verificação on-device O1–O11,
flip do gate default, `tools/milestone.sh` + MILESTONES, LibraryScreen (fora do escopo
da Onda 2 — ver §5).

---

## 1. Design das correções (missões)

### M1 — Cache de perfis no hub (corrige L1 + L6)

**Problema:** `ProfileResolver.resolve` (via `hub.profileFor`) atinge disco + JSON por
evento. O hot path do gamepad não pode pagar isso.

**Fix:**
- `GamepadProfileStore` ganha um **cache em memória por instância** (o store já é
  single-instance no hub): `private var cached: Map<String, GamepadProfile>? = null`;
  `load` lê do cache quando não-null; `save`/`clear` atualizam o cache E o disco.
  Invalidação nunca é necessária fora do store — o arquivo só muda por `save`/`clear`
  deste processo (sem concorrência de escritor).
- O `GamepadHub` ganha um **cache de perfil efetivo** por `(deviceId, appId)` com
  invalidação barata: `private val profileCache = mutableMapOf<String, GamepadProfile>()`
  onde a chave é `"$deviceId:$appId"` e a entrada é invalidada quando o mappingKey do
  device muda (hotplug `addDevice`/`removeDevice` já limpa) e quando o store salva
  (o hub expõe `fun invalidateProfiles()` — chamado pelo caller do remap após salvar).
  Sem invalidação por tempo: as entradas são estáveis dentro da sessão de um container.
- `onAxis` deixa de construir `DeadzoneConfig` por evento: um `private val
  defaultDeadzones = DeadzoneConfig()` no hub (L6).
- `menuDeadzoneFor`/`confirmKeyCodeFor` passam a usar o cache (mesmo caminho do
  `profileFor`).

**Aceite:** um teste JVM do store (`GamepadProfileStoreTest` estendido) prova que
`load` após `save` retorna do cache (sem tocar o arquivo — deletar o arquivo após o
primeiro load e confirmar que o cache serve a leitura seguinte); `onAxis` não referencia
mais `file.readText` (auditoria de código); medição on-device do overhead do hub
(cenário O11) confirma < 1 ms.

### M2 — Seção de settings "Gamepad" (corrige L3 — e destrava o gate)

**Problema:** sem UI, `gamepadUniversalEnabled` nunca liga e o usuário não controla
OK/Cancel nem deadzones.

**Fix (escopo mínimo, padrão dos settings existentes):**
- Nova seção no settings do app (localizar o container de seções existente — NÃO tocar
  no QuickMenu) com 4 controles:
  1. **Suporte universal a gamepads** (switch → `gamepadUniversalEnabled`).
  2. **Trocar OK/Cancel** (switch → `gamepadSwapOkCancel`; subtítulo explicando que
     equivale ao `menu_swap_ok_cancel_buttons` do RetroArch).
  3. **Deadzone do stick (jogo)** (slider 0.05–0.60 → `gamepadStickDeadzone`).
  4. **Deadzone do stick (menu)** (slider 0.05–0.60 → `gamepadMenuStickDeadzone`).
- Strings novas EN + pt-rBR (`gamepad_settings_*` — o catálogo da §5 do spec universal
  já reserva o prefixo; completar as chaves que faltarem).
- Reuso do padrão de linhas de ajuste existente (gamepadSelectable/adjustable rows,
  A-lock nos sliders) para a seção ser navegável por gamepad.

**Aceite:** com o switch ON, `GamepadLogical` aparece no logcat na primeira tecla; com
OFF, o log desaparece; sliders persistem entre reinícios; navegação por gamepad na
seção funciona (harness on-device).

### M3 — Remap alcançável + salvar no store (corrige L2)

**Problema:** o diálogo não tem caller nem persistência.

**Fix:**
- `GamepadHub` expõe `fun saveDeviceProfile(deviceId: Int, profile: GamepadProfile)`
  (salva no `deviceStore` keyed por `mappingKey` e chama `invalidateProfiles()`).
- Ponto de entrada: botão "Remapear controles" na seção de settings do M2 (contexto de
  library/settings — janela de diálogo, padrão `GamepadFocusScope`), que:
  1. lê o device ativo do hub (`activeDevice`); sem device conectado → estado
     desabilitado com hint;
  2. abre `GamepadRemapDialog` com `profile = hub.profileFor(...)` e
     `onSave = { hub.saveDeviceProfile(...) }`.
- O diálogo continua editando a camada DEFAULT (escopo da Fase 5 — sem remap no jogo).

**Aceite:** remap de FACE_BOTTOM persiste em `files/gamepad/device_profiles.json`;
reabrir o diálogo mostra o binding salvo; conflito bloqueia com mensagem; round-trip
export/import via clipboard funciona.

### M4 — Limpeza de API e dead code (corrige L4, L5, L7)

- **L4:** remover o parâmetro `deadZone` de `BusJoystickFocusNavigator` e
  `JoystickFocusNavigator` (o hub é a fonte única; call sites atuais passam o default —
  auditoria antes de remover; se algum caller passar valor custom, ele passa a usar o
  perfil/hub e o comportamento é documentado na revisão).
- **L5:** `bindingForKey` — mover para uso real no `GamepadRemapDialog` (exibição do
  binding atual já usa `bindingFor`; unificar com `bindingForKey` ou remover a função e
  o teste). `directionFromResult` — sem consumidor de produção: **remover** (o
  `stickDirection` compara direto no cru) junto com o caso de teste correspondente.
- **L7:** documentar no KDoc do `BusGamepadKeyBridge` que X/Y são consumidos por
  invariante (o jogo nunca vê input com overlay aberto) e re-dispatchados crus para
  superfícies futuras — decisão explícita, não acidente. (Sem mudança de comportamento
  nesta missão.)

**Aceite:** sem parâmetros mortos nos navigators (grep de callers); `bindingForKey`
usado pelo remap OU removido; `directionFromResult` removido; testes correspondentes
atualizados; 0 warnings novos no `assembleModernDebug`.

---

## 2. Arquivos afetados

| Arquivo | Mudança |
|---|---|
| `gamepad/profiles/GamepadProfileStore.kt` | cache em memória por instância (M1) |
| `gamepad/GamepadHub.kt` | cache de perfil efetivo por (deviceId, appId) + invalidação em hotplug/save; `defaultDeadzones` cached; `saveDeviceProfile`; `invalidateProfiles` (M1, M3) |
| `gamepad/remap/GamepadRemapDialog.kt` | usar `bindingForKey` (ou remover) (M4) |
| `gamepad/GamepadViewBridge.kt` | remover `directionFromResult`; decidir `bindingForKey` (M4) |
| `ui/component/GamepadBusInput.kt` | remover parâmetro `deadZone` do navigator; KDoc do X/Y no bridge (M4) |
| `ui/component/JoystickFocusNavigator.kt` | remover parâmetro `deadZone` (M4) |
| `ui/screen/settings/` (novo arquivo de seção) | seção Gamepad com 4 controles (M2) + botão de remap (M3) |
| `res/values/strings.xml` + `values-pt-rBR/strings.xml` | chaves `gamepad_settings_*` (M2) |
| Testes: `GamepadProfileStoreTest` (+cache), `GamepadViewBridgeTest` (−directionFromResult), novos testes da seção se houver lógica pura | (M1, M4) |
| `docs/MILESTONES.md` | entrada ao final, junto com a verificação on-device pendente |

**Protegidos (ninguém toca):** `EventDispatcher`, `OverlayInputContext`,
`GamepadStickLogic`, `GamepadMoveDedupe`, `PhysicalControllerHandler` (fora do M1-M4),
`XServerScreen.kt` (limite do dex — a seção de settings NÃO vive lá), nativo/Vulkan.

---

## 3. Verificação

### 3.1 JVM
- Suítes filtradas (`*gamepad*` + `*Gamepad*` + `*Shader*` + `*SearchField*`) — nunca a
  suíte completa (AGENTS.md).
- Novos/ajustados: cache do store (leitura servida pelo cache após deletar o arquivo),
  remoção de `directionFromResult`, `bindingForKey` (usado ou removido).

### 3.2 On-device (mesma bateria pendente da Onda 2, estendida)
- **M1/O11+:** com gate ON, medir timestamps `GamepadTrace` → `GamepadLogical`:
  overhead do hub < 1 ms mesmo com perfil salvo (cache quente).
- **M2:** ligar o gate pela UI → `GamepadLogical` aparece; desligar → some; sliders
  persistem; swap OK/Cancel inverte o botão de confirmação no QuickMenu (DS4).
- **M3:** remap → salvar → persistir → reabrir mostra o binding; conflito bloqueado.
- **Regressão:** O1–O11 da Onda 2 + T1–T9/V1–V10/F1–F10 quando a bateria completa rodar.

### 3.3 Aceite global
- `assembleModernDebug` sem warnings novos.
- Nenhum `file.readText` no caminho `onAxis` (auditoria).
- Gate ligável/desligável pela UI; remap alcançável pela UI.
- Entrada em `docs/MILESTONES.md` + `tools/milestone.sh` (após a verificação on-device
  completa, incluindo o flip do gate default=true do impl spec).

---

## 4. Ordem de execução

1. M1 (perf — o mais urgente; desbloqueia a medição O11).
2. M2 (settings — destrava o gate para o usuário).
3. M3 (remap caller — completa a Fase 5).
4. M4 (limpeza de API/dead code).
5. Verificação 3.1 + on-device quando houver dispositivo; flip do gate; milestone.

## 5. Fora de escopo / follow-ups

- **LibraryScreen** (navegação da biblioteca com deadzone 0.45 hardcoded e BUTTON_A
  fixo) — migrar para hub/perfil/FaceStyle num spec próprio (é o padrão comprovado fora
  da janela do jogo; mudança merece verificação dedicada).
- Remap aplicado ao JOGO (camada `layers` no `PhysicalControllerHandler`).
- Gyro → mouse/câmera; touchpad DS4/DualSense → mouse (stubs `SensorUpdate`/
  `TouchpadMotion` já prontos).
- Action Layers completas (chords/toggles); rumble avançado.
- UI de deadzone POR DEVICE (perfil) — hoje só globais têm UI; sliders por device vão
  para dentro do diálogo de remap num spec futuro.
