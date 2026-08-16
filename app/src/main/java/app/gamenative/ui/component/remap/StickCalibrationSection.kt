package app.gamenative.ui.component.remap

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gamenative.PluviaApp
import app.gamenative.R
import app.gamenative.events.GamepadInputEvent
import app.gamenative.gamepad.GamepadAxis
import app.gamenative.gamepad.InputEvent
import app.gamenative.gamepad.processing.DeadzoneMode
import app.gamenative.gamepad.processing.ResponseCurve
import app.gamenative.gamepad.processing.StickSample
import app.gamenative.gamepad.processing.StickTransformConfig
import java.util.Locale

/**
 * K7 (spec 2026-08-16-K7, §1.3) — seção de CALIBRAÇÃO VISUAL do stick no remap
 * dialog: duas [JoystickHistoryView] lado a lado (RAW | CALIBRADO com a config em
 * edição), alimentadas pelos eixos LÓGICOS do device via bus (listener
 * `GamepadInputEvent` filtrado por deviceId — padrão do flash do B §1.2; holder
 * único `remember`, dialog não tem restrição dex). O botão FACE continua
 * navegando o dialog (só EIXOS são observados — igual PPSSPP `axis()` bypass,
 * ControlMappingScreen.cpp:585).
 *
 * Sliders ligados DIRETO à config em edição (deadzone, mode, anti-deadzone,
 * curve, max output); a LUT permanece na lista avançada (spec §1.3). O lado em
 * edição alterna Left/Right; os dois history views refletem o lado selecionado.
 */
@Composable
fun StickCalibrationSection(
    deviceId: Int,
    left: StickTransformConfig,
    right: StickTransformConfig,
    onLeftChange: (StickTransformConfig) -> Unit,
    onRightChange: (StickTransformConfig) -> Unit,
) {
    var editingRight by remember { mutableStateOf(false) }
    val config = if (editingRight) right else left
    val onConfigChange = if (editingRight) onRightChange else onLeftChange

    // Fonte de dados: eixos LÓGICOS do device (o pipeline já aplicou a deadzone do
    // perfil; a tab compara "como está hoje" vs "com a config proposta").
    val samples = remember { mutableStateOf<List<StickSample>>(emptyList()) }
    val currentX = remember { mutableStateOf(0f) }
    val currentY = remember { mutableStateOf(0f) }
    DisposableEffect(deviceId) {
        fun handle(event: GamepadInputEvent): Boolean {
            if (event.input.deviceId != deviceId) return false
            val motion = event.input as? InputEvent.AxisMotion ?: return false
            when (motion.axis) {
                GamepadAxis.LEFT_X -> currentX.value = motion.value
                GamepadAxis.LEFT_Y -> currentY.value = motion.value
                else -> return false
            }
            samples.value = (samples.value + StickSample(currentX.value, currentY.value)).takeLast(32)
            return false
        }
        val handler: (GamepadInputEvent) -> Boolean = ::handle
        PluviaApp.events.on<GamepadInputEvent, Boolean>(handler)
        onDispose { PluviaApp.events.off<GamepadInputEvent, Boolean>(handler) }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            JoystickHistoryView(
                mode = JoystickHistoryMode.RAW,
                config = config,
                samples = samples,
            )
            JoystickHistoryView(
                mode = JoystickHistoryMode.CALIBRATED,
                config = config,
                samples = samples,
            )
        }
        Text(
            text = stringResource(R.string.gamepad_calibration_raw_vs_calibrated),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )

        // Lado em edição (Left/Right).
        Row(modifier = Modifier.padding(top = 6.dp)) {
            CalibrationSideChip(
                text = stringResource(R.string.gamepad_stick_left_title),
                selected = !editingRight,
                onClick = { editingRight = false },
                modifier = Modifier.padding(end = 8.dp),
            )
            CalibrationSideChip(
                text = stringResource(R.string.gamepad_stick_right_title),
                selected = editingRight,
                onClick = { editingRight = true },
            )
        }

        CalibrationSlider(
            title = stringResource(R.string.gamepad_stick_deadzone_title),
            value = config.deadzone,
            range = 0f..0.5f,
            format = { String.format(Locale.US, "%.0f%%", it * 100f) },
            onValueChange = { onConfigChange(config.copy(deadzone = it)) },
        )
        CalibrationSlider(
            title = stringResource(R.string.gamepad_stick_anti_deadzone_title),
            value = config.antiDeadzone,
            range = 0f..0.5f,
            format = { String.format(Locale.US, "%.0f%%", it * 100f) },
            onValueChange = { onConfigChange(config.copy(antiDeadzone = it)) },
        )
        CalibrationSlider(
            title = stringResource(R.string.gamepad_stick_max_output_title),
            value = config.maxOutput,
            range = 0.1f..1f,
            format = { String.format(Locale.US, "%.0f%%", it * 100f) },
            onValueChange = { onConfigChange(config.copy(maxOutput = it)) },
        )

        // Modo e curva — segmented simples (mesmo vocabulário da lista avançada).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DeadzoneMode.values().forEach { mode ->
                CalibrationSideChip(
                    text = when (mode) {
                        DeadzoneMode.RADIAL -> stringResource(R.string.gamepad_deadzone_mode_radial)
                        DeadzoneMode.AXIAL -> stringResource(R.string.gamepad_deadzone_mode_axial)
                    },
                    selected = config.mode == mode,
                    onClick = { onConfigChange(config.copy(mode = mode)) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                ResponseCurve.LINEAR to stringResource(R.string.gamepad_curve_linear),
                ResponseCurve.EXPONENTIAL to stringResource(R.string.gamepad_curve_exponential),
                ResponseCurve.SCURVE to stringResource(R.string.gamepad_curve_scurve),
            ).forEach { (curve, label) ->
                CalibrationSideChip(
                    text = label,
                    selected = config.curve == curve,
                    onClick = { onConfigChange(config.copy(curve = curve)) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun CalibrationSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = format(value),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
        )
    }
}

@Composable
private fun CalibrationSideChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = modifier,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
