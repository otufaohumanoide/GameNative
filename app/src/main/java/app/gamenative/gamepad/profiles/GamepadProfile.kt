package app.gamenative.gamepad.profiles

import app.gamenative.gamepad.FaceStyle
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Camadas de ação (spec 2026-08-13, Parte I §7 — conceito Steam Input adotado SÓ no
 * modelo de dados). A camada [MENU] é resolvida de graça pelo OverlayInputState
 * existente (menu aberto vs jogo); camadas completas (chords/toggles) são follow-up.
 */
enum class ActionLayer { DEFAULT, MENU }

/**
 * Preferências do usuário sobre um controle (spec 2026-08-13, Parte III — Profiles).
 *
 * Escopos: per-device (chave = mappingKey do modelo) e per-jogo (chave = appId do
 * container); o merge é device → game, game vence (padrão Steam Input/Dolphin) — ver
 * [GamepadProfileStore.merged].
 *
 * `layers` guarda `GamepadButton.name → binding serializado` por camada
 * (serialização via GamepadBindingCodec).
 */
@Serializable
data class GamepadProfile(
    val faceStyle: FaceStyle? = null, // null = do mapping
    val swapOkCancel: Boolean? = null, // null = PrefManager.gamepadSwapOkCancel
    val leftStickDeadzone: Float? = null, // null = PrefManager.gamepadStickDeadzone
    val rightStickDeadzone: Float? = null,
    val leftTriggerDeadzone: Float? = null,
    val rightTriggerDeadzone: Float? = null,
    val layers: Map<String, Map<String, String>> = emptyMap(),
) {
    /** Perfil indistinguível de "sem preferência": salvar REMOVE a entrada (padrão do repo). */
    fun isDefault(): Boolean =
        faceStyle == null &&
            swapOkCancel == null &&
            leftStickDeadzone == null &&
            rightStickDeadzone == null &&
            leftTriggerDeadzone == null &&
            rightTriggerDeadzone == null &&
            layers.isEmpty()

    fun toJson(): String = json.encodeToString(GamepadProfile.serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** null = JSON inválido (degrade, nunca exceção). */
        fun fromJson(json: String): GamepadProfile? =
            runCatching { this.json.decodeFromString<GamepadProfile>(json) }.getOrNull()
    }
}
