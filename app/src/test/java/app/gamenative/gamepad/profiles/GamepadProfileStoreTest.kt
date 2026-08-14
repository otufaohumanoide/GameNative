package app.gamenative.gamepad.profiles

import app.gamenative.gamepad.DeviceClass
import app.gamenative.gamepad.FaceStyle
import app.gamenative.gamepad.GamepadDevice
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * GamepadProfileStore + merged + ProfileResolver (spec 2026-08-13, Parte I §7 e
 * Passo 6 — D7): padrão PerGameShaderStore (JSON atômico, malformado degrada a vazio),
 * default remove entrada, chave de jogo = appId, merge device→game com game vencendo.
 */
class GamepadProfileStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(fileName: String = "profiles.json") =
        GamepadProfileStore(File(tmp.root, fileName))

    private fun profile(
        faceStyle: FaceStyle? = null,
        swapOkCancel: Boolean? = null,
        leftStickDeadzone: Float? = null,
        rightStickDeadzone: Float? = null,
        layers: Map<String, Map<String, String>> = emptyMap(),
    ) = GamepadProfile(
        faceStyle = faceStyle,
        swapOkCancel = swapOkCancel,
        leftStickDeadzone = leftStickDeadzone,
        rightStickDeadzone = rightStickDeadzone,
        layers = layers,
    )

    private val ds4 = GamepadDevice(
        deviceId = 1,
        descriptor = "stable-descriptor",
        vendorId = 0x054c,
        productId = 0x09cc,
        name = "Wireless Controller",
        deviceClass = DeviceClass.CONTROLLER,
        faceStyle = FaceStyle.PLAYSTATION,
    )

    // ── roundtrip / isolamento ──

    @Test
    fun `save then load roundtrips every field`() {
        val s = store()
        s.save("054c09cc", profile(faceStyle = FaceStyle.PLAYSTATION, leftStickDeadzone = 0.25f))
        val loaded = s.load("054c09cc")!!
        assertEquals(FaceStyle.PLAYSTATION, loaded.faceStyle)
        assertEquals(0.25f, loaded.leftStickDeadzone!!, 0.001f)
        assertNull(loaded.rightStickDeadzone)
    }

    @Test
    fun `device and game scopes are isolated`() {
        val s = store()
        s.save("054c09cc", profile(leftStickDeadzone = 0.3f))
        s.save("STEAM_1293830", profile(swapOkCancel = true))
        assertEquals(0.3f, s.load("054c09cc")?.leftStickDeadzone!!, 0.001f)
        assertEquals(true, s.load("STEAM_1293830")?.swapOkCancel)
        assertNull(s.load("STEAM_1293830")?.leftStickDeadzone)
    }

    // ── cache em memória (spec 2026-08-14-onda2-pos-implementacao, M1 — L1) ──

    @Test
    fun `cache serves reads after the file is deleted`() {
        val s = store()
        s.save("054c09cc", profile(leftStickDeadzone = 0.25f))
        assertEquals(0.25f, s.load("054c09cc")?.leftStickDeadzone!!, 0.001f)
        // O cache da instância serve a leitura seguinte SEM tocar o disco.
        assertTrue(File(tmp.root, "profiles.json").delete())
        assertEquals(0.25f, s.load("054c09cc")?.leftStickDeadzone!!, 0.001f)
        // Uma instância nova (outro processo) vê o estado real (arquivo ausente).
        assertNull(store().load("054c09cc"))
    }

    @Test
    fun `cache reflects save and clear immediately`() {
        val s = store()
        s.save("054c09cc", profile(leftStickDeadzone = 0.25f))
        s.save("054c09cc", profile(leftStickDeadzone = 0.4f))
        assertEquals(0.4f, s.load("054c09cc")?.leftStickDeadzone!!, 0.001f)
        s.save("054c09cc", GamepadProfile()) // default remove — também no cache
        assertNull(s.load("054c09cc"))
        s.save("054c09cc", profile(leftStickDeadzone = 0.3f))
        s.clear("054c09cc")
        assertNull(s.load("054c09cc"))
        // Entradas não relacionadas permanecem no cache após clear da chave alvo.
        s.save("045e028e", profile(leftStickDeadzone = 0.2f))
        s.clear("054c09cc")
        assertEquals(0.2f, s.load("045e028e")?.leftStickDeadzone!!, 0.001f)
    }

    // ── default remove / clear / arquivo some ──

    @Test
    fun `saving a default profile removes the entry and the file`() {
        val s = store()
        s.save("054c09cc", profile(leftStickDeadzone = 0.25f))
        assertTrue(s.load("054c09cc") != null)
        s.save("054c09cc", GamepadProfile())
        assertNull(s.load("054c09cc"))
        assertFalse(File(tmp.root, "profiles.json").exists())
    }

    @Test
    fun `clear removes only the target key`() {
        val s = store()
        s.save("054c09cc", profile(leftStickDeadzone = 0.25f))
        s.save("045e028e", profile(leftStickDeadzone = 0.3f))
        s.clear("054c09cc")
        assertNull(s.load("054c09cc"))
        assertEquals(0.3f, s.load("045e028e")?.leftStickDeadzone!!, 0.001f)
        s.clear("ausente")
        assertEquals(0.3f, s.load("045e028e")?.leftStickDeadzone!!, 0.001f)
    }

    // ── malformado degrada a vazio e recupera ──

    @Test
    fun `malformed json degrades to empty and recovers on next save`() {
        val file = File(tmp.root, "profiles.json")
        file.writeText("{ isto não é json valido")
        val s = store()
        assertNull(s.load("054c09cc"))
        s.save("054c09cc", profile(leftStickDeadzone = 0.25f))
        assertEquals(0.25f, s.load("054c09cc")?.leftStickDeadzone!!, 0.001f)
    }

    @Test
    fun `toJson and fromJson roundtrip`() {
        val p = profile(faceStyle = FaceStyle.NINTENDO, swapOkCancel = true, layers = mapOf("DEFAULT" to mapOf("FACE_BOTTOM" to "key:96")))
        val json = p.toJson()
        val back = GamepadProfile.fromJson(json)!!
        assertEquals(p, back)
        assertNull(GamepadProfile.fromJson("{ inválido"))
    }

    // ── merged: device → game, game vence ──

    @Test
    fun `merged with no game returns the device profile`() {
        val device = profile(leftStickDeadzone = 0.25f, faceStyle = FaceStyle.PLAYSTATION)
        val merged = GamepadProfileStore.merged(device, null)
        assertEquals(0.25f, merged.leftStickDeadzone!!, 0.001f)
        assertEquals(FaceStyle.PLAYSTATION, merged.faceStyle)
    }

    @Test
    fun `merged with no device returns the game profile`() {
        val game = profile(swapOkCancel = true)
        val merged = GamepadProfileStore.merged(null, game)
        assertEquals(true, merged.swapOkCancel)
    }

    @Test
    fun `merged game wins field by field and nulls preserve the device`() {
        val device = profile(leftStickDeadzone = 0.25f, rightStickDeadzone = 0.3f, faceStyle = FaceStyle.PLAYSTATION)
        val game = profile(leftStickDeadzone = 0.4f, swapOkCancel = true)
        val merged = GamepadProfileStore.merged(device, game)
        assertEquals(0.4f, merged.leftStickDeadzone!!, 0.001f)   // game vence
        assertEquals(0.3f, merged.rightStickDeadzone!!, 0.001f)  // null preserva device
        assertEquals(FaceStyle.PLAYSTATION, merged.faceStyle)    // null preserva device
        assertEquals(true, merged.swapOkCancel)
    }

    @Test
    fun `merged layers game replaces only when non empty`() {
        val device = profile(layers = mapOf("DEFAULT" to mapOf("FACE_BOTTOM" to "key:96")))
        val game = profile(layers = mapOf("DEFAULT" to mapOf("FACE_BOTTOM" to "key:97")))
        assertEquals("key:97", GamepadProfileStore.merged(device, game).layers["DEFAULT"]!!["FACE_BOTTOM"])
        assertEquals("key:96", GamepadProfileStore.merged(device, null).layers["DEFAULT"]!!["FACE_BOTTOM"])
    }

    // ── ProfileResolver ──

    @Test
    fun `resolver uses mappingKey for the device and appId for the game`() {
        val deviceStore = store("device.json")
        val gameStore = store("game.json")
        deviceStore.save(ds4.mappingKey, profile(leftStickDeadzone = 0.25f))
        gameStore.save("STEAM_1", profile(leftStickDeadzone = 0.45f))

        val resolved = ProfileResolver.resolve(ds4, "STEAM_1", deviceStore, gameStore)
        assertEquals(0.45f, resolved.leftStickDeadzone!!, 0.001f) // game vence

        val withoutGame = ProfileResolver.resolve(ds4, null, deviceStore, gameStore)
        assertEquals(0.25f, withoutGame.leftStickDeadzone!!, 0.001f)

        val otherGame = ProfileResolver.resolve(ds4, "GOG_2", deviceStore, gameStore)
        assertEquals(0.25f, otherGame.leftStickDeadzone!!, 0.001f) // sem entrada → device
    }
}
