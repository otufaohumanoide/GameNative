package app.gamenative.gamepad.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E (spec 2026-08-16-E-profile-catalog-comunitario, §1.2): parser PURO do catálogo —
 * robustez (nunca exceção, inválidos contados), busca por tokens, forGame e o
 * diff-resumo por categoria (summaryOf).
 */
class ProfileCatalogTest {

    private val validEntry = """
        {
          "id": "silk-gyro",
          "game": "silksong",
          "faceStyle": "PLAYSTATION",
          "controller": "DualShock 4",
          "name": "Gyro aim",
          "author": "seed",
          "description": "Gyro as mouse.",
          "downloads": 12,
          "profile": {
            "gyroMode": "MOUSE",
            "gyroSensitivity": 1.4,
            "touchpadSwipes": {
              "RIGHT": [{"keyCode": -1000, "holdMs": 60, "gapMs": 40}],
              "UP": [{"keyCode": 96}]
            },
            "schemaVersion": 1
          }
        }
    """

    private val minimalEntry = """
        { "id": "b", "game": null, "name": "Haptics off", "author": "seed",
          "profile": { "rumbleOnActivate": false, "rumbleOnBack": false } }
    """

    private val unknownProfileFieldEntry = """
        { "id": "c", "game": "other", "name": "Bad field", "author": "seed",
          "profile": { "turboPeriodMs": 40 } }
    """

    private val notAnObjectEntry = """ "just-a-string" """

    private val fullEntry = """
        {
          "id": "full",
          "game": "silksong",
          "faceStyle": "PLAYSTATION",
          "controller": "DS4",
          "name": "Full",
          "author": "seed",
          "description": "Tudo ligado.",
          "profile": {
            "faceStyle": "PLAYSTATION",
            "swapOkCancel": true,
            "layers": {"DEFAULT": {"RIGHT_BUMPER": "key:96"}},
            "gyroMode": "MOUSE",
            "layerTriggers": {"FACE_LEFT": {"button": "FACE_LEFT", "mode": "HOLD", "isShift": true}},
            "rumbleOnActivate": false,
            "touchpadDoubleTapRightClick": true,
            "touchpadSwipes": {"RIGHT": [{"keyCode": -1000}], "UP": [{"keyCode": 96}]},
            "leftStickCurve": "EXPONENTIAL",
            "leftStickLut": [0.0, 0.5, 1.0],
            "flickStickEnabled": true,
            "gyroFusionEnabled": true,
            "schemaVersion": 1
          }
        }
    """

    private fun catalogOf(entries: List<String>): String =
        """{ "generatedFrom": "local-seed-v1", "schemaVersion": 1,
             "profiles": [${entries.joinToString(",")}] }"""

    @Test
    fun `parse valida e conta invalidos sem excecao`() {
        // unknownProfileFieldEntry: o parser Kotlin é LENIENTE (ignoreUnknownKeys —
        // o sync tool é quem rejeita campos fora da allowlist na GERAÇÃO do asset);
        // campo extra de perfil é ignorado, a entry sobrevive.
        val result = ProfileCatalog.parse(
            catalogOf(listOf(validEntry, minimalEntry, unknownProfileFieldEntry, notAnObjectEntry)),
        )
        assertEquals(4, result.parsedCount)
        assertEquals(1, result.invalidCount)
        assertEquals(listOf("silk-gyro", "b", "c"), result.entries.map { it.id })
    }

    @Test
    fun `parse de lixo nunca lanca`() {
        val empty = ProfileCatalog.parse("")
        assertEquals(0, empty.parsedCount)
        assertTrue(empty.entries.isEmpty())
        val garbage = ProfileCatalog.parse("not json at all {{{")
        assertTrue(garbage.entries.isEmpty())
        val wrongShape = ProfileCatalog.parse("""{"profiles": {}}""")
        assertEquals(0, wrongShape.parsedCount)
    }

    @Test
    fun `parse preserva perfil completo com swipes camadas e lut`() {
        val result = ProfileCatalog.parse(catalogOf(listOf(fullEntry)))
        assertEquals(1, result.entries.size)
        val entry = result.entries.single()
        assertEquals("full", entry.id)
        assertEquals("silksong", entry.game)
        val profile = entry.profile
        assertEquals(true, profile.swapOkCancel)
        assertEquals("key:96", profile.layers["DEFAULT"]?.get("RIGHT_BUMPER"))
        assertEquals(true, profile.layerTriggers["FACE_LEFT"]?.isShift)
        assertEquals(-1000, profile.touchpadSwipes?.get("RIGHT")?.single()?.keyCode)
        assertEquals(96, profile.touchpadSwipes?.get("UP")?.single()?.keyCode)
        assertEquals(3, profile.leftStickLut?.size)
        assertEquals(true, profile.flickStickEnabled)
        assertEquals(true, profile.gyroFusionEnabled)
    }

    @Test
    fun `entry com campos extras sobrevive ignoreUnknownKeys`() {
        val extra = """
            { "id": "x", "name": "Extra", "author": "seed",
              "futureField": {"nested": true},
              "profile": { "gyroMode": "CAMERA", "newerProfileField": 1 } }
        """
        val result = ProfileCatalog.parse(catalogOf(listOf(extra)))
        assertEquals(1, result.entries.size)
        assertEquals("CAMERA", result.entries.single().profile.gyroMode?.name)
    }

    @Test
    fun `search por tokens case insensitive sobre os campos`() {
        val profiles = ProfileCatalog.parse(catalogOf(listOf(validEntry, minimalEntry))).entries
        // game
        assertEquals(1, ProfileCatalog.search(profiles, "SILKSONG").size)
        // nome
        assertEquals(1, ProfileCatalog.search(profiles, "gyro aim").size)
        // controle
        assertEquals(1, ProfileCatalog.search(profiles, "dualshock").size)
        // autor
        assertEquals(2, ProfileCatalog.search(profiles, "seed").size)
        // descrição
        assertEquals(1, ProfileCatalog.search(profiles, "mouse").size)
        // tokens: TODOS precisam casar
        assertEquals(0, ProfileCatalog.search(profiles, "silksong haptics").size)
        // blank = todos
        assertEquals(2, ProfileCatalog.search(profiles, "   ").size)
    }

    @Test
    fun `forGame casa exato case-insensitive e universal nao casa`() {
        val profiles = ProfileCatalog.parse(
            catalogOf(listOf(validEntry, minimalEntry, fullEntry)),
        ).entries
        assertEquals(listOf("silk-gyro", "full"), ProfileCatalog.forGame(profiles, "SilkSong").map { it.id })
        assertEquals(0, ProfileCatalog.forGame(profiles, "other").size)
        assertEquals(0, ProfileCatalog.forGame(profiles, null).size)
        // universal (game null) nunca casa — aparece na lista geral/busca.
        assertTrue(ProfileCatalog.forGame(profiles, "silksong").all { it.game != null })
    }

    @Test
    fun `summaryOf lista as categorias tocadas na ordem fixa`() {
        val profile = ProfileCatalog.parse(catalogOf(listOf(fullEntry))).entries.single().profile
        assertEquals(
            listOf(
                ProfileSummaryCategory.BINDINGS,
                ProfileSummaryCategory.GYRO,
                ProfileSummaryCategory.LAYERS,
                ProfileSummaryCategory.SWIPES,
                ProfileSummaryCategory.STICK,
                ProfileSummaryCategory.RUMBLE,
                ProfileSummaryCategory.TOUCHPAD,
            ),
            ProfileCatalog.summaryOf(profile),
        )
    }

    @Test
    fun `summaryOf de perfil vazio e vazio`() {
        assertTrue(ProfileCatalog.summaryOf(GamepadProfile()).isEmpty())
    }

    @Test
    fun `summaryOf conta modificadores por binding como STICK`() {
        // H (spec 2026-08-16-H-binding-modifiers-duckstation, §2.4): o sufixo :m=
        // nos tokens das camadas conta como STICK — sem categoria nova.
        val modsOnly = GamepadProfile(
            layers = mapOf("DEFAULT" to mapOf("LEFT_TRIGGER" to "axis:17:1:m=full,s130,dz5")),
        )
        assertEquals(
            listOf(ProfileSummaryCategory.BINDINGS, ProfileSummaryCategory.STICK),
            ProfileCatalog.summaryOf(modsOnly),
        )
        // Token sem bloco m= → SÓ BINDINGS (comportamento anterior preservado).
        val plainBindings = GamepadProfile(
            layers = mapOf("DEFAULT" to mapOf("RIGHT_BUMPER" to "key:96")),
        )
        assertEquals(listOf(ProfileSummaryCategory.BINDINGS), ProfileCatalog.summaryOf(plainBindings))
    }

    @Test
    fun `summaryOf conta tokens expr como categoria EXPR`() {
        // J1 (spec 2026-08-16-J-expressions-dolphin, §2.4): categoria nova EXPR.
        val exprOnly = GamepadProfile(
            layers = mapOf("DEFAULT" to mapOf("FACE_TOP" to "expr:face_bottom and axis:left_y > 0.7")),
        )
        assertEquals(
            listOf(ProfileSummaryCategory.BINDINGS, ProfileSummaryCategory.EXPR),
            ProfileCatalog.summaryOf(exprOnly),
        )
        val plainBindings = GamepadProfile(
            layers = mapOf("DEFAULT" to mapOf("RIGHT_BUMPER" to "key:96")),
        )
        assertTrue(ProfileSummaryCategory.EXPR !in ProfileCatalog.summaryOf(plainBindings))
    }

    @Test
    fun `summaryOf detecta categorias parciais`() {
        val swipesOnly = GamepadProfile(
            touchpadSwipes = mapOf("UP" to listOf(app.gamenative.gamepad.radial.RadialMacroKey(96))),
        )
        assertEquals(listOf(ProfileSummaryCategory.SWIPES), ProfileCatalog.summaryOf(swipesOnly))

        val gyroOff = GamepadProfile(gyroMode = app.gamenative.gamepad.GyroMode.OFF)
        // Desligar o gyro TOCA o gyro (decisão do impl: nenhum resumo vazio para
        // perfis que só mudam uma chave).
        assertEquals(listOf(ProfileSummaryCategory.GYRO), ProfileCatalog.summaryOf(gyroOff))

        val stickOnly = GamepadProfile(leftStickCurve = app.gamenative.gamepad.processing.ResponseCurve.EXPONENTIAL)
        assertEquals(listOf(ProfileSummaryCategory.STICK), ProfileCatalog.summaryOf(stickOnly))
    }
}
