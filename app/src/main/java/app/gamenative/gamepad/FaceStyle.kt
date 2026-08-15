package app.gamenative.gamepad

/**
 * Como desenhar/rotular os botões de face (spec 2026-08-13, Parte I §3). NUNCA muda a
 * posição física — FACE_BOTTOM é sempre o botão de baixo; a label é que varia
 * (A no Xbox, ✕ no PlayStation, B no Nintendo).
 */
enum class FaceStyle {
    XBOX,
    PLAYSTATION,
    NINTENDO,
    GENERIC,
}
