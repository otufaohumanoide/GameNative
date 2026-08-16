package app.gamenative.ui.screen.settings

import android.content.res.Configuration
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.gamepad.mapping.MappingDatabase
import app.gamenative.gamepad.remap.GamepadRemapDialog
import app.gamenative.ui.component.gamepadAdjustableRow
import app.gamenative.ui.component.gamepadSelectable
import app.gamenative.ui.theme.PluviaTheme
import java.util.Locale

/**
 * Seção de settings "Gamepad" (spec 2026-08-14-onda2-pos-implementacao, M2/M3): o gate
 * da camada universal, o swap OK/Cancel (equiv. ao `menu_swap_ok_cancel_buttons` do
 * RetroArch) e as deadzones globais — antes só existiam no DataStore, sem UI (L3).
 *
 * Linhas CUSTOM com o padrão de gamepad do repo (gamepadSelectable / A-lock nos sliders)
 * para a seção ser navegável por gamepad (DPAD move foco, A ativa, L/R ajusta travado).
 * O botão de remap (M3) abre o [GamepadRemapDialog] com o device ATIVO do hub e salva
 * via `saveDeviceProfile` (camada DEFAULT — escopo da Fase 5, sem remap no jogo).
 */
@Composable
fun SettingsGroupGamepad() {
    val isPreview = LocalInspectionMode.current
    val activeDevice = if (isPreview) {
        null
    } else {
        PluviaApp.gamepadHub.activeDevice.collectAsState().value
    }
    // U7 (spec 2026-08-14-gamepad-u7-battery): lista de controles com bateria e
    // capacidades (coletadas no hotplug — pull fora do hot path, V3).
    val connectedDevices = if (isPreview) {
        emptyMap<Int, app.gamenative.gamepad.GamepadDevice>()
    } else {
        PluviaApp.gamepadHub.connectedDevices.collectAsState().value
    }

    var universalEnabled by rememberSaveable {
        mutableStateOf(if (isPreview) false else PrefManager.gamepadUniversalEnabled)
    }
    var swapOkCancel by rememberSaveable {
        mutableStateOf(if (isPreview) false else PrefManager.gamepadSwapOkCancel)
    }
    var stickDeadzone by rememberSaveable {
        mutableStateOf(if (isPreview) 0.15f else PrefManager.gamepadStickDeadzone)
    }
    var menuStickDeadzone by rememberSaveable {
        mutableStateOf(if (isPreview) 0.45f else PrefManager.gamepadMenuStickDeadzone)
    }
    var showRemapDialog by rememberSaveable { mutableStateOf(false) }
    var touchpadMouseEnabled by rememberSaveable {
        mutableStateOf(if (isPreview) false else PrefManager.gamepadTouchpadMouseEnabled)
    }
    var touchpadSensitivity by rememberSaveable {
        mutableStateOf(if (isPreview) 1.0f else PrefManager.gamepadTouchpadSensitivity)
    }
    var rumbleEnabled by rememberSaveable {
        mutableStateOf(if (isPreview) true else PrefManager.gamepadRumbleEnabled)
    }
    // F2.3 (spec 2026-08-15-input-core-avancado): tick háptico de camada/radial.
    var layerTickEnabled by rememberSaveable {
        mutableStateOf(if (isPreview) true else PrefManager.gamepadLayerTickEnabled)
    }
    // Spec 2026-08-16-A §1.5: fallback de rumble no TELEFONE (default ON = original).
    var phoneRumbleFallback by rememberSaveable {
        mutableStateOf(if (isPreview) true else PrefManager.gamepadPhoneRumbleFallback)
    }
    // P3-4 (spec 2026-08-14-gamepad-upgrades-pendencias): refresh PULL da bateria ao
    // ABRIR a seção (a SettingsScreen é um destino de navegação — compõe ao abrir e
    // descarta ao sair). Fora do hot path, sem polling; o nível do hotplug ficava
    // stale durante uma partida longa.
    if (!isPreview) {
        LaunchedEffect(Unit) {
            val hub = PluviaApp.gamepadHub
            hub.connectedDevices.value.keys.forEach(hub::refreshBattery)
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // U7: controles conectados (nome, % de bateria API 31+, badges de capacidade).
        if (connectedDevices.isNotEmpty()) {
            Text(
                text = stringResource(R.string.gamepad_devices_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            connectedDevices.values.forEach { device ->
                // Spec 2026-08-16-C §1.1: o cartão de diagnóstico substitui o
                // ConnectedDeviceRow (header colapsado idêntico) e absorve o teste de
                // vibração da fase A dentro da seção expandida. `key(deviceId)` fixa a
                // identidade do card por device (hotplug não reusa estado entre ids).
                key(device.deviceId) {
                    DeviceDiagnosticsCard(
                        device = device,
                        isActive = activeDevice?.deviceId == device.deviceId,
                    )
                }
            }
            GamepadSettingsDivider()
        } else if (!isPreview) {
            Text(
                text = stringResource(R.string.gamepad_devices_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            GamepadSettingsDivider()
        }

        GamepadSettingsSwitchRow(
            title = stringResource(R.string.gamepad_settings_universal_title),
            subtitle = stringResource(R.string.gamepad_settings_universal_subtitle),
            checked = universalEnabled,
            onCheckedChange = {
                universalEnabled = it
                PrefManager.gamepadUniversalEnabled = it
            },
        )
        GamepadSettingsDivider()
        // K1 (spec 2026-08-16-K1, §1.4): pipeline do gamepad virtual de toque —
        // o overlay de toque vira um device no hub (camadas/expressões/radial/
        // turbo por jogo). Default OFF — byte-identical. Requer o universal ON
        // para agir (o ControlElement só roteia com as DUAS flags).
        var virtualPipelineEnabled by rememberSaveable {
            mutableStateOf(if (isPreview) false else PrefManager.virtualGamepadPipeline)
        }
        GamepadSettingsSwitchRow(
            title = stringResource(R.string.gamepad_settings_virtual_pipeline_title),
            subtitle = stringResource(R.string.gamepad_settings_virtual_pipeline_subtitle),
            checked = virtualPipelineEnabled,
            onCheckedChange = {
                virtualPipelineEnabled = it
                PrefManager.virtualGamepadPipeline = it
                if (!it) {
                    // Desligar remove o virtual do hub na hora (se registrado).
                    app.gamenative.gamepad.virtual.TouchGamepadSource.unregister()
                }
            },
        )
        GamepadSettingsDivider()
        GamepadSettingsSwitchRow(
            title = stringResource(R.string.gamepad_swap_ok_cancel),
            subtitle = stringResource(R.string.gamepad_swap_ok_cancel_subtitle),
            checked = swapOkCancel,
            onCheckedChange = {
                swapOkCancel = it
                PrefManager.gamepadSwapOkCancel = it
            },
        )
        GamepadSettingsDivider()
        GamepadSettingsSliderRow(
            title = stringResource(R.string.gamepad_deadzone_stick_title),
            subtitle = stringResource(R.string.gamepad_deadzone_subtitle),
            value = stickDeadzone,
            onValueChange = {
                stickDeadzone = it
                PrefManager.gamepadStickDeadzone = it
            },
        )
        GamepadSettingsDivider()
        GamepadSettingsSliderRow(
            title = stringResource(R.string.gamepad_deadzone_menu_stick_title),
            subtitle = stringResource(R.string.gamepad_deadzone_subtitle),
            value = menuStickDeadzone,
            onValueChange = {
                menuStickDeadzone = it
                PrefManager.gamepadMenuStickDeadzone = it
            },
        )
        GamepadSettingsDivider()

        // U2 (spec 2026-08-14-gamepad-u2-touchpad-mouse, §1.5): touchpad do controle →
        // mouse (opt-in; default OFF — byte-identical com OFF). O touchpad continua
        // consumido pelo gate de ghost input; o forwarder lê no mesmo ponto.
        GamepadSettingsSwitchRow(
            title = stringResource(R.string.gamepad_touchpad_mouse_title),
            subtitle = stringResource(R.string.gamepad_touchpad_mouse_subtitle),
            checked = touchpadMouseEnabled,
            onCheckedChange = {
                touchpadMouseEnabled = it
                PrefManager.gamepadTouchpadMouseEnabled = it
            },
        )
        GamepadSettingsDivider()
        GamepadSettingsSliderRow(
            title = stringResource(R.string.gamepad_touchpad_sensitivity_title),
            subtitle = stringResource(R.string.gamepad_touchpad_sensitivity_subtitle),
            value = touchpadSensitivity,
            onValueChange = {
                touchpadSensitivity = it
                PrefManager.gamepadTouchpadSensitivity = it
            },
        )
        GamepadSettingsDivider()

        // U5 (spec 2026-08-14-gamepad-u5-rumble): rumble do menu por device.
        GamepadSettingsSwitchRow(
            title = stringResource(R.string.gamepad_rumble_title),
            subtitle = stringResource(R.string.gamepad_rumble_subtitle),
            checked = rumbleEnabled,
            onCheckedChange = {
                rumbleEnabled = it
                PrefManager.gamepadRumbleEnabled = it
            },
        )
        GamepadSettingsDivider()

        // Spec 2026-08-16-A §1.5: fallback no telefone quando o controle não expõe
        // vibrator (default ON — comportamento do GameNative original). O master
        // `gamepadRumbleEnabled` continua guardando TUDO (gate no GamepadHaptics).
        GamepadSettingsSwitchRow(
            title = stringResource(R.string.gamepad_phone_rumble_fallback_title),
            subtitle = stringResource(R.string.gamepad_phone_rumble_fallback_subtitle),
            checked = phoneRumbleFallback,
            onCheckedChange = {
                phoneRumbleFallback = it
                PrefManager.gamepadPhoneRumbleFallback = it
            },
        )
        GamepadSettingsDivider()

        // F2.3 (spec 2026-08-15-input-core-avancado): tick háptico na ativação de
        // camada (U3) e no setor do radial menu. `gamepadRumbleEnabled` guarda tudo
        // (rumble OFF ⇒ tick mudo também — o gate vive no GamepadHaptics.tickDevice).
        GamepadSettingsSwitchRow(
            title = stringResource(R.string.gamepad_layer_tick_title),
            subtitle = stringResource(R.string.gamepad_layer_tick_subtitle),
            checked = layerTickEnabled,
            onCheckedChange = {
                layerTickEnabled = it
                PrefManager.gamepadLayerTickEnabled = it
            },
        )
        GamepadSettingsDivider()

        // M3: remap alcançável pela UI. Sem device conectado → linha desabilitada com hint
        // (o diálogo precisa do device ativo do hub para capturar bindings físicos).
        val remapEnabled = activeDevice != null
        GamepadSettingsButtonRow(
            title = stringResource(R.string.gamepad_settings_remap_title),
            subtitle = if (remapEnabled) {
                stringResource(R.string.gamepad_settings_remap_subtitle, activeDevice!!.name)
            } else {
                stringResource(R.string.gamepad_settings_remap_no_device)
            },
            enabled = remapEnabled,
            onClick = { showRemapDialog = true },
        )
    }

    if (showRemapDialog && activeDevice != null) {
        val device = activeDevice
        val hub = PluviaApp.gamepadHub
        val mapping = MappingDatabase.mappingFor(device.vendorId, device.productId)
            ?: MappingDatabase.defaultAndroidMapping(device.faceStyle)
        GamepadRemapDialog(
            device = device,
            mapping = mapping,
            profile = hub.profileFor(device.deviceId, null),
            onSave = { saved ->
                hub.saveDeviceProfile(device.deviceId, saved)
                showRemapDialog = false
            },
            onDismiss = { showRemapDialog = false },
        )
    }
}

@Composable
private fun GamepadSettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
    )
}

/**
 * Linha de switch navegável por gamepad: `gamepadSelectable` (A/DPAD_CENTER/ENTER ativa
 * quando focada). O Switch em si fica fora do foco (não disputa com a navegação) mas
 * continua tocável — mesmo padrão do SettingsSwitchWithAction do repo.
 */
@Composable
private fun GamepadSettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .gamepadSelectable(
                selected = checked,
                onClick = { onCheckedChange(!checked) },
                shape = RoundedCornerShape(12.dp),
                interactionSource = interactionSource,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.focusProperties { canFocus = false },
        )
    }
}

/**
 * Slider de deadzone com A-lock (padrão G4 do repo, spec 2026-08-09): a linha é o alvo
 * de foco do gamepad; A/DPAD_CENTER trava, L/R ajusta em passos de 5% da faixa, B
 * destrava e o lock reseta quando o foco sai. O Slider fica fora do foco para não
 * disputar com o lock (manuseio nativo de setas do Material).
 */
@Composable
private fun GamepadSettingsSliderRow(
    title: String,
    subtitle: String,
    value: Float,
    onValueChange: (Float) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    var isLocked by remember { mutableStateOf(false) }
    val valueRange = 0.05f..0.60f
    val adjustStep = (valueRange.endInclusive - valueRange.start) / 20f

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .gamepadAdjustableRow(
                    locked = isLocked,
                    onLockChange = { isLocked = it },
                    onAdjust = { delta ->
                        onValueChange(
                            (value + delta * adjustStep).coerceIn(valueRange.start, valueRange.endInclusive),
                        )
                    },
                    shape = RoundedCornerShape(10.dp),
                    interactionSource = interactionSource,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                modifier = Modifier
                    .weight(1f)
                    .focusProperties { canFocus = false },
            )
            Text(
                text = String.format(Locale.US, "%.2f", value),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (isLocked) {
                Text(
                    text = stringResource(R.string.quick_menu_locked_indicator),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** Linha de ação (remap) — gamepadSelectable com estado desabilitado + hint. */
@Composable
private fun GamepadSettingsButtonRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .gamepadSelectable(
                selected = false,
                onClick = onClick,
                enabled = enabled,
                shape = RoundedCornerShape(12.dp),
                interactionSource = interactionSource,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun Preview_SettingsGroupGamepad() {
    PluviaTheme {
        SettingsGroupGamepad()
    }
}
