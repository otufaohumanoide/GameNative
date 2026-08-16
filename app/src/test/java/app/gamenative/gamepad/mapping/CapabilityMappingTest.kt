package app.gamenative.gamepad.mapping

import app.gamenative.gamepad.FaceStyle
import app.gamenative.gamepad.GamepadAxis
import app.gamenative.gamepad.GamepadButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * K3 (spec 2026-08-16-K3, §1.2): síntese de mapping pelas capabilities reais —
 * port clean-room do SDL_CreateMappingForAndroidGamepad (SDL_gamepad.c:705-831).
 * Regras 1-6 do spec + classify por shape.
 */
class CapabilityMappingTest {

    /** Keycodes normalizados de um gamepad completo (BUTTON_A..MODE + DPAD). */
    private val fullKeys = setOf(
        AndroidConstants.BUTTON_A,
        AndroidConstants.BUTTON_B,
        AndroidConstants.BUTTON_X,
        AndroidConstants.BUTTON_Y,
        AndroidConstants.BUTTON_L1,
        AndroidConstants.BUTTON_R1,
        AndroidConstants.BUTTON_L2,
        AndroidConstants.BUTTON_R2,
        AndroidConstants.BUTTON_THUMBL,
        AndroidConstants.BUTTON_THUMBR,
        AndroidConstants.BUTTON_START,
        AndroidConstants.BUTTON_SELECT,
        AndroidConstants.BUTTON_MODE,
        AndroidConstants.DPAD_UP,
        AndroidConstants.DPAD_DOWN,
        AndroidConstants.DPAD_LEFT,
        AndroidConstants.DPAD_RIGHT,
    )

    private val fullAxes = listOf(
        AndroidConstants.AXIS_X,
        AndroidConstants.AXIS_Y,
        AndroidConstants.AXIS_Z,
        AndroidConstants.AXIS_RZ,
        AndroidConstants.AXIS_LTRIGGER,
        AndroidConstants.AXIS_RTRIGGER,
    )

    private val dpadKeys = setOf(
        AndroidConstants.DPAD_UP,
        AndroidConstants.DPAD_DOWN,
        AndroidConstants.DPAD_LEFT,
        AndroidConstants.DPAD_RIGHT,
    )

    private fun caps(
        keys: Set<Int> = fullKeys,
        axes: List<Int> = fullAxes,
        hasHat: Boolean = false,
        gamepad: Boolean = true,
    ) = GamepadCapabilities(keycodes = keys, axes = axes, hasHat = hasHat, isGamepadSource = gamepad)

    // ── Regra 1 + 6: só o que existe ──

    @Test
    fun `gamepad completo sintetiza o default inteiro`() {
        val mapping = CapabilityMapping.synthesize(caps(), FaceStyle.GENERIC)!!
        val dflt = MappingDatabase.defaultAndroidMapping(FaceStyle.GENERIC)
        // Regra 4: triggers vão SÓ para o eixo — o default ESTÁTICO liga os dois
        // (Key L2/R2 E Axis LTRIGGER/RTRIGGER); o sintetizado segue o SDL e prefere
        // o axis, nunca ambos. O resto é idêntico ao default.
        val expectedButtons = dflt.buttons - GamepadButton.LEFT_TRIGGER - GamepadButton.RIGHT_TRIGGER
        assertEquals(expectedButtons, mapping.buttons)
        assertEquals(dflt.axes, mapping.axes)
        assertEquals(CapabilityMapping.MAPPING_KEY, mapping.mappingKey)
    }

    @Test
    fun `gamepad completo classifica GAMEPAD`() {
        assertEquals(DeviceShape.GAMEPAD, CapabilityMapping.classify(caps()))
    }

    @Test
    fun `R3 ausente nao vira binding fantasma`() {
        val mapping = CapabilityMapping.synthesize(
            caps(keys = fullKeys - AndroidConstants.BUTTON_THUMBR),
            FaceStyle.GENERIC,
        )!!
        assertNull(mapping.buttons[GamepadButton.RIGHT_STICK])
        assertEquals(RawBinding.Key(AndroidConstants.BUTTON_THUMBL), mapping.buttons[GamepadButton.LEFT_STICK])
    }

    @Test
    fun `stick direito sintetiza com os ids REAIS do MotionEvent Z=11 RZ=14`() {
        // FIX do guia universal input (pré-K6): a coleta do hotplug entrega os ids
        // reais do MotionEvent (AXIS_Z=11/AXIS_RZ=14); o gate da síntese tinha
        // 2/3 (AXIS_PRESSURE/SIZE) e nunca casava — o stick direito nunca nascia.
        val mapping = CapabilityMapping.synthesize(
            caps(axes = listOf(0, 1, 11, 14)),
            FaceStyle.GENERIC,
        )!!
        assertEquals(RawBinding.Axis(11, +1), mapping.axes[GamepadAxis.RIGHT_X])
        assertEquals(RawBinding.Axis(14, +1), mapping.axes[GamepadAxis.RIGHT_Y])
    }

    @Test
    fun `stick direito so com AXIS_Z e AXIS_RZ (pad de 1 stick nao ganha fantasma)`() {
        val mapping = CapabilityMapping.synthesize(
            caps(axes = listOf(AndroidConstants.AXIS_X, AndroidConstants.AXIS_Y)),
            FaceStyle.GENERIC,
        )!!
        assertNull(mapping.axes[GamepadAxis.RIGHT_X])
        assertNull(mapping.axes[GamepadAxis.RIGHT_Y])
        assertNotNull(mapping.axes[GamepadAxis.LEFT_X])
        assertNotNull(mapping.axes[GamepadAxis.LEFT_Y])
    }

    @Test
    fun `generic DInput com hat e 1 stick classifica DINPUT_GENERIC`() {
        val generic = caps(
            keys = setOf(AndroidConstants.BUTTON_1),
            axes = listOf(AndroidConstants.AXIS_X, AndroidConstants.AXIS_Y),
            hasHat = true,
            gamepad = false,
        )
        assertEquals(DeviceShape.DINPUT_GENERIC, CapabilityMapping.classify(generic))
    }

    // ── Regra 3: guide gateado ──

    @Test
    fun `GUIDE so entra com BUTTON_MODE presente`() {
        val withGuide = CapabilityMapping.synthesize(caps(), FaceStyle.GENERIC)!!
        assertEquals(RawBinding.Key(AndroidConstants.BUTTON_MODE), withGuide.buttons[GamepadButton.GUIDE])

        val noGuide = CapabilityMapping.synthesize(
            caps(keys = fullKeys - AndroidConstants.BUTTON_MODE),
            FaceStyle.GENERIC,
        )!!
        assertNull(noGuide.buttons[GamepadButton.GUIDE])
    }

    // ── Regra 4: triggers ──

    @Test
    fun `trigger prefere eixo e cai para botao L2 R2 (nunca ambos)`() {
        val withAxis = CapabilityMapping.synthesize(caps(), FaceStyle.GENERIC)!!
        assertEquals(RawBinding.Axis(AndroidConstants.AXIS_LTRIGGER, 1), withAxis.axes[GamepadAxis.LEFT_TRIGGER])
        assertEquals(RawBinding.Axis(AndroidConstants.AXIS_RTRIGGER, 1), withAxis.axes[GamepadAxis.RIGHT_TRIGGER])
        assertNull(withAxis.buttons[GamepadButton.LEFT_TRIGGER])
        assertNull(withAxis.buttons[GamepadButton.RIGHT_TRIGGER])

        val buttonOnly = CapabilityMapping.synthesize(
            caps(axes = listOf(AndroidConstants.AXIS_X, AndroidConstants.AXIS_Y, AndroidConstants.AXIS_Z, AndroidConstants.AXIS_RZ)),
            FaceStyle.GENERIC,
        )!!
        assertEquals(RawBinding.Key(AndroidConstants.BUTTON_L2), buttonOnly.buttons[GamepadButton.LEFT_TRIGGER])
        assertEquals(RawBinding.Key(AndroidConstants.BUTTON_R2), buttonOnly.buttons[GamepadButton.RIGHT_TRIGGER])
        assertNull(buttonOnly.axes[GamepadAxis.LEFT_TRIGGER])
        assertNull(buttonOnly.axes[GamepadAxis.RIGHT_TRIGGER])
    }

    // ── Regra 5: dpad ──

    @Test
    fun `dpad por keycode vence o hat`() {
        val viaKeys = CapabilityMapping.synthesize(caps(hasHat = true), FaceStyle.GENERIC)!!
        assertEquals(RawBinding.Key(AndroidConstants.DPAD_UP), viaKeys.buttons[GamepadButton.DPAD_UP])
        assertEquals(RawBinding.Key(AndroidConstants.DPAD_DOWN), viaKeys.buttons[GamepadButton.DPAD_DOWN])
        assertEquals(RawBinding.Key(AndroidConstants.DPAD_LEFT), viaKeys.buttons[GamepadButton.DPAD_LEFT])
        assertEquals(RawBinding.Key(AndroidConstants.DPAD_RIGHT), viaKeys.buttons[GamepadButton.DPAD_RIGHT])
    }

    @Test
    fun `dpad sem keycode usa o hat (caminho do genericDInput)`() {
        val viaHat = CapabilityMapping.synthesize(
            caps(keys = fullKeys - dpadKeys, hasHat = true),
            FaceStyle.GENERIC,
        )!!
        assertEquals(RawBinding.Hat(0, MappingParser.HAT_UP), viaHat.buttons[GamepadButton.DPAD_UP])
        assertEquals(RawBinding.Hat(0, MappingParser.HAT_DOWN), viaHat.buttons[GamepadButton.DPAD_DOWN])
        assertEquals(RawBinding.Hat(0, MappingParser.HAT_LEFT), viaHat.buttons[GamepadButton.DPAD_LEFT])
        assertEquals(RawBinding.Hat(0, MappingParser.HAT_RIGHT), viaHat.buttons[GamepadButton.DPAD_RIGHT])
    }

    @Test
    fun `sem dpad e sem hat nao emite dpad`() {
        val mapping = CapabilityMapping.synthesize(
            caps(keys = fullKeys - dpadKeys),
            FaceStyle.GENERIC,
        )!!
        assertNull(mapping.buttons[GamepadButton.DPAD_UP])
        assertNull(mapping.buttons[GamepadButton.DPAD_DOWN])
        assertNull(mapping.buttons[GamepadButton.DPAD_LEFT])
        assertNull(mapping.buttons[GamepadButton.DPAD_RIGHT])
    }

    // ── Regra 2: shapes ──

    @Test
    fun `REMOTE sem face e sem dpad vira BACK para FACE_BOTTOM e o resto vazio`() {
        val remoteCaps = caps(
            keys = setOf(AndroidConstants.BACK),
            axes = emptyList(),
            gamepad = false,
        )
        assertEquals(DeviceShape.REMOTE, CapabilityMapping.classify(remoteCaps))
        val mapping = CapabilityMapping.synthesize(remoteCaps, FaceStyle.GENERIC)!!
        assertEquals(1, mapping.buttons.size)
        assertEquals(RawBinding.Key(AndroidConstants.BACK), mapping.buttons[GamepadButton.FACE_BOTTOM])
        assertTrue(mapping.axes.isEmpty())
    }

    @Test
    fun `REMOTE sem BACK nao gera mapping (fica o default estatico)`() {
        assertNull(
            CapabilityMapping.synthesize(
                caps(keys = setOf(AndroidConstants.BUTTON_MODE), axes = emptyList(), gamepad = false),
                FaceStyle.GENERIC,
            ),
        )
    }

    @Test
    fun `sem botao e sem eixo retorna null e classifica KEYBOARD`() {
        val empty = GamepadCapabilities(emptySet(), emptyList(), hasHat = false, isGamepadSource = false)
        assertEquals(DeviceShape.KEYBOARD, CapabilityMapping.classify(empty))
        assertNull(CapabilityMapping.synthesize(empty, FaceStyle.GENERIC))
    }

    @Test
    fun `remote com dpad mas sem face nao ganha face fantasma`() {
        val dpadRemote = caps(
            keys = dpadKeys + AndroidConstants.BACK,
            axes = emptyList(),
            gamepad = false,
        )
        assertEquals(DeviceShape.DINPUT_GENERIC, CapabilityMapping.classify(dpadRemote))
        val mapping = CapabilityMapping.synthesize(dpadRemote, FaceStyle.GENERIC)!!
        assertNull(mapping.buttons[GamepadButton.FACE_BOTTOM])
        assertNull(mapping.buttons[GamepadButton.FACE_RIGHT])
        assertNotNull(mapping.buttons[GamepadButton.DPAD_UP])
        // BACK vira SELECT (sem face para o fallback de EAST — gate da máscara do SDL).
        assertEquals(RawBinding.Key(AndroidConstants.BACK), mapping.buttons[GamepadButton.SELECT])
    }

    // ── Fallback de EAST do SDL (778-781) ──

    @Test
    fun `EAST ausente usa BACK como b e BACK nao vira SELECT`() {
        val keys = fullKeys - AndroidConstants.BUTTON_B + AndroidConstants.BACK
        val mapping = CapabilityMapping.synthesize(caps(keys = keys), FaceStyle.GENERIC)!!
        assertEquals(RawBinding.Key(AndroidConstants.BACK), mapping.buttons[GamepadButton.FACE_RIGHT])
        // BACK consumido pelo fallback — SELECT fica só no keycode normalizado.
        assertEquals(RawBinding.Key(AndroidConstants.BUTTON_SELECT), mapping.buttons[GamepadButton.SELECT])
    }

    @Test
    fun `sem B e sem BACK o EAST fica vazio`() {
        val mapping = CapabilityMapping.synthesize(
            caps(keys = fullKeys - AndroidConstants.BUTTON_B),
            FaceStyle.GENERIC,
        )!!
        assertNull(mapping.buttons[GamepadButton.FACE_RIGHT])
        assertEquals(RawBinding.Key(AndroidConstants.BUTTON_SELECT), mapping.buttons[GamepadButton.SELECT])
    }
}
