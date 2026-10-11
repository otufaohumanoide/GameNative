package app.gamenative.ui.screen.xserver

import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.events.GamepadDeviceRemovedEvent
import app.gamenative.events.GamepadInputEvent
import app.gamenative.gamepad.DeviceClass
import app.gamenative.gamepad.GamepadButton
import app.gamenative.gamepad.InputEvent
import app.gamenative.gamepad.mapping.MappingDatabase
import app.gamenative.gamepad.mapping.RawBinding
import app.gamenative.gamepad.processing.DeadzoneConfig
import app.gamenative.gamepad.processing.DeadzoneMode
import app.gamenative.gamepad.processing.DeadzoneProcessor
import app.gamenative.gamepad.processing.GyroStickMapping
import app.gamenative.gamepad.processing.LatencyTracker
import app.gamenative.gamepad.processing.ResponseCurve
import app.gamenative.gamepad.processing.StickSample
import app.gamenative.gamepad.processing.StickTransform
import app.gamenative.gamepad.processing.StickTransformConfig
import app.gamenative.gamepad.processing.TurboScheduler
import app.gamenative.gamepad.remap.GamepadBindingCodec
import app.gamenative.gamepad.remap.raw
import app.gamenative.gamepad.remap.turbo
import app.gamenative.gamepad.virtual.TouchGamepadBridge
import app.gamenative.gamepad.virtual.TouchGamepadConstants
import app.gamenative.gamepad.virtual.TouchGamepadSource
import com.winlator.inputcontrols.Binding
import com.winlator.inputcontrols.BindingCombo
import com.winlator.inputcontrols.ControlElement
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.ExternalController
import com.winlator.inputcontrols.ExternalControllerBinding
import com.winlator.inputcontrols.GamepadState
import com.winlator.inputcontrols.JoyConSupport
import com.winlator.math.Mathf
import com.winlator.xserver.XServer
import java.util.Timer
import java.util.TimerTask

/**
 * Standalone handler for physical controller input that works independently of view visibility.
 * Applies profile bindings to convert physical controller input into virtual gamepad state.
 */
class PhysicalControllerHandler(
    private var profile: ControlsProfile?,
    private val xServer: XServer?,
    private val onOpenNavigationMenu: (() -> Unit)? = null,
    private val onShowKeyboard: (() -> Unit)? = null,
    private val onRadialMenuButtonStateChanged: ((Boolean, Boolean) -> Unit)? = null,
    private val onRadialMenuVectorChanged: ((Float, Float) -> Unit)? = null,
    private val onGyroModifierChanged: ((Any, Boolean) -> Unit)? = null,
    private val gyroStickMixer: ((Binding, Boolean, Float, Int) -> Float)? = null,
    private val gamepadStateSender: (GamepadState?) -> Unit = { state ->
        xServer?.winHandler?.let { winHandler ->
            winHandler.sendGamepadState()
            winHandler.sendVirtualGamepadState(state)
        }
    },
) {
    private data class PhysicalInputSource(val deviceId: Int, val keyCode: Int)

    private data class MouseMoveSource(
        val deviceId: Int,
        val keyCode: Int,
        val binding: Binding,
    )

    companion object {
        private const val SCROLL_REPEAT_INTERVAL_MS = 90L
        private const val UNKNOWN_DEVICE_ID = -1
        private const val SEQUENCE_PRESS_MS = 80L
    }

    private val TAG = "gncontrol"
    private val mouseMoveOffset = PointF(0f, 0f)
    private val mouseMoveRemainder = PointF(0f, 0f)
    private val mouseMoveContributions = mutableMapOf<MouseMoveSource, Float>()
    private val mouseMoveLock = Any()
    private val sequenceHandler = Handler(Looper.getMainLooper())
    private var mouseMoveTimer: Timer? = null
    private var scrollRepeatTimer: Timer? = null
    private val scrollRepeatLock = Any()
    private val activeScrollBindings = mutableSetOf<Binding>()
    // track which axis keycodes are currently "pressed" so we only release on actual transitions.
    // accessed only from main thread (MotionEvent dispatch + Compose lifecycle), no sync needed.
    private val activeAxisBindings = mutableSetOf<PhysicalInputSource>()
    private val activeButtonBindings = mutableMapOf<PhysicalInputSource, BindingCombo>()
    private val activeTriggerBindings = mutableMapOf<PhysicalInputSource, BindingCombo>()
    private val activeSequenceTriggerBindings = mutableSetOf<PhysicalInputSource>()
    private val activeSequenceBindings = mutableMapOf<Binding, Int>()
    private val activeSequenceGyroSources = mutableSetOf<PhysicalInputSource>()
    private val activeGyroModifierSources = mutableSetOf<PhysicalInputSource>()

    /** Bindings-alvo atualmente segurados de eixos remapeados (U4) — por eixo cru. */
    private val remappedAxisBindings = mutableMapOf<Int, Binding>()

    /**
     * F (spec 2026-08-16-F-radial-v2-modeshift-turbo, §1.4): estado de turbo por
     * device — fonte = GamepadButton.name LÓGICO. V6: morto no removeDevice.
     */
    private class TurboToggleState(
        var phase: Int,
        val target: Binding,
        var pending: Runnable?,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val turboStates = mutableMapOf<Int, MutableMap<String, TurboToggleState>>()

    private val deviceRemovedListener: (GamepadDeviceRemovedEvent) -> Unit = { event ->
        turboStates.remove(event.deviceId)?.values?.forEach { entry ->
            entry.pending?.let { mainHandler.removeCallbacks(it) }
        }
    }

    /**
     * K1 (spec 2026-08-16-K1, §1.3): o device VIRTUAL de toque injeta os eventos
     * LÓGICOS do pipeline no jogo pelo MESMO caminho U4 (handleInputEvent +
     * sendGamepadState). Só o deviceId virtual entra.
     */
    private val virtualGamepadListener: (GamepadInputEvent) -> Boolean = { event ->
        if (event.input.deviceId != TouchGamepadConstants.DEVICE_ID) {
            false
        } else {
            injectVirtualEvent(event.input)
            true
        }
    }

    init {
        PluviaApp.events.on<GamepadDeviceRemovedEvent, Unit>(deviceRemovedListener)
        PluviaApp.events.on<GamepadInputEvent, Boolean>(virtualGamepadListener)
    }

    /** K1 §1.3: evento lógico do device virtual → Binding do overlay → injeção U4. */
    private fun injectVirtualEvent(input: InputEvent) {
        when (input) {
            is InputEvent.ButtonDown -> {
                val binding = TouchGamepadBridge.bindingFor(input.button) ?: return
                injectBinding(binding, true, 1f)
            }
            is InputEvent.ButtonUp -> {
                val binding = TouchGamepadBridge.bindingFor(input.button) ?: return
                injectBinding(binding, false, 0f)
            }
            is InputEvent.AxisMotion -> {
                val pair = TouchGamepadBridge.axisBindingsFor(input.axis) ?: return
                val negative = input.value < 0f
                val binding = if (negative) pair.first else pair.second
                val magnitude = if (negative) -input.value else input.value
                if (magnitude > 0.01f) {
                    injectBinding(binding, true, magnitude.coerceAtMost(1f))
                } else {
                    injectBinding(binding, false, 0f)
                }
            }
            else -> Unit
        }
    }

    // Tracks whether SHOW_KEYBOARD is currently held, so onShowKeyboard fires once per press (rising edge only)
    private var showKeyboardPressed = false
    private var radialMenuPressed = false
    private var radialMenuOpenedFromMotion = false
    private var radialMenuOpenerKeyCode = KeyEvent.KEYCODE_UNKNOWN
    private var radialMenuOpenerDeviceId = UNKNOWN_DEVICE_ID

    private fun releaseActiveAxes(
        exceptSource: PhysicalInputSource? = null,
        deviceId: Int? = null,
    ) {
        for (source in activeAxisBindings.toList()) {
            if (source == exceptSource || (deviceId != null && source.deviceId != deviceId)) continue
            activeAxisBindings.remove(source)
            val controller = profile?.getController(source.deviceId) ?: continue
            controller.getControllerBinding(source.keyCode)
                ?.takeIf { Binding.OPEN_RADIAL_MENU !in it.bindingCombo.bindings }
                ?.let {
                    handleInputEvent(
                        it.bindingCombo,
                        false,
                        0f,
                        fromMotion = true,
                        sourceKeyCode = source.keyCode,
                        sourceDeviceId = source.deviceId,
                        sourceController = controller,
                    )
                }
        }
    }

    private fun releaseActiveBindings(
        bindings: MutableMap<PhysicalInputSource, BindingCombo>,
        deviceId: Int? = null,
        fromMotion: Boolean = false,
    ) {
        for ((source, bindingCombo) in bindings.toMap()) {
            if (deviceId != null && source.deviceId != deviceId) continue
            bindings.remove(source)
            val controller = profile?.getController(source.deviceId)
            handleInputEvent(
                bindingCombo,
                false,
                0f,
                fromMotion = fromMotion,
                sourceKeyCode = source.keyCode,
                sourceDeviceId = source.deviceId,
                sourceController = controller,
            )
        }
    }

    init {
        ExternalController.setStickTuning(profile, this)
    }

    fun setProfile(profile: ControlsProfile?) {
        releaseActiveBindings(activeButtonBindings)
        releaseActiveBindings(activeTriggerBindings, fromMotion = true)
        releaseGyroModifierSources()
        releaseActiveAxes()
        cancelActiveSequences()
        clearMouseMoveContributions()
        clearScrollRepeats()
        closeRadialMenuIfOpen(commit = false)
        activeSequenceTriggerBindings.clear()
        sendGamepadState()
        this.profile = profile
        ExternalController.setStickTuning(profile, this)
        Log.d(TAG, "PhysicalControllerHandler: Profile set to ${profile?.name}")
    }

    /**
     * Clean up resources when handler is destroyed
     */
    fun cleanup() {
        releaseActiveBindings(activeButtonBindings)
        releaseActiveBindings(activeTriggerBindings, fromMotion = true)
        releaseGyroModifierSources()
        releaseActiveAxes()
        // U4: solta bindings de eixos remapeados segurados (state limpo).
        for (binding in remappedAxisBindings.values) {
            handleInputEvent(binding, false, 0f)
        }
        remappedAxisBindings.clear()
        // F §1.4: cancela ciclos de turbo pendentes + release lógico limpo (mesma
        // disciplina do remappedAxisBindings — nunca botão preso no teardown).
        for (sources in turboStates.values) {
            for (entry in sources.values) {
                entry.pending?.let { mainHandler.removeCallbacks(it) }
                injectBinding(entry.target, false, 0f)
            }
        }
        turboStates.clear()
        PluviaApp.events.off<GamepadDeviceRemovedEvent, Unit>(deviceRemovedListener)
        // K1 §1.3: listener do device virtual morre junto + o virtual sai do hub.
        PluviaApp.events.off<GamepadInputEvent, Boolean>(virtualGamepadListener)
        TouchGamepadSource.unregister()
        cancelActiveSequences()
        clearMouseMoveContributions()
        clearScrollRepeats()
        activeSequenceTriggerBindings.clear()
        showKeyboardPressed = false
        closeRadialMenuIfOpen(commit = false)
        sendGamepadState()
        ExternalController.clearStickTuning(this)
    }

    fun onInputDeviceRemoved(deviceId: Int) {
        cancelActiveSequences()
        releaseActiveBindings(activeButtonBindings, deviceId)
        releaseActiveBindings(activeTriggerBindings, deviceId, fromMotion = true)
        releaseActiveAxes(deviceId = deviceId)
        releaseGyroModifierSources(deviceId)
        mouseMoveContributions.keys.removeAll { it.deviceId == deviceId }
        recalculateMouseMoveOffset()
        sendGamepadState()
        if (radialMenuPressed && radialMenuOpenerDeviceId == deviceId) {
            closeRadialMenuIfOpen(commit = false)
        }
    }

    /**
     * Handle physical controller button events.
     * Extracted from InputControlsView.onKeyEvent()
     */
    /**
     * P5 (spec 2026-08-14-gamepad-upgrades-pendencias, Parte V): device de touchpad
     * PURO (classe TOUCHPAD do hub) nunca emite entrada de jogo. O DS4 fundido é
     * CONTROLLER e passa; devices fora do hub → null → permitidos.
     */
    private fun isControllerTouchpadDevice(deviceId: Int): Boolean =
        PluviaApp.gamepadHub.deviceFor(deviceId)?.deviceClass == DeviceClass.TOUCHPAD

    /**
     * K2 (spec 2026-08-16-K2, §1.3): teclas consumidas pelo modo mouse — A/B
     * (cliques) e DPAD (scroll). START NÃO está na lista: faz o toggle (hub).
     */
    private val MOUSE_MODE_CONSUMED_KEYS = setOf(
        KeyEvent.KEYCODE_BUTTON_A,
        KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
    )

    fun onKeyEvent(event: KeyEvent): Boolean {
        // F0 (spec 2026-08-15-input-core-avancado): t1 da medição de latência — par
        // do begin em MainActivity.dispatchKeyEvent.
        LatencyTracker.end(LatencyTracker.Source.KEY, System.nanoTime())
        // K2 (spec 2026-08-16-K2, §1.3): modo mouse ATIVO — A/B/dpad crus são do
        // mouse (o hub roteou os lógicos para o sink); consumir aqui para NÃO
        // chegarem ao jogo. START passa (é o toggle).
        if (PluviaApp.gamepadHub.mouseModeActive(event.deviceId) &&
            event.keyCode in MOUSE_MODE_CONSUMED_KEYS
        ) {
            return true
        }
        if (profile != null && event.repeatCount == 0) {
            val keyCode = JoyConSupport.remapKeyCode(event.device, event)
            // P5: defesa em profundidade — touchpad PURO nunca dirige o jogo.
            if (isControllerTouchpadDevice(event.deviceId)) return false
            // U4: remap da camada universal (gate ON) — binding EXPLÍCITO vence; sem
            // binding o fluxo cai no ExternalControllerBinding (byte-identical, V10).
            if (PrefManager.gamepadUniversalEnabled && applyUniversalKeyRemap(event)) {
                return true
            }
            if (radialMenuPressed && !isRadialMenuOpenerDevice(event.deviceId)) return true
            val controller = profile?.getController(event.deviceId)
            if (controller != null) {
                val controllerBinding = controller.getControllerBinding(keyCode)
                if (radialMenuPressed && controllerBinding?.bindingCombo?.bindings?.contains(Binding.OPEN_RADIAL_MENU) == true) {
                    if (keyCode == radialMenuOpenerKeyCode ||
                        radialMenuOpenerKeyCode == KeyEvent.KEYCODE_UNKNOWN
                    ) {
                        handleInputEvent(
                            controllerBinding.bindingCombo,
                            event.action == KeyEvent.ACTION_DOWN,
                            sourceKeyCode = keyCode,
                            sourceDeviceId = event.deviceId,
                            sourceController = controller,
                        )
                        return true
                    }
                    handleRadialMenuNavigationKey(event, keyCode)
                    return true
                }
                if (radialMenuPressed && handleRadialMenuNavigationKey(event, keyCode)) {
                    return true
                }

                if (controllerBinding != null) {
                    // Some controllers emit BOTH a digital KeyEvent for L2/R2 and an analog axis value in MotionEvent.
                    // If this physical key is mapped to a virtual trigger AND the device exposes trigger axes,
                    // ignore the KeyEvent to avoid an initial "full press" spike. MotionEvent will provide the analog value.
                    if ((keyCode == KeyEvent.KEYCODE_BUTTON_L2 || keyCode == KeyEvent.KEYCODE_BUTTON_R2) &&
                        controllerBinding.bindingCombo.bindings.any { it == Binding.GAMEPAD_BUTTON_L2 || it == Binding.GAMEPAD_BUTTON_R2 } &&
                        deviceHasTriggerAxis(event.device, keyCode)
                    ) {
                        return true
                    }
                    val isActionDown = event.action == KeyEvent.ACTION_DOWN
                    val source = PhysicalInputSource(event.deviceId, keyCode)
                    val bindingCombo = if (!controllerBinding.bindingCombo.isSequence &&
                        Binding.OPEN_RADIAL_MENU !in controllerBinding.bindingCombo.bindings
                    ) {
                        if (isActionDown) {
                            activeButtonBindings[source] = controllerBinding.bindingCombo
                            controllerBinding.bindingCombo
                        } else {
                            activeButtonBindings.remove(source) ?: controllerBinding.bindingCombo
                        }
                    } else {
                        controllerBinding.bindingCombo
                    }
                    val offset = if (isActionDown &&
                        bindingCombo.bindings.any { it == Binding.GAMEPAD_BUTTON_L2 || it == Binding.GAMEPAD_BUTTON_R2 }
                    ) 1f else 0f
                    handleInputEvent(
                        bindingCombo,
                        isActionDown,
                        offset,
                        sourceKeyCode = keyCode,
                        sourceDeviceId = event.deviceId,
                        sourceController = controller,
                    )

                    sendGamepadState()
                    return true
                }
            }
        }
        return false
    }

    private fun deviceHasTriggerAxis(device: InputDevice?, keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_L2 ->
                hasMotionRange(device, MotionEvent.AXIS_LTRIGGER) || hasMotionRange(device, MotionEvent.AXIS_BRAKE)
            KeyEvent.KEYCODE_BUTTON_R2 ->
                hasMotionRange(device, MotionEvent.AXIS_RTRIGGER) || hasMotionRange(device, MotionEvent.AXIS_GAS)
            else -> false
        }
    }

    private fun hasMotionRange(device: InputDevice?, axis: Int): Boolean {
        if (device == null) return false
        return device.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK) != null ||
            device.getMotionRange(axis, InputDevice.SOURCE_GAMEPAD) != null ||
            device.getMotionRange(axis) != null
    }

    /**
     * U4: aplica o binding da camada universal (DEFAULT + camada ativa) para uma tecla
     * crua. Retorna true quando o remap CONSUMIU o evento (injeção feita OU alvo sem
     * binding — o remap explícito substitui o botão original).
     */
    private fun applyUniversalKeyRemap(event: KeyEvent): Boolean {
        val hub = PluviaApp.gamepadHub
        val device = hub.deviceFor(event.deviceId) ?: return false
        val mapping = MappingDatabase.mappingFor(device.vendorId, device.productId)
            ?: MappingDatabase.defaultAndroidMapping(device.faceStyle)
        val logical = mapping.buttons.entries
            .firstOrNull { (_, b) -> b is RawBinding.Key && b.keyCode == event.keyCode }
            ?.key ?: return false
        val token = hub.layerBindingFor(event.deviceId, logical) ?: return false
        val decoded = GamepadBindingCodec.decode(token) ?: return false
        val controller = profile?.getController(event.deviceId) ?: return false
        val isDown = event.action == KeyEvent.ACTION_DOWN

        val targetKeyCode = when (val binding = decoded.raw) {
            is RawBinding.Key -> binding.keyCode
            is RawBinding.Axis -> ExternalControllerBinding.getKeyCodeForAxis(
                binding.axis,
                binding.direction.toByte(),
            )
            // Hat não é traduzido no caminho do jogo (decisão U4 v1 — dpad via tecla).
            is RawBinding.Hat -> return true
            // J1 (spec 2026-08-16-J): binding expr: — a expressão é a dona do
            // botão; o evento físico é consumido (sem injeção por aqui).
            null -> return true
        }
        val targetBinding = controller.getControllerBinding(targetKeyCode)
        // F §1.4: turbo — a FONTE lógica (mesma chave do caminho de eixo — triggers
        // digitais+analógicos não criam ciclo duplo) é a chave do ciclo. DOWN físico
        // inicia (DOWN imediato + toggles agendados); UP físico cancela + release.
        if (decoded.turbo) {
            if (targetBinding != null) {
                if (isDown) {
                    startTurbo(event.deviceId, logical.name, targetBinding.binding)
                } else {
                    stopTurbo(event.deviceId, logical.name)
                }
            }
            return true
        }
        if (targetBinding != null) {
            val offset = if (isDown &&
                (targetBinding.binding == Binding.GAMEPAD_BUTTON_L2 || targetBinding.binding == Binding.GAMEPAD_BUTTON_R2)
            ) 1f else 0f
            injectBinding(targetBinding.binding, isDown, offset)
        }
        return true
    }

    /**
     * E2 (spec 2026-08-13-onda2 §1.7): aplica a deadzone do PERFIL UNIVERSAL no estado
     * do ExternalController — sticks em par radial (DeadzoneProcessor.process), triggers
     * axiais. Só roda quando o perfil override EXPLICITAMENTE o valor; sem override o
     * caminho fica byte-identical (regressão V10 preservada — o ExternalController já
     * aplica a `flat` do próprio device via getCenteredAxis).
     */
    private fun applyProfileDeadzone(controller: ExternalController) {
        val deviceId = controller.deviceId
        if (deviceId == -1) return
        val profile = PluviaApp.gamepadHub.profileFor(deviceId, PluviaApp.gamepadHub.activeAppId)
        // F1.2: com Flick Stick ativo o stick DIREITO é do FlickStickProcessor — nenhum
        // pré-processamento aqui (o activationRadius 0.85 É a dead band do modo).
        val flickOwnsRight = profile.flickStickEnabled == true
        // F1.1 (spec 2026-08-15-input-core-avancado): modo/curva/LUT custom assumem o
        // pipeline do stick (StickTransform completo: deadzone + curva). Só deadzone
        // override = caminho antigo (byte-identical, regressão V10).
        val leftCustom = profile.leftStickDeadzoneMode != null ||
            profile.leftStickCurve != null || profile.leftStickLut != null
        if (leftCustom) {
            val result = StickTransform.apply(
                StickSample(controller.state.thumbLX, controller.state.thumbLY),
                StickTransformConfig(
                    deadzone = profile.leftStickDeadzone ?: PrefManager.gamepadStickDeadzone,
                    mode = profile.leftStickDeadzoneMode ?: DeadzoneMode.RADIAL,
                    curve = profile.leftStickCurve ?: ResponseCurve.LINEAR,
                    lut = profile.leftStickLut ?: emptyList(),
                ),
            )
            controller.state.thumbLX = result.x
            controller.state.thumbLY = result.y
        } else {
            profile.leftStickDeadzone?.let { dead ->
                val result = DeadzoneProcessor.process(
                    StickSample(controller.state.thumbLX, controller.state.thumbLY),
                    DeadzoneConfig(leftStick = dead, rightStick = dead),
                )
                controller.state.thumbLX = result.x
                controller.state.thumbLY = result.y
            }
        }
        if (!flickOwnsRight) {
            val rightCustom = profile.rightStickDeadzoneMode != null ||
                profile.rightStickCurve != null || profile.rightStickLut != null
            if (rightCustom) {
                val result = StickTransform.apply(
                    StickSample(controller.state.thumbRX, controller.state.thumbRY),
                    StickTransformConfig(
                        deadzone = profile.rightStickDeadzone ?: PrefManager.gamepadStickDeadzone,
                        mode = profile.rightStickDeadzoneMode ?: DeadzoneMode.RADIAL,
                        curve = profile.rightStickCurve ?: ResponseCurve.LINEAR,
                        lut = profile.rightStickLut ?: emptyList(),
                    ),
                )
                controller.state.thumbRX = result.x
                controller.state.thumbRY = result.y
            } else {
                profile.rightStickDeadzone?.let { dead ->
                    val result = DeadzoneProcessor.process(
                        StickSample(controller.state.thumbRX, controller.state.thumbRY),
                        DeadzoneConfig(leftStick = dead, rightStick = dead),
                    )
                    controller.state.thumbRX = result.x
                    controller.state.thumbRY = result.y
                }
            }
        }
        profile.leftTriggerDeadzone?.let { dead ->
            controller.state.triggerL = DeadzoneProcessor.processAxis(controller.state.triggerL, dead)
        }
        profile.rightTriggerDeadzone?.let { dead ->
            controller.state.triggerR = DeadzoneProcessor.processAxis(controller.state.triggerR, dead)
        }
    }

    /**
     * U4: injeta o binding ALVO de um eixo remapeado pela camada universal (tecla ou
     * eixo), com o valor do eixo de origem. Libera quando o valor cai abaixo da
     * deadzone (release controlado por [remappedAxisBindings] — o eixo original não
     * passa pelo fluxo normal).
     *
     * F §1.4: com turbo no token, o ciclo é DONO da fonte enquanto ativo — o valor
     * analógico contínuo só liga/desliga o ciclo (onda quadrada digital, período
     * fixo 80 ms); pressed → [startTurbo] (DOWN imediato + toggles), released →
     * [stopTurbo] (cancel + release limpo). [sourceKey] é o GamepadButton.name
     * lógico — a MESMA chave do caminho de tecla (sem ciclo duplo nos triggers).
     */
    private fun handleRemappedAxis(
        controller: ExternalController,
        sourceKey: String,
        sourceAxis: Int,
        value: Float,
        token: String,
    ) {
        val decoded = GamepadBindingCodec.decode(token) ?: return
        val targetKeyCode = when (val binding = decoded.raw) {
            is RawBinding.Key -> binding.keyCode
            is RawBinding.Axis -> ExternalControllerBinding.getKeyCodeForAxis(
                binding.axis,
                binding.direction.toByte(),
            )
            is RawBinding.Hat -> return
            // J1: binding expr: — dono do botão; eixo físico consumido.
            null -> return
        }
        val target = controller.getControllerBinding(targetKeyCode) ?: return
        if (decoded.turbo) {
            val pressed = kotlin.math.abs(value) > ControlElement.STICK_DEAD_ZONE
            val active = turboStates[controller.deviceId]?.get(sourceKey) != null
            if (pressed && !active) {
                startTurbo(controller.deviceId, sourceKey, target.binding)
            } else if (!pressed && active) {
                stopTurbo(controller.deviceId, sourceKey)
            }
            return
        }
        val pressed = kotlin.math.abs(value) > ControlElement.STICK_DEAD_ZONE
        val wasPressed = remappedAxisBindings[sourceAxis] != null
        if (pressed) {
            if (!wasPressed || target.binding.isGamepad) {
                handleInputEvent(target.binding, true, value)
            }
            remappedAxisBindings[sourceAxis] = target.binding
        } else if (wasPressed) {
            handleInputEvent(target.binding, false, 0f)
            remappedAxisBindings.remove(sourceAxis)
        }
    }

    /**
     * F §1.4: injeção compartilhada — binding no estado do virtual gamepad +
     * envio imediato ao WinHandler. O ciclo de turbo reusa EXATAMENTE este caminho
     * (o mesmo do U4 — nada de rota nova de injeção).
     */
    private fun injectBinding(binding: Binding, isDown: Boolean, offset: Float) {
        handleInputEvent(binding, isDown, offset)
        val winHandler = xServer?.winHandler
        val state = profile?.gamepadState
        if (winHandler != null) {
            winHandler.sendGamepadState()
            winHandler.sendVirtualGamepadState(state)
        }
    }

    /**
     * F §1.4: inicia o ciclo de turbo de uma fonte física — DOWN lógico imediato
     * (fase 1) + agendamento do próximo toggle no Handler MAIN (mesmo relógio e
     * mesmo caminho da injeção U4). Chamado no DOWN físico; o UP físico chama
     * [stopTurbo]. Reiniciar com ciclo ativo substitui o agendamento (idempotente).
     */
    private fun startTurbo(deviceId: Int, sourceKey: String, targetBinding: Binding) {
        val sources = turboStates.getOrPut(deviceId) { mutableMapOf() }
        sources.remove(sourceKey)?.pending?.let { mainHandler.removeCallbacks(it) }
        injectBinding(targetBinding, true, 1f)
        sources[sourceKey] = TurboToggleState(
            phase = 1,
            target = targetBinding,
            pending = scheduleTurboToggle(deviceId, sourceKey, targetBinding),
        )
    }

    /**
     * Agendamento recursivo do toggle: cada disparo alterna a fase (0→DOWN, 1→UP) e
     * re-agenda meio período adiante (TurboScheduler — onda quadrada 50%, período
     * fixo 80 ms). O Runnable se auto-remove do mapa quando o ciclo morre (UP
     * físico/removeDevice/cleanup) — `entry == null` = no-op.
     */
    private fun scheduleTurboToggle(deviceId: Int, sourceKey: String, targetBinding: Binding): Runnable {
        val runnable = object : Runnable {
            override fun run() {
                val entry = turboStates[deviceId]?.get(sourceKey) ?: return
                val down = entry.phase == 0
                injectBinding(targetBinding, down, if (down) 1f else 0f)
                entry.phase = if (down) 1 else 0
                entry.pending = this
                val now = SystemClock.uptimeMillis()
                val delay = (TurboScheduler.nextToggleAt(now, TurboScheduler.PERIOD_DEFAULT_MS) - now)
                    .coerceAtLeast(0L)
                mainHandler.postDelayed(this, delay)
            }
        }
        val now = SystemClock.uptimeMillis()
        val delay = (TurboScheduler.nextToggleAt(now, TurboScheduler.PERIOD_DEFAULT_MS) - now)
            .coerceAtLeast(0L)
        mainHandler.postDelayed(runnable, delay)
        return runnable
    }

    /**
     * F §1.4: UP físico / fim do ciclo — cancela o agendamento pendente e garante o
     * release lógico limpo (mesma disciplina do remappedAxisBindings — nunca botão
     * preso). Sem ciclo ativo = no-op.
     */
    private fun stopTurbo(deviceId: Int, sourceKey: String) {
        val entry = turboStates[deviceId]?.remove(sourceKey) ?: return
        entry.pending?.let { mainHandler.removeCallbacks(it) }
        injectBinding(entry.target, false, 0f)
    }


    /**
     * Handle physical controller analog stick and trigger events.
     * Extracted from InputControlsView.onGenericMotionEvent()
     */
    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        // F0 (spec 2026-08-15-input-core-avancado): t1 — par do begin em
        // MainActivity.dispatchGenericMotionEvent.
        LatencyTracker.end(LatencyTracker.Source.MOTION, System.nanoTime())
        if (profile != null) {
            // P5: mesma defesa do onKeyEvent — touchpad PURO nunca dirige o jogo.
            if (isControllerTouchpadDevice(event.deviceId)) return false
            if (radialMenuPressed && !isRadialMenuOpenerDevice(event.deviceId)) return true
            val controller = profile?.getController(event.deviceId)
            if (controller != null && controller.updateStateFromMotionEvent(event)) {
                // E2 (spec 2026-08-13-onda2 §1.7): deadzone por device via perfil da
                // camada universal — APENAS quando o perfil override explicitamente.
                if (PrefManager.gamepadUniversalEnabled) {
                    applyProfileDeadzone(controller)
                }
                if (radialMenuPressed) {
                    updateRadialMenuVector(controller)
                    if (radialMenuOpenedFromMotion && !isRadialMenuMotionOpenerPressed(controller)) {
                        handleInputEvent(
                            Binding.OPEN_RADIAL_MENU,
                            false,
                            0f,
                            fromMotion = true,
                            sourceKeyCode = radialMenuOpenerKeyCode,
                            sourceDeviceId = event.deviceId,
                            sourceController = controller,
                        )
                    }
                    return true
                }

                // Process trigger buttons (L2/R2) — U4: com remap universal explícito
                // de LEFT_TRIGGER/RIGHT_TRIGGER, o binding da camada vence.
                val hub = PluviaApp.gamepadHub
                val l2Remap = if (PrefManager.gamepadUniversalEnabled) {
                    hub.layerBindingFor(event.deviceId, GamepadButton.LEFT_TRIGGER)
                } else {
                    null
                }
                val r2Remap = if (PrefManager.gamepadUniversalEnabled) {
                    hub.layerBindingFor(event.deviceId, GamepadButton.RIGHT_TRIGGER)
                } else {
                    null
                }
                var controllerBinding = controller.getControllerBinding(KeyEvent.KEYCODE_BUTTON_L2)
                if (controllerBinding != null) {
                    if (l2Remap == null) {
                        handleTriggerBinding(
                            KeyEvent.KEYCODE_BUTTON_L2,
                            controllerBinding.binding,
                            controllerBinding.bindingCombo,
                            controller.state.triggerL > 0f,
                            controller.state.triggerL,
                            fromMotion = true,
                            sourceKeyCode = KeyEvent.KEYCODE_BUTTON_L2,
                            sourceDeviceId = event.deviceId,
                            sourceController = controller,
                        )
                    } else {
                        handleRemappedAxis(controller, GamepadButton.LEFT_TRIGGER.name, KeyEvent.KEYCODE_BUTTON_L2, controller.state.triggerL, l2Remap)
                    }
                    if (radialMenuPressed) {
                        sendGamepadState()
                        return true
                    }
                }

                controllerBinding = controller.getControllerBinding(KeyEvent.KEYCODE_BUTTON_R2)
                if (controllerBinding != null) {
                    if (r2Remap == null) {
                        handleTriggerBinding(
                            KeyEvent.KEYCODE_BUTTON_R2,
                            controllerBinding.binding,
                            controllerBinding.bindingCombo,
                            controller.state.triggerR > 0f,
                            controller.state.triggerR,
                            fromMotion = true,
                            sourceKeyCode = KeyEvent.KEYCODE_BUTTON_R2,
                            sourceDeviceId = event.deviceId,
                            sourceController = controller,
                        )
                    } else {
                        handleRemappedAxis(controller, GamepadButton.RIGHT_TRIGGER.name, KeyEvent.KEYCODE_BUTTON_R2, controller.state.triggerR, r2Remap)
                    }
                    if (radialMenuPressed) {
                        sendGamepadState()
                        return true
                    }
                }

                // Process analog stick input
                processJoystickInput(controller, event.deviceId)

                sendGamepadState()
                return true
            }
        }
        return false
    }

    private fun sendGamepadState() {
        gamepadStateSender(profile?.gamepadState)
    }

    /**
     * Create a timer for continuous mouse movement injection.
     * Runs at 60 FPS, injecting mouse deltas based on mouseMoveOffset.
     */
    private fun createMouseMoveTimer() {
        if (profile != null && mouseMoveTimer == null) {
            mouseMoveTimer = Timer()
            mouseMoveTimer?.schedule(object : TimerTask() {
                override fun run() {
                    synchronized(mouseMoveLock) {
                        // Tuning already applies the user's chosen deadzone. Preserve sub-pixel input
                        // across ticks instead of silently discarding low-sensitivity movement.
                        if (mouseMoveOffset.x == 0f) mouseMoveRemainder.x = 0f
                        if (mouseMoveOffset.y == 0f) mouseMoveRemainder.y = 0f
                        if (mouseMoveOffset.x == 0f && mouseMoveOffset.y == 0f) return@synchronized

                        // Look up cursor speed dynamically so it updates when profile changes
                        val cursorSpeed = profile?.cursorSpeed ?: 1f
                        val scaledX = mouseMoveOffset.x * 10 * cursorSpeed + mouseMoveRemainder.x
                        val scaledY = mouseMoveOffset.y * 10 * cursorSpeed + mouseMoveRemainder.y
                        val deltaX = scaledX.toInt()
                        val deltaY = scaledY.toInt()
                        mouseMoveRemainder.set(scaledX - deltaX, scaledY - deltaY)
                        if (deltaX != 0 || deltaY != 0) {
                            xServer?.injectPointerMoveDelta(deltaX, deltaY)
                        }
                    }
                }
            }, 0, 1000 / 60)
        }
    }

    private fun updateMouseMoveContribution(
        binding: Binding,
        isActionDown: Boolean,
        offset: Float,
        sourceKeyCode: Int,
        sourceDeviceId: Int,
    ) {
        if (isActionDown) {
            val contribution = if (offset != 0f) {
                offset
            } else if (binding == Binding.MOUSE_MOVE_LEFT || binding == Binding.MOUSE_MOVE_UP) {
                -1f
            } else {
                1f
            }
            mouseMoveContributions[MouseMoveSource(sourceDeviceId, sourceKeyCode, binding)] = contribution
            createMouseMoveTimer()
        } else {
            mouseMoveContributions.keys.removeAll { source ->
                source.binding == binding &&
                    (sourceKeyCode == KeyEvent.KEYCODE_UNKNOWN || source.keyCode == sourceKeyCode) &&
                    (sourceDeviceId == UNKNOWN_DEVICE_ID || source.deviceId == sourceDeviceId)
            }
        }
        recalculateMouseMoveOffset()
    }

    private fun recalculateMouseMoveOffset() {
        synchronized(mouseMoveLock) {
            mouseMoveOffset.set(0f, 0f)
            mouseMoveContributions.forEach { (source, contribution) ->
                if (source.binding == Binding.MOUSE_MOVE_LEFT || source.binding == Binding.MOUSE_MOVE_RIGHT) {
                    mouseMoveOffset.x += contribution
                } else {
                    mouseMoveOffset.y += contribution
                }
            }
            if (mouseMoveContributions.isEmpty()) {
                mouseMoveRemainder.set(0f, 0f)
                mouseMoveTimer?.cancel()
                mouseMoveTimer = null
            }
        }
    }

    private fun clearMouseMoveContributions() {
        synchronized(mouseMoveLock) {
            mouseMoveContributions.clear()
            mouseMoveOffset.set(0f, 0f)
            mouseMoveRemainder.set(0f, 0f)
            mouseMoveTimer?.cancel()
            mouseMoveTimer = null
        }
    }

    private fun handleScrollBinding(binding: Binding, isActionDown: Boolean): Boolean {
        if (binding != Binding.MOUSE_SCROLL_UP && binding != Binding.MOUSE_SCROLL_DOWN) {
            return false
        }

        var pulseImmediately = false
        synchronized(scrollRepeatLock) {
            if (isActionDown) {
                pulseImmediately = activeScrollBindings.add(binding)
                createScrollRepeatTimerLocked()
            } else {
                activeScrollBindings.remove(binding)
                if (activeScrollBindings.isEmpty()) {
                    cancelScrollRepeatTimerLocked()
                }
            }
        }

        if (pulseImmediately) {
            sendScrollPulse(binding)
        }
        return true
    }

    private fun createScrollRepeatTimerLocked() {
        if (scrollRepeatTimer != null) return
        scrollRepeatTimer = Timer()
        scrollRepeatTimer?.schedule(object : TimerTask() {
            override fun run() {
                val bindings = synchronized(scrollRepeatLock) {
                    activeScrollBindings.toList()
                }
                bindings.forEach { sendScrollPulse(it) }
            }
        }, SCROLL_REPEAT_INTERVAL_MS, SCROLL_REPEAT_INTERVAL_MS)
    }

    private fun cancelScrollRepeatTimerLocked() {
        scrollRepeatTimer?.cancel()
        scrollRepeatTimer = null
    }

    private fun clearScrollRepeats() {
        synchronized(scrollRepeatLock) {
            activeScrollBindings.clear()
            cancelScrollRepeatTimerLocked()
        }
    }

    private fun sendScrollPulse(binding: Binding) {
        val pointerButton = binding.pointerButton ?: return
        xServer?.injectPointerButtonPress(pointerButton)
        xServer?.injectPointerButtonRelease(pointerButton)
    }

    /**
     * Process analog stick input and apply bindings.
     * Extracted from InputControlsView.processJoystickInput()
     */
    private fun processJoystickInput(controller: ExternalController, deviceId: Int) {
        // F1.2 (spec 2026-08-15-input-core-avancado): Flick Stick ativo ⇒ o stick
        // direito físico vira gesto de flick (yaw rad/s → deflexão, mesma unidade do
        // CAMERA mode). Sem o gate de CAMERA: o gyro do fork foi removido (§4).
        val flickOwnsRightStick = PrefManager.gamepadUniversalEnabled &&
            PluviaApp.gamepadHub.profileFor(controller.deviceId, PluviaApp.gamepadHub.activeAppId)
                .flickStickEnabled == true
        if (flickOwnsRightStick) {
            val yaw = PluviaApp.gamepadHub.flickStickProcess(
                controller.deviceId,
                controller.state.thumbRX,
                controller.state.thumbRY,
                SystemClock.uptimeMillis(),
            )
            controller.state.thumbRX = GyroStickMapping.deflection(yaw, 1f)
            controller.state.thumbRY = 0f
        }

        val axes = intArrayOf(
            MotionEvent.AXIS_X,
            MotionEvent.AXIS_Y,
            MotionEvent.AXIS_Z,
            MotionEvent.AXIS_RZ,
            MotionEvent.AXIS_HAT_X,
            MotionEvent.AXIS_HAT_Y
        )
        val values = floatArrayOf(
            controller.state.thumbLX,
            controller.state.thumbLY,
            controller.state.thumbRX,
            controller.state.thumbRY,
            controller.state.dPadX.toFloat(),
            controller.state.dPadY.toFloat()
        )
        for (i in axes.indices) {
            // F1.2: Flick Stick ativo ⇒ o processador escreveu a deflexão acima; o
            // eixo físico do right stick não passa pelo fluxo normal.
            if (flickOwnsRightStick &&
                (axes[i] == MotionEvent.AXIS_Z || axes[i] == MotionEvent.AXIS_RZ)
            ) {
                continue
            }
            // U4: sticks com binding explícito na camada universal são injetados pelo
            // binding ALVO (o eixo original não passa pelo activeAxisBindings — o
            // controle de release é próprio). Hats não são remapeados no caminho do
            // jogo (decisão U4 v1 — dpad via canal de tecla).
            val logicalButton = when (axes[i]) {
                MotionEvent.AXIS_X, MotionEvent.AXIS_Y -> GamepadButton.LEFT_STICK
                MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ -> GamepadButton.RIGHT_STICK
                else -> null
            }
            if (logicalButton != null && PrefManager.gamepadUniversalEnabled) {
                val token = PluviaApp.gamepadHub.layerBindingFor(controller.deviceId, logicalButton)
                if (token != null) {
                    handleRemappedAxis(controller, logicalButton.name, axes[i], values[i], token)
                    continue
                }
            }

            val posKeyCode = ExternalControllerBinding.getKeyCodeForAxis(axes[i], 1.toByte())
            val negKeyCode = ExternalControllerBinding.getKeyCodeForAxis(axes[i], (-1).toByte())
            val positiveSource = PhysicalInputSource(deviceId, posKeyCode)
            val negativeSource = PhysicalInputSource(deviceId, negKeyCode)

            val isActive = isPhysicalAxisActive(values[i], axes[i])

            if (isActive) {
                val activeKey = ExternalControllerBinding.getKeyCodeForAxis(axes[i], Mathf.sign(values[i]))
                val oppositeKey = if (activeKey == posKeyCode) negKeyCode else posKeyCode
                val activeSource = if (activeKey == posKeyCode) positiveSource else negativeSource
                val oppositeSource = if (activeKey == posKeyCode) negativeSource else positiveSource

                val wasAlreadyActive = !activeAxisBindings.add(activeSource)
                controller.getControllerBinding(activeKey)?.let {
                    if (!it.bindingCombo.isSequence || !wasAlreadyActive) {
                        handleInputEvent(
                            it.bindingCombo,
                            true,
                            values[i],
                            fromMotion = true,
                            sourceKeyCode = activeKey,
                            sourceDeviceId = deviceId,
                            sourceController = controller,
                        )
                    }
                    if (radialMenuPressed) return
                }
                // release opposite direction (if it was active)
                if (activeAxisBindings.remove(oppositeSource)) {
                    controller.getControllerBinding(oppositeKey)?.let {
                        handleInputEvent(
                            it.bindingCombo,
                            false,
                            0f,
                            fromMotion = true,
                            sourceKeyCode = oppositeKey,
                            sourceDeviceId = deviceId,
                            sourceController = controller,
                        )
                    }
                }
            } else {
                // release both directions only if they were active
                if (activeAxisBindings.remove(positiveSource)) {
                    controller.getControllerBinding(posKeyCode)?.let {
                        handleInputEvent(
                            it.bindingCombo,
                            false,
                            0f,
                            fromMotion = true,
                            sourceKeyCode = posKeyCode,
                            sourceDeviceId = deviceId,
                            sourceController = controller,
                        )
                    }
                }
                if (activeAxisBindings.remove(negativeSource)) {
                    controller.getControllerBinding(negKeyCode)?.let {
                        handleInputEvent(
                            it.bindingCombo,
                            false,
                            0f,
                            fromMotion = true,
                            sourceKeyCode = negKeyCode,
                            sourceDeviceId = deviceId,
                            sourceController = controller,
                        )
                    }
                }
            }
        }
    }

    private fun handleTriggerBinding(
        keyCode: Int,
        legacyBinding: Binding,
        bindingCombo: BindingCombo,
        isPressed: Boolean,
        offset: Float,
        fromMotion: Boolean = false,
        sourceKeyCode: Int = KeyEvent.KEYCODE_UNKNOWN,
        sourceDeviceId: Int = UNKNOWN_DEVICE_ID,
        sourceController: ExternalController? = null,
    ) {
        val triggerSource = physicalInputSource(sourceDeviceId, keyCode, sourceController)
        if (bindingCombo.isSequence) {
            if (isPressed) {
                if (activeSequenceTriggerBindings.add(triggerSource)) {
                    handleInputEvent(
                        bindingCombo,
                        true,
                        offset,
                        fromMotion,
                        sourceKeyCode,
                        sourceDeviceId,
                        sourceController,
                    )
                }
            } else {
                activeSequenceTriggerBindings.remove(triggerSource)
            }
        } else {
            val resolvedBindingCombo = bindingCombo.takeIf { !it.isEmpty } ?: BindingCombo.of(legacyBinding)
            val dispatchedBindingCombo = if (isPressed) {
                activeTriggerBindings[triggerSource] = resolvedBindingCombo
                resolvedBindingCombo
            } else {
                activeTriggerBindings.remove(triggerSource) ?: resolvedBindingCombo
            }
            handleInputEvent(
                dispatchedBindingCombo,
                isPressed,
                offset,
                fromMotion,
                sourceKeyCode,
                sourceDeviceId,
                sourceController,
            )
        }
    }

    /**
     * Apply a binding to the virtual gamepad state and send to WinHandler.
     * Extracted from InputControlsView.handleInputEvent()
     */
    // offset: analog axis value for presses; must be 0f for releases (triggers use offset > 0f
    // to determine pressed state, sticks gate on isActionDown, everything else ignores offset)
    private fun handleInputEvent(
        bindingCombo: BindingCombo,
        isActionDown: Boolean,
        offset: Float = 0f,
        fromMotion: Boolean = false,
        sourceKeyCode: Int = KeyEvent.KEYCODE_UNKNOWN,
        sourceDeviceId: Int = UNKNOWN_DEVICE_ID,
        sourceController: ExternalController? = null,
    ) {
        if (bindingCombo.isEmpty) return
        if (Binding.OPEN_RADIAL_MENU in bindingCombo.bindings) {
            handleInputEvent(
                Binding.OPEN_RADIAL_MENU,
                isActionDown,
                offset,
                fromMotion,
                sourceKeyCode,
                sourceDeviceId,
                sourceController,
            )
            return
        }
        if (bindingCombo.isSequence) {
            if (isActionDown) {
                performBindingSequence(
                    bindingCombo,
                    offset,
                    fromMotion,
                    sourceKeyCode,
                    sourceDeviceId,
                    sourceController,
                )
            }
            return
        }

        if (isActionDown) {
            bindingCombo.bindings.forEach { binding ->
                handleInputEvent(
                    binding,
                    true,
                    offset,
                    fromMotion,
                    sourceKeyCode,
                    sourceDeviceId,
                    sourceController,
                )
            }
        } else {
            bindingCombo.bindings.asReversed().forEach { binding ->
                handleInputEvent(
                    binding,
                    false,
                    offset,
                    fromMotion,
                    sourceKeyCode,
                    sourceDeviceId,
                    sourceController,
                )
            }
        }
    }

    private fun performBindingSequence(
        bindingCombo: BindingCombo,
        offset: Float,
        fromMotion: Boolean,
        sourceKeyCode: Int,
        sourceDeviceId: Int,
        sourceController: ExternalController?,
    ) {
        val pressDurationMs = minOf(
            SEQUENCE_PRESS_MS,
            (bindingCombo.sequenceDelayMs - 1).coerceAtLeast(1).toLong(),
        )
        bindingCombo.bindings.forEachIndexed { index, binding ->
            sequenceHandler.postDelayed({
                handleInputEvent(
                    binding,
                    true,
                    offset,
                    fromMotion,
                    sourceKeyCode,
                    sourceDeviceId,
                    sourceController,
                )
                activeSequenceBindings[binding] = (activeSequenceBindings[binding] ?: 0) + 1
                val sequenceSource = physicalInputSource(sourceDeviceId, sourceKeyCode, sourceController)
                if (binding == Binding.GYRO_MODIFIER) activeSequenceGyroSources.add(sequenceSource)
                sendGamepadState()
                sequenceHandler.postDelayed({
                    val activeCount = activeSequenceBindings[binding] ?: return@postDelayed
                    if (binding == Binding.GYRO_MODIFIER) {
                        activeSequenceGyroSources.remove(sequenceSource)
                        handleInputEvent(
                            binding,
                            false,
                            0f,
                            fromMotion,
                            sourceKeyCode,
                            sourceDeviceId,
                            sourceController,
                        )
                    }
                    if (activeCount > 1) {
                        activeSequenceBindings[binding] = activeCount - 1
                    } else {
                        activeSequenceBindings.remove(binding)
                        if (binding != Binding.GYRO_MODIFIER) {
                            handleInputEvent(
                                binding,
                                false,
                                0f,
                                fromMotion,
                                sourceKeyCode,
                                sourceDeviceId,
                                sourceController,
                            )
                        }
                        sendGamepadState()
                    }
                }, pressDurationMs)
            }, index * bindingCombo.sequenceDelayMs.toLong())
        }
    }

    private fun cancelActiveSequences() {
        sequenceHandler.removeCallbacksAndMessages(null)
        activeSequenceGyroSources.toList().forEach { source ->
            handleInputEvent(
                Binding.GYRO_MODIFIER,
                false,
                0f,
                sourceKeyCode = source.keyCode,
                sourceDeviceId = source.deviceId,
            )
        }
        activeSequenceGyroSources.clear()
        if (activeSequenceBindings.isEmpty()) {
            activeSequenceTriggerBindings.clear()
            return
        }
        activeSequenceBindings.keys.toList().asReversed().forEach { binding ->
            if (binding != Binding.GYRO_MODIFIER) handleInputEvent(binding, false, 0f)
        }
        activeSequenceBindings.clear()
        activeSequenceTriggerBindings.clear()
        sendGamepadState()
    }

    private fun handleInputEvent(
        binding: Binding,
        isActionDown: Boolean,
        offset: Float = 0f,
        fromMotion: Boolean = false,
        sourceKeyCode: Int = KeyEvent.KEYCODE_UNKNOWN,
        sourceDeviceId: Int = UNKNOWN_DEVICE_ID,
        sourceController: ExternalController? = null,
    ) {
        if (binding == Binding.NONE) return

        if (binding == Binding.GYRO_MODIFIER) {
            setGyroModifierPressed(sourceDeviceId, sourceKeyCode, sourceController, isActionDown)
            return
        }

        if (binding == Binding.OPEN_RADIAL_MENU) {
            if (!isActionDown && radialMenuPressed && !isRadialMenuOpenerDevice(sourceDeviceId)) return
            if (radialMenuPressed != isActionDown) {
                if (isActionDown) {
                    neutralizeMotionInputs(sourceKeyCode, sourceDeviceId, sourceController)
                } else if (fromMotion && sourceKeyCode != KeyEvent.KEYCODE_UNKNOWN) {
                    activeAxisBindings.remove(
                        physicalInputSource(sourceDeviceId, sourceKeyCode, sourceController),
                    )
                }
                radialMenuPressed = isActionDown
                radialMenuOpenedFromMotion = isActionDown && fromMotion
                radialMenuOpenerKeyCode = if (isActionDown) sourceKeyCode else KeyEvent.KEYCODE_UNKNOWN
                radialMenuOpenerDeviceId = if (isActionDown) sourceDeviceId else UNKNOWN_DEVICE_ID
                onRadialMenuButtonStateChanged?.invoke(isActionDown, true)
                if (!isActionDown) onRadialMenuVectorChanged?.invoke(0f, 0f)
            } else if (!isActionDown) {
                radialMenuOpenedFromMotion = false
                radialMenuOpenerKeyCode = KeyEvent.KEYCODE_UNKNOWN
                radialMenuOpenerDeviceId = UNKNOWN_DEVICE_ID
            }
            return
        }

        if (binding.isGamepad) {
            val winHandler = xServer?.winHandler
            val state = profile?.gamepadState

            if (state != null) {
                val buttonIdx = binding.ordinal - Binding.GAMEPAD_BUTTON_A.ordinal
                if (buttonIdx <= ExternalController.IDX_BUTTON_R2.toInt()) {
                    when (buttonIdx) {
                        ExternalController.IDX_BUTTON_L2.toInt() -> {
                            val triggerValue = if (offset > 0f) offset else if (isActionDown) 1f else 0f
                            state.triggerL = triggerValue
                            state.setPressed(ExternalController.IDX_BUTTON_L2.toInt(), triggerValue > 0f)
                        }
                        ExternalController.IDX_BUTTON_R2.toInt() -> {
                            val triggerValue = if (offset > 0f) offset else if (isActionDown) 1f else 0f
                            state.triggerR = triggerValue
                            state.setPressed(ExternalController.IDX_BUTTON_R2.toInt(), triggerValue > 0f)
                        }
                        else -> state.setPressed(buttonIdx, isActionDown)
                    }
                }
                else {
                    when (binding) {
                        Binding.GAMEPAD_LEFT_THUMB_UP, Binding.GAMEPAD_LEFT_THUMB_DOWN -> {
                            state.thumbLY = gyroStickMixer?.invoke(binding, isActionDown, offset, sourceKeyCode)
                                ?: if (isActionDown) offset else 0f
                        }
                        Binding.GAMEPAD_LEFT_THUMB_LEFT, Binding.GAMEPAD_LEFT_THUMB_RIGHT -> {
                            state.thumbLX = gyroStickMixer?.invoke(binding, isActionDown, offset, sourceKeyCode)
                                ?: if (isActionDown) offset else 0f
                        }
                        Binding.GAMEPAD_RIGHT_THUMB_UP, Binding.GAMEPAD_RIGHT_THUMB_DOWN -> {
                            state.thumbRY = gyroStickMixer?.invoke(binding, isActionDown, offset, sourceKeyCode)
                                ?: if (isActionDown) offset else 0f
                        }
                        Binding.GAMEPAD_RIGHT_THUMB_LEFT, Binding.GAMEPAD_RIGHT_THUMB_RIGHT -> {
                            state.thumbRX = gyroStickMixer?.invoke(binding, isActionDown, offset, sourceKeyCode)
                                ?: if (isActionDown) offset else 0f
                        }
                        Binding.GAMEPAD_DPAD_UP  -> {
                            state.dpad[0] = isActionDown
                            if(isActionDown) {
                                state.dpad[Binding.GAMEPAD_DPAD_DOWN.ordinal - Binding.GAMEPAD_DPAD_UP.ordinal ] = false
                            }
                        }
                        Binding.GAMEPAD_DPAD_DOWN -> {
                            state.dpad[binding.ordinal - Binding.GAMEPAD_DPAD_UP.ordinal] = isActionDown
                            if(isActionDown) {
                                state.dpad[0] = false
                            }
                        }
                       Binding.GAMEPAD_DPAD_LEFT -> {
                            state.dpad[binding.ordinal - Binding.GAMEPAD_DPAD_UP.ordinal] = isActionDown
                            if(isActionDown) {
                              state.dpad[Binding.GAMEPAD_DPAD_RIGHT.ordinal - Binding.GAMEPAD_DPAD_UP.ordinal ] = false
                          }
                        }
                        Binding.GAMEPAD_DPAD_RIGHT -> {
                            state.dpad[binding.ordinal - Binding.GAMEPAD_DPAD_UP.ordinal] = isActionDown
                            if(isActionDown) {
                                state.dpad[Binding.GAMEPAD_DPAD_LEFT.ordinal - Binding.GAMEPAD_DPAD_UP.ordinal ] = false
                            }
                        }
                        else -> {}
                    }
                }

                if (winHandler != null) {
                    val controller = winHandler.getCurrentController()
                    if (controller != null) {
                        controller.state.copy(state)
                    }
                }
            }
        } else {
            // Handle special bindings
            if (binding == Binding.OPEN_NAVIGATION_MENU) {
                if (isActionDown) {
                    Log.d(TAG, "Opening navigation menu from controller binding")
                    onOpenNavigationMenu?.invoke()
                }
            } else if (binding == Binding.SHOW_KEYBOARD) {
                if (isActionDown) {
                    if (!showKeyboardPressed) {
                        showKeyboardPressed = true
                        Log.d(TAG, "Showing keyboard from controller binding")
                        onShowKeyboard?.invoke()
                    }
                } else {
                    showKeyboardPressed = false
                }
            } else if (binding == Binding.MOUSE_MOVE_LEFT || binding == Binding.MOUSE_MOVE_RIGHT) {
                updateMouseMoveContribution(binding, isActionDown, offset, sourceKeyCode, sourceDeviceId)
            } else if (binding == Binding.MOUSE_MOVE_DOWN || binding == Binding.MOUSE_MOVE_UP) {
                updateMouseMoveContribution(binding, isActionDown, offset, sourceKeyCode, sourceDeviceId)
            } else if (handleScrollBinding(binding, isActionDown)) {
                // Mouse wheel events are pulses, not held button state.
            } else {
                // For keyboard/mouse button bindings, inject into XServer
                val pointerButton = binding.pointerButton
                if (isActionDown) {
                    if (pointerButton != null) {
                        xServer?.injectPointerButtonPress(pointerButton)
                    } else {
                        xServer?.let { binding.inject(it, true) }
                    }
                } else {
                    if (pointerButton != null) {
                        xServer?.injectPointerButtonRelease(pointerButton)
                    } else {
                        xServer?.let { binding.inject(it, false) }
                    }
                }
            }
        }
    }

    private fun setGyroModifierPressed(
        sourceDeviceId: Int,
        sourceKeyCode: Int,
        sourceController: ExternalController?,
        pressed: Boolean,
    ) {
        val source = physicalInputSource(sourceDeviceId, sourceKeyCode, sourceController)
        val changed = if (pressed) {
            activeGyroModifierSources.add(source)
        } else {
            activeGyroModifierSources.remove(source)
        }
        if (changed) onGyroModifierChanged?.invoke(source, pressed)
    }

    private fun physicalInputSource(
        sourceDeviceId: Int,
        sourceKeyCode: Int,
        sourceController: ExternalController?,
    ): PhysicalInputSource {
        val resolvedDeviceId = if (sourceDeviceId != UNKNOWN_DEVICE_ID) {
            sourceDeviceId
        } else {
            sourceController?.deviceId ?: UNKNOWN_DEVICE_ID
        }
        return PhysicalInputSource(resolvedDeviceId, sourceKeyCode)
    }

    private fun releaseGyroModifierSources(deviceId: Int? = null) {
        activeGyroModifierSources.toList().forEach { source ->
            if (deviceId == null || source.deviceId == deviceId) {
                activeGyroModifierSources.remove(source)
                onGyroModifierChanged?.invoke(source, false)
            }
        }
    }

    private fun closeRadialMenuIfOpen(commit: Boolean) {
        if (!radialMenuPressed) return
        radialMenuPressed = false
        radialMenuOpenedFromMotion = false
        radialMenuOpenerKeyCode = KeyEvent.KEYCODE_UNKNOWN
        radialMenuOpenerDeviceId = UNKNOWN_DEVICE_ID
        onRadialMenuVectorChanged?.invoke(0f, 0f)
        onRadialMenuButtonStateChanged?.invoke(false, commit)
    }

    private fun neutralizeMotionInputs(
        exceptKeyCode: Int,
        sourceDeviceId: Int,
        controller: ExternalController?,
    ) {
        val exceptSource = if (exceptKeyCode == KeyEvent.KEYCODE_UNKNOWN) {
            null
        } else {
            physicalInputSource(sourceDeviceId, exceptKeyCode, controller)
        }
        releaseActiveAxes(exceptSource)
        neutralizeTrigger(controller, KeyEvent.KEYCODE_BUTTON_L2, exceptKeyCode)
        neutralizeTrigger(controller, KeyEvent.KEYCODE_BUTTON_R2, exceptKeyCode)
        clearMouseMoveContributions()
        clearScrollRepeats()
    }

    private fun neutralizeTrigger(controller: ExternalController?, keyCode: Int, exceptKeyCode: Int) {
        if (keyCode == exceptKeyCode) return
        val activeController = controller ?: profile?.getController("*") ?: return
        val triggerValue = when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_L2 -> activeController.state.triggerL
            KeyEvent.KEYCODE_BUTTON_R2 -> activeController.state.triggerR
            else -> 0f
        }
        if (triggerValue <= 0f) return
        activeController.getControllerBinding(keyCode)
            ?.takeIf { Binding.OPEN_RADIAL_MENU !in it.bindingCombo.bindings }
            ?.let {
                handleInputEvent(
                    it.bindingCombo,
                    false,
                    0f,
                    fromMotion = true,
                    sourceKeyCode = keyCode,
                    sourceController = activeController,
                )
            }
    }

    private fun isRadialMenuOpenerDevice(deviceId: Int): Boolean {
        return radialMenuOpenerDeviceId == UNKNOWN_DEVICE_ID || deviceId == radialMenuOpenerDeviceId
    }

    private fun handleRadialMenuNavigationKey(event: KeyEvent, keyCode: Int): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                -> true
                else -> false
            }
        }
        val vector = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> 0f to -1f
            KeyEvent.KEYCODE_DPAD_DOWN -> 0f to 1f
            KeyEvent.KEYCODE_DPAD_LEFT -> -1f to 0f
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1f to 0f
            else -> return false
        }
        onRadialMenuVectorChanged?.invoke(vector.first, vector.second)
        return true
    }

    private fun updateRadialMenuVector(controller: ExternalController) {
        val vector = radialSelectionVectorForAxes(
            controller.state.thumbLX,
            controller.state.thumbLY,
            MotionEvent.AXIS_X,
            MotionEvent.AXIS_Y,
        ) ?: radialSelectionVectorForAxes(
            controller.state.thumbRX,
            controller.state.thumbRY,
            MotionEvent.AXIS_Z,
            MotionEvent.AXIS_RZ,
        ) ?: radialSelectionVectorForAxes(
            controller.state.dPadX.toFloat(),
            controller.state.dPadY.toFloat(),
            MotionEvent.AXIS_HAT_X,
            MotionEvent.AXIS_HAT_Y,
        )

        if (vector == null) {
            onRadialMenuVectorChanged?.invoke(0f, 0f)
        } else {
            onRadialMenuVectorChanged?.invoke(vector.first, vector.second)
        }
    }

    private fun radialSelectionVectorForAxes(
        x: Float,
        y: Float,
        xAxis: Int,
        yAxis: Int,
    ): Pair<Float, Float>? {
        val filteredX = radialSelectionComponent(x, xAxis)
        val filteredY = radialSelectionComponent(y, yAxis)
        return if (filteredX == 0f && filteredY == 0f) {
            null
        } else {
            filteredX to filteredY
        }
    }

    private fun radialSelectionComponent(value: Float, axis: Int): Float {
        if (!isPhysicalAxisActive(value, axis)) return 0f
        val keyCode = ExternalControllerBinding.getKeyCodeForAxis(axis, Mathf.sign(value))
        return if (keyCode == radialMenuOpenerKeyCode) 0f else value
    }

    private fun isPhysicalAxisActive(value: Float, axis: Int): Boolean {
        val isStick = axis == MotionEvent.AXIS_X || axis == MotionEvent.AXIS_Y ||
            axis == MotionEvent.AXIS_Z || axis == MotionEvent.AXIS_RZ
        return if (isStick && profile?.isStickTuningConfigured == true) {
            value != 0f
        } else {
            Math.abs(value) > ControlElement.STICK_DEAD_ZONE
        }
    }

    private fun isRadialMenuMotionOpenerPressed(controller: ExternalController): Boolean {
        val axes = intArrayOf(
            MotionEvent.AXIS_X,
            MotionEvent.AXIS_Y,
            MotionEvent.AXIS_Z,
            MotionEvent.AXIS_RZ,
            MotionEvent.AXIS_HAT_X,
            MotionEvent.AXIS_HAT_Y,
        )
        val values = floatArrayOf(
            controller.state.thumbLX,
            controller.state.thumbLY,
            controller.state.thumbRX,
            controller.state.thumbRY,
            controller.state.dPadX.toFloat(),
            controller.state.dPadY.toFloat(),
        )

        for (i in axes.indices) {
            if (!isPhysicalAxisActive(values[i], axes[i])) continue
            val keyCode = ExternalControllerBinding.getKeyCodeForAxis(axes[i], Mathf.sign(values[i]))
            if (keyCode == radialMenuOpenerKeyCode) {
                return true
            }
        }

        return (radialMenuOpenerKeyCode == KeyEvent.KEYCODE_BUTTON_L2 && controller.state.triggerL > 0f) ||
            (radialMenuOpenerKeyCode == KeyEvent.KEYCODE_BUTTON_R2 && controller.state.triggerR > 0f)
    }
}
