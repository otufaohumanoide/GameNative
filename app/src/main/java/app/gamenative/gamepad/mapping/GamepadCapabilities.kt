package app.gamenative.gamepad.mapping

/**
 * Capacidades reais de um device de entrada (spec 2026-08-16-K3, §1.1) — a base da
 * síntese de mapping por capabilities. Espelha as capability masks do backend Android
 * da SDL3 (zlib — SDLControllerManager.java `getAxisMask`:449 / `getButtonMask`:485),
 * reimplementadas em Kotlin puro (zero `android.*`, JVM-testável): a COLETA
 * (`InputDevice.hasKeys` / `motionRanges`) vive no `GamepadHub.addDevice`; aqui só os
 * dados imutáveis.
 *
 * - [keycodes]: keycodes candidatos PRESENTES (tabela [AndroidConstants.ALL_CANDIDATE_KEYCODES]);
 * - [axes]: motionRanges com SOURCE_JOYSTICK, ordenados por axis id;
 * - [hasHat]: AXIS_HAT_X e AXIS_HAT_Y presentes;
 * - [isGamepadSource]: o device declara SOURCE_GAMEPAD.
 */
data class GamepadCapabilities(
    val keycodes: Set<Int>,
    val axes: List<Int>,
    val hasHat: Boolean,
    val isGamepadSource: Boolean,
)
