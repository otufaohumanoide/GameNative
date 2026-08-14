package app.gamenative.gamepad.processing

import app.gamenative.ui.component.GamepadStickDecision
import app.gamenative.ui.component.GamepadStickDirection
import app.gamenative.ui.component.GamepadStickLogic
import app.gamenative.ui.component.GamepadStickState
import kotlin.math.abs

/**
 * Converte uma amostra pós-deadzone em direção (spec 2026-08-13, Parte III —
 * Processing). O hat tem prioridade (`|v| > 0.5`); senão o stick pós-deadzone;
 * null = neutro. A decisão com estado/cooldown continua em [GamepadStickLogic.decide]
 * (RC1 preservado — re-arm abaixo do deadzone).
 */
object AnalogToDpad {

    /** Convenção Android (validada no BusJoystickFocusNavigator): Y negativo = cima. */
    fun sampleToDirection(
        sample: DeadzoneResult,
        hatX: Float,
        hatY: Float,
        deadZone: Float,
    ): GamepadStickDirection? {
        // 1. Hat vence (|v| > 0.5).
        if (abs(hatX) > 0.5f || abs(hatY) > 0.5f) {
            return when {
                hatY < -0.5f -> GamepadStickDirection.Up
                hatY > 0.5f -> GamepadStickDirection.Down
                hatX < -0.5f -> GamepadStickDirection.Left
                hatX > 0.5f -> GamepadStickDirection.Right
                else -> null
            }
        }

        // 2. Stick pós-deadzone (amostra já rescalonada pelo DeadzoneProcessor).
        if (sample.inDeadzone) return null
        return when {
            sample.y < -deadZone -> GamepadStickDirection.Up
            sample.y > deadZone -> GamepadStickDirection.Down
            sample.x < -deadZone -> GamepadStickDirection.Left
            sample.x > deadZone -> GamepadStickDirection.Right
            else -> null
        }
    }

    /** Delega a decisão completa (state + direction) para o GamepadStickLogic. */
    fun decide(
        previous: GamepadStickState,
        now: Long,
        magnitude: Float,
        direction: GamepadStickDirection?,
        deadZone: Float = 0.45f,
        cooldownMs: Long = 180L,
    ): GamepadStickDecision = GamepadStickLogic.decide(
        previous = previous,
        now = now,
        magnitude = magnitude,
        direction = direction,
        deadZone = deadZone,
        cooldownMs = cooldownMs,
    )
}
