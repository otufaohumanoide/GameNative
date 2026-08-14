package app.gamenative.ui.component.radial

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
import androidx.compose.material.icons.filled.Save
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import app.gamenative.gamepad.radial.RadialMacroKey
import app.gamenative.gamepad.radial.RadialMenuConfig
import app.gamenative.gamepad.radial.RadialSector
import app.gamenative.ui.component.GamepadFocusScope
import app.gamenative.ui.component.gamepadSelectable

/**
 * Editor de setores/macros do Radial Menu (F3.1 do spec 2026-08-15-input-core-
 * avancado) — janela de diálogo própria (padrão GamepadFocusScope do remap; regra do
 * AGENTS.md: view-focus em janela separada). Aberto pelo QuickMenu.
 *
 * Edita: gatilho (camada do perfil do device — o BINDING do botão continua no editor
 * de camadas existente), número de setores (2..8) e, por setor, o rótulo e a sequência
 * de teclas (captura via bus cru; timing default hold 60 ms / gap 40 ms — sliders por
 * tecla são follow-up registrado no spec).
 */
@Composable
fun RadialMenuEditorDialog(
    deviceId: Int,
    profileLayers: List<String>,
    config: RadialMenuConfig,
    onSave: (RadialMenuConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var triggerLayer by remember { mutableStateOf(config.triggerLayer) }
    var sectors by remember {
        mutableStateOf(config.sectors.ifEmpty { List(8) { RadialSector("", emptyList(), it) } })
    }
    var captureSector by remember { mutableIntStateOf(-1) }
    var status by remember { mutableStateOf<String?>(null) }

    fun appendKey(sectorIndex: Int, keyCode: Int) {
        val sector = sectors[sectorIndex]
        sectors = sectors.toMutableList().also {
            it[sectorIndex] = sector.copy(keys = sector.keys + RadialMacroKey(keyCode))
        }
    }

    fun clearKeys(sectorIndex: Int) {
        val sector = sectors[sectorIndex]
        sectors = sectors.toMutableList().also {
            it[sectorIndex] = sector.copy(keys = emptyList())
        }
    }

    fun setLabel(sectorIndex: Int, label: String) {
        val sector = sectors[sectorIndex]
        sectors = sectors.toMutableList().also {
            it[sectorIndex] = sector.copy(label = label)
        }
    }

    fun resizeSectors(count: Int) {
        val current = sectors
        sectors = (0 until count).map { i ->
            current.getOrNull(i) ?: RadialSector("", emptyList(), i)
        }
        if (captureSector >= count) captureSector = -1
    }

    // Captura de teclas via bus CRU (mesmo padrão do GamepadRemapDialog).
    DisposableEffect(captureSector) {
        if (captureSector < 0) return@DisposableEffect onDispose {}
        fun handleKey(androidEvent: AndroidEvent.KeyEvent): Boolean {
            val ev = androidEvent.event ?: return false
            if (ev.deviceId != deviceId) return false
            if (ev.action == android.view.KeyEvent.ACTION_DOWN && ev.repeatCount == 0) {
                when (ev.keyCode) {
                    android.view.KeyEvent.KEYCODE_BACK, android.view.KeyEvent.KEYCODE_ESCAPE -> {
                        captureSector = -1
                        status = null
                    }
                    else -> {
                        appendKey(captureSector, ev.keyCode)
                    }
                }
                return true
            }
            return false
        }
        val keyHandler: (AndroidEvent.KeyEvent) -> Boolean = ::handleKey
        PluviaApp.events.on<AndroidEvent.KeyEvent, Boolean>(keyHandler)
        onDispose {
            PluviaApp.events.off<AndroidEvent.KeyEvent, Boolean>(keyHandler)
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
        GamepadFocusScope(
            enabled = captureSector < 0,
            backAction = onDismiss,
            initialFocusRequester = initialFocus,
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.radial_menu_editor_title),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Filled.Close, contentDescription = null)
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

                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        // Gatilho: camada do perfil do device (binding no editor de camadas).
                        Text(
                            text = stringResource(R.string.radial_menu_trigger_layer_title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TriggerChip(
                                label = stringResource(R.string.radial_menu_trigger_none),
                                selected = triggerLayer == null,
                                onClick = { triggerLayer = null },
                            )
                            profileLayers.forEach { layer ->
                                TriggerChip(
                                    label = layer,
                                    selected = triggerLayer == layer,
                                    onClick = { triggerLayer = layer },
                                )
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                        // Número de setores.
                        Text(
                            text = stringResource(R.string.radial_menu_sector_count_title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            for (count in RadialMenuConfig.MIN_SECTORS..RadialMenuConfig.MAX_SECTORS) {
                                TriggerChip(
                                    label = count.toString(),
                                    selected = sectors.size == count,
                                    onClick = { resizeSectors(count) },
                                )
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                        // Setores.
                        sectors.forEachIndexed { index, sector ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(R.string.radial_menu_sector_label, index + 1),
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                                OutlinedTextField(
                                    value = sector.label,
                                    onValueChange = { setLabel(index, it) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = if (sector.keys.isEmpty()) {
                                        stringResource(R.string.radial_menu_macro_empty)
                                    } else {
                                        sector.keys.joinToString(" → ") { keyName(it.keyCode) }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = {
                                    captureSector = if (captureSector == index) -1 else index
                                }) {
                                    Text(
                                        stringResource(
                                            if (captureSector == index) {
                                                R.string.radial_menu_capture_running
                                            } else {
                                                R.string.radial_menu_capture
                                            },
                                        ),
                                    )
                                }
                                TextButton(onClick = { clearKeys(index) }) {
                                    Text(stringResource(R.string.radial_menu_clear_macro))
                                }
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
                                RadialMenuConfig(
                                    triggerLayer = triggerLayer,
                                    sectors = sectors.map { sector ->
                                        sector.copy(colorIndex = sectors.indexOf(sector))
                                    },
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
private fun TriggerChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .gamepadSelectable(
                selected = selected,
                onClick = onClick,
                shape = RoundedCornerShape(8.dp),
                interactionSource = interactionSource,
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** Nome curto do keycode (mesma tabela do remap — AndroidConstants, nada inventado). */
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
    else -> "key:$keyCode"
}
