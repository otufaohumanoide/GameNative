package app.gamenative.gamepad.mapping

import app.gamenative.gamepad.FaceStyle
import app.gamenative.gamepad.GamepadAxis
import app.gamenative.gamepad.GamepadButton

/**
 * Catálogo de mapeamentos por MODELO (spec 2026-08-13, Parte I §5 — "por que a DB é
 * pequena"): para os pads populares o framework Android JÁ entrega os keycodes
 * semânticos (96-110, 19-22) via arquivos `.kl` — não precisamos de binding, só do
 * [FaceStyle]. A DB cobre então:
 *
 * (a) os modelos populares (DS4, DualSense, Xbox, Switch Pro, 8BitDo) — identidade
 *     Android + face style;
 * (b) genéricos DInput (SOURCE_JOYSTICK sem SOURCE_GAMEPAD): chegam como
 *     BUTTON_1..16 + AXIS_X/Y/Z/RZ + HAT_X/Y — perfil com botões brutos;
 * (c) quirks de trigger (BRAKE/GAS vs LTRIGGER/RTRIGGER).
 *
 * NÃO importar o gamecontrollerdb.txt inteiro (2.256 entradas desktop, ~95%
 * irrelevantes em Android).
 */
object MappingDatabase {

    private val entries: Map<String, GamepadMapping> = buildMap {
        // ── Pads normalizados pelo Android (.kl entrega 96-110/19-22) ──
        put("054c09cc", defaultAndroidMapping(FaceStyle.PLAYSTATION)) // DualShock 4
        put("054c05c4", defaultAndroidMapping(FaceStyle.PLAYSTATION)) // DualShock 4 (2013)
        put("054c0ce6", defaultAndroidMapping(FaceStyle.PLAYSTATION)) // DualSense
        put("045e028e", defaultAndroidMapping(FaceStyle.XBOX)) // Xbox 360
        put("045e02d1", defaultAndroidMapping(FaceStyle.XBOX)) // Xbox One
        put("045e0b12", defaultAndroidMapping(FaceStyle.XBOX)) // Xbox Series
        put("057e2009", defaultAndroidMapping(FaceStyle.NINTENDO)) // Switch Pro
        put("2dc86001", defaultAndroidMapping(FaceStyle.GENERIC)) // 8BitDo SN30 Pro
        put("2dc89002", defaultAndroidMapping(FaceStyle.GENERIC)) // 8BitDo SN30 Pro+
        put("2dc83106", defaultAndroidMapping(FaceStyle.GENERIC)) // 8BitDo Ultimate

        // ── Genéricos DInput (JOYSTICK-only) ──
        // DragonRise "Generic USB Joystick" (o clássico controle chinês): dpad no hat.
        put("00790006", genericDInput(name = "Generic DInput (hat dpad)", dpadViaHat = true))
        // Variante com dpad em botões e triggers em BRAKE/GAS (AXIS_BRAKE=23, AXIS_GAS=22).
        put("00790011", genericDInput(name = "Generic DInput (button dpad)", dpadViaHat = false))
    }

    /** Lookup por vendor+product (modelo, não unidade). Desconhecido → null. */
    fun mappingFor(vendorId: Int, productId: Int): GamepadMapping? =
        entries["%04x%04x".format(vendorId, productId)]

    /**
     * Identidade dos keycodes Android normalizados (spec Parte I §5): o framework entrega
     * KEYCODE_BUTTON_A/B/X/Y (96/97/99/100), L1/R1 (102/103), L2/R2 (104/105 quando
     * botão), THUMBL/R (106/107), START/SELECT/MODE (108/109/110) e DPAD 19-22; eixos
     * AXIS_X/Y (0/1), Z/RZ (11/14), LTRIGGER/RTRIGGER (17/18) — ids REAIS do MotionEvent
     * (fix do guia universal input: 2/3 eram AXIS_PRESSURE/SIZE; o stick direito não
     * lia o Z/RZ verdadeiro).
     */
    fun defaultAndroidMapping(faceStyle: FaceStyle): GamepadMapping {
        val buttons = mapOf(
            GamepadButton.FACE_BOTTOM to RawBinding.Key(AndroidConstants.BUTTON_A),
            GamepadButton.FACE_RIGHT to RawBinding.Key(AndroidConstants.BUTTON_B),
            GamepadButton.FACE_LEFT to RawBinding.Key(AndroidConstants.BUTTON_X),
            GamepadButton.FACE_TOP to RawBinding.Key(AndroidConstants.BUTTON_Y),
            GamepadButton.LEFT_BUMPER to RawBinding.Key(AndroidConstants.BUTTON_L1),
            GamepadButton.RIGHT_BUMPER to RawBinding.Key(AndroidConstants.BUTTON_R1),
            GamepadButton.LEFT_TRIGGER to RawBinding.Key(AndroidConstants.BUTTON_L2),
            GamepadButton.RIGHT_TRIGGER to RawBinding.Key(AndroidConstants.BUTTON_R2),
            GamepadButton.LEFT_STICK to RawBinding.Key(AndroidConstants.BUTTON_THUMBL),
            GamepadButton.RIGHT_STICK to RawBinding.Key(AndroidConstants.BUTTON_THUMBR),
            GamepadButton.START to RawBinding.Key(AndroidConstants.BUTTON_START),
            GamepadButton.SELECT to RawBinding.Key(AndroidConstants.BUTTON_SELECT),
            GamepadButton.GUIDE to RawBinding.Key(AndroidConstants.BUTTON_MODE),
            GamepadButton.DPAD_UP to RawBinding.Key(AndroidConstants.DPAD_UP),
            GamepadButton.DPAD_DOWN to RawBinding.Key(AndroidConstants.DPAD_DOWN),
            GamepadButton.DPAD_LEFT to RawBinding.Key(AndroidConstants.DPAD_LEFT),
            GamepadButton.DPAD_RIGHT to RawBinding.Key(AndroidConstants.DPAD_RIGHT),
        )
        val axes = mapOf(
            GamepadAxis.LEFT_X to RawBinding.Axis(AndroidConstants.AXIS_X, +1),
            GamepadAxis.LEFT_Y to RawBinding.Axis(AndroidConstants.AXIS_Y, +1),
            GamepadAxis.RIGHT_X to RawBinding.Axis(AndroidConstants.AXIS_Z, +1),
            GamepadAxis.RIGHT_Y to RawBinding.Axis(AndroidConstants.AXIS_RZ, +1),
            GamepadAxis.LEFT_TRIGGER to RawBinding.Axis(AndroidConstants.AXIS_LTRIGGER, +1),
            GamepadAxis.RIGHT_TRIGGER to RawBinding.Axis(AndroidConstants.AXIS_RTRIGGER, +1),
        )
        return GamepadMapping("default", "Default Android", faceStyle, buttons, axes)
    }

    /** Perfil DInput: botões brutos BUTTON_1..16 (188..203) + AXIS_X/Y/Z/RZ + hat. */
    private fun genericDInput(name: String, dpadViaHat: Boolean): GamepadMapping {
        val buttons = mutableMapOf<GamepadButton, RawBinding>(
            GamepadButton.FACE_BOTTOM to RawBinding.Key(AndroidConstants.BUTTON_1 + 0),
            GamepadButton.FACE_RIGHT to RawBinding.Key(AndroidConstants.BUTTON_1 + 1),
            GamepadButton.FACE_LEFT to RawBinding.Key(AndroidConstants.BUTTON_1 + 2),
            GamepadButton.FACE_TOP to RawBinding.Key(AndroidConstants.BUTTON_1 + 3),
            GamepadButton.SELECT to RawBinding.Key(AndroidConstants.BUTTON_1 + 4), // back
            GamepadButton.GUIDE to RawBinding.Key(AndroidConstants.BUTTON_1 + 5), // guide
            GamepadButton.START to RawBinding.Key(AndroidConstants.BUTTON_1 + 6), // start
            GamepadButton.LEFT_STICK to RawBinding.Key(AndroidConstants.BUTTON_1 + 7),
            GamepadButton.RIGHT_STICK to RawBinding.Key(AndroidConstants.BUTTON_1 + 8),
            GamepadButton.LEFT_BUMPER to RawBinding.Key(AndroidConstants.BUTTON_1 + 9),
            GamepadButton.RIGHT_BUMPER to RawBinding.Key(AndroidConstants.BUTTON_1 + 10),
        )
        if (dpadViaHat) {
            buttons[GamepadButton.DPAD_UP] = RawBinding.Hat(0, MappingParser.HAT_UP)
            buttons[GamepadButton.DPAD_DOWN] = RawBinding.Hat(0, MappingParser.HAT_DOWN)
            buttons[GamepadButton.DPAD_LEFT] = RawBinding.Hat(0, MappingParser.HAT_LEFT)
            buttons[GamepadButton.DPAD_RIGHT] = RawBinding.Hat(0, MappingParser.HAT_RIGHT)
        } else {
            buttons[GamepadButton.DPAD_UP] = RawBinding.Key(AndroidConstants.BUTTON_1 + 11)
            buttons[GamepadButton.DPAD_DOWN] = RawBinding.Key(AndroidConstants.BUTTON_1 + 12)
            buttons[GamepadButton.DPAD_LEFT] = RawBinding.Key(AndroidConstants.BUTTON_1 + 13)
            buttons[GamepadButton.DPAD_RIGHT] = RawBinding.Key(AndroidConstants.BUTTON_1 + 14)
        }
        val axes = mutableMapOf(
            GamepadAxis.LEFT_X to RawBinding.Axis(AndroidConstants.AXIS_X, +1),
            GamepadAxis.LEFT_Y to RawBinding.Axis(AndroidConstants.AXIS_Y, +1),
            GamepadAxis.RIGHT_X to RawBinding.Axis(AndroidConstants.AXIS_Z, +1),
            GamepadAxis.RIGHT_Y to RawBinding.Axis(AndroidConstants.AXIS_RZ, +1),
            GamepadAxis.LEFT_TRIGGER to RawBinding.Axis(
                if (dpadViaHat) AndroidConstants.AXIS_LTRIGGER else AndroidConstants.AXIS_BRAKE, +1,
            ),
            GamepadAxis.RIGHT_TRIGGER to RawBinding.Axis(
                if (dpadViaHat) AndroidConstants.AXIS_RTRIGGER else AndroidConstants.AXIS_GAS, +1,
            ),
        )
        return GamepadMapping(
            mappingKey = if (dpadViaHat) "00790006" else "00790011",
            name = name,
            faceStyle = FaceStyle.GENERIC,
            buttons = buttons,
            axes = axes,
        )
    }
}
