package app.gamenative.gamepad.mapping

import app.gamenative.gamepad.FaceStyle
import app.gamenative.gamepad.GamepadAxis
import app.gamenative.gamepad.GamepadButton

data class GamepadMapping(
    val mappingKey: String,
    val name: String,
    val faceStyle: FaceStyle,
    val buttons: Map<GamepadButton, RawBinding>,
    val axes: Map<GamepadAxis, RawBinding>,
) {
    /**
     * Botão de CONFIRMAÇÃO do estilo do controle (spec 2026-08-13-onda2 §1.6 — Fase 6
     * do spec original): a posição semântica que dispara "OK" no menu.
     *
     * - XBOX/PLAYSTATION/GENERIC: FACE_BOTTOM (A no Xbox, ✕ no PlayStation).
     * - NINTENDO: FACE_RIGHT (o botão de confirmar fica À DIREITA no layout Nintendo).
     * - `swapOkCancel` (opção `menu_swap_ok_cancel_buttons` do RetroArch, global ou por
     *   perfil) INVERTE a escolha.
     */
    fun confirmButton(swapOkCancel: Boolean): GamepadButton {
        val natural = when (faceStyle) {
            FaceStyle.NINTENDO -> GamepadButton.FACE_RIGHT
            else -> GamepadButton.FACE_BOTTOM
        }
        return if (swapOkCancel) otherFaceButton(natural) else natural
    }

    /**
     * O keycode cru que dispara a confirmação (ex.: KEYCODE_BUTTON_A=96 no Xbox).
     * null = o botão de confirmação não tem binding de tecla (ex.: mapeado em eixo).
     */
    fun confirmKeyCode(swapOkCancel: Boolean): Int? =
        (buttons[confirmButton(swapOkCancel)] as? RawBinding.Key)?.keyCode

    /**
     * O botão de CANCELAR do estilo (spec 2026-08-14, U6): o OUTRO face button do
     * [confirmButton] — para Xbox/PlayStation/Generic é FACE_RIGHT (B), para Nintendo
     * é FACE_BOTTOM (o botão de baixo cancela no layout Nintendo); `swapOkCancel`
     * inverte ambos. Simétrico ao confirm: uma superfície de menu que trata "back"
     * deve responder a este botão, não ao raw BUTTON_B posicional.
     */
    fun cancelButton(swapOkCancel: Boolean): GamepadButton =
        otherFaceButton(confirmButton(swapOkCancel))

    private fun otherFaceButton(button: GamepadButton): GamepadButton = when (button) {
        GamepadButton.FACE_BOTTOM -> GamepadButton.FACE_RIGHT
        GamepadButton.FACE_RIGHT -> GamepadButton.FACE_BOTTOM
        else -> button
    }
}
