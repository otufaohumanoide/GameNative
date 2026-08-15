package app.gamenative.gamepad.profiles

import app.gamenative.gamepad.DeviceClass
import app.gamenative.gamepad.FaceStyle
import app.gamenative.gamepad.GamepadDevice
import app.gamenative.gamepad.GyroMode
import app.gamenative.gamepad.layers.LayerTriggerMode
import app.gamenative.gamepad.layers.LayerTriggerSpec
import app.gamenative.gamepad.radial.RadialMacroKey
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
        touchpadSwipes: Map<String, List<RadialMacroKey>>? = null,
    ) = GamepadProfile(
        faceStyle = faceStyle,
        swapOkCancel = swapOkCancel,
        leftStickDeadzone = leftStickDeadzone,
        rightStickDeadzone = rightStickDeadzone,
        layers = layers,
        touchpadSwipes = touchpadSwipes,
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

    // ── V1 (spec 2026-08-14-gamepad-intuito-validacao-upgrades): chaves
    // desconhecidas sobrevivem ao save (downgrade de build real) ──

    @Test
    fun `V1 - save preserves unknown keys from newer builds`() {
        val file = File(tmp.root, "profiles.json")
        // Arquivo gravado por um build FUTURO: chaves fora do schema conhecido.
        file.writeText("""{"054c09cc":{"leftStickDeadzone":0.25,"futureField":42,"futureObj":{"a":1}}}""")
        val s = store()
        assertEquals(0.25f, s.load("054c09cc")?.leftStickDeadzone!!, 0.001f)
        s.save("054c09cc", profile(leftStickDeadzone = 0.4f))
        val text = file.readText()
        assertTrue(text, text.contains("\"futureField\":42"))
        assertTrue(text, text.contains("\"futureObj\":{\"a\":1}"))
        assertEquals(0.4f, s.load("054c09cc")?.leftStickDeadzone!!, 0.001f)
        // Outras entradas (sem extras) seguem normais.
        s.save("045e028e", profile(swapOkCancel = true))
        assertTrue(file.readText().contains("\"swapOkCancel\":true"))
    }

    @Test
    fun `V1 - deleting an entry removes its unknown keys too`() {
        val file = File(tmp.root, "profiles.json")
        file.writeText("""{"054c09cc":{"leftStickDeadzone":0.25,"futureField":42}}""")
        val s = store()
        s.save("054c09cc", GamepadProfile()) // default → remove a entrada inteira
        assertFalse(file.exists())
    }

    @Test
    fun `V1 - unknown keys preserved across multiple saves`() {
        val file = File(tmp.root, "profiles.json")
        file.writeText("""{"054c09cc":{"futureField":42}}""")
        val s = store()
        s.save("054c09cc", profile(swapOkCancel = true))
        s.save("054c09cc", profile(swapOkCancel = false))
        assertTrue(file.readText().contains("\"futureField\":42"))
    }

    // ── U1/U3/U5 — campos novos (V1 no lugar): roundtrip, default e merge ──

    @Test
    fun `U1 - gyro fields roundtrip and merge`() {
        val s = store()
        s.save(
            "054c09cc",
            GamepadProfile(
                gyroMode = GyroMode.MOUSE,
                gyroSensitivity = 2f,
                gyroDeadzone = 0.1f,
                gyroActivateButton = "FACE_TOP",
            ),
        )
        val loaded = s.load("054c09cc")!!
        assertEquals(GyroMode.MOUSE, loaded.gyroMode)
        assertEquals(2f, loaded.gyroSensitivity!!, 0.001f)
        assertEquals(0.1f, loaded.gyroDeadzone!!, 0.001f)
        assertEquals("FACE_TOP", loaded.gyroActivateButton)
        // Merge: game vence campo a campo; null preserva device.
        val merged = GamepadProfileStore.merged(loaded, GamepadProfile(gyroMode = GyroMode.CAMERA))
        assertEquals(GyroMode.CAMERA, merged.gyroMode)
        assertEquals(2f, merged.gyroSensitivity!!, 0.001f)
    }

    @Test
    fun `U3 - merged layers is granular - game adds layers without erasing device ones`() {
        val device = profile(layers = mapOf("DEFAULT" to mapOf("FACE_BOTTOM" to "key:96")))
        val game = profile(layers = mapOf("SPRINT" to mapOf("FACE_BOTTOM" to "key:97")))
        val merged = GamepadProfileStore.merged(device, game)
        assertEquals("key:96", merged.layers["DEFAULT"]!!["FACE_BOTTOM"])
        assertEquals("key:97", merged.layers["SPRINT"]!!["FACE_BOTTOM"])
        // layerTriggers seguem o mesmo merge granular.
        val d2 = GamepadProfile(layerTriggers = mapOf("SPRINT" to LayerTriggerSpec("LEFT_BUMPER", LayerTriggerMode.HOLD)))
        val g2 = GamepadProfile(layerTriggers = mapOf("SNIPER" to LayerTriggerSpec("RIGHT_STICK", LayerTriggerMode.TOGGLE)))
        val m2 = GamepadProfileStore.merged(d2, g2)
        assertEquals(2, m2.layerTriggers.size)
        assertEquals(LayerTriggerMode.TOGGLE, m2.layerTriggers["SNIPER"]!!.mode)
    }

    @Test
    fun `U1 - default detection includes new fields`() {
        assertTrue(GamepadProfile().isDefault())
        assertFalse(GamepadProfile(gyroMode = GyroMode.MOUSE).isDefault())
        assertFalse(GamepadProfile(gyroSensitivity = 2f).isDefault())
        assertFalse(GamepadProfile(rumbleOnBack = false).isDefault())
        assertFalse(
            GamepadProfile(
                layerTriggers = mapOf("X" to LayerTriggerSpec("FACE_BOTTOM", LayerTriggerMode.TOGGLE)),
            ).isDefault(),
        )
    }

    // ── D (spec 2026-08-16-D-touchpad-swipes-macros): touchpadSwipes — roundtrip,
    // merge por direção e default ──

    @Test
    fun `D - touchpad swipes roundtrip and null default`() {
        val s = store()
        val right = listOf(RadialMacroKey(96))
        s.save("054c09cc", profile(touchpadSwipes = mapOf("RIGHT" to right)))
        val loaded = s.load("054c09cc")!!
        assertEquals(1, loaded.touchpadSwipes!!.size)
        assertEquals(96, loaded.touchpadSwipes!!["RIGHT"]!![0].keyCode)
        // Campos novos com default: perfil vazio segue null (OFF = byte-identical).
        assertNull(GamepadProfile().touchpadSwipes)
        assertNull(s.load("045e028e")?.touchpadSwipes)
    }

    @Test
    fun `D - merged swipes is a union by direction with the game winning`() {
        val device = profile(
            touchpadSwipes = mapOf(
                "RIGHT" to listOf(RadialMacroKey(96)),
                "LEFT" to listOf(RadialMacroKey(97)),
            ),
        )
        val game = profile(touchpadSwipes = mapOf("LEFT" to listOf(RadialMacroKey(98))))
        val merged = GamepadProfileStore.merged(device, game)
        assertEquals(2, merged.touchpadSwipes!!.size)
        assertEquals(listOf(RadialMacroKey(96)), merged.touchpadSwipes!!["RIGHT"]) // device preservado
        assertEquals(listOf(RadialMacroKey(98)), merged.touchpadSwipes!!["LEFT"])  // jogo vence
        // Jogo sem swipes preserva o device; device sem swipes preserva o jogo.
        assertEquals(2, GamepadProfileStore.merged(device, null).touchpadSwipes!!.size)
        assertEquals(listOf(RadialMacroKey(98)), GamepadProfileStore.merged(null, game).touchpadSwipes!!["LEFT"])
        assertNull(GamepadProfileStore.merged(null, null).touchpadSwipes)
    }

    @Test
    fun `D - default detection includes swipes`() {
        // null E vazio = OFF (mesma convenção de layers).
        assertTrue(GamepadProfile().isDefault())
        assertTrue(GamepadProfile(touchpadSwipes = emptyMap()).isDefault())
        assertFalse(GamepadProfile(touchpadSwipes = mapOf("UP" to listOf(RadialMacroKey(96)))).isDefault())
        // save de perfil com mapa vazio remove a entrada (default).
        val s = store()
        s.save("054c09cc", profile(touchpadSwipes = emptyMap()))
        assertNull(s.load("054c09cc"))
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
