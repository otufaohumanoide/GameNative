package app.gamenative.gamepad.mapping

import app.gamenative.gamepad.FaceStyle
import app.gamenative.gamepad.GamepadAxis
import app.gamenative.gamepad.GamepadButton
import app.gamenative.gamepad.InputEvent
import app.gamenative.gamepad.processing.DeadzoneConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * K4 (spec 2026-08-16-K4, §2): tabela declarativa de quirks por vid/pid/nome/BT —
 * port clean-room do `handleRemapping` do moonlight (ControllerHandler.java:1312-
 * 1547). Cobertura: match por vid/pid/nome/BT (§1.1), gate de capabilities
 * (resolve), apply idempotente, fixup null = mesma referência, alias de scanCode
 * (§1.3.2) e domínio idle-negative dos triggers em RX/RY (ControllerHandler.java:
 * 875-876, 1613-1620).
 */
class DeviceQuirksTest {

    private val mapping = MappingDatabase.defaultAndroidMapping(FaceStyle.PLAYSTATION)

    // ── §1.1: matcher puro (vid/pid/nome/BT) ──

    @Test
    fun `firstMatch casa DS4 BT por vendorId`() {
        val quirk = DeviceQuirks.firstMatch(0x054c, 0x09cc, "Wireless Controller", isBt = true)
        assertNotNull(quirk)
        assertEquals("DS4 non-standard (RX/RY triggers)", quirk!!.name)
    }

    @Test
    fun `firstMatch nao devolve DS4 sem Bluetooth`() {
        // Sem BT a entry do DS4 (bluetoothOnly) não casa; cai no catch-all.
        val quirk = DeviceQuirks.firstMatch(0x054c, 0x09cc, "Wireless Controller", isBt = false)
        assertNotNull(quirk)
        assertTrue(quirk!!.name != "DS4 non-standard (RX/RY triggers)")
    }

    @Test
    fun `firstMatch casa Switch Pro por vid e pid`() {
        val quirk = DeviceQuirks.firstMatch(0x057e, 0x2009, "Pro Controller", isBt = true)
        assertEquals("Switch Pro (pre-hid-nintendo)", quirk!!.name)
    }

    @Test
    fun `firstMatch casa Xbox por nome e Bluetooth`() {
        val quirk = DeviceQuirks.firstMatch(0x045e, 0x02fd, "Xbox Wireless Controller", isBt = true)
        assertEquals("Xbox Wireless Controller (old BT firmware)", quirk!!.name)
    }

    @Test
    fun `firstMatch nao devolve Xbox sem Bluetooth`() {
        // O matcher exige BT; um device com o MESMO nome em outro transporte não
        // devolve a entry do Xbox (cai no catch-all).
        val quirk = DeviceQuirks.firstMatch(0x045e, 0x02fd, "Xbox Wireless Controller", isBt = false)
        assertNotNull(quirk)
        assertTrue(quirk!!.name != "Xbox Wireless Controller (old BT firmware)")
    }

    @Test
    fun `firstMatch nome e case-insensitive`() {
        val quirk = DeviceQuirks.firstMatch(0x0b05, 0x1234, "asus gamepad", isBt = false)
        assertEquals("ASUS Gamepad (back=start, mode=select)", quirk!!.name)
    }

    @Test
    fun `firstMatch catch-all raw dpad casa qualquer device`() {
        val quirk = DeviceQuirks.firstMatch(0x1234, 0x5678, "Unknown Pad", isBt = false)
        assertEquals("Raw d-pad scancodes (704-707)", quirk!!.name)
    }

    // ── Gate de capabilities (resolve — "capability decide se o quirk é necessário") ──

    private fun caps(
        keys: Set<Int> = emptySet(),
        axes: List<Int> = emptyList(),
        hasHat: Boolean = false,
    ) = GamepadCapabilities(keycodes = keys, axes = axes, hasHat = hasHat, isGamepadSource = true)

    @Test
    fun `resolve DS4 com triggers LTRIGGER_RTRIGGER nao aplica quirk`() {
        // DS4 com .kl normal: triggers em LTRIGGER/RTRIGGER → regressão zero (§4.1).
        val caps = caps(
            axes = listOf(
                AndroidConstants.AXIS_X, AndroidConstants.AXIS_Y,
                AndroidConstants.AXIS_Z, AndroidConstants.AXIS_RZ,
                AndroidConstants.AXIS_LTRIGGER, AndroidConstants.AXIS_RTRIGGER,
            ),
            hasHat = true,
        )
        assertNull(DeviceQuirks.resolve(0x054c, 0x09cc, "Wireless Controller", true, caps))
    }

    @Test
    fun `resolve DS4 sem triggers mas com RX_RY aplica quirk`() {
        // O hid-sony antigo (sem .kl): triggers em RX/RY (ControllerHandler.java:851-859).
        val caps = caps(
            axes = listOf(
                AndroidConstants.AXIS_X, AndroidConstants.AXIS_Y,
                AndroidConstants.AXIS_RX, AndroidConstants.AXIS_RY,
            ),
            hasHat = true,
        )
        val quirk = DeviceQuirks.resolve(0x054c, 0x09cc, "Wireless Controller", true, caps)
        assertNotNull(quirk)
        assertEquals("DS4 non-standard (RX/RY triggers)", quirk!!.name)

        val fixed = DeviceQuirks.apply(mapping, quirk.fixup)
        assertEquals(
            RawBinding.Axis(AndroidConstants.AXIS_RX, +1),
            fixed.axes[GamepadAxis.LEFT_TRIGGER],
        )
        assertEquals(
            RawBinding.Axis(AndroidConstants.AXIS_RY, +1),
            fixed.axes[GamepadAxis.RIGHT_TRIGGER],
        )
        assertEquals(
            RawBinding.Key(AndroidConstants.BUTTON_1),
            fixed.buttons[GamepadButton.TOUCHPAD],
        )
        // A entry original da DB NÃO é mutada.
        assertEquals(
            RawBinding.Axis(AndroidConstants.AXIS_LTRIGGER, +1),
            mapping.axes[GamepadAxis.LEFT_TRIGGER],
        )
        assertNull(mapping.buttons[GamepadButton.TOUCHPAD])
    }

    @Test
    fun `resolve caps null nao ativa quirk gateado por capability`() {
        // Degradação byte-identical: sem capabilities coletadas, entries gateadas
        // por capability NÃO ativam.
        assertNull(DeviceQuirks.resolve(0x054c, 0x09cc, "Wireless Controller", true, null))
    }

    @Test
    fun `resolve Xbox old firmware gateado por eixo GAS ausente`() {
        val oldFirmware = caps(axes = listOf(AndroidConstants.AXIS_X, AndroidConstants.AXIS_Y))
        val quirk = DeviceQuirks.resolve(0x045e, 0x02fd, "Xbox Wireless Controller", true, oldFirmware)
        assertNotNull(quirk)
        assertEquals("Xbox Wireless Controller (old BT firmware)", quirk!!.name)

        val newFirmware = caps(
            axes = listOf(
                AndroidConstants.AXIS_X, AndroidConstants.AXIS_Y,
                AndroidConstants.AXIS_GAS, AndroidConstants.AXIS_BRAKE,
            ),
            hasHat = true,
        )
        assertNull(DeviceQuirks.resolve(0x045e, 0x02fd, "Xbox Wireless Controller", true, newFirmware))
    }

    @Test
    fun `resolve raw dpad sem hat casa qualquer device`() {
        val quirk = DeviceQuirks.resolve(
            0x1234, 0x5678, "Unknown Pad", false,
            caps(axes = listOf(AndroidConstants.AXIS_X, AndroidConstants.AXIS_Y)),
        )
        assertEquals("Raw d-pad scancodes (704-707)", quirk!!.name)
    }

    @Test
    fun `resolve raw dpad com hat nao casa`() {
        // ControllerHandler.java:1487-1509: com HAT_X/HAT_Y o d-pad vem pelo hat.
        val quirk = DeviceQuirks.resolve(
            0x1234, 0x5678, "Unknown Pad", false,
            caps(hasHat = true),
        )
        assertNull(quirk)
    }

    // ── §1.1: apply pura (idempotente; null = mesma referência) ──

    @Test
    fun `apply fixup null retorna a mesma referencia`() {
        assertSame(mapping, DeviceQuirks.apply(mapping, null))
    }

    @Test
    fun `apply fixup so com scanCodeAliases retorna a mesma referencia`() {
        val fixup = DeviceQuirkFixup(
            scanCodeAliases = mapOf(704 to AndroidConstants.DPAD_LEFT),
        )
        assertSame(mapping, DeviceQuirks.apply(mapping, fixup))
    }

    @Test
    fun `apply e idempotente`() {
        val fixup = DeviceQuirkFixup(
            replaceButton = mapOf(
                GamepadButton.SELECT to RawBinding.Key(AndroidConstants.BUTTON_MODE),
            ),
            replaceAxis = mapOf(
                GamepadAxis.LEFT_TRIGGER to RawBinding.Axis(AndroidConstants.AXIS_RX, +1),
            ),
        )
        val once = DeviceQuirks.apply(mapping, fixup)
        val twice = DeviceQuirks.apply(once, fixup)
        assertEquals(once, twice)
        assertEquals(
            RawBinding.Key(AndroidConstants.BUTTON_MODE),
            once.buttons[GamepadButton.SELECT],
        )
        // Os bindings NÃO substituídos continuam os mesmos da entry original.
        assertEquals(mapping.buttons[GamepadButton.START], once.buttons[GamepadButton.START])
    }

    // ── §1.2: conteúdo do seed (valores extraídos do moonlight) ──

    @Test
    fun `seed 8BitDo alias 306 para BUTTON_MODE`() {
        val quirk = DeviceQuirks.firstMatch(0x2dc8, 0x6001, "8BitDo SN30 Pro", isBt = true)
        assertEquals(
            AndroidConstants.BUTTON_MODE,
            quirk!!.fixup.scanCodeAliases[306],
        )
    }

    @Test
    fun `seed raw dpad alias 704 a 707`() {
        val quirk = DeviceQuirks.firstMatch(0x1234, 0x5678, "X", isBt = false)
        val aliases = quirk!!.fixup.scanCodeAliases
        assertEquals(AndroidConstants.DPAD_LEFT, aliases[704])
        assertEquals(AndroidConstants.DPAD_RIGHT, aliases[705])
        assertEquals(AndroidConstants.DPAD_UP, aliases[706])
        assertEquals(AndroidConstants.DPAD_DOWN, aliases[707])
    }

    @Test
    fun `raw dpad fixup tambem binda os keycodes DPAD no mapping`() {
        // O alias corrige o keycode, mas o tradutor casa por `mapping.buttons` — o
        // fixup precisa garantir os bindings de DPAD (tier CAPABILITIES de device
        // desconhecido não os emite sem a capability de keycodes DPAD).
        val quirk = DeviceQuirks.firstMatch(0x1234, 0x5678, "X", isBt = false)!!
        val fixed = DeviceQuirks.apply(mapping, quirk.fixup)
        assertEquals(
            RawBinding.Key(AndroidConstants.DPAD_LEFT),
            fixed.buttons[GamepadButton.DPAD_LEFT],
        )
        assertEquals(
            RawBinding.Key(AndroidConstants.DPAD_DOWN),
            fixed.buttons[GamepadButton.DPAD_DOWN],
        )
    }

    @Test
    fun `seed Switch Pro alias 317 para BUTTON_MODE`() {
        val quirk = DeviceQuirks.firstMatch(0x057e, 0x2009, "Pro Controller", isBt = true)
        assertEquals(
            AndroidConstants.BUTTON_MODE,
            quirk!!.fixup.scanCodeAliases[317],
        )
    }

    @Test
    fun `seed Kunai alias 264 e 265 para START e SELECT`() {
        val quirk = DeviceQuirks.firstMatch(0x0b05, 0x7902, "ASUS ROG Kunai", isBt = true)
        val aliases = quirk!!.fixup.scanCodeAliases
        assertEquals(AndroidConstants.BUTTON_START, aliases[264])
        assertEquals(AndroidConstants.BUTTON_SELECT, aliases[265])
        assertEquals(AndroidConstants.BUTTON_START, aliases[266])
        assertEquals(AndroidConstants.BUTTON_SELECT, aliases[267])
    }

    // ── Domínio idle-negative dos triggers em RX/RY (ControllerHandler.java:875-876, 1613-1620) ──

    private fun quirkedDs4Mapping(): GamepadMapping {
        val quirk = DeviceQuirks.firstMatch(0x054c, 0x09cc, "Wireless Controller", isBt = true)!!
        return DeviceQuirks.apply(mapping, quirk.fixup)
    }

    @Test
    fun `trigger em RX neutro em -1 nao emite e pressionado em 1 emite`() {
        val quirked = quirkedDs4Mapping()
        val deadzones = DeadzoneConfig()
        val rawAtRest = RawAxisInput(
            deviceId = 1,
            source = 0x01000010,
            action = 0,
            axisValues = mapOf(AndroidConstants.AXIS_RX to -1f, AndroidConstants.AXIS_RY to -1f),
        )
        val rest = EventTranslator.translateAxis(rawAtRest, quirked, deadzones)
        assertTrue(rest.none { it is InputEvent.AxisMotion && it.axis == GamepadAxis.LEFT_TRIGGER })
        assertTrue(rest.none { it is InputEvent.AxisMotion && it.axis == GamepadAxis.RIGHT_TRIGGER })

        val rawPressed = RawAxisInput(
            deviceId = 1,
            source = 0x01000010,
            action = 0,
            axisValues = mapOf(AndroidConstants.AXIS_RX to 1f, AndroidConstants.AXIS_RY to -1f),
        )
        val pressed = EventTranslator.translateAxis(rawPressed, quirked, deadzones)
        val left = pressed.filterIsInstance<InputEvent.AxisMotion>()
            .first { it.axis == GamepadAxis.LEFT_TRIGGER }
        assertTrue("trigger cheio deve virar >0.9, veio ${left.value}", left.value > 0.9f)
        val right = pressed.filterIsInstance<InputEvent.AxisMotion>()
            .firstOrNull { it.axis == GamepadAxis.RIGHT_TRIGGER }
        assertNull("RY em -1 (neutro) não pode emitir trigger", right)
    }
}
