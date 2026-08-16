package app.gamenative.ui.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.gamepad.GamepadDevice
import app.gamenative.gamepad.mapping.AutoconfigSaveResult
import app.gamenative.gamepad.mapping.GamepadMapping
import app.gamenative.gamepad.mapping.MappingDiff
import app.gamenative.gamepad.mapping.SdlControllerDb
import app.gamenative.gamepad.mapping.SdlMappingCodec
import java.nio.charset.Charset

/**
 * K6 (spec 2026-08-16-K6, §1.3) — diálogo de EXPORT no formato SDL: gera a string
 * (`SdlMappingCodec.encode` — formato SDL_GameControllerDB, zlib), mostra o
 * preview copiável, compartilha via `ACTION_SEND` (clipboard/share intent) e
 * salva `.txt` via SAF. Usado pelo device card e pelo remap dialog.
 *
 * O rodapé cita o formato e a DB comunitária (atribuição pedida pela licença ao
 * reutilizar o DB — o share da string própria não exige, mas o preview cita).
 */
@Composable
fun SdlMappingExportDialog(
    device: GamepadDevice,
    mapping: GamepadMapping,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val encoded = remember(device, mapping) {
        SdlMappingCodec.encode(device, mapping, mapping.faceStyle)
    }
    var status by remember { mutableStateOf<String?>(null) }

    var pendingSave by remember { mutableStateOf<String?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val text = pendingSave ?: return@rememberLauncherForActivityResult
        pendingSave = null
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
            } ?: error("null stream")
        }.onSuccess {
            status = context.getString(R.string.gamepad_sdl_saved)
        }.onFailure {
            status = context.getString(R.string.gamepad_sdl_save_failed)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.gamepad_sdl_export_dialog_title)) },
        text = {
            Column {
                SelectionContainer {
                    Text(
                        text = encoded,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.gamepad_sdl_export_footer),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                status?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = {
                    val clipboard =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("sdl_mapping", encoded))
                    status = context.getString(R.string.gamepad_sdl_copied)
                }) {
                    Text(stringResource(R.string.gamepad_sdl_copy))
                }
                TextButton(onClick = {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, encoded)
                    }
                    context.startActivity(
                        Intent.createChooser(intent, context.getString(R.string.gamepad_sdl_share)),
                    )
                }) {
                    Text(stringResource(R.string.gamepad_sdl_share))
                }
                TextButton(onClick = {
                    pendingSave = encoded
                    saveLauncher.launch("mapping-${device.mappingKey}.txt")
                }) {
                    Text(stringResource(R.string.gamepad_sdl_save_file))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.gamepad_sdl_close))
            }
        },
    )
}

/**
 * K6 (spec 2026-08-16-K6, §1.2) — diálogo de IMPORT no formato SDL: colar string
 * (TextField) ou arquivo via SAF; parse AO VIVO ([SdlControllerDb.parseLine]) com
 * preview (nome, plataforma, N botões, N eixos) e DIFF contra o mapping atual
 * ([SdlMappingCodec.diff]); validações: `platform:Android` (ausente = desktop →
 * bloqueio com explicação) e GUID de outro controle → aviso NÃO-bloqueante
 * (affinity vid/pid, análogo ao RetroArch task_autodetect.c:163).
 *
 * [onImport] recebe o mapping decodificado e devolve o resultado do hub (tier
 * USER + re-resolve vivo, K5 §1.3.4); `Saved` fecha o diálogo (o badge do card
 * passa a USER na hora).
 */
@Composable
fun SdlMappingImportDialog(
    device: GamepadDevice,
    current: GamepadMapping,
    onImport: (GamepadMapping) -> AutoconfigSaveResult,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }

    var pendingLoad by remember { mutableStateOf<Boolean?>(null) }
    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val flag = pendingLoad ?: return@rememberLauncherForActivityResult
        pendingLoad = null
        if (!flag || uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.readBytes().toString(Charsets.UTF_8)
            } ?: error("null stream")
        }.onSuccess { text -> input = text }
    }

    val parsed = remember(input) { SdlControllerDb.parseLine(input) }
    val platform = remember(input) { SdlControllerDb.platformOf(input) }
    val blockReason = when {
        input.isBlank() -> null
        platform == "Android" -> null
        else -> stringResource(R.string.gamepad_sdl_import_block_platform_format, platform ?: "—")
    }
    val affinityWarning = parsed?.let {
        val guid = input.substringBefore(',').trim()
        val key = SdlControllerDb.mappingKeyFromGuid(guid)
        if (key.isNotEmpty() && key != device.mappingKey) {
            stringResource(R.string.gamepad_sdl_import_warn_affinity_format)
        } else {
            null
        }
    }
    val diffs = parsed?.let { SdlMappingCodec.diff(current, it) }.orEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.gamepad_sdl_import_dialog_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(stringResource(R.string.gamepad_sdl_import_paste_hint)) },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = {
                    pendingLoad = true
                    openLauncher.launch(arrayOf("text/*"))
                }) {
                    Text(stringResource(R.string.gamepad_sdl_import_load_file))
                }
                if (input.isBlank()) {
                    Text(
                        text = stringResource(R.string.gamepad_sdl_import_invalid),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (parsed == null) {
                    Text(
                        text = stringResource(R.string.gamepad_sdl_import_invalid),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(
                        text = stringResource(
                            R.string.gamepad_sdl_import_preview_format,
                            parsed.name,
                            platform ?: "—",
                            parsed.buttons.size,
                            parsed.axes.size,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (diffs.isEmpty()) {
                        Text(
                            text = stringResource(R.string.gamepad_sdl_import_no_diff),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    } else {
                        diffs.take(12).forEach { diff ->
                            Text(
                                text = diffLine(context, diff),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (diffs.size > 12) {
                            Text(
                                text = stringResource(R.string.gamepad_sdl_import_more_diffs, diffs.size - 12),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                blockReason?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                affinityWarning?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsed != null && blockReason == null,
                onClick = {
                    val mapping = parsed ?: return@TextButton
                    when (onImport(mapping)) {
                        is AutoconfigSaveResult.Saved -> onDismiss()
                        else -> Unit // o erro de validação aparece no diálogo do caller
                    }
                },
            ) {
                Text(stringResource(R.string.gamepad_sdl_import_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.gamepad_sdl_import_cancel))
            }
        },
    )
}

/** Linha do diff no formato do spec §1.2: `+ X: b1` / `− X: b0` / `± X: b0 → b1`. */
private fun diffLine(context: Context, diff: MappingDiff): String = when {
    diff.from == null && diff.to != null ->
        context.getString(R.string.gamepad_sdl_diff_add_format, diff.semantic, diff.to)
    diff.from != null && diff.to == null ->
        context.getString(R.string.gamepad_sdl_diff_remove_format, diff.semantic, diff.from)
    else ->
        context.getString(R.string.gamepad_sdl_diff_change_format, diff.semantic, diff.from ?: "—", diff.to ?: "—")
}
