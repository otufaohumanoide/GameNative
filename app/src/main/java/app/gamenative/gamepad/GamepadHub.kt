package app.gamenative.gamepad

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.events.GamepadDeviceAddedEvent
import app.gamenative.events.GamepadDeviceRemovedEvent
import app.gamenative.events.GamepadInputEvent
import app.gamenative.gamepad.mapping.EventTranslator
import app.gamenative.gamepad.mapping.MappingDatabase
import app.gamenative.gamepad.mapping.RawAxisInput
import app.gamenative.gamepad.mapping.RawKeyInput
import app.gamenative.gamepad.processing.DeadzoneConfig
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
        val alive = _connectedDevices.value.keys.toList()
        _connectedDevices.value = emptyMap()
        _activeDevice.value = null
        buttonStates.clear()
        for (deviceId in alive) {
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
        for (event in events) {
            logLogical(device, event)
            PluviaApp.events.emit(GamepadInputEvent(event))
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
        var emitted = false
        for (event in events) {
            val forward = when (event) {
                is InputEvent.ButtonDown -> state.add(event.button)
                is InputEvent.ButtonUp -> state.remove(event.button)
                is InputEvent.AxisMotion -> true
                else -> false
            }
            if (forward) {
                logLogical(device, event)
                PluviaApp.events.emit(GamepadInputEvent(event))
                emitted = true
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
        val device = GamepadDevice(
            deviceId = deviceId,
            descriptor = inputDevice.descriptor,
            vendorId = inputDevice.vendorId,
            productId = inputDevice.productId,
            name = inputDevice.name,
            deviceClass = deviceClass,
            faceStyle = faceStyle,
        )
        _connectedDevices.value = _connectedDevices.value + (deviceId to device)
        refreshActive()
        invalidateProfiles()
        PluviaApp.events.emit(GamepadDeviceAddedEvent(device))
        Timber.d(
            "GamepadHub: added id=%d name=%s vendor=%04x product=%04x class=%s",
            deviceId, device.name, device.vendorId, device.productId, deviceClass,
        )
    }

    private fun removeDevice(deviceId: Int) {
        buttonStates.remove(deviceId)
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
