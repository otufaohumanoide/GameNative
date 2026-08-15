package app.gamenative.gamepad.remap

import app.gamenative.gamepad.mapping.RawBinding
import app.gamenative.gamepad.processing.BindingModifier
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
        assertEquals(GamepadBindingCodec.LayerBinding.Physical(key, turbo = true), GamepadBindingCodec.decode("key:96:turbo"))
        assertEquals(GamepadBindingCodec.LayerBinding.Physical(RawBinding.Axis(17, -1), turbo = true), GamepadBindingCodec.decode("axis:17:-1:turbo"))
        assertEquals(GamepadBindingCodec.LayerBinding.Physical(RawBinding.Hat(0, 4), turbo = true), GamepadBindingCodec.decode("hat:0:4:turbo"))
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

    // ── H (spec 2026-08-16-H-binding-modifiers-duckstation, §2.2/§4): bloco :m= ──

    @Test
    fun `mods roundtrip cada modificador isolado`() {
        assertEquals(
            GamepadBindingCodec.LayerBinding.Physical(RawBinding.Key(96), mod = BindingModifier(invert = true)),
            GamepadBindingCodec.decode("key:96:m=inv"),
        )
        assertEquals(
            GamepadBindingCodec.LayerBinding.Physical(RawBinding.Axis(17, 1), mod = BindingModifier(fullAxis = true)),
            GamepadBindingCodec.decode("axis:17:1:m=full"),
        )
        assertEquals(
            GamepadBindingCodec.LayerBinding.Physical(RawBinding.Axis(17, -1), mod = BindingModifier(scale = 1.3f)),
            GamepadBindingCodec.decode("axis:17:-1:m=s130"),
        )
        assertEquals(
            GamepadBindingCodec.LayerBinding.Physical(RawBinding.Hat(0, 4), mod = BindingModifier(deadzone = 0.05f)),
            GamepadBindingCodec.decode("hat:0:4:m=dz5"),
        )
    }

    @Test
    fun `mods combinados com turbo roundtrip`() {
        val mod = BindingModifier(fullAxis = true, invert = true, scale = 1.3f, deadzone = 0.05f)
        val token = GamepadBindingCodec.encode(RawBinding.Axis(17, 1), turbo = true, mod = mod)
        assertEquals("axis:17:1:turbo:m=full,inv,s130,dz5", token)
        assertEquals(
            GamepadBindingCodec.LayerBinding.Physical(RawBinding.Axis(17, 1), turbo = true, mod = mod),
            GamepadBindingCodec.decode(token),
        )
    }

    @Test
    fun `encode omite campos default e o token sem mod e byte-identical ao atual`() {
        // Sem modificadores ⇒ token IDÊNTICO ao formato atual (§2.2).
        assertEquals("key:96", GamepadBindingCodec.encode(RawBinding.Key(96)))
        assertEquals("key:96", GamepadBindingCodec.encode(RawBinding.Key(96), mod = BindingModifier()))
        assertEquals(
            "key:96",
            GamepadBindingCodec.encode(
                RawBinding.Key(96),
                mod = BindingModifier(invert = false, fullAxis = false, scale = 1f, deadzone = 0f),
            ),
        )
        assertEquals("axis:17:1", GamepadBindingCodec.encode(RawBinding.Axis(17, 1), mod = BindingModifier(scale = 1f)))
        assertEquals(
            "axis:17:1:m=inv",
            GamepadBindingCodec.encode(RawBinding.Axis(17, 1), mod = BindingModifier(invert = true)),
        )
        // Round-trip ESTÁVEL: decode → encode é byte-identical (percentuais inteiros).
        for (token in listOf(
            "axis:17:1:m=full,s130,dz5",
            "key:96:turbo:m=inv",
            "hat:0:4:m=dz10",
            "axis:23:-1:m=full,inv,s50,dz50",
        )) {
            val decoded = GamepadBindingCodec.decode(token)!!
            assertEquals(token, GamepadBindingCodec.encode(decoded.raw!!, decoded.turbo, decoded.mod))
        }
    }

    @Test
    fun `decode ignora campos desconhecidos no bloco m`() {
        // `future` é ignorado; inv preserva; s0 clampa em 50 → scale 0.5.
        val binding = GamepadBindingCodec.decode("axis:17:1:m=future,inv,s0")!!
        assertEquals(BindingModifier(invert = true, scale = 0.5f), binding.mod)
        assertEquals(RawBinding.Axis(17, 1), binding.raw)
        // Tudo desconhecido/default → mod null (token canônico, V1 leniente).
        assertNull(GamepadBindingCodec.decode("key:96:m=whatever")!!.mod)
        assertNull(GamepadBindingCodec.decode("key:96:m=")!!.mod)
        // Percentuais fora da faixa clampam (s999 → 2.0, dz999 → 0.5).
        assertEquals(
            BindingModifier(scale = 2f, deadzone = 0.5f),
            GamepadBindingCodec.decode("axis:17:1:m=s999,dz999")!!.mod,
        )
    }

    @Test
    fun `base do token continua rigida com bloco m presente`() {
        // Estrutura da base inválida ⇒ null (comportamento atual, mesmo com mod).
        assertNull(GamepadBindingCodec.decode("key:abc:m=inv"))
        assertNull(GamepadBindingCodec.decode("axis:0:0:m=inv"))
        assertNull(GamepadBindingCodec.decode("hat:0:m=inv"))
        // m= no MEIO do token não é reconhecido como bloco (o bloco é o ÚLTIMO campo):
        // as partes extras continuam lenientes como hoje.
        assertEquals(
            GamepadBindingCodec.LayerBinding.Physical(RawBinding.Key(96), turbo = true),
            GamepadBindingCodec.decode("key:96:m=inv:turbo"),
        )
        // Token só com bloco m (sem base) ⇒ null.
        assertNull(GamepadBindingCodec.decode("m=inv"))
    }

    // ── J1 (spec 2026-08-16-J-expressions-dolphin, §2.2): token expr: ──

    @Test
    fun `expr roundtrip e variante selada`() {
        val decoded = GamepadBindingCodec.decode("expr:toggle(face_bottom)")
        assertTrue(decoded is GamepadBindingCodec.LayerBinding.ExprBinding)
        assertEquals("toggle(face_bottom)", (decoded as GamepadBindingCodec.LayerBinding.ExprBinding).source)
        // Acessores de compatibilidade: null/default para expressão.
        assertNull(decoded.raw)
        assertFalse(decoded.turbo)
        assertNull(decoded.mod)
        // expr vazio ⇒ null (degrade).
        assertNull(GamepadBindingCodec.decode("expr:"))
    }

    @Test
    fun `token legado continua byte-identical`() {
        // O decode de tokens físicos continua produzindo LayerBinding.Physical
        // com round-trip intacto (F/H).
        assertEquals("key:96", GamepadBindingCodec.encode(RawBinding.Key(96)))
        assertEquals(
            GamepadBindingCodec.LayerBinding.Physical(RawBinding.Key(96)),
            GamepadBindingCodec.decode("key:96"),
        )
        assertEquals(
            GamepadBindingCodec.LayerBinding.Physical(RawBinding.Axis(17, 1), turbo = true, mod = BindingModifier(invert = true)),
            GamepadBindingCodec.decode("axis:17:1:turbo:m=inv"),
        )
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
