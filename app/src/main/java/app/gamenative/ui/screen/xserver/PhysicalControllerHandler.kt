package app.gamenative.ui.screen.xserver

import timber.log.Timber

import android.graphics.PointF
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.gamepad.DeviceClass
import app.gamenative.gamepad.GamepadButton
import app.gamenative.gamepad.GyroMode
import app.gamenative.gamepad.mapping.MappingDatabase
import app.gamenative.gamepad.mapping.RawBinding
import app.gamenative.gamepad.processing.DeadzoneConfig
import app.gamenative.gamepad.processing.DeadzoneProcessor
import app.gamenative.gamepad.processing.GyroStickMapping
import app.gamenative.gamepad.processing.StickSample
import app.gamenative.gamepad.remap.GamepadBindingCodec
import com.winlator.inputcontrols.Binding
import com.winlator.inputcontrols.ControlElement
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.ExternalController
import com.winlator.inputcontrols.ExternalControllerBinding
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
    private val onShowKeyboard: (() -> Unit)? = null
) {
    companion object {
        private const val SCROLL_REPEAT_INTERVAL_MS = 90L
    }

    private val TAG = "gncontrol"
    private val mouseMoveOffset = PointF(0f, 0f)
    private var mouseMoveTimer: Timer? = null
    private var scrollRepeatTimer: Timer? = null
    private val scrollRepeatLock = Any()
    private val activeScrollBindings = mutableSetOf<Binding>()
    // track which axis keycodes are currently "pressed" so we only release on actual transitions.
    // accessed only from main thread (MotionEvent dispatch + Compose lifecycle), no sync needed.
    private val activeAxisBindings = mutableSetOf<Int>()

    // Tracks whether SHOW_KEYBOARD is currently held, so onShowKeyboard fires once per press (rising edge only)
    private var showKeyboardPressed = false

    private fun releaseActiveAxes() {
        val controller = profile?.getController("*") ?: return
        for (keyCode in activeAxisBindings) {
            controller.getControllerBinding(keyCode)?.let {
                handleInputEvent(it.binding, false, 0f)
            }
        }
        activeAxisBindings.clear()
    }

    fun setProfile(profile: ControlsProfile?) {
        releaseActiveAxes()
        clearScrollRepeats()
        this.profile = profile
        Log.d(TAG, "PhysicalControllerHandler: Profile set to ${profile?.name}")

        // Cancel mouse movement timer if profile is null
        if (profile == null) {
            mouseMoveTimer?.cancel()
            mouseMoveTimer = null
            mouseMoveOffset.set(0f, 0f)
        }
    }

    /**
     * Clean up resources when handler is destroyed
     */
    fun cleanup() {
        releaseActiveAxes()
        // U4: solta bindings de eixos remapeados segurados (state limpo).
        for (binding in remappedAxisBindings.values) {
            handleInputEvent(binding, false, 0f)
        }
        remappedAxisBindings.clear()
        mouseMoveTimer?.cancel()
        mouseMoveTimer = null
        mouseMoveOffset.set(0f, 0f)
        clearScrollRepeats()
        showKeyboardPressed = false
    }

    /**
     * P5 (spec 2026-08-14-gamepad-upgrades-pendencias, Parte V): device de touchpad
     * PURO (o hub classifica TOUCHPAD — sub-device "Wireless Controller Touchpad" de
     * kernels que separam os devices do DS4) não tem sticks nem face buttons — eventos
     * dele nunca dirigem o jogo. Defesa em profundidade do gate do MainActivity para
     * rotas que não passam pelo dispatch principal (external display etc.). Com o
     * redesign do classifier (P5), o DS4 FUNDIDO do MIUI é CONTROLLER → passa; devices
     * fora do hub (teclado, touchscreen, virtual) → null → permitidos (byte-identical).
     */
    private fun isControllerTouchpadDevice(deviceId: Int): Boolean =
        PluviaApp.gamepadHub.deviceFor(deviceId)?.deviceClass == DeviceClass.TOUCHPAD

    /**
     * Handle physical controller button events.
     * Extracted from InputControlsView.onKeyEvent()
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (profile != null && event.repeatCount == 0) {
            // P5 (spec 2026-08-14-gamepad-upgrades-pendencias, Parte V): defesa em
            // profundidade do gate do MainActivity — device de touchpad PURO (classe
            // TOUCHPAD do hub, sub-device de kernels que separam os devices) nunca
            // emite entrada de jogo. O DS4 fundido do MIUI é CONTROLLER (P5) e passa.
            if (isControllerTouchpadDevice(event.deviceId)) return false
            // U4 (spec 2026-08-14-gamepad-u3-u4-layers-remap-jogo, §2.1): remap da
            // camada universal (gate ON) — binding EXPLÍCITO vence; sem binding o
            // fluxo cai no ExternalControllerBinding (byte-identical, V10).
            if (PrefManager.gamepadUniversalEnabled && applyUniversalKeyRemap(event)) {
                return true
            }
            val controller = profile?.getController(event.deviceId)
            if (controller != null) {
                val controllerBinding = controller.getControllerBinding(event.keyCode)
                if (controllerBinding != null) {
                    // Some controllers emit BOTH a digital KeyEvent for L2/R2 and an analog axis value in MotionEvent.
                    // If this physical key is mapped to a virtual trigger AND the device exposes trigger axes,
                    // ignore the KeyEvent to avoid an initial "full press" spike. MotionEvent will provide the analog value.
                    if ((event.keyCode == KeyEvent.KEYCODE_BUTTON_L2 || event.keyCode == KeyEvent.KEYCODE_BUTTON_R2) &&
                        (controllerBinding.binding == Binding.GAMEPAD_BUTTON_L2 || controllerBinding.binding == Binding.GAMEPAD_BUTTON_R2) &&
                        deviceHasTriggerAxis(event.device, event.keyCode)
                    ) {
                        return true
                    }
                    val offset = if (event.action == KeyEvent.ACTION_DOWN &&
                        (controllerBinding.binding == Binding.GAMEPAD_BUTTON_L2 || controllerBinding.binding == Binding.GAMEPAD_BUTTON_R2)
                    ) 1f else 0f
                    handleInputEvent(controllerBinding.binding, event.action == KeyEvent.ACTION_DOWN, offset)

                    val winHandler = xServer?.winHandler
                    val state = profile?.gamepadState
                    if (winHandler != null) {
                        winHandler.sendGamepadState()
                        winHandler.sendVirtualGamepadState(state)
                    }
                    return true
                }
            }
        }
        return false
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
        val binding = GamepadBindingCodec.decode(token) ?: return false
        val controller = profile?.getController(event.deviceId) ?: return false
        val isDown = event.action == KeyEvent.ACTION_DOWN

        val targetKeyCode = when (binding) {
            is RawBinding.Key -> binding.keyCode
            is RawBinding.Axis -> ExternalControllerBinding.getKeyCodeForAxis(
                binding.axis,
                binding.direction.toByte(),
            )
            // Hat não é traduzido no caminho do jogo (decisão U4 v1 — dpad via tecla).
            is RawBinding.Hat -> return true
        }
        val targetBinding = controller.getControllerBinding(targetKeyCode)
        if (targetBinding != null) {
            val offset = if (isDown &&
                (targetBinding.binding == Binding.GAMEPAD_BUTTON_L2 || targetBinding.binding == Binding.GAMEPAD_BUTTON_R2)
            ) 1f else 0f
            handleInputEvent(targetBinding.binding, isDown, offset)
            val winHandler = xServer?.winHandler
            val state = profile?.gamepadState
            if (winHandler != null) {
                winHandler.sendGamepadState()
                winHandler.sendVirtualGamepadState(state)
            }
        }
        return true
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
     * Handle physical controller analog stick and trigger events.
     * Extracted from InputControlsView.onGenericMotionEvent()
     */
    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (profile != null) {
            // P5 (spec 2026-08-14-gamepad-upgrades-pendencias, Parte V): mesma defesa
            // do onKeyEvent — device de touchpad PURO nunca dirige o jogo; o DS4
            // fundido (CONTROLLER com hasTouchpad) passa e o POINTER-class dele é
            // tratado pelo gate do MainActivity por EVENTO.
            if (isControllerTouchpadDevice(event.deviceId)) return false
            val controller = profile?.getController(event.deviceId)
            if (controller != null && controller.updateStateFromMotionEvent(event)) {
                // E2 (spec 2026-08-13-onda2 §1.7): deadzone por device via perfil da
                // camada universal — APENAS quando o perfil override explicitamente
                // (gate ON). Sem override o caminho fica byte-identical (V10): o
                // ExternalController usa a `flat` do próprio device; o fallback
                // STICK_DEAD_ZONE 0.15f não é aplicado aqui de propósito.
                if (PrefManager.gamepadUniversalEnabled) {
                    applyProfileDeadzone(controller)
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
                if (l2Remap == null) {
                    val controllerBinding = controller.getControllerBinding(KeyEvent.KEYCODE_BUTTON_L2)
                    if (controllerBinding != null) {
                        handleInputEvent(
                            controllerBinding.binding,
                            controller.state.triggerL > 0f,
                            controller.state.triggerL
                        )
                    }
                } else {
                    handleRemappedAxis(controller, KeyEvent.KEYCODE_BUTTON_L2, controller.state.triggerL, l2Remap)
                }

                if (r2Remap == null) {
                    val controllerBinding = controller.getControllerBinding(KeyEvent.KEYCODE_BUTTON_R2)
                    if (controllerBinding != null) {
                        handleInputEvent(
                            controllerBinding.binding,
                            controller.state.triggerR > 0f,
                            controller.state.triggerR
                        )
                    }
                } else {
                    handleRemappedAxis(controller, KeyEvent.KEYCODE_BUTTON_R2, controller.state.triggerR, r2Remap)
                }

                // Process analog stick input
                processJoystickInput(controller)

                val winHandler = xServer?.winHandler
                val state = profile?.gamepadState
                if (winHandler != null) {
                    winHandler.sendGamepadState()
                    winHandler.sendVirtualGamepadState(state)
                }
                return true
            }
        }
        return false
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
        profile.leftStickDeadzone?.let { dead ->
            val result = DeadzoneProcessor.process(
                StickSample(controller.state.thumbLX, controller.state.thumbLY),
                DeadzoneConfig(leftStick = dead, rightStick = dead),
            )
            controller.state.thumbLX = result.x
            controller.state.thumbLY = result.y
        }
        profile.rightStickDeadzone?.let { dead ->
            val result = DeadzoneProcessor.process(
                StickSample(controller.state.thumbRX, controller.state.thumbRY),
                DeadzoneConfig(leftStick = dead, rightStick = dead),
            )
            controller.state.thumbRX = result.x
            controller.state.thumbRY = result.y
        }
        profile.leftTriggerDeadzone?.let { dead ->
            controller.state.triggerL = DeadzoneProcessor.processAxis(controller.state.triggerL, dead)
        }
        profile.rightTriggerDeadzone?.let { dead ->
            controller.state.triggerR = DeadzoneProcessor.processAxis(controller.state.triggerR, dead)
        }
    }

    /**
     * U1/P1-2 (spec 2026-08-14-gamepad-upgrades-pendencias): CAMERA mode — mapeia a
     * VELOCIDADE angular do gyro (rad/s) em deflexão do RIGHT STICK do virtual
     * gamepad (mouse-look) e envia o estado. Controle de TAXA (padrão DS4Windows
     * MouseJoystick/JoyShockLibrary): parar de girar ⇒ deflexão volta a 0 — o modelo
     * antigo integrava deltas e o stick permanecia no último valor. Escrito por cima
     * do stick físico (único escritor do campo: processJoystickInput pula os eixos do
     * right stick com CAMERA ativo). Chamado pela main thread (sink do hub — P2-7).
     */
    fun applyCameraGyro(yawRadS: Float, pitchRadS: Float, sensitivity: Float) {
        val state = profile?.gamepadState ?: return
        state.thumbRX = GyroStickMapping.deflection(yawRadS, sensitivity)
        state.thumbRY = GyroStickMapping.deflection(pitchRadS, sensitivity)
        val winHandler = xServer?.winHandler
        if (winHandler != null) {
            winHandler.sendGamepadState()
            winHandler.sendVirtualGamepadState(state)
        }
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
                    // Skip injection if movement is below 8% deadzone to save CPU cycles
                    val magnitude = Math.sqrt((mouseMoveOffset.x * mouseMoveOffset.x + mouseMoveOffset.y * mouseMoveOffset.y).toDouble())
                    if (magnitude < 0.08) return

                    // Look up cursor speed dynamically so it updates when profile changes
                    val cursorSpeed = profile?.cursorSpeed ?: 1f
                    val deltaX = (mouseMoveOffset.x * 10 * cursorSpeed).toInt()
                    val deltaY = (mouseMoveOffset.y * 10 * cursorSpeed).toInt()
                    xServer?.injectPointerMoveDelta(deltaX, deltaY)
                }
            }, 0, 1000 / 60)
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
    private fun processJoystickInput(controller: ExternalController) {
        // Reset mouse movement offset at the start - contributions will be added during processing
        mouseMoveOffset.set(0f, 0f)

        // P1-2 (spec 2026-08-14-gamepad-upgrades-pendencias): CAMERA mode ativo ⇒ o
        // gyro controla o right stick POR CIMA do físico (padrão DS4Windows — é um
        // modo do perfil, não uma soma). Sem isto, o MotionEvent do stick físico e o
        // sink do sensor escreviam o MESMO campo (thumbRX/RY) — flicker de câmera.
        val cameraOwnsRightStick = PrefManager.gamepadUniversalEnabled &&
            PluviaApp.gamepadHub.profileFor(controller.deviceId, PluviaApp.gamepadHub.activeAppId)
                .gyroMode == GyroMode.CAMERA

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
            // P1-2: CAMERA mode ⇒ right stick físico ignorado (gyro é o único
            // escritor de thumbRX/RY do virtual gamepad enquanto o modo está ativo).
            if (cameraOwnsRightStick && (axes[i] == MotionEvent.AXIS_Z || axes[i] == MotionEvent.AXIS_RZ)) {
                continue
            }
            // U4: sticks com binding explícito na camada universal são injetados pelo
            // binding ALVO (o eixo original não passa pelo activeAxisBindings — o
            // controle de release é próprio). Hats (i >= 4) não são remapeados no
            // caminho do jogo (decisão U4 v1 — dpad via canal de tecla).
            val logicalButton = when (axes[i]) {
                MotionEvent.AXIS_X, MotionEvent.AXIS_Y -> GamepadButton.LEFT_STICK
                MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ -> GamepadButton.RIGHT_STICK
                else -> null
            }
            if (logicalButton != null && PrefManager.gamepadUniversalEnabled) {
                val token = PluviaApp.gamepadHub.layerBindingFor(controller.deviceId, logicalButton)
                if (token != null) {
                    handleRemappedAxis(controller, axes[i], values[i], token)
                    continue
                }
            }

            val posKeyCode = ExternalControllerBinding.getKeyCodeForAxis(axes[i], 1.toByte())
            val negKeyCode = ExternalControllerBinding.getKeyCodeForAxis(axes[i], (-1).toByte())

            if (Math.abs(values[i]) > ControlElement.STICK_DEAD_ZONE) {
                val activeKey = ExternalControllerBinding.getKeyCodeForAxis(axes[i], Mathf.sign(values[i]))
                val oppositeKey = if (activeKey == posKeyCode) negKeyCode else posKeyCode

                // always send press (gamepad bindings need continuous offset updates)
                activeAxisBindings.add(activeKey)
                controller.getControllerBinding(activeKey)?.let {
                    handleInputEvent(it.binding, true, values[i])
                }
                // release opposite direction (if it was active)
                if (activeAxisBindings.remove(oppositeKey)) {
                    controller.getControllerBinding(oppositeKey)?.let {
                        handleInputEvent(it.binding, false, 0f)
                    }
                }
            } else {
                // release both directions only if they were active
                if (activeAxisBindings.remove(posKeyCode)) {
                    controller.getControllerBinding(posKeyCode)?.let {
                        handleInputEvent(it.binding, false, 0f)
                    }
                }
                if (activeAxisBindings.remove(negKeyCode)) {
                    controller.getControllerBinding(negKeyCode)?.let {
                        handleInputEvent(it.binding, false, 0f)
                    }
                }
            }
        }
    }

    /** Bindings-alvo atualmente segurados de eixos remapeados (U4) — por eixo cru. */
    private val remappedAxisBindings = mutableMapOf<Int, Binding>()

    /**
     * U4: injeta o binding ALVO de um eixo remapeado pela camada universal (tecla ou
     * eixo), com o valor do eixo de origem. Libera quando o valor cai abaixo da
     * deadzone (release controlado por [remappedAxisBindings] — o eixo original não
     * passa pelo fluxo normal).
     */
    private fun handleRemappedAxis(
        controller: ExternalController,
        sourceAxis: Int,
        value: Float,
        token: String,
    ) {
        val binding = GamepadBindingCodec.decode(token) ?: return
        val targetKeyCode = when (binding) {
            is RawBinding.Key -> binding.keyCode
            is RawBinding.Axis -> ExternalControllerBinding.getKeyCodeForAxis(
                binding.axis,
                binding.direction.toByte(),
            )
            is RawBinding.Hat -> return
        }
        val target = controller.getControllerBinding(targetKeyCode) ?: return
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
     * Apply a binding to the virtual gamepad state and send to WinHandler.
     * Extracted from InputControlsView.handleInputEvent()
     */
    // offset: analog axis value for presses; must be 0f for releases (triggers use offset > 0f
    // to determine pressed state, sticks gate on isActionDown, everything else ignores offset)
    private fun handleInputEvent(binding: Binding, isActionDown: Boolean, offset: Float = 0f) {
        if (binding == Binding.NONE) return

        if (binding.isGamepad) {
            val winHandler = xServer?.winHandler
            val state = profile?.gamepadState

            if (state != null) {
                val buttonIdx = binding.ordinal - Binding.GAMEPAD_BUTTON_A.ordinal
                if (buttonIdx <= ExternalController.IDX_BUTTON_R2.toInt()) {
                    when (buttonIdx) {
                        ExternalController.IDX_BUTTON_L2.toInt() -> {
                            state.triggerL = offset
                            state.setPressed(ExternalController.IDX_BUTTON_L2.toInt(), offset > 0f)
                        }
                        ExternalController.IDX_BUTTON_R2.toInt() -> {
                            state.triggerR = offset
                            state.setPressed(ExternalController.IDX_BUTTON_R2.toInt(), offset > 0f)
                        }
                        else -> state.setPressed(buttonIdx, isActionDown)
                    }
                }
                else {
                    when (binding) {
                        Binding.GAMEPAD_LEFT_THUMB_UP, Binding.GAMEPAD_LEFT_THUMB_DOWN -> {
                            state.thumbLY = if (isActionDown) offset else 0f
                        }
                        Binding.GAMEPAD_LEFT_THUMB_LEFT, Binding.GAMEPAD_LEFT_THUMB_RIGHT -> {
                            state.thumbLX = if (isActionDown) offset else 0f
                        }
                        Binding.GAMEPAD_RIGHT_THUMB_UP, Binding.GAMEPAD_RIGHT_THUMB_DOWN -> {
                            state.thumbRY = if (isActionDown) offset else 0f
                        }
                        Binding.GAMEPAD_RIGHT_THUMB_LEFT, Binding.GAMEPAD_RIGHT_THUMB_RIGHT -> {
                            state.thumbRX = if (isActionDown) offset else 0f
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
                // Handle horizontal mouse movement - ADD contribution from this input
                if (isActionDown) {
                    val contribution = if (offset != 0f) offset else if (binding == Binding.MOUSE_MOVE_LEFT) -1f else 1f
                    mouseMoveOffset.x += contribution
                    createMouseMoveTimer()
                }
                // Don't reset when isActionDown=false - mouseMoveOffset is reset at the start of processJoystickInput
            } else if (binding == Binding.MOUSE_MOVE_DOWN || binding == Binding.MOUSE_MOVE_UP) {
                // Handle vertical mouse movement - ADD contribution from this input
                if (isActionDown) {
                    val contribution = if (offset != 0f) offset else if (binding == Binding.MOUSE_MOVE_UP) -1f else 1f
                    mouseMoveOffset.y += contribution
                    createMouseMoveTimer()
                }
                // Don't reset when isActionDown=false - mouseMoveOffset is reset at the start of processJoystickInput
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
}
