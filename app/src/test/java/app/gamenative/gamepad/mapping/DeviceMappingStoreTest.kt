package app.gamenative.gamepad.mapping

import app.gamenative.gamepad.FaceStyle
import app.gamenative.gamepad.GamepadAxis
import app.gamenative.gamepad.GamepadButton
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * DeviceMappingStore (spec 2026-08-16-K5, §1.1): um JSON por device em
 * `<dir>/<mappingKey>.json`, política V1 (ignoreUnknownKeys + chaves desconhecidas
 * preservadas no save), delete trivial, malformado degrada a vazio e se recupera no
 * próximo save (padrão GamepadProfileStore).
 */
class DeviceMappingStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = DeviceMappingStore(File(tmp.root, "deviceMappings"))

    private fun config(
        mappingKey: String = "054c09cc",
        deviceName: String = "Wireless Controller",
        mapping: GamepadMapping = ds4Mapping(),
        faceStyle: FaceStyle = FaceStyle.PLAYSTATION,
        createdAtMs: Long = 1755000000000L,
    ) = DeviceAutoconfig(
        mappingKey = mappingKey,
        deviceName = deviceName,
        mapping = mapping,
        faceStyle = faceStyle,
        createdAtMs = createdAtMs,
    )

    private fun ds4Mapping() = GamepadMapping(
        mappingKey = "054c09cc",
        name = "Sony DualShock 4",
        faceStyle = FaceStyle.PLAYSTATION,
        buttons = mapOf(
            GamepadButton.FACE_BOTTOM to RawBinding.Key(96),
            GamepadButton.FACE_RIGHT to RawBinding.Key(97),
            GamepadButton.FACE_LEFT to RawBinding.Key(99),
            GamepadButton.FACE_TOP to RawBinding.Key(100),
            GamepadButton.DPAD_UP to RawBinding.Key(19),
            GamepadButton.DPAD_DOWN to RawBinding.Key(20),
            GamepadButton.DPAD_LEFT to RawBinding.Key(21),
            GamepadButton.DPAD_RIGHT to RawBinding.Key(22),
            GamepadButton.LEFT_BUMPER to RawBinding.Key(102),
            GamepadButton.RIGHT_BUMPER to RawBinding.Key(103),
            GamepadButton.LEFT_TRIGGER to RawBinding.Axis(AndroidConstants.AXIS_LTRIGGER, +1),
            GamepadButton.RIGHT_TRIGGER to RawBinding.Axis(AndroidConstants.AXIS_RTRIGGER, +1),
            GamepadButton.LEFT_STICK to RawBinding.Key(106),
            GamepadButton.RIGHT_STICK to RawBinding.Key(107),
            GamepadButton.START to RawBinding.Key(108),
            GamepadButton.SELECT to RawBinding.Key(109),
            GamepadButton.GUIDE to RawBinding.Key(110),
        ),
        axes = mapOf(
            GamepadAxis.LEFT_X to RawBinding.Axis(AndroidConstants.AXIS_X, +1),
            GamepadAxis.LEFT_Y to RawBinding.Axis(AndroidConstants.AXIS_Y, +1),
            GamepadAxis.RIGHT_X to RawBinding.Axis(AndroidConstants.AXIS_Z, +1),
            GamepadAxis.RIGHT_Y to RawBinding.Axis(AndroidConstants.AXIS_RZ, +1),
        ),
    )

    // ── round-trip ──

    @Test
    fun `save then load roundtrips every field`() {
        val s = store()
        s.save(config())
        val loaded = s.load("054c09cc")!!
        assertEquals("054c09cc", loaded.mappingKey)
        assertEquals("Wireless Controller", loaded.deviceName)
        assertEquals(FaceStyle.PLAYSTATION, loaded.faceStyle)
        assertEquals(1755000000000L, loaded.createdAtMs)
        assertEquals(1, loaded.schemaVersion)
        val mapping = loaded.mapping
        assertEquals("Sony DualShock 4", mapping.name)
        assertEquals(FaceStyle.PLAYSTATION, mapping.faceStyle)
        assertEquals(RawBinding.Key(96), mapping.buttons[GamepadButton.FACE_BOTTOM])
        assertEquals(RawBinding.Key(22), mapping.buttons[GamepadButton.DPAD_RIGHT])
        assertEquals(
            RawBinding.Axis(AndroidConstants.AXIS_LTRIGGER, +1),
            mapping.buttons[GamepadButton.LEFT_TRIGGER],
        )
        assertEquals(RawBinding.Axis(AndroidConstants.AXIS_Z, +1), mapping.axes[GamepadAxis.RIGHT_X])
    }

    @Test
    fun `schemaVersion defaults to 1 when absent from the file`() {
        val dir = File(tmp.root, "deviceMappings")
        dir.mkdirs()
        File(dir, "054c09cc.json").writeText(
            """{"mappingKey":"054c09cc","deviceName":"X","mapping":{"mappingKey":"054c09cc","name":"X","faceStyle":"GENERIC","buttons":{"FACE_BOTTOM":{"type":"app.gamenative.gamepad.mapping.RawBinding.Key","keyCode":96}},"axes":{}},"faceStyle":"GENERIC","createdAtMs":1}""",
        )
        assertEquals(1, store().load("054c09cc")?.schemaVersion)
    }

    // ── isolamento por chave ──

    @Test
    fun `entries are isolated per mappingKey`() {
        val s = store()
        s.save(config(mappingKey = "054c09cc", deviceName = "A"))
        s.save(config(mappingKey = "045e028e", deviceName = "B"))
        assertEquals("A", s.load("054c09cc")?.deviceName)
        assertEquals("B", s.load("045e028e")?.deviceName)
        assertNull(s.load("deadbeef"))
    }

    // ── cache em memória (padrão M1 do GamepadProfileStore) ──

    @Test
    fun `cache serves reads after the file is deleted`() {
        val s = store()
        s.save(config())
        assertEquals("Wireless Controller", s.load("054c09cc")?.deviceName)
        assertTrue(File(tmp.root, "deviceMappings/054c09cc.json").delete())
        assertEquals("Wireless Controller", s.load("054c09cc")?.deviceName)
        assertNull(store().load("054c09cc"))
    }

    @Test
    fun `cache reflects save and delete immediately`() {
        val s = store()
        s.save(config(deviceName = "A"))
        s.save(config(deviceName = "B"))
        assertEquals("B", s.load("054c09cc")?.deviceName)
        s.delete("054c09cc")
        assertNull(s.load("054c09cc"))
        s.save(config(deviceName = "C"))
        assertEquals("C", s.load("054c09cc")?.deviceName)
        assertEquals("C", store().load("054c09cc")?.deviceName)
    }

    // ── delete / list ──

    @Test
    fun `delete removes the file`() {
        val s = store()
        s.save(config())
        assertTrue(s.load("054c09cc") != null)
        s.delete("054c09cc")
        assertNull(s.load("054c09cc"))
        assertFalse(File(tmp.root, "deviceMappings/054c09cc.json").exists())
    }

    @Test
    fun `delete of absent key is a no-op`() {
        val s = store()
        s.delete("ffffffff")
        assertTrue(s.list().isEmpty())
    }

    @Test
    fun `list returns saved configs sorted by key and honors delete`() {
        val s = store()
        s.save(config(mappingKey = "054c09cc", deviceName = "A"))
        s.save(config(mappingKey = "045e028e", deviceName = "B"))
        assertEquals(listOf("045e028e", "054c09cc"), s.list().map { it.mappingKey })
        s.delete("054c09cc")
        assertEquals(listOf("045e028e"), s.list().map { it.mappingKey })
    }

    // ── degradação de conteúdo malformado (padrão do repo) ──

    @Test
    fun `malformed file degrades to null and recovers on next save`() {
        val dir = File(tmp.root, "deviceMappings")
        dir.mkdirs()
        File(dir, "054c09cc.json").writeText("{not json")
        assertNull(store().load("054c09cc"))
        val s = store()
        s.save(config())
        assertEquals("Wireless Controller", s.load("054c09cc")?.deviceName)
        assertEquals("Wireless Controller", store().load("054c09cc")?.deviceName)
    }

    // ── V1 (política obrigatória do repo): chaves desconhecidas sobrevivem ──

    @Test
    fun `V1 - save preserves unknown keys from newer builds`() {
        val s = store()
        s.save(config())
        // Arquivo gravado por um build FUTURO: chave fora do schema conhecido.
        val file = File(tmp.root, "deviceMappings/054c09cc.json")
        file.writeText(file.readText().trimEnd().dropLast(1) + ",\"futureField\":42}")
        // Instância nova (outro processo/build): lê ignorando a desconhecida.
        val fresh = store()
        assertEquals("Wireless Controller", fresh.load("054c09cc")?.deviceName)
        fresh.save(config(deviceName = "Renamed"))
        val text = file.readText()
        assertTrue(text, text.contains("\"futureField\":42"))
        assertEquals("Renamed", fresh.load("054c09cc")?.deviceName)
    }

    @Test
    fun `V1 - unknown keys preserved across multiple saves`() {
        val dir = File(tmp.root, "deviceMappings")
        dir.mkdirs()
        File(dir, "054c09cc.json").writeText(
            """{"mappingKey":"054c09cc","deviceName":"X","mapping":{"mappingKey":"054c09cc","name":"X","faceStyle":"GENERIC","buttons":{},"axes":{}},"faceStyle":"GENERIC","createdAtMs":1,"futureField":42}""",
        )
        val s = store()
        s.save(config(deviceName = "A"))
        s.save(config(deviceName = "B"))
        assertTrue(File(dir, "054c09cc.json").readText().contains("\"futureField\":42"))
    }

    @Test
    fun `V1 - delete removes the unknown keys too`() {
        val s = store()
        s.save(config())
        val file = File(tmp.root, "deviceMappings/054c09cc.json")
        file.writeText(file.readText().trimEnd().dropLast(1) + ",\"futureField\":42}")
        store().delete("054c09cc")
        assertFalse(file.exists())
    }
}
