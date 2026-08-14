package app.gamenative.gamepad.mapping

import app.gamenative.gamepad.GamepadAxis
import app.gamenative.gamepad.InputEvent
import app.gamenative.gamepad.processing.DeadzoneConfig
import app.gamenative.gamepad.processing.DeadzoneProcessor
import app.gamenative.gamepad.processing.StickSample

/**
 * Tradutor cru → lógico (spec 2026-08-13, Parte I §2 e Passo 4). Objeto PURO e
 * sem estado: o AndroidAdapter (fino) converte KeyEvent/MotionEvent em
 * [RawKeyInput]/[RawAxisInput]; o GamepadHub é o dono do estado entre amostras
 * (transições de hat/botão-de-eixo), nunca este tradutor.
 *
 * Regras (correção D4):
 * - `mapping.buttons` invertido por keyCode (o dicionário é a fonte da verdade);
 * - ACTION_DOWN=0 → ButtonDown somente com repeatCount == 0; ACTION_UP=1 → ButtonUp;
 * - hats (AXIS_HAT_X/Y=15/16) → DPAD buttons via bitmask SDL; o estado da amostra é
 *   descrito por completo (Down quando ativo, Up quando neutro) e o hub deduplica em
 *   transições (padrão do backend Android da SDL — `dpad_state`);
 * - botões dirigidos por meia-eixo (`dpup:-a1` do PlayStation Classic): ativo quando
 *   `valor × direção ≥ 0.5`;
 * - triggers: AXIS_LTRIGGER/RTRIGGER (17/18) e BRAKE/GAS (23/22), conforme o mapping;
 * - deadzone via DeadzoneProcessor (o DeadzoneConfig duplicado de mapping/ foi
 *   removido — existe UM, em processing/).
 */
object EventTranslator {

    /** Máscara do hat atual (SDL: 1=up, 2=right, 4=down, 8=left); 0 = neutro. */
    fun hatMask(hatX: Float, hatY: Float): Int {
        var mask = 0
        if (hatY < -0.5f) mask = mask or MappingParser.HAT_UP
        if (hatY > 0.5f) mask = mask or MappingParser.HAT_DOWN
        if (hatX < -0.5f) mask = mask or MappingParser.HAT_LEFT
        if (hatX > 0.5f) mask = mask or MappingParser.HAT_RIGHT
        return mask
    }

    fun translateKey(raw: RawKeyInput, mapping: GamepadMapping): List<InputEvent> {
        val button = mapping.buttons.entries
            .firstOrNull { (_, binding) ->
                binding is RawBinding.Key && binding.keyCode == raw.keyCode
            }
            ?.key ?: return emptyList()

        return when (raw.action) {
            AndroidConstants.ACTION_DOWN ->
                if (raw.repeatCount == 0) listOf(InputEvent.ButtonDown(raw.deviceId, button)) else emptyList()
            AndroidConstants.ACTION_UP -> listOf(InputEvent.ButtonUp(raw.deviceId, button))
            else -> emptyList()
        }
    }

    fun translateAxis(raw: RawAxisInput, mapping: GamepadMapping, deadzones: DeadzoneConfig): List<InputEvent> {
        val events = mutableListOf<InputEvent>()

        // 1. Hats → botões DPAD (estado COMPLETO da amostra: Down quando a máscara casa,
        // Up quando não — o hub deduplica em transições, padrão SDL Android `dpad_state`).
        val hatX = raw.axisValues[AndroidConstants.AXIS_HAT_X] ?: 0f
        val hatY = raw.axisValues[AndroidConstants.AXIS_HAT_Y] ?: 0f
        val mask = hatMask(hatX, hatY)
        for ((button, binding) in mapping.buttons) {
            if (binding !is RawBinding.Hat) continue
            val active = (mask and binding.mask) != 0
            events += if (active) {
                InputEvent.ButtonDown(raw.deviceId, button)
            } else {
                InputEvent.ButtonUp(raw.deviceId, button)
            }
        }

        // 2. Botões dirigidos por meia-eixo (`dpup:-a1`): ativos quando valor×direção ≥ 0.5.
        for ((button, binding) in mapping.buttons) {
            if (binding !is RawBinding.Axis) continue
            val value = raw.axisValues[binding.axis] ?: continue
            val active = value * binding.direction >= 0.5f
            events += if (active) {
                InputEvent.ButtonDown(raw.deviceId, button)
            } else {
                InputEvent.ButtonUp(raw.deviceId, button)
            }
        }

        // 3. Eixos semânticos → AxisMotion com deadzone (sticks em par, triggers por eixo).
        emitStickAxis(events, raw, mapping, deadzones, GamepadAxis.LEFT_X, GamepadAxis.LEFT_Y, deadzones.leftStick)
        emitStickAxis(events, raw, mapping, deadzones, GamepadAxis.RIGHT_X, GamepadAxis.RIGHT_Y, deadzones.rightStick)
        emitTrigger(events, raw, mapping, deadzones, GamepadAxis.LEFT_TRIGGER, deadzones.leftTrigger)
        emitTrigger(events, raw, mapping, deadzones, GamepadAxis.RIGHT_TRIGGER, deadzones.rightTrigger)

        return events
    }

    /** Emite AxisMotion para um par de eixos de stick (deadzone radial/axial em par). */
    private fun emitStickAxis(
        events: MutableList<InputEvent>,
        raw: RawAxisInput,
        mapping: GamepadMapping,
        deadzones: DeadzoneConfig,
        xAxis: GamepadAxis,
        yAxis: GamepadAxis,
        deadzone: Float,
    ) {
        val xBinding = mapping.axes[xAxis] as? RawBinding.Axis ?: return
        val yBinding = mapping.axes[yAxis] as? RawBinding.Axis ?: return
        val xRaw = raw.axisValues[xBinding.axis] ?: return
        val yRaw = raw.axisValues[yBinding.axis] ?: return
        // O par usa a deadzone do stick em questão: process() lê config.leftStick, então
        // o tradutor passa um config ajustado (documentado no DeadzoneProcessor).
        val config = deadzones.copy(leftStick = deadzone, rightStick = deadzone)
        val result = DeadzoneProcessor.process(
            StickSample(xRaw * xBinding.direction, yRaw * yBinding.direction),
            config,
        )
        if (result.inDeadzone) return
        events += InputEvent.AxisMotion(raw.deviceId, xAxis, result.x)
        events += InputEvent.AxisMotion(raw.deviceId, yAxis, result.y)
    }

    /** Emite AxisMotion para um trigger (axial, 0..1 rescalonado). */
    private fun emitTrigger(
        events: MutableList<InputEvent>,
        raw: RawAxisInput,
        mapping: GamepadMapping,
        deadzones: DeadzoneConfig,
        axis: GamepadAxis,
        deadzone: Float,
    ) {
        val binding = mapping.axes[axis] as? RawBinding.Axis ?: return
        val value = raw.axisValues[binding.axis] ?: return
        val processed = DeadzoneProcessor.processAxis(value * binding.direction, deadzone)
        if (processed == 0f) return
        events += InputEvent.AxisMotion(raw.deviceId, axis, processed)
    }
}
