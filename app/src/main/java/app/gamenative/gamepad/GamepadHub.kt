package app.gamenative.gamepad

import android.content.Context
import android.hardware.BatteryState
import android.hardware.Sensor
import android.hardware.input.InputManager
import android.os.Build
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.events.GamepadDeviceAddedEvent
import app.gamenative.events.GamepadDeviceRemovedEvent
import app.gamenative.events.GamepadInputEvent
import app.gamenative.gamepad.layers.LayerResolver
import app.gamenative.gamepad.layers.LayerState
import app.gamenative.gamepad.mapping.EventTranslator
import app.gamenative.gamepad.mapping.GamepadMapping
import app.gamenative.gamepad.mapping.MappingDatabase
import app.gamenative.gamepad.mapping.RawAxisInput
import app.gamenative.gamepad.mapping.RawBinding
import app.gamenative.gamepad.mapping.RawKeyInput
import app.gamenative.gamepad.remap.GamepadBindingCodec
import app.gamenative.gamepad.processing.DeadzoneConfig
import app.gamenative.gamepad.processing.GyroConfig
import app.gamenative.gamepad.processing.GyroState
import app.gamenative.gamepad.processing.GyroProcessor
import app.gamenative.gamepad.processing.GyroSample
import app.gamenative.gamepad.profiles.GamepadProfile
import app.gamenative.gamepad.profiles.GamepadProfileStore
import app.gamenative.gamepad.profiles.ProfileResolver
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * O ÚNICO dono da descoberta de devices (spec 2026-08-13, Parte I §8 — "por que o hub
 * NÃO existir é o defeito-mãe"): consolida os dois InputDeviceListener duplicados
 * (MainActivity.kt:144 e XServerScreen.kt:1464 — removidos na Onda 2).
 *
 * Regras:
 * - Hot path síncrono: [onKey]/[onAxis] rodam na thread de dispatch do Android, sem
 *   coroutine e sem alocação em rajada (overhead alvo < 1 ms).
 * - UI reativa fora do hot path: [connectedDevices]/[activeDevice] (StateFlow) — a UI
 *   observa conexão/perfil SEM interceptar eventos.
 * - Gate: [PrefManager.gamepadUniversalEnabled] (default false) — eventos lógicos só
 *   são emitidos quando a Onda 2 ligar o consumo. Até lá a tradução roda em paralelo
 *   ao fluxo cru sem mudar NENHUM comportamento do caminho do jogo.
 * - Estado entre amostras (transições de hat/meia-eixo) vive AQUI — o tradutor é puro.
 */
class GamepadHub(context: Context) {

    companion object {
        /** Deadzone angular default do gyro (rad/s — ~3°/s), U1. */
        const val DEFAULT_GYRO_DEADZONE = 0.05f

        /** MOUSE: rad de rotação → pixels de cursor (U1 — 300 px/rad ≈ 5.2 px/grau). */
        const val GYRO_PIXELS_PER_RADIAN = 300f
    }

    private val appContext = context.applicationContext
    private val inputManager = appContext.getSystemService(Context.INPUT_SERVICE) as InputManager

    private val _connectedDevices = MutableStateFlow<Map<Int, GamepadDevice>>(emptyMap())
    val connectedDevices: StateFlow<Map<Int, GamepadDevice>> = _connectedDevices.asStateFlow()

    private val _activeDevice = MutableStateFlow<GamepadDevice?>(null)
    val activeDevice: StateFlow<GamepadDevice?> = _activeDevice.asStateFlow()

    private val deviceStore =
        GamepadProfileStore(File(File(appContext.filesDir, "gamepad"), "device_profiles.json"))
    private val gameStore =
        GamepadProfileStore(File(File(appContext.filesDir, "gamepad"), "game_profiles.json"))

    /**
     * Cache do perfil EFETIVO por (deviceId, appId) (spec 2026-08-14-onda2-pos-implementacao,
     * M1 — L1): o hot path (~120 Hz por stick + hats) não paga disco + JSON por evento.
     * Acesso só na main thread (dispatch + hotplug + save — nenhuma coroutine), então
     * mapa plano sem sincronização. Inválido por [invalidateProfiles] em hotplug
     * (add/remove/refreshDevice) e após save de perfil — entradas são estáveis dentro
     * da sessão de um container.
     */
    private val profileCache = mutableMapOf<String, GamepadProfile>()

    /** DeadzoneConfig default (triggers) cacheado — não alocar por MotionEvent (L6). */
    private val defaultDeadzones = DeadzoneConfig()

    private var started = false

    /**
     * appId do container em execução (holder vivo — lição C1 do hardening, spec
     * 2026-08-13-onda2 §1.4). O XServerScreen ESCREVE na composição
     * (`hub.activeAppId = container.id`) e os handlers LEEM no momento do evento.
     * Hot path síncrono: @Volatile, sem StateFlow (a UI não observa isso).
     */
    @Volatile
    var activeAppId: String? = null

    /** Botões descritos por amostra (hat/meia-eixo) → transições (padrão SDL Android). */
    private val buttonStates = mutableMapOf<Int, MutableSet<GamepadButton>>()

    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = addDevice(deviceId)
        override fun onInputDeviceRemoved(deviceId: Int) = removeDevice(deviceId)
        override fun onInputDeviceChanged(deviceId: Int) = refreshDevice(deviceId)
    }

    /** Registra o ÚNICO InputDeviceListener + scan inicial. */
    fun start() {
        if (started) return
        started = true
        inputManager.registerInputDeviceListener(deviceListener, null)
        for (deviceId in InputDevice.getDeviceIds()) {
            addDevice(deviceId)
        }
        Timber.d("GamepadHub: started devices=%d", _connectedDevices.value.size)
    }

    /** Unregister; emite DeviceRemoved dos vivos. */
    fun stop() {
        if (!started) return
        started = false
        inputManager.unregisterInputDeviceListener(deviceListener)
        sensorSource?.stop()
        val alive = _connectedDevices.value.keys.toList()
        _connectedDevices.value = emptyMap()
        _activeDevice.value = null
        buttonStates.clear()
        for (deviceId in alive) {
            PluviaApp.gamepadTouchpad.onDeviceRemoved(deviceId)
            PluviaApp.events.emit(GamepadDeviceRemovedEvent(deviceId))
            Timber.d("GamepadHub: removed %d (stop)", deviceId)
        }
    }

    /** Getter síncrono (hot path). */
    fun deviceFor(deviceId: Int): GamepadDevice? = _connectedDevices.value[deviceId]

    /**
     * Keycode de CONFIRMAÇÃO do device (spec 2026-08-13-onda2 §1.6): FaceStyle do
     * mapping + swap do perfil/global. null = sem binding de tecla.
     */
    fun confirmKeyCodeFor(deviceId: Int): Int? {
        val device = deviceFor(deviceId) ?: return null
        val profile = profileFor(deviceId, activeAppId)
        val swap = profile.swapOkCancel ?: PrefManager.gamepadSwapOkCancel
        return mappingFor(device).confirmKeyCode(swap)
    }

    /**
     * Deadzone do MENU por device (spec 2026-08-13-onda2 §1.5): profile override OU a
     * global `gamepadMenuStickDeadzone` (0.45 — o valor que o menu sempre usou). O
     * threshold do menu é aplicado sobre o valor CRU (o AxisMotion lógico já vem
     * rescalonado com a deadzone do JOGO, 0.15).
     */
    fun menuDeadzoneFor(deviceId: Int): Float {
        val device = deviceFor(deviceId) ?: return PrefManager.gamepadMenuStickDeadzone
        val profile = profileFor(deviceId, activeAppId)
        return profile.leftStickDeadzone ?: PrefManager.gamepadMenuStickDeadzone
    }

    /**
     * Conjunto de keycodes LÓGICOS da LibraryScreen (spec 2026-08-14, U6): confirm/
     * cancel por FaceStyle + swap, atalhos traduzidos pelo mapping do device. null =
     * device desconhecido (a superfície cai no fallback raw — byte-identical).
     */
    fun libraryKeySetFor(deviceId: Int): LibraryKeySet? {
        val device = deviceFor(deviceId) ?: return null
        return LibraryGamepadKeys.resolve(mappingFor(device), swapFor(deviceId))
    }

    /**
     * Botão SEMÂNTICO de confirmação do device (U6 — ActionBar): FaceStyle + swap.
     * null = device desconhecido (UI usa o default A).
     */
    fun confirmButtonFor(deviceId: Int): GamepadButton? {
        val device = deviceFor(deviceId) ?: return null
        return mappingFor(device).confirmButton(swapFor(deviceId))
    }

    /** Swap OK/Cancel efetivo do device: perfil ?: global. */
    fun swapFor(deviceId: Int): Boolean {
        val profile = profileFor(deviceId, activeAppId)
        return profile.swapOkCancel ?: PrefManager.gamepadSwapOkCancel
    }

    /**
     * Perfil efetivo (device + jogo + globais) resolvido no momento do evento, servido
     * pelo cache (M1). Entrada estável dentro da sessão de um container; inválida em
     * hotplug (deviceId efêmero reusado) e em save de perfil.
     */
    fun profileFor(deviceId: Int, appId: String?): GamepadProfile {
        val device = deviceFor(deviceId) ?: return GamepadProfile()
        return profileCache.getOrPut("$deviceId:$appId") {
            ProfileResolver.resolve(device, appId, deviceStore, gameStore)
        }
    }

    /** Invalida o cache de perfis (hotplug e pós-save). Barata — chamada fora do hot path. */
    fun invalidateProfiles() {
        profileCache.clear()
    }

    /**
     * Persiste o perfil do device (chave = mappingKey) e invalida o cache (M3 — remap).
     * Um perfil default REMOVE a entrada (padrão do store). Sem device = no-op.
     */
    fun saveDeviceProfile(deviceId: Int, profile: GamepadProfile) {
        val device = deviceFor(deviceId) ?: return
        deviceStore.save(device.mappingKey, profile)
        invalidateProfiles()
    }

    // U3: estado de camadas por device (V6 — morto no removeDevice).
    private val layerStates = mutableMapOf<Int, LayerState>()

    /** Camada ativa do device (U3/U4); null = DEFAULT. */
    fun activeLayerFor(deviceId: Int): String? = layerStates[deviceId]?.activeLayer

    /**
     * U4 (spec 2026-08-14-gamepad-u3-u4-layers-remap-jogo, §2.1): binding de camada
     * EFETIVO (DEFAULT + ativa) para um botão lógico — o PhysicalControllerHandler
     * consulta ANTES de injetar o ExternalControllerBinding (precedência: binding
     * explícito universal vence; sem binding → caminho byte-identical). Cache M1.
     */
    fun layerBindingFor(deviceId: Int, button: GamepadButton): String? {
        val device = deviceFor(deviceId) ?: return null
        val profile = profileFor(deviceId, activeAppId)
        return LayerResolver.effectiveBindings(profile.layers, layerStates[deviceId]?.activeLayer)[button.name]
    }

    /** U3 §1.3 (1): triggers resolvem no botão FÍSICO (antes do remap). */
    private fun resolveLayerTriggers(
        layerState: LayerState,
        profile: GamepadProfile,
        event: InputEvent,
    ) {
        val button = when (event) {
            is InputEvent.ButtonDown -> event.button
            is InputEvent.ButtonUp -> event.button
            else -> return
        }
        val trigger = profile.layerTriggers.entries.firstOrNull { (_, spec) ->
            spec.button == button.name
        } ?: return
        val now = android.os.SystemClock.uptimeMillis()
        when (event) {
            is InputEvent.ButtonDown -> LayerResolver.onButtonDown(layerState, trigger.key, trigger.value, now)
            is InputEvent.ButtonUp -> LayerResolver.onButtonUp(layerState, trigger.key, trigger.value, now)
            else -> {}
        }
    }

    /**
     * U3 §1.3 (2): emite o evento lógico com o remap da camada ativa aplicado
     * (DEFAULT + camada ativa); sem binding na camada → evento original. O gyro
     * activate (U1) rastreia o botão PÓS-remap (consistência com o remap físico).
     */
    private fun emitLogical(
        device: GamepadDevice,
        mapping: GamepadMapping,
        profile: GamepadProfile,
        layerState: LayerState,
        event: InputEvent,
        deviceId: Int,
    ): Boolean {
        val emitted = remapEvent(device, mapping, event, layerState)
        val activateButton = profile.gyroActivateButton
        for (e in emitted) {
            if (activateButton != null && e is InputEvent.ButtonDown && e.button.name == activateButton) {
                gyroActivateHeld[deviceId] = activateButton
            } else if (activateButton != null && e is InputEvent.ButtonUp && e.button.name == activateButton) {
                gyroActivateHeld.remove(deviceId)
            }
            logLogical(device, e)
            PluviaApp.events.emit(GamepadInputEvent(e))
        }
        return emitted.isNotEmpty()
    }

    /** Aplica o remap da camada ativa a um evento de botão (U3). */
    private fun remapEvent(
        device: GamepadDevice,
        mapping: GamepadMapping,
        event: InputEvent,
        layerState: LayerState,
    ): List<InputEvent> {
        val bindings = LayerResolver.effectiveBindings(
            profileFor(device.deviceId, activeAppId).layers,
            layerState.activeLayer,
        )
        if (bindings.isEmpty()) return listOf(event)
        val button = when (event) {
            is InputEvent.ButtonDown -> event.button
            is InputEvent.ButtonUp -> event.button
            else -> return listOf(event)
        }
        val token = bindings[button.name] ?: return listOf(event)
        val binding = GamepadBindingCodec.decode(token) ?: return listOf(event)
        val down = event is InputEvent.ButtonDown
        return when (binding) {
            is RawBinding.Key -> {
                val target = mapping.buttons.entries
                    .firstOrNull { (_, b) -> b is RawBinding.Key && b.keyCode == binding.keyCode }
                    ?.key
                if (target == null) emptyList() else {
                    listOf(
                        if (down) InputEvent.ButtonDown(device.deviceId, target)
                        else InputEvent.ButtonUp(device.deviceId, target),
                    )
                }
            }
            is RawBinding.Axis -> {
                val target = mapping.axes.entries
                    .firstOrNull { (_, b) -> b is RawBinding.Axis && b.axis == binding.axis }
                    ?.key
                if (target == null) emptyList() else {
                    val value = if (down) binding.direction.toFloat() else 0f
                    listOf(InputEvent.AxisMotion(device.deviceId, target, value))
                }
            }
            // Hat não é alvo de remap de botão no caminho lógico (decisão registrada —
            // o remap de dpad no jogo passa pelo canal de tecla).
            is RawBinding.Hat -> emptyList()
        }
    }

    /**
     * U1/V12 (spec 2026-08-14-gamepad-u1-gyro): amostra de sensor (giroscópio) de um
     * device — chamada pelo GamepadSensorSource (entrega na MAIN thread — P2-7, o
     * registerListener usa o Looper de quem registrou) e pelo harness (`gyro:x:y:z`).
     * Gate-aware como onKey/onAxis.
     *
     * Pipeline: perfil (cache M1) → GyroProcessor (puro, estado por device V6) →
     * evento lógico emitido (vocabulário V4) → injeção:
     * - MOUSE → sink de mouse compartilhado (XServer injectPointerMoveDelta);
     * - CAMERA → `gyroCameraSink` (setado pelo XServerScreen: acumula no right stick
     *   do virtual gamepad via PhysicalControllerHandler).
     */
    fun onSensorSample(deviceId: Int, gyroX: Float, gyroY: Float, gyroZ: Float, nowMs: Long) {
        if (!PrefManager.gamepadUniversalEnabled) return
        val device = deviceFor(deviceId) ?: return
        if (device.deviceClass != DeviceClass.CONTROLLER && device.deviceClass != DeviceClass.TOUCHPAD) return
        if (!device.hasGyro) return
        val profile = profileFor(deviceId, activeAppId)
        val gyroState = gyroStates.getOrPut(deviceId) { GyroState() }
        val output = GyroProcessor.process(
            sample = GyroSample(gyroX, gyroY, gyroZ, nowMs),
            state = gyroState,
            config = GyroConfig(deadzone = profile.gyroDeadzone ?: DEFAULT_GYRO_DEADZONE),
            activate = gyroActivateHeld(deviceId, profile),
        )
        // Evento lógico sempre emitido para device com gyro (consumidores futuros).
        val event = InputEvent.SensorUpdate(
            deviceId = deviceId,
            gyroX = gyroX,
            gyroY = gyroY,
            gyroZ = gyroZ,
            accelX = 0f,
            accelY = 0f,
            accelZ = 0f,
        )
        logLogical(device, event)
        PluviaApp.events.emit(GamepadInputEvent(event))
        if (!output.active) return

        val sensitivity = profile.gyroSensitivity ?: 1f
        when (profile.gyroMode ?: GyroMode.OFF) {
            GyroMode.MOUSE -> {
                val dx = (output.deltaXRad * GYRO_PIXELS_PER_RADIAN * sensitivity).toInt()
                val dy = (output.deltaYRad * GYRO_PIXELS_PER_RADIAN * sensitivity).toInt()
                if (dx != 0 || dy != 0) {
                    PluviaApp.xServerMouseSink.move(dx, dy)
                }
            }
            GyroMode.CAMERA -> {
                gyroCameraSink?.invoke(output.deltaXRad, output.deltaYRad, sensitivity)
            }
            GyroMode.OFF -> {}
        }
    }

    /** Sink do CAMERA mode — setado pelo XServerScreen quando o container roda (limpo no exit). */
    @Volatile
    var gyroCameraSink: ((deltaXRad: Float, deltaYRad: Float, sensitivity: Float) -> Unit)? = null

    /** Fonte de sensores (U1) — injetada pelo PluviaApp; hotplug avisa o source. */
    var sensorSource: GamepadSensorSource? = null

    private val gyroStates = mutableMapOf<Int, GyroState>()
    private val gyroActivateHeld = mutableMapOf<Int, String>()

    private fun gyroActivateHeld(deviceId: Int, profile: GamepadProfile): Boolean {
        val buttonName = profile.gyroActivateButton ?: return true // sempre ativo
        return gyroActivateHeld[deviceId] == buttonName
    }

    /** Traduz um KeyEvent cru e emite GamepadInputEvent no bus (gate-aware). */
    fun onKey(raw: RawKeyInput): Boolean {
        if (!PrefManager.gamepadUniversalEnabled) return false
        val device = deviceFor(raw.deviceId) ?: return false
        // Só CONTROLLER emite lógico: TOUCHPAD continua sendo gate do MainActivity
        // (spec 2026-08-13-onda2 §1.2 — correção 3 da validação).
        if (device.deviceClass != DeviceClass.CONTROLLER) return false
        val mapping = mappingFor(device)
        val events = EventTranslator.translateKey(raw, mapping)
        if (events.isEmpty()) return false
        val profile = profileFor(raw.deviceId, activeAppId)
        val activateButton = profile.gyroActivateButton
        val layerState = layerStates.getOrPut(raw.deviceId) { LayerState() }
        for (event in events) {
            resolveLayerTriggers(layerState, profile, event)
            emitLogical(device, mapping, profile, layerState, event, raw.deviceId)
        }
        return true
    }

    /** Traduz um MotionEvent cru e emite GamepadInputEvent no bus (gate-aware). */
    fun onAxis(raw: RawAxisInput): Boolean {
        if (!PrefManager.gamepadUniversalEnabled) return false
        val device = deviceFor(raw.deviceId) ?: return false
        if (device.deviceClass != DeviceClass.CONTROLLER) return false
        val mapping = mappingFor(device)
        val profile = profileFor(raw.deviceId, activeAppId)
        // Trigger não tem key global no PrefManager (spec Passo 1: só stick) — usa o
        // default do DeadzoneConfig cacheado no hub quando o perfil não override (L6:
        // sem alocação do default por MotionEvent).
        val deadzones = DeadzoneConfig(
            leftStick = profile.leftStickDeadzone ?: PrefManager.gamepadStickDeadzone,
            rightStick = profile.rightStickDeadzone ?: PrefManager.gamepadStickDeadzone,
            leftTrigger = profile.leftTriggerDeadzone ?: defaultDeadzones.leftTrigger,
            rightTrigger = profile.rightTriggerDeadzone ?: defaultDeadzones.rightTrigger,
        )
        val events = EventTranslator.translateAxis(raw, mapping, deadzones)
        if (events.isEmpty()) return false

        // O tradutor descreve o ESTADO da amostra (hat/meia-eixo); o hub vira transição.
        val state = buttonStates.getOrPut(raw.deviceId) { mutableSetOf() }
        val layerState = layerStates.getOrPut(raw.deviceId) { LayerState() }
        var emitted = false
        for (event in events) {
            val forward = when (event) {
                is InputEvent.ButtonDown -> state.add(event.button)
                is InputEvent.ButtonUp -> state.remove(event.button)
                is InputEvent.AxisMotion -> true
                else -> false
            }
            if (forward) {
                // U3: triggers de camada (botão FÍSICO) + remap pela camada ativa.
                resolveLayerTriggers(layerState, profile, event)
                emitted = emitLogical(device, mapping, profile, layerState, event, raw.deviceId) || emitted
            }
        }
        return emitted
    }

    /** Instrumentação Onda 2 (spec §1.9): par do GamepadTrace cru. */
    private fun logLogical(device: GamepadDevice, event: InputEvent) {
        Timber.d(
            "GamepadLogical: dev=%s (%04x%04x) %s",
            device.name, device.vendorId, device.productId, event,
        )
    }

    private fun mappingFor(device: GamepadDevice) =
        MappingDatabase.mappingFor(device.vendorId, device.productId)
            ?: MappingDatabase.defaultAndroidMapping(device.faceStyle)

    private fun addDevice(deviceId: Int) {
        val inputDevice = InputDevice.getDevice(deviceId) ?: return
        val deviceClass = DeviceClassifier.classify(deviceFeatures(inputDevice))
        if (deviceClass == DeviceClass.UNKNOWN || deviceClass == DeviceClass.SENSOR) return
        val faceStyle = MappingDatabase.mappingFor(inputDevice.vendorId, inputDevice.productId)
            ?.faceStyle ?: FaceStyle.GENERIC
        // U1/U7 (V11): capacidades coletadas no hotplug (fora do hot path — pull).
        // Gyro: API 31+ via getSensorManager do device; API < 31 → false (degradação
        // silenciosa — a UI esconde, nunca mostra erro). Touchpad: CLASS_POINTER.
        val hasGyro = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            runCatching {
                inputDevice.getSensorManager()?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
            }.getOrDefault(false)
        val hasTouchpad = (inputDevice.sources and InputDevice.SOURCE_CLASS_POINTER) != 0
        val batteryPercent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                val state = inputDevice.batteryState
                if (state.isPresent && state.capacity >= 0f) {
                    when (state.status) {
                        BatteryState.STATUS_CHARGING, BatteryState.STATUS_FULL -> 100
                        else -> (state.capacity * 100f).toInt()
                    }
                } else {
                    null
                }
            }.getOrNull()
        } else {
            null
        }
        val device = GamepadDevice(
            deviceId = deviceId,
            descriptor = inputDevice.descriptor,
            vendorId = inputDevice.vendorId,
            productId = inputDevice.productId,
            name = inputDevice.name,
            deviceClass = deviceClass,
            faceStyle = faceStyle,
            hasGyro = hasGyro,
            hasTouchpad = hasTouchpad,
            batteryPercent = batteryPercent,
        )
        _connectedDevices.value = _connectedDevices.value + (deviceId to device)
        refreshActive()
        invalidateProfiles()
        sensorSource?.onDeviceAdded(deviceId)
        PluviaApp.events.emit(GamepadDeviceAddedEvent(device))
        Timber.d(
            "GamepadHub: added id=%d name=%s vendor=%04x product=%04x class=%s",
            deviceId, device.name, device.vendorId, device.productId, deviceClass,
        )
    }

    private fun removeDevice(deviceId: Int) {
        buttonStates.remove(deviceId)
        // U2 (V6): estado do touchpad→mouse morre junto com o device (mesmo padrão
        // buttonStates — o deviceId efêmero pode ser reusado por outro hardware).
        PluviaApp.gamepadTouchpad.onDeviceRemoved(deviceId)
        // U1 (V6): estado do gyro morre junto; listener de sensor desregistrado (V3).
        gyroStates.remove(deviceId)
        gyroActivateHeld.remove(deviceId)
        // U3 (V6): camadas ativas morrem junto (deviceId efêmero).
        layerStates.remove(deviceId)
        sensorSource?.onDeviceRemoved(deviceId)
        val current = _connectedDevices.value
        if (deviceId !in current) return
        _connectedDevices.value = current - deviceId
        refreshActive()
        // Hotplug: deviceId é efêmero e pode ser reusado por outro hardware — o cache
        // de perfil (chaveado por deviceId) precisa morrer junto (M1).
        invalidateProfiles()
        PluviaApp.events.emit(GamepadDeviceRemovedEvent(deviceId))
        Timber.d("GamepadHub: removed %d", deviceId)
    }

    private fun refreshDevice(deviceId: Int) {
        if (_connectedDevices.value.containsKey(deviceId)) {
            removeDevice(deviceId)
            addDevice(deviceId)
        }
    }

    /** Mesma heurística do harness (DebugGamepadInput.gamepadDeviceId): CONTROLLER > TOUCHPAD. */
    private fun refreshActive() {
        _activeDevice.value = _connectedDevices.value.values.maxByOrNull { score(it) }
    }

    private fun score(device: GamepadDevice): Int =
        if (device.deviceClass == DeviceClass.CONTROLLER) 2 else 1

    /** Adapter fino InputDevice → dados puros do classificador. */
    private fun deviceFeatures(device: InputDevice): DeviceClassifier.DeviceFeatures {
        val faceKeys = device.hasKeys(
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y,
        )
        return DeviceClassifier.DeviceFeatures(
            isVirtual = device.isVirtual,
            sources = device.sources,
            hasAnyFaceButton = faceKeys.any { it },
            hasAxisX = device.getMotionRange(MotionEvent.AXIS_X) != null,
            hasAxisY = device.getMotionRange(MotionEvent.AXIS_Y) != null,
        )
    }
}
