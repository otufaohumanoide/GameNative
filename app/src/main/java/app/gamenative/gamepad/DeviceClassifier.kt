package app.gamenative.gamepad

/**
 * Classifica um device ANTES de tratar eventos (spec 2026-08-13, Parte I §1;
 * redesign P5 — spec 2026-08-14-gamepad-upgrades-pendencias, Parte V).
 *
 * Lógica pura em `object` (entrada = dados puros, sem android.*) — o adapter fino que
 * lê o [android.view.InputDevice] vive no GamepadHub.
 *
 * P5 (sessão on-device 2026-08-14, "joystick morto no menu"): o Android NÃO tem
 * "um device = uma classe" — um único InputDevice pode ser gamepad + touchpad +
 * sensor ao mesmo tempo (DS4 fundido no MIUI: sources 0x05002513 = GAMEPAD|JOYSTICK|
 * POINTER|SENSOR, dumpsys id 24 "Wireless Controller Touchpad"). Taxonomia exclusiva
 * (CONTROLLER xor TOUCHPAD) quebrava o controle inteiro: o hub emitia eventos lógicos
 * SÓ para CONTROLLER e o gate consumia TUDO de TOUCHPAD. O modelo correto (padrão
 * SDL/androidx): o device é [DeviceClass.CONTROLLER] quando tem entrada de jogo, e a
 * presença de POINTER vira a CAPACIDADE [GamepadDevice.hasTouchpad] — não uma classe
 * alternativa. O fluxo do touchpad decide por EVENTO (source POINTER-class do evento),
 * nunca pela classe do device.
 *
 * Regras:
 * - CONTROLLER  = (SOURCE_GAMEPAD e tem teclas BUTTON_A/B/X/Y) OU
 *                 (SOURCE_JOYSTICK e tem eixos X/Y) — a MESMA de
 *                 `ExternalController.isGameController` (com/winlator/inputcontrols/
 *                 ExternalController.java:363-391). Independe de POINTER.
 * - TOUCHPAD    = sub-device de touchpad PURO: sem entrada de jogo (não passa no
 *                 CONTROLLER) mas com POINTER. Ex.: o device separado "Wireless
 *                 Controller Touchpad" de kernels que NÃO fundem. Não tem sticks nem
 *                 face buttons — nunca emite evento lógico.
 * - SENSOR      = device virtual (nunca é controle; nunca entra no hot path).
 * - UNKNOWN     = resto (teclado, mouse, joystick sem eixos, ...).
 *
 * [hasTouchpad] (capacidade) é derivada de POINTER; a UI e o forwarder do touchpad
 * consultam a flag — nunca a classe.
 */
object DeviceClassifier {

    /** Fontes Android espelhadas para lógica pura (constantes reais de InputDevice). */
    const val SOURCE_GAMEPAD = 0x00000401
    const val SOURCE_JOYSTICK = 0x01000010
    const val SOURCE_CLASS_POINTER = 0x00000002
    const val SOURCE_TOUCHSCREEN = 0x00001002
    const val SOURCE_MOUSE = 0x00002002

    /** Dados puros de um device — o que a classificação precisa, nada mais. */
    data class DeviceFeatures(
        val isVirtual: Boolean,
        val sources: Int,
        val hasAnyFaceButton: Boolean,
        val hasAxisX: Boolean,
        val hasAxisY: Boolean,
    )

    /**
     * Classe PRINCIPAL do device (P5): entrada de jogo decide CONTROLLER, POINTER
     * NÃO rebaixa — vira [hasTouchpad] para o [GamepadDevice]. TOUCHPAD só para
     * sub-device puro (pointer sem jogo).
     */
    fun classify(features: DeviceFeatures): DeviceClass {
        // Virtual devices nunca são controles (gate do ExternalController) — incluem os
        // HIDs virtuais de sensores, que só reportam sensores.
        if (features.isVirtual) return DeviceClass.SENSOR

        val isController = (features.sources and SOURCE_GAMEPAD != 0 && features.hasAnyFaceButton) ||
            (features.sources and SOURCE_JOYSTICK != 0 && (features.hasAxisX || features.hasAxisY))
        if (isController) return DeviceClass.CONTROLLER

        // Sub-device de touchpad puro (ex.: DS4 em kernels que separam os devices).
        // P5: só cai aqui quando NÃO há entrada de jogo no device. Touchscreen e mouse
        // também são POINTER-class mas NÃO são touchpad de controle — excluídos por
        // FULL-source match (o AND simples colide: SOURCE_TOUCHSCREEN 0x1002 e
        // SOURCE_TOUCHPAD 0x00100002 compartilham o bit POINTER 0x2 — fts_ts no Mi 11
        // virou TOUCHPAD por engano na sessão 2026-08-14, 12:27).
        val hasTouchscreen = features.sources and SOURCE_TOUCHSCREEN == SOURCE_TOUCHSCREEN
        val hasMouse = features.sources and SOURCE_MOUSE == SOURCE_MOUSE
        if (features.sources and SOURCE_CLASS_POINTER != 0 && !hasTouchscreen && !hasMouse) {
            return DeviceClass.TOUCHPAD
        }

        return DeviceClass.UNKNOWN
    }

    /**
     * Capacidade touchpad (P5): POINTER no device. Independente da classe — um
     * CONTROLLER fundido (DS4/MIUI) tem hasTouchpad = true.
     */
    fun hasTouchpad(features: DeviceFeatures): Boolean =
        features.sources and SOURCE_CLASS_POINTER != 0
}
