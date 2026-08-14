package app.gamenative.gamepad.profiles

import app.gamenative.gamepad.FaceStyle
import app.gamenative.gamepad.GyroMode
import app.gamenative.gamepad.layers.LayerTriggerSpec
import app.gamenative.gamepad.processing.DeadzoneMode
import app.gamenative.gamepad.processing.ResponseCurve
import app.gamenative.gamepad.processing.StickTransform
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
    // U1 (spec 2026-08-14-gamepad-u1-gyro): gyro por device. null = default
    // (OFF / sensibilidade 1.0 / deadzone 0.05 rad/s / sempre ativo). Campos novos
    // com a política V1 do store (downgrade de build preserva chaves desconhecidas).
    val gyroMode: GyroMode? = null,
    val gyroSensitivity: Float? = null,
    val gyroDeadzone: Float? = null,
    /** GamepadButton.name que ativa o gyro (hold); null = sempre ativo. */
    val gyroActivateButton: String? = null,
    // U3 (spec 2026-08-14-gamepad-u3-u4-layers-remap-jogo): triggers de camada.
    val layerTriggers: Map<String, LayerTriggerSpec> = emptyMap(),
    // U5 (spec 2026-08-14-gamepad-u5-rumble): haptics do menu por device.
    val rumbleOnActivate: Boolean? = null,
    val rumbleOnBack: Boolean? = null,
    // P2-6 (spec 2026-08-14-touchpad-drag-double-tap): duplo-toque do touchpad =
    // clique direito (opt-in por perfil; null = OFF — 2 cliques, comportamento U2).
    val touchpadDoubleTapRightClick: Boolean? = null,
    // ── F1 (spec 2026-08-15-input-core-avancado) ──
    // F1.1: deadzone radial/axial POR STICK (null = RADIAL, comportamento atual) e
    // response curve (null = LINEAR). LUT = lista de pontos 0..1 serializada no JSON.
    val leftStickDeadzoneMode: DeadzoneMode? = null,
    val rightStickDeadzoneMode: DeadzoneMode? = null,
    val leftStickCurve: ResponseCurve? = null,
    val rightStickCurve: ResponseCurve? = null,
    val leftStickLut: List<Float>? = null,
    val rightStickLut: List<Float>? = null,
    // F1.2: stick DIREITO vira Flick Stick (null = OFF — stick normal).
    val flickStickEnabled: Boolean? = null,
    val flickStickActivationRadius: Float? = null,
    val flickStickSnapAngle: Float? = null,
    // F1.3: fusão Mahony (null = OFF — caminho atual byte-identical).
    val gyroFusionEnabled: Boolean? = null,
    val gyroFusionKp: Float? = null,
    val gyroFusionKi: Float? = null,
    // F3.3: versão do schema (export/import cloud-ready; chaves novas preservadas — V1).
    val schemaVersion: Int = 1,
) {
    /**
     * Limpeza 1.3-3 (doc pendentes-e-validacao-gamepad-universal): LUTs sanitizadas
     * UMA vez no LOAD do store — o hot path (StickTransform por MotionEvent) nunca
     * re-sanitiza. LUT inválida (vazia após limpeza) vira null (sem preferência).
     */
    fun withSanitizedLuts(): GamepadProfile {
        val left = leftStickLut?.let { StickTransform.sanitizeLut(it) }?.takeIf { it.isNotEmpty() }
        val right = rightStickLut?.let { StickTransform.sanitizeLut(it) }?.takeIf { it.isNotEmpty() }
        if (left == leftStickLut && right == rightStickLut) return this
        return copy(leftStickLut = left, rightStickLut = right)
    }

    /** Perfil indistinguível de "sem preferência": salvar REMOVE a entrada (padrão do repo). */
    fun isDefault(): Boolean =
        faceStyle == null &&
            swapOkCancel == null &&
            leftStickDeadzone == null &&
            rightStickDeadzone == null &&
            leftTriggerDeadzone == null &&
            rightTriggerDeadzone == null &&
            layers.isEmpty() &&
            gyroMode == null &&
            gyroSensitivity == null &&
            gyroDeadzone == null &&
            gyroActivateButton == null &&
            layerTriggers.isEmpty() &&
            rumbleOnActivate == null &&
            rumbleOnBack == null &&
            touchpadDoubleTapRightClick == null &&
            leftStickDeadzoneMode == null &&
            rightStickDeadzoneMode == null &&
            leftStickCurve == null &&
            rightStickCurve == null &&
            leftStickLut == null &&
            rightStickLut == null &&
            flickStickEnabled == null &&
            flickStickActivationRadius == null &&
            flickStickSnapAngle == null &&
            gyroFusionEnabled == null &&
            gyroFusionKp == null &&
            gyroFusionKi == null

    fun toJson(): String = json.encodeToString(GamepadProfile.serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** null = JSON inválido (degrade, nunca exceção). */
        fun fromJson(json: String): GamepadProfile? =
            runCatching { this.json.decodeFromString<GamepadProfile>(json) }.getOrNull()
    }
}
