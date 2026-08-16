package app.gamenative.ui.screen.settings

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gamenative.PluviaApp
import app.gamenative.R
import app.gamenative.events.GamepadInputEvent
import app.gamenative.gamepad.GamepadDevice
import app.gamenative.gamepad.GyroPreview
import app.gamenative.gamepad.TouchpadPreview
import app.gamenative.gamepad.profiles.GamepadProfile
import app.gamenative.gamepad.mapping.AutoconfigCheck
import app.gamenative.gamepad.mapping.AutoconfigSaveResult
import app.gamenative.gamepad.mapping.AutoconfigValidation
import app.gamenative.gamepad.mapping.ControllerVisualLayout
import app.gamenative.gamepad.processing.RumblePhoneCurve
import app.gamenative.ui.component.GamepadHaptics
import app.gamenative.ui.component.SdlMappingExportDialog
import app.gamenative.ui.component.SdlMappingImportDialog
import app.gamenative.ui.component.gamepadSelectable
import app.gamenative.ui.component.remap.ControllerVisualView
import app.gamenative.ui.component.remap.VisualControlState
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/** Duração do flash do viewer (mesmo PPSSPP ~600 ms da fase B). */
private const val FLASH_DURATION_MS = 600L

/**
 * Rótulo amigável da ORIGEM do mapping (spec 2026-08-16-K3, §1.5) para o card —
 * os valores técnicos do enum viram palavras que qualquer usuário entende
 * ("recognized as: your saved layout" em vez de "Mapping: USER").
 */
@Composable
private fun mappingSourceLabel(source: app.gamenative.gamepad.MappingSource, hasQuirk: Boolean): String {
    val base = when (source) {
        app.gamenative.gamepad.MappingSource.USER ->
            stringResource(R.string.gamepad_diag_mapping_user)
        app.gamenative.gamepad.MappingSource.MODEL ->
            stringResource(R.string.gamepad_diag_mapping_model)
        app.gamenative.gamepad.MappingSource.SDL_DB ->
            stringResource(R.string.gamepad_diag_mapping_community)
        app.gamenative.gamepad.MappingSource.CAPABILITIES ->
            stringResource(R.string.gamepad_diag_mapping_capabilities)
        app.gamenative.gamepad.MappingSource.DEFAULT ->
            stringResource(R.string.gamepad_diag_mapping_default)
    }
    return if (hasQuirk) {
        stringResource(R.string.gamepad_diag_mapping_with_fix, base)
    } else {
        base
    }
}

/**
 * Cartão de diagnóstico por device (spec 2026-08-16-C-device-card-input-viewer, §1.1):
 * substitui o ConnectedDeviceRow na composição do [SettingsGroupGamepad] — header
 * IDÊNTICO ao row antigo quando recolhido (nome/bateria/badges GYRO/TOUCHPAD,
 * byte-identical). Expande (só com device ATIVO) em:
 * - Input viewer: [ControllerVisualView] com o faceStyle do device (reuso integral da
 *   fase B) e flash ao vivo por [GamepadInputEvent] filtrado por deviceId;
 * - Readouts mono ~10 Hz: gyro yaw/pitch rad/s (só com [GamepadDevice.hasGyro]) via
 *   `GamepadHub.gyroPreview`; touchpad x/y normalizados (só com
 *   [GamepadDevice.hasTouchpad]) via `GamepadTouchpadForwarder.touchpadPreview`;
 * - Botões de teste: "Testar vibração" (fase A — `GamepadHaptics.rumbleDevice` +
 *   `rumbleTargetFor`), "Recentrar giroscópio" (só com gyro — `GamepadHub.recenterGyro`)
 *   e "Todos os botões" (só instruções + o viewer acescendo — sem lógica extra).
 *
 * Os hooks de preview ligam/desligam no [DisposableEffect] enquanto o card está
 * expandido/visível (limpeza garantida no dispose) — OFF ⇒ caminho byte-identical.
 */
@Composable
fun DeviceDiagnosticsCard(
    device: GamepadDevice,
    isActive: Boolean,
) {
    var expanded by rememberSaveable(device.deviceId) { mutableStateOf(false) }
    // Spec §1.1: card expandido SÓ com device ativo — perdeu a ativação, recolhe.
    LaunchedEffect(isActive) {
        if (!isActive) expanded = false
    }

    // Spec §1.2/§1.3: hooks de preview ligados junto com o card expandido; o dispose
    // SEMPRE desliga (limpeza mesmo em collapse/dispose inesperado).
    DisposableEffect(expanded) {
        if (expanded) {
            if (device.hasGyro) PluviaApp.gamepadHub.gyroPreviewEnabled = true
            if (device.hasTouchpad) PluviaApp.gamepadTouchpad.previewEnabled = true
        }
        onDispose {
            if (device.hasGyro) PluviaApp.gamepadHub.gyroPreviewEnabled = false
            if (device.hasTouchpad) PluviaApp.gamepadTouchpad.previewEnabled = false
        }
    }

    // Spec 2026-08-16-A §1.4 (reuso): resultado do teste de vibração deste device;
    // auto-limpa após ~3 s (LaunchedEffect keyed no resultado — cliques reiniciam a
    // janela, mesmo comportamento do row antigo do SettingsGroupGamepad).
    var rumbleResult by rememberSaveable(device.deviceId) {
        mutableStateOf<RumblePhoneCurve.RumbleTarget?>(null)
    }
    if (rumbleResult != null) {
        LaunchedEffect(rumbleResult) {
            delay(3000)
            rumbleResult = null
        }
    }

    // K5 (spec 2026-08-16-K5, §1.3): estado dos diálogos do autoconfig — motivo da
    // recusa (1.3.2) e confirmação de sobrescrita (1.3.3). O mapping em si NUNCA é
    // re-derivado no clique: a validação opera sobre o base capturado no addDevice.
    var autoconfigError by rememberSaveable(device.deviceId) {
        mutableStateOf<AutoconfigValidation.Reason?>(null)
    }
    var showOverwriteConfirm by rememberSaveable(device.deviceId) { mutableStateOf(false) }
    // K6 (spec 2026-08-16-K6): estado dos diálogos de import/export SDL + status
    // transitório do import (auto-limpa ~3 s, padrão do rumbleResult).
    var showSdlExport by rememberSaveable(device.deviceId) { mutableStateOf(false) }
    var showSdlImport by rememberSaveable(device.deviceId) { mutableStateOf(false) }
    var sdlStatus by rememberSaveable(device.deviceId) { mutableStateOf<String?>(null) }
    if (sdlStatus != null) {
        LaunchedEffect(sdlStatus) {
            delay(3000)
            sdlStatus = null
        }
    }

    val headerInteraction = remember { MutableInteractionSource() }
    Column(modifier = Modifier.fillMaxWidth()) {
        // ── Header (toggle de colapso) — conteúdo byte-identical ao ConnectedDeviceRow.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .gamepadSelectable(
                    selected = false,
                    onClick = { if (isActive) expanded = !expanded },
                    shape = RoundedCornerShape(12.dp),
                    interactionSource = headerInteraction,
                )
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = device.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                color = if (isActive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f),
            )
            device.batteryPercent?.let { battery ->
                Text(
                    text = stringResource(R.string.gamepad_battery_format, battery),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (device.hasGyro) {
                DiagCapabilityBadge(stringResource(R.string.gamepad_cap_gyro))
            }
            if (device.hasTouchpad) {
                DiagCapabilityBadge(stringResource(R.string.gamepad_cap_touchpad))
            }
        }

        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            ) {
                // ── Input viewer (fase B, reuso integral) com flash ao vivo filtrado
                // por deviceId — handler registrado UMA vez lê o deviceId no momento
                // do evento (rememberUpdatedState, lição C1); retorna false = observador.
                val hotspots = remember(device.deviceId) {
                    ControllerVisualLayout.layoutFor(device.faceStyle)
                }
                val flashTimes = remember(device.deviceId) { mutableStateOf<Map<String, Long>>(emptyMap()) }
                val flashSet = remember(device.deviceId) { mutableStateOf<Set<String>>(emptySet()) }
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
                // Expiração do flash (~600 ms) — remove dos holders; o decaimento
                // visual (alpha) é do ControllerVisualView (deriva os timestamps).
                LaunchedEffect(Unit) {
                    while (true) {
                        if (flashTimes.value.isNotEmpty()) {
                            val now = SystemClock.uptimeMillis()
                            val pruned = flashTimes.value.filterValues { now - it < FLASH_DURATION_MS }
                            if (pruned.size != flashTimes.value.size) {
                                flashTimes.value = pruned
                                flashSet.value = pruned.keys
                            }
                        }
                        delay(50)
                    }
                }
                ControllerVisualView(
                    faceStyle = device.faceStyle,
                    hotspots = hotspots,
                    // Diagnóstico, não remap: todo controle AUTO (o viewer desenha os
                    // badges neutros) e hotspots sem ação de toque.
                    stateOf = { VisualControlState.AUTO },
                    flash = flashSet,
                    onHotspotTap = {},
                    capturingControl = null,
                    onCancelCapture = {},
                    onRestoreControl = {},
                )

                // K3 (spec 2026-08-16-K3, §1.5): origem (tier) do mapping efetivo —
                // USER / MODEL / SDL_DB / CAPABILITIES / DEFAULT. null = não
                // resolvido → linha escondida (byte-identical). K4 (spec
                // 2026-08-16-K4, §1.4): com quirk ativo o rótulo ganha o sufixo
                // "· auto fix applied" (ex.: "community database · auto fix applied").
                device.mappingSource?.let { source ->
                    Text(
                        text = stringResource(
                            R.string.gamepad_diag_mapping_source,
                            mappingSourceLabel(source, device.quirkName != null),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                // K4 (spec 2026-08-16-K4, §1.4): label do quirk ativo no card do
                // device — nome do quirk (ex.: "DS4 non-standard (RX/RY triggers)"),
                // agora sob o rótulo amigável "Automatic fix: …".
                device.quirkName?.let { quirk ->
                    Text(
                        text = stringResource(R.string.gamepad_diag_quirk, quirk),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                // ── Readouts ao vivo (mono ~10 Hz — último valor do device coletado
                // num laço de 100 ms; o StateFlow guarda a última amostra GLOBAL e o
                // card mantém o último valor DESTE device). Nada é capturado de
                // composição antiga: o laço lê o StateFlow no momento da amostragem.
                var gyroPreview by remember(device.deviceId) { mutableStateOf<GyroPreview?>(null) }
                var touchpadPreview by remember(device.deviceId) { mutableStateOf<TouchpadPreview?>(null) }
                LaunchedEffect(Unit) {
                    while (true) {
                        PluviaApp.gamepadHub.gyroPreview.value?.let { preview ->
                            if (preview.deviceId == device.deviceId) gyroPreview = preview
                        }
                        PluviaApp.gamepadTouchpad.touchpadPreview.value?.let { preview ->
                            if (preview.deviceId == device.deviceId) touchpadPreview = preview
                        }
                        delay(100)
                    }
                }
                if (device.hasGyro) {
                    Text(
                        text = stringResource(
                            R.string.gamepad_diag_gyro_readout,
                            gyroPreview?.let { String.format(Locale.US, "%.2f", it.yawRadS) } ?: "—",
                            gyroPreview?.let { String.format(Locale.US, "%.2f", it.pitchRadS) } ?: "—",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (device.hasTouchpad) {
                    val base = stringResource(
                        R.string.gamepad_diag_touchpad_readout,
                        touchpadPreview?.let { String.format(Locale.US, "%.2f", it.x) } ?: "—",
                        touchpadPreview?.let { String.format(Locale.US, "%.2f", it.y) } ?: "—",
                    )
                    Text(
                        text = if (touchpadPreview?.down == true) {
                            // Separador no código: aapt corta espaços nas pontas da
                            // string resource (o sufixo vai colado sem o " · ").
                            base + " · " + stringResource(R.string.gamepad_diag_touchpad_touching)
                        } else {
                            base
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                // ── Botões de teste ──
                DiagButtonRow(
                    title = stringResource(R.string.gamepad_rumble_test_title),
                    subtitle = when (rumbleResult) {
                        RumblePhoneCurve.RumbleTarget.CONTROLLER ->
                            stringResource(R.string.gamepad_rumble_test_result_controller)
                        RumblePhoneCurve.RumbleTarget.PHONE ->
                            stringResource(R.string.gamepad_rumble_test_result_phone)
                        RumblePhoneCurve.RumbleTarget.NONE ->
                            stringResource(R.string.gamepad_rumble_test_result_none)
                        null -> stringResource(R.string.gamepad_rumble_test_hint)
                    },
                    onClick = {
                        val vibrated = GamepadHaptics.rumbleDevice(device.deviceId, 0.6f, 0.6f, 300L)
                        val target = GamepadHaptics.rumbleTargetFor(device.deviceId)
                        // Sem vibração de fato (master OFF, sem vibrator, sem fallback)
                        // ⇒ "nada" — o resultado é o que ACONTECEU, não o alvo teórico.
                        rumbleResult = if (vibrated) target else RumblePhoneCurve.RumbleTarget.NONE
                    },
                )
                if (device.hasGyro) {
                    DiagButtonRow(
                        title = stringResource(R.string.gamepad_diag_recenter_gyro_title),
                        subtitle = stringResource(R.string.gamepad_diag_recenter_gyro_hint),
                        onClick = { PluviaApp.gamepadHub.recenterGyro(device.deviceId) },
                    )
                    // G6 (spec 2026-08-16-G-gyro-v2): θ = atan2 do accel da última
                    // amostra processada — salva o gyroGripAngleDeg no perfil do
                    // DEVICE (a pegada é física, não do jogo). Sem amostra/accel
                    // ainda = no-op.
                    DiagButtonRow(
                        title = stringResource(R.string.gamepad_diag_calibrate_grip_title),
                        subtitle = stringResource(R.string.gamepad_diag_calibrate_grip_hint),
                        onClick = { PluviaApp.gamepadHub.calibrateGrip(device.deviceId) },
                    )
                }
                // K5 (spec 2026-08-16-K5, §1.3): "Salvar perfil deste controle" —
                // grava o autoconfig (mapping BASE pré-quirk capturado no addDevice)
                // e re-resolve ao vivo (tier USER). O clique NÃO re-deriva o mapping:
                // validação/confirmação operam sobre o estado resolvido no hotplug
                // (padrão RetroArch — o autoconf grava a conexão, não o clique).
                val savedAutoconfig = PluviaApp.gamepadHub.savedAutoconfig(device.mappingKey)
                DiagButtonRow(
                    title = stringResource(R.string.gamepad_autoconfig_save_title),
                    subtitle = if (savedAutoconfig != null) {
                        stringResource(
                            R.string.gamepad_autoconfig_saved_format,
                            savedAutoconfig.deviceName,
                            formatAutoconfigDate(savedAutoconfig.createdAtMs),
                        )
                    } else {
                        stringResource(R.string.gamepad_autoconfig_save_hint)
                    },
                    onClick = {
                        when (val check = PluviaApp.gamepadHub.autoconfigCheck(device.deviceId)) {
                            is AutoconfigCheck.Invalid -> autoconfigError = check.reason
                            is AutoconfigCheck.Valid -> {
                                if (PluviaApp.gamepadHub.savedAutoconfig(device.mappingKey) != null) {
                                    showOverwriteConfirm = true
                                } else {
                                    PluviaApp.gamepadHub.saveAutoconfig(device.deviceId)
                                }
                            }
                        }
                    },
                )
                if (savedAutoconfig != null) {
                    DiagButtonRow(
                        title = stringResource(R.string.gamepad_autoconfig_restore_title),
                        subtitle = stringResource(R.string.gamepad_autoconfig_restore_hint),
                        onClick = { PluviaApp.gamepadHub.deleteAutoconfig(device.mappingKey) },
                    )
                }
                // K2 (spec 2026-08-16-K2, §1.4): modo mouse por stick — toggle
                // rápido no card (o chord START 750 ms fica disponível quando ON).
                // Edita o perfil BRUTO do device (nunca congela o merge).
                val deviceProfile = PluviaApp.gamepadHub.deviceProfileFor(device.deviceId)
                val mouseModeOn = PluviaApp.gamepadHub.profileFor(device.deviceId, null)
                    .mouseModeEnabled == true
                DiagButtonRow(
                    title = stringResource(R.string.gamepad_mouse_mode_card_title),
                    subtitle = if (mouseModeOn) {
                        stringResource(R.string.gamepad_mouse_mode_card_on)
                    } else {
                        stringResource(R.string.gamepad_mouse_mode_card_off)
                    },
                    onClick = {
                        val base = deviceProfile ?: GamepadProfile()
                        PluviaApp.gamepadHub.saveDeviceProfile(
                            device.deviceId,
                            base.copy(mouseModeEnabled = !mouseModeOn),
                        )
                    },
                )
                // K6 (spec 2026-08-16-K6, §1.2/§1.3): import/export no formato SDL —
                // o export serializa o mapping BASE (pré-quirk — quirk é correção de
                // transporte, não identidade do controle, racional do K5); o diff do
                // import usa o mapping EFETIVO (o que o controle usa AGORA).
                DiagButtonRow(
                    title = stringResource(R.string.gamepad_sdl_export_title),
                    subtitle = stringResource(R.string.gamepad_sdl_export_hint),
                    onClick = { showSdlExport = true },
                )
                DiagButtonRow(
                    title = stringResource(R.string.gamepad_sdl_import_title),
                    subtitle = stringResource(R.string.gamepad_sdl_import_hint),
                    onClick = { showSdlImport = true },
                )
                sdlStatus?.let { status ->
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                // "Todos os botões" — só instruções + o viewer acescendo (sem lógica).
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(
                        text = stringResource(R.string.gamepad_diag_all_buttons_title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.gamepad_diag_all_buttons_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }

    // K5 §1.3.2: recusa SEM salvar — diálogo de erro com o motivo exato.
    autoconfigError?.let { reason ->
        AlertDialog(
            onDismissRequest = { autoconfigError = null },
            title = { Text(stringResource(R.string.gamepad_autoconfig_error_title)) },
            text = {
                Text(
                    stringResource(
                        when (reason) {
                            AutoconfigValidation.Reason.MISSING_CONFIRM ->
                                R.string.gamepad_autoconfig_error_missing_confirm
                            AutoconfigValidation.Reason.MISSING_DIRECTION ->
                                R.string.gamepad_autoconfig_error_missing_direction
                        },
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { autoconfigError = null }) {
                    Text(stringResource(R.string.gamepad_autoconfig_dismiss))
                }
            },
        )
    }
    // K5 §1.3.3: sobrescreve autoconfig existente? Mostra nome + data do atual.
    if (showOverwriteConfirm) {
        val existing = PluviaApp.gamepadHub.savedAutoconfig(device.mappingKey)
        AlertDialog(
            onDismissRequest = { showOverwriteConfirm = false },
            title = { Text(stringResource(R.string.gamepad_autoconfig_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.gamepad_autoconfig_confirm_message,
                        existing?.deviceName ?: "",
                        existing?.let { formatAutoconfigDate(it.createdAtMs) } ?: "",
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showOverwriteConfirm = false
                        PluviaApp.gamepadHub.saveAutoconfig(device.deviceId)
                    },
                ) {
                    Text(stringResource(R.string.gamepad_autoconfig_overwrite))
                }
            },
            dismissButton = {
                TextButton(onClick = { showOverwriteConfirm = false }) {
                    Text(stringResource(R.string.gamepad_autoconfig_cancel))
                }
            },
        )
    }
    // K6 §1.3: export no formato SDL — mapping BASE (pré-quirk) do hub.
    if (showSdlExport) {
        val base = PluviaApp.gamepadHub.baseMappingFor(device.deviceId)
        if (base == null) {
            showSdlExport = false
        } else {
            SdlMappingExportDialog(
                device = device,
                mapping = base,
                onDismiss = { showSdlExport = false },
            )
        }
    }
    // K6 §1.2: import no formato SDL — diff contra o mapping EFETIVO; o resultado
    // inválido (1.3.2 do K5) reaproveita o diálogo de erro do autoconfig.
    if (showSdlImport) {
        val current = PluviaApp.gamepadHub.effectiveMappingFor(device.deviceId)
        if (current == null) {
            showSdlImport = false
        } else {
            // Capturada FORA do lambda de import (stringResource é composable).
            val importedLabel = stringResource(R.string.gamepad_sdl_imported)
            SdlMappingImportDialog(
                device = device,
                current = current,
                onImport = { mapping ->
                    when (val result = PluviaApp.gamepadHub.importAutoconfig(device.deviceId, mapping)) {
                        is AutoconfigSaveResult.Invalid -> {
                            autoconfigError = result.reason
                            AutoconfigSaveResult.Invalid(result.reason)
                        }
                        is AutoconfigSaveResult.Saved -> {
                            sdlStatus = importedLabel
                            result
                        }
                        AutoconfigSaveResult.NoDevice -> AutoconfigSaveResult.NoDevice
                    }
                },
                onDismiss = { showSdlImport = false },
            )
        }
    }
}

/** K5 §1.3.3: data/hora do autoconfig no locale do device — APENAS display (nunca chave). */
private fun formatAutoconfigDate(createdAtMs: Long): String =
    DateFormat.getDateTimeInstance().format(Date(createdAtMs))

/** Badge de capacidade (mesmo visual do CapabilityBadge antigo do SettingsGroupGamepad). */
@Composable
private fun DiagCapabilityBadge(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * Linha de ação do card — mesmo padrão de navegação do GamepadSettingsButtonRow
 * (`gamepadSelectable`, A/DPAD_CENTER ativa quando focada).
 */
@Composable
private fun DiagButtonRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .gamepadSelectable(
                selected = false,
                onClick = onClick,
                shape = RoundedCornerShape(12.dp),
                interactionSource = interactionSource,
            )
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
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
    }
}
