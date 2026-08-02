package app.gamenative.ui.component.dialog

import android.content.Context
import com.winlator.container.Container
import com.winlator.renderer.RetroArchShaderConfig
import com.winlator.renderer.ShaderImporter
import java.io.File
import timber.log.Timber

private const val SHADER_KEY_ENABLED = "retroArchShaderEnabled"
private const val SHADER_KEY_PRESET_PATH = "retroArchShaderPresetPath"
private const val SHADER_KEY_PRESET_NAME = "retroArchShaderPresetName"
private const val SHADER_KEY_RELATIVE_PATH = "retroArchShaderRelativePath"

private const val ASSET_SHADERS_ROOT = "retroarch"

/** Friendly display name for a bundled preset key, e.g. {@code crt/easymode.slangp} -> "Easymode". */
fun friendlyName(key: String): String {
    val base = key.substringAfterLast('/').substringBeforeLast('.')
    if (base.isBlank()) return key
    return base.split('_', '-', ' ')
        .filter { it.isNotEmpty() }
        .joinToString(" ") { part ->
            part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
}

/** Category derived from the first path segment of a preset key, or null if the key has no subdir. */
fun categoryOf(key: String): String? {
    val slash = key.indexOf('/')
    return if (slash > 0) key.substring(0, slash) else null
}

/**
 * Ensures the bundled shader presets are materialized from {@code assets/retroarch} into the app
 * files dir so they can be enumerated and imported by [ShaderImporter].
 */
fun ensureBundledShaders(context: Context) {
    val importer = ShaderImporter(context)
    if (importer.ensureBundledPresets()) return
    // Fallback: best-effort recursive copy from assets.
    val root = importer.bundledPresetsDir
    if (root.isDirectory) return
    runCatching {
        copyAssetTree(context, ASSET_SHADERS_ROOT, root)
    }.onFailure {
        Timber.e(it, "Failed to copy bundled shader presets from assets")
    }
}

private fun copyAssetTree(context: Context, assetPath: String, dst: File) {
    val children = context.assets.list(assetPath) ?: return
    dst.mkdirs()
    for (child in children) {
        val childPath = "$assetPath/$child"
        val target = File(dst, child)
        val sub = context.assets.list(childPath)
        if (sub != null && sub.isNotEmpty()) {
            copyAssetTree(context, childPath, target)
        } else {
            target.parentFile?.mkdirs()
            context.assets.open(childPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }
}

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
