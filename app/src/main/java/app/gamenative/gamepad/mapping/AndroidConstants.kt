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
    const val BACK = 4
    const val MENU = 82
    // K4 (spec 2026-08-16-K4): keycodes/valores usados pelas entries de quirk —
    // SEARCH (SHIELD search→mode, ControllerHandler.java:1541-1543), KEYCODE_UNKNOWN
    // (guarda do alias de scanCode, §1.3.2) e os eixos CENTRADOS RX/RY (triggers do
    // DS4 BT não-padrão, ControllerHandler.java:851-859).
    const val SEARCH = 84
    const val KEYCODE_UNKNOWN = 0
    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val DPAD_CENTER = 23

    /**
     * Candidatos de botão da coleta de capabilities (spec 2026-08-16-K3, §1.1) —
     * a MESMA lista de keycodes do `getButtonMask` do backend Android da SDL3
     * (zlib — SDLControllerManager.java:485-535), com AKEYCODE_BACK/MENU/DPAD_CENTER
     * incluídos como lá. `InputDevice.hasKeys(*this)` vira UMA chamada binder no
     * hotplug (fora do hot path).
     */
    val ALL_CANDIDATE_KEYCODES: IntArray = buildList {
        add(BUTTON_A); add(BUTTON_B); add(BUTTON_X); add(BUTTON_Y)
        add(BACK); add(MENU); add(BUTTON_MODE); add(BUTTON_START)
        add(BUTTON_THUMBL); add(BUTTON_THUMBR); add(BUTTON_L1); add(BUTTON_R1)
        add(DPAD_UP); add(DPAD_DOWN); add(DPAD_LEFT); add(DPAD_RIGHT)
        add(BUTTON_SELECT); add(DPAD_CENTER)
        add(BUTTON_L2); add(BUTTON_R2); add(BUTTON_C); add(BUTTON_Z)
        for (keyCode in BUTTON_1..BUTTON_16) add(keyCode)
    }.toIntArray()

    // MotionEvent.AXIS_* (ids REAIS do SDK — javap em platforms/android-36/android.jar:
    // AXIS_X=0, AXIS_Y=1, AXIS_Z=11, AXIS_RX=12, AXIS_RY=13, AXIS_RZ=14, AXIS_HAT_X=15,
    // AXIS_HAT_Y=16, AXIS_LTRIGGER=17, AXIS_RTRIGGER=18, AXIS_GAS=22, AXIS_BRAKE=23.
    // FIX (guia universal input, bug de mapping/hub pré-K6): Z/RZ eram 2/3 (transcritos
    // da ORDEM do driver da SDL `a2`/`a3`), mas o AndroidInputAdapter chaveia
    // axisValues pelos ids REAIS do MotionEvent — o stick direito ficava morto no
    // pipeline universal (axisValues[2/3] nunca existem; AXIS_PRESSURE=2/SIZE=3).
    const val AXIS_X = 0
    const val AXIS_Y = 1
    const val AXIS_Z = 11
    const val AXIS_RZ = 14
    const val AXIS_RX = 12
    const val AXIS_RY = 13
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
