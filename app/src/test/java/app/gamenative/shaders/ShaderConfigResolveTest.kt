package app.gamenative.shaders

import com.winlator.renderer.RetroArchShaderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Spec §6: migration of configs from the old embedded-preset system.
 * Cases 1–4 as pure function tests (no Android, no renderer).
 */
class ShaderConfigResolveTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun config(
        enabled: Boolean = true,
        presetPath: String = "",
        presetName: String = "",
        relativePath: String = "",
    ) = RetroArchShaderConfig(enabled, presetPath, presetName, "", relativePath)

    @Test
    fun `case1 absolute path still exists is loaded unchanged`() {
        val preset = tmp.newFile("crt-easymode.slangp")
        val resolved = resolveShaderConfig(config(presetPath = preset.absolutePath), packDir = null)
        assertEquals(preset.absolutePath, resolved.presetPath)
        assert(resolved.enabled)
    }

    @Test
    fun `case2 missing absolute re-resolves relative path inside installed pack`() {
        val packDir = tmp.newFolder("pack")
        val preset = File(packDir, "crt/easymode.slangp").apply {
            parentFile.mkdirs()
            writeText("shaders")
        }
        val oldPath = File(tmp.root, "retroarch_presets/crt/easymode.slangp").absolutePath
        val resolved = resolveShaderConfig(
            config(presetPath = oldPath, presetName = "Easymode", relativePath = "crt/easymode.slangp"),
            packDir = packDir,
        )
        assertEquals(preset.absolutePath, resolved.presetPath)
        assertEquals("crt/easymode.slangp", resolved.relativePath)
        assertEquals("Easymode", resolved.presetName)
    }

    @Test
    fun `case3 relative path does not resolve keeps selection but clears absolute path`() {
        // Pack not installed at all.
        val resolved = resolveShaderConfig(
            config(
                presetPath = "/data/user/0/app.gamenative/files/retroarch_presets/crt/easymode.slangp",
                presetName = "Easymode",
                relativePath = "crt/easymode.slangp",
            ),
            packDir = null,
        )
        assertEquals("", resolved.presetPath)
        assertEquals("crt/easymode.slangp", resolved.relativePath)
        assertEquals("Easymode", resolved.presetName)
        assert(resolved.enabled) // stays enabled; renderer simply runs without preset

        // Pack installed but the file is not in it.
        val packDir = tmp.newFolder("pack2")
        val resolved2 = resolveShaderConfig(
            config(presetPath = "/gone.slangp", relativePath = "ntsc/blargg.slangp"),
            packDir = packDir,
        )
        assertEquals("", resolved2.presetPath)
        assertEquals("ntsc/blargg.slangp", resolved2.relativePath)
    }

    @Test
    fun `case4 legacy config without relative path is cleared and stays visible`() {
        val resolved = resolveShaderConfig(
            config(
                presetPath = "/data/user/0/app.gamenative/files/retroarch_presets/misc/invert.slangp",
                presetName = "Invert",
                relativePath = "",
            ),
            packDir = null,
        )
        assertEquals("", resolved.presetPath)
        assertEquals("", resolved.relativePath)
        assertEquals("Invert", resolved.presetName)
        assert(resolved.enabled)
    }

    @Test
    fun `disabled config passes through untouched`() {
        val resolved = resolveShaderConfig(config(enabled = false), packDir = null)
        assertEquals(false, resolved.enabled)
        assertEquals("", resolved.presetPath)
    }

    // ── per-shader toggle-off only when actually loaded (spec 2026-08-11 + user fix) ──

    @Test
    fun `toggle off only when the active preset is loaded`() {
        // Normal active preset: re-picking it clears only it.
        assertTrue(shouldToggleOffActivePreset(true, "crt/easymode.slangp", "/data/pack/crt/easymode.slangp", "crt/easymode.slangp"))
        // Different preset: never clears.
        assertFalse(shouldToggleOffActivePreset(true, "crt/easymode.slangp", "/data/pack/crt/easymode.slangp", "film/technicolor.slangp"))
        // System disabled: nothing to clear.
        assertFalse(shouldToggleOffActivePreset(false, "crt/easymode.slangp", "/data/pack/crt/easymode.slangp", "crt/easymode.slangp"))
    }

    @Test
    fun `migrated selection without loaded path must not clear - it must load`() {
        // §6.3 state: selection visible (relativePath set) but NOT loaded (absolute empty,
        // pack was missing at boot). Re-picking the same preset LOADS it; it must never
        // take the toggle-off branch.
        assertFalse(shouldToggleOffActivePreset(true, "reshade/FilmGrain.slangp", "", "reshade/FilmGrain.slangp"))
        // Same, when the system was toggled off meanwhile.
        assertFalse(shouldToggleOffActivePreset(false, "reshade/FilmGrain.slangp", "", "reshade/FilmGrain.slangp"))
        // Legacy config without relativePath at all.
        assertFalse(shouldToggleOffActivePreset(true, "", "", "crt/easymode.slangp"))
    }
}
