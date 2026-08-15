package app.gamenative.gamepad.remap

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.PluviaApp
import app.gamenative.R
import app.gamenative.events.AndroidEvent
import app.gamenative.events.GamepadInputEvent
import app.gamenative.gamepad.FaceStyle
import app.gamenative.gamepad.GyroMode
import app.gamenative.gamepad.layers.LayerTriggerMode
import app.gamenative.gamepad.layers.LayerTriggerSpec
import app.gamenative.gamepad.GamepadButton
import app.gamenative.gamepad.GamepadDevice
import app.gamenative.gamepad.glyphs.GamepadGlyphProvider
import app.gamenative.gamepad.mapping.AndroidConstants
import app.gamenative.gamepad.mapping.ControllerVisualLayout
import app.gamenative.gamepad.mapping.GamepadMapping
import app.gamenative.gamepad.mapping.RawBinding
import app.gamenative.gamepad.processing.DeadzoneMode
import app.gamenative.gamepad.processing.ResponseCurve
import app.gamenative.gamepad.processing.StickTransform
import app.gamenative.gamepad.processing.SwipeDir
import app.gamenative.gamepad.profiles.ActionLayer
import app.gamenative.ui.component.ProfileCatalogBrowser
import app.gamenative.gamepad.radial.RadialMacroKey
import app.gamenative.gamepad.radial.SWIPE_OPEN_RADIAL
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.gamenative.gamepad.profiles.GamepadProfile
import app.gamenative.ui.component.GamepadFocusScope
import app.gamenative.ui.component.gamepadAdjustableRow
import app.gamenative.ui.component.gamepadBackHandler
import app.gamenative.ui.component.gamepadSelectable
import app.gamenative.ui.component.remap.ControllerVisualView
import app.gamenative.ui.component.remap.VisualControlState
import kotlin.math.abs
import kotlinx.coroutines.delay

/**
 * Diálogo de remap (spec 2026-08-13, Passo 7 — D8). Janela separada: padrão
 * [GamepadFocusScope] do ElementEditorDialog (focus scope de VIEW — NUNCA navigator de
 * bus em janela de diálogo, regra do AGENTS.md). A captura de binding usa eventos do
 * BUS cru (AndroidEvent.KeyEvent/MotionEvent) — o evento lógico não carrega a fonte
 * física, que é exatamente o que o remap precisa gravar.
 *
 * Edita a camada [ActionLayer.DEFAULT] do perfil: `GamepadButton.name → binding
 * serializado` (GamepadBindingCodec). Export/import via clipboard (toJson/fromJson).
 */
@Composable
fun GamepadRemapDialog(
    device: GamepadDevice,
    mapping: GamepadMapping,
    profile: GamepadProfile,
    onSave: (GamepadProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var layers by remember { mutableStateOf(profile.layers) }
    var captureTarget by remember { mutableStateOf<GamepadButton?>(null) }
    // U1 (spec 2026-08-14-gamepad-u1-gyro): seção Gyro per-device (só com hasGyro).
    var gyroMode by remember { mutableStateOf(profile.gyroMode ?: GyroMode.OFF) }
    var gyroSensitivity by remember { mutableStateOf(profile.gyroSensitivity ?: 1f) }
    var gyroDeadzone by remember { mutableStateOf(profile.gyroDeadzone ?: 0.05f) }
    var gyroActivateButton by remember { mutableStateOf(profile.gyroActivateButton) }
    var captureGyroActivate by remember { mutableStateOf(false) }
    // U3 (spec 2026-08-14-gamepad-u3-u4-layers-remap-jogo, §1.4): camada em edição,
    // triggers de camada (capture do botão + modo) e nova camada.
    var selectedLayer by remember { mutableStateOf(ActionLayer.DEFAULT.name) }
    var layerTriggers by remember { mutableStateOf(profile.layerTriggers) }
    var captureLayerTrigger by remember { mutableStateOf(false) }
    var pendingTriggerMode by remember { mutableStateOf(LayerTriggerMode.HOLD) }
    // P2-6 (spec 2026-08-14-touchpad-drag-double-tap): duplo-toque do touchpad =
    // clique direito (opt-in por perfil; null = OFF — 2 cliques, comportamento U2).
    var touchpadDoubleTapRightClick by remember {
        mutableStateOf(profile.touchpadDoubleTapRightClick ?: false)
    }
    // D (spec 2026-08-16-D-touchpad-swipes-macros): seção Swipes (só com hasTouchpad).
    // Chaves = nomes de SwipeDir; valor = macro (lista de RadialMacroKey) OU lista
    // com 1 RadialMacroKey(SWIPE_OPEN_RADIAL) = abrir radial.
    var swipeBindings by remember { mutableStateOf(profile.touchpadSwipes ?: emptyMap()) }
    var captureSwipe by remember { mutableStateOf<SwipeDir?>(null) }
    // ── F1 (spec 2026-08-15-input-core-avancado) — seção Stick + Flick + fusão ──
    var leftStickMode by remember { mutableStateOf(profile.leftStickDeadzoneMode ?: DeadzoneMode.RADIAL) }
    var rightStickMode by remember { mutableStateOf(profile.rightStickDeadzoneMode ?: DeadzoneMode.RADIAL) }
    var leftCurve by remember { mutableStateOf(profile.leftStickCurve ?: ResponseCurve.LINEAR) }
    var rightCurve by remember { mutableStateOf(profile.rightStickCurve ?: ResponseCurve.LINEAR) }
    var leftLut by remember { mutableStateOf(profile.leftStickLut ?: emptyList()) }
    var rightLut by remember { mutableStateOf(profile.rightStickLut ?: emptyList()) }
    var flickStickEnabled by remember { mutableStateOf(profile.flickStickEnabled ?: false) }
    var flickRadius by remember {
        mutableStateOf(profile.flickStickActivationRadius ?: DEFAULT_FLICK_RADIUS)
    }
    var flickSnap by remember { mutableStateOf(profile.flickStickSnapAngle ?: DEFAULT_FLICK_SNAP) }
    var gyroFusionEnabled by remember { mutableStateOf(profile.gyroFusionEnabled ?: false) }
    // ── G (spec 2026-08-16-G-gyro-v2) — gyro v2 ──
    var gyroSensitivityY by remember { mutableStateOf(profile.gyroSensitivityY ?: 1f) }
    var gyroInvertX by remember { mutableStateOf(profile.gyroInvertX ?: false) }
    var gyroInvertY by remember { mutableStateOf(profile.gyroInvertY ?: false) }
    var gyroSmoothEnabled by remember {
        mutableStateOf(profile.gyroSmoothMinCutoff != null || profile.gyroSmoothBeta != null)
    }
    var gyroSmoothMinCutoff by remember { mutableStateOf(profile.gyroSmoothMinCutoff ?: 1.0f) }
    var gyroSmoothBeta by remember { mutableStateOf(profile.gyroSmoothBeta ?: 0.7f) }
    var gyroStickMaxOutput by remember { mutableStateOf(profile.gyroStickMaxOutput ?: 1f) }
    var gyroStickAntiDeadzone by remember { mutableStateOf(profile.gyroStickAntiDeadzone ?: 0f) }
    var gyroActivateToggle by remember { mutableStateOf(profile.gyroActivateToggle ?: false) }
    var gyroGripAngleDeg by remember { mutableStateOf(profile.gyroGripAngleDeg ?: 0f) }
    var status by remember { mutableStateOf<String?>(null) }
    // E (spec 2026-08-16-E-profile-catalog-comunitario, §1.3): browser do catálogo
    // aberto (janela própria por cima deste dialog); desliga o escopo de foco deste
    // dialog enquanto o browser está por cima (uma janela, um dono do input).
    var catalogOpen by remember { mutableStateOf(false) }
    // ── B (spec 2026-08-16-B-remap-visual-ppsspp): mapa visual + escopo + flash ──
    val hub = PluviaApp.gamepadHub
    // appId do container ativo (holder do hub — lido ao abrir o dialog; null fora de
    // jogo desabilita o escopo "Este jogo" com hint, §1.4).
    val appId = remember { hub.activeAppId }
    // Perfil BRUTO do jogo (sem merge com o device) — base dos bindings do escopo GAME
    // no save; o merge efetivo (JOGO > GLOBAL > AUTO) continua no profileFor.
    val initialGameProfile = remember(appId) { hub.gameProfileFor(appId) }
    var gameLayers by remember(initialGameProfile) {
        mutableStateOf(initialGameProfile?.layers ?: emptyMap())
    }
    var visualExpanded by remember { mutableStateOf(true) }
    var visualScope by remember {
        mutableStateOf(if (appId != null) VisualScope.GAME else VisualScope.DEVICE)
    }
    var visualCapture by remember { mutableStateOf<GamepadButton?>(null) }
    // Flash ao vivo (§1.2): timestamps por controle + set derivado para a view (o set
    // expira em ~600 ms; o decaimento visual é do ControllerVisualView).
    val flashTimes = remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    val flashSet = remember { mutableStateOf<Set<String>>(emptySet()) }

    /**
     * Perfil efetivo do editor — defaults colapsados em null (política do store:
     * null = sem preferência; salvar default REMOVE a entrada). Usado pelo save,
     * export clipboard e export arquivo (F3.3).
     */
    fun editorProfile(): GamepadProfile = profile.copy(
        layers = layers,
        layerTriggers = layerTriggers,
        gyroMode = if (gyroMode == GyroMode.OFF) null else gyroMode,
        gyroSensitivity = if (gyroSensitivity == 1f) null else gyroSensitivity,
        gyroDeadzone = if (gyroDeadzone == 0.05f) null else gyroDeadzone,
        gyroActivateButton = gyroActivateButton,
        touchpadDoubleTapRightClick = if (touchpadDoubleTapRightClick) true else null,
        // D (spec 2026-08-16-D-touchpad-swipes-macros): vazio = OFF → null
        // (política do store: null = sem preferência).
        touchpadSwipes = if (swipeBindings.isEmpty()) null else swipeBindings,
        // F1 (spec 2026-08-15-input-core-avancado)
        leftStickDeadzoneMode = if (leftStickMode == DeadzoneMode.RADIAL) null else leftStickMode,
        rightStickDeadzoneMode = if (rightStickMode == DeadzoneMode.RADIAL) null else rightStickMode,
        leftStickCurve = if (leftCurve == ResponseCurve.LINEAR) null else leftCurve,
        rightStickCurve = if (rightCurve == ResponseCurve.LINEAR) null else rightCurve,
        leftStickLut = if (leftLut.isEmpty()) null else leftLut,
        rightStickLut = if (rightLut.isEmpty()) null else rightLut,
        flickStickEnabled = if (flickStickEnabled) true else null,
        flickStickActivationRadius = if (flickStickEnabled && flickRadius != DEFAULT_FLICK_RADIUS) flickRadius else null,
        flickStickSnapAngle = if (flickStickEnabled && flickSnap != DEFAULT_FLICK_SNAP) flickSnap else null,
        gyroFusionEnabled = if (gyroFusionEnabled) true else null,
        gyroFusionKp = null,
        gyroFusionKi = null,
        // G (spec 2026-08-16-G-gyro-v2): defaults colapsam em null (política do store).
        gyroSensitivityY = if (gyroSensitivityY == 1f) null else gyroSensitivityY,
        gyroInvertX = if (gyroInvertX) true else null,
        gyroInvertY = if (gyroInvertY) true else null,
        gyroSmoothMinCutoff = if (gyroSmoothEnabled) gyroSmoothMinCutoff else null,
        gyroSmoothBeta = if (gyroSmoothEnabled) gyroSmoothBeta else null,
        gyroStickMaxOutput = if (gyroStickMaxOutput == 1f) null else gyroStickMaxOutput,
        gyroStickAntiDeadzone = if (gyroStickAntiDeadzone == 0f) null else gyroStickAntiDeadzone,
        gyroActivateToggle = if (gyroActivateToggle) true else null,
        gyroGripAngleDeg = if (gyroGripAngleDeg == 0f) null else gyroGripAngleDeg,
    )

    /** Aplica um perfil importado (clipboard ou arquivo — F3.3) ao estado do editor. */
    fun applyImportedProfile(imported: GamepadProfile) {
        layers = imported.layers
        layerTriggers = imported.layerTriggers
        gyroMode = imported.gyroMode ?: GyroMode.OFF
        gyroSensitivity = imported.gyroSensitivity ?: 1f
        gyroDeadzone = imported.gyroDeadzone ?: 0.05f
        gyroActivateButton = imported.gyroActivateButton
        touchpadDoubleTapRightClick = imported.touchpadDoubleTapRightClick ?: false
        swipeBindings = imported.touchpadSwipes ?: emptyMap()
        leftStickMode = imported.leftStickDeadzoneMode ?: DeadzoneMode.RADIAL
        rightStickMode = imported.rightStickDeadzoneMode ?: DeadzoneMode.RADIAL
        leftCurve = imported.leftStickCurve ?: ResponseCurve.LINEAR
        rightCurve = imported.rightStickCurve ?: ResponseCurve.LINEAR
        leftLut = imported.leftStickLut ?: emptyList()
        rightLut = imported.rightStickLut ?: emptyList()
        flickStickEnabled = imported.flickStickEnabled ?: false
        flickRadius = imported.flickStickActivationRadius ?: DEFAULT_FLICK_RADIUS
        flickSnap = imported.flickStickSnapAngle ?: DEFAULT_FLICK_SNAP
        gyroFusionEnabled = imported.gyroFusionEnabled ?: false
        // G (spec 2026-08-16-G-gyro-v2): mesmos defaults do estado do editor.
        gyroSensitivityY = imported.gyroSensitivityY ?: 1f
        gyroInvertX = imported.gyroInvertX ?: false
        gyroInvertY = imported.gyroInvertY ?: false
        gyroSmoothEnabled = imported.gyroSmoothMinCutoff != null || imported.gyroSmoothBeta != null
        gyroSmoothMinCutoff = imported.gyroSmoothMinCutoff ?: 1.0f
        gyroSmoothBeta = imported.gyroSmoothBeta ?: 0.7f
        gyroStickMaxOutput = imported.gyroStickMaxOutput ?: 1f
        gyroStickAntiDeadzone = imported.gyroStickAntiDeadzone ?: 0f
        gyroActivateToggle = imported.gyroActivateToggle ?: false
        gyroGripAngleDeg = imported.gyroGripAngleDeg ?: 0f
    }

    // ── F1.1/F3.3: SAF (CreateDocument/OpenDocument) para LUT e perfil por arquivo ──
    var pendingLutExport by remember { mutableStateOf<List<Float>>(emptyList()) }
    var pendingLutImportSetter by remember { mutableStateOf<((List<Float>) -> Unit)?>(null) }
    val lutExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(lutJson(pendingLutExport).toByteArray(Charsets.UTF_8))
            } ?: error("null stream")
        }.onSuccess {
            status = context.getString(R.string.gamepad_stick_lut_exported)
        }.onFailure {
            status = context.getString(R.string.gamepad_stick_lut_export_failed)
        }
    }
    val lutImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val setter = pendingLutImportSetter ?: return@rememberLauncherForActivityResult
        pendingLutImportSetter = null
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val text = input.readBytes().toString(Charsets.UTF_8)
                val clean = parseLutJson(text)
                check(clean.isNotEmpty()) { "lut vazia" }
                clean
            } ?: error("null stream")
        }.onSuccess { clean ->
            setter(clean)
            status = context.getString(R.string.gamepad_stick_lut_imported)
        }.onFailure {
            status = context.getString(R.string.gamepad_stick_lut_import_failed)
        }
    }
    var pendingProfileExport by remember { mutableStateOf<String?>(null) }
    val profileExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val json = pendingProfileExport ?: return@rememberLauncherForActivityResult
        pendingProfileExport = null
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
            } ?: error("null stream")
        }.onSuccess {
            status = context.getString(R.string.gamepad_remap_exported)
        }.onFailure {
            status = context.getString(R.string.gamepad_profile_export_failed)
        }
    }
    val profileImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            } ?: error("null stream")
        }.onSuccess { text ->
            val imported = GamepadProfile.fromJson(text)
            if (imported == null) {
                status = context.getString(R.string.gamepad_profile_import_failed)
            } else {
                applyImportedProfile(imported)
                status = context.getString(R.string.gamepad_profile_imported_file)
            }
        }.onFailure {
            status = context.getString(R.string.gamepad_profile_import_failed)
        }
    }

    fun layerMap(layerName: String): Map<String, String> = layers[layerName] ?: emptyMap()

    fun bindingFor(button: GamepadButton): GamepadBindingCodec.LayerBinding? {
        val token = layerMap(selectedLayer)[button.name]
        if (token != null) return GamepadBindingCodec.decode(token)
        // A camada só sobrepõe o que define; DEFAULT mostra o binding do mapping.
        if (selectedLayer == ActionLayer.DEFAULT.name) return mapping.buttons[button]?.let {
            GamepadBindingCodec.LayerBinding(it)
        }
        return null
    }

    /**
     * F (spec 2026-08-16-F-radial-v2-modeshift-turbo, §1.4): toggle Turbo do chip de
     * binding da camada — re-encoda o token com/sem o sufixo `:turbo` (período fixo
     * 80 ms v2). A captura NOVA zera o flag (default OFF = byte-identical).
     */
    fun setTurbo(button: GamepadButton, turbo: Boolean) {
        val token = layerMap(selectedLayer)[button.name] ?: return
        val decoded = GamepadBindingCodec.decode(token) ?: return
        layers = layers + (
            selectedLayer to (layerMap(selectedLayer) + (button.name to GamepadBindingCodec.encode(decoded.raw, turbo)))
        )
        status = null
    }

    fun commitBinding(button: GamepadButton, binding: RawBinding) {
        val conflict = GamepadButton.entries.any { other ->
            other != button && bindingFor(other)?.raw?.let { GamepadBindingCodec.conflicts(it, binding) } == true
        }
        if (conflict) {
            status = context.getString(R.string.gamepad_remap_conflict)
            return
        }
        val newLayer = layerMap(selectedLayer) + (button.name to GamepadBindingCodec.encode(binding))
        layers = layers + (selectedLayer to newLayer)
        status = null
    }

    fun clearBinding(button: GamepadButton) {
        if (button.name !in layerMap(selectedLayer)) return
        layers = layers + (selectedLayer to (layerMap(selectedLayer) - button.name))
        status = null
    }

    // ── B §1.4: bindings EFETIVOS da camada DEFAULT (device + jogo; jogo vence) ──
    fun visualEffectiveDefault(): Map<String, String> =
        layerMap(ActionLayer.DEFAULT.name) + (gameLayers[ActionLayer.DEFAULT.name] ?: emptyMap())

    /** Estado do controle no mapa visual: binding explícito no efetivo = OVERRIDE. */
    fun visualStateOf(button: GamepadButton): VisualControlState =
        if (button.name in visualEffectiveDefault()) {
            VisualControlState.OVERRIDE
        } else {
            VisualControlState.AUTO
        }

    /** B §1.3/§1.4: commit do mapa visual na camada DEFAULT do escopo selecionado. */
    fun commitVisualBinding(button: GamepadButton, binding: RawBinding) {
        val conflict = visualEffectiveDefault().entries.any { (name, token) ->
            name != button.name && GamepadBindingCodec.decode(token)?.raw?.let {
                GamepadBindingCodec.conflicts(it, binding)
            } == true
        }
        if (conflict) {
            status = context.getString(R.string.gamepad_remap_conflict)
            return
        }
        val token = GamepadBindingCodec.encode(binding)
        if (visualScope == VisualScope.DEVICE) {
            layers = layers + (
                ActionLayer.DEFAULT.name to (layerMap(ActionLayer.DEFAULT.name) + (button.name to token))
            )
        } else {
            val gameDefault = gameLayers[ActionLayer.DEFAULT.name] ?: emptyMap()
            gameLayers = gameLayers + (ActionLayer.DEFAULT.name to (gameDefault + (button.name to token)))
        }
        status = null
    }

    /** B §1.4: restaurar automático POR CONTROLE — limpa o override de onde ele vier. */
    fun restoreVisualControl(button: GamepadButton) {
        val name = button.name
        val gameDefault = gameLayers[ActionLayer.DEFAULT.name] ?: emptyMap()
        if (name in gameDefault) {
            gameLayers = gameLayers + (ActionLayer.DEFAULT.name to (gameDefault - name))
        }
        val deviceDefault = layerMap(ActionLayer.DEFAULT.name)
        if (name in deviceDefault) {
            layers = layers + (ActionLayer.DEFAULT.name to (deviceDefault - name))
        }
        if (visualCapture == button) visualCapture = null
        status = context.getString(R.string.gamepad_visual_restored_control)
    }

    /** B §1.4: restaurar automático GERAL — limpa todos os bindings do escopo selecionado. */
    fun restoreVisualScope() {
        if (visualScope == VisualScope.DEVICE) layers = emptyMap() else gameLayers = emptyMap()
        visualCapture = null
        status = context.getString(R.string.gamepad_visual_restored_all)
    }

    // Captura via eventos do BUS cru enquanto captureTarget != null OU
    // captureGyroActivate (o escopo de foco fica desabilitado: TODO o input do
    // controle vira binding).
    DisposableEffect(captureTarget, captureGyroActivate, captureLayerTrigger) {
        val target = captureTarget
        if (target == null && !captureGyroActivate && !captureLayerTrigger) {
            return@DisposableEffect onDispose {}
        }

        fun handleKey(androidEvent: AndroidEvent.KeyEvent): Boolean {
            val ev = androidEvent.event ?: return false
            if (ev.deviceId != device.deviceId) return false
            if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) {
                when (ev.keyCode) {
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                        captureTarget = null
                        captureGyroActivate = false
                        captureLayerTrigger = false
                        status = context.getString(R.string.gamepad_remap_capture_cancelled)
                    }
                    else -> {
                        if (captureLayerTrigger) {
                            // U3: o botão do trigger é um GamepadButton LÓGICO
                            // (conversão reversa do keycode cru via mapping).
                            val logical = mapping.buttons.entries
                                .firstOrNull { it.value == RawBinding.Key(ev.keyCode) }
                                ?.key
                            if (logical != null) {
                                // P3-3 (spec 2026-08-14-gamepad-upgrades-pendencias):
                                // dois triggers no MESMO botão = comportamento
                                // indefinido (o hub usa firstOrNull). Bloqueia com
                                // erro inline — padrão key-mapper.
                                val conflict = layerTriggers.entries.any { (layer, spec) ->
                                    layer != selectedLayer && spec.button == logical.name
                                }
                                if (conflict) {
                                    status = context.getString(R.string.gamepad_layer_trigger_conflict)
                                } else {
                                    // F §1.3: re-captura PRESERVA o isShift do trigger
                                    // existente (capturar não reseta o modo de shift).
                                    val existing = layerTriggers[selectedLayer]
                                    layerTriggers = layerTriggers + (
                                        selectedLayer to LayerTriggerSpec(
                                            button = logical.name,
                                            mode = pendingTriggerMode,
                                            doubleTapMs = existing?.doubleTapMs ?: 250,
                                            isShift = existing?.isShift ?: false,
                                        )
                                    )
                                    status = null
                                }
                            } else {
                                status = context.getString(R.string.gamepad_gyro_activate_unmapped)
                            }
                            captureLayerTrigger = false
                        } else if (captureGyroActivate) {
                            // U1: o botão de ativação é um GamepadButton LÓGICO —
                            // converte o keycode cru via mapping reverso.
                            val logical = mapping.buttons.entries
                                .firstOrNull { it.value == RawBinding.Key(ev.keyCode) }
                                ?.key
                            gyroActivateButton = logical?.name
                            if (logical == null) {
                                status = context.getString(R.string.gamepad_gyro_activate_unmapped)
                            } else {
                                status = null
                            }
                            captureGyroActivate = false
                        } else if (target != null) {
                            commitBinding(target, RawBinding.Key(ev.keyCode))
                            captureTarget = null
                        }
                    }
                }
                return true
            }
            return false
        }

        fun handleMotion(androidEvent: AndroidEvent.MotionEvent): Boolean {
            val ev = androidEvent.event ?: return false
            if (ev.deviceId != device.deviceId) return false
            if (ev.actionMasked != MotionEvent.ACTION_MOVE) return false
            val (axis, direction, magnitude) = strongestCapturableAxis(ev) ?: return false
            if (magnitude < 0.5f) return false
            if (target != null) {
                commitBinding(target, RawBinding.Axis(axis, direction))
                captureTarget = null
            }
            return true
        }

        val keyHandler: (AndroidEvent.KeyEvent) -> Boolean = ::handleKey
        val motionHandler: (AndroidEvent.MotionEvent) -> Boolean = ::handleMotion
        PluviaApp.events.on<AndroidEvent.KeyEvent, Boolean>(keyHandler)
        PluviaApp.events.on<AndroidEvent.MotionEvent, Boolean>(motionHandler)
        onDispose {
            PluviaApp.events.off<AndroidEvent.KeyEvent, Boolean>(keyHandler)
            PluviaApp.events.off<AndroidEvent.MotionEvent, Boolean>(motionHandler)
        }
    }

    // ── B §1.3: captura do mapa visual — padrão do RadialMenuEditorDialog (bus CRU do
    // deviceId); B cruzeiro / hardware back cancela. Mutuamente exclusiva com as
    // capturas existentes (os inícios de captura antigos zeram visualCapture, e o tap
    // no hotspot zera captureTarget/gyro/trigger).
    DisposableEffect(visualCapture) {
        val target = visualCapture
        if (target == null) return@DisposableEffect onDispose {}

        fun handleKey(androidEvent: AndroidEvent.KeyEvent): Boolean {
            val ev = androidEvent.event ?: return false
            if (ev.deviceId != device.deviceId) return false
            if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) {
                when (ev.keyCode) {
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE,
                    AndroidConstants.BUTTON_B -> {
                        visualCapture = null
                        status = context.getString(R.string.gamepad_remap_capture_cancelled)
                    }
                    else -> {
                        commitVisualBinding(target, RawBinding.Key(ev.keyCode))
                        visualCapture = null
                    }
                }
                return true
            }
            return false
        }

        fun handleMotion(androidEvent: AndroidEvent.MotionEvent): Boolean {
            val ev = androidEvent.event ?: return false
            if (ev.deviceId != device.deviceId) return false
            if (ev.actionMasked != MotionEvent.ACTION_MOVE) return false
            val (axis, direction, magnitude) = strongestCapturableAxis(ev) ?: return false
            if (magnitude < 0.5f) return false
            commitVisualBinding(target, RawBinding.Axis(axis, direction))
            visualCapture = null
            return true
        }

        val keyHandler: (AndroidEvent.KeyEvent) -> Boolean = ::handleKey
        val motionHandler: (AndroidEvent.MotionEvent) -> Boolean = ::handleMotion
        PluviaApp.events.on<AndroidEvent.KeyEvent, Boolean>(keyHandler)
        PluviaApp.events.on<AndroidEvent.MotionEvent, Boolean>(motionHandler)
        onDispose {
            PluviaApp.events.off<AndroidEvent.KeyEvent, Boolean>(keyHandler)
            PluviaApp.events.off<AndroidEvent.MotionEvent, Boolean>(motionHandler)
        }
    }

    // ── D (spec 2026-08-16-D-touchpad-swipes-macros): captura de MACRO do swipe —
    // mesmo padrão do RadialMenuEditorDialog (bus CRU do deviceId): teclas CONCATENAM
    // enquanto a captura está ativa (macro de N teclas, timing default 60/40 ms);
    // BACK/ESCAPE encerra. Mutuamente exclusiva com as capturas existentes (todo
    // início de captura zera captureSwipe, e o início da captura de swipe zera as
    // demais).
    DisposableEffect(captureSwipe) {
        val dir = captureSwipe ?: return@DisposableEffect onDispose {}

        fun handleKey(androidEvent: AndroidEvent.KeyEvent): Boolean {
            val ev = androidEvent.event ?: return false
            if (ev.deviceId != device.deviceId) return false
            if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) {
                when (ev.keyCode) {
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                        captureSwipe = null
                        status = context.getString(R.string.gamepad_remap_capture_cancelled)
                    }
                    else -> {
                        val current = swipeBindings[dir.name] ?: emptyList()
                        swipeBindings = swipeBindings + (dir.name to (current + RadialMacroKey(ev.keyCode)))
                    }
                }
                return true
            }
            return false
        }

        val swipeKeyHandler: (AndroidEvent.KeyEvent) -> Boolean = ::handleKey
        PluviaApp.events.on<AndroidEvent.KeyEvent, Boolean>(swipeKeyHandler)
        onDispose {
            PluviaApp.events.off<AndroidEvent.KeyEvent, Boolean>(swipeKeyHandler)
        }
    }

    // ── B §1.2: flash ao vivo — listener de GamepadInputEvent com holders vivos
    // (lição C1): o handler registrado UMA vez lê o deviceId ATUAL via
    // rememberUpdatedState; o retorno false = observador (nunca consome input).
    val currentDeviceId by rememberUpdatedState(device.deviceId)
    DisposableEffect(Unit) {
        fun handle(event: GamepadInputEvent): Boolean {
            val control = ControllerVisualLayout.flashControlFor(event.input, currentDeviceId)
                ?: return false
            flashTimes.value = flashTimes.value + (control to SystemClock.uptimeMillis())
            flashSet.value = flashSet.value + control
            return false
        }
        val handler: (GamepadInputEvent) -> Boolean = ::handle
        PluviaApp.events.on<GamepadInputEvent, Boolean>(handler)
        onDispose { PluviaApp.events.off<GamepadInputEvent, Boolean>(handler) }
    }
    // Expiração do flash (~600 ms) — remove dos holders; o decaimento visual (alpha) é
    // do ControllerVisualView, que deriva os timestamps de entrada do set.
    LaunchedEffect(Unit) {
        while (true) {
            if (flashTimes.value.isNotEmpty()) {
                val now = SystemClock.uptimeMillis()
                val pruned = flashTimes.value.filterValues { now - it < VISUAL_FLASH_MS }
                if (pruned.size != flashTimes.value.size) {
                    flashTimes.value = pruned
                    flashSet.value = pruned.keys
                }
            }
            delay(50)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            // B §1.3: hardware back CANCELA a captura visual em vez de fechar o dialog
            // (sem consumo pelo Dialog, o BACK chega ao bus e o handler cancela).
            // D: idem para a captura de swipe.
            dismissOnBackPress = visualCapture == null && captureSwipe == null,
            dismissOnClickOutside = false,
        ),
    ) {
        val initialFocus = remember { FocusRequester() }
        // Enquanto captura, o escopo de foco fica OFF: todo input do controle é captura.
        GamepadFocusScope(
            enabled = captureTarget == null && !captureGyroActivate && !captureLayerTrigger &&
                visualCapture == null && captureSwipe == null && !catalogOpen,
            backAction = onDismiss,
            initialFocusRequester = initialFocus,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (captureTarget == null && !captureGyroActivate && !captureLayerTrigger &&
                            visualCapture == null && captureSwipe == null && !catalogOpen
                        ) {
                            Modifier.gamepadBackHandler(onDismiss)
                        } else {
                            Modifier
                        },
                    ),
                color = MaterialTheme.colorScheme.background,
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    // ── Header ──
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.gamepad_remap_title, device.name),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.gamepad_remap_cancel))
                        }
                    }
                    status?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    // ── U3: camadas (DEFAULT + extras) ──
                    LayerSelectorRow(
                        layers = layers.keys.toList(),
                        selectedLayer = selectedLayer,
                        onSelect = {
                            selectedLayer = it
                            status = null
                        },
                        onAdd = {
                            // Nome padrão "LAYER_N" (rename é follow-up cosmético).
                            var n = 1
                            while (layers.containsKey("LAYER_$n")) n++
                            layers = layers + ("LAYER_$n" to emptyMap())
                            selectedLayer = "LAYER_$n"
                        },
                        onRemove = {
                            if (it != ActionLayer.DEFAULT.name) {
                                layers = layers - it
                                layerTriggers = layerTriggers - it
                                if (selectedLayer == it) selectedLayer = ActionLayer.DEFAULT.name
                            }
                        },
                    )
                    // U3: trigger da camada selecionada (a DEFAULT é a base e não tem
                    // ativação própria).
                    if (selectedLayer != ActionLayer.DEFAULT.name) {
                        LayerTriggerRow(
                            trigger = layerTriggers[selectedLayer],
                            onModeChange = { newMode ->
                                pendingTriggerMode = newMode
                                val existing = layerTriggers[selectedLayer]
                                if (existing != null) {
                                    layerTriggers = layerTriggers +
                                        (selectedLayer to existing.copy(mode = newMode))
                                }
                            },
                            capturing = captureLayerTrigger,
                            onCapture = {
                                captureLayerTrigger = !captureLayerTrigger
                                captureTarget = null
                                captureGyroActivate = false
                                visualCapture = null
                                captureSwipe = null
                                status = null
                            },
                            onClearTrigger = {
                                layerTriggers = layerTriggers - selectedLayer
                                status = null
                            },
                            faceStyle = device.faceStyle,
                        )
                        // F (spec 2026-08-16-F-radial-v2-modeshift-turbo, §1.3):
                        // toggle "Camada de shift" — o hub consome o botão físico,
                        // não emite GamepadLayerEvent nem tick, e o remap continua
                        // pelo effectiveBindings (mecânica U3 intacta).
                        layerTriggers[selectedLayer]?.let { spec ->
                            val shiftInteraction = remember { MutableInteractionSource() }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .gamepadSelectable(
                                        selected = spec.isShift,
                                        onClick = {
                                            layerTriggers = layerTriggers + (
                                                selectedLayer to spec.copy(isShift = !spec.isShift)
                                            )
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        interactionSource = shiftInteraction,
                                    )
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.gamepad_layer_shift_title),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        text = stringResource(R.string.gamepad_layer_shift_subtitle),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = spec.isShift,
                                    onCheckedChange = {
                                        layerTriggers = layerTriggers + (
                                            selectedLayer to spec.copy(isShift = it)
                                        )
                                    },
                                    modifier = Modifier.focusProperties { canFocus = false },
                                )
                            }
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    // ── Lista de botões ──
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        // ── B (spec 2026-08-16-B-remap-visual-ppsspp, §1.5): seção
                        // colapsável "Mapa visual" NO TOPO do tab CONTROLLER; a lista
                        // avançada existente permanece intacta embaixo. Nada existente
                        // é removido. ──
                        VisualRemapSection(
                            expanded = visualExpanded,
                            onToggleExpanded = { visualExpanded = !visualExpanded },
                            scope = visualScope,
                            scopeGameEnabled = appId != null,
                            onScopeChange = { visualScope = it },
                            faceStyle = device.faceStyle,
                            stateOf = { visualStateOf(it) },
                            flash = flashSet,
                            capturing = visualCapture,
                            onHotspotTap = { button ->
                                visualCapture = if (visualCapture == button) null else button
                                if (visualCapture != null) {
                                    captureTarget = null
                                    captureGyroActivate = false
                                    captureLayerTrigger = false
                                    captureSwipe = null
                                }
                                status = null
                            },
                            onCancelCapture = { visualCapture = null },
                            onRestoreControl = { restoreVisualControl(it) },
                            onRestoreAll = { restoreVisualScope() },
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                        // ── F1: Stick (deadzone radial/axial + response curve + LUT) ──
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        Text(
                            text = stringResource(R.string.gamepad_stick_transform_title),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                        StickTransformBlock(
                            title = stringResource(R.string.gamepad_stick_left_title),
                            mode = leftStickMode,
                            curve = leftCurve,
                            lut = leftLut,
                            onModeChange = { leftStickMode = it },
                            onCurveChange = { leftCurve = it },
                            onExportLut = {
                                pendingLutExport = leftLut
                                lutExportLauncher.launch("gamepad-lut-left.json")
                            },
                            onImportLut = {
                                pendingLutImportSetter = { clean -> leftLut = clean }
                                lutImportLauncher.launch(arrayOf("application/json", "text/plain"))
                            },
                        )
                        StickTransformBlock(
                            title = stringResource(R.string.gamepad_stick_right_title),
                            mode = rightStickMode,
                            curve = rightCurve,
                            lut = rightLut,
                            onModeChange = { rightStickMode = it },
                            onCurveChange = { rightCurve = it },
                            onExportLut = {
                                pendingLutExport = rightLut
                                lutExportLauncher.launch("gamepad-lut-right.json")
                            },
                            onImportLut = {
                                pendingLutImportSetter = { clean -> rightLut = clean }
                                lutImportLauncher.launch(arrayOf("application/json", "text/plain"))
                            },
                        )
                        // ── F1.2: Flick Stick ──
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        Text(
                            text = stringResource(R.string.gamepad_flick_stick_title),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                        val flickInteraction = remember { MutableInteractionSource() }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .gamepadSelectable(
                                    selected = flickStickEnabled,
                                    onClick = { flickStickEnabled = !flickStickEnabled },
                                    shape = RoundedCornerShape(8.dp),
                                    interactionSource = flickInteraction,
                                )
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.gamepad_flick_stick_toggle_title),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = flickStickEnabled,
                                onCheckedChange = { flickStickEnabled = it },
                                modifier = Modifier.focusProperties { canFocus = false },
                            )
                        }
                        if (flickStickEnabled) {
                            GyroSliderRow(
                                title = stringResource(R.string.gamepad_flick_stick_threshold_title),
                                value = flickRadius,
                                range = 0.5f..1f,
                                format = { String.format(java.util.Locale.US, "%.2f", it) },
                                onValueChange = { flickRadius = it },
                            )
                            GyroSliderRow(
                                title = stringResource(R.string.gamepad_flick_stick_snap_title),
                                value = flickSnap,
                                range = 0f..45f,
                                format = { String.format(java.util.Locale.US, "%.0f°", it) },
                                onValueChange = { flickSnap = it },
                            )
                        }

                        GamepadButton.entries.forEach { button ->
                            val rowBinding = bindingFor(button)
                            RemapRow(
                                button = button,
                                faceStyle = device.faceStyle,
                                binding = rowBinding,
                                capturing = captureTarget == button,
                                onClick = {
                                    captureTarget = if (captureTarget == button) null else button
                                    visualCapture = null
                                    captureSwipe = null
                                    status = null
                                },
                                onClear = { clearBinding(button) },
                                // F §1.4: toggle Turbo no chip de binding da camada
                                // (período fixo 80 ms v2; OFF = byte-identical).
                                onToggleTurbo = { setTurbo(button, !(rowBinding?.turbo ?: false)) },
                            )
                        }

                        // ── D (spec 2026-08-16-D-touchpad-swipes-macros, §1.4):
                        // seção Swipes — 8 direções → macro / "Abrir radial". Só com
                        // hasTouchpad (capability V11 — a seção SOME sem touchpad
                        // físico, nunca mostra erro).
                        if (device.hasTouchpad) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            Text(
                                text = stringResource(R.string.gamepad_touchpad_swipes_title),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(bottom = 2.dp),
                            )
                            Text(
                                text = stringResource(R.string.gamepad_touchpad_swipes_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                            SwipeDir.entries.forEach { dir ->
                                SwipeBindingRow(
                                    dir = dir,
                                    binding = swipeBindings[dir.name],
                                    capturing = captureSwipe == dir,
                                    onCaptureToggle = {
                                        captureSwipe = if (captureSwipe == dir) null else dir
                                        if (captureSwipe != null) {
                                            captureTarget = null
                                            captureGyroActivate = false
                                            captureLayerTrigger = false
                                            visualCapture = null
                                        }
                                        status = null
                                    },
                                    onOpenRadial = {
                                        swipeBindings = swipeBindings + (
                                            dir.name to listOf(RadialMacroKey(SWIPE_OPEN_RADIAL))
                                        )
                                        status = null
                                    },
                                    onClear = {
                                        swipeBindings = swipeBindings - dir.name
                                        status = null
                                    },
                                )
                            }
                        }
                    }
                    // ── U1: Gyro (spec 2026-08-14-gamepad-u1-gyro, §1.5) — só com
                    // capability (V11: a seção SOME quando o device não expõe sensor —
                    // nunca mostra erro). Modo, sensibilidade, deadzone e botão de
                    // ativação (hold; null = sempre ativo, recenter na borda).
                    if (device.hasGyro) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Text(
                            text = stringResource(R.string.gamepad_gyro_title),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            GyroModeRow(GyroMode.OFF, gyroMode == GyroMode.OFF, Modifier.weight(1f)) { gyroMode = GyroMode.OFF }
                            GyroModeRow(GyroMode.MOUSE, gyroMode == GyroMode.MOUSE, Modifier.weight(1f)) { gyroMode = GyroMode.MOUSE }
                            GyroModeRow(GyroMode.CAMERA, gyroMode == GyroMode.CAMERA, Modifier.weight(1f)) { gyroMode = GyroMode.CAMERA }
                        }
                        GyroSliderRow(
                            title = stringResource(R.string.gamepad_gyro_sensitivity_title),
                            value = gyroSensitivity,
                            range = 0.1f..3.0f,
                            onValueChange = { gyroSensitivity = it },
                        )
                        // G3 (spec 2026-08-16-G-gyro-v2): sensibilidade vertical
                        // (null = usa a de cima — 1.0 aqui = igual à horizontal).
                        GyroSliderRow(
                            title = stringResource(R.string.gamepad_gyro_sensitivity_y_title),
                            value = gyroSensitivityY,
                            range = 0.1f..3.0f,
                            onValueChange = { gyroSensitivityY = it },
                        )
                        // P2-4 (spec 2026-08-14-gamepad-upgrades-pendencias): a UI
                        // exibe a deadzone em °/s (unidade dos usuários — Dolphin/
                        // JoyShock/Steam Input); a persistência continua rad/s (sem
                        // migração). Slider 0–30°/s; default 0.05 rad ≈ 2.9°/s.
                        GyroSliderRow(
                            title = stringResource(R.string.gamepad_gyro_deadzone_title),
                            value = gyroDeadzone,
                            range = 0.0f..GYRO_DEADZONE_MAX_RAD_S,
                            format = { rad ->
                                String.format(java.util.Locale.US, "%.1f°/s", rad * RAD_TO_DEG)
                            },
                            onValueChange = { gyroDeadzone = it },
                        )
                        // Botão de ativação (capture mode — mesmo padrão do remap).
                        val activateInteraction = remember { MutableInteractionSource() }
                        val activateLabel = gyroActivateButton?.let { name ->
                            val logical = runCatching { GamepadButton.valueOf(name) }.getOrNull()
                            if (logical != null) {
                                stringResource(GamepadGlyphProvider.labelRes(logical, device.faceStyle))
                            } else {
                                name
                            }
                        } ?: stringResource(R.string.gamepad_gyro_activate_always)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .gamepadSelectable(
                                    selected = captureGyroActivate,
                                    onClick = {
                                        captureGyroActivate = !captureGyroActivate
                                        captureTarget = null
                                        visualCapture = null
                                        captureSwipe = null
                                        status = null
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    interactionSource = activateInteraction,
                                )
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.gamepad_gyro_activate_title),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = if (captureGyroActivate) {
                                    stringResource(R.string.gamepad_remap_press_to_bind)
                                } else {
                                    activateLabel
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // G5 (spec 2026-08-16-G-gyro-v2): ativação por TOGGLE — a
                        // borda de descida do botão flipa o latch (sem botão de
                        // ativação o gyro é sempre ativo; o switch não aparece).
                        if (gyroActivateButton != null) {
                            GyroToggleRow(
                                title = stringResource(R.string.gamepad_gyro_activate_toggle_title),
                                subtitle = stringResource(R.string.gamepad_gyro_activate_toggle_subtitle),
                                checked = gyroActivateToggle,
                                onCheckedChange = { gyroActivateToggle = it },
                            )
                        }
                        // G3 (spec 2026-08-16-G-gyro-v2): inversão por eixo (null = false).
                        GyroToggleRow(
                            title = stringResource(R.string.gamepad_gyro_invert_x_title),
                            checked = gyroInvertX,
                            onCheckedChange = { gyroInvertX = it },
                        )
                        GyroToggleRow(
                            title = stringResource(R.string.gamepad_gyro_invert_y_title),
                            checked = gyroInvertY,
                            onCheckedChange = { gyroInvertY = it },
                        )
                        // G2 (spec 2026-08-16-G-gyro-v2): smoothing One Euro opt-in
                        // (MOUSE) — off = ambos null = caminho byte-identical.
                        GyroToggleRow(
                            title = stringResource(R.string.gamepad_gyro_smooth_title),
                            subtitle = stringResource(R.string.gamepad_gyro_smooth_subtitle),
                            checked = gyroSmoothEnabled,
                            onCheckedChange = { gyroSmoothEnabled = it },
                        )
                        if (gyroSmoothEnabled) {
                            GyroSliderRow(
                                title = stringResource(R.string.gamepad_gyro_smooth_min_cutoff_title),
                                value = gyroSmoothMinCutoff,
                                range = 0.1f..3.0f,
                                format = { String.format(java.util.Locale.US, "%.1f Hz", it) },
                                onValueChange = { gyroSmoothMinCutoff = it },
                            )
                            GyroSliderRow(
                                title = stringResource(R.string.gamepad_gyro_smooth_beta_title),
                                value = gyroSmoothBeta,
                                range = 0.0f..2.0f,
                                format = { String.format(java.util.Locale.US, "%.1f", it) },
                                onValueChange = { gyroSmoothBeta = it },
                            )
                        }
                        // G4 (spec 2026-08-16-G-gyro-v2): shaping do CAMERA — teto da
                        // deflexão e floor acima da deadzone (só relevantes nesse modo).
                        if (gyroMode == GyroMode.CAMERA) {
                            GyroSliderRow(
                                title = stringResource(R.string.gamepad_gyro_stick_max_output_title),
                                value = gyroStickMaxOutput,
                                range = 0.1f..1.0f,
                                format = { String.format(java.util.Locale.US, "%.0f%%", it * 100f) },
                                onValueChange = { gyroStickMaxOutput = it },
                            )
                            GyroSliderRow(
                                title = stringResource(R.string.gamepad_gyro_stick_anti_deadzone_title),
                                value = gyroStickAntiDeadzone,
                                range = 0.0f..1.0f,
                                format = { String.format(java.util.Locale.US, "%.0f%%", it * 100f) },
                                onValueChange = { gyroStickAntiDeadzone = it },
                            )
                        }
                        // G6 (spec 2026-08-16-G-gyro-v2): grip angle — rotação do par
                        // (X, Z) no eixo longitudinal; calibrável também pelo botão do
                        // card de diagnóstico (Settings → Gamepad).
                        GyroSliderRow(
                            title = stringResource(R.string.gamepad_gyro_grip_title),
                            value = gyroGripAngleDeg,
                            range = -90f..90f,
                            format = { String.format(java.util.Locale.US, "%+.0f°", it) },
                            onValueChange = { gyroGripAngleDeg = it },
                        )
                        Text(
                            text = stringResource(R.string.gamepad_gyro_grip_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                        )
                        // F1.3 (spec 2026-08-15-input-core-avancado): fusão Mahony
                        // opt-in — corrige pitch/roll pela gravidade; yaw permanece no
                        // recenter + calibração contínua. Desligado = byte-identical.
                        val fusionInteraction = remember { MutableInteractionSource() }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .gamepadSelectable(
                                    selected = gyroFusionEnabled,
                                    onClick = { gyroFusionEnabled = !gyroFusionEnabled },
                                    shape = RoundedCornerShape(8.dp),
                                    interactionSource = fusionInteraction,
                                )
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.gamepad_gyro_fusion_title),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = stringResource(R.string.gamepad_gyro_fusion_subtitle),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = gyroFusionEnabled,
                                onCheckedChange = { gyroFusionEnabled = it },
                                modifier = Modifier.focusProperties { canFocus = false },
                            )
                        }
                    }

                    // ── P2-6: Touchpad (spec 2026-08-14-touchpad-drag-double-tap) ──
                    // Duplo-toque = clique direito (opt-in por perfil; OFF = 2 cliques,
                    // byte-identical com o U2 — V10).
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    val touchpadInteraction = remember { MutableInteractionSource() }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .gamepadSelectable(
                                selected = touchpadDoubleTapRightClick,
                                onClick = { touchpadDoubleTapRightClick = !touchpadDoubleTapRightClick },
                                shape = RoundedCornerShape(8.dp),
                                interactionSource = touchpadInteraction,
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.gamepad_touchpad_double_tap_title),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = stringResource(R.string.gamepad_touchpad_double_tap_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = touchpadDoubleTapRightClick,
                            onCheckedChange = { touchpadDoubleTapRightClick = it },
                            modifier = Modifier.focusProperties { canFocus = false },
                        )
                    }

                    // ── Footer ──
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = {
                            layers = emptyMap()
                            status = null
                        }) {
                            Text(stringResource(R.string.gamepad_remap_reset))
                        }
                        Row {
                            TextButton(onClick = {
                                val json = editorProfile().toJson()
                                val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clip.setPrimaryClip(ClipData.newPlainText("gamepad_profile", json))
                                status = context.getString(R.string.gamepad_remap_exported)
                            }) {
                                Text(stringResource(R.string.gamepad_remap_export))
                            }
                            TextButton(onClick = {
                                val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val json = clip.primaryClip?.getItemAt(0)?.text?.toString()
                                val imported = json?.let { GamepadProfile.fromJson(it) }
                                if (imported == null) {
                                    status = context.getString(R.string.gamepad_remap_import_failed)
                                } else {
                                    applyImportedProfile(imported)
                                    status = context.getString(R.string.gamepad_remap_imported)
                                }
                            }) {
                                Text(stringResource(R.string.gamepad_remap_import))
                            }
                            // F3.3 (spec 2026-08-15-input-core-avancado): export/import
                            // por ARQUIVO (SAF) — estrutura cloud-ready (schemaVersion).
                            TextButton(onClick = {
                                pendingProfileExport = editorProfile().toJson()
                                profileExportLauncher.launch("gamepad-profile.json")
                            }) {
                                Text(stringResource(R.string.gamepad_profile_export_file))
                            }
                            TextButton(onClick = {
                                profileImportLauncher.launch(arrayOf("application/json", "text/plain"))
                            }) {
                                Text(stringResource(R.string.gamepad_profile_import_file))
                            }
                            // E (spec 2026-08-16-E, §1.3): catálogo comunitário
                            // (offline). Contexto = device ativo + jogo atual; sem
                            // jogo o botão desabilita com hint (mesmo padrão do
                            // escopo "Este jogo" de B §1.4).
                            TextButton(
                                enabled = appId != null,
                                onClick = { catalogOpen = true },
                            ) {
                                Text(stringResource(R.string.gamepad_profile_catalog))
                            }
                            if (appId == null) {
                                Text(
                                    text = stringResource(R.string.gamepad_profile_catalog_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.gamepad_remap_cancel))
                        }
                        TextButton(onClick = {
                            onSave(editorProfile())
                            // B §1.4: persiste o override do JOGO (mapa visual) na
                            // chave appId do gameStore — o perfil do device não é
                            // contaminado. Sem edição no escopo GAME o save é
                            // idempotente (mesmo conteúdo / default remove a entrada).
                            if (appId != null) {
                                hub.saveGameProfile(
                                    appId,
                                    (initialGameProfile ?: GamepadProfile()).copy(layers = gameLayers),
                                )
                            }
                        }) {
                            Icon(Icons.Filled.Save, contentDescription = null)
                            Text(stringResource(R.string.gamepad_remap_save))
                        }
                    }
                }
            }
        }
    }

    // E (spec 2026-08-16-E, §1.3): browser do catálogo como JANELA PRÓPRIA por cima
    // deste dialog (dialogs não compartilham escopo de foco — um dono por janela).
    if (catalogOpen) {
        ProfileCatalogBrowser(
            appId = appId,
            onApply = { entry ->
                status = context.getString(R.string.gamepad_profile_catalog_applied, entry.name)
            },
            onDismiss = { catalogOpen = false },
        )
    }
}

@Composable
private fun RemapRow(
    button: GamepadButton,
    faceStyle: FaceStyle,
    binding: GamepadBindingCodec.LayerBinding?,
    capturing: Boolean,
    onClick: () -> Unit,
    onClear: () -> Unit,
    onToggleTurbo: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .gamepadSelectable(
                selected = capturing,
                onClick = onClick,
                shape = RoundedCornerShape(8.dp),
                interactionSource = interactionSource,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val glyphLabel = stringResource(GamepadGlyphProvider.labelRes(button, faceStyle))
        Text(
            text = if (capturing) stringResource(R.string.gamepad_remap_press_to_bind) else glyphLabel,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (!capturing) {
            Text(
                text = bindingDescription(binding?.raw, context),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (binding != null) {
                // F §1.4: chip Turbo — rapid-fire de 80 ms enquanto a fonte está
                // segurada (OFF = token byte-identical ao v1).
                TextButton(onClick = onToggleTurbo) {
                    Text(
                        text = stringResource(R.string.gamepad_binding_turbo_title),
                        color = if (binding.turbo) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                TextButton(onClick = onClear) {
                    Text(stringResource(R.string.gamepad_remap_clear))
                }
            }
        }
    }
}

/**
 * D (spec 2026-08-16-D-touchpad-swipes-macros, §1.4): linha de UMA direção de swipe
 * — rótulo, ação atual (macro / "Abrir radial" / nada), captura de macro por bus cru
 * (teclas concatenam — padrão do RadialMenuEditorDialog) e atalho "Abrir radial"
 * (binding especial SWIPE_OPEN_RADIAL).
 */
@Composable
private fun SwipeBindingRow(
    dir: SwipeDir,
    binding: List<RadialMacroKey>?,
    capturing: Boolean,
    onCaptureToggle: () -> Unit,
    onOpenRadial: () -> Unit,
    onClear: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val openRadial = binding != null && binding.size == 1 && binding[0].keyCode == SWIPE_OPEN_RADIAL
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .gamepadSelectable(
                selected = capturing,
                onClick = onCaptureToggle,
                shape = RoundedCornerShape(8.dp),
                interactionSource = interactionSource,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(swipeDirLabelRes(dir)),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (capturing) {
            Text(
                text = stringResource(R.string.gamepad_touchpad_swipe_capture_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Text(
                text = when {
                    openRadial -> stringResource(R.string.gamepad_touchpad_swipe_open_radial_action)
                    binding.isNullOrEmpty() -> stringResource(R.string.gamepad_touchpad_swipe_unbound)
                    else -> binding.joinToString(" → ") { keyName(it.keyCode) }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onOpenRadial) {
                Text(
                    text = stringResource(R.string.gamepad_touchpad_swipe_open_radial_action),
                    color = if (openRadial) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (!binding.isNullOrEmpty()) {
                TextButton(onClick = onClear) {
                    Text(stringResource(R.string.gamepad_remap_clear))
                }
            }
        }
    }
}

/** D: rótulo localizado de cada direção de swipe. */
private fun swipeDirLabelRes(dir: SwipeDir): Int = when (dir) {
    SwipeDir.UP -> R.string.gamepad_touchpad_swipe_up
    SwipeDir.UP_RIGHT -> R.string.gamepad_touchpad_swipe_up_right
    SwipeDir.RIGHT -> R.string.gamepad_touchpad_swipe_right
    SwipeDir.DOWN_RIGHT -> R.string.gamepad_touchpad_swipe_down_right
    SwipeDir.DOWN -> R.string.gamepad_touchpad_swipe_down
    SwipeDir.DOWN_LEFT -> R.string.gamepad_touchpad_swipe_down_left
    SwipeDir.LEFT -> R.string.gamepad_touchpad_swipe_left
    SwipeDir.UP_LEFT -> R.string.gamepad_touchpad_swipe_up_left
}

private fun bindingDescription(binding: RawBinding?, context: Context): String = when (binding) {
    null -> context.getString(R.string.gamepad_remap_binding_none)
    is RawBinding.Key -> keyName(binding.keyCode)
    is RawBinding.Axis -> context.getString(
        R.string.gamepad_remap_axis_format,
        binding.axis,
        if (binding.direction > 0) "+" else "-",
    )
    is RawBinding.Hat -> context.getString(R.string.gamepad_remap_hat_format, binding.hat, binding.mask)
}

/** Nome curto dos keycodes Android reais (AndroidConstants — nada inventado). */
private fun keyName(keyCode: Int): String = when (keyCode) {
    app.gamenative.gamepad.mapping.AndroidConstants.BUTTON_A -> "A"
    app.gamenative.gamepad.mapping.AndroidConstants.BUTTON_B -> "B"
    app.gamenative.gamepad.mapping.AndroidConstants.BUTTON_X -> "X"
    app.gamenative.gamepad.mapping.AndroidConstants.BUTTON_Y -> "Y"
    app.gamenative.gamepad.mapping.AndroidConstants.BUTTON_L1 -> "LB"
    app.gamenative.gamepad.mapping.AndroidConstants.BUTTON_R1 -> "RB"
    app.gamenative.gamepad.mapping.AndroidConstants.BUTTON_L2 -> "LT"
    app.gamenative.gamepad.mapping.AndroidConstants.BUTTON_R2 -> "RT"
    app.gamenative.gamepad.mapping.AndroidConstants.BUTTON_THUMBL -> "LS"
    app.gamenative.gamepad.mapping.AndroidConstants.BUTTON_THUMBR -> "RS"
    app.gamenative.gamepad.mapping.AndroidConstants.BUTTON_START -> "START"
    app.gamenative.gamepad.mapping.AndroidConstants.BUTTON_SELECT -> "SELECT"
    app.gamenative.gamepad.mapping.AndroidConstants.BUTTON_MODE -> "GUIDE"
    in app.gamenative.gamepad.mapping.AndroidConstants.DPAD_UP..app.gamenative.gamepad.mapping.AndroidConstants.DPAD_RIGHT -> "DPAD"
    in app.gamenative.gamepad.mapping.AndroidConstants.BUTTON_1..app.gamenative.gamepad.mapping.AndroidConstants.BUTTON_16 -> {
        val genericIndex = keyCode - app.gamenative.gamepad.mapping.AndroidConstants.BUTTON_1 + 1
        "B$genericIndex" // B1..B16 (BUTTON_1..16)
    }
    else -> "key:$keyCode"
}

/** U3 — seletor de camadas (DEFAULT + extras) com add/remove navegável por gamepad. */
@Composable
private fun LayerSelectorRow(
    layers: List<String>,
    selectedLayer: String,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val allLayers = listOf(ActionLayer.DEFAULT.name) + layers.filter { it != ActionLayer.DEFAULT.name }
        allLayers.forEach { name ->
            LayerChip(
                name = name,
                selected = name == selectedLayer,
                removable = name != ActionLayer.DEFAULT.name,
                onClick = { onSelect(name) },
                onRemove = { onRemove(name) },
            )
        }
        LayerChip(
            name = "+",
            selected = false,
            removable = false,
            onClick = onAdd,
            onRemove = {},
        )
    }
}

/** U3 — chip de camada (gamepadSelectable; "x" remove quando aplicável). */
@Composable
private fun LayerChip(
    name: String,
    selected: Boolean,
    removable: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .gamepadSelectable(
                selected = selected,
                onClick = onClick,
                shape = RoundedCornerShape(8.dp),
                interactionSource = interactionSource,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = if (name == ActionLayer.DEFAULT.name) {
                stringResource(R.string.gamepad_layer_default)
            } else {
                name
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (removable) {
            Text(
                text = "✕",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.gamepadSelectable(
                    selected = false,
                    onClick = onRemove,
                    shape = RoundedCornerShape(4.dp),
                    interactionSource = remember { MutableInteractionSource() },
                ),
            )
        }
    }
}

/** U3 — linha de trigger da camada (botão + modo; capture mode para o botão). */
@Composable
private fun LayerTriggerRow(
    trigger: LayerTriggerSpec?,
    onModeChange: (LayerTriggerMode) -> Unit,
    capturing: Boolean,
    onCapture: () -> Unit,
    onClearTrigger: () -> Unit,
    faceStyle: FaceStyle,
) {
    val captureInteraction = remember { MutableInteractionSource() }
    val triggerLabel = if (trigger != null) {
        val button = runCatching { GamepadButton.valueOf(trigger.button) }.getOrNull()
        val buttonLabel = if (button != null) {
            stringResource(GamepadGlyphProvider.labelRes(button, faceStyle))
        } else {
            trigger.button
        }
        "$buttonLabel · " + stringResource(
            when (trigger.mode) {
                LayerTriggerMode.HOLD -> R.string.gamepad_layer_mode_hold
                LayerTriggerMode.TOGGLE -> R.string.gamepad_layer_mode_toggle
                LayerTriggerMode.DOUBLE_TAP -> R.string.gamepad_layer_mode_double_tap
            },
        )
    } else {
        stringResource(R.string.gamepad_layer_no_trigger)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .gamepadSelectable(
                selected = capturing,
                onClick = onCapture,
                shape = RoundedCornerShape(8.dp),
                interactionSource = captureInteraction,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.gamepad_layer_trigger_title),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (capturing) {
                stringResource(R.string.gamepad_remap_press_to_bind)
            } else {
                triggerLabel
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (trigger != null && !capturing) {
            TextButton(onClick = onClearTrigger) {
                Text(stringResource(R.string.gamepad_remap_clear))
            }
        }
    }
    if (trigger != null && !capturing) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LayerTriggerModeChip(LayerTriggerMode.HOLD, trigger.mode == LayerTriggerMode.HOLD, Modifier.weight(1f)) { onModeChange(LayerTriggerMode.HOLD) }
            LayerTriggerModeChip(LayerTriggerMode.TOGGLE, trigger.mode == LayerTriggerMode.TOGGLE, Modifier.weight(1f)) { onModeChange(LayerTriggerMode.TOGGLE) }
            LayerTriggerModeChip(LayerTriggerMode.DOUBLE_TAP, trigger.mode == LayerTriggerMode.DOUBLE_TAP, Modifier.weight(1f)) { onModeChange(LayerTriggerMode.DOUBLE_TAP) }
        }
    }
}

/** U3 — chip de modo do trigger. */
@Composable
private fun LayerTriggerModeChip(
    mode: LayerTriggerMode,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .gamepadSelectable(
                selected = selected,
                onClick = onClick,
                shape = RoundedCornerShape(8.dp),
                interactionSource = interactionSource,
            )
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(
                when (mode) {
                    LayerTriggerMode.HOLD -> R.string.gamepad_layer_mode_hold
                    LayerTriggerMode.TOGGLE -> R.string.gamepad_layer_mode_toggle
                    LayerTriggerMode.DOUBLE_TAP -> R.string.gamepad_layer_mode_double_tap
                },
            ),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/**
 * G (spec 2026-08-16-G-gyro-v2) — linha de switch da seção Gyro (toggle de
 * ativação, inversão por eixo, smoothing) com navegação de gamepad no MESMO
 * padrão da linha de fusão (gamepadSelectable + Switch sem foco próprio).
 */
@Composable
private fun GyroToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .gamepadSelectable(
                selected = checked,
                onClick = { onCheckedChange(!checked) },
                shape = RoundedCornerShape(8.dp),
                interactionSource = interactionSource,
            )
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.focusProperties { canFocus = false },
        )
    }
}

/** U1 — linha de seleção de modo do gyro (OFF/MOUSE/CAMERA). */
@Composable
private fun GyroModeRow(
    mode: GyroMode,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .gamepadSelectable(
                selected = selected,
                onClick = onClick,
                shape = RoundedCornerShape(8.dp),
                interactionSource = interactionSource,
            )
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(
                when (mode) {
                    GyroMode.OFF -> R.string.gamepad_gyro_mode_off
                    GyroMode.MOUSE -> R.string.gamepad_gyro_mode_mouse
                    GyroMode.CAMERA -> R.string.gamepad_gyro_mode_camera
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/**
 * U1 — slider de ajuste do gyro com A-lock (mesmo padrão dos settings). P2-4: o
 * valor exibido passa por [format] (ex.: °/s para a deadzone) — o valor interno
 * permanece na unidade persistida (rad/s).
 */
@Composable
private fun GyroSliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    format: (Float) -> String = { String.format(java.util.Locale.US, "%.2f", it) },
) {
    val interactionSource = remember { MutableInteractionSource() }
    var isLocked by remember { mutableStateOf(false) }
    val adjustStep = (range.endInclusive - range.start) / 20f
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .gamepadAdjustableRow(
                    locked = isLocked,
                    onLockChange = { isLocked = it },
                    onAdjust = { delta ->
                        onValueChange(
                            (value + delta * adjustStep).coerceIn(range.start, range.endInclusive),
                        )
                    },
                    shape = RoundedCornerShape(8.dp),
                    interactionSource = interactionSource,
                )
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = range,
                modifier = Modifier
                    .weight(1f)
                    .focusProperties { canFocus = false },
            )
            Text(
                text = format(value),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** °/s (unidade da UI) — P2-4. */
private const val RAD_TO_DEG = 180f / kotlin.math.PI.toFloat()

/** Máximo da deadzone na UI: 30°/s convertido para rad/s (persistência). */
private const val GYRO_DEADZONE_MAX_RAD_S = 30f / RAD_TO_DEG

/** Eixo dominante capturável (exclui hat — o dpad já tem botões próprios). */
private fun strongestCapturableAxis(ev: MotionEvent): Triple<Int, Int, Float>? {
    val candidates = listOf(
        MotionEvent.AXIS_X to +1,
        MotionEvent.AXIS_Y to +1,
        MotionEvent.AXIS_Z to +1,
        MotionEvent.AXIS_RZ to +1,
        MotionEvent.AXIS_LTRIGGER to +1,
        MotionEvent.AXIS_RTRIGGER to +1,
        MotionEvent.AXIS_BRAKE to +1,
        MotionEvent.AXIS_GAS to +1,
    )
    var best: Triple<Int, Int, Float>? = null
    for ((axis, direction) in candidates) {
        val value = ev.getAxisValue(axis)
        val magnitude = abs(value)
        if (best == null || magnitude > best!!.third) {
            best = Triple(axis, if (value < 0f) -direction else direction, magnitude)
        }
    }
    return best
}

// ── F1 (spec 2026-08-15-input-core-avancado) ──

/** Defaults do Flick Stick na UI (espelham o FlickStickConfig). */
private const val DEFAULT_FLICK_RADIUS = 0.85f
private const val DEFAULT_FLICK_SNAP = 15f

/** LUT → JSON `{"lut":[...]}` (SAF export). Valores já sanitizados no uso. */
private fun lutJson(lut: List<Float>): String {
    val values = lut.joinToString(",") { v -> if (v.isFinite()) v.toString() else "0" }
    return """{"lut":[$values]}"""
}

/** JSON `{"lut":[...]}` → LUT sanitizada (vazia = inválida — nunca crasha). */
private fun parseLutJson(text: String): List<Float> {
    val parsed = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
    }.getOrNull() ?: return emptyList()
    val arr = parsed["lut"]?.jsonArray ?: return emptyList()
    val raw = arr.mapNotNull { it.jsonPrimitive.floatOrNull }
    return StickTransform.sanitizeLut(raw)
}

/**
 * F1.1 — bloco de um stick: deadzone radial/axial (chips), response curve (chips),
 * preview read-only da curva (Canvas) e import/export da LUT (SAF).
 */
@Composable
private fun StickTransformBlock(
    title: String,
    mode: DeadzoneMode,
    curve: ResponseCurve,
    lut: List<Float>,
    onModeChange: (DeadzoneMode) -> Unit,
    onCurveChange: (ResponseCurve) -> Unit,
    onExportLut: () -> Unit,
    onImportLut: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.gamepad_stick_deadzone_mode_title),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            ChoiceChipRow(
                options = listOf(
                    stringResource(R.string.gamepad_stick_deadzone_mode_radial) to DeadzoneMode.RADIAL,
                    stringResource(R.string.gamepad_stick_deadzone_mode_axial) to DeadzoneMode.AXIAL,
                ),
                selected = mode,
                onSelect = onModeChange,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.gamepad_stick_curve_title),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            ChoiceChipRow(
                options = listOf(
                    stringResource(R.string.gamepad_stick_curve_linear) to ResponseCurve.LINEAR,
                    stringResource(R.string.gamepad_stick_curve_exponential) to ResponseCurve.EXPONENTIAL,
                    stringResource(R.string.gamepad_stick_curve_scurve) to ResponseCurve.SCURVE,
                    stringResource(R.string.gamepad_stick_curve_lut) to ResponseCurve.LUT,
                ),
                selected = curve,
                onSelect = onCurveChange,
            )
        }
        if (curve == ResponseCurve.LUT) {
            LutPreviewCanvas(lut = lut)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onImportLut) {
                    Text(stringResource(R.string.gamepad_stick_lut_import))
                }
                TextButton(onClick = onExportLut) {
                    Text(stringResource(R.string.gamepad_stick_lut_export))
                }
            }
        }
    }
}

/** Chips mutuamente exclusivos (gamepad-navegáveis) para opções genéricas. */
@Composable
private fun <T> ChoiceChipRow(
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (label, value) ->
            val interactionSource = remember { MutableInteractionSource() }
            Row(
                modifier = Modifier
                    .gamepadSelectable(
                        selected = value == selected,
                        onClick = { onSelect(value) },
                        shape = RoundedCornerShape(8.dp),
                        interactionSource = interactionSource,
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (value == selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/**
 * F1.1 — preview read-only da curva (Canvas): poli-linha da LUT sanitizada + linha
 * de referência linear tracejada. Nenhum estado — puro desenho.
 */
@Composable
private fun LutPreviewCanvas(lut: List<Float>) {
    val lineColor = MaterialTheme.colorScheme.primary
    val refColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .height(56.dp),
    ) {
        val w = size.width
        val h = size.height
        val clean = StickTransform.sanitizeLut(lut)
        // referência linear
        drawLine(refColor, Offset(0f, h), Offset(w, 0f), strokeWidth = 2f)
        if (clean.isNotEmpty()) {
            val path = Path()
            val n = clean.size
            for (i in 0 until n) {
                val x = if (n == 1) 0f else i * (w / (n - 1))
                val y = (1f - clean[i]) * h
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color = lineColor)
        }
    }
}


// ── B (spec 2026-08-16-B-remap-visual-ppsspp) ──

/** Duração do flash do mapa visual (mesma constante do ControllerVisualView). */
private const val VISUAL_FLASH_MS = 600L

/** B §1.4 — escopo do mapa visual: override do jogo (chave appId) ou global do device. */
private enum class VisualScope { GAME, DEVICE }

/**
 * B §1.5 — seção colapsável "Mapa visual" no TOPO do dialog de remap: header com
 * colapso + "Restaurar tudo" (geral, §1.4), seletor de escopo "Este jogo"/"Todos os
 * jogos" (§1.4) e o [ControllerVisualView] (desenho vetorial + flash + captura §1.2/
 * §1.3). Tudo gamepad-navegável (view-level — diálogo, regra do AGENTS.md: nunca
 * bus-navigators dentro de janela de diálogo).
 */
@Composable
private fun VisualRemapSection(
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    scope: VisualScope,
    scopeGameEnabled: Boolean,
    onScopeChange: (VisualScope) -> Unit,
    faceStyle: FaceStyle,
    stateOf: (GamepadButton) -> VisualControlState,
    flash: androidx.compose.runtime.State<Set<String>>,
    capturing: GamepadButton?,
    onHotspotTap: (GamepadButton) -> Unit,
    onCancelCapture: () -> Unit,
    onRestoreControl: (GamepadButton) -> Unit,
    onRestoreAll: () -> Unit,
) {
    val hotspots = remember(faceStyle) { ControllerVisualLayout.layoutFor(faceStyle) }
    val headerInteraction = remember { MutableInteractionSource() }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .gamepadSelectable(
                    selected = false,
                    onClick = onToggleExpanded,
                    shape = RoundedCornerShape(8.dp),
                    interactionSource = headerInteraction,
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.gamepad_visual_section_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            )
            TextButton(onClick = onRestoreAll) {
                Text(stringResource(R.string.gamepad_visual_restore_all))
            }
        }
        if (expanded) {
            // ── Escopo (§1.4): Este jogo / Todos os jogos ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                VisualScopeChip(
                    label = stringResource(R.string.gamepad_visual_scope_game),
                    selected = scope == VisualScope.GAME,
                    enabled = scopeGameEnabled,
                    onClick = { onScopeChange(VisualScope.GAME) },
                )
                VisualScopeChip(
                    label = stringResource(R.string.gamepad_visual_scope_all),
                    selected = scope == VisualScope.DEVICE,
                    enabled = true,
                    onClick = { onScopeChange(VisualScope.DEVICE) },
                )
            }
            if (!scopeGameEnabled) {
                Text(
                    text = stringResource(R.string.gamepad_visual_scope_game_unavailable),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                )
            }

            ControllerVisualView(
                faceStyle = faceStyle,
                hotspots = hotspots,
                stateOf = { control ->
                    val button = runCatching { GamepadButton.valueOf(control) }.getOrNull()
                    if (button != null) stateOf(button) else VisualControlState.AUTO
                },
                flash = flash,
                onHotspotTap = { control ->
                    runCatching { GamepadButton.valueOf(control) }.getOrNull()?.let(onHotspotTap)
                },
                capturingControl = capturing?.name,
                onCancelCapture = onCancelCapture,
                onRestoreControl = { control ->
                    runCatching { GamepadButton.valueOf(control) }.getOrNull()?.let(onRestoreControl)
                },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/** B §1.4 — chip do escopo (gamepadSelectable; desabilitado sem jogo ativo). */
@Composable
private fun VisualScopeChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .gamepadSelectable(
                selected = selected,
                onClick = onClick,
                enabled = enabled,
                shape = RoundedCornerShape(8.dp),
                interactionSource = interactionSource,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) {
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            },
        )
    }
}
