package app.gamenative.gamepad.remap

import android.os.SystemClock
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.PluviaApp
import app.gamenative.R
import app.gamenative.events.GamepadInputEvent
import app.gamenative.gamepad.GamepadDevice
import app.gamenative.gamepad.InputEvent
import app.gamenative.gamepad.expressions.ExprEvaluator
import app.gamenative.gamepad.expressions.ExprParser
import app.gamenative.gamepad.expressions.ExprState
import app.gamenative.ui.component.GamepadFocusScope
import app.gamenative.ui.component.gamepadBackHandler
import app.gamenative.ui.component.gamepadSelectable
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * J1 (spec 2026-08-16-J-expressions-dolphin, §2.3): editor de expressão — janela
 * própria (GamepadFocusScope, padrão do GamepadRemapDialog): campo multiline com
 * parse AO VIVO (erro com coluna), "Inserir entrada" com os nomes válidos do
 * device ([ExprParser.INPUT_NAMES] — GamepadButton + alias + axis:…) e preview
 * numérico ao vivo (reusa o fluxo de eventos do bus como o input viewer da fase
 * C: listener de GamepadInputEvent + relógio de 50 ms para as funções temporais).
 */
@Composable
fun ExprEditorDialog(
    device: GamepadDevice,
    initialSource: String,
    onApply: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var source by remember { mutableStateOf(initialSource) }
    val currentSource by rememberUpdatedState(source)
    var parseError by remember { mutableStateOf<String?>(null) }

    fun reparse(text: String) {
        parseError = runCatching { ExprParser.parse(text) }.exceptionOrNull()?.message
    }

    // Preview ao vivo — estado dos inputs vindo do bus (padrão da fase C).
    val liveInputs = remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
    val currentDeviceId by rememberUpdatedState(device.deviceId)
    DisposableEffect(Unit) {
        fun handle(event: GamepadInputEvent): Boolean {
            val input = event.input
            val eventDeviceId = when (input) {
                is InputEvent.ButtonDown -> input.deviceId
                is InputEvent.ButtonUp -> input.deviceId
                is InputEvent.AxisMotion -> input.deviceId
                else -> return false
            }
            if (eventDeviceId != currentDeviceId) return false
            val update = when (input) {
                is InputEvent.ButtonDown -> mapOf(input.button.name.lowercase() to 1f)
                is InputEvent.ButtonUp -> mapOf(input.button.name.lowercase() to 0f)
                is InputEvent.AxisMotion -> mapOf("axis:" + input.axis.name.lowercase() to input.value)
                else -> emptyMap()
            }
            if (update.isNotEmpty()) liveInputs.value = liveInputs.value + update
            return false
        }
        val handler: (GamepadInputEvent) -> Boolean = ::handle
        PluviaApp.events.on<GamepadInputEvent, Boolean>(handler)
        onDispose { PluviaApp.events.off<GamepadInputEvent, Boolean>(handler) }
    }
    var preview by remember { mutableStateOf<Float?>(null) }
    val previewState = remember { ExprState() }
    LaunchedEffect(Unit) {
        while (true) {
            val ast = runCatching { ExprParser.parse(currentSource) }.getOrNull()
            preview = ast?.let {
                val now = SystemClock.uptimeMillis()
                ExprEvaluator.eval(
                    it,
                    { name, axis -> liveInputs.value[if (axis) "axis:$name" else name.lowercase()] ?: 0f },
                    previewState,
                    50L,
                    now,
                )
            }
            delay(50)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        val initialFocus = remember { FocusRequester() }
        GamepadFocusScope(
            enabled = true,
            backAction = onDismiss,
            initialFocusRequester = initialFocus,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .gamepadBackHandler(onDismiss),
                color = MaterialTheme.colorScheme.background,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.gamepad_expr_editor_title, device.name),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.gamepad_remap_cancel))
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    OutlinedTextField(
                        value = source,
                        onValueChange = {
                            source = it
                            reparse(it)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp),
                        label = { Text(stringResource(R.string.gamepad_expr_field_label)) },
                        minLines = 2,
                        maxLines = 4,
                    )
                    if (parseError != null) {
                        Text(
                            text = context.getString(R.string.gamepad_expr_parse_error, parseError),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    } else {
                        Text(
                            text = context.getString(
                                R.string.gamepad_expr_preview,
                                String.format(Locale.US, "%.3f", preview ?: 0f),
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }

                    Text(
                        text = stringResource(R.string.gamepad_expr_insert_input),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (name in ExprParser.INPUT_NAMES) {
                            ExprInputChip(name) {
                                source = (if (source.isBlank()) "" else "$source ") + name
                                reparse(source)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.gamepad_remap_cancel))
                        }
                        TextButton(
                            enabled = parseError == null && source.isNotBlank(),
                            onClick = { onApply(source.trim()) },
                        ) {
                            Text(stringResource(R.string.gamepad_remap_save))
                        }
                    }
                }
            }
        }
    }
}

/** J1 §2.3: chip de inserção de entrada (nome → cursor). */
@Composable
private fun ExprInputChip(name: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .gamepadSelectable(
                selected = false,
                onClick = onClick,
                shape = RoundedCornerShape(8.dp),
                interactionSource = interactionSource,
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(name, style = MaterialTheme.typography.labelMedium)
    }
}
