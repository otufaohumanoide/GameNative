package app.gamenative.shaders

import com.winlator.container.Container
import java.io.File
import com.winlator.renderer.RetroArchShaderConfig

private const val SHADER_KEY_ENABLED = "retroArchShaderEnabled"
private const val SHADER_KEY_PRESET_PATH = "retroArchShaderPresetPath"
private const val SHADER_KEY_PRESET_NAME = "retroArchShaderPresetName"
private const val SHADER_KEY_RELATIVE_PATH = "retroArchShaderRelativePath"

/** Loads the persisted RetroArch shader config from container extras (falling back to defaults). */
fun loadShaderConfig(container: Container?): RetroArchShaderConfig {
    if (container == null) return RetroArchShaderConfig(false, "", "", "", "")
    return RetroArchShaderConfig(
        container.getExtra(SHADER_KEY_ENABLED).toBooleanStrictOrNull() ?: false,
        container.getExtra(SHADER_KEY_PRESET_PATH),
        container.getExtra(SHADER_KEY_PRESET_NAME),
        "",
        container.getExtra(SHADER_KEY_RELATIVE_PATH),
    )
}

/** Persists the RetroArch shader config to container extras. Callers debounce [Container.saveData]. */
fun persistShaderConfig(container: Container?, config: RetroArchShaderConfig) {
    if (container == null) return
    container.putExtra(SHADER_KEY_ENABLED, config.enabled)
    container.putExtra(SHADER_KEY_PRESET_PATH, config.presetPath)
    container.putExtra(SHADER_KEY_PRESET_NAME, config.presetName)
    container.putExtra(SHADER_KEY_RELATIVE_PATH, config.relativePath)
}
/**
 * Result of resolving a persisted shader config against the current pack (spec §6).
 * [presetPath] is the absolute path that should be handed to the renderer — empty means
 * "load nothing" while the menu selection ([presetName]/[relativePath]) stays visible.
 */
data class ResolvedShaderConfig(
    val enabled: Boolean,
    val presetPath: String,
    val presetName: String,
    val relativePath: String,
)

/**
 * Migration rule (spec §6) for configs written by the old embedded-preset system. Old
 * configs persist an absolute path under `.../retroarch_presets/...` — a directory that no
 * longer exists — plus a repo-relative path. Resolution, in order:
 *
 *  1. `presetPath` exists on disk → load normally (new path, nothing to do).
 *  2. `presetPath` is gone and `relativePath` resolves inside the installed pack →
 *     re-resolve `packDir/relativePath`; the caller persists the new absolute path.
 *  3. `relativePath` does not resolve (pack not installed) → keep `enabled` and the menu
 *     selection visible, but clear the absolute path: nothing is loaded and NOTHING is
 *     downloaded without user intent (the browser shows the download CTA).
 *  4. Old config without `relativePath` (the legacy dialog wrote only the absolute path) →
 *     same as (3): path cleared, user re-picks a preset.
 *
 * Pure JVM function — unit-testable without Android.
 */
fun resolveShaderConfig(config: RetroArchShaderConfig, packDir: File?): ResolvedShaderConfig {
    val enabled = config.enabled
    val relative = config.relativePath
    val name = config.presetName
    val path = config.presetPath
    return when {
        path.isNotEmpty() && File(path).isFile ->
            ResolvedShaderConfig(enabled, path, name, relative)
        relative.isNotEmpty() && packDir != null && File(packDir, relative).isFile ->
            ResolvedShaderConfig(enabled, File(packDir, relative).absolutePath, name, relative)
        else ->
            ResolvedShaderConfig(enabled, "", name, relative)
    }
}


/**
 * Per-shader toggle-off decision (spec 2026-08-11): selecting the SAME preset clears ONLY
 * that preset — but only when it is actually LOADED. A migrated selection (§6.3) keeps
 * `relativePath` with an EMPTY absolute path (the pack was missing at boot): re-picking it
 * must LOAD the preset, never clear it — otherwise the shader "never works" after the pack
 * download completes. Pure JVM function — unit-testable.
 */
fun shouldToggleOffActivePreset(
    enabled: Boolean,
    activeRelativePath: String,
    activePresetPath: String,
    candidatePath: String,
): Boolean = enabled && candidatePath == activeRelativePath && activePresetPath.isNotEmpty()
