package app.gamenative.gamepad

import kotlinx.serialization.Serializable

/**
 * Eixos semânticos (spec 2026-08-13, Parte I §3): nomes em maiúsculas por convenção do
 * contrato congelado. Triggers são eixos separados dos sticks.
 */
@Serializable
enum class GamepadAxis { LEFT_X, LEFT_Y, RIGHT_X, RIGHT_Y, LEFT_TRIGGER, RIGHT_TRIGGER }
