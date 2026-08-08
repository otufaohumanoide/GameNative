package app.gamenative.ui.component.dialog

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.gamenative.R
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

/** String resources for the known preset families (bundled slang-shaders layout). */
private val CATEGORY_LABEL_RES = mapOf(
    "crt" to R.string.shader_cat_crt,
    "lcd" to R.string.shader_cat_lcd,
    "interpolation" to R.string.shader_cat_interpolation,
    "misc" to R.string.shader_cat_misc,
    "film" to R.string.shader_cat_film,
    "cel" to R.string.shader_cat_cel,
    "hdr" to R.string.shader_cat_hdr,
    "ntsc" to R.string.shader_cat_ntsc,
    "reshade" to R.string.shader_cat_reshade,
    "nearest" to R.string.shader_cat_nearest,
    "bilinear" to R.string.shader_cat_bilinear,
    "stock" to R.string.shader_cat_stock,
    "outros" to R.string.shader_cat_other,
)

/**
 * Human-readable category label, e.g. "crt" -> "CRT". Localized via string resources;
 * falls back to title-casing the raw name. Composable so resources resolve per locale.
 */
@Composable
fun friendlyCategoryName(category: String): String {
    val resId = CATEGORY_LABEL_RES[category]
    return if (resId != null) {
        stringResource(resId)
    } else {
        category.split('_', '-')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { part -> part.replaceFirstChar { c -> c.titlecase() } }
    }
}

/**
 * Counts the shader passes of a `.slangp` preset: sums `shaderN = ...` entries (ignoring
 * comments) and follows a `#reference <path>` chain one level deep (the referenced preset's
 * shader entries count too). Returns 0 for unreadable/unknown files.
 */
fun passCountOf(presetFile: File): Int = passCountOf(presetFile, mutableSetOf())

/**
 * Counts the shader passes of a `.slangp` preset: sums `shaderN = ...` entries (ignoring
 * comments) and resolves `#reference <path>` chains recursively (megatron-style presets are
 * parameter overrides referencing a base preset 2-3 levels deep). Cycle-safe via a visited set.
 */
private fun passCountOf(presetFile: File, visited: MutableSet<String>): Int {
    if (!presetFile.isFile) return 0
    val canonical = runCatching { presetFile.canonicalPath }.getOrElse { presetFile.path }
    if (!visited.add(canonical)) return 0 // cycle guard
    val text = runCatching { presetFile.readText() }.getOrNull() ?: return 0
    var count = 0
    var reference: String? = null
    for (rawLine in text.lineSequence()) {
        val line = rawLine.trim()
        if (line.isEmpty()) continue
        if (line.startsWith('#')) {
            if (line.startsWith("#reference")) {
                reference = line.substringAfter(' ').trim().trim('"').takeIf { it.isNotEmpty() }
            }
            continue
        }
        if (line.startsWith("shader") && line.contains('=')) {
            val key = line.substringBefore('=').trim()
            if (key.removePrefix("shader").toIntOrNull() != null) count++
        }
    }
    if (reference != null) {
        count += passCountOf(File(presetFile.parentFile, reference), visited)
    }
    return count
}

/** Pass count for a bundled preset key (relative path, e.g. "misc/invert.slangp"), path-traversal safe. */
fun resolvePassCount(key: String, bundledDir: File): Int {
    if (key.isBlank()) return 0
    val file = File(bundledDir, key).normalize()
    if (!file.path.startsWith(bundledDir.path)) return 0
    return passCountOf(file)
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
