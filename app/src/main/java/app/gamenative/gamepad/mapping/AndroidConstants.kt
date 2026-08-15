package app.gamenative.gamepad.mapping

/**
 * Tabela física verdadeira do Android (spec 2026-08-13, Parte I §5) espelhada como
 * constantes Int para a lógica pura (JVM) — NUNCA inventar keycodes fora desta tabela.
 * Valores verificados no SDK e no backend Android da SDL (SDL_sysjoystick.c:38-160).
 */
object AndroidConstants {
    // KeyEvent.KEYCODE_*
    const val BUTTON_A = 96
    const val BUTTON_B = 97
    const val BUTTON_X = 99
    const val BUTTON_Y = 100
    const val BUTTON_L1 = 102
    const val BUTTON_R1 = 103
    const val BUTTON_L2 = 104
    const val BUTTON_R2 = 105
    const val BUTTON_THUMBL = 106
    const val BUTTON_THUMBR = 107
    const val BUTTON_START = 108
    const val BUTTON_SELECT = 109
    const val BUTTON_MODE = 110
    const val BUTTON_C = 111
    const val BUTTON_Z = 112
    const val BUTTON_1 = 188
    const val BUTTON_16 = 203
    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22

    // MotionEvent.AXIS_*
    const val AXIS_X = 0
    const val AXIS_Y = 1
    const val AXIS_Z = 2
    const val AXIS_RZ = 3
    const val AXIS_HAT_X = 15
    const val AXIS_HAT_Y = 16
    const val AXIS_LTRIGGER = 17
    const val AXIS_RTRIGGER = 18
    const val AXIS_GAS = 22
    const val AXIS_BRAKE = 23

    // KeyEvent.ACTION_*
    const val ACTION_DOWN = 0
    const val ACTION_UP = 1
}
