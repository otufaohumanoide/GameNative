package app.gamenative.gamepad

import kotlinx.serialization.Serializable

/**
 * Vocabulário SEMÂNTICO de botões (spec 2026-08-13, Parte I §3): o que o botão É no
 * controle (posição), nunca a label de um fabricante. FACE_BOTTOM é o botão de baixo
 * (A no Xbox, ✕ no PlayStation, B no Nintendo) — a label vem do [FaceStyle].
 */
@Serializable
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
    // K3 (spec 2026-08-16-K3, §1.4): botões EXTRAS do SDL_GameControllerDB — análogos
    // do enum SDL3 (zlib — SDL_gamepad.h, SDL_GamepadButton): MISC1 = botão
    // adicional (share do Xbox Series, mute do DualSense, capture do Switch Pro);
    // PADDLE_1..4 = paddles traseiros (ordem posicional `paddle1..paddle4` do DB);
    // TOUCHPAD = clique do touchpad (PS4/PS5). APPEND no fim do enum — os nomes
    // serializam no perfil por `.name`, nunca por ordinal.
    MISC1,
    PADDLE_1,
    PADDLE_2,
    PADDLE_3,
    PADDLE_4,
    TOUCHPAD,
}
