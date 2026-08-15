package app.gamenative.gamepad.remap

import app.gamenative.gamepad.mapping.RawBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Codec de binding das camadas do perfil (spec 2026-08-13, Parte I §7): round-trip
 * garantido, tokens inválidos degradam a null, conflito = mesma fonte física.
 */
class GamepadBindingCodecTest {

    @Test
    fun `key roundtrips`() {
        val binding = RawBinding.Key(96)
        assertEquals(binding, GamepadBindingCodec.decode(GamepadBindingCodec.encode(binding))?.raw)
    }

    @Test
    fun `axis roundtrips both directions`() {
        for (direction in listOf(-1, 1)) {
            val binding = RawBinding.Axis(17, direction)
            assertEquals(binding, GamepadBindingCodec.decode(GamepadBindingCodec.encode(binding))?.raw)
        }
    }

    @Test
    fun `hat roundtrips`() {
        val binding = RawBinding.Hat(0, 4)
        assertEquals(binding, GamepadBindingCodec.decode(GamepadBindingCodec.encode(binding))?.raw)
    }

    @Test
    fun `turbo default off e byte-identical ao v1`() {
        // F §1.4 (spec 2026-08-16-F): default OFF = token EXATO do v1 (sem sufixo).
        assertEquals("key:96", GamepadBindingCodec.encode(RawBinding.Key(96)))
        assertEquals("axis:17:1", GamepadBindingCodec.encode(RawBinding.Axis(17, 1)))
        assertEquals("hat:0:4", GamepadBindingCodec.encode(RawBinding.Hat(0, 4)))
        assertFalse(GamepadBindingCodec.decode("key:96")!!.turbo)
        assertFalse(GamepadBindingCodec.decode("axis:17:-1")!!.turbo)
        assertFalse(GamepadBindingCodec.decode("hat:0:4")!!.turbo)
    }

    @Test
    fun `turbo roundtrips com sufixo`() {
        val key = RawBinding.Key(96)
        assertEquals("key:96:turbo", GamepadBindingCodec.encode(key, turbo = true))
        assertEquals(GamepadBindingCodec.LayerBinding(key, turbo = true), GamepadBindingCodec.decode("key:96:turbo"))
        assertEquals(GamepadBindingCodec.LayerBinding(RawBinding.Axis(17, -1), turbo = true), GamepadBindingCodec.decode("axis:17:-1:turbo"))
        assertEquals(GamepadBindingCodec.LayerBinding(RawBinding.Hat(0, 4), turbo = true), GamepadBindingCodec.decode("hat:0:4:turbo"))
    }

    @Test
    fun `sufixo turbo malformado degrada`() {
        // hat com sufixo turbo mas máscara ausente → null (nunca exceção).
        assertNull(GamepadBindingCodec.decode("hat:0:turbo"))
        assertNull(GamepadBindingCodec.decode("key:abc:turbo"))
    }

    @Test
    fun `invalid tokens decode to null`() {
        assertNull(GamepadBindingCodec.decode(""))
        assertNull(GamepadBindingCodec.decode("foo"))
        assertNull(GamepadBindingCodec.decode("key:abc"))
        assertNull(GamepadBindingCodec.decode("axis:0:0"))    // direção 0 proibida
        assertNull(GamepadBindingCodec.decode("axis:0:2"))
        assertNull(GamepadBindingCodec.decode("hat:0:0"))     // máscara 0 inválida
        assertNull(GamepadBindingCodec.decode("hat:x:4"))
    }

    @Test
    fun `conflicts detect the same physical source`() {
        assertTrue(GamepadBindingCodec.conflicts(RawBinding.Key(96), RawBinding.Key(96)))
        assertFalse(GamepadBindingCodec.conflicts(RawBinding.Key(96), RawBinding.Key(97)))
        assertTrue(GamepadBindingCodec.conflicts(RawBinding.Axis(0, 1), RawBinding.Axis(0, -1)))
        assertFalse(GamepadBindingCodec.conflicts(RawBinding.Axis(0, 1), RawBinding.Axis(1, 1)))
        assertTrue(GamepadBindingCodec.conflicts(RawBinding.Hat(0, 1), RawBinding.Hat(0, 1)))
        // Máscaras que se sobrepõem (4 = down, 12 = down|right) disputam a mesma direção.
        assertTrue(GamepadBindingCodec.conflicts(RawBinding.Hat(0, 4), RawBinding.Hat(0, 12)))
        // Máscaras disjuntas no MESMO hat não conflitam (up e down são direções diferentes).
        assertFalse(GamepadBindingCodec.conflicts(RawBinding.Hat(0, 4), RawBinding.Hat(0, 8)))
        assertFalse(GamepadBindingCodec.conflicts(RawBinding.Hat(0, 1), RawBinding.Hat(1, 1)))
        assertFalse(GamepadBindingCodec.conflicts(RawBinding.Key(96), RawBinding.Axis(0, 1)))
    }
}
