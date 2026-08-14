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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.PluviaApp
import app.gamenative.R
import app.gamenative.events.AndroidEvent
import app.gamenative.gamepad.FaceStyle
import app.gamenative.gamepad.GamepadButton
import app.gamenative.gamepad.GamepadDevice
import app.gamenative.gamepad.glyphs.GamepadGlyphProvider
import app.gamenative.gamepad.mapping.GamepadMapping
import app.gamenative.gamepad.mapping.RawBinding
import app.gamenative.gamepad.profiles.ActionLayer
import app.gamenative.gamepad.profiles.GamepadProfile
import app.gamenative.ui.component.GamepadFocusScope
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
    var status by remember { mutableStateOf<String?>(null) }

    val defaultLayer = layers[ActionLayer.DEFAULT.name] ?: emptyMap()

    fun bindingFor(button: GamepadButton): RawBinding? {
        val token = defaultLayer[button.name] ?: return mapping.buttons[button]
        return GamepadBindingCodec.decode(token) ?: mapping.buttons[button]
    }

    fun commitBinding(button: GamepadButton, binding: RawBinding) {
        val conflict = GamepadButton.entries.any { other ->
            other != button && bindingFor(other)?.let { GamepadBindingCodec.conflicts(it, binding) } == true
        }
        if (conflict) {
            status = context.getString(R.string.gamepad_remap_conflict)
            return
        }
        val newLayer = defaultLayer + (button.name to GamepadBindingCodec.encode(binding))
        layers = layers + (ActionLayer.DEFAULT.name to newLayer)
        status = null
    }

    fun clearBinding(button: GamepadButton) {
        if (button.name !in defaultLayer) return
        layers = layers + (ActionLayer.DEFAULT.name to (defaultLayer - button.name))
        status = null
    }

    // Captura via eventos do BUS cru enquanto captureTarget != null (o escopo de foco
    // fica desabilitado: TODO o input do controle vira binding).
    DisposableEffect(captureTarget) {
        if (captureTarget == null) return@DisposableEffect onDispose {}
        val target = captureTarget!!

        fun handleKey(androidEvent: AndroidEvent.KeyEvent): Boolean {
            val ev = androidEvent.event ?: return false
            if (ev.deviceId != device.deviceId) return false
            if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) {
                when (ev.keyCode) {
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                        captureTarget = null
                        status = context.getString(R.string.gamepad_remap_capture_cancelled)
                    }
                    else -> {
                        commitBinding(target, RawBinding.Key(ev.keyCode))
                        captureTarget = null
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
            commitBinding(target, RawBinding.Axis(axis, direction))
            captureTarget = null
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
            enabled = captureTarget == null,
            backAction = onDismiss,
            initialFocusRequester = initialFocus,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (captureTarget == null) Modifier.gamepadBackHandler(onDismiss) else Modifier,
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
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

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
                                val json = profile.copy(layers = layers).toJson()
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
                        TextButton(onClick = { onSave(profile.copy(layers = layers)) }) {
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
