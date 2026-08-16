package app.gamenative.gamepad.virtual

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.gamepad.DeviceClass
import app.gamenative.gamepad.FaceStyle
import app.gamenative.gamepad.GamepadAxis
import app.gamenative.gamepad.GamepadButton
import app.gamenative.gamepad.GamepadDevice
import app.gamenative.gamepad.InputEvent
import app.gamenative.gamepad.mapping.AndroidConstants
import app.gamenative.gamepad.mapping.GamepadCapabilities
import app.gamenative.gamepad.mapping.RawAxisInput
import app.gamenative.gamepad.mapping.RawKeyInput
import com.winlator.inputcontrols.Binding

/**
 * K1 (spec 2026-08-16-K1, §1.1/§1.2) — gamepad VIRTUAL de toque no pipeline do
 * fork (o "Steam Input mobile"): o overlay de toque do winlator passa a ser um
 * [GamepadDevice] de verdade no hub — camadas/expressões/radial/turbo/perfil por
 * jogo funcionam sobre o toque, como em qualquer controle físico.
 *
 * Atribuição (identidade técnica do fork — preservar):
 * - SDL3 `reference/SDL/src/joystick/virtual/SDL_virtualjoystick.c`: o dispositivo
 *   virtual ANUNCIA botões/eixos e entra no MESMO pipeline dos físicos (aqui:
 *   registro no hub + RawInput pelo caminho do hotplug);
 * - moonlight `virtual_controller/` (VirtualController injeta no MESMO
 *   ControllerHandler dos pads físicos — o OSC e o físico dividem pipeline);
 * - RetroArch `tasks/task_overlay.c` (overlay é "other input source").
 * SEMÂNTICAS reimplementadas em Kotlin — NUNCA copiar código.
 *
 * O ponto de emissão é o [ControlElement] do winlator (java): com a flag
 * `virtualGamepadPipeline` ON os bindings de GAMEPAD roteiam para
 * [TouchGamepadSource.emitBinding] em vez do caminho legado (GamepadState direto);
 * a injeção FINAL no jogo é o MESMO caminho U4 (PhysicalControllerHandler), que
 * escuta os eventos LÓGICOS deste deviceId. Teclas/mouse do overlay seguem no
 * caminho legado (TouchMouse/teclado ficam).
 */
object TouchGamepadConstants {
    /** Marcador estável (negativo = nunca um InputDevice real do Android). */
    const val DEVICE_ID = -0x7C0A1
    const val DESCRIPTOR = "gamenative-virtual-touch"
    const val MAPPING_KEY = "00000000"
    const val NAME = "Virtual touch gamepad"
}

/**
 * Conversões puras do bridge (JVM-testável): Binding do winlator ↔ RawInput do
 * hub e InputEvent lógico → Binding (injeção final no handler). O mapping do
 * virtual é a IDENTIDADE dos pads normalizados (entry `00000000` do
 * [MappingDatabase] — keycodes Android canônicos → botões semânticos), então o
 * bridge fala em keycodes/eixos reais.
 */
object TouchGamepadBridge {

    /** Binding de botão → keycode Android (só botões — dpad e face/l1/r1/l2/r2/...). */
    fun keyCodeFor(binding: Binding): Int? = when (binding) {
        Binding.GAMEPAD_BUTTON_A -> AndroidConstants.BUTTON_A
        Binding.GAMEPAD_BUTTON_B -> AndroidConstants.BUTTON_B
        Binding.GAMEPAD_BUTTON_X -> AndroidConstants.BUTTON_X
        Binding.GAMEPAD_BUTTON_Y -> AndroidConstants.BUTTON_Y
        Binding.GAMEPAD_BUTTON_L1 -> AndroidConstants.BUTTON_L1
        Binding.GAMEPAD_BUTTON_R1 -> AndroidConstants.BUTTON_R1
        Binding.GAMEPAD_BUTTON_L2 -> AndroidConstants.BUTTON_L2
        Binding.GAMEPAD_BUTTON_R2 -> AndroidConstants.BUTTON_R2
        Binding.GAMEPAD_BUTTON_SELECT -> AndroidConstants.BUTTON_SELECT
        Binding.GAMEPAD_BUTTON_START -> AndroidConstants.BUTTON_START
        Binding.GAMEPAD_BUTTON_L3 -> AndroidConstants.BUTTON_THUMBL
        Binding.GAMEPAD_BUTTON_R3 -> AndroidConstants.BUTTON_THUMBR
        Binding.GAMEPAD_DPAD_UP -> AndroidConstants.DPAD_UP
        Binding.GAMEPAD_DPAD_DOWN -> AndroidConstants.DPAD_DOWN
        Binding.GAMEPAD_DPAD_LEFT -> AndroidConstants.DPAD_LEFT
        Binding.GAMEPAD_DPAD_RIGHT -> AndroidConstants.DPAD_RIGHT
        else -> null
    }

    /**
     * Binding de stick → (eixo Android real, sinal da direção). UP/DOWN = AXIS_Y/
     * AXIS_RZ (negativo = cima, padrão MotionEvent); LEFT/RIGHT = AXIS_X/AXIS_Z.
     * O ControlElement emite o valor CRU do delta (ex.: THUMB_UP com -0.8) — o
     * bridge usa o EIXO, não a direção do binding (UP e DOWN recebem o mesmo
     * deltaY; emitir os dois duplicaria o eixo no tradutor).
     */
    fun axisFor(binding: Binding): Int? = when (binding) {
        Binding.GAMEPAD_LEFT_THUMB_UP, Binding.GAMEPAD_LEFT_THUMB_DOWN ->
            AndroidConstants.AXIS_Y
        Binding.GAMEPAD_LEFT_THUMB_LEFT, Binding.GAMEPAD_LEFT_THUMB_RIGHT ->
            AndroidConstants.AXIS_X
        Binding.GAMEPAD_RIGHT_THUMB_UP, Binding.GAMEPAD_RIGHT_THUMB_DOWN ->
            AndroidConstants.AXIS_RZ
        Binding.GAMEPAD_RIGHT_THUMB_LEFT, Binding.GAMEPAD_RIGHT_THUMB_RIGHT ->
            AndroidConstants.AXIS_Z
        else -> null
    }

    /** RawKeyInput do binding de botão (action DOWN/UP). null = não é botão. */
    fun rawKeyFor(binding: Binding, isDown: Boolean): RawKeyInput? {
        val keyCode = keyCodeFor(binding) ?: return null
        return RawKeyInput(
            deviceId = TouchGamepadConstants.DEVICE_ID,
            source = InputDevice.SOURCE_GAMEPAD,
            keyCode = keyCode,
            action = if (isDown) AndroidConstants.ACTION_DOWN else AndroidConstants.ACTION_UP,
            repeatCount = 0,
        )
    }

    /**
     * RawAxisInput do binding de stick — SÓ quando há movimento (|value| > 0); o
     * release (isDown=false) entra com 0. Retorna null para value 0 com isDown
     * true (o elemento chama por direção; o eixo par já foi emitido).
     */
    fun rawAxisFor(binding: Binding, isDown: Boolean, value: Float): RawAxisInput? {
        val axis = axisFor(binding) ?: return null
        if (!isDown && value == 0f) return null // release real (elemento solto)
        return RawAxisInput(
            deviceId = TouchGamepadConstants.DEVICE_ID,
            source = InputDevice.SOURCE_GAMEPAD,
            action = MotionEvent.ACTION_MOVE,
            axisValues = mapOf(axis to value),
        )
    }

    /** Botão LÓGICO (evento do pipeline) → Binding de injeção final. null = sem
     *  representação no overlay (GUIDE/extras — não chegam ao jogo). */
    fun bindingFor(button: GamepadButton): Binding? = when (button) {
        GamepadButton.FACE_BOTTOM -> Binding.GAMEPAD_BUTTON_A
        GamepadButton.FACE_RIGHT -> Binding.GAMEPAD_BUTTON_B
        GamepadButton.FACE_LEFT -> Binding.GAMEPAD_BUTTON_X
        GamepadButton.FACE_TOP -> Binding.GAMEPAD_BUTTON_Y
        GamepadButton.LEFT_BUMPER -> Binding.GAMEPAD_BUTTON_L1
        GamepadButton.RIGHT_BUMPER -> Binding.GAMEPAD_BUTTON_R1
        GamepadButton.LEFT_TRIGGER -> Binding.GAMEPAD_BUTTON_L2
        GamepadButton.RIGHT_TRIGGER -> Binding.GAMEPAD_BUTTON_R2
        GamepadButton.LEFT_STICK -> Binding.GAMEPAD_BUTTON_L3
        GamepadButton.RIGHT_STICK -> Binding.GAMEPAD_BUTTON_R3
        GamepadButton.START -> Binding.GAMEPAD_BUTTON_START
        GamepadButton.SELECT -> Binding.GAMEPAD_BUTTON_SELECT
        GamepadButton.DPAD_UP -> Binding.GAMEPAD_DPAD_UP
        GamepadButton.DPAD_DOWN -> Binding.GAMEPAD_DPAD_DOWN
        GamepadButton.DPAD_LEFT -> Binding.GAMEPAD_DPAD_LEFT
        GamepadButton.DPAD_RIGHT -> Binding.GAMEPAD_DPAD_RIGHT
        else -> null // GUIDE/MISC1/PADDLE/TOUCHPAD — sem binding no overlay legado
    }

    /**
     * AxisMotion lógico → (Binding de direção, |valor|) para a injeção final —
     * o par de direções do stick do overlay (UP/DOWN no mesmo eixo).
     */
    fun axisBindingsFor(axis: GamepadAxis): Pair<Binding, Binding>? = when (axis) {
        GamepadAxis.LEFT_X -> Binding.GAMEPAD_LEFT_THUMB_LEFT to Binding.GAMEPAD_LEFT_THUMB_RIGHT
        GamepadAxis.LEFT_Y -> Binding.GAMEPAD_LEFT_THUMB_UP to Binding.GAMEPAD_LEFT_THUMB_DOWN
        GamepadAxis.RIGHT_X -> Binding.GAMEPAD_RIGHT_THUMB_LEFT to Binding.GAMEPAD_RIGHT_THUMB_RIGHT
        GamepadAxis.RIGHT_Y -> Binding.GAMEPAD_RIGHT_THUMB_UP to Binding.GAMEPAD_RIGHT_THUMB_DOWN
        else -> null // triggers: o overlay legado usa L2/R2 como botão — sem eixo
    }
}

/**
 * Ponte overlay → hub (app-scoped, main thread — callbacks de touch já são).
 * Registro LAZY no primeiro evento (não no boot — device fantasma na UI de
 * settings é ruído) e remoção explícita pelo handler no cleanup (XServer
 * destruído); o próximo toque re-registra (idempotente).
 */
object TouchGamepadSource {

    @Volatile
    private var registered = false

    fun isRegistered(): Boolean = registered

    /** Registra o device virtual no hub (idempotente). Main thread. */
    fun ensureRegistered() {
        if (registered) return
        val hub = PluviaApp.gamepadHub
        val device = GamepadDevice(
            deviceId = TouchGamepadConstants.DEVICE_ID,
            descriptor = TouchGamepadConstants.DESCRIPTOR,
            vendorId = 0,
            productId = 0,
            name = TouchGamepadConstants.NAME,
            deviceClass = DeviceClass.VIRTUAL,
            faceStyle = FaceStyle.XBOX,
            // Capacidades estáticas completas: o layout do ControlsProfile restringe
            // o que EMITE (botões presentes no overlay), não o que o pipeline aceita
            // (o mapping identidade é fixo; a síntese CAPABILITIES nem é alcançada).
            capabilities = GamepadCapabilities(
                keycodes = setOf(
                    AndroidConstants.BUTTON_A, AndroidConstants.BUTTON_B,
                    AndroidConstants.BUTTON_X, AndroidConstants.BUTTON_Y,
                    AndroidConstants.BUTTON_L1, AndroidConstants.BUTTON_R1,
                    AndroidConstants.BUTTON_L2, AndroidConstants.BUTTON_R2,
                    AndroidConstants.BUTTON_THUMBL, AndroidConstants.BUTTON_THUMBR,
                    AndroidConstants.BUTTON_START, AndroidConstants.BUTTON_SELECT,
                    AndroidConstants.DPAD_UP, AndroidConstants.DPAD_DOWN,
                    AndroidConstants.DPAD_LEFT, AndroidConstants.DPAD_RIGHT,
                ),
                axes = listOf(
                    AndroidConstants.AXIS_X, AndroidConstants.AXIS_Y,
                    AndroidConstants.AXIS_Z, AndroidConstants.AXIS_RZ,
                ),
                hasHat = false,
                isGamepadSource = true,
            ),
        )
        hub.registerVirtualDevice(device)
        registered = true
    }

    /** Remove o device virtual (cleanup do XServer/handler). Idempotente. */
    fun unregister() {
        if (!registered) return
        PluviaApp.gamepadHub.unregisterVirtualDevice(TouchGamepadConstants.DEVICE_ID)
        registered = false
    }

    /**
     * Emissão do overlay (chamado pelo ControlElement com a flag ON): converte o
     * binding em RawInput e injeta no hub — o pipeline (remap/camadas/expressões/
     * radial/turbo/perfil por jogo) roda sem saber que é toque.
     */
    @JvmStatic
    fun emitBinding(binding: Binding, isDown: Boolean, offset: Float) {
        if (!PrefManager.virtualGamepadPipeline) return
        ensureRegistered()
        val hub = PluviaApp.gamepadHub
        // Botões: DOWN/UP com keycode canônico → tradução identidade.
        TouchGamepadBridge.rawKeyFor(binding, isDown)?.let { raw ->
            hub.onKey(raw)
            return
        }
        // Sticks: o valor cru do delta vira o eixo real (só quando há movimento).
        TouchGamepadBridge.rawAxisFor(binding, isDown, offset)?.let { raw ->
            hub.onAxis(raw)
        }
    }
}
