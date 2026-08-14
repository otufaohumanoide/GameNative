package app.gamenative.gamepad.remap

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.PluviaApp
import app.gamenative.R
import app.gamenative.events.AndroidEvent
import app.gamenative.gamepad.FaceStyle
import app.gamenative.gamepad.GyroMode
import app.gamenative.gamepad.layers.LayerTriggerMode
import app.gamenative.gamepad.layers.LayerTriggerSpec
import app.gamenative.gamepad.GamepadButton
import app.gamenative.gamepad.GamepadDevice
import app.gamenative.gamepad.glyphs.GamepadGlyphProvider
import app.gamenative.gamepad.mapping.GamepadMapping
import app.gamenative.gamepad.mapping.RawBinding
import app.gamenative.gamepad.profiles.ActionLayer
import app.gamenative.gamepad.profiles.GamepadProfile
import app.gamenative.ui.component.GamepadFocusScope
import app.gamenative.ui.component.gamepadAdjustableRow
import app.gamenative.ui.component.gamepadBackHandler
import app.gamenative.ui.component.gamepadSelectable
import kotlin.math.abs

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
    var status by remember { mutableStateOf<String?>(null) }

    fun layerMap(layerName: String): Map<String, String> = layers[layerName] ?: emptyMap()

    fun bindingFor(button: GamepadButton): RawBinding? {
        val token = layerMap(selectedLayer)[button.name]
        if (token != null) return GamepadBindingCodec.decode(token)
        // A camada só sobrepõe o que define; DEFAULT mostra o binding do mapping.
        if (selectedLayer == ActionLayer.DEFAULT.name) return mapping.buttons[button]
        return null
    }

    fun commitBinding(button: GamepadButton, binding: RawBinding) {
        val conflict = GamepadButton.entries.any { other ->
            other != button && bindingFor(other)?.let { GamepadBindingCodec.conflicts(it, binding) } == true
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
                                layerTriggers = layerTriggers + (
                                    selectedLayer to LayerTriggerSpec(
                                        button = logical.name,
                                        mode = pendingTriggerMode,
                                    )
                                )
                                status = null
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

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        val initialFocus = remember { FocusRequester() }
        // Enquanto captura, o escopo de foco fica OFF: todo input do controle é captura.
        GamepadFocusScope(
            enabled = captureTarget == null && !captureGyroActivate && !captureLayerTrigger,
            backAction = onDismiss,
            initialFocusRequester = initialFocus,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (captureTarget == null && !captureGyroActivate && !captureLayerTrigger) {
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
                                status = null
                            },
                            onClearTrigger = {
                                layerTriggers = layerTriggers - selectedLayer
                                status = null
                            },
                            faceStyle = device.faceStyle,
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    // ── Lista de botões ──
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        GamepadButton.entries.forEach { button ->
                            RemapRow(
                                button = button,
                                faceStyle = device.faceStyle,
                                binding = bindingFor(button),
                                capturing = captureTarget == button,
                                onClick = {
                                    captureTarget = if (captureTarget == button) null else button
                                    status = null
                                },
                                onClear = { clearBinding(button) },
                            )
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
                        GyroSliderRow(
                            title = stringResource(R.string.gamepad_gyro_deadzone_title),
                            value = gyroDeadzone,
                            range = 0.0f..0.3f,
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
                                val json = profile.copy(
                                layers = layers,
                                layerTriggers = layerTriggers,
                                gyroMode = if (gyroMode == GyroMode.OFF) null else gyroMode,
                                gyroSensitivity = if (gyroSensitivity == 1f) null else gyroSensitivity,
                                gyroDeadzone = if (gyroDeadzone == 0.05f) null else gyroDeadzone,
                                gyroActivateButton = gyroActivateButton,
                            ).toJson()
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
                                    layers = imported.layers
                                    gyroMode = imported.gyroMode ?: GyroMode.OFF
                                    gyroSensitivity = imported.gyroSensitivity ?: 1f
                                    gyroDeadzone = imported.gyroDeadzone ?: 0.05f
                                    gyroActivateButton = imported.gyroActivateButton
                                    status = context.getString(R.string.gamepad_remap_imported)
                                }
                            }) {
                                Text(stringResource(R.string.gamepad_remap_import))
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
                            onSave(
                                profile.copy(
                                    layers = layers,
                                    gyroMode = if (gyroMode == GyroMode.OFF) null else gyroMode,
                                    gyroSensitivity = if (gyroSensitivity == 1f) null else gyroSensitivity,
                                    gyroDeadzone = if (gyroDeadzone == 0.05f) null else gyroDeadzone,
                                    gyroActivateButton = gyroActivateButton,
                                ),
                            )
                        }) {
                            Icon(Icons.Filled.Save, contentDescription = null)
                            Text(stringResource(R.string.gamepad_remap_save))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RemapRow(
    button: GamepadButton,
    faceStyle: FaceStyle,
    binding: RawBinding?,
    capturing: Boolean,
    onClick: () -> Unit,
    onClear: () -> Unit,
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
                text = bindingDescription(binding, context),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (binding != null) {
                TextButton(onClick = onClear) {
                    Text(stringResource(R.string.gamepad_remap_clear))
                }
            }
        }
    }
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

/** U1 — slider de ajuste do gyro com A-lock (mesmo padrão dos settings). */
@Composable
private fun GyroSliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
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
                text = String.format(java.util.Locale.US, "%.2f", value),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

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
