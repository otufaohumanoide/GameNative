package app.gamenative.gamepad.mapping

import app.gamenative.gamepad.FaceStyle
import app.gamenative.gamepad.GamepadAxis
import app.gamenative.gamepad.GamepadButton

/**
 * O dicionário que traduz um MODELO de controle (vendor+product) para o vocabulário
 * semântico (spec 2026-08-13, Parte I §4): [buttons] e [axes] descrevem ONDE o controle
 * físico emite cada botão/eixo, e [faceStyle] responde "como desenhar/rotular".
 */
data class GamepadMapping(
    val mappingKey: String,
    val name: String,
    val faceStyle: FaceStyle,
    val buttons: Map<GamepadButton, RawBinding>,
    val axes: Map<GamepadAxis, RawBinding>,
)
