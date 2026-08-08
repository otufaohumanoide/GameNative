package app.gamenative.ui.component

/**
 * Single source of truth for "who consumes gamepad input right now" (D3,
 * docs/superpowers/specs/2026-08-08-gamepad-input-refactoring-design.md).
 *
 * The XServerScreen computes this from its overlay states in ONE place; the key and
 * motion handlers only consult it, so a new overlay cannot silently leak gamepad
 * input to the game (P2-8).
 */
enum class OverlayInputContext {
    /** No overlay is open — gamepad input goes to the game (PhysicalControllerHandler/WinHandler). */
    NONE,

    /** An overlay (menu, dialog, editor) is open — gamepad input goes to the Compose UI. */
    OVERLAY,
}
