package app.gamenative.gamepad

/**
 * Classificação de um [android.view.InputDevice] antes de qualquer tratamento de evento
 * (spec 2026-08-13, Parte I §1).
 *
 * - [CONTROLLER]: gamepad/joystick real — único que entra no hot path.
 * - [TOUCHPAD]: device de controle que também expõe fonte pointer (touchpad do DS4/
 *   DualSense) — o gate de ghost input do MainActivity depende dessa distinção.
 * - [SENSOR]: device virtual (inclui HIDs só de sensores) — nunca emitido no hot path.
 * - [UNKNOWN]: teclado, mouse, resto.
 */
enum class DeviceClass { CONTROLLER, TOUCHPAD, SENSOR, UNKNOWN, VIRTUAL }
