package app.gamenative.gamepad.mapping

import app.gamenative.gamepad.FaceStyle
import app.gamenative.gamepad.GamepadButton
import kotlinx.serialization.Serializable

/**
 * K5 (spec 2026-08-16-K5, §1.1) — autoconfig por device: o mapping RAW→LÓGICO
 * efetivo congelado em arquivo ("deste dia em diante, este controle sempre mapeia
 * assim"). Port clean-room do RetroArch (GPL-3) `reference/RetroArch/configuration.c`
 * — `config_save_autoconf_profile` (:8137, grava o perfil efetivo do port com
 * identidade driver/device/display_name/vid/pid; validação mínima em :8206-8233,
 * que recusa perfil sem ao menos B + uma direção; após salvar, limpa binds manuais
 * e RECONECTA para o perfil salvo já valer) e o matching por vid/pid + affinity
 * (`reference/RetroArch/tasks/task_autodetect.c:163` — +30 vid+pid, +20 nome exato).
 * SEMÂNTICAS reimplementadas em Kotlin, origem citada aqui — NUNCA copiar código.
 *
 * NÃO confundir com o perfil LÓGICO (`GamepadProfile` do `GamepadProfileStore`):
 * este modelo é a camada de BAIXO (raw→lógico), consumida pelo tier USER da cadeia
 * do hub (spec §1.2) e pela fase K6 (import/export no formato SDL — que consumirá
 * este store; formatos NÃO se misturam aqui, spec §1.4).
 *
 * O mapping gravado é o PRÉ-quirk (capturado no addDevice — ver
 * `GamepadHub.resolveMapping`): quirk é correção de TRANSPORTE, não preferência do
 * usuário; firmware novo com quirk novo continua sendo corrigido por cima do USER.
 */
@Serializable
data class DeviceAutoconfig(
    /** Chave natural do autoconfig — `"%04x%04x"` de vid/pid (ex.: "054c09cc"). */
    val mappingKey: String,
    /** Nome do device — APENAS display (nunca usado como chave). */
    val deviceName: String,
    /** Mapping RAW→LÓGICO efetivo no momento do save (pré-quirk). */
    val mapping: GamepadMapping,
    /** Estilo de face efetivo (rótulos dos botões de face). */
    val faceStyle: FaceStyle,
    val createdAtMs: Long,
    val schemaVersion: Int = 1,
)

/**
 * K5 §1.3.2 — validação MÍNIMA antes do save, port clean-room de
 * `configuration.c:8206-8233`: o RetroArch recusa perfil sem ao menos o botão B
 * (a posição de confirmação) e sem direção de navegação (lá, cada direção dpad OU
 * analógica; aqui a regra simplificada do spec — FACE_BOTTOM + ao menos UMA
 * direção de dpad). Sem save: diálogo de erro com o motivo.
 */
object AutoconfigValidation {

    /** Direções de navegação do dpad (posicional — ordem do enum [GamepadButton]). */
    val DPAD_DIRECTIONS = listOf(
        GamepadButton.DPAD_UP,
        GamepadButton.DPAD_DOWN,
        GamepadButton.DPAD_LEFT,
        GamepadButton.DPAD_RIGHT,
    )

    /**
     * Regra 1.3.2: válido ⇔ FACE_BOTTOM com binding de TECLA (Key/Hat — o `joykey`
     * do RetroArch; eixo NÃO conta, configuration.c:8206-8209) E ao menos uma
     * direção de dpad (navegação). B vem primeiro na checagem — mesma ordem do
     * RetroArch (checa B antes das direções).
     */
    fun validate(mapping: GamepadMapping): AutoconfigCheck = when {
        mapping.buttons[GamepadButton.FACE_BOTTOM] !is RawBinding.Key &&
            mapping.buttons[GamepadButton.FACE_BOTTOM] !is RawBinding.Hat ->
            AutoconfigCheck.Invalid(Reason.MISSING_CONFIRM)
        DPAD_DIRECTIONS.none { it in mapping.buttons } ->
            AutoconfigCheck.Invalid(Reason.MISSING_DIRECTION)
        else -> AutoconfigCheck.Valid
    }

    /** Motivo da recusa — vira o texto do diálogo de erro (1.3.2). */
    enum class Reason { MISSING_CONFIRM, MISSING_DIRECTION }
}

/** Resultado de [AutoconfigValidation.validate]. */
sealed interface AutoconfigCheck {
    data object Valid : AutoconfigCheck
    data class Invalid(val reason: AutoconfigValidation.Reason) : AutoconfigCheck
}

/** Resultado do save no hub (K5 §1.3.4) — a UI decide o diálogo a mostrar. */
sealed interface AutoconfigSaveResult {
    data class Saved(val config: DeviceAutoconfig, val overwroteExisting: Boolean) :
        AutoconfigSaveResult
    data class Invalid(val reason: AutoconfigValidation.Reason) : AutoconfigSaveResult
    data object NoDevice : AutoconfigSaveResult
}
