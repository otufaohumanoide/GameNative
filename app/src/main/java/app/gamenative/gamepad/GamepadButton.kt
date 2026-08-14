package app.gamenative.gamepad

/**
 * Vocabulário SEMÂNTICO de botões (spec 2026-08-13, Parte I §3): o que o botão É no
 * controle (posição), nunca a label de um fabricante. FACE_BOTTOM é o botão de baixo
 * (A no Xbox, ✕ no PlayStation, B no Nintendo) — a label vem do [FaceStyle].
 */
enum class GamepadButton {
    FACE_BOTTOM,
    FACE_RIGHT,
    FACE_LEFT,
    FACE_TOP,
    DPAD_UP,
    DPAD_DOWN,
    DPAD_LEFT,
    DPAD_RIGHT,
    LEFT_BUMPER,
    RIGHT_BUMPER,
    LEFT_TRIGGER,
    RIGHT_TRIGGER,
    LEFT_STICK,
    RIGHT_STICK,
    START,
    SELECT,
    GUIDE,
}
