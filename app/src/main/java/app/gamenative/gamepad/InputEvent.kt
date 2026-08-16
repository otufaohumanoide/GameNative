package app.gamenative.gamepad

/**
 * Evento LÓGICO — o vocabulário da aplicação (spec 2026-08-13, Parte I §2). Único tipo
 * que a UI/jogo consome. O tradutor (puro) converte evento cru (KeyEvent/MotionEvent)
 * neste formato; o adapter Android é um arquivo fino fora do hot path de tradução.
 *
 * [SensorUpdate] é emitido pelo `GamepadHub.onSensorSample` (U1 — gyro/accel por
 * device); [TouchpadMotion] é reservado para follow-ups do touchpad lógico.
 */
sealed interface InputEvent {
    /** Identidade do device de origem (efêmera por sessão — padrão V6). */
    val deviceId: Int

    data class ButtonDown(override val deviceId: Int, val button: GamepadButton) : InputEvent
    data class ButtonUp(override val deviceId: Int, val button: GamepadButton) : InputEvent
    data class AxisMotion(override val deviceId: Int, val axis: GamepadAxis, val value: Float) : InputEvent
    data class DeviceAdded(override val deviceId: Int, val device: GamepadDevice) : InputEvent
    data class DeviceRemoved(override val deviceId: Int) : InputEvent

    // Stubs — follow-ups gyro/touchpad:
    data class SensorUpdate(
        override val deviceId: Int,
        val gyroX: Float,
        val gyroY: Float,
        val gyroZ: Float,
        val accelX: Float,
        val accelY: Float,
        val accelZ: Float,
    ) : InputEvent

    data class TouchpadMotion(override val deviceId: Int, val x: Float, val y: Float) : InputEvent
}
