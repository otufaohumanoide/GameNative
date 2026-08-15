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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.PluviaApp
import app.gamenative.R
import app.gamenative.events.AndroidEvent
import app.gamenative.gamepad.radial.ExecuteMode
import app.gamenative.gamepad.radial.RadialMacroKey
import app.gamenative.gamepad.radial.RadialMenuConfig
import app.gamenative.gamepad.radial.RadialSector
import app.gamenative.ui.component.GamepadFocusScope
import app.gamenative.ui.component.gamepadSelectable

/**
 * Editor de setores/macros do Radial Menu (F3.1 do spec 2026-08-15-input-core-
 * avancado + v2 do spec 2026-08-16-F-radial-v2-modeshift-turbo §1.2) — janela de
 * diálogo própria (padrão GamepadFocusScope do remap; regra do AGENTS.md: view-focus
 * em janela separada). Aberto pelo QuickMenu.
 *
 * Edita: gatilho (camada do perfil do device — o BINDING do botão continua no editor
 * de camadas existente), número de setores (2..8), executeMode (TAP_RELEASE | HOLD)
 * e, por setor, o rótulo, o ícone (grade da allowlist), a sequência de teclas
 * (captura via bus cru; timing default hold 60 ms / gap 40 ms — sliders por tecla
 * são follow-up registrado no spec) e o submenu (1 nível): "transformar em submenu"
 * promove o setor a pai (a macro atual vira o PRIMEIRO filho), filhos editáveis
 * (rótulo/ícone/macro) + adicionar/remover.
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
    var executeMode by remember { mutableStateOf(config.executeMode) }
    var sectors by remember {
        mutableStateOf(config.sectors.ifEmpty { List(8) { RadialSector("", emptyList(), it) } })
    }
    // F §1.2: captura por CAMINHO — setor raiz + filho opcional (submenu 1 nível).
    var capturePath by remember { mutableStateOf<Pair<Int, Int?>?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    fun sectorAt(path: Pair<Int, Int?>): RadialSector? {
        val parent = sectors.getOrNull(path.first) ?: return null
        val childIndex = path.second
        return if (childIndex == null) parent else parent.children.getOrNull(childIndex)
    }

    fun updateSector(path: Pair<Int, Int?>, transform: (RadialSector) -> RadialSector) {
        val childIndex = path.second
        sectors = if (childIndex == null) {
            sectors.toMutableList().also { it[path.first] = transform(it[path.first]) }
        } else {
            sectors.toMutableList().also { list ->
                val parent = list[path.first]
                list[path.first] = parent.copy(
                    children = parent.children.toMutableList().also { children ->
                        children[childIndex] = transform(children[childIndex])
                    },
                )
            }
        }
    }

    fun appendKey(path: Pair<Int, Int?>, keyCode: Int) =
        updateSector(path) { it.copy(keys = it.keys + RadialMacroKey(keyCode)) }

    fun clearKeys(path: Pair<Int, Int?>) = updateSector(path) { it.copy(keys = emptyList()) }

    fun setLabel(path: Pair<Int, Int?>, label: String) = updateSector(path) { it.copy(label = label) }

    fun setIcon(path: Pair<Int, Int?>, iconKey: String?) = updateSector(path) { it.copy(iconKey = iconKey) }

    fun resizeSectors(count: Int) {
        val current = sectors
        sectors = (0 until count).map { i ->
            current.getOrNull(i) ?: RadialSector("", emptyList(), i)
        }
        if ((capturePath?.first ?: -1) >= count) capturePath = null
    }

    /** F §1.2: "transformar em submenu" — promove o setor a pai; a macro vira o filho. */
    fun promoteToSubmenu(topIndex: Int) {
        val sector = sectors[topIndex]
        if (sector.children.isNotEmpty()) return
        sectors = sectors.toMutableList().also { list ->
            list[topIndex] = sector.copy(
                keys = emptyList(),
                children = listOf(
                    RadialSector(
                        label = sector.label,
                        keys = sector.keys,
                        colorIndex = 0,
                        iconKey = sector.iconKey,
                        children = emptyList(),
                    ),
                ),
            )
        }
    }

    /** Volta a setor normal (descarta os filhos — decisão registrada no impl doc). */
    fun removeSubmenu(topIndex: Int) {
        val sector = sectors[topIndex]
        sectors = sectors.toMutableList().also { it[topIndex] = sector.copy(children = emptyList()) }
    }

    fun addChild(topIndex: Int) {
        val sector = sectors[topIndex]
        sectors = sectors.toMutableList().also { list ->
            list[topIndex] = sector.copy(
                children = sector.children + RadialSector(
                    label = "",
                    keys = emptyList(),
                    colorIndex = sector.children.size,
                ),
            )
        }
    }

    fun removeChild(topIndex: Int, childIndex: Int) {
        val sector = sectors[topIndex]
        sectors = sectors.toMutableList().also { list ->
            list[topIndex] = sector.copy(
                children = sector.children.filterIndexed { i, _ -> i != childIndex },
            )
        }
    }

    // Captura de teclas via bus CRU (mesmo padrão do GamepadRemapDialog) — o alvo é
    // o CAMINHO (setor raiz ou filho) em captura.
    DisposableEffect(capturePath) {
        val path = capturePath ?: return@DisposableEffect onDispose {}
        fun handleKey(androidEvent: AndroidEvent.KeyEvent): Boolean {
            val ev = androidEvent.event ?: return false
            if (ev.deviceId != deviceId) return false
            if (ev.action == android.view.KeyEvent.ACTION_DOWN && ev.repeatCount == 0) {
                when (ev.keyCode) {
                    android.view.KeyEvent.KEYCODE_BACK, android.view.KeyEvent.KEYCODE_ESCAPE -> {
                        capturePath = null
                        status = null
                    }
                    else -> {
                        appendKey(path, ev.keyCode)
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
            enabled = capturePath == null,
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

                        // F §1.2: modo de execução (TAP_RELEASE = v1; HOLD = painel).
                        Text(
                            text = stringResource(R.string.radial_menu_execute_mode_title),
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
                                label = stringResource(R.string.radial_menu_execute_mode_tap_release),
                                selected = executeMode == ExecuteMode.TAP_RELEASE,
                                onClick = { executeMode = ExecuteMode.TAP_RELEASE },
                            )
                            TriggerChip(
                                label = stringResource(R.string.radial_menu_execute_mode_hold),
                                selected = executeMode == ExecuteMode.HOLD,
                                onClick = { executeMode = ExecuteMode.HOLD },
                            )
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

                        // Setores (raiz) + filhos do submenu.
                        sectors.forEachIndexed { index, sector ->
                            SectorEditorRow(
                                sector = sector,
                                indexLabel = stringResource(R.string.radial_menu_sector_label, index + 1),
                                capturing = capturePath == (index to null),
                                onCaptureToggle = {
                                    capturePath = if (capturePath == (index to null)) null else (index to null)
                                },
                                onLabelChange = { setLabel(index to null, it) },
                                onClearKeys = { clearKeys(index to null) },
                                onIconSelect = { setIcon(index to null, it) },
                            )
                            if (sector.children.isEmpty()) {
                                TextButton(onClick = { promoteToSubmenu(index) }) {
                                    Text(stringResource(R.string.radial_menu_submenu_button))
                                }
                            } else {
                                sector.children.forEachIndexed { childIndex, child ->
                                    Column(modifier = Modifier.padding(start = 16.dp)) {
                                        SectorEditorRow(
                                            sector = child,
                                            indexLabel = stringResource(
                                                R.string.radial_menu_submenu_child_label,
                                                childIndex + 1,
                                            ),
                                            capturing = capturePath == (index to childIndex),
                                            onCaptureToggle = {
                                                capturePath =
                                                    if (capturePath == (index to childIndex)) null else (index to childIndex)
                                            },
                                            onLabelChange = { setLabel(index to childIndex, it) },
                                            onClearKeys = { clearKeys(index to childIndex) },
                                            onIconSelect = { setIcon(index to childIndex, it) },
                                        )
                                        TextButton(onClick = { removeChild(index, childIndex) }) {
                                            Text(stringResource(R.string.radial_menu_submenu_remove_child))
                                        }
                                    }
                                }
                                TextButton(onClick = { addChild(index) }) {
                                    Text(stringResource(R.string.radial_menu_submenu_add_child))
                                }
                                TextButton(onClick = { removeSubmenu(index) }) {
                                    Text(stringResource(R.string.radial_menu_submenu_remove))
                                }
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
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
                                    executeMode = executeMode,
                                    sectors = sectors.mapIndexed { i, sector ->
                                        sector.copy(colorIndex = i)
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

/**
 * F §1.2: linha de edição de UM setor (raiz ou filho) — rótulo, captura/limpeza de
 * macro e grade de ícones da allowlist (None + 16 Material icons).
 */
@Composable
private fun SectorEditorRow(
    sector: RadialSector,
    indexLabel: String,
    capturing: Boolean,
    onCaptureToggle: () -> Unit,
    onLabelChange: (String) -> Unit,
    onClearKeys: () -> Unit,
    onIconSelect: (String?) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = indexLabel,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(end = 8.dp),
        )
        OutlinedTextField(
            value = sector.label,
            onValueChange = onLabelChange,
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
        TextButton(onClick = onCaptureToggle) {
            Text(
                stringResource(
                    if (capturing) {
                        R.string.radial_menu_capture_running
                    } else {
                        R.string.radial_menu_capture
                    },
                ),
            )
        }
        TextButton(onClick = onClearKeys) {
            Text(stringResource(R.string.radial_menu_clear_macro))
        }
    }
    // Grade de ícones (allowlist — Material icons, nunca asset).
    Text(
        text = stringResource(R.string.radial_menu_icon_title),
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(top = 2.dp),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconChip(
            icon = null,
            selected = sector.iconKey == null,
            onClick = { onIconSelect(null) },
        )
        RadialMenuIcons.ALL.forEach { (key, vector) ->
            IconChip(
                icon = vector,
                selected = sector.iconKey == key,
                onClick = { onIconSelect(key) },
            )
        }
    }
}

/** Chip de ícone da grade (None = sem ícone). */
@Composable
private fun IconChip(icon: ImageVector?, selected: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .gamepadSelectable(
                selected = selected,
                onClick = onClick,
                shape = RoundedCornerShape(8.dp),
                interactionSource = interactionSource,
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon == null) {
            Text(
                text = stringResource(R.string.radial_menu_icon_none),
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(2.dp),
            )
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
