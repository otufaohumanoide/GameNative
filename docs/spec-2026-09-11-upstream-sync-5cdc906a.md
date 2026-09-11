# Spec 2026-09-11 — Sync com upstream `5cdc906a` (upstream-first) + absorção do gyro upstream

**Data:** 2026-09-11
**Origem:** relato do usuário — a atualização do fork a partir do projeto original
gerou conflitos. A causa foi investigada por reprodução do merge em clone descartável
(evidências na §0); este spec é o manual de execução da resolução.
**Escopo:** merge de `upstream/master` (`5cdc906a`) em `master` (`c1e15539`);
resolução dos **36 hunks em 9 arquivos**; remoção do gyro do fork em favor do #1882
do upstream; preservação das features do fork sem equivalente; verificações e
processo de prevenção de recorrência.
**Fora de escopo:** propor shaders/librashader no upstream (decisão 3 do usuário);
refatoração ampla de costuras (é backlog da §7); qualquer mudança no renderer Vulkan
(não há conflito neste sync).

**Decisões do usuário (2026-09-11):**

| # | Decisão | Efeito neste spec |
|---|---|---|
| 1 | Gyro: prioridade total à implementação upstream; evoluir a partir dela se houver contexto aproveitável; se não, apagar | §4 remove o motor/UI do gyro do fork; §5 registra o backlog de evolução sobre o `GyroController` upstream |
| 2 | Resolução do merge: prioridade à implementação upstream; código do fork só permanece quando aproveitável | §1 fixa a política P1–P5; §3 dá a receita por arquivo |
| 3 | `feat/retroarch-shaders`: manter somente no fork (sem PR upstream) | branch não participa deste sync; shaders ficam intocados (não conflitaram) |

---

## 0. Diagnóstico reproduzido

Esta seção existe para justificar cada edição da §3. Tudo abaixo foi reproduzido, não
inferido: o merge foi executado num clone descartável em `/tmp/opencode` (o clone de
trabalho do usuário não foi tocado).

### 0.1 Números do sync

| Item | Valor |
|---|---|
| Merge base (último sync, 26/08) | `1ad70ae54dcac1b9ae50d0b42c816e95f40cf22f` |
| Fork (`master` hoje) | `c1e15539a17499556eec1bb97ec132f3df874caa` |
| Upstream hoje (11/09) | `5cdc906aca99b4f2b628104c867545f4c794a83f` |
| Commits novos do upstream (sem merges internos) | 36 |
| Diff do upstream no período | 283 arquivos, +34.692/−1.356 |
| Diff do fork vs. base | 348 arquivos, +180.237/−955 (124.302 linhas são `assets/retroarch/catalog.json`) |
| Arquivos só do fork (mesclam limpos) | 327 |
| Arquivos tocados pelos dois lados | 21 |
| Arquivos em conflito | **9 (36 hunks)** |

### 0.2 Causa raiz

1. **O fork edita por dentro de arquivos que o upstream desenvolve ativamente.** As
   features de gamepad do fork moram em `PhysicalControllerHandler.kt`,
   `ControlElement.java`, `XServerScreen.kt`, `PrefManager.kt` e nos diálogos de
   binding — os mesmos arquivos que o upstream refatora. Conflito textual aqui é
   consequência estrutural, não acidente.
2. **O upstream implementou features sobrepostas nas duas últimas semanas:**
   - #1833 `b6258af8` "simultaneous and sequential multi-action bindings" —
     introduz `com/winlator/inputcontrols/BindingCombo.java` e reescreve o caminho de
     emissão de binding (`emitBinding` → `handleBindingInputEvent`, `getBindingAt` →
     `getBindingComboAt`) em `ControlElement`, `PhysicalControllerHandler` e nos
     diálogos de binding/touch gesture. Colide com as camadas/expressões/chords/turbo
     do fork.
   - #1882 `4cdc9473` "configurable gyro aiming and tilt steering" (precedido de
     #1878 `3299aef9` e revert `435c9e31`) — implementa gyro próprio em
     `PhysicalControllerHandler`, `ControlElement`, `ShooterModeSettingsDialog`,
     `SettingsDialogBlocks` e `XServerScreen`. Colide com o gyro v1/v2 do fork.
   - #1891 `627bf6f8` mouse relativo no Shooter Mode; #1870 `c94df3c3` pareamento de
     Joy-Con; #1864 `036c135d` player 1 por primeiro input — todos na mesma região.
3. **Renames, não só adições.** O upstream trocou a API que o fork estendeu; por isso
   `ControlElement.java` tem 11 hunks e `PhysicalControllerHandler.kt` tem 7.
4. **Janela de sync de 16 dias.** O sync anterior (26/08) já exigira um commit
   corretivo de compilação (`78700638`); quanto maior a janela, maior a sobreposição.
5. **Sem processo**: o clone não tem remote `upstream` nem `rerere` configurados, e não
   existe checklist de sync documentado.

### 0.3 Descoberta crítica: o gyro do upstream é do CELULAR

Não são a mesma feature, e isso muda a decisão de remoção:

| | Upstream (#1882) | Fork (U1/G-v2) |
|---|---|---|
| Fonte do sensor | `Context.SENSOR_SERVICE` → `Sensor.TYPE_GYROSCOPE` + game rotation vector (**gyro do celular/tablet**) | `InputDevice.getSensorManager()` (API 31+) → **gyro do controle** |
| Arquivo central | `app/src/main/java/com/winlator/widget/GyroController.java` (538 linhas) | `app/src/main/java/app/gamenative/gamepad/GamepadSensorSource.kt` |
| Escopo da config | Por container (`data/GyroSettings.kt`, dialog próprio) | Por device (`GamepadProfile`) |
| UI | Entrada no QuickMenu + `GyroSettingsDialog` | Seção Gyro no `GamepadRemapDialog` + calibrar grip no card de device |
| Recursos | modos stick/mouse, activation always/ratchet, sensibilidade, inversão, smoothing ms, steadying, tilt steering | One Euro, sensibilidade por eixo, sub-pixel, ativação por botão, grip angle, shaping de câmera |

Consequência prática: **adotar o upstream 100% mantém gyro do celular e perde o gyro
do controle** — que o upstream não tem. O relato do usuário ("não parecia funcionar
com os joysticks que conectei") é compatível com limitação de HID/API do aparelho, mas
é o único caminho existente. Por isso a §4 remove o motor do fork e a §5 registra a
evolução futura *sobre o motor upstream* (fonte de sensor de controle), recuperável do
histórico do git.

### 0.4 Shaders não têm parte no conflito

`app/src/main/java/com/winlator/renderer/VulkanRenderer.java` e todos os arquivos de
shader (`VulkanLibrashader.*`, `ShaderBrowserOverlay.kt`, catálogo, store por jogo)
**auto-mesclaram**. O upstream tocou o renderer só no #1879 `546a162d` (VR imersivo,
guardas `flatPresentationEnabled` em `onMapWindow`/`onChangeWindowZOrder`/
`queueSceneUpdate`), em regiões diferentes dos call sites do librashader — mas a §6
inclui verificação semântica porque auto-merge não garante comportamento.

### 0.5 Reprodução e artefatos

O merge real foi reproduzido com:

```bash
git clone --bare --reference /home/marcos/GameNative \
  https://github.com/otufaohumanoide/GameNative /tmp/opencode/gn-fork.git
cd /tmp/opencode/gn-fork.git
git remote add upstream https://github.com/utkarshdalal/GameNative
git fetch --no-tags upstream master
git merge-base master upstream/master            # 1ad70ae5
git merge-tree --write-tree --name-only master upstream/master
```

O worktree `/tmp/opencode/gn-merge` rodou `git merge --no-commit upstream/master` e os
arquivos com marcadores foram copiados para `/tmp/opencode/conflicts/`. Use-os como
gabarito durante a resolução; podem ser apagados no final.

### 0.6 Mapa dos conflitos

| Arquivo | Hunks | Causa upstream | Natureza |
|---|---|---|---|
| `app/src/main/java/com/winlator/inputcontrols/ControlElement.java` | 11 | #1833/#1882 | Refactor de API + touchpad |
| `app/src/main/java/app/gamenative/ui/screen/xserver/PhysicalControllerHandler.kt` | 7 | #1833/#1882/#1870 | Semântico profundo |
| `app/src/main/java/app/gamenative/ui/component/dialog/ControllerBindingDialog.kt` | 4 | #1833 | Semântico (mesma UI) |
| `app/src/main/java/app/gamenative/ui/component/dialog/TouchGestureSettingsDialog.kt` | 4 | #1833 | Semântico (mesma UI) |
| `app/src/main/java/app/gamenative/ui/component/dialog/ShooterModeSettingsDialog.kt` | 4 | #1882/#1891 | Refactor + adjacência |
| `app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt` | 2 | #1870/#1877 | Hotplug duplicado + adjacência |
| `app/src/main/java/app/gamenative/ui/component/dialog/SettingsDialogBlocks.kt` | 2 | #1833/#1882 | Extração de helpers |
| `app/src/main/java/app/gamenative/PrefManager.kt` | 1 | #1707 | Aditivo |
| `app/src/main/java/app/gamenative/ui/screen/settings/SettingsGroupInterface.kt` | 1 | #1799/#1881 | Aditivo |

---

## 1. Política de resolução

Aplique nesta ordem, sem exceção:

| # | Regra | Justificativa |
|---|---|---|
| P1 | Em qualquer hunk, **a versão do upstream vence** | Decisões 1 e 2 do usuário; o upstream é o dono dos arquivos e sua versão é a que continuará evoluindo |
| P2 | O fork só reescreve código upstream quando for **necessário para uma feature do fork sem equivalente** (lista P3) | Minimiza hunks futuros: cada linha do fork dentro de arquivo upstream é uma dívida de conflito |
| P3 | Features do fork **mantidas** | São a identidade do fork e não têm equivalente upstream |
| P4 | Features do fork **removidas** neste sync | Duplicatas do upstream, com risco de comportamento duplo (sensor, UI, dados) |
| P5 | Nunca reformatar, renomear ou "melhorar de passagem" código upstream | Mudança cosmética cria conflitos artificiais no próximo sync |

**P3 — mantidas (com o arquivo principal):** pipeline universal K1 (gamepad virtual de
toque — `TouchGamepadSource`/`GamepadHub`), camadas/remap universal U3/U4
(`LayerResolver`, `applyUniversalKeyRemap`), expressões/chords J (`gamepad/expressions`),
trigger engine/modifiers H/I, turbo/mode shift/radial v2 F (`RadialMenuOverlay`,
`TurboScheduler`), touchpad→mouse U2 (`GamepadTouchpadForwarder`), swipes/macros D,
perfis por device/autoconfig/quirks/catálogo E e K3–K6 (`gamepad/profiles`,
`gamepad/mapping`), card de device/input viewer C, rumble por device A/U5, bateria U7,
HUD de latência, navegação gamepad do QuickMenu/browser, e todo o shader/librashader.

**P3 — mantida com ressalva:** flick stick (`FlickStickProcessor`) — não tem
equivalente upstream e sobrevive ao merge, mas depende de duas utilidades nascidas no
gyro do fork (ver §4.3). Validar on-device; se não funcionar, remover em follow-up.

**P4 — removidas:** motor/UI/dados do gyro do fork (`GamepadSensorSource`,
`GyroProcessor`, `GyroFusion`, `GyroActivation`, `OneEuroFilter`, `GyroMode`, campos de
gyro no `GamepadProfile`, seção Gyro do remap, verbo `gyro:` do harness), porque o
upstream passa a ser dono do gyro.

### Como usar os comandos de resolução

Durante o merge, **`--ours` é o fork (`master`) e `--theirs` é o upstream**. Com a
política P1, o atalho é `git checkout --theirs -- <arquivo>` e depois reaplicar a
costura quando houver (arquivos com P2). Nunca comece resolvendo "os dois lados" de um
arquivo por padrão — isso é exatamente o que cria a dívida que este spec está pagando.

---

## 2. Preparação

1. Garanta ambiente (esta máquina):

```bash
# local.properties não existe no clone — crie (não versionado):
echo "sdk.dir=/home/marcos/Android/Sdk" > /home/marcos/GameNative/local.properties
# Java do Android Studio:
export JAVA_HOME=/home/marcos/android-studio/jbr
# submódulos (já presentes nesta máquina):
git submodule status
```

2. Configure e prepare o sync:

```bash
cd /home/marcos/GameNative
git remote get-url upstream >/dev/null 2>&1 || \
  git remote add upstream https://github.com/utkarshdalal/GameNative
git fetch --no-tags upstream master
git config rerere.enabled true          # reaproveita resoluções futuras
git config rerere.autoupdate true
# ponto de retorno antes de mexer:
git tag -a pre-sync-2026-09-11 -m "antes do sync com upstream 5cdc906a" master
git switch -c sync/upstream-2026-09 master
```

3. Preflight (deve listar os mesmos 9 arquivos da §0.6; se divergir, pare e
   reavalie — o upstream pode ter andado):

```bash
git merge-tree --write-tree --name-only master upstream/master || true
```

4. Faça o merge sem commit para inspecionar:

```bash
git merge --no-ff --no-commit upstream/master
git diff --name-only --diff-filter=U
```

---

## 3. Resolução por arquivo

Ordem sugerida: aditivos primeiro (ganho rápido, menos risco), depois diálogos, por
último o núcleo (`ControlElement` e `PhysicalControllerHandler`). Ao final de cada
arquivo, confira com o gabarito em `/tmp/opencode/conflicts/`.

### 3.1 `app/src/main/java/app/gamenative/PrefManager.kt` (1 hunk)

**Conflito:** o fork anexou o bloco de prefs de gamepad (`gamepadUniversalEnabled`,
deadzones de stick/menu, `virtualGamepadPipeline`, touchpad, rumble, HUD) no mesmo ponto
em que o upstream anexou `PREFERRED_FAMILY_LENDERS_JSON` + `preferredFamilyLenders` +
`setPreferredFamilyLender` (#1707 — escolha de cópia Steam Family). As chaves não
colidem (`gamepad*`/`debug*` vs. `preferred_family_lenders_json`).

**Resolução:** manter **os dois blocos**, na ordem que estava em cada lado. Não mover
nada de lugar.

**Por quê:** as prefs do fork são lidas pelo `GamepadHub` em tempo de execução (removê-las
quebraria os gates das features P3) e as do upstream sustentam o #1707. É colisão de
posição, não de significado.

### 3.2 `app/src/main/java/app/gamenative/ui/screen/settings/SettingsGroupInterface.kt` (1 hunk)

**Conflito:** o fork adicionou os switches "Home direto para o jogo" (spec 2026-08-13)
e "ignorar touchpad do controle" (gate de ghost input) imediatamente antes do
`var showRecommendations`; o upstream alterou a região de recomendações (#1799/#1881).

**Resolução:** manter os dois switches do fork e a versão upstream da seção de
recomendações. Nenhum compartilha estado.

**Por quê:** os switches são a única interface de dois gates que o fork usa
(`homeButtonStraightToGame`, `ignoreControllerTouchpad`); removê-los deixaria features
ligadas sem controle. As linhas do upstream não são tocadas.

### 3.3 `app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt` (2 hunks + remoções do gyro)

**Conflito 1 — hotplug:** o fork roteava `GamepadDeviceAddedEvent` por um handler
`handleAdded`; o upstream introduziu `InputManager.InputDeviceListener` canônico no
arquivo (onInputDeviceAdded/Removed/Changed — #1870, correção de pareamento de Joy-Con).

**Resolução:** ficar com o listener do upstream e, **dentro dele**, emitir os eventos de
bus do fork (`GamepadDeviceAddedEvent`/`GamepadDeviceRemovedEvent`) com o mesmo payload
que o handler antigo produzia. Remover o listener/handler duplicado do fork.

**Por quê:** manter os dois registry de hotplug duplicaria
`ControllerManager.onDeviceConnected`/`onDeviceDisconnected` e o `scanForExternalDevices`
(correção #1870 fica inútil e o hub do fork recebe eventos em dobro). O adaptador de uma
linha preserva o contrato do bus sem brigar com a lógica nova.

**Conflito 2 — teardown:** linha `PluviaApp.gamepadSensorSource.setSuspended(true)` (fork)
vs. `PerfSampler.halt()` (upstream #1877).

**Resolução:** remover a linha do sensor (o objeto será deletado na §4) e manter
`PerfSampler.halt()`.

**Remoções adicionais no arquivo (consequência da §4):** chamadas
`PluviaApp.gamepadSensorSource.setSuspended(...)` no exit/start e
`PluviaApp.gamepadHub.gyroCameraSink = null` + o lambda que seta
`physicalControllerHandler?.applyCameraGyro(...)`.

**Por quê:** o sensor passa a ser registrado pelo `GyroController` do upstream via
`InputControlsView`; manter o registro do fork significaria dois listeners no mesmo
sensor (entrada dobrada e bateria). O `gyroCameraSink` só existia para o modo CAMERA do
gyro do fork.

### 3.4 `app/src/main/java/com/winlator/inputcontrols/ControlElement.java` (11 hunks)

**Conflito:** o upstream substituiu a emissão direta por `handleBindingInputEvent(...)`
em três métodos (`handleBindingInputEvent(int, boolean)`,
`handleBindingInputEvent(int, boolean, float)`, `handleSimultaneousBindingMembers`) e
introduziu `BindingCombo`, `getBindingComboAt`, `usesToggleSwitch()`,
`releaseActiveDirectionalStates()` e `RangeScroller.cancelTouch()`. O fork tinha criado
o wrapper `emitBinding(...)` (~linha 1100) para desviar bindings de GAMEPAD do overlay
para o `TouchGamepadSource` quando o pipeline virtual está ligado (K1).

**Resolução:** ficar com o upstream integralmente e reintroduzir **apenas** o wrapper,
reancorado na API nova:

```java
private void emitBinding(Binding binding, boolean isActionDown, float offset) {
    if (PrefManager.INSTANCE.getVirtualGamepadPipeline()
            && PrefManager.INSTANCE.getGamepadUniversalEnabled()
            && binding != null && binding.isGamepad()) {
        app.gamenative.gamepad.virtual.TouchGamepadSource
                .emitBinding(binding, isActionDown, offset);
    } else {
        inputControlsView.handleInputEvent(binding, isActionDown, offset);
    }
}

private void emitBinding(Binding binding, boolean isActionDown) {
    emitBinding(binding, isActionDown, 0f);
}
```

Regras de integração:

- Nos dois `handleBindingInputEvent`, trocar as emissões de **binding único**
  (`inputControlsView.handleInputEvent(binding, ...)`) por `emitBinding(...)`; manter
  intactos os caminhos de `GYRO_MODIFIER` (que passam `bindingSources[index]`) e de
  `BindingCombo` (que passam o combo inteiro).
- Em `handleSimultaneousBindingMembers`, trocar as emissões por membro
  (`inputControlsView.handleInputEvent(binding, true/false, offset)`) por
  `emitBinding(...)` — cada membro é um `Binding` simples e deve respeitar o pipeline
  virtual.
- Combos **sequenciais** continuam 100% no caminho do upstream (a temporização vive no
  `InputControlsView`/`GamepadState`); documentar no KDoc do wrapper que combo
  sequencial + pipeline virtual usa o caminho legado (limitação conhecida e aceitável).

**Por quê:** K1 não tem equivalente upstream e é opt-in (default OFF), mas é a única
forma de o overlay de toque virar device no hub universal. Concentrar a rota num helper
único significa que o próximo rename do upstream quebra em **um** ponto, não em 11.
Não reimplementar `handleBindingInputEvent` no estilo antigo do fork: isso descartaria
os combos #1833 (violação de P1).

**Verificação de compilação:** `emitBinding`/`getBindingAt` aparecem em
`TouchGamepadSource.kt`, `InputControlsView.java`, `ControlAppearanceEditor.kt`,
`ElementEditorDialog.kt` e `InputControlsProfileCopy.kt` — compile e confira que cada
chamada restante é de método próprio, não do `ControlElement` renomeado.

### 3.5 `app/src/main/java/app/gamenative/ui/screen/xserver/PhysicalControllerHandler.kt` (7 hunks)

**Conflito:** o upstream reescreveu os caminhos de key/trigger/motion e o teardown:
`handleTriggerBinding(keyCode, legacyBinding, bindingCombo, ...)` (~linha 565),
`cancelActiveSequences()`, `clearMouseMoveContributions()`,
`JoyConSupport.remapKeyCode(event.device, event)`, mixers de gyro
(`gyroStickMixer`/`onGyroModifierChanged`, fornecidos pelo `XServerScreen`). O fork
tinha, nos mesmos pontos: gate de remap universal (`applyUniversalKeyRemap`), remap de
L2/R2 (`l2Remap`/`r2Remap` + `handleRemappedAxis`), liberação de
`remappedAxisBindings`, turbo (`turboStates`), flick stick
(`flickOwnsRightStick`/`flickStickProcess`), gyro CAMERA (`cameraOwnsRightStick`) e o
listener do gamepad virtual.

**Resolução:** ficar com o upstream em todos os hunks e reancorar apenas:

| Reancoragem | Onde | Por quê |
|---|---|---|
| Gate do remap universal | no caminho de key, **depois** do `JoyConSupport.remapKeyCode` do upstream | o remap do Joy-Con é correção de hardware e precisa enxergar o keycode cru; o remap do fork é camada lógica e vem depois (binding explícito vence, senão byte-identical — V10/spec U4) |
| L2/R2 | onde o upstream chama `handleTriggerBinding(...)`: se `gamepadUniversalEnabled` e `hub.layerBindingFor(deviceId, LEFT/RIGHT_TRIGGER) != null`, chamar o `handleRemappedAxis` do fork; senão chamar o upstream **com o `bindingCombo` original** | preserva combos #1833 no caminho normal; o remap do fork só intercepta quando há binding explícito |
| Teardown | manter `cancelActiveSequences()` + `clearMouseMoveContributions()` do upstream e **acrescentar** a limpeza do fork (release de `remappedAxisBindings`, cancelamento de `turboStates`, `PluviaApp.events.off` do device virtual, `TouchGamepadSource.unregister`, cancelamento do `mouseMoveTimer`) | cada subsistema tem estado próprio; sem a limpeza o próximo `onStart` herda botão preso (disciplina já documentada no fork) |
| Início do processamento de motion | manter a liberação de eixos remapeados do fork, se o upstream não a cobre | mesma razão acima |
| Flick stick | manter o bloco `flickOwnsRightStick` que seta `thumbRX/thumbRY`, **sem** o gate de `cameraOwnsRightStick` (gyro morre na §4) | feature P3 sem equivalente; o único acoplamento com o gyro era a posse compartilhada do right stick, que deixa de existir |
| Gate de touchpad puro (P5) e listener `virtualGamepadListener` | manter como estavam | sem equivalente upstream; necessários para o pipeline universal |

**Remover do arquivo:** `cameraOwnsRightStick`, qualquer referência a
`GyroMode`/`GyroProcessor`/`GamepadSensorSource`, e `applyCameraGyro` (consumido só
pelo sink do gyro).

**Por quê:** o handler é o coração do input no jogo; a ordem dos gates define
comportamento. Ficar com o upstream e reancorar o mínimo evita reintroduzir a
concorrência entre dois sistemas de gyro pelo mesmo stick direito (o `gyroStickMixer`
do upstream passa a ser o único dono) e preserva os combos novos.

### 3.6 `app/src/main/java/app/gamenative/ui/component/dialog/ControllerBindingDialog.kt` (4 hunks)

**Conflito:** o upstream reescreveu o picker para multi-seleção com `BindingCombo`
(`selectedBindings`, `BindingCombo.MAX_BINDINGS`, `forEach`, parâmetro `enabled`,
`.clickable(enabled=...)`, import `Color`); o fork tinha `forEachIndexed`,
`gamepadSelectable` e `FocusRequester` para navegação por controle.

**Resolução:** ficar com a lógica do upstream (seleção/toggle/limite/`isNone`) e
reaplicar o foco:

- voltar a usar `filteredBindings.forEachIndexed { index, binding -> ... }` apenas para
  aplicar `initialFocusRequester` na primeira linha (ou equivalente);
- trocar `.clickable(enabled = enabled, onClick = onClick)` por `gamepadSelectable(...)`
  **com guarda explícita de `enabled`** nos lambdas (padrão já usado no catálogo de
  perfis, fase E — `gamepadSelectable` não lê `enabled`);
- manter o `Log.d` e a semântica de toggle do upstream.

**Por quê:** sem foco, o diálogo volta a ser inalcançável pelo controle (regressão da
navegação gamepad, que é feature P3); sem a guarda de `enabled`, uma linha desabilitada
responderia ao A. A lógica de seleção é do upstream — reimplementar seria violar P1.

### 3.7 `app/src/main/java/app/gamenative/ui/component/dialog/TouchGestureSettingsDialog.kt` (4 hunks)

**Conflito:** o upstream trocou o picker de ação única por multi-ação
(`selectedActions`, `selectedSequence`, `selectedSequenceDelayMs`,
`TouchGestureConfig.actionComboOf`, botões simultâneo/sequência, `android.R.string.ok`
com validação); o fork tinha `GamepadFocusScope` + `pickerInitialFocus` +
`gamepadSelectable` e `PAN_ACTIONS`.

**Resolução:** ficar com o picker do upstream e reaplicar o foco:

- envolver a `LazyColumn` no `GamepadFocusScope(backAction = { showDialog = false },
  initialFocusRequester = pickerInitialFocus)` e manter o B fechando;
- aplicar `gamepadSelectable(selected = isSelected, onClick = ...)` nas linhas de
  `categories.forEach`, preservando o estado multi-seleção do upstream;
- **não** ressuscitar `PAN_ACTIONS` nem o fluxo `closePicker` antigo; o botão OK do
  upstream mantém a validação (`selectedActions.isNotEmpty()` etc.);
- manter a regra do fork de IME: navegação por stick não abre teclado; entrada no campo
  só por X/toque.

**Por quê:** o upstream incorporou a funcionalidade (multi-ação + delay) que o fork
tinha em outro vocabulário; manter os dois pickers duplicaria o diálogo. O `GamepadFocusScope`
e o B-close são a camada de navegação do fork e continuam necessários.

### 3.8 `app/src/main/java/app/gamenative/ui/component/dialog/ShooterModeSettingsDialog.kt` (4 hunks)

**Conflito:** dois hunks de imports (aditivos) + dois hunks em que o fork mantinha
implementações inline de dropdown/slider com foco gamepad e o upstream passou a chamar
os helpers compartilhados `SettingsDropdownBlock(...)`/`SettingsSliderBlock(...)` (extraídos
pelo #1882 em `SettingsDialogBlocks.kt`).

**Resolução:** ficar com o upstream (chamadas de uma linha + imports novos; remover
imports órfãos do fork) e não manter as cópias inline.

**Por quê:** as cópias inline são o motivo de o próximo sync ter 3 versões do mesmo
bloco; o upstream já fez a extração. O comportamento de foco gamepad será reaplicado
**uma vez** no bloco compartilhado (3.9), onde todos os diálogos se beneficiam.

### 3.9 `app/src/main/java/app/gamenative/ui/component/dialog/SettingsDialogBlocks.kt` (2 hunks)

**Conflito:** imports (fork: `gamepadAdjustableRow`, `gamepadBackHandler`,
`GamepadFocusScope`; upstream: `border`, `focusable`, `focusProperties`,
`onFocusChanged` etc.) e os blocos: o fork tinha `SettingsDropdownBlock`/slider com
A-lock (`gamepadAdjustableRow`, `focusProperties { canFocus = false }` no `Slider`,
indicador de travado) vs. um-liners do upstream chamando as versões compartilhadas.

**Resolução:** ficar com a implementação do upstream e reaplicar a experiência de
gamepad **dentro do bloco compartilhado**, uma única vez:

- no `SettingsSliderBlock`: usar `gamepadAdjustableRow(locked/onLockChange/onAdjust)` na
  `Row`, com `Slider` em `focusProperties { canFocus = false }` e o passo de ajuste
  `(end - start) / 20f` (A-lock/nudge L/R — comportamento aprovado no milestone
  `milestone-2026-08-09-quickmenu-gamepad-100pct`);
- no `SettingsDropdownBlock`: manter o foco no row (`focusRequester` quando recebido) e
  o dropdown abre pelo A;
- remover imports que ficarem sem uso.

**Por quê:** é a costura de menor impacto que preserva a navegação por controle sem
triplicar código; como o upstream centralizou os blocos, a próxima refatoração dele
toca um arquivo só.

---

## 4. Remoção cirúrgica do gyro do fork

Faça isto **depois** que o merge estiver compilando (commit de merge) e como commit
separado, para o rollback ser limpo. A numeração corresponde às dependências:
remova primeiro a UI/wiring, depois o motor e por fim os dados.

### 4.1 Arquivos deletados

| Arquivo | Justificativa |
|---|---|
| `app/src/main/java/app/gamenative/gamepad/GamepadSensorSource.kt` | Registrava sensor de controle no `GamepadHub`; substituído pelo `GyroController` upstream |
| `app/src/main/java/app/gamenative/gamepad/GyroMode.kt` | Enum do motor do fork |
| `app/src/main/java/app/gamenative/gamepad/processing/GyroProcessor.kt` | Motor do fork |
| `app/src/main/java/app/gamenative/gamepad/processing/GyroFusion.kt` | Fusão Mahony do fork (o upstream não usa) |
| `app/src/main/java/app/gamenative/gamepad/processing/GyroActivation.kt` | Latch de ativação do fork (upstream tem `activationMode` no `GyroSettings`) |
| `app/src/main/java/app/gamenative/gamepad/processing/OneEuroFilter.kt` | Filtro do fork; upstream tem `smoothingMilliseconds` |
| `app/src/test/java/.../processing/GyroActivationTest.kt`, `GyroCalibrationTest.kt`, `GyroFusionTest.kt`, `GyroPixelAccumulatorTest.kt` (manter se a utilidade ficar — ver 4.3), `GyroProcessorTest.kt`, `GyroStickMappingTest.kt` (idem), `OneEuroFilterTest.kt` | Testes do código removido |

### 4.2 Edições em arquivos que ficam

| Arquivo | Ação | Justificativa |
|---|---|---|
| `GamepadProfile.kt` | remover os campos `gyroMode`, `gyroSensitivity`, `gyroSensitivityY`, `gyroDeadzone`, `gyroActivateButton`, `gyroActivateToggle`, `gyroFusion*`, `gyroSmooth*`, `gyroStick*`, `gyroInvert*`, `gyroGripAngleDeg` | dados do motor removido; o `GamepadProfileStore` usa `ignoreUnknownKeys` e **preserva chaves desconhecidas no save (V1)** — perfis antigos não quebram e nenhuma migração é necessária |
| `ProfileCatalog.kt` + `tools/profiles/sync_profile_repo.py` | remover os mesmos campos da allowlist/LUTs sanitizadas | o catálogo comunitário não deve expor config que não existe |
| `GamepadHub.kt` | remover imports/estado/métodos de gyro: `onSensorSample`, `gyroPreview`/`gyroPreviewEnabled`, `gyroActivateHeld`/`gyroActivateLatches`, `gyroCameraSink`; manter `flickStickProcess` | o hub não pode continuar processando amostras de um sensor que não registra mais |
| `PluviaApp.kt` | remover a propriedade/init `gamepadSensorSource` | wiring morto |
| `MainActivity.kt` | remover as chamadas `setSuspended(false/true)` | idem |
| `XServerScreen.kt` | remover chamadas de `setSuspended`, `gyroCameraSink` e o lambda de `applyCameraGyro` (além do que a 3.3 já cobre) | idem |
| `GamepadRemapDialog.kt` | remover a seção Gyro (sensibilidade por eixo, inversão, ativação, smoothing, grip) | UI do motor removido; o gyro agora se configura no `GyroSettingsDialog` upstream |
| `DeviceDiagnosticsCard.kt` | remover o botão "Calibrar grip" e o preview de gyro | dependiam do `GyroProcessor` |
| `PhysicalControllerHandler.kt` | remover `applyCameraGyro` e o que restar de gyro do fork | ver 3.5 |
| `DebugGamepadInput.kt` | remover o verbo `gyro:x:y:z` | ele injetava em `hub.onSensorSample`, que deixa de existir |
| `LatencyTracker.kt` / `LatencyDebugOverlay.kt` | remover a fonte SENSOR da medição | não há mais caminho sensor do fork; manter as fontes KEY/MOTION |
| `res/values/strings.xml` + `values-pt-rBR/strings.xml` | remover as chaves da UI de gyro do fork | strings órfãs; **conferir uma a uma** se não são usadas em outro lugar |
| `AGENTS.md` | remover as menções a `gyro:` e `GamepadSensorSource`/`GyroProcessor` (linhas 25–26) | a documentação de arquitetura não pode apontar para código inexistente |
| specs históricos de gyro (`docs/spec-2026-08-14-gamepad-u1-gyro.md`, `...-upgrades-pendencias.md`, `...-G-gyro-v2*.md`, `...-input-core-avancado*.md`) | **não reescrever**; registrar neste spec que foram superseded pelo #1882 | histórico é evidência; o ponteiro de supersessão fica na §5 |

### 4.3 Utilidades que **ficam** (exceções justificadas)

| Utilidade | Onde é usada | Ação |
|---|---|---|
| `GyroPixelAccumulator` (resto sub-pixel) | `MouseModeProcessor` (K2, modo mouse universal por stick) | manter; renomear/realocar só se o time quiser tirar "Gyro" do nome — não é obrigatório |
| `deflection` de `GyroStickMapping` | `FlickStickProcessor` (flick stick) | mover a função para perto do `FlickStickProcessor` (ou manter o arquivo só com ela) e ajustar o teste remanescente |
| `LatencyTracker` | HUD (F0) | manter, sem a fonte SENSOR |
| `FlickStickProcessor` | flick stick | manter (P3 com ressalva — validar on-device) |

### 4.4 Critério de aceite da remoção

```bash
grep -rnE "GamepadSensorSource|GyroProcessor|GyroFusion|GyroActivation|OneEuroFilter|GyroMode|gyroCameraSink|applyCameraGyro|onSensorSample" \
  app/src/main app/src/test AGENTS.md
```

Pode sobrar apenas: `GyroPixelAccumulator`/`GyroStickMapping` (4.3), palavras "gyro"
em contexto do upstream (`GyroSettings`, `GyroSettingsDialog`, `GyroController`,
`gyroStickMixer`) e specs históricos em `docs/` (com a nota da §5).

---

## 5. Backlog: evoluir o gyro upstream com o sensor do controle

O upstream só cobre o gyro do aparelho. O fork era o único caminho para gyro de
controle; a remoção não pode significar perda de conhecimento. Registrar como
follow-up (não executar neste sync):

1. **Objetivo:** no `GyroController.java` upstream, aceitar como fonte o sensor do
   controle (`InputDevice.getSensorManager()`, API 31+) quando o device tiver
   `Sensor.TYPE_GYROSCOPE`, caindo para o sensor do aparelho quando não tiver.
2. **Como recuperar o know-how:** os commits do fork têm a implementação completa e
   testada (ponteiro de leitura: `fa681240` U1 gyro; `848480a0`/`74ce5136`/`be8a22f7`
   calibração/deadzone/CAMERA; `95fa0eb7`/`b5f7d95e`/`53b83927`/`127cc3f2`/`8d717ee2`
   gyro v2 + revisão; `72af2dc5` lifecycle de sensores; `f33fc076` gamepad virtual).
3. **Ordem:** primeiro validar o gyro upstream no device (o usuário relata que o do
   fork não funcionava nos joysticks testados — não repetir a aposta sem medir); depois
   portar **só a fonte de sensor** para o motor upstream, mantendo `GyroSettings` e o
   `GyroController` como donos da lógica.
4. **Critério de aceite futuro:** com um controle que exponha giroscópio (ex.: DS4 via
   Bluetooth em Android 12+), o modo `MODE_RIGHT_STICK` responde ao movimento do
   controle; sem sensor no device, o comportamento atual (gyro do aparelho) permanece
   byte-identical.

**Por quê manter no backlog:** apagar sem registrar transformaria "não funcionou com
meus joysticks" em "nunca mais será possível"; e a base upstream, sendo mantida pelos
devs originais, é o lugar certo para a evolução (decisão 1 do usuário).

---

## 6. Verificação

### 6.1 Gate obrigatório

```bash
export JAVA_HOME=/home/marcos/android-studio/jbr
# testes filtrados (NUNCA a suíte completa — AGENTS.md)
./gradlew :app:testModernDebugUnitTest \
  --tests "*Gamepad*" --tests "*Shader*" --tests "*SearchField*" --tests "*Touchpad*"
./gradlew :app:assembleModernDebug
```

Observações:
- Os testes de gyro do fork são removidos na §4; a contagem cai, e isso é esperado.
- `GamepadProfileStoreTest`/`ProfileCatalogTest` devem ser ajustados à remoção dos
  campos e continuar verdes (compatibilidade de perfis antigos é pré-requisito).
- Erros de build podem vir da toolchain Rust/librashader (cargo + NDK 27.3.13750724),
  não do merge.

### 6.2 Verificação semântica (o auto-merge não cobre)

| Ponto | O que verificar | Por quê |
|---|---|---|
| `ElementEditorDialog.kt`, `PhysicalControllerConfigSection.kt`, `RadialMenuSettingsDialog.kt`, `InputControlsProfileCopy.kt`, `RadialMenuCoordinator.kt` | compilação + uso correto de `BindingCombo` (auto-mergeados com #1833) | o upstream mudou a API nesses arquivos e o fork tem código adjacente; compilar pega, mas revisar os call sites evita comportamento silenciosamente errado |
| Radial menu | o `RadialMenuCoordinator` upstream (#1709) e o radial do fork (`RadialMenuOverlay`/camadas) não podem responder ao mesmo trigger | dois radiais concorrentes seria confusão de UX e input |
| `QuickMenu.kt` | a entrada de gyro do upstream convive com as abas/HUD/browser do fork | arquivo auto-mergeado grande; checar navegação por gamepad e strings |
| `VulkanRenderer.java` + `VulkanLibrashader` | guardas `flatPresentationEnabled` do #1879 não pulam `queueSceneUpdate`/apresentação do chain librashader no modo imersivo | auto-merge ok, semântica de apresentação é sensível (lições ARMSX2/melonDS) |
| `XServerScreen.kt` | `PluviaApp.gamepadHub` ainda recebe hotplug pelo listener upstream (3.3) | regressão silenciosa de controle se o bus parar de emitir |
| Combo + pipeline virtual | overlay com binding de gamepad (K1) continua sendo entregue ao `TouchGamepadSource` | é a costura P2 do 3.4; testar com `virtualGamepadPipeline` ON e OFF |

### 6.3 Checklist on-device (Mi 11 / Adreno 650 + DS4)

1. **Gyro upstream (novo):** modos stick esquerdo/direito/mouse, ativação always/ratchet,
   inversão X/Y, tilt steering; sem crash quando o aparelho não tem sensor.
2. **Fork (P3):** camadas/remap universal, expressões/chords, turbo/mode shift/radial,
   touchpad→mouse (U2), gamepad virtual (K1), perfis por device/catálogo, rumble por
   device, bateria, HUD de latência.
3. **Novidades do upstream:** combos simultâneo/sequencial no `ControllerBindingDialog`
   e no `TouchGestureSettingsDialog` (com e sem gamepad), Joy-Con, mouse relativo do
   Shooter Mode.
4. **Shaders:** aplicar/trocar preset pelo QuickMenu, browser com foco/dupla-clique,
   favoritos; se houver modo imersivo/VR disponível, checar o ponto 6.2 do renderer.
5. **Flick stick:** validar; se não funcionar, abrir follow-up de remoção.

### 6.4 Commit e registro

- Merge: `chore(upstream): merge master 5cdc906a — gyro upstream #1882, combos #1833,
  VR/XR e Nexus Mods; remove gyro do fork (spec 2026-09-11-upstream-sync-5cdc906a)`.
- Correções pós-merge: commits separados no padrão `fix(...)`, como em `78700638`.
- Atualizar `docs/MILESTONES.md` apenas se o usuário quiser marcar o sync como
  milestone; os merges de upstream anteriores não estão listados lá.

---

## 7. Prevenção de recorrência

### 7.1 Cadência e higiene

1. **Sincronizar a cada 1–2 semanas** (`git fetch upstream && git merge upstream/master`).
   A janela de 16 dias gerou 36 commits e 9 arquivos em conflito; janelas menores
   reduzem a sobreposição e o custo de resolução.
2. **`rerere` ligado** (feito na §2) — resoluções repetidas viram automáticas.
3. **Tag de retorno antes de cada sync** (`pre-sync-<data>`), branch `sync/upstream-<ano>-<mês>`.
4. **Push dos branches** já existentes: manter `feat/joystick-avancado` e
   `feat/retroarch-shaders` como referência histórica; como decidido, shaders não vão
   para o upstream.

### 7.2 `docs/upstream-sync.md` (novo; criar junto com este sync)

Documento vivo com:

- **Mapa de costuras**: os 21 arquivos tocados pelos dois lados (lista da §0.1/§0.6) e,
  para cada um, o que o fork faz ali e a regra de resolução. É o que evita redescobrir
  a causa a cada 2 semanas.
- **Política P1–P5** deste spec, resumida.
- **Log de syncs**: data, hash do upstream, arquivos em conflito, decisões novas.
- **Receita de emergência**: `merge --abort`, tag `pre-sync`, `rerere` e como usar
  `--theirs` sem medo (no merge, `--ours` é o fork e `--theirs` é o upstream).

### 7.3 `tools/upstream-sync.sh` (novo; preflight)

Esqueleto para reportar conflitos **antes** de mergear:

```bash
#!/usr/bin/env bash
# Preflight do sync com o upstream: busca, lista conflitos e diffs. Não mergeia.
set -euo pipefail
UPSTREAM_URL="https://github.com/utkarshdalal/GameNative"
git remote get-url upstream >/dev/null 2>&1 || git remote add upstream "$UPSTREAM_URL"
git fetch --no-tags upstream master
base=$(git merge-base HEAD upstream/master)
echo "merge-base: $base"
echo "commits novos: $(git rev-list --count "$base..upstream/master")"
echo "--- conflitos previstos ---"
git merge-tree --write-tree --name-only HEAD upstream/master || true
echo "--- arquivos tocados pelos dois lados ---"
comm -12 <(git diff --name-only "$base" HEAD | sort) \
         <(git diff --name-only "$base" upstream/master | sort)
```

### 7.4 Endurecimento das costuras (backlog estrutural)

Ordem de prioridade, por quantidade de hunks gerados:

1. `PhysicalControllerHandler.kt` (7 hunks; 33 commits do fork já o tocaram) — extrair
   os hooks do fork para um coordenador próprio (ex.: `UniversalInputHooks`) chamado de
   poucos pontos estáveis; o arquivo upstream não deve ganhar lógica inline do fork.
2. `ControlElement.java` (11 hunks) — manter apenas o helper `emitBinding` (3.4) e nunca
   duplicar a lógica de emissão; KDoc apontando o contrato.
3. `XServerScreen.kt` (~6.000 linhas) — hotplug por adaptador único que emite o bus;
   evitar novos `remember`/estado local (limite do verifier de dex já documentado).
4. Diálogos — foco gamepad como modifiers compartilhados e A-lock nos blocos
   compartilhados (3.9), nunca inline.
5. `VulkanRenderer.java` — manter os call sites do librashader concentrados e
   documentados; o shader é a área que hoje mescla limpa (327 arquivos só do fork) e
   deve continuar assim.

Cada item vira spec próprio quando for executado; aqui são só a direção e a
justificativa (reduzir a superfície de interseção com o upstream sem perder features).

---

## 8. Riscos e pontos abertos

| Risco / ponto | Mitigação |
|---|---|
| Perda temporária do gyro de controle | backlog §5 com ponteiro de recuperação; o gyro do celular (upstream) cobre handheld |
| Flick stick depende de utilidades do gyro | §4.3 mantém as utilidades; validar no device e, se falhar, abrir follow-up de remoção |
| Combo sequencial + pipeline virtual (K1) cai no caminho legado | limitação documentada no KDoc do helper; aceitável porque K1 é opt-in e cobre o caso comum (binding único) |
| Strings órfãs ou remoção excessiva | conferir uso com `grep` antes de apagar cada chave; EN + pt-rBR sempre juntos |
| Duas entradas de sensor ativas | §3.3/§4 removem o registro do fork; teste no device com log de sensor |
| Radiais duplicados (upstream #1709 vs. fork) | verificação semântica 6.2; se colidirem, decidir gate/trigger único antes de fechar o sync |
| `build/` com classes stale mascarando erro | limpar `build/` se o comportamento não bater com o source (gotcha conhecido do repo) |

---

## 9. Referências

- Upstream: `5cdc906aca99b4f2b628104c867545f4c794a83f` (11/09/2026);
  merge base `1ad70ae5` (26/08/2026); fork `c1e15539`.
- PRs que causaram os conflitos: #1833 `b6258af8`, #1882 `4cdc9473` (revert `435c9e31`,
  original #1878 `3299aef9`), #1891 `627bf6f8`, #1870 `c94df3c3`, #1864 `036c135d`,
  #1918 `5cdc906a`, #1917 `ca9ee1e5`, #1877 `a1459a72`, #1879 `546a162d`,
  #1888 `310ae984`, #1800 `b3823860`, #1799 `19f217fe`, #1796 `d9e9764a`,
  #1881 `69c197a8`, #1707 `cd5ecbc8`, #1786 `a6d69ee8`, #1868 `c36053ec`,
  #1865 `c76d3ddc`.
- Reprodução: `/tmp/opencode/gn-fork.git` (bare, descartável),
  `/tmp/opencode/gn-merge` (worktree do merge), `/tmp/opencode/conflicts/`
  (gabarito com marcadores).
- Specs do fork relacionadas: `docs/spec-2026-08-14-gamepad-u1-gyro.md`,
  `docs/spec-2026-08-16-G-gyro-v2.md` e família (superseded pelo #1882 — ver §5),
  `docs/spec-2026-08-13-gamepad-universal-correcao.md`,
  `docs/spec-2026-08-16-master-roadmap-universal-input.md`,
  `docs/spec-2026-08-16-universal-input-fechamento.md`,
  `docs/spec-2026-08-12-shader-ux-facilities.md`, `docs/ARMSX2-librashader-vulkan.md`.
- Comandos de build/teste: `AGENTS.md` (§Build/§Testes) — `JAVA_HOME` obrigatório,
  nunca rodar a suíte completa.
