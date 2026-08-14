package app.gamenative.gamepad.radial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** F3.1 (spec 2026-08-15-input-core-avancado): geometria, plano e store. */
class RadialMenuCoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `angulo zero e cima e setores contam em sentido horario`() {
        assertEquals(0, RadialMenuGeometry.sectorIndex(0f, 8))
        assertEquals(1, RadialMenuGeometry.sectorIndex(45f, 8))
        assertEquals(2, RadialMenuGeometry.sectorIndex(90f, 8))
        assertEquals(7, RadialMenuGeometry.sectorIndex(315f, 8))
        assertEquals(7, RadialMenuGeometry.sectorIndex(359f, 8))
    }

    @Test
    fun `angulos negativos e acima de 360 normalizam`() {
        assertEquals(7, RadialMenuGeometry.sectorIndex(-1f, 8))
        assertEquals(0, RadialMenuGeometry.sectorIndex(360f, 8))
        assertEquals(2, RadialMenuGeometry.sectorIndex(360f + 90f, 8))
    }

    @Test
    fun `contagem zero degrada`() {
        assertEquals(-1, RadialMenuGeometry.sectorIndex(90f, 0))
    }

    @Test
    fun `vetor do stick mapeia direcao`() {
        assertEquals(0f, RadialMenuGeometry.angleOf(0f, -1f), 0.01f)
        assertEquals(90f, RadialMenuGeometry.angleOf(1f, 0f), 0.01f)
        assertEquals(180f, RadialMenuGeometry.angleOf(0f, 1f), 0.01f)
        assertEquals(270f, RadialMenuGeometry.angleOf(-1f, 0f), 0.01f)
    }

    @Test
    fun `plano acumula timing de hold e gap`() {
        val keys = listOf(
            RadialMacroKey(keyCode = 96, holdMs = 60L, gapMs = 40L),
            RadialMacroKey(keyCode = 97, holdMs = 50L, gapMs = 0L),
        )
        val plan = RadialMenuPlan.plan(keys)
        assertEquals(2, plan.size)
        assertEquals(0L, plan[0].downAtMs)
        assertEquals(60L, plan[0].upAtMs)
        assertEquals(100L, plan[1].downAtMs)
        assertEquals(150L, plan[1].upAtMs)
        assertEquals(150L, RadialMenuPlan.totalMs(keys))
    }

    @Test
    fun `plano vazio e zero e sem teclas`() {
        assertEquals(0, RadialMenuPlan.plan(emptyList()).size)
        assertEquals(0L, RadialMenuPlan.totalMs(emptyList()))
    }

    @Test
    fun `json roundtrip preserva setores e macros`() {
        val config = RadialMenuConfig(
            triggerLayer = "LAYER_1",
            sectors = listOf(
                RadialSector("Pular", listOf(RadialMacroKey(96, 60L, 40L)), 1),
                RadialSector("Mapa", emptyList(), 3),
            ),
        )
        val decoded = RadialMenuConfig.fromJson(config.toJson())
        assertNotNull(decoded)
        assertEquals(config, decoded)
    }

    @Test
    fun `json malformado degrada a null`() {
        assertNull(RadialMenuConfig.fromJson("nao-e-json"))
        assertNull(RadialMenuConfig.fromJson(""))
    }

    @Test
    fun `store salva e carrega por appId`() {
        val store = RadialMenuStore(tmp.newFile("radial.json"))
        val config = RadialMenuConfig(
            triggerLayer = "LAYER_1",
            sectors = listOf(RadialSector("A", listOf(RadialMacroKey(96)))),
        )
        store.save("STEAM_1030300", config)
        val loaded = store.load("STEAM_1030300")
        assertEquals(config, loaded)
        assertNull(store.load("outro_jogo"))
    }

    @Test
    fun `store default remove a entrada`() {
        val file = tmp.newFile("radial.json")
        val store = RadialMenuStore(file)
        val config = RadialMenuConfig(
            triggerLayer = "LAYER_1",
            sectors = listOf(RadialSector("A", listOf(RadialMacroKey(96)))),
        )
        store.save("STEAM_1030300", config)
        store.save("STEAM_1030300", RadialMenuConfig())
        assertNull(store.load("STEAM_1030300"))
        assertTrue(!file.exists())
    }
}
