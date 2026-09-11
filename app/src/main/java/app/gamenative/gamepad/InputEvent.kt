package app.gamenative.gamepad

/**
 * Evento LÓGICO — o vocabulário da aplicação (spec 2026-08-13, Parte I §2). Único tipo
 * que a UI/jogo consome. O tradutor (puro) converte evento cru (KeyEvent/MotionEvent)
 * neste formato; o adapter Android é um arquivo fino fora do hot path de tradução.
 *
 * [TouchpadMotion] é reservado para follow-ups do touchpad lógico.
 */
sealed interface InputEvent {
    /** Identidade do device de origem (efêmera por sessão — padrão V6). */
    val deviceId: Int

    data class ButtonDown(override val deviceId: Int, val button: GamepadButton) : InputEvent
    data class ButtonUp(override val deviceId: Int, val button: GamepadButton) : InputEvent
    data class AxisMotion(override val deviceId: Int, val axis: GamepadAxis, val value: Float) : InputEvent
    data class DeviceAdded(override val deviceId: Int, val device: GamepadDevice) : InputEvent
    data class DeviceRemoved(override val deviceId: Int) : InputEvent

    // Stub — follow-up touchpad:
    data class TouchpadMotion(override val deviceId: Int, val x: Float, val y: Float) : InputEvent
}
