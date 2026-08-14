# Pendências dos upgrades de gamepad (U1–U7) — auditoria pós-implementação + estudo das referências

**Data:** 2026-08-14
**Origem:** auditoria de validação da implementação dos upgrades do doc de intuito
(`spec-2026-08-14-gamepad-intuito-validacao-upgrades.md` + impl doc
`spec-2026-08-14-gamepad-intuito-upgrades-impl.md`) e estudo dirigido das referências
oficiais da pasta `reference/` para estabelecer COMO cada peça deve funcionar.
**Natureza:** documento de pendências — **não implementa nada**. Cada item traz: evidência
(`arquivo:linha`), o padrão oficial (referência + arquivo) com descrição profunda do
funcionamento esperado, e critérios de aceite. A implementação de cada bloco gera
commit/spec próprio referenciando este documento.
**Estado auditado:** commits `824fa17d..7ec6531f` (U6→U2→U1→U3+U4→U5→U7). 213 testes JVM
filtrados verdes, 0 falhas. Os defeitos abaixo não são cobertos por testes — parte do
aceite é CRIAR o teste que falharia hoje.

**Fontes oficiais consultadas (o que determina como a tecnologia funciona):**

| Referência | Arquivo | Papel neste documento |
|---|---|---|
| **SDL 3** (zlib) | `src/sensor/android/SDL_androidsensor.c`, `android-project/.../SDLControllerManager.java` | Fonte de sensores por device no Android: descoberta, rate, timestamps, entrega, lifecycle; rumble por device (modelo 2 motores) |
| **Dolphin** (GPL-2.0+) | `Source/Core/InputCommon/ControllerEmu/ControlGroup/IMUGyroscope.cpp/.h` | Deadzone angular em °/s e calibração contínua com janela estável (algoritmo completo) |
| **DS4Windows** (MIT) | `DS4Library/DS4Sixaxis.cs` (calibração contínua herdada da **JoyShockLibrary**), `DS4Control/ScpUtil.cs` (`GyroOutMode`) | Calibração contínua em anel de janelas; modos de saída do gyro (Controls/Mouse/MouseJoystick/Swipe/Passthru) |
| **moonlight-android** (GPL-3.0) | `binding/input/touch/AbsoluteTouchContext.java`, `RelativeTouchContext.java` | Thresholds canônicos de tap/duplo-toque/arrasto em Android |
| SDL / Dolphin / DS4Windows combinados | — | Contrato de rumble (baixa/alta frequência × duração × cancelamento) |

**Regra de licença preservada:** as referências foram LIDAS para extrair comportamento
(e o que cada projeto oficial define como correto) — **nenhum código é copiado** (regra
do intuito: "conceito sim, código não"; o SDL además proíbe código gerado por IA em
contribuições a ele — nada daqui é contribuição ao SDL). Toda correção no GameNative é
autoria própria, em Kotlin, com testes JVM do repo.

---

# PARTE I — P1: defeitos funcionais (bloqueiam a verificação on-device)

## P1-1 · Modo CAMERA do gyro é dead code — sink nunca instalado

**Evidência:** `gyroCameraSink` só é atribuído a `null` (`XServerScreen.kt:519`, no
`onDispose`); nenhuma atribuição funcional existe no código todo (grep: 4 ocorrências,
todas no hub + o clear). `PhysicalControllerHandler.applyCameraGyro`
(`PhysicalControllerHandler.kt:312`) não tem nenhum caller. O caminho
`GyroMode.CAMERA` em `GamepadHub.onSensorSample` invoca um sink sempre nulo → no-op.

**Padrão oficial:** SDL — o consumidor (o jogo, no nosso caso o container) **habilita** o
sensor e o dado flui até quem pediu (`SDL_GameControllerSetSensorEnabled` regista o
listener só quando há consumidor; `SDLControllerManager.java:607-617` religa/desliga o
registro conforme a demanda). DS4Windows — o modo de saída do gyro é uma propriedade do
perfil (`GyroOutMode` em `ScpUtil.cs:61-70`: `Controls | Mouse | MouseJoystick |
DirectionalSwipe | Passthru`) e TODO modo listado na UI tem pipeline ligado — não existe
modo selecionável sem destino.

**Funcionamento esperado no GameNative:** quando o `XServerScreen` cria o
`PhysicalControllerHandler` (`XServerScreen.kt:2471`), instala também o sink:
`hub.gyroCameraSink = { dx, dy, s -> handlerRef?.applyCameraGyro(dx, dy, s) }` (holder
vivo — mesma lição C1; o handler é recriado por container). No exit do container e no
`onDispose` existente o sink volta a `null` (já feito). Regra de roteamento: MOUSE e
CAMERA são mutuamente exclusivos por perfil (um campo `gyroMode` — já é assim).

**Aceite:** com perfil `gyroMode=CAMERA` + gate ON + harness `gyro:0:0:-1` repetido, o
right stick do virtual gamepad se move (verificável no log do winHandler) e volta ao
repouso quando as amostras param. Teste JVM: não aplicável (view wiring) — registro no
§[H] do verify script.

## P1-2 · CAMERA acumula em vez de mapear velocidade — e briga com o stick real

**Evidência:** `applyCameraGyro` faz `state.thumbRX = (state.thumbRX + deltaXRad *
scale).coerceIn(-1f, 1f)` — **integra** deltas no valor do stick. Dois defeitos:
1. Quando o gyro para (delta=0), o stick **permanece no último valor** — a câmera
   continua girando para sempre. Nenhuma referência faz isso.
2. `state.thumbRX/RY` são os MESMOS campos escritos por `processJoystickInput` a cada
   MotionEvent do stick físico direito — escritores concorrentes no mesmo estado
   (sensor em 50 Hz × motion a ~120 Hz): com gyro CAMERA ativo e stick em uso, o valor
   transmitido oscila entre os dois últimos escritores (flicker de câmera).

**Padrão oficial (DS4Windows `MouseJoystick` / JoyShockLibrary `GyroMouseStick`):** o
gyro em modo stick é **controle de taxa** — a deflexão do stick é FUNÇÃO da velocidade
angular, não da integral: `deflection = clamp(angVelRadS * sensPerRadS, -1, 1)` (a
convenção JoyShock é expressa em "graus por segundo → deflexão", com sensibilidade
padrão próxima de 1:1 para ~máx deflection em torno de 180–360°/s, ajustável). Parar de
girar ⇒ deflexão volta a 0 automaticamente (nada a re-centrar). DS4Windows expõe ainda a
escolha do stick de saída (`GyroMouseStickInfo.OutputStick` Left/Right) — quando o gyro
controla um stick, ele o FAZ POR CIMA do físico (é um modo do perfil, não uma soma).

**Funcionamento esperado no GameNative:** `applyCameraGyro(deltaXRad, deltaYRad, s)` é
substituído por um mapeamento de velocidade: deflexão X/Y = clamp(velocidade angular
(yaw/pitch) × escala × s, -1, 1), onde a velocidade angular já vem do GyroProcessor
(rad/s — passar a velocidade, não o delta integral; alterar o contrato do sink para
`(yawRadS, pitchRadS, sensitivity)`). Composição com o stick físico: o valor enviado ao
winHandler = valor do stick físico SOBRESCRITO pelo gyro enquanto CAMERA ativo (padrão
DS4Windows: o gyro controla o stick; alternativa futura: composição aditiva no momento
do send, nunca escrita concorrente em campos compartilhados). Suavização opcional
(follow-up): um-poleiro low-pass na deflexão (JoyShock usa smoothing suave) — não
bloqueia o aceite.

**Aceite:** (1) girar o controle e parar → câmera gira e PARA (deflexão decai a 0 junto
com a velocidade); (2) com stick físico + gyro CAMERA ativos simultaneamente não há
flicker (um único escritor do campo por modo); (3) teste JVM do mapeamento
velocidade→deflexão (clamp, sinal, retorno a zero).

## P1-3 · Lifecycle da fonte de sensores incompleto (V3 violado nos dois sentidos)

**Evidência:** `setSuspended(false)` só tem UM chamador: `MainActivity.onResume`
(`MainActivity.kt:422`), condicionado a `PluviaApp.xServerView != null`. Nenhum chamador
no `XServerScreen`. Consequências:
1. **Nunca registra no fluxo comum:** app abre → `onResume` roda com `xServerView ==
   null` (jogo ainda não abriu) → fica suspenso; usuário lança o jogo (container sobe,
   `xServerView` criado) SEM novo `onResume` → sensores nunca registram. O gyro só
   funcionaria se o usuário backgroundasse/foregroundasse o app DEPOIS de abrir o jogo.
2. **Vazamento no exit:** container encerrado com o app em foreground → nenhum
   `setSuspended(true)` → listeners de sensor continuam registrados até o próximo
   `onPause` do app — exatamente o vazamento de bateria que o V3 existe para impedir
   (o impl doc §1.4 declara "unregister em pause/exit"; o "exit" não existe).

**Padrão oficial (SDL):** o registro de sensores é **dirigido pelo consumidor**:
`SDLControllerManager.java` regista (`:607-610`) quando o jogo pede o sensor e
desregistra (`:614-617`) quando o jogo desliga — ciclo de vida ligado ao USO, não só ao
processo. O lado NDK (`SDL_androidsensor.c:202-257`) abre a fila no `SensorOpen` e a
destrói no `SensorClose` — pareamento estrito open/close por fonte.

**Funcionamento esperado no GameNative:** o `XServerScreen` passa a ser o dono da
retomada: no ponto onde o container sobe (perto da criação do
`PhysicalControllerHandler`, `XServerScreen.kt:2471` e no start do container) chama
`PluviaApp.gamepadSensorSource.setSuspended(false)`; no `gameBack`/exit do container
chama `setSuspended(true)` (idempotente — o source já tolera). O `MainActivity.onResume`
mantém o papel de retomar no retorno ao foreground COM jogo de pé, e `onPause` o de
suspender. Invariante resultante: listener registrado ⇔ (container de pé E app em
foreground E device com gyro E perfil com gyroMode ≠ OFF). A última condição é otimização
permitida pelo padrão SDL (registrar só quando há consumidor ativo do dado) — o
`GamepadSensorSource.registerAll` já filtra por `hasGyro`; acrescentar o filtro por
`gyroMode ≠ OFF` do perfil efetivo (consultado no momento do register, fora do hot path)
economiza bateria quando o usuário não usa gyro.

**Aceite:** (1) lançar o jogo direto do app (sem background/foreground) → log
`GamepadSensor: gyro registered`; (2) sair do jogo (app em foreground) → log `gyro
unregistered` e `dumpsys sensorservice` sem listener residual; (3) §[H] do verify script
conta registered == unregistered ao final da sessão.

## P1-4 · Histerese da deadzone do GyroProcessor invertida (flicker na banda)

**Evidência:** `GyroProcessor.kt:83-87`: `threshold = if (aboveDeadzone) deadzone*1.2f
else deadzone*0.8f` + `aboveDeadzone = |yaw| >= deadzone`. Efeito com sinal constante
entre 0.8× e 1.2× da deadzone (0,04–0,06 rad/s ≈ 2,3–3,4°/s — exatamente o tremor de
mão parada): amostra n passa (abaixo→threshold 0.8×), marca above=true; amostra n+1 é
zerada (acima→threshold 1.2×), marca above=false; alterna on/off a cada amostra — o
oposto do que histerese faz. O KDoc do arquivo (`GyroProcessor.kt:16-17`) descreve o
comportamento CORRETO ("acima de deadzone*1.2 passa a valer; abaixo de deadzone*0.8
zera") — código e doc divergem. O teste `deadzone hysteresis - entry and exit thresholds
are sticky` (`GyroProcessorTest.kt:77-94`) codifica o comportamento invertido e passa
sem testar o que afirma: em 0.045 rad/s `aboveDeadzone` NUNCA fica true (0.045 < 0.05),
então o caso "sticky acima" nunca executa.

**Padrão oficial (Dolphin):** a deadzone do gyro é um LIMIAR ÚNICO em °/s
(`IMUGyroscope.cpp:47`: "Angular velocity to ignore", default 2, faixa 0–180), aplicado
à velocidade calibrada; Dolphin não usa histerese dupla — a estabilidade vem da
CALIBRAÇÃO (P2-2), não de duas bordas. (Histerese simples, quando usada em input, sempre
tem limiar de entrada MAIOR que o de saída.)

**Funcionamento esperado no GameNative:** corrigir para entrada 1.2× / saída 0.8×:
`threshold = if (aboveDeadzone) deadzone*0.8f else deadzone*1.2f`; `aboveDeadzone =
|raw| >= threshold` (o mesmo threshold aplicado — remover a referência ao deadzone cru
que hoje desincroniza o estado do limiar). Sinal constante 0.9×: permanece OFF
(abaixo da entrada 1.2×); sinal constante 1.1×: entra e PERMANECE até cair abaixo de
0.8×. Reescrever o teste: (a) 0.9× constante por N amostras → sempre zero; (b) 1.3×
constante → todas passam; (c) 1.3× → 0.9× → ainda passa (sticky); 0.7× → zera; (d)
regressão do flicker: 1.1× constante por 10 amostras → nº de amostras não-zero == 10
(hoje seria 5).

**Aceite:** teste JVM (d) verde com o código novo; KDoc e teste descrevendo o mesmo
comportamento; harness `gyro:0:0:-0.055` não produz micro-tremor de cursor.

---

# PARTE II — P2: paridade com os padrões oficiais (qualidade/percepção do usuário)

## P2-1 · dt deve vir do timestamp do evento do sensor, não do relógio de processamento

**Evidência:** `GamepadSensorSource.kt:86` usa `SystemClock.uptimeMillis()` NO MOMENTO DO
CALLBACK; o `SensorEvent.timestamp` (ns, timestamp do SENSOR) é descartado. O GyroProcessor
deriva dt entre amostras consecutivas (`coerceIn(1..100 ms)`).

**Padrão oficial:** SDL encaminha `event.timestamp` do `SensorEvent` para o core
(`SDLControllerManager.java:1064-1067` — `onNativeJoySensor(..., event.timestamp, ...)`)
e a API pública expõe dados COM timestamp (`SDL_GetSensorDataWithTimestamp`). DS4Windows
usa o timestamp do report do device em microssegundos e, em report com timestamp
DUPLICADO (dois pacotes mesmo ts), cai para o relógio do sistema
(`DS4Device.cs:1321-1326`) — dt nunca fica 0 nem furado.

**Funcionamento esperado:** `GamepadSensorSource` passa `event.timestamp` (convertido
ns→ms, com guarda de monotonicidade: se `ts <= tsAnterior`, usar `uptimeMillis` — padrão
DS4Windows para duplicados) até `hub.onSensorSample`. Benefício: o callback pode atrasar
na main thread (jogo pesado) sem inflar/deflacionar a integração — o dt reflete quando o
sensor mediu, não quando o app processou. O clamp 1–100 ms permanece como defesa.

**Aceite:** teste JVM do dt (amostras com timestamps do sensor espaçados 8 ms entregues
em rajada produzem o mesmo delta que espaçadas); log de instrumentação mostra dt estável
~20 ms com `SENSOR_DELAY_GAME`.

## P2-2 · Drift: recenter-na-ativação é o modelo mais fraco de todas as referências — adotar calibração contínua

**Evidência (atual):** o único mecanismo anti-bias é o recenter na borda de ativação
(`GyroProcessor.kt:62-66`) — o offset congelado na ativação envelhece: com `gyroMode`
sempre-ativo (sem botão), o bias é capturado UMA vez (na primeira amostra) e o drift do
sensor acumula ao longo da sessão (o usuário nota "cursor andando sozinho").

**Padrão oficial 1 — Dolphin, calibração contínua por janela estável**
(`IMUGyroscope.cpp:25-115`, algoritmo completo):
- Acumulador de média corrida (count/soma) + `calibration_period_start`.
- Config "Calibration Period" (default 3 s, 0 = desligar calibração).
- A cada amostra: se o período é 0 → bias=0 e reinicia. Se não há amostras no
  acumulador (gyro acabou de ser mapeado/calibração ligada) → aplica o estado ATUAL
  como bias imediato ("better than zeros" — o que o nosso recenter-na-borda já faz).
- Senão: calcula a frequência de amostragem do acumulador; se `freq < 25 Hz` OU qualquer
  eixo da amostra se desvia da média corrida mais que a DEADZONE → **reinicia a janela**
  (movimento invalida calibração — é isso que impede calibrar em cima de rotação).
- Empurra a amostra no acumulador; ao completar o período → `bias = média corrida`.
- `CanCalibrate()` retorna o INPUT GATE: **calibra só com o gate de input aberto**
  (em foco de jogo) — "miscalibration to zero values would occur" fora dele (no
  GameNative: só com o jogo de pé e o gyro ativo — nunca no menu/fundo).

**Padrão oficial 2 — DS4Windows/JoyShockLibrary, anel de janelas**
(`DS4Sixaxis.cs:195-201, 546-625`): 3 janelas de 5 s em anel; média PONDERADA por
duração das janelas não vazias (a janela parcial entra com peso proporcional);
`CntCalibrating` expõe ms acumulados (UI mostra progresso "calibrando…");
`ResetContinuousCalibration` reinicia tudo. Amostras são empurradas somente enquanto o
controle está em REPOUSO (detecção de stillness) — a média vive no último ~15 s de
quietude.

**Funcionamento esperado no GameNative (síntese das duas, JVM-pura):** no
`GyroProcessor`, adicionar ao lado do recenter-de-ativação (que permanece como bootstrap
inicial — é o "first sample as calibration" do Dolphin):
- Estado: `runningCount`, `runningSum{X,Y,Z}`, `windowStartMs`.
- Push de amostra **somente quando** o gyro está ativo E em stillness (|velocidade
  calibrada| < deadzone em TODOS os eixos — o critério Dolphin de desvio; a qualidade
  sobe quando o accel existir, P2-3: |accel| ≈ 1g estável).
- Amostra em movimento (desvio > deadzone) → zera o acumulador (movimento invalida).
- Ao completar o período configurável (default 3 s — Dolphin; expor `gyroCalibPeriod`
  futuro no perfil, 0 = desligado) → `offset = média` e reinicia a janela.
- O offset novo substitui o de ativação suavemente (média é por definição estável).
- Sem input gate (jogo fechado / gyro off) → nada acumula (não há amostras: fonte
  desregistrada por P1-3 — o gate sai de graça).
- UI (follow-up): badge "calibrando…" na seção Gyro quando a janela não completou
  (equivalente do `CntCalibrating`).

**Aceite:** teste JVM: (1) bias real de 0.3 rad/s + controle parado → após o período,
deltas ≈ 0 sem re-ativação; (2) girar o controle durante a janela → acumulador zera
(nunca calibra em cima de movimento); (3) período 0 → desligado (comportamento atual);
(4) on-device: gyro sempre-ativo por 10 min, cursor parado quando o controle está
imóvel na mesa (antes: deriva visível em ~1–2 min).

## P2-3 · Fonte registra só o giroscópio — SDL registra gyro+accel; stillness e futuros modos dependem do accel

**Evidência:** `GamepadSensorSource.register` (`:76`) pega só
`getDefaultSensor(TYPE_GYROSCOPE)`. O `InputEvent.SensorUpdate` já carrega
`accelX/Y/Z` (`InputEvent.kt:19-27`) — sempre zerados.

**Padrão oficial:** SDL descobre e registra AMBOS por device
(`SDLControllerManager.java:337-339`: `TYPE_ACCELEROMETER` e `TYPE_GYROSCOPE`;
`:607-610`: registra os dois junto). JoyShockLibrary usa a magnitude do accel (≈1g em
repouso) como sinal de stillness para a calibração contínua — mais confiável que gyro
sozinho (ruído do gyro pode mascarar micro-movimento lento).

**Funcionamento esperado:** mesmo listener, dois registros (`accelerometerSensor` +
`gyroscopeSensor`); `onSensorChanged` discrimina por `event.sensor.type` e o
`onSensorSample` do hub recebe ambos (mesma assinatura — os campos accel existem).
Stillness do P2-2 passa a usar |accel| desviando < ε de 1g por janela. Custo: ~0 (mesma
entrega, mesmo rate).

**Aceite:** `SensorUpdate` emitido com accel real (log); UI "Controller Readings"
(follow-up, como o `ControllerReadingsControl` do DS4Windows) consegue exibir os dois.

## P2-4 · Deadzone do gyro expressa em °/s na UI (unidade dos usuários)

**Evidência:** perfil `gyroDeadzone` em rad/s (0.05 ≈ 2.86°/s); slider da UI em rad/s.

**Padrão oficial:** Dolphin: setting "Dead Zone" em **°/s** (default 2, faixa 0–180,
`IMUGyroscope.cpp:39-47` — o texto oficial é "Angular velocity to ignore and remap").
Toda a literatura de gyro aim usa °/s (JoyShock, Steam Input).

**Funcionamento esperado:** persistência pode permanecer rad/s; a UI converte e exibe
°/s (slider 0–30°/s, default ~2.9°/s). Strings EN/pt-rBR com a unidade.

**Aceite:** slider mostra "2.9°/s" no default; valor persistido inalterado (sem
migração).

## P2-5 · Contrato de rumble: assinatura única low/high/duration/cancel (preparar a ponte do jogo)

**Evidência:** `GamepadHaptics.vibrateDevice(context, deviceId, effect)` faz one-shot de
amplitude fixa (18/12 ms). Sem `cancel()`, sem intensidade, sem o modelo de dois motores
— a futura ponte Wine/XInput (dimensionada no spec U5 §1.3) entregará low/high/duration
e não há contrato para recebê-la.

**Padrão oficial (SDL, `SDLControllerManager.java:633-690`):**
- API: `rumble(device_id, low_frequency_intensity 0–1, high_frequency_intensity 0–1,
  duration_ms)`.
- Device com **≥2 vibrators**: motor 0 = low, motor 1 = high (DualSense expõe 2).
- Device com **1 vibrator**: mix `low*0.6 + high*0.4`.
- Intensidade 0.0 ⇒ **`vibrator.cancel()`** (parar é parte do contrato — jogos mandam
  rumble contínuo com durações longas e depois cancelam).
- Amplitude: `createOneShot(length, round(intensity*255))` (clamp 1–255; <1 = cancel);
  try/catch com fallback para o one-shot de amplitude default (API <26/defensivo).

**Funcionamento esperado no GameNative:** nova função interna
`GamepadHaptics.rumbleDevice(deviceId, low, high, durationMs)` com exatamente esse
contrato (dois vibrators via `VibratorManager` no API 31+; mix 0.6/0.4 num motor;
cancel em 0; amplitude 0–255). `vibrateDevice` (efeitos de menu) passa a CHAMAR esse
contrato (ACTIVATE = low 0.4/high 0.2 × 18 ms, por exemplo) — um único ponto de
vibração. `gamepadRumbleEnabled` continua guardando tudo. A ponte Wine/XInput (spec
próprio futuro) então só traduz XInput low/high/duration → este contrato — zero retrabalho.

**Aceite:** teste de unidade do mix (0.6/0.4) e do cancel; DualSense (2 motores) vibra
motor distinto por low/high on-device (§[H]).

## P2-6 · Touchpad: thresholds calibrados pela prática + modo arrasto + duplo-toque

**Evidência (atual):** tap = janela 250 ms ✓ (igual ao padrão), movimento máximo 0.08
normalizado (~80 px de percurso do DS4 — folgado), sem modo arrasto, duplo-toque = 2
cliques (decisão registrada no impl doc §2.2).

**Padrão oficial (moonlight-android, `RelativeTouchContext.java:87-90`,
`AbsoluteTouchContext.java:60-67`)** — thresholds de anos de uso real em streaming:
- Tap: **tempo ≤ 250 ms** E **movimento ≤ 20–25 px** (não normalizado — px de tela).
- Duplo-toque: 250 ms / 60 px entre os toques (é o gesto "botão direito" no touch).
- **Arrasto (drag):** toque segurado **≥ 650 ms** sem soltar vira clique+arrasto
  contínuo (`LONG_PRESS_TIME_THRESHOLD = 650`, distância 30 px) — é o gesto que falta e
  o que mais dói em jogos (arrastar itens/objetos).
- DEAD ZONE de pós-toque: 100 ms/20 px antes de considerar o toque "real"
  (`TOUCH_DOWN_DEAD_ZONE_*`) — filtra micro-toques fantasma.

**Funcionamento esperado no GameNative (follow-up do U2, spec próprio):**
1. Fechar a calibração do tap: `tapMoveDeadzone` de 0.08 → ~0.02–0.03 normalizado
   (equivalente aos 20–25 px do moonlight no touchpad do DS4 ~1000 px de percurso),
   ou expressar em px convertidos no adapter. Ajuste fino + teste atualizado.
2. **Modo arrasto:** finger-down ≥ 650 ms (sem soltar, movimentação qualquer) → press
   BUTTON_LEFT contínuo + deltas; finger-up → release. Máquina de estados no
   TouchpadProcessor (puro) com os três estados (idle/tapCandidate/dragging).
3. **Duplo-toque = clique direito (opt-in):** 2 taps dentro de 250 ms/60 px-equivalente
   → BUTTON_RIGHT. Default off (comportamento atual: 2 cliques) — pref por perfil.
4. Dead zone de pós-toque (100 ms) para rejeitar bounce do touchpad gasto — o mesmo
   público do gate de ghost input (DS4 usado, spec 2026-08-13).

**Aceite:** testes JVM da máquina de estados (tap/arrasto/duplo/direito); on-device:
arrastar item no Silksong com o touchpad; duplo-toque abre menu de contexto.

## P2-7 · Premissa de threading documentada (ou corrigida)

**Evidência:** `GamepadSensorSource` KDoc afirma "Eventos chegam por callback em THREAD
PRÓPRIA" (`:15`) e `applyCameraGyro` "Chamado pela thread do sensor" — mas
`registerListener(listener, sensor, rate)` sem Handler associa o listener ao **Looper da
thread que chamou** (main) → entrega na main thread. O harness também chama
`onSensorSample` da main (`LaunchedEffect`). Na prática NÃO há concorrência hoje — mas a
arquitetura documentada está errada, e quem seguir os comentários (ex.: registrar numa
thread própria "para sair do hot path") encontrará `gyroStates`/`profileCache`/
`layerStates` (HashMap) e o `EventDispatcher.listeners` (mutableMap + removeIf) sem
nenhuma sincronização → CME/corrupção.

**Padrão oficial:** SDL NDK: thread DEDICADA de sensores (`SDL_androidsensor.c:58-92`)
com lock global do registro (`SDL_LockSensors`) — a concorrência é tratada explicitamente
por quem a introduz. Dolphin: todo o pipeline IMU roda na emulation thread com gates.

**Funcionamento esperado:** decisão A (recomendada, zero código): fixar a premissa
"entrega na main thread" nos KDocs (source + applyCameraGyro + hub V3) e registrar a
regra "registrar listeners de sensor SOMENTE da main thread" (senão: decisão B — migrar
para Handler/Looper explícito + coleções concorrentes no hub). Ambas válidas; o que não
pode é a doc dizer uma coisa e o runtime fazer outra.

**Aceite:** KDocs coerentes com o runtime; verificação on-device com `dumpsys` confirma
entrega na main (sem crash em 30 min de jogo com gyro ativo).

---

# PARTE III — P3: consistência, docs e ferramentas

| # | Pendência | Evidência | Correção esperada |
|---|---|---|---|
| P3-1 | `tools/quickmenu-verify.sh` com ERRO DE SINTAXE — aspas não fechadas nas linhas ~113–116 (`echo "--- U6 ... ON in` sem fechar) — `bash -n` falha na linha 116; **o script da bateria on-device não roda** | `bash -n tools/quickmenu-verify.sh` → `syntax error near unexpected token '('` | Fechar as aspas das 3 linhas de echo quebradas; rodar `bash -n` no CI mental de todo PR que tocar o script (regra nova) |
| P3-2 | KDoc do `LayerTriggerSpec` contradiz o hub: "(pós-remap de camada)" (`LayerTriggerSpec.kt:9`) vs hub "triggers resolvem no botão FÍSICO antes do remap" (`GamepadHub.kt:235`) | — | Alinhar: o comportamento é PRÉ-remap (decisão U3 §1.3 já registrada); corrigir o KDoc |
| P3-3 | Dois `layerTriggers` com o mesmo botão: `firstOrNull` decide (`GamepadHub.kt:246-248`); comportamento indefinido para o usuário | — | Padrão key-mapper (melhor UX de remap das referências): **detecção de conflito na UI** — impedir salvar dois triggers no mesmo botão (erro inline no GamepadRemapDialog); registrar a decisão |
| P3-4 | Bateria (U7) coletada só no `addDevice` — nível fica stale durante a sessão (uma partida longa descarrega sem a UI notar) | `GamepadHub.addDevice:485-499` | Refresh PULL ao ABRIR a seção Gamepad dos settings (fora do hot path — igual ao padrão "coleta no hotplug"); sem polling |
| P3-5 | `LibraryAppScreen` (tela de detalhe do jogo) ainda usa raw keycodes | registrado como desvio 1 do impl doc | Spec futuro U6b reaproveitando `LibraryGamepadKeys` (mesmo padrão) |
| P3-6 | `remapEvent` re-resolve `profileFor` por evento em vez de usar o `profile` já em mão (`GamepadHub.kt:292` vs `:262-270`) | — | Passar o profile como parâmetro (cache hit barato, mas é chamada por botão×evento) — micro |
| P3-7 | `MILESTONES.md` e impl doc afirmam CAMERA "implementado" | entrada `milestone-2026-08-14-gamepad-intuito-upgrades` | Após P1-1/P1-2, retificar a entrada (nota de correção referenciando este doc) — os docs não devem descrever como feito o que está quebrado |
| P3-8 | Teste da histerese atual passa sem testar o comportamento (P1-4) | `GyroProcessorTest.kt:77-94` | Reescrever conforme P1-4 (casos a–d) — regra: todo teste de comportamento limítrofe precisa de sequência de estados, não pares isolados |

---

# PARTE IV — Ordem de execução e matriz de rastreio

**Ordem sugerida (menor risco primeiro, tudo antes da bateria on-device):**

1. **P1-4** (histerese) + **P3-8** (teste) — puro, isolado, 30 min.
2. **P1-3** (lifecycle) — wiring pequeno no XServerScreen; junto **P2-7** (fixar
   premissa de thread nos KDocs).
3. **P1-1 + P1-2** (CAMERA: sink + modelo velocidade→deflexão) — dependem do contrato
   novo do sink; incluir **P2-1** (timestamp do evento) na mesma passada do source.
4. **P2-5** (contrato de rumble) — independente; destrava a ponte Wine/XInput futura.
5. **P2-2 + P2-3** (calibração contínua + accel) — o maior bloco; puro, testável;
   endereça o drift que a bateria on-device vai expor de qualquer forma.
6. **P2-4, P2-6, P3-*** — UI/unidades/touchpad-follow-up/docs/verify script.
7. **Bateria on-device §[H]** (após P1-* e P3-1) → flip do gate
   `gamepadUniversalEnabled` default ON + tag do MILESTONES (pendência registrada na
   Onda 2 e no impl doc).

**Matriz pendência × critério do intuito (V1–V12):**

| Pendência | V-critérios acionados |
|---|---|
| P1-1/P1-2 | V4 (contrato do sink), V8 (não tocar roteamento), V6 |
| P1-3 | V3 (o próprio), V11 |
| P1-4/P3-8 | V5 (puro + teste que falha hoje) |
| P2-1 | V4, V5 |
| P2-2 | V5, V6 (estado por device), V3 (gate de calibração) |
| P2-3 | V11, V4 |
| P2-5 | V11, V9 (strings) |
| P2-6 | V5, V7 (tudo pelo ponto do gate), V12 (harness `touch*` já cobre) |
| P2-7 | V3 (doc = runtime) |
| P3-1 | — (ferramenta) |
| P3-2/P3-6/P3-7 | docs |
| P3-3 | V5/UI |
| P3-4 | V11 |

**Cada correção**: commit `fix(gamepad): ...` ou `feat(gamepad): ...` referenciando este
documento + spec do upgrade correspondente; testes JVM no mesmo commit; §[H] do
`tools/quickmenu-verify.sh` atualizado quando o item for verificável on-device.
