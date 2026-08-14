package app.gamenative.gamepad

/**
 * Classifica um device ANTES de tratar eventos (spec 2026-08-13, Parte I §1).
 *
 * Lógica pura em `object` (entrada = dados puros, sem android.*) — o adapter fino que
 * lê o [android.view.InputDevice] vive no GamepadHub. A regra é a mesma que o repo já
 * usa com sucesso em `ExternalController.isGameController` (com/winlator/inputcontrols/
 * ExternalController.java:363-391), com as fontes expressas como constantes Int.
 */
object DeviceClassifier {

    /** Fontes Android espelhadas para lógica pura (constantes reais de InputDevice). */
    const val SOURCE_GAMEPAD = 0x00000401
    const val SOURCE_JOYSTICK = 0x01000010
    const val SOURCE_CLASS_POINTER = 0x00000002

    /** Dados puros de um device — o que a classificação precisa, nada mais. */
    data class DeviceFeatures(
        val isVirtual: Boolean,
        val sources: Int,
        val hasAnyFaceButton: Boolean,
        val hasAxisX: Boolean,
        val hasAxisY: Boolean,
    )

    /**
     * CONTROLLER  = (SOURCE_GAMEPAD e tem teclas BUTTON_A/B/X/Y) OU
     *               (SOURCE_JOYSTICK e tem eixos X/Y)
     * TOUCHPAD    = CONTROLLER que também expõe SOURCE_CLASS_POINTER (touchpad do DS4)
     * SENSOR      = device virtual (nunca é controle; nunca entra no hot path)
     * UNKNOWN     = resto (teclado, mouse, joystick sem eixos, ...)
     */
    fun classify(features: DeviceFeatures): DeviceClass {
        // Virtual devices nunca são controles (gate do ExternalController) — incluem os
        // HIDs virtuais de sensores, que só reportam sensores.
        if (features.isVirtual) return DeviceClass.SENSOR

        val isController = (features.sources and SOURCE_GAMEPAD != 0 && features.hasAnyFaceButton) ||
            (features.sources and SOURCE_JOYSTICK != 0 && (features.hasAxisX || features.hasAxisY))
        if (!isController) return DeviceClass.UNKNOWN

        return if (features.sources and SOURCE_CLASS_POINTER != 0) {
            DeviceClass.TOUCHPAD
        } else {
            DeviceClass.CONTROLLER
        }
    }
}
