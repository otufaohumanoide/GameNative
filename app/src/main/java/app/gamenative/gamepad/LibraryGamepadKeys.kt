package app.gamenative.gamepad

import android.view.KeyEvent
import app.gamenative.gamepad.mapping.GamepadMapping
import app.gamenative.gamepad.mapping.RawBinding

/**
 * Conjunto de keycodes LÓGICOS de uma superfície de biblioteca/menu (spec 2026-08-14,
 * U6 — doc de intuito, U6): a LibraryScreen era a última superfície que usava raw
 * keycodes posicionais, ignorando FaceStyle/swapOkCancel e o mapping por
 * vendor/product da camada universal.
 *
 * Lógica pura em `object` (V5): [resolve] recebe o [GamepadMapping] (ou null) e devolve
 * o conjunto de keycodes que a superfície deve ouvir. Sem mapping (device desconhecido
 * — teclado, mouse, controle fora do hub) o resultado é o fallback raw atual →
 * **byte-identical** (V10). Botão lógico sem binding de tecla no mapping (ex.: mapeado
 * em eixo) também cai no fallback raw do mesmo botão.
 *
 * As constantes [KeyEvent] são compile-time (inlined) — sem dependência Android em
 * runtime, JVM-testável.
 */
data class LibraryKeySet(
    /** Keycode que CONFIRMA o item focado (FaceStyle + swap). */
    val confirmKey: Int,
    /** Keycode que CANCELA/volta (o OUTRO face button — simétrico ao confirm). */
    val cancelKey: Int,
    val yKey: Int,
    val xKey: Int,
    val l1Key: Int,
    val r1Key: Int,
    val selectKey: Int,
    val startKey: Int,
) {
    companion object {
        /** Fallback raw (Xbox posicional) — comportamento histórico da LibraryScreen. */
        val FALLBACK = LibraryKeySet(
            confirmKey = KeyEvent.KEYCODE_BUTTON_A,
            cancelKey = KeyEvent.KEYCODE_BUTTON_B,
            yKey = KeyEvent.KEYCODE_BUTTON_Y,
            xKey = KeyEvent.KEYCODE_BUTTON_X,
            l1Key = KeyEvent.KEYCODE_BUTTON_L1,
            r1Key = KeyEvent.KEYCODE_BUTTON_R1,
            selectKey = KeyEvent.KEYCODE_BUTTON_SELECT,
            startKey = KeyEvent.KEYCODE_BUTTON_START,
        )
    }
}

object LibraryGamepadKeys {

    fun resolve(mapping: GamepadMapping?, swapOkCancel: Boolean): LibraryKeySet {
        if (mapping == null) return LibraryKeySet.FALLBACK

        fun keyOf(button: GamepadButton, fallback: Int): Int =
            (mapping.buttons[button] as? RawBinding.Key)?.keyCode ?: fallback

        val confirm = mapping.confirmButton(swapOkCancel)
        val cancel = mapping.cancelButton(swapOkCancel)
        return LibraryKeySet(
            confirmKey = keyOf(confirm, KeyEvent.KEYCODE_BUTTON_A),
            cancelKey = keyOf(cancel, KeyEvent.KEYCODE_BUTTON_B),
            yKey = keyOf(GamepadButton.FACE_TOP, KeyEvent.KEYCODE_BUTTON_Y),
            xKey = keyOf(GamepadButton.FACE_LEFT, KeyEvent.KEYCODE_BUTTON_X),
            l1Key = keyOf(GamepadButton.LEFT_BUMPER, KeyEvent.KEYCODE_BUTTON_L1),
            r1Key = keyOf(GamepadButton.RIGHT_BUMPER, KeyEvent.KEYCODE_BUTTON_R1),
            selectKey = keyOf(GamepadButton.SELECT, KeyEvent.KEYCODE_BUTTON_SELECT),
            startKey = keyOf(GamepadButton.START, KeyEvent.KEYCODE_BUTTON_START),
        )
    }
}
