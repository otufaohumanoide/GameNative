package app.gamenative.gamepad.mapping

import app.gamenative.gamepad.FaceStyle
import app.gamenative.gamepad.GamepadAxis
import app.gamenative.gamepad.GamepadButton
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AutoconfigValidation (spec 2026-08-16-K5, §1.3.2): validação mínima antes do save,
 * port clean-room de RetroArch configuration.c:8206-8233 — recusa perfil sem ao
 * menos FACE_BOTTOM (confirmação) + uma direção de dpad (navegação).
 */
class AutoconfigValidationTest {

    private fun mapping(
        buttons: Map<GamepadButton, RawBinding> = emptyMap(),
        axes: Map<GamepadAxis, RawBinding> = emptyMap(),
    ) = GamepadMapping(
        mappingKey = "054c09cc",
        name = "Test",
        faceStyle = FaceStyle.PLAYSTATION,
        buttons = buttons,
        axes = axes,
    )

    private val fullButtons = mapOf(
        GamepadButton.FACE_BOTTOM to RawBinding.Key(96),
        GamepadButton.FACE_RIGHT to RawBinding.Key(97),
        GamepadButton.DPAD_UP to RawBinding.Key(19),
        GamepadButton.DPAD_DOWN to RawBinding.Key(20),
        GamepadButton.DPAD_LEFT to RawBinding.Key(21),
        GamepadButton.DPAD_RIGHT to RawBinding.Key(22),
    )

    @Test
    fun `full mapping with confirm and dpad is valid`() {
        assertEquals(AutoconfigCheck.Valid, AutoconfigValidation.validate(mapping(fullButtons)))
    }

    @Test
    fun `missing FACE_BOTTOM is invalid with MISSING_CONFIRM`() {
        assertEquals(
            AutoconfigCheck.Invalid(AutoconfigValidation.Reason.MISSING_CONFIRM),
            AutoconfigValidation.validate(mapping(fullButtons - GamepadButton.FACE_BOTTOM)),
        )
    }

    @Test
    fun `confirm without any dpad direction is invalid with MISSING_DIRECTION`() {
        assertEquals(
            AutoconfigCheck.Invalid(AutoconfigValidation.Reason.MISSING_DIRECTION),
            AutoconfigValidation.validate(
                mapping(mapOf(GamepadButton.FACE_BOTTOM to RawBinding.Key(96))),
            ),
        )
    }

    @Test
    fun `empty mapping reports MISSING_CONFIRM first - RetroArch checks B before directions`() {
        assertEquals(
            AutoconfigCheck.Invalid(AutoconfigValidation.Reason.MISSING_CONFIRM),
            AutoconfigValidation.validate(mapping()),
        )
    }

    @Test
    fun `FACE_BOTTOM bound as axis still counts as missing confirm`() {
        // configuration.c:8206-8209 só aceita joykey para B — binding de eixo não conta.
        val buttons = fullButtons - GamepadButton.FACE_BOTTOM +
            (GamepadButton.FACE_BOTTOM to RawBinding.Axis(0, +1))
        assertEquals(
            AutoconfigCheck.Invalid(AutoconfigValidation.Reason.MISSING_CONFIRM),
            AutoconfigValidation.validate(mapping(buttons = buttons)),
        )
    }

    @Test
    fun `a single dpad direction satisfies the minimal rule`() {
        assertEquals(
            AutoconfigCheck.Valid,
            AutoconfigValidation.validate(
                mapping(
                    mapOf(
                        GamepadButton.FACE_BOTTOM to RawBinding.Key(96),
                        GamepadButton.DPAD_LEFT to RawBinding.Key(21),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `hat-bound dpad direction counts as a direction`() {
        assertEquals(
            AutoconfigCheck.Valid,
            AutoconfigValidation.validate(
                mapping(
                    mapOf(
                        GamepadButton.FACE_BOTTOM to RawBinding.Key(96),
                        GamepadButton.DPAD_LEFT to RawBinding.Hat(0, 8),
                    ),
                ),
            ),
        )
    }
}
