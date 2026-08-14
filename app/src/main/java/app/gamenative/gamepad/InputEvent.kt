package app.gamenative.gamepad

/**
 * Evento LÓGICO — o vocabulário da aplicação (spec 2026-08-13, Parte I §2). Único tipo
 * que a UI/jogo consome. O tradutor (puro) converte evento cru (KeyEvent/MotionEvent)
 * neste formato; o adapter Android é um arquivo fino fora do hot path de tradução.
 *
 * Os stubs [SensorUpdate]/[TouchpadMotion] nunca são emitidos nesta missão — existem
 * para os follow-ups de gyro e touchpad sem refatoração futura.
 */
sealed interface InputEvent {
    data class ButtonDown(val deviceId: Int, val button: GamepadButton) : InputEvent
    data class ButtonUp(val deviceId: Int, val button: GamepadButton) : InputEvent
    data class AxisMotion(val deviceId: Int, val axis: GamepadAxis, val value: Float) : InputEvent
    data class DeviceAdded(val device: GamepadDevice) : InputEvent
    data class DeviceRemoved(val deviceId: Int) : InputEvent

    // Stubs — follow-ups gyro/touchpad:
    data class SensorUpdate(
        val deviceId: Int,
        val gyroX: Float,
        val gyroY: Float,
        val gyroZ: Float,
        val accelX: Float,
        val accelY: Float,
        val accelZ: Float,
    ) : InputEvent

    data class TouchpadMotion(val deviceId: Int, val x: Float, val y: Float) : InputEvent
}
