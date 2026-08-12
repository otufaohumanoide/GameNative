package app.gamenative.shaders

/**
 * Subtitle shown by the RetroArch shaders toggle row in the EFFECTS tab.
 * Extracted as a pure decision (pattern: ShaderDoubleClickLogic / GamepadStickLogic)
 * so the self-heal state can be unit-tested without Compose.
 */
enum class ShaderToggleSubtitle { ActivePreset, SelectedNotDownloaded, PickPreset, Off }

/**
 * Decides the toggle-row subtitle from the live shader state.
 *
 * [SelectedNotDownloaded] is the self-heal state introduced by the closure-aware
 * resolution (2026-08-12): the preset's dependency closure is incomplete in the cache,
 * so the absolute path was cleared — nothing is loaded even though the selection is
 * still visible. The user must re-pick the preset in the browser, which downloads ONLY
 * the missing files.
 */
fun shaderToggleSubtitle(enabled: Boolean, name: String, path: String): ShaderToggleSubtitle =
    when {
        enabled && name.isNotEmpty() && path.isNotEmpty() -> ShaderToggleSubtitle.ActivePreset
        enabled && name.isNotEmpty() -> ShaderToggleSubtitle.SelectedNotDownloaded
        enabled -> ShaderToggleSubtitle.PickPreset
        else -> ShaderToggleSubtitle.Off
    }
