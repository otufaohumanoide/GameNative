package app.gamenative.ui.component

import android.view.KeyEvent
import app.gamenative.ui.component.SearchFieldImeLogic.KeyAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the search-field IME decision logic (spec 2026-08-10-search-field-ime-explicit-design):
 * the QuickMenu search field must never open the soft keyboard by itself when focus arrives
 * via gamepad navigation; X opens, B closes (and must not close the menu while the IME is up).
 *
 * Only compile-time KeyEvent constants are used, so no Android runtime is needed.
 */
class SearchFieldImeLogicTest {

    // ── gamepad arrival detection (real moves × programmatic stamps) ────

    @Test
    fun `recent stick move counts as gamepad arrival`() {
        assertTrue(
            SearchFieldImeLogic.arrivedViaGamepad(
                now = 1_000L,
                lastMoveAt = 900L,
                programmaticFocusAt = 0L,
                windowMs = 400L,
            )
        )
    }

    @Test
    fun `stale stick move does not count as gamepad arrival`() {
        assertFalse(
            SearchFieldImeLogic.arrivedViaGamepad(
                now = 1_000L,
                lastMoveAt = 100L,
                programmaticFocusAt = 0L,
                windowMs = 400L,
            )
        )
    }

    @Test
    fun `recent programmatic stamp counts as gamepad arrival`() {
        assertTrue(
            SearchFieldImeLogic.arrivedViaGamepad(
                now = 1_000L,
                lastMoveAt = 0L,
                programmaticFocusAt = 900L,
                windowMs = 400L,
            )
        )
    }

    @Test
    fun `stale programmatic stamp does not count as gamepad arrival`() {
        assertFalse(
            SearchFieldImeLogic.arrivedViaGamepad(
                now = 1_000L,
                lastMoveAt = 0L,
                programmaticFocusAt = 100L,
                windowMs = 400L,
            )
        )
    }

    @Test
    fun `recent move wins over an older programmatic stamp`() {
        assertTrue(
            SearchFieldImeLogic.arrivedViaGamepad(
                now = 1_000L,
                lastMoveAt = 950L,
                programmaticFocusAt = 100L,
                windowMs = 400L,
            )
        )
    }

    @Test
    fun `recent programmatic stamp wins over an older move`() {
        assertTrue(
            SearchFieldImeLogic.arrivedViaGamepad(
                now = 1_000L,
                lastMoveAt = 100L,
                programmaticFocusAt = 980L,
                windowMs = 400L,
            )
        )
    }

    @Test
    fun `move exactly at the window boundary does not count`() {
        assertFalse(
            SearchFieldImeLogic.arrivedViaGamepad(
                now = 1_000L,
                lastMoveAt = 600L,
                programmaticFocusAt = 0L,
                windowMs = 400L,
            )
        )
    }

    @Test
    fun `both stamps exactly at the window boundary do not count`() {
        assertFalse(
            SearchFieldImeLogic.arrivedViaGamepad(
                now = 1_000L,
                lastMoveAt = 600L,
                programmaticFocusAt = 600L,
                windowMs = 400L,
            )
        )
    }

    @Test
    fun `no stamp at all never counts`() {
        assertFalse(
            SearchFieldImeLogic.arrivedViaGamepad(
                now = 1_000L,
                lastMoveAt = 0L,
                programmaticFocusAt = 0L,
                windowMs = 400L,
            )
        )
    }

    // ── X opens the IME (only with the field focused and the IME closed) ──

    @Test
    fun `raw A opens the IME on a focused field`() {
        assertEquals(
            KeyAction.OpenIme,
            SearchFieldImeLogic.onKey(
                keyCode = KeyEvent.KEYCODE_BUTTON_A,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                isFocused = true,
                isImeVisible = false,
            )
        )
    }

    @Test
    fun `DPAD_CENTER opens the IME (bridged A)`() {
        assertEquals(
            KeyAction.OpenIme,
            SearchFieldImeLogic.onKey(
                keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                isFocused = true,
                isImeVisible = false,
            )
        )
    }

    @Test
    fun `ENTER opens the IME on a focused field`() {
        assertEquals(
            KeyAction.OpenIme,
            SearchFieldImeLogic.onKey(
                keyCode = KeyEvent.KEYCODE_ENTER,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                isFocused = true,
                isImeVisible = false,
            )
        )
    }

    @Test
    fun `select key with the IME already open propagates (typing keeps normal keys)`() {
        assertEquals(
            KeyAction.Propagate,
            SearchFieldImeLogic.onKey(
                keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                isFocused = true,
                isImeVisible = true,
            )
        )
    }

    @Test
    fun `select key repeats propagate`() {
        assertEquals(
            KeyAction.Propagate,
            SearchFieldImeLogic.onKey(
                keyCode = KeyEvent.KEYCODE_BUTTON_A,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 1,
                isFocused = true,
                isImeVisible = false,
            )
        )
    }

    @Test
    fun `select key up never opens`() {
        assertEquals(
            KeyAction.Propagate,
            SearchFieldImeLogic.onKey(
                keyCode = KeyEvent.KEYCODE_BUTTON_A,
                action = KeyEvent.ACTION_UP,
                repeatCount = 0,
                isFocused = true,
                isImeVisible = false,
            )
        )
    }

    @Test
    fun `unfocused field never consumes select keys`() {
        assertEquals(
            KeyAction.Propagate,
            SearchFieldImeLogic.onKey(
                keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                isFocused = false,
                isImeVisible = false,
            )
        )
    }

    // ── B closes the IME (consumed), otherwise propagates as back ────────

    @Test
    fun `B closes the IME while it is open`() {
        assertEquals(
            KeyAction.CloseIme,
            SearchFieldImeLogic.onKey(
                keyCode = KeyEvent.KEYCODE_BUTTON_B,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                isFocused = true,
                isImeVisible = true,
            )
        )
    }

    @Test
    fun `held B keeps closing while the IME is open`() {
        assertEquals(
            KeyAction.CloseIme,
            SearchFieldImeLogic.onKey(
                keyCode = KeyEvent.KEYCODE_BUTTON_B,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 2,
                isFocused = true,
                isImeVisible = true,
            )
        )
    }

    @Test
    fun `B with the IME closed propagates as hierarchical back`() {
        assertEquals(
            KeyAction.Propagate,
            SearchFieldImeLogic.onKey(
                keyCode = KeyEvent.KEYCODE_BUTTON_B,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                isFocused = true,
                isImeVisible = false,
            )
        )
    }

    @Test
    fun `B key up never closes the IME`() {
        assertEquals(
            KeyAction.Propagate,
            SearchFieldImeLogic.onKey(
                keyCode = KeyEvent.KEYCODE_BUTTON_B,
                action = KeyEvent.ACTION_UP,
                repeatCount = 0,
                isFocused = true,
                isImeVisible = true,
            )
        )
    }

    // ── everything else propagates ────────────────────────────────────────

    @Test
    fun `navigation and tab keys always propagate`() {
        for (key in intArrayOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_L2,
            KeyEvent.KEYCODE_BUTTON_R2,
        )) {
            assertEquals(
                "key $key must propagate",
                KeyAction.Propagate,
                SearchFieldImeLogic.onKey(
                    keyCode = key,
                    action = KeyEvent.ACTION_DOWN,
                    repeatCount = 0,
                    isFocused = true,
                    isImeVisible = false,
                )
            )
        }
    }
}
