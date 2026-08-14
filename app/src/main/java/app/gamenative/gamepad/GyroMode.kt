package app.gamenative.gamepad

/**
 * Modos do gyro (spec 2026-08-14-gamepad-u1-gyro, §1.2 — doc de intuito U1):
 * - [OFF]: desligado (default — nada muda no comportamento);
 * - [MOUSE]: rotação → deltas de cursor (XServer injectPointerMoveDelta);
 * - [CAMERA]: rotação → right stick do virtual gamepad (mouse-look no jogo).
 */
enum class GyroMode { OFF, MOUSE, CAMERA }
