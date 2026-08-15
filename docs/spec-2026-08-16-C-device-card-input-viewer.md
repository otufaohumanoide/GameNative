# Spec 2026-08-16 C — Cartão de device com input viewer ao vivo + testes de hardware

**Data:** 2026-08-16
**Origem:** a jornada do usuário com o rumble provou a lacuna: não há como VER o
que o controle envia nem testar hardware sem jogo rodando. Referências: PPSSPP
(flash de botões), DS4Windows (readouts ao vivo).
**Executor:** sub-agente autônomo. Leia `AGENTS.md` + master roadmap §2.
**DEPENDÊNCIAS: executa DEPOIS de A** (botão testar vibração) **e de B**
(`ControllerVisualView`). Spec autocontido.

## 0. Estado atual

- `SettingsGroupGamepad.kt` → `ConnectedDeviceRow`: nome, bateria
  (`refreshBattery` PULL ao abrir, `GamepadHub.kt:509`), badges GYRO/TOUCHPAD
  (`CapabilityBadge`).
- A (fase anterior) entrega: botão "Testar vibração" + `rumbleTargetFor`.
- B entrega: `ControllerVisualView` (Canvas com flash por evento bus).
- `GamepadSensorSource` entrega amostras de gyro/accel ao hub
  (`onSensorSample`); `TouchpadProcessor` mantém `lastX/lastY` normalizados
  (0..1) por device no forwarder.

## 1. Design

### 1.1 `DeviceDiagnosticsCard` (arquivo próprio)

Novo `ui/screen/settings/DeviceDiagnosticsCard.kt` substitui o
`ConnectedDeviceRow` na composição (header idêntico: nome/bateria/badges —
comportamento byte-identical quando recolhido). Expande em:
- **Input viewer**: `ControllerVisualView(faceStyle do device)` com flash por
  `GamepadInputEvent` do deviceId (reuso integral do componente de B).
- **Readouts ao vivo** (linha de texto mono, atualização ~10 Hz):
  - gyro: yaw/pitch rad/s (`GamepadHub.gyroPreview`, ver 1.2) — só se
    `device.hasGyro`;
  - touchpad: x/y normalizados (1.3) — só se `device.hasTouchpad`.
- **Botões de teste**: "Testar vibração" (de A); "Recentrar gyro" (zera offsets
  do GyroProcessor do device — chama hook novo `GamepadHub.recenterGyro(deviceId)`);
  "Todos os botões" (só instruções + o viewer acescendo — sem lógica extra).
- Card expandido SÓ com device ativo; colapsado = estado atual.

### 1.2 `GamepadHub.gyroPreview` (hook de observação, main thread)

- `@Volatile var gyroPreviewEnabled: Boolean = false` (setado pelo card no
  DisposableEffect — ON quando visível).
- No fim de `onSensorSample` (após o processamento existente, SEM alterá-lo):
  se `gyroPreviewEnabled` → `_gyroPreview.value = GyroPreview(deviceId, yawRadS,
  pitchRadS, timestamp)` (`MutableStateFlow<GyroPreview?>`; última amostra vence).
- Custo: 1 write por amostra quando ON; ZERO quando OFF (byte-identical).
- `recenterGyro(deviceId)`: reaplica a âncora de offset do `GyroState` do device
  (mesma operação da borda de ativação — extrair para função e chamar nos dois
  lugares; sem mudança de comportamento do pipeline).

### 1.3 Touchpad readout

`GamepadTouchpadForwarder` ganha `@Volatile var previewEnabled: Boolean = false`
+ `touchpadPreview: StateFlow<TouchpadPreview?>` (x/y/ down, escrita no
processamento de amostra quando ON; mesmo padrão 1.2). O card coleta.

### 1.4 Strings

PT-BR/EN para: readouts, botões de teste, dica "todos os botões".

## 2. Arquivos afetados

| Arquivo | Mudança |
|---|---|
| `ui/screen/settings/DeviceDiagnosticsCard.kt` | NOVO (1.1) |
| `ui/screen/settings/SettingsGroupGamepad.kt` | troca o row pelo card (comportamento colapsado idêntico) |
| `gamepad/GamepadHub.kt` | `gyroPreview` + `recenterGyro` (1.2) — SEM tocar no pipeline |
| `gamepad/GamepadTouchpadForwarder.kt` | `touchpadPreview` (1.3) |
| `gamepad/processing/GyroProcessor.kt` | extrair recenter reutilizável (1.2, refator pura) |
| `res/values*/strings.xml` | chaves |

## 3. Verificação (gate)

```
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:testModernDebugUnitTest --tests "*Gamepad*"
JAVA_HOME=/home/annapaula/android-studio/jbr ./gradlew :app:assembleModernDebug
```
On-device (humano, "on-device pendente"): DS4 conectado → expandir card →
apertar botões acende o desenho; gyro readout varia girando o controle;
touchpad readout segue o dedo; testar vibração reporta controle/telefone/nada;
recenterar gyro zera o readout.

## 4. Fora de escopo

Gráficos de latência no card (HUD F0 já existe), log de eventos em arquivo,
histórico/drift analytics, tester de sticks (curva morta visual).
