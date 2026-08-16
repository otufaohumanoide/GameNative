package app.gamenative.gamepad

import android.content.Context
import android.hardware.BatteryState
import android.hardware.Sensor
import android.hardware.input.InputManager
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.events.GamepadDeviceAddedEvent
import app.gamenative.events.GamepadDeviceRemovedEvent
import app.gamenative.events.GamepadInputEvent
import app.gamenative.events.GamepadLayerEvent
import app.gamenative.gamepad.layers.LayerChange
import app.gamenative.gamepad.layers.LayerResolver
import app.gamenative.gamepad.layers.LayerState
import app.gamenative.gamepad.layers.LayerTriggerMode
import app.gamenative.gamepad.layers.TriggerEngine
import app.gamenative.gamepad.layers.TriggerEngineState
import app.gamenative.gamepad.layers.TriggerOutcome
import app.gamenative.gamepad.expressions.ChordLogic
import app.gamenative.gamepad.expressions.ExprBindingProcessor
import app.gamenative.gamepad.expressions.ExprState
import app.gamenative.gamepad.mapping.AndroidConstants
import app.gamenative.gamepad.mapping.AutoconfigCheck
import app.gamenative.gamepad.mapping.AutoconfigSaveResult
import app.gamenative.gamepad.mapping.AutoconfigValidation
import app.gamenative.gamepad.mapping.CapabilityMapping
import app.gamenative.gamepad.mapping.DeviceAutoconfig
import app.gamenative.gamepad.mapping.DeviceMappingStore
import app.gamenative.gamepad.mapping.DeviceQuirk
import app.gamenative.gamepad.mapping.DeviceQuirks
import app.gamenative.gamepad.mapping.EventTranslator
import app.gamenative.gamepad.mapping.GamepadCapabilities
import app.gamenative.gamepad.mapping.GamepadMapping
import app.gamenative.gamepad.mapping.MappingDatabase
import app.gamenative.gamepad.mapping.RawAxisInput
import app.gamenative.gamepad.mapping.SdlControllerDb
import app.gamenative.gamepad.mapping.RawBinding
import app.gamenative.gamepad.mapping.RawKeyInput
import app.gamenative.gamepad.remap.GamepadBindingCodec
import app.gamenative.gamepad.remap.mod
import app.gamenative.gamepad.remap.raw
import app.gamenative.gamepad.processing.DeadzoneConfig
import app.gamenative.gamepad.processing.DeadzoneMode
import app.gamenative.gamepad.processing.BindingModifier
import app.gamenative.gamepad.processing.BindingModifiers
import app.gamenative.gamepad.processing.LatencyTracker
import app.gamenative.gamepad.processing.FlickStickConfig
import app.gamenative.gamepad.processing.FlickStickProcessor
import app.gamenative.gamepad.processing.FlickStickState
import app.gamenative.gamepad.processing.GyroFusion
import app.gamenative.gamepad.processing.GyroFusionConfig
import app.gamenative.gamepad.processing.GyroFusionState
import app.gamenative.gamepad.processing.GyroActivation
import app.gamenative.gamepad.processing.GyroConfig
import app.gamenative.gamepad.processing.GyroMouseState
import app.gamenative.gamepad.processing.GyroPixelAccumulator
import app.gamenative.gamepad.processing.GyroState
import app.gamenative.gamepad.processing.GyroProcessor
import app.gamenative.gamepad.processing.MouseModeOutcome
import app.gamenative.gamepad.processing.MouseModeProcessor
import app.gamenative.gamepad.processing.MouseModeSpeed
import app.gamenative.gamepad.processing.MouseModeState
import app.gamenative.gamepad.processing.GyroSample
import app.gamenative.gamepad.processing.OneEuroFilter
import app.gamenative.gamepad.processing.StickSample
import app.gamenative.gamepad.profiles.GamepadProfile
import app.gamenative.gamepad.profiles.GamepadProfileStore
import app.gamenative.gamepad.profiles.ProfileResolver
import app.gamenative.ui.component.DebugPropertyCache
import app.gamenative.ui.component.GamepadHaptics
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

        /** F1.2: defaults do Flick Stick (spec — activationRadius 0.85, snap 15°). */
        const val DEFAULT_FLICK_ACTIVATION_RADIUS = 0.85f
        const val DEFAULT_FLICK_SNAP_ANGLE = 15f

        /** F1.3: Kp default do Mahony (padrão do paper). */
        const val DEFAULT_FUSION_KP = 0.5f

        /** Propriedade do trace de amostras de sensor (default off — logcat legível). */
        const val SENSOR_TRACE_PROPERTY = "debug.gamenative.sensortrace"

        // H (spec 2026-08-16-H-binding-modifiers-duckstation, §2.3): pares eixo
        // semântico → botão lógico que carrega o mod no token da camada. Sticks usam
        // o botão de CLIQUE (LEFT_STICK/RIGHT_STICK) como porta-token — o nome do
        // eixo não existe como chave de layers; triggers usam o próprio botão trigger.
        private val TRIGGER_PAIRS: List<Pair<GamepadAxis, GamepadButton>> = listOf(
            GamepadAxis.LEFT_TRIGGER to GamepadButton.LEFT_TRIGGER,
            GamepadAxis.RIGHT_TRIGGER to GamepadButton.RIGHT_TRIGGER,
        )

        private val AXIS_BUTTON_PAIRS: List<Pair<GamepadAxis, GamepadButton>> = TRIGGER_PAIRS + listOf(
            GamepadAxis.LEFT_X to GamepadButton.LEFT_STICK,
            GamepadAxis.LEFT_Y to GamepadButton.LEFT_STICK,
            GamepadAxis.RIGHT_X to GamepadButton.RIGHT_STICK,
            GamepadAxis.RIGHT_Y to GamepadButton.RIGHT_STICK,
        )
    }

    private val appContext = context.applicationContext
    private val inputManager = appContext.getSystemService(Context.INPUT_SERVICE) as InputManager

    private val _connectedDevices = MutableStateFlow<Map<Int, GamepadDevice>>(emptyMap())
    val connectedDevices: StateFlow<Map<Int, GamepadDevice>> = _connectedDevices.asStateFlow()

    private val _activeDevice = MutableStateFlow<GamepadDevice?>(null)
    val activeDevice: StateFlow<GamepadDevice?> = _activeDevice.asStateFlow()

    /**
     * Spec 2026-08-16-C §1.2: preview do gyro para o card de diagnóstico
     * ([DeviceDiagnosticsCard]) — última amostra processada pelo [GyroProcessor] de
     * CADA device (o card filtra por deviceId). Hook de observação: ligado pelo card
     * no DisposableEffect enquanto expandido; 1 write por amostra quando ON, ZERO
     * quando OFF (caminho byte-identical com OFF). NÃO altera o pipeline — só observa.
     */
    @Volatile
    var gyroPreviewEnabled: Boolean = false

    private val _gyroPreview = MutableStateFlow<GyroPreview?>(null)
    val gyroPreview: StateFlow<GyroPreview?> = _gyroPreview.asStateFlow()

    private val deviceStore =
        GamepadProfileStore(File(File(appContext.filesDir, "gamepad"), "device_profiles.json"))
    private val gameStore =
        GamepadProfileStore(File(File(appContext.filesDir, "gamepad"), "game_profiles.json"))

    /**
     * K5 (spec 2026-08-16-K5, §1.1): autoconfig por device (mapping RAW→LÓGICO —
     * camada de BAIXO; NÃO é o perfil lógico do [GamepadProfileStore]). Um arquivo
     * por controle em `<filesDir>/deviceMappings/<mappingKey>.json`; alimenta o
     * tier USER da cadeia e a fase K6 (import/export SDL).
     */
    private val autoconfigStore =
        DeviceMappingStore(File(appContext.filesDir, "deviceMappings"))

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
        private set

    /**
     * F3.2 (spec 2026-08-15-input-core-avancado): troca de container em foreground —
     * re-resolve o perfil pelo appId novo SEM reconectar. O re-resolver em si é o
     * cache M1 (chave deviceId:appId); aqui, a RE-EMISSÃO de bindings: botões lógicos
     * fisicamente segurados reemitem pelo perfil NOVO (sem isto o jogo novo só veria
     * o remap novo no PRÓXIMO evento físico — um botão segurado na troca ficaria com
     * o binding velho até ser solto e apertado de novo).
     */
    fun setActiveAppId(appId: String?) {
        val previous = activeAppId
        if (appId == previous) return
        activeAppId = appId
        invalidateProfiles()
        if (!PrefManager.gamepadUniversalEnabled) return
        for ((deviceId, held) in buttonStates.toMap()) {
            if (held.isEmpty()) continue
            val device = deviceFor(deviceId) ?: continue
            val mapping = mappingFor(device)
            val profile = profileFor(deviceId, appId)
            val layerState = layerStates.getOrPut(deviceId) { LayerState() }
            // O gyro re-arma pelo perfil novo (o emitLogical registra o activate).
            // G5 (spec 2026-08-16-G-gyro-v2): o latch de toggle morre junto — a
            // ativação reinicia fechada com o perfil novo (V6).
            gyroActivateHeld.remove(deviceId)
            gyroActivateLatches.remove(deviceId)
            // I: triggers pendentes (long-press armado/sequência em curso) e emits
            // retardados morrem na troca de container — a ativação reinicia fechada
            // com o perfil novo (mesmo padrão G5/V6).
            triggerEngineStates.remove(deviceId)
            pendingEmits.remove(deviceId)
            for (button in held.toList()) {
                emitLogical(device, mapping, profile, layerState, InputEvent.ButtonDown(deviceId, button), deviceId)
            }
        }
    }

    /**
     * F2.4 (spec 2026-08-15-input-core-avancado): foco da janela — escrito pelo
     * MainActivity.onWindowFocusChanged. Com foco perdido, ativação de camada/tick é
     * no-op (nunca input fantasma durante perda de foco — gap da auditoria).
     */
    @Volatile
    var windowFocused: Boolean = true

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
        mappingCache.clear()
        quirkCache.clear()
        // K5: base capturado morre junto (deviceId efêmero, padrão K3/K4).
        baseMappingCache.clear()
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
        // J1: estados das expressões RESETAM na troca de perfil (borda — padrão
        // GyroProcessor) e o cache do parse morre junto.
        exprStates.clear()
        exprBindingCache.clear()
        exprLastEvalMs.clear()
        logicalInputState.clear()
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

    /**
     * K2 (spec 2026-08-16-K2, §1.4): perfil BRUTO do device (sem merge com jogo/
     * globais) — a UI do modo mouse edita SÓ o campo dela sobre o bruto e salva
     * (nunca congela o merge no save, padrão do repo). null = sem entrada salva.
     */
    fun deviceProfileFor(deviceId: Int): GamepadProfile? {
        val device = deviceFor(deviceId) ?: return null
        return deviceStore.load(device.mappingKey)
    }

    /**
     * K5 (spec 2026-08-16-K5, §1.3.2): valida o mapping BASE capturado no addDevice
     * (port clean-room de configuration.c:8206-8233 — sem FACE_BOTTOM ou sem
     * direção de dpad o perfil é inútil/navegável quebrado). Sem device = válido
     * (o [saveAutoconfig] guarda o NoDevice — a UI nunca chega aqui sem device).
     */
    fun autoconfigCheck(deviceId: Int): AutoconfigCheck {
        val device = deviceFor(deviceId) ?: return AutoconfigCheck.Valid
        val base = baseMappingCache[device.deviceId] ?: resolveBaseMapping(device).first
        return AutoconfigValidation.validate(base)
    }

    /** K5 §1.3: autoconfig salvo para o [mappingKey] (card — badge/restaurar). Main thread. */
    fun savedAutoconfig(mappingKey: String): DeviceAutoconfig? = autoconfigStore.load(mappingKey)

    /**
     * K5 (spec 2026-08-16-K5, §1.3.1/§1.3.4): monta o [DeviceAutoconfig] do mapping
     * EFETIVO (base PRÉ-quirk capturado no addDevice), valida (1.3.2), salva e
     * RE-RESOLVE ao vivo — análogo ao reconect do RetroArch (após gravar o autoconf,
     * o RA limpa binds manuais e reconecta para o perfil salvo já valer): o device
     * conectado passa a usar o tier USER na hora, sem reconectar fisicamente.
     */
    fun saveAutoconfig(deviceId: Int): AutoconfigSaveResult {
        val device = deviceFor(deviceId) ?: return AutoconfigSaveResult.NoDevice
        val base = baseMappingCache[device.deviceId] ?: resolveBaseMapping(device).first
        when (val check = AutoconfigValidation.validate(base)) {
            is AutoconfigCheck.Invalid -> return AutoconfigSaveResult.Invalid(check.reason)
            is AutoconfigCheck.Valid -> Unit
        }
        val overwrote = savedAutoconfig(device.mappingKey) != null
        val config = DeviceAutoconfig(
            mappingKey = device.mappingKey,
            deviceName = device.name,
            mapping = base,
            faceStyle = base.faceStyle,
            createdAtMs = System.currentTimeMillis(),
        )
        autoconfigStore.save(config)
        reResolveAutoconfig(device.mappingKey)
        Timber.d(
            "gncontrol: autoconfig %s salvo — tier USER ativo no device %d (%s)",
            device.mappingKey, deviceId, device.name,
        )
        return AutoconfigSaveResult.Saved(config, overwrote)
    }

    /**
     * K5 §1.3.5: "Restaurar automático" — deleta o autoconfig do [mappingKey] e
     * re-resolve (a cadeia volta a MODEL/SDL_DB/CAPABILITIES/DEFAULT na hora, sem
     * reconexão física). Chave ausente = no-op.
     */
    fun deleteAutoconfig(mappingKey: String) {
        if (autoconfigStore.load(mappingKey) == null) return
        autoconfigStore.delete(mappingKey)
        reResolveAutoconfig(mappingKey)
        Timber.d(
            "gncontrol: autoconfig %s removido — cadeia MODEL/SDL_DB/CAPABILITIES/DEFAULT",
            mappingKey,
        )
    }

    /**
     * K6 (spec 2026-08-16-K6, §1.2): import de um mapping já DECODADO (parse do
     * [SdlControllerDb] na UI — o hub NÃO parseia strings). Escreve o tier USER
     * exatamente como o save do K5 ([DeviceMappingStore] + re-resolve ao vivo) e
     * valida com a MESMA regra do RetroArch ([AutoconfigValidation]) — uma string
     * de fórum sem botão de confirmação/navegação deixaria o controle inutilizável
     * nos menus. `mapping.name` = nome da string (display no mapping); o
     * [DeviceAutoconfig.deviceName] continua sendo o nome do DEVICE (K5).
     */
    fun importAutoconfig(deviceId: Int, mapping: GamepadMapping): AutoconfigSaveResult {
        val device = deviceFor(deviceId) ?: return AutoconfigSaveResult.NoDevice
        when (val check = AutoconfigValidation.validate(mapping)) {
            is AutoconfigCheck.Invalid -> return AutoconfigSaveResult.Invalid(check.reason)
            is AutoconfigCheck.Valid -> Unit
        }
        val overwrote = savedAutoconfig(device.mappingKey) != null
        val config = DeviceAutoconfig(
            mappingKey = device.mappingKey,
            deviceName = device.name,
            mapping = mapping,
            faceStyle = mapping.faceStyle,
            createdAtMs = System.currentTimeMillis(),
        )
        autoconfigStore.save(config)
        reResolveAutoconfig(device.mappingKey)
        Timber.d(
            "gncontrol: autoconfig %s importado (formato SDL) — tier USER ativo no device %d (%s)",
            device.mappingKey, deviceId, device.name,
        )
        return AutoconfigSaveResult.Saved(config, overwrote)
    }

    /**
     * K6 (spec 2026-08-16-K6, §1.3): mapping BASE (pré-quirk) do device — o que o
     * export no formato SDL serializa (quirk é correção de TRANSPORTE, não
     * identidade do controle — mesmo racional do save do K5). Main thread; null =
     * sem device conectado com esse id.
     */
    fun baseMappingFor(deviceId: Int): GamepadMapping? {
        val device = deviceFor(deviceId) ?: return null
        return baseMappingCache[device.deviceId] ?: resolveBaseMapping(device).first
    }

    /**
     * K6 (spec 2026-08-16-K6, §1.2): mapping EFETIVO (pós-quirk) do device — a
     * referência do DIFF do preview de import ("o que muda em relação ao que o
     * controle usa AGORA"). Main thread; null = sem device.
     */
    fun effectiveMappingFor(deviceId: Int): GamepadMapping? {
        val device = deviceFor(deviceId) ?: return null
        return resolveMapping(device).first
    }

    /**
     * K5 §1.3.4: re-resolve AO VIVO dos devices conectados com o [mappingKey]
     * (análogo ao reconect do RetroArch — o mapping USER passa a valer na hora).
     * Invalida os caches por deviceId (K3/K4/K5) e atualiza o
     * [GamepadDevice.mappingSource] no StateFlow (o card mostra o badge USER ao
     * vivo). O hub não tem padrão de evento de "mapping changed"
     * (Added/Removed/Input/Layer) — log + invalidação + StateFlow, como o spec pede.
     */
    private fun reResolveAutoconfig(mappingKey: String) {
        for ((deviceId, device) in _connectedDevices.value) {
            if (device.mappingKey != mappingKey) continue
            mappingCache.remove(deviceId)
            baseMappingCache.remove(deviceId)
            val (_, source) = resolveMapping(device)
            _connectedDevices.value = _connectedDevices.value +
                (deviceId to device.copy(mappingSource = source))
        }
    }

    /**
     * Perfil BRUTO do escopo JOGO (chave = appId), SEM merge com o device
     * (spec 2026-08-16-B-remap-visual-ppsspp, §1.4): o mapa visual do remap edita o
     * override por-jogo em separado — o merge (JOGO > GLOBAL > AUTO) continua sendo do
     * [GamepadProfileStore.merged] via [profileFor]. null = sem entrada para esse jogo.
     */
    fun gameProfileFor(appId: String?): GamepadProfile? = appId?.let { gameStore.load(it) }

    /**
     * Persiste o perfil do JOGO (chave = appId) e invalida o cache (spec B §1.4).
     * Um perfil default REMOVE a entrada (padrão do store).
     */
    fun saveGameProfile(appId: String, profile: GamepadProfile) {
        gameStore.save(appId, profile)
        invalidateProfiles()
    }

    /**
     * E (spec 2026-08-16-E-profile-catalog-comunitario, §1.4): appIds com override
     * de perfil — badge "personalizado" na Library (um read por visita à tela; o
     * cache do store responde sem disco a partir do segundo read). Main thread
     * apenas (contrato M1 do store).
     */
    fun profileOverrideGameIds(): Set<String> = gameStore.overrideKeys()

    // U3: estado de camadas por device (V6 — morto no removeDevice).
    private val layerStates = mutableMapOf<Int, LayerState>()

    // I (spec 2026-08-16-I-trigger-engine-keymapper, §2.3): estado do engine por
    // device (V6 — morto no removeDevice; limpo na troca de container) e a fila de
    // emits retardados (disambiguação #1386): deviceId → botão → (eventos, prazo).
    private val triggerEngineStates = mutableMapOf<Int, TriggerEngineState>()

    private val pendingEmits = mutableMapOf<Int, MutableMap<String, PendingEmit>>()

    // J1 (spec 2026-08-16-J-expressions-dolphin, §2.2): expressões ATIVAS do perfil
    // efetivo — cache do parse por (bindings efetivos) por device (M1); avaliadas
    // no MESMO flush de eventos da fase I. Estado por device (V6) + estado lógico
    // vivo dos botões/eixos (reader das expressões). SEM token expr: ⇒ zero parse,
    // zero alocação (byte-identical).
    private val exprBindingCache = mutableMapOf<Int, MutableMap<Map<String, String>, List<ExprBindingProcessor.Parsed>>>()
    private val exprStates = mutableMapOf<Int, ExprState>()
    private val exprLastEvalMs = mutableMapOf<Int, Long>()
    private val logicalInputState = mutableMapOf<Int, MutableMap<String, Float>>()

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

    /**
     * U3 §1.3 (1): triggers resolvem no botão FÍSICO (antes do remap).
     *
     * F (spec 2026-08-16-F-radial-v2-modeshift-turbo, §1.3): retorna true quando o
     * trigger é de camada SHIFT — o evento físico é CONSUMIDO (o chamador não chama
     * emitLogical; o botão não chega ao jogo — camada comum é pass-through) e NÃO
     * emite GamepadLayerEvent (não abre radial, não compete com triggers reais) nem
     * tick háptico. A ativação em si é a MESMA (LayerResolver intacto — a mecânica
     * U3 é preservada pelo branch).
     *
     * I (spec 2026-08-16-I-trigger-engine-keymapper, §2.3): para LONG_PRESS/SEQUENCE
     * o [TriggerEngine] é consultado ANTES do resolver — o botão do trigger é
     * consumido desde o down (isShift implícito para os modos novos) e o Down lógico
     * do 1º botão de uma sequência fica RETARDADO em [pendingEmits] até a resolução
     * (disambiguação #1386). ConsumeDelay/ReleaseDelay só mexem na fila — o segundo
     * vence por ordem determinística (ConsumeDelay primeiro: um completamento vence
     * a morte da sequência sobreposta).
     */
    private fun resolveLayerTriggers(
        device: GamepadDevice,
        mapping: GamepadMapping,
        layerState: LayerState,
        profile: GamepadProfile,
        event: InputEvent,
    ): Boolean {
        // F2.4 (spec 2026-08-15-input-core-avancado): sem foco de janela, ativação de
        // camada (e o tick dela) é no-op — nunca input fantasma durante perda de foco.
        if (!windowFocused) return false
        val (button, deviceId) = when (event) {
            is InputEvent.ButtonDown -> event.button to event.deviceId
            is InputEvent.ButtonUp -> event.button to event.deviceId
            else -> return false
        }
        val now = android.os.SystemClock.uptimeMillis()
        // I: engine primeiro — consultado para botões dos specs novos E para TODO
        // botão enquanto há sequência pendente (botão errado mata a sequência).
        val engineState = triggerEngineStates.getOrPut(deviceId) { TriggerEngineState() }
        var engineConsumed = false
        val sequencesPending = engineState.seqProgress.isNotEmpty()
        val engineOutcomes = mutableListOf<TriggerOutcome>()
        for ((layerName, spec) in profile.layerTriggers) {
            val isNewMode = spec.mode == LayerTriggerMode.LONG_PRESS ||
                spec.mode == LayerTriggerMode.SEQUENCE
            if (!isNewMode) continue
            val involved = spec.button == button.name ||
                spec.sequence.contains(button.name) ||
                (sequencesPending && engineState.seqProgress.containsKey(layerName))
            if (!involved) continue
            engineOutcomes += when (event) {
                is InputEvent.ButtonDown ->
                    TriggerEngine.onButtonDown(engineState, profile.layerTriggers, layerName, spec, button.name, now)
                is InputEvent.ButtonUp ->
                    TriggerEngine.onButtonUp(engineState, profile.layerTriggers, layerName, spec, button.name, now)
                else -> emptyList()
            }
        }
        if (engineOutcomes.isNotEmpty()) {
            // ConsumeDelay ANTES de ReleaseDelay: um completamento vence a morte da
            // sequência sobreposta (o retardo é descartado, nunca liberado).
            val ordered = engineOutcomes.sortedBy { if (it is TriggerOutcome.ConsumeDelay) 0 else 1 }
            for (outcome in ordered) {
                if (applyTriggerOutcome(outcome, event, device, mapping, layerState, profile, deviceId)) {
                    engineConsumed = true
                }
            }
        }
        if (engineConsumed) return true

        // Caminho existente (HOLD/TOGGLE/DOUBLE_TAP) — byte-identical.
        val trigger = profile.layerTriggers.entries.firstOrNull { (_, spec) ->
            spec.button == button.name
        } ?: return false
        val change = when (event) {
            is InputEvent.ButtonDown -> LayerResolver.onButtonDown(layerState, trigger.key, trigger.value, now)
            is InputEvent.ButtonUp -> LayerResolver.onButtonUp(layerState, trigger.key, trigger.value, now)
            else -> null
        }
        // F §1.3: camada de SHIFT suprime os eventos comuns (decisão PURA no
        // LayerResolver — testada em JVM).
        val shift = LayerResolver.suppressCommonEvents(trigger.value)
        // F2.3: tick háptico na ATIVAÇÃO da camada (LayerChange.Activated) — mesmo
        // gate global do rumble + toggle dedicado (GamepadHaptics.tickDevice).
        // F3.1: o evento de camada no bus é o gatilho do Radial Menu.
        when (change) {
            is LayerChange.Activated -> {
                if (!shift) {
                    GamepadHaptics.tickDevice(deviceId)
                    PluviaApp.events.emit(GamepadLayerEvent(deviceId, change.layer, true))
                }
            }
            is LayerChange.Deactivated -> {
                if (!shift) {
                    PluviaApp.events.emit(GamepadLayerEvent(deviceId, change.layer, false))
                }
            }
            else -> {}
        }
        return shift
    }

    /**
     * I §2.3: relógio do engine + liberação de emits retardados — SEM timer novo:
     * o TOPO de onKey/onAxis/onSensorSample varre a fila minúscula do device
     * (os eventos de input são o relógio; ~120 Hz de polling de stick). O
     * [TriggerEngine.onClock] dispara LONG_PRESS no limiar e mata passos de
     * sequência expirados; a varredura de prazo libera os Downs guardados vencidos
     * (disambiguação #1386). Sem engine armado e fila vazia → retorno imediato
     * (zero custo — byte-identical).
     */
    private fun flushTriggerClock(
        deviceId: Int,
        device: GamepadDevice,
        mapping: GamepadMapping,
        profile: GamepadProfile,
        layerState: LayerState,
    ) {
        if (!windowFocused) return
        val engineState = triggerEngineStates[deviceId]
        val pending = pendingEmits[deviceId]
        if (engineState == null && pending.isNullOrEmpty()) return
        val now = android.os.SystemClock.uptimeMillis()
        if (engineState != null) {
            for (outcome in TriggerEngine.onClock(engineState, profile.layerTriggers, now)) {
                applyTriggerOutcome(outcome, null, device, mapping, layerState, profile, deviceId)
            }
        }
        pending?.let { entries ->
            for ((button, entry) in entries.toList()) {
                if (now >= entry.deadlineMs) {
                    entries.remove(button)
                    for (stored in entry.events) {
                        emitLogical(device, mapping, profile, layerState, stored, deviceId)
                    }
                }
            }
        }
    }

    /**
     * J1 (spec 2026-08-16-J-expressions-dolphin, §2.2): dobra os eventos lógicos do
     * MOMENTO no estado vivo ([logicalInputState]) e avalia as expressões ATIVAS do
     * perfil efetivo — no MESMO flush de eventos da fase I. Sem token `expr:` no
     * perfil efetivo ⇒ retorno imediato (zero parse/alocação no hot path —
     * byte-identical). Emissão direta no bus (o valor da expressão É o valor
     * lógico do botão — não passa pelo remapEvent, que consumiria o próprio token).
     */
    private fun flushExpressions(
        deviceId: Int,
        device: GamepadDevice,
        mapping: GamepadMapping,
        profile: GamepadProfile,
        layerState: LayerState,
        events: List<InputEvent>,
    ): ExprFlush? {
        if (profile.layers.isEmpty()) return null
        val effective = LayerResolver.effectiveBindings(profile.layers, layerState.activeLayer)
        if (effective.isEmpty()) return null
        val bindings = exprBindingCache.getOrPut(deviceId) { mutableMapOf() }
            .getOrPut(effective) { ExprBindingProcessor.parseBindings(effective) }
        if (bindings.isEmpty()) return null
        // Dobra o evento atual ANTES da avaliação (o próprio input não sofre lag).
        val inputState = logicalInputState.getOrPut(deviceId) { mutableMapOf() }
        for (event in events) {
            when (event) {
                is InputEvent.ButtonDown -> inputState[event.button.name.lowercase()] = 1f
                is InputEvent.ButtonUp -> inputState[event.button.name.lowercase()] = 0f
                is InputEvent.AxisMotion -> inputState["axis:" + event.axis.name.lowercase()] = event.value
                else -> {}
            }
        }
        val now = android.os.SystemClock.uptimeMillis()
        val lastEval = exprLastEvalMs[deviceId] ?: now
        exprLastEvalMs[deviceId] = now
        val dtMs = (now - lastEval).coerceIn(1L, 100L)
        val state = exprStates.getOrPut(deviceId) { ExprState() }
        // J2 (spec 2026-08-16-J, §3): registro de chords do perfil + botões
        // segurados — o chord avalia pelo registro e a supressão do binding
        // simples do botão final usa os modificadores segurados.
        val chords = ExprBindingProcessor.chordsOf(bindings)
        val held = inputState.filterValues { it > 0.5f }.keys
        // Os nomes já vêm normalizados do parser (GamepadButton.name lowercased /
        // axis:left_x) — o leitor só consulta o estado vivo.
        val reader: (String, Boolean) -> Float = { name, axis ->
            inputState[if (axis) "axis:$name" else name.lowercase()] ?: 0f
        }
        for (event in ExprBindingProcessor.evaluate(bindings, reader, state, dtMs, now, deviceId, held, chords)) {
            logLogical(device, event)
            PluviaApp.events.emit(GamepadInputEvent(event))
        }
        return ExprFlush(held, chords)
    }

    /** J2 §3: o botão é o FINAL de um chord com os modificadores segurados. */
    private fun isChordSuppressed(flush: ExprFlush, event: InputEvent): Boolean {
        val button = when (event) {
            is InputEvent.ButtonDown -> event.button.name.lowercase()
            is InputEvent.ButtonUp -> event.button.name.lowercase()
            else -> return false
        }
        return ChordLogic.suppressFinal(flush.chords, flush.held, button)
    }

    /**
     * I §2.3: aplica um outcome do engine. Retorna true quando o EVENTO atual foi
     * consumido pelo trigger. [event] é null nos outcomes do relógio (flush) — nesse
     * caso só Activate/Deactivate/ReleaseDelay aparecem (nunca DelayEmit/Consume).
     */
    private fun applyTriggerOutcome(
        outcome: TriggerOutcome,
        event: InputEvent?,
        device: GamepadDevice,
        mapping: GamepadMapping,
        layerState: LayerState,
        profile: GamepadProfile,
        deviceId: Int,
    ): Boolean {
        when (outcome) {
            is TriggerOutcome.Activate -> {
                if (layerState.activeLayer != outcome.layer) {
                    layerState.activeLayer = outcome.layer
                    val spec = profile.layerTriggers[outcome.layer]
                    if (spec == null || !spec.isShift) {
                        GamepadHaptics.tickDevice(deviceId)
                        PluviaApp.events.emit(GamepadLayerEvent(deviceId, outcome.layer, true))
                    }
                }
                return true
            }
            is TriggerOutcome.Deactivate -> {
                if (layerState.activeLayer == outcome.layer) {
                    layerState.activeLayer = null
                    val spec = profile.layerTriggers[outcome.layer]
                    if (spec == null || !spec.isShift) {
                        PluviaApp.events.emit(GamepadLayerEvent(deviceId, outcome.layer, false))
                    }
                }
                return true
            }
            is TriggerOutcome.DelayEmit -> {
                if (event == null) return false
                val entry = pendingEmits.getOrPut(deviceId) { mutableMapOf() }
                    .getOrPut(outcome.button) { PendingEmit(mutableListOf(), 0L) }
                // Só o Down DO PRÓPRIO botão retardado entra na fila (o re-estendimento
                // de um passo avançado carrega o evento do passo, que é só consumido).
                if (event is InputEvent.ButtonDown &&
                    event.button.name == outcome.button &&
                    entry.events.none { it is InputEvent.ButtonDown }
                ) {
                    entry.events += event
                }
                entry.deadlineMs = maxOf(entry.deadlineMs, outcome.untilMs)
                return true
            }
            is TriggerOutcome.ReleaseDelay -> {
                val entry = pendingEmits[deviceId]?.remove(outcome.button) ?: return false
                for (stored in entry.events) {
                    emitLogical(device, mapping, profile, layerState, stored, deviceId)
                }
                return false
            }
            is TriggerOutcome.ConsumeDelay -> {
                pendingEmits[deviceId]?.remove(outcome.button)
                return true
            }
            is TriggerOutcome.Consume -> {
                if (event == null) return false
                // Up de botão com Down consumido: se o Down está RETARDADO, o Up entra
                // NA MESMA fila (o par Down/Up sai junto na liberação — nunca um Down
                // fantasma sem Up); sem fila, o evento morre aqui (balanço).
                val buttonName = when (event) {
                    is InputEvent.ButtonDown -> event.button.name
                    is InputEvent.ButtonUp -> event.button.name
                    else -> null
                }
                val entry = buttonName?.let { pendingEmits[deviceId]?.get(it) }
                if (entry != null && event is InputEvent.ButtonUp &&
                    entry.events.none { it is InputEvent.ButtonUp }
                ) {
                    entry.events += event
                }
                return true
            }
            TriggerOutcome.None -> return false
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
        val emitted = remapEvent(device, mapping, profile, event, layerState)
        val activateButton = profile.gyroActivateButton
        for (e in emitted) {
            if (activateButton != null && e is InputEvent.ButtonDown && e.button.name == activateButton) {
                gyroActivateHeld[deviceId] = activateButton
                // G5 (spec 2026-08-16-G-gyro-v2): borda de PRESS do botão de
                // ativação flipa o latch por device (toggle — padrão DS4Windows
                // IsGyroTriggerActive; correção G-v2-revisão: antes era no release).
                // O recenter da borda off→on já existe no GyroProcessor — sai de
                // graça.
                if (profile.gyroActivateToggle == true) {
                    gyroActivateLatches[deviceId] = GyroActivation.onPressButton(
                        latch = gyroActivateLatches[deviceId] ?: false,
                        toggle = true,
                    )
                }
            } else if (activateButton != null && e is InputEvent.ButtonUp && e.button.name == activateButton) {
                gyroActivateHeld.remove(deviceId)
            }
            logLogical(device, e)
            PluviaApp.events.emit(GamepadInputEvent(e))
        }
        return emitted.isNotEmpty()
    }

    /**
     * Aplica o remap da camada ativa a um evento de botão (U3). P3-6 (spec
     * 2026-08-14-gamepad-upgrades-pendencias): recebe o [profile] já resolvido pelo
     * chamador — antes re-resolvia `profileFor` por evento (chamada por
     * botão×evento; o cache M1 tornava barato, mas o parâmetro já estava em mão).
     */
    private fun remapEvent(
        device: GamepadDevice,
        mapping: GamepadMapping,
        profile: GamepadProfile,
        event: InputEvent,
        layerState: LayerState,
    ): List<InputEvent> {
        val bindings = LayerResolver.effectiveBindings(
            profile.layers,
            layerState.activeLayer,
        )
        if (bindings.isEmpty()) return listOf(event)
        val button = when (event) {
            is InputEvent.ButtonDown -> event.button
            is InputEvent.ButtonUp -> event.button
            else -> return listOf(event)
        }
        val token = bindings[button.name] ?: return listOf(event)
        // F §1.4: o flag turbo vive no token, mas o remap LÓGICO não pulsa — o
        // turbo é aplicado na injeção física (PhysicalControllerHandler, caminho U4).
        // J1 §2.2: binding `expr:` NÃO é fonte física — a expressão É o dono do
        // botão (o evento físico é consumido; o valor lógico vem do avaliador).
        // Verificação (spec-2026-08-16-master-roadmap-input-avancado-verificacao
        // §5.2): SÓ ExprBinding consome — token malformado (perfil corrompido)
        // volta ao PASS-THROUGH do comportamento pré-J.
        return when (val decoded = GamepadBindingCodec.decode(token)) {
            is GamepadBindingCodec.LayerBinding.ExprBinding -> emptyList()
            null -> listOf(event)
            is GamepadBindingCodec.LayerBinding.Physical -> {
                val binding = decoded.raw
                val down = event is InputEvent.ButtonDown
                when (binding) {
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
                    // Hat não é alvo de remap de botão no caminho lógico (decisão
                    // registrada — o remap de dpad no jogo passa pelo canal de tecla).
                    is RawBinding.Hat -> emptyList()
                }
            }
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
     * - MOUSE → sink de mouse compartilhado (XServer injectPointerMoveDelta), com
     *   OneEuro opt-in (G2) e acumulador sub-pixel (G1) — spec 2026-08-16-G-gyro-v2;
     * - CAMERA → `gyroCameraSink` (setado pelo XServerScreen — P1-1) com a
     *   VELOCIDADE angular (rad/s, P1-2 — padrão DS4Windows: deflexão = f(velocidade),
     *   não integral; o PhysicalControllerHandler mapeia no right stick do virtual
     *   gamepad e zera quando a rotação para), sensibilidade por eixo + inversão (G3)
     *   e shaping da deflexão (G4).
     */
    fun onSensorSample(
        deviceId: Int,
        gyroX: Float,
        gyroY: Float,
        gyroZ: Float,
        accelX: Float,
        accelY: Float,
        accelZ: Float,
        nowMs: Long,
    ) {
        // F0 (spec 2026-08-15-input-core-avancado): t0 da medição de latência do
        // caminho de sensor — onSensorSample → PhysicalControllerHandler.applyCameraGyro.
        LatencyTracker.begin(LatencyTracker.Source.SENSOR, System.nanoTime())
        if (!PrefManager.gamepadUniversalEnabled) return
        val device = deviceFor(deviceId) ?: return
        if (device.deviceClass != DeviceClass.CONTROLLER && device.deviceClass != DeviceClass.TOUCHPAD) return
        if (!device.hasGyro) return
        val profile = profileFor(deviceId, activeAppId)
        // I §2.3: relógio do engine também no caminho de sensor (~50 Hz — suficiente
        // para os vencimentos; o MOUSE/CAMERA é o único caminho que não vê teclas).
        val sensorLayerState = layerStates.getOrPut(deviceId) { LayerState() }
        flushTriggerClock(deviceId, device, mappingFor(device), profile, sensorLayerState)
        // J1 §2.2: o caminho de sensor (~50 Hz) também avalia expressões — o
        // relógio das funções temporais (timer/smooth/hold) não depende de teclas.
        flushExpressions(deviceId, device, mappingFor(device), profile, sensorLayerState, emptyList())
        val gyroState = gyroStates.getOrPut(deviceId) { GyroState() }
        val output = GyroProcessor.process(
            sample = GyroSample(gyroX, gyroY, gyroZ, nowMs, accelX, accelY, accelZ),
            state = gyroState,
            config = GyroConfig(
                deadzone = profile.gyroDeadzone ?: DEFAULT_GYRO_DEADZONE,
                // G6 (spec 2026-08-16-G-gyro-v2): grip angle do perfil (null = 0 =
                // eixos atuais — byte-identical).
                gripAngleDeg = profile.gyroGripAngleDeg ?: 0f,
            ),
            activate = gyroActivateHeld(deviceId, profile),
        )
        // Evento lógico sempre emitido para device com gyro (consumidores futuros).
        val event = InputEvent.SensorUpdate(
            deviceId = deviceId,
            gyroX = gyroX,
            gyroY = gyroY,
            gyroZ = gyroZ,
            // P2-3: accel real do device (a fonte registra os DOIS — padrão SDL).
            accelX = accelX,
            accelY = accelY,
            accelZ = accelZ,
        )
        // O sensor entrega ~40 Hz — logar TODA amostra inunda o logcat e gira o
        // buffer em segundos (sessão on-device 2026-08-14: navegação de menu ficou
        // ilegível). Só loga com `debug.gamenative.sensortrace 1`.
        if (DebugPropertyCache.read(SENSOR_TRACE_PROPERTY) == "1") {
            logLogical(device, event)
        }
        PluviaApp.events.emit(GamepadInputEvent(event))
        // G3 (spec 2026-08-16-G-gyro-v2): sensibilidade por eixo + inversão
        // (null = usa gyroSensitivity; null = false — sinal já absorvido aqui).
        val sensitivity = profile.gyroSensitivity ?: 1f
        val sensX = sensitivity * (if (profile.gyroInvertX == true) -1f else 1f)
        val sensY = (profile.gyroSensitivityY ?: sensitivity) * (if (profile.gyroInvertY == true) -1f else 1f)
        if (!output.active) {
            // G1/G2: o estado do OneEuro E o resto sub-pixel morrem quando o gyro
            // desativa — o próximo período ativo recomeça limpo (mesmo padrão do
            // SixMouseReset do DS4Windows).
            gyroSmoothStates.remove(deviceId)
            gyroMouseStates.remove(deviceId)
            // G3/G5: com CAMERA ativo, a amostra inativa (botão solto / toggle off)
            // leva o REPOUSO ao stick — o branch existia no código antigo mas
            // ficava ATRÁS deste return (morto: a deflexão congelava no último
            // valor ao soltar; o toggle do G5 tornaria o congelamento visível a
            // cada desligada).
            if ((profile.gyroMode ?: GyroMode.OFF) == GyroMode.CAMERA) {
                gyroCameraSink?.invoke(
                    0f, 0f, sensX, sensY,
                    profile.gyroStickMaxOutput ?: 1f,
                    profile.gyroStickAntiDeadzone ?: 0f,
                )
            }
            return
        }

        when (profile.gyroMode ?: GyroMode.OFF) {
            GyroMode.MOUSE -> {
                // G2: OneEuro opt-in — filtra deltaXRad/deltaYRad POR EIXO antes da
                // conversão em pixels (ambos null = OFF → byte-identical, sem
                // alocação de estado). Compõe com G1: o acumulador recebe o delta
                // já filtrado.
                var dxRad = output.deltaXRad
                var dyRad = output.deltaYRad
                val smooth = gyroSmoothFor(deviceId, profile, nowMs)
                if (smooth != null) {
                    dxRad = smooth.filterX.filter(dxRad, smooth.rateHz)
                    dyRad = smooth.filterY.filter(dyRad, smooth.rateHz)
                }
                // G1: acumulador sub-pixel — emite a parte inteira e guarda a
                // fração (o .toInt() antigo descartava o sub-pixel: giro lento
                // podia nunca mover o cursor).
                val (dx, dy) = GyroPixelAccumulator.accumulate(
                    deltaXPx = dxRad * GYRO_PIXELS_PER_RADIAN * sensX,
                    deltaYPx = dyRad * GYRO_PIXELS_PER_RADIAN * sensY,
                    state = gyroMouseStates.getOrPut(deviceId) { GyroMouseState() },
                )
                if (dx != 0 || dy != 0) {
                    PluviaApp.xServerMouseSink.move(dx, dy)
                }
            }
            GyroMode.CAMERA -> {
                // P1-2: velocidade angular (rad/s) morta pela deadzone — o sink
                // mapeia em deflexão (controle de taxa, padrão DS4Windows).
                // F1.3 (spec 2026-08-15-input-core-avancado): com a fusão Mahony
                // opt-in, o PITCH vem da fusão (corrigido pela gravidade — o accel
                // do device já é coletado, P2-3); o YAW permanece o do
                // GyroProcessor (recenter + calibração contínua — honestidade
                // técnica: sem magnetômetro não há referência de yaw). Desligado,
                // o caminho é byte-identical (a fusão nem é chamada).
                val pitch = if (profile.gyroFusionEnabled == true) {
                    val fusionState = fusionStates.getOrPut(deviceId) { GyroFusionState() }
                    val fusion = GyroFusion.update(
                        gyroX = gyroX,
                        gyroY = gyroY,
                        gyroZ = gyroZ,
                        accelX = accelX,
                        accelY = accelY,
                        accelZ = accelZ,
                        nowMs = nowMs,
                        state = fusionState,
                        config = GyroFusionConfig(
                            kp = profile.gyroFusionKp ?: DEFAULT_FUSION_KP,
                            ki = profile.gyroFusionKi ?: 0f,
                        ),
                    )
                    fusion.pitchRadS
                } else {
                    output.pitchRadS
                }
                // G3/G4: contrato do sink = (yaw, pitch, sensX, sensY, maxOutput,
                // antiDeadzone) — a sensibilidade por eixo e o shaping (teto/floor
                // da deflexão, G4) seguem juntos. Único call site:
                // PhysicalControllerHandler.applyCameraGyro.
                gyroCameraSink?.invoke(
                    output.yawRadS, pitch, sensX, sensY,
                    profile.gyroStickMaxOutput ?: 1f,
                    profile.gyroStickAntiDeadzone ?: 0f,
                )
            }
            GyroMode.OFF -> {}
        }

        // Spec 2026-08-16-C §1.2: preview do card de diagnóstico — escrito NO FIM de
        // onSensorSample, APÓS o processamento existente (SEM alterá-lo): 1 write por
        // amostra quando ON, ZERO quando OFF (byte-identical). As velocidades vêm do
        // GyroProcessor (morta pela deadzone) — recenterar zera o readout.
        if (gyroPreviewEnabled) {
            _gyroPreview.value = GyroPreview(deviceId, output.yawRadS, output.pitchRadS, nowMs)
        }
    }

    /**
     * Spec 2026-08-16-C §1.2 ("Recentrar gyro"): reaplica a âncora de offset do
     * [GyroState] do device — a MESMA operação da borda de ativação, extraída em
     * [GyroProcessor.recenter]. Usa `state.lastSample` (a última amostra processada);
     * sem lastSample (gyro nunca ativado/inativo) = no-op. Não muda o pipeline: só
     * recalibra o offset observado pelo readout do card.
     */
    fun recenterGyro(deviceId: Int) {
        val state = gyroStates[deviceId] ?: return
        val sample = state.lastSample ?: return
        GyroProcessor.recenter(state, sample)
    }

    /**
     * G6 (spec 2026-08-16-G-gyro-v2): "Calibrar grip" do DeviceDiagnosticsCard —
     * θ = atan2 do accel da última amostra processada (`state.lastSample`, já
     * coletado P2-3) e salva no perfil do DEVICE (a pegada é propriedade física do
     * usuário + hardware, não do jogo — o override por-jogo continua vencendo no
     * merge). Sem lastSample (gyro nunca ativado) ou sem accel (0,0,0 —
     * harness/device sem accel) = no-op: nunca grava θ=0 por cima de um grip real.
     */
    fun calibrateGrip(deviceId: Int) {
        val device = deviceFor(deviceId) ?: return
        val state = gyroStates[deviceId] ?: return
        val sample = state.lastSample ?: return
        if (sample.accelX == 0f && sample.accelY == 0f && sample.accelZ == 0f) return
        val degrees = GyroProcessor.gripAngleFromAccel(sample.accelX, sample.accelZ)
        val profile = deviceStore.load(device.mappingKey) ?: GamepadProfile()
        deviceStore.save(device.mappingKey, profile.copy(gyroGripAngleDeg = degrees))
        invalidateProfiles()
    }

    /**
     * Sink do CAMERA mode — setado pelo XServerScreen quando o container roda (P1-1;
     * holder vivo, limpo no exit/onDispose). Contrato G3/G4 (spec
     * 2026-08-16-G-gyro-v2): (yawRadS, pitchRadS, sensX, sensY, maxOutput,
     * antiDeadzone) — VELOCIDADE angular (não delta integral) + sensibilidade por
     * eixo (já com inversão) + shaping da deflexão. Único call site:
     * PhysicalControllerHandler.applyCameraGyro (atualizado junto).
     */
    @Volatile
    var gyroCameraSink: ((
        yawRadS: Float,
        pitchRadS: Float,
        sensX: Float,
        sensY: Float,
        maxOutput: Float,
        antiDeadzone: Float,
    ) -> Unit)? = null

    /** Fonte de sensores (U1) — injetada pelo PluviaApp; hotplug avisa o source. */
    var sensorSource: GamepadSensorSource? = null

    private val gyroStates = mutableMapOf<Int, GyroState>()
    private val gyroActivateHeld = mutableMapOf<Int, String>()
    // G5 (spec 2026-08-16-G-gyro-v2): latch do toggle por device (V6 — morto no
    // removeDevice; deviceId efêmero nunca vaza estado).
    private val gyroActivateLatches = mutableMapOf<Int, Boolean>()
    // G1/G2: estado sub-pixel e OneEuro do MOUSE mode por device (idem V6). Só
    // existem com o modo ativo — OFF/outros modos ficam byte-identical.
    private val gyroMouseStates = mutableMapOf<Int, GyroMouseState>()

    /**
     * K2 (spec 2026-08-16-K2, §1.3): estado do modo mouse por device (V6 — morto
     * no removeDevice). Criado só quando o perfil habilita o modo; o toggle vive
     * no flush (routeMouseMode), o repeat do scroll nos repeats crus do onKey.
     */
    private val mouseModeStates = mutableMapOf<Int, MouseModeState>()

    /**
     * K2 (spec 2026-08-16-K2, §1.3): suspensão do modo mouse por overlay — o
     * XServerScreen escreve na composição (holder vivo, lição C1): com QuickMenu/
     * radial/remap abertos o dpad volta a navegar o menu e o modo fica suspenso
     * (o `active` persiste — ao fechar o overlay o modo volta).
     */
    @Volatile
    var overlayOpen: Boolean = false
    private val gyroSmoothStates = mutableMapOf<Int, GyroSmoothState>()

    // F1.2 (spec 2026-08-15-input-core-avancado): estado do Flick Stick por device
    // (V6 — morto no removeDevice; deviceId efêmero).
    private val flickStickStates = mutableMapOf<Int, FlickStickState>()

    // F1.3: estado da fusão Mahony por device (idem V6). Só existe com opt-in.
    private val fusionStates = mutableMapOf<Int, GyroFusionState>()

    /** F1.2: amostra do stick DIREITO com Flick Stick ativo → yaw rad/s (unidade U1). */
    fun flickStickProcess(deviceId: Int, x: Float, y: Float, nowMs: Long): Float {
        val profile = profileFor(deviceId, activeAppId)
        if (profile.flickStickEnabled != true) return 0f
        val state = flickStickStates.getOrPut(deviceId) { FlickStickState() }
        val config = FlickStickConfig(
            activationRadius = profile.flickStickActivationRadius ?: DEFAULT_FLICK_ACTIVATION_RADIUS,
            snapAngleDeg = profile.flickStickSnapAngle ?: DEFAULT_FLICK_SNAP_ANGLE,
        )
        return FlickStickProcessor.process(StickSample(x, y), nowMs, state, config).yawRadS
    }

    private fun gyroActivateHeld(deviceId: Int, profile: GamepadProfile): Boolean {
        val buttonName = profile.gyroActivateButton ?: return true // sempre ativo
        val toggle = profile.gyroActivateToggle == true
        // G5 (spec 2026-08-16-G-gyro-v2): toggle lê o latch; hold lê o botão
        // pressionado (decisão pura em GyroActivation — testada em JVM).
        return GyroActivation.active(
            held = gyroActivateHeld[deviceId] == buttonName,
            latch = gyroActivateLatches[deviceId] ?: false,
            toggle = toggle,
        )
    }

    /**
     * G2 (spec 2026-08-16-G-gyro-v2): estado do OneEuro do MOUSE mode — null = OFF
     * (ambos os campos do perfil null → sem alocação, caminho byte-identical).
     * rateHz = 1/dt da amostra (dt clampado 1..100 ms, o MESMO do GyroProcessor).
     * Parâmetros re-lidos por amostra (salvar perfil novo com smoothing ligado vale
     * sem reiniciar — padrão DS4Windows SetupLateOneEuroFilters).
     */
    private fun gyroSmoothFor(deviceId: Int, profile: GamepadProfile, nowMs: Long): GyroSmoothState? {
        val minCutoff = profile.gyroSmoothMinCutoff
        val beta = profile.gyroSmoothBeta
        if (minCutoff == null && beta == null) return null
        val state = gyroSmoothStates.getOrPut(deviceId) {
            GyroSmoothState(
                filterX = OneEuroFilter(),
                filterY = OneEuroFilter(),
                lastSampleMs = nowMs,
            )
        }
        state.filterX.minCutoff = minCutoff ?: OneEuroFilter.DEFAULT_MIN_CUTOFF
        state.filterX.beta = beta ?: OneEuroFilter.DEFAULT_BETA
        state.filterY.minCutoff = minCutoff ?: OneEuroFilter.DEFAULT_MIN_CUTOFF
        state.filterY.beta = beta ?: OneEuroFilter.DEFAULT_BETA
        state.rateHz = 1f / ((nowMs - state.lastSampleMs).coerceIn(1L, 100L) / 1000f)
        state.lastSampleMs = nowMs
        return state
    }

    /** Traduz um KeyEvent cru e emite GamepadInputEvent no bus (gate-aware). */
    fun onKey(raw: RawKeyInput): Boolean {
        if (!PrefManager.gamepadUniversalEnabled) return false
        val device = deviceFor(raw.deviceId) ?: return false
        // Só CONTROLLER (e o VIRTUAL do K1) emite lógico: TOUCHPAD continua sendo
        // gate do MainActivity (spec 2026-08-13-onda2 §1.2 — correção 3 da
        // validação). O virtual de toque entra no MESMO pipeline (K1 §1.1).
        if (device.deviceClass != DeviceClass.CONTROLLER && device.deviceClass != DeviceClass.VIRTUAL) return false
        val mapping = mappingFor(device)
        // K4 (spec 2026-08-16-K4, §1.3.2): alias de scanCode ANTES da tradução —
        // keyCode KEYCODE_UNKNOWN (device sem .kl) + scanCode na tabela do quirk
        // ativo → substitui o keycode. Guard curto-circuita por keyCode (um int
        // compare por evento; nada de lookup quando o keycode é conhecido — o
        // caminho atual fica byte-identical).
        val effectiveRaw = if (raw.keyCode == AndroidConstants.KEYCODE_UNKNOWN) {
            val alias = quirkCache[raw.deviceId]?.fixup?.scanCodeAliases
                ?.get(raw.scanCode)
            if (alias != null) raw.copy(keyCode = alias) else raw
        } else {
            raw
        }
        val events = EventTranslator.translateKey(effectiveRaw, mapping)
        if (events.isEmpty()) {
            // K2 (spec 2026-08-16-K2, §1.1): repeats CRUS do dpad — o tradutor
            // descarta repeats (só bordas), então este é o ÚNICO canal de
            // repetição do scroll do modo mouse (janela de 120 ms no processor).
            if (raw.repeatCount > 0 && !overlayOpen) {
                val profile = profileFor(raw.deviceId, activeAppId)
                if (profile.mouseModeEnabled == true) {
                    val state = mouseModeStates[raw.deviceId]
                    if (state != null && state.active) {
                        val outcome = MouseModeProcessor.onScrollRepeat(
                            state,
                            SystemClock.uptimeMillis(),
                        )
                        if (outcome is MouseModeOutcome.MouseScroll) {
                            PluviaApp.xServerMouseSink.scroll(outcome.steps)
                        }
                    }
                }
            }
            return false
        }
        val profile = profileFor(raw.deviceId, activeAppId)
        val activateButton = profile.gyroActivateButton
        val layerState = layerStates.getOrPut(raw.deviceId) { LayerState() }
        // J1 (spec 2026-08-16-J, §2.2): expressões do perfil efetivo avaliadas NO
        // MESMO flush de eventos — o evento atual é dobrado no estado lógico ANTES
        // da avaliação (sem lag de 1 evento para o próprio input).
        val exprFlush = flushExpressions(raw.deviceId, device, mapping, profile, layerState, events)
        // I (spec 2026-08-16-I, §2.3): relógio do engine + liberação de emits
        // retardados — os eventos de input SÃO o relógio (sem timer/coroutine).
        flushTriggerClock(raw.deviceId, device, mapping, profile, layerState)
        for (event in events) {
            // J2 §3: o botão FINAL de um chord armado não emite o binding simples
            // (o chord é o dono do evento — triggers de camada continuam físicos).
            val chordSuppressed = exprFlush != null && isChordSuppressed(exprFlush, event)
            // F §1.3: trigger SHIFT consome o evento físico (não emite lógico — o
            // botão não chega ao jogo); camada comum segue pass-through.
            if (!resolveLayerTriggers(device, mapping, layerState, profile, event) && !chordSuppressed) {
                // K2 §1.3: modo mouse DEPOIS do pipeline lógico, ANTES da emissão —
                // consumido (clique/scroll/cursor) não chega aos menus/overlays.
                if (!routeMouseMode(raw.deviceId, event)) {
                    emitLogical(device, mapping, profile, layerState, event, raw.deviceId)
                }
            }
        }
        return true
    }

    /**
     * K2 (spec 2026-08-16-K2, §1.3) — hook do modo mouse no flush (post-remap,
     * pré-emissão): consome A/B (cliques), dpad (scroll) e o stick esquerdo
     * (cursor) enquanto ativo; START arma (down) e flipa (up confirmado) o
     * toggle. Retorna true quando o evento foi consumido pelo modo (não emite).
     *
     * Suspenso com [overlayOpen] (o dpad navega o menu; `active` persiste) e
     * quando o perfil não habilita o modo (null = OFF — caminho byte-identical).
     * O stick direito/triggers passam (o modo usa SÓ o stick esquerdo — o
     * moonlight usa os dois, o spec K2 definiu o esquerdo; configuração é
     * follow-up).
     */
    private fun routeMouseMode(deviceId: Int, event: InputEvent): Boolean {
        val profile = profileFor(deviceId, activeAppId)
        if (profile.mouseModeEnabled != true) return false
        if (overlayOpen) return false
        val state = mouseModeStates.getOrPut(deviceId) { MouseModeState() }
        val nowMs = SystemClock.uptimeMillis()
        val toggleMs = profile.mouseModeToggleMs?.toLong()
            ?: MouseModeProcessor.DEFAULT_TOGGLE_MS

        fun handleButton(button: GamepadButton, isDown: Boolean): Boolean {
            when (val outcome = MouseModeProcessor.onKey(state, button, isDown, nowMs, toggleMs)) {
                MouseModeOutcome.None -> return false
                is MouseModeOutcome.Activated -> {
                    onMouseModeToggle(deviceId, active = true)
                    return false // START segue o pipeline (toggle + volta a ser START)
                }
                is MouseModeOutcome.Deactivated -> {
                    onMouseModeToggle(deviceId, active = false)
                    return false
                }
                is MouseModeOutcome.MouseButton -> {
                    when {
                        outcome.left && outcome.down -> PluviaApp.xServerMouseSink.pressLeft()
                        outcome.left -> PluviaApp.xServerMouseSink.releaseLeft()
                        else -> if (outcome.down) PluviaApp.xServerMouseSink.rightClick()
                    }
                    return true
                }
                is MouseModeOutcome.MouseScroll -> {
                    PluviaApp.xServerMouseSink.scroll(outcome.steps)
                    return true
                }
            }
        }

        return when (event) {
            is InputEvent.ButtonDown -> handleButton(event.button, isDown = true)
            is InputEvent.ButtonUp -> handleButton(event.button, isDown = false)
            is InputEvent.AxisMotion -> {
                if (event.axis != GamepadAxis.LEFT_X && event.axis != GamepadAxis.LEFT_Y) {
                    return false
                }
                if (event.axis == GamepadAxis.LEFT_X) state.lastStickX = event.value
                if (event.axis == GamepadAxis.LEFT_Y) state.lastStickY = event.value
                val speed = MouseModeSpeed(
                    basePps = profile.mouseModeBasePps ?: 0f,
                    gainPps = profile.mouseModeGainPps ?: 80f,
                )
                val move = MouseModeProcessor.onStick(
                    state, state.lastStickX, state.lastStickY, nowMs, speed,
                )
                if (move != null) PluviaApp.xServerMouseSink.move(move.dx, move.dy)
                true // stick esquerdo consumido enquanto o modo está ativo
            }
            else -> false
        }
    }

    /** K2 §1.3: feedback de toggle — haptic curto + log (padrão moonlight "OSD toast"). */
    private fun onMouseModeToggle(deviceId: Int, active: Boolean) {
        GamepadHaptics.rumbleDevice(deviceId, 0.4f, 0.4f, 80L)
        Timber.d(
            "gncontrol: modo mouse %s (device %d)",
            if (active) "ATIVADO" else "desativado", deviceId,
        )
    }

    /** K2 §1.3: modo mouse ativo para o device — o PhysicalControllerHandler usa
     *  para CONSUMIR A/B/dpad crus (não chegam ao jogo). */
    fun mouseModeActive(deviceId: Int): Boolean =
        mouseModeStates[deviceId]?.active == true && !overlayOpen

    /** Traduz um MotionEvent cru e emite GamepadInputEvent no bus (gate-aware). */
    fun onAxis(raw: RawAxisInput): Boolean {
        if (!PrefManager.gamepadUniversalEnabled) return false
        val device = deviceFor(raw.deviceId) ?: return false
        // K1: o VIRTUAL de toque entra no MESMO pipeline (só CONTROLLER + VIRTUAL).
        if (device.deviceClass != DeviceClass.CONTROLLER && device.deviceClass != DeviceClass.VIRTUAL) return false
        val mapping = mappingFor(device)
        val profile = profileFor(raw.deviceId, activeAppId)
        val layerState = layerStates.getOrPut(raw.deviceId) { LayerState() }
        // Trigger não tem key global no PrefManager (spec Passo 1: só stick) — usa o
        // default do DeadzoneConfig cacheado no hub quando o perfil não override (L6:
        // sem alocação do default por MotionEvent).
        val deadzones = DeadzoneConfig(
            leftStick = profile.leftStickDeadzone ?: PrefManager.gamepadStickDeadzone,
            rightStick = profile.rightStickDeadzone ?: PrefManager.gamepadStickDeadzone,
            leftTrigger = profile.leftTriggerDeadzone ?: defaultDeadzones.leftTrigger,
            rightTrigger = profile.rightTriggerDeadzone ?: defaultDeadzones.rightTrigger,
            // F1.1 (spec 2026-08-15-input-core-avancado): o modo (radial/axial) do
            // perfil vale para o caminho LÓGICO também (navegação de menu usa o stick
            // esquerdo — o modo do LEFT é o que conta). null = RADIAL (atual).
            mode = profile.leftStickDeadzoneMode ?: DeadzoneMode.RADIAL,
        )
        // H (spec 2026-08-16-H-binding-modifiers-duckstation, §2.3): modificadores POR
        // BINDING — o hub resolve o mod do token de camada do binding efetivo (mapping
        // .axes + override via bindings efetivos) e aplica DEPOIS do processamento
        // existente do eixo (o override por-binding VENCE o global por ser o último).
        // FullAxis converte o domínio do valor CRU ANTES da tradução (a deadzone por
        // binding vira o limiar da conversão eixo→botão). Sem layers/tokens → caminho
        // byte-identical (nenhuma alocação extra).
        val effectiveBindings = if (profile.layers.isEmpty()) {
            emptyMap()
        } else {
            LayerResolver.effectiveBindings(profile.layers, layerState.activeLayer)
        }
        val bindingMods = bindingModsFor(effectiveBindings, mapping)
        val preRaw = preApplyFullAxis(raw, mapping, effectiveBindings)
        var events = EventTranslator.translateAxis(preRaw, mapping, deadzones)
        if (effectiveBindings.isNotEmpty()) {
            val buttonDeadzones = buttonDeadzonesFor(effectiveBindings, mapping)
            if (buttonDeadzones.isNotEmpty()) {
                events = events.map { applyButtonDeadzone(it, preRaw, mapping, buttonDeadzones) }
            }
            if (bindingMods.isNotEmpty()) {
                events = events.mapNotNull { applyAxisMods(it, bindingMods) }
            }
        }
        if (events.isEmpty()) return false

        // O tradutor descreve o ESTADO da amostra (hat/meia-eixo); o hub vira transição.
        val state = buttonStates.getOrPut(raw.deviceId) { mutableSetOf() }
        // J1 §2.2: expressões no mesmo flush (evento atual dobrado antes da avaliação).
        val exprFlush = flushExpressions(raw.deviceId, device, mapping, profile, layerState, events)
        // I §2.3: relógio do engine + liberação de emits retardados (eventos = relógio).
        flushTriggerClock(raw.deviceId, device, mapping, profile, layerState)
        var emitted = false
        for (event in events) {
            val forward = when (event) {
                is InputEvent.ButtonDown -> state.add(event.button)
                is InputEvent.ButtonUp -> state.remove(event.button)
                is InputEvent.AxisMotion -> true
                else -> false
            }
            if (forward) {
                // J2 §3: supressão do binding simples do botão final de chord armado.
                val chordSuppressed = exprFlush != null && isChordSuppressed(exprFlush, event)
                // U3: triggers de camada (botão FÍSICO) + remap pela camada ativa.
                // F §1.3: trigger SHIFT consome o evento físico.
                if (!resolveLayerTriggers(device, mapping, layerState, profile, event) && !chordSuppressed) {
                    // K2 §1.3: modo mouse no flush (post-pipeline, pré-emissão) —
                    // o stick esquerdo ativo vira cursor e não chega aos menus.
                    if (!routeMouseMode(raw.deviceId, event)) {
                        emitted = emitLogical(device, mapping, profile, layerState, event, raw.deviceId) || emitted
                    }
                }
            }
        }
        return emitted
    }

    /**
     * H (spec 2026-08-16-H-binding-modifiers-duckstation, §2.3): modificador POR
     * BINDING de cada eixo semântico — o binding efetivo é `mapping.axes[axis]`
     * (eixo físico) + o override de camada (token do botão porta-token). O mod vale
     * quando o binding do token referencia o MESMO eixo físico que produz o valor;
     * sem token/mod → mapa vazio (caminho byte-identical).
     */
    private fun bindingModsFor(
        bindings: Map<String, String>,
        mapping: GamepadMapping,
    ): Map<GamepadAxis, BindingModifier> {
        var result: MutableMap<GamepadAxis, BindingModifier>? = null
        for ((axis, button) in AXIS_BUTTON_PAIRS) {
            val bound = (mapping.axes[axis] as? RawBinding.Axis)?.axis ?: continue
            val token = bindings[button.name] ?: continue
            val decoded = GamepadBindingCodec.decode(token) ?: continue
            val mod = decoded.mod ?: continue
            val rawBinding = decoded.raw
            if (rawBinding !is RawBinding.Axis || rawBinding.axis != bound) continue
            if (result == null) result = mutableMapOf()
            result[axis] = mod
        }
        return result ?: emptyMap()
    }

    /**
     * H §2.3: FullAxis ANTES do pipeline — o eixo CENTRADO −1..1 vira 0..1
     * (`v * 0.5 + 0.5`) antes do resto da tradução, SOMENTE quando o binding Axis do
     * token alimenta um trigger (eixo semântico L/R_TRIGGER ou a meia-eixo do botão
     * trigger — SDL half-axis a4/a5). Sem mod → retorna [raw] intacto (zero alocação).
     */
    private fun preApplyFullAxis(
        raw: RawAxisInput,
        mapping: GamepadMapping,
        bindings: Map<String, String>,
    ): RawAxisInput {
        var values: MutableMap<Int, Float>? = null
        for ((axis, button) in TRIGGER_PAIRS) {
            val token = bindings[button.name] ?: continue
            val decoded = GamepadBindingCodec.decode(token) ?: continue
            val tokenRaw = decoded.raw
            if (tokenRaw !is RawBinding.Axis) continue
            val mod = decoded.mod ?: continue
            if (mod.fullAxis != true) continue
            val axisBound = (mapping.axes[axis] as? RawBinding.Axis)?.axis
            val buttonBound = (mapping.buttons[button] as? RawBinding.Axis)?.axis
            val target = listOfNotNull(axisBound, buttonBound)
                .firstOrNull { it == tokenRaw.axis } ?: continue
            val original = raw.axisValues[target] ?: continue
            val converted = original * 0.5f + 0.5f
            if (converted != original) {
                if (values == null) values = raw.axisValues.toMutableMap()
                values[target] = converted
            }
        }
        return if (values == null) raw else raw.copy(axisValues = values)
    }

    /**
     * H §2.3: deadzone POR BINDING de botões dirigidos por meia-eixo (trigger como
     * botão) — o limiar de conversão eixo→botão do EventTranslator passa a ser o dz
     * do binding quando presente (hair trigger; sem mod o limiar 0.5 atual permanece).
     */
    private fun buttonDeadzonesFor(
        bindings: Map<String, String>,
        mapping: GamepadMapping,
    ): Map<GamepadButton, Float> {
        var result: MutableMap<GamepadButton, Float>? = null
        for ((button, rawBinding) in mapping.buttons) {
            if (rawBinding !is RawBinding.Axis) continue
            val token = bindings[button.name] ?: continue
            val decoded = GamepadBindingCodec.decode(token) ?: continue
            val mod = decoded.mod ?: continue
            val tokenRaw = decoded.raw
            if (tokenRaw !is RawBinding.Axis || tokenRaw.axis != rawBinding.axis) continue
            val dz = (mod.deadzone ?: 0f).coerceIn(BindingModifiers.DEADZONE_MIN, BindingModifiers.DEADZONE_MAX)
            if (dz <= 0f) continue
            if (result == null) result = mutableMapOf()
            result[button] = dz
        }
        return result ?: emptyMap()
    }

    /** H §2.3: re-deriva o estado ativo do botão de meia-eixo com o limiar do binding. */
    private fun applyButtonDeadzone(
        event: InputEvent,
        raw: RawAxisInput,
        mapping: GamepadMapping,
        deadzones: Map<GamepadButton, Float>,
    ): InputEvent {
        val button: GamepadButton
        val deviceId: Int
        val isDown: Boolean
        when (event) {
            is InputEvent.ButtonDown -> {
                button = event.button
                deviceId = event.deviceId
                isDown = true
            }
            is InputEvent.ButtonUp -> {
                button = event.button
                deviceId = event.deviceId
                isDown = false
            }
            else -> return event
        }
        val dz = deadzones[button] ?: return event
        val binding = mapping.buttons[button] as? RawBinding.Axis ?: return event
        val value = raw.axisValues[binding.axis] ?: return event
        val active = value * binding.direction >= dz
        return if (active) {
            if (isDown) event else InputEvent.ButtonDown(deviceId, button)
        } else {
            if (!isDown) event else InputEvent.ButtonUp(deviceId, button)
        }
    }

    /**
     * H §2.3: aplica o mod do binding no AxisMotion DEPOIS do processamento existente
     * (o override vence o global por ser o último). O fullAxis já foi pré-aplicado no
     * valor CRU (domínio antes do pipeline); aqui roda o resto da cadeia — invert →
     * scale → deadzone — na ordem documentada de [BindingModifiers.apply].
     */
    private fun applyAxisMods(event: InputEvent, mods: Map<GamepadAxis, BindingModifier>): InputEvent? {
        if (event !is InputEvent.AxisMotion) return event
        val mod = mods[event.axis] ?: return event
        val value = BindingModifiers.apply(event.value, mod.copy(fullAxis = null))
        return if (value == 0f) null else event.copy(value = value)
    }

    /** Instrumentação Onda 2 (spec §1.9): par do GamepadTrace cru. */
    private fun logLogical(device: GamepadDevice, event: InputEvent) {
        Timber.d(
            "GamepadLogical: dev=%s (%04x%04x) %s",
            device.name, device.vendorId, device.productId, event,
        )
    }

    /**
     * K3 (spec 2026-08-16-K3, §1.5): cache do mapping EFETIVO por deviceId (par
     * mapping + origem). O resultado é determinístico por device (vid/pid + [GamepadCapabilities]
     * imutáveis no [GamepadDevice] + autoconfig USER) e o hot path (~120 Hz por
     * stick + hats) NÃO aloca mapping por evento (a síntese de capabilities custa
     * mapas novos a cada chamada). Acesso só na main thread (dispatch + hotplug —
     * contrato M1 do profileCache); invalidado em addDevice/removeDevice/stop
     * (deviceId é efêmero) e em save/delete de autoconfig (K5 — o tier USER muda o
     * resultado SEM o device trocar).
     */
    private val mappingCache = mutableMapOf<Int, Pair<GamepadMapping, MappingSource>>()

    /**
     * K4 (spec 2026-08-16-K4, §1.3): quirk resolvido UMA vez por hotplug/addDevice
     * (nunca por evento) — par (entry, aliases) do device ativo. A entry carrega a
     * identidade de diagnóstico (nome/origem) e o fixup; os aliases de scanCode
     * alimentam o caminho de KeyEvent. Cache morre junto com o mappingCache
     * (deviceId efêmero, padrão K3).
     */
    private val quirkCache = mutableMapOf<Int, DeviceQuirk?>()

    /**
     * K5 (spec 2026-08-16-K5, §1.3.1): mapping BASE (pré-quirk) capturado no
     * addDevice — exatamente o que o tier vencedor produziu ANTES do quirk; é o
     * que "Salvar perfil deste controle" grava, nunca re-derivado no clique (como o
     * RetroArch grava o estado da CONEXÃO, não o momento do clique). Capturado
     * DENTRO do [mappingCache] (mesmo ciclo de vida: main thread, deviceId efêmero,
     * morto em removeDevice/stop/autoconfig).
     */
    private val baseMappingCache = mutableMapOf<Int, GamepadMapping>()

    /**
     * Cadeia de prioridade do mapping (spec 2026-08-16-K3, §1.5): USER (K5 — o
     * autoconfig salvo do [DeviceMappingStore], spec 2026-08-16-K5 §1.2) > MODEL
     * (MappingDatabase) > SDL_DB (gamecontrollerdb) > CAPABILITIES (síntese) >
     * DEFAULT (estático). A ORDEM da cadeia É a prioridade — regra de escalonamento
     * do SDL (SDL_gamepad.c:2214-2221, um tier só é sobrescrito por prioridade ≥).
     * A origem volta junto para UI/log ([MappingSource]).
     *
     * K4 (spec 2026-08-16-K4, §1.3.1): o quirk do device (se houver) aplica DEPOIS,
     * como pós-processamento do mapping escolhido — [DeviceQuirks.apply] devolve o
     * MESMO objeto quando o fixup é nulo/vazio (degradação zero, sem alocação por
     * evento; o cache por deviceId já amortiza a cópia única do hotplug).
     *
     * K5 (spec 2026-08-16-K5, §1.2): a ORDEM quirk-DEPOIS é invariante — o
     * autoconfig salvo captura o mapping PRÉ-quirk ([baseMappingCache]): quirk é
     * correção de TRANSPORTE, não preferência do usuário; firmware novo com quirk
     * novo continua sendo corrigido por cima do USER (e um quirk removido deixa de
     * aplicar sem re-salvar nada).
     */
    private fun resolveMapping(device: GamepadDevice): Pair<GamepadMapping, MappingSource> =
        mappingCache.getOrPut(device.deviceId) {
            val base = resolveBaseMapping(device)
            // K5 §1.3.1: captura do base PRÉ-quirk no addDevice — o save grava ESTE
            // estado (nunca o pós-quirk).
            baseMappingCache[device.deviceId] = base.first
            DeviceQuirks.apply(base.first, quirkCache[device.deviceId]?.fixup) to base.second
        }

    /**
     * Cadeia BASE (pré-quirk) — extraída de [resolveMapping] para o save do
     * autoconfig (K5) capturar exatamente o que o tier vencedor produziu, sem
     * quirk. USER primeiro: [DeviceMappingStore.load] devolve null quando não há
     * autoconfig salvo (cache do store — sem custo no hot path).
     */
    private fun resolveBaseMapping(device: GamepadDevice): Pair<GamepadMapping, MappingSource> =
        autoconfigStore.load(device.mappingKey)?.mapping
            ?.let { it to MappingSource.USER }
            // F1.4 (spec 2026-08-15-input-core-avancado): fallback do DB pinado do
            // SDL_GameControllerDB (entradas Android). Load do asset UMA vez,
            // preguiçoso (o hotplug do device desconhecido esquenta o cache).
            ?: MappingDatabase.mappingFor(device.vendorId, device.productId)
                ?.let { it to MappingSource.MODEL }
            ?: sdlDb()[device.mappingKey]?.let { it to MappingSource.SDL_DB }
            ?: device.capabilities?.let { capabilities ->
                CapabilityMapping.synthesize(capabilities, device.faceStyle)?.let {
                    it to MappingSource.CAPABILITIES
                }
            }
            ?: (MappingDatabase.defaultAndroidMapping(device.faceStyle) to MappingSource.DEFAULT)

    private fun mappingFor(device: GamepadDevice): GamepadMapping = resolveMapping(device).first

    /** Mapa `"vvvvpppp" → GamepadMapping` do asset pinado (lazy; cacheado). */
    @Volatile
    private var sdlDbCache: Map<String, GamepadMapping>? = null

    private fun sdlDb(): Map<String, GamepadMapping> {
        sdlDbCache?.let { return it }
        val parsed = runCatching {
            appContext.assets.open("gamecontrollerdb.txt").use { input ->
                SdlControllerDb.parse(input.readBytes().toString(Charsets.UTF_8))
            }
        }.getOrElse { emptyMap() }
        sdlDbCache = parsed
        return parsed
    }

    /**
     * P3-4 (spec 2026-08-14-gamepad-upgrades-pendencias): refresh PULL da bateria de
     * um device — chamado ao ABRIR a seção Gamepad dos settings (fora do hot path,
     * sem polling; mesmo padrão da coleta no hotplug). O nível coletado no addDevice
     * ficava stale durante uma partida longa.
     */
    fun refreshBattery(deviceId: Int) {
        val device = _connectedDevices.value[deviceId] ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val fresh = runCatching {
            val inputDevice = InputDevice.getDevice(deviceId) ?: return@runCatching null
            val state = inputDevice.batteryState
            if (state.isPresent && state.capacity >= 0f) {
                when (state.status) {
                    BatteryState.STATUS_CHARGING, BatteryState.STATUS_FULL -> 100
                    else -> (state.capacity * 100f).toInt()
                }
            } else {
                null
            }
        }.getOrNull() ?: return
        if (fresh == device.batteryPercent) return
        _connectedDevices.value = _connectedDevices.value + (deviceId to device.copy(batteryPercent = fresh))
    }

    private fun addDevice(deviceId: Int) {
        val inputDevice = InputDevice.getDevice(deviceId) ?: return
        val deviceClass = DeviceClassifier.classify(deviceFeatures(inputDevice))
        if (deviceClass == DeviceClass.UNKNOWN || deviceClass == DeviceClass.SENSOR) return
        val faceStyle = MappingDatabase.mappingFor(inputDevice.vendorId, inputDevice.productId)
            ?.faceStyle ?: FaceStyle.GENERIC
        // K3 (spec 2026-08-16-K3, §1.1): capacidades coletadas no hotplug — UMA
        // chamada binder (InputDevice.hasKeys) + 1 passada nos motionRanges, fora do
        // hot path (mesmo padrão V11 do hasGyro/hasTouchpad).
        val hasKeys = inputDevice.hasKeys(*AndroidConstants.ALL_CANDIDATE_KEYCODES)
        val keycodes = buildSet {
            AndroidConstants.ALL_CANDIDATE_KEYCODES.forEachIndexed { index, keyCode ->
                if (hasKeys[index]) add(keyCode)
            }
        }
        val joystickAxes = inputDevice.motionRanges
            .filter { it.source == InputDevice.SOURCE_JOYSTICK }
            .map { it.axis }
            .distinct()
            .sorted()
        val capabilities = GamepadCapabilities(
            keycodes = keycodes,
            axes = joystickAxes,
            hasHat = AndroidConstants.AXIS_HAT_X in joystickAxes &&
                AndroidConstants.AXIS_HAT_Y in joystickAxes,
            isGamepadSource = (inputDevice.sources and InputDevice.SOURCE_GAMEPAD) != 0,
        )
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
            capabilities = capabilities,
        )
        // K3: cache do mapping é por deviceId EFÊMERO — entrada velha nunca vaza.
        mappingCache.remove(deviceId)
        // K4 (spec 2026-08-16-K4, §1.3): quirk resolvido UMA vez no hotplug (fora
        // do hot path) — vid/pid/nome/transporte + gate de capabilities (K3). O
        // mapping efetivo (com fixup) e os aliases de scanCode saem daqui.
        val quirk = DeviceQuirks.resolve(
            vendorId = inputDevice.vendorId,
            productId = inputDevice.productId,
            name = inputDevice.name,
            isBt = isBluetoothInput(inputDevice),
            caps = capabilities,
        )
        quirkCache[deviceId] = quirk
        val (_, mappingSource) = resolveMapping(device)
        val stored = device.copy(mappingSource = mappingSource, quirkName = quirk?.name)
        _connectedDevices.value = _connectedDevices.value + (deviceId to stored)
        refreshActive()
        invalidateProfiles()
        sensorSource?.onDeviceAdded(deviceId)
        PluviaApp.events.emit(GamepadDeviceAddedEvent(stored))
        Timber.d(
            "GamepadHub: added id=%d name=%s vendor=%04x product=%04x class=%s shape=%s mapping=%s battery=%s gyro=%b touchpad=%b",
            deviceId, stored.name, stored.vendorId, stored.productId, deviceClass,
            CapabilityMapping.classify(capabilities), mappingSource,
            stored.batteryPercent?.toString() ?: "-", stored.hasGyro, stored.hasTouchpad,
        )
        // K4 §1.4: log ÚNICO no addDevice quando há quirk ativo (nada por evento).
        if (quirk != null) {
            Timber.d("gncontrol: quirk %s aplicado (%s)", quirk.name, quirk.source)
        }
    }

    private fun removeDevice(deviceId: Int) {
        buttonStates.remove(deviceId)
        // K3: cache do mapping morre junto (deviceId efêmero pode ser reusado).
        mappingCache.remove(deviceId)
        // K4: o quirk do device morre junto (mesma regra do mappingCache).
        quirkCache.remove(deviceId)
        // K5: o base capturado (pré-quirk) morre junto (mesma regra).
        baseMappingCache.remove(deviceId)
        // U2 (V6): estado do touchpad→mouse morre junto com o device (mesmo padrão
        // buttonStates — o deviceId efêmero pode ser reusado por outro hardware).
        PluviaApp.gamepadTouchpad.onDeviceRemoved(deviceId)
        // U1 (V6): estado do gyro morre junto; listener de sensor desregistrado (V3).
        gyroStates.remove(deviceId)
        gyroActivateHeld.remove(deviceId)
        // G1/G2/G5 (V6): estados do gyro v2 morrem junto.
        gyroMouseStates.remove(deviceId)
        gyroSmoothStates.remove(deviceId)
        gyroActivateLatches.remove(deviceId)
        // K2 (V6): estado do modo mouse morre junto (deviceId efêmero reusado).
        mouseModeStates.remove(deviceId)
        // F1.2/F1.3 (V6): estados do Flick Stick e da fusão morrem junto.
        flickStickStates.remove(deviceId)
        fusionStates.remove(deviceId)
        // U3 (V6): camadas ativas morrem junto (deviceId efêmero).
        layerStates.remove(deviceId)
        // I (V6): estado do engine e fila de emits retardados morrem junto.
        triggerEngineStates.remove(deviceId)
        pendingEmits.remove(deviceId)
        // J1 (V6): estados das expressões morrem junto (deviceId efêmero).
        exprStates.remove(deviceId)
        exprBindingCache.remove(deviceId)
        exprLastEvalMs.remove(deviceId)
        logicalInputState.remove(deviceId)
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

    /**
     * K1 (spec 2026-08-16-K1, §1.1) — registro do device VIRTUAL de toque: não há
     * InputDevice real (o TouchGamepadSource chama ANTES do primeiro evento, lazy —
     * "dispositivo fantasma na UI de settings é ruído", spec §1.1). Mesmo contrato
     * do addDevice físico: caches invalidados, mapping resolvido pela cadeia
     * (entry `00000000` do MODEL), mappingSource no StateFlow e
     * [GamepadDeviceAddedEvent] emitido (o card de diagnóstico o enxerga).
     */
    fun registerVirtualDevice(device: GamepadDevice) {
        val deviceId = device.deviceId
        if (_connectedDevices.value.containsKey(deviceId)) return // idempotente
        mappingCache.remove(deviceId)
        baseMappingCache.remove(deviceId)
        quirkCache[deviceId] = null
        val (_, mappingSource) = resolveMapping(device)
        val stored = device.copy(mappingSource = mappingSource)
        _connectedDevices.value = _connectedDevices.value + (deviceId to stored)
        refreshActive()
        invalidateProfiles()
        sensorSource?.onDeviceAdded(deviceId)
        PluviaApp.events.emit(GamepadDeviceAddedEvent(stored))
        Timber.d("GamepadHub: virtual touch gamepad registrado (device %d)", deviceId)
    }

    /** K1 §1.1: remoção do device virtual (overlay destruído — cleanup do handler). */
    fun unregisterVirtualDevice(deviceId: Int) {
        if (!_connectedDevices.value.containsKey(deviceId)) return
        removeDevice(deviceId)
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

    /**
     * K4 (spec 2026-08-16-K4, §1.1): heurística de transporte Bluetooth — o Android
     * NÃO expõe API pública para isso. Sinais acumulados, conservadores (sem sinal
     * → false → quirks gateados por BT não ativam — degradação ao caminho atual):
     * 1. nome com "wireless"/"bluetooth" (padrão dos pads HID de BT — o moonlight
     *    também usa o nome: "Xbox Wireless Controller", ControllerHandler.java:985);
     * 2. descriptor com "bluetooth" (prefixo de MAC nos builds modernos do
     *    EventHub — sem custo se o build não produzir);
     * 3. vendors conhecidos BT-only (0x057e Nintendo — Switch Pro/Joy-Con).
     */
    private fun isBluetoothInput(device: InputDevice): Boolean {
        val name = device.name.lowercase()
        if (name.contains("wireless") || name.contains("bluetooth")) return true
        if (device.descriptor.lowercase().contains("bluetooth")) return true
        return device.vendorId == 0x057e
    }
}

/**
 * G2 (spec 2026-08-16-G-gyro-v2): par de filtros OneEuro + timestamp da última
 * amostra — estado do smoothing do MOUSE mode por device (V6, morto no
 * removeDevice). O rate é calculado no hub (mesmo dt clampado do GyroProcessor).
 */
private class GyroSmoothState(
    val filterX: OneEuroFilter,
    val filterY: OneEuroFilter,
    var lastSampleMs: Long,
) {
    var rateHz: Float = 1f
}

/**
 * J2 (spec 2026-08-16-J, §3): resultado do flush de expressões — botões segurados
 * (> 0.5) e o registro de chords do perfil efetivo (supressão do binding simples
 * do botão final no caminho físico).
 */
private class ExprFlush(
    val held: Set<String>,
    val chords: List<ChordLogic.Chord>,
)

/**
 * I (spec 2026-08-16-I-trigger-engine-keymapper, §2.3): fila de eventos lógicos de
 * UM botão retardado (disambiguação #1386) — o Down do 1º botão de uma sequência
 * pendente (e o Up, se o usuário soltar enquanto a decisão vive) aguardam o prazo;
 * resolução libera (emite na ordem) ou descarta (consumido). Estado por device
 * (V6 — morto no removeDevice).
 */
private class PendingEmit(
    val events: MutableList<InputEvent>,
    var deadlineMs: Long,
)

/**
 * Spec 2026-08-16-C §1.2: amostra do preview de gyro para o card de diagnóstico
 * (DeviceDiagnosticsCard) — última amostra processada pelo [GyroProcessor] do device.
 * O card filtra por [deviceId] (o StateFlow do hub guarda só a última amostra global).
 */
data class GyroPreview(
    val deviceId: Int,
    /** Velocidade angular de yaw morta pela deadzone, rad/s. */
    val yawRadS: Float,
    /** Velocidade angular de pitch morta pela deadzone, rad/s. */
    val pitchRadS: Float,
    /** Timestamp da amostra (ms — relógio do evento de sensor, P2-1). */
    val timestampMs: Long,
)
