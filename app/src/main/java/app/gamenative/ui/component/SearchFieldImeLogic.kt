package app.gamenative.ui.component

import android.view.KeyEvent

/**
 * Pure decision logic for the QuickMenu search-field IME (spec
 * 2026-08-10-search-field-ime-explicit-design).
 *
 * UX practice: a soft keyboard must only appear on explicit intent — X (A / DPAD_CENTER /
 * ENTER) opens it, B closes it, and merely navigating (stick/hat/walk-down) onto the field
 * never shows it. The Compose modifier in [ScreenEffectsPanel] is a thin wrapper over these
 * functions; everything here takes plain [KeyEvent] ints so it is unit-testable on the JVM.
 */
object SearchFieldImeLogic {

    /** What the search-field key handler decides for one key event. */
    enum class KeyAction {
        /** Show the IME and consume the event (X with the IME closed). */
        OpenIme,

        /** Hide the IME and consume the event (B with the IME open — the menu must NOT close). */
        CloseIme,

        /** Not our concern: the event flows to the parent (back, navigation, typing…). */
        Propagate,
    }

    /**
     * True when focus most likely landed on the field through gamepad navigation — either a
     * REAL move ([lastMoveAt]: stick/hat/DPAD key) or a PROGRAMMATIC bootstrap/restore
     * ([programmaticFocusAt]: menu-open walk-down or guardian restore) — rather than touch,
     * i.e. when the soft keyboard must NOT show itself.
     *
     * The two clocks are separate (spec 2026-08-12 follow-ups, Missão B): real moves stamp
     * [GamepadNavigationClock.lastMoveAt] (read by the dedupe and the guardians, which must
     * never see a programmatic stamp) while bootstraps/restores stamp
     * [GamepadNavigationClock.programmaticFocusAt]. Either one inside [windowMs] means the
     * landing was not explicit intent, so the most recent of the two decides.
     */
    fun arrivedViaGamepad(
        now: Long,
        lastMoveAt: Long,
        programmaticFocusAt: Long,
        windowMs: Long,
    ): Boolean {
        val lastActivityAt = maxOf(lastMoveAt, programmaticFocusAt)
        return lastActivityAt != 0L && now - lastActivityAt < windowMs
    }

    /**
     * Decides what one key event should do on the focused search field.
     *
     * - X (A / DPAD_CENTER / ENTER, first down, field focused, IME closed) → [KeyAction.OpenIme]
     * - Same keys with the IME open → [KeyAction.Propagate] (ENTER must keep its Done action,
     *   and typing must never be disturbed).
     * - B with the IME open → [KeyAction.CloseIme] (consumed so the innermost surface wins and
     *   the menu does not close — the same hierarchy as `gamepadBackHandler`).
     * - B with the IME closed → [KeyAction.Propagate] (hierarchical back as usual).
     */
    fun onKey(
        keyCode: Int,
        action: Int,
        repeatCount: Int,
        isFocused: Boolean,
        isImeVisible: Boolean,
    ): KeyAction {
        if (!isFocused) return KeyAction.Propagate
        if (action != KeyEvent.ACTION_DOWN) return KeyAction.Propagate
        return when {
            keyCode == KeyEvent.KEYCODE_BUTTON_B -> {
                if (isImeVisible) KeyAction.CloseIme else KeyAction.Propagate
            }
            !isImeVisible && repeatCount == 0 && (
                keyCode == KeyEvent.KEYCODE_BUTTON_A ||
                    keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == KeyEvent.KEYCODE_ENTER
                ) -> KeyAction.OpenIme

            else -> KeyAction.Propagate
        }
    }
}
