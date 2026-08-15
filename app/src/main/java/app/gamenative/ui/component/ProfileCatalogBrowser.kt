package app.gamenative.ui.component

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.PluviaApp
import app.gamenative.R
import app.gamenative.gamepad.profiles.CatalogEntry
import app.gamenative.gamepad.profiles.ProfileCatalog
import app.gamenative.gamepad.profiles.ProfileSummaryCategory
import app.gamenative.shaders.ShaderPagingLogic
import app.gamenative.ui.theme.PluviaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Browser do catálogo comunitário de perfis (spec 2026-08-16-E-profile-catalog-
 * comunitario, §1.3) — JANELA PRÓPRIA (aberta por cima do GamepadRemapDialog),
 * então usa [GamepadFocusScope] de VIEW (regra do repo: nunca navigator de bus em
 * janela separada). Mesma ergonomia do ShaderBrowserOverlay: busca primeiro,
 * paginação (ShaderPagingLogic puro), A aplica / B volta, IME do campo só abre com
 * intenção explícita ([GamepadSearchField]).
 *
 * Aplicar = gravar override do JOGO ATUAL (chave appId) no gameStore — o merge de
 * 3 camadas (JOGO > GLOBAL > AUTO) faz o resto e o hub re-resolve o perfil na hora
 * (F3.2); NADA é escrito no escopo global do device.
 */
@Composable
fun ProfileCatalogBrowser(
    appId: String?,
    onApply: (CatalogEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    // Catálogo do asset (offline, embarcado no APK — nunca rede).
    var entries by remember { mutableStateOf<List<CatalogEntry>>(emptyList()) }
    var loadFailed by remember { mutableStateOf(false) }
    var invalidSkipped by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        val text = runCatching {
            withContext(Dispatchers.IO) {
                context.assets.open("profile-catalog.json").bufferedReader().use { it.readText() }
            }
        }.getOrNull()
        if (text == null) {
            loadFailed = true
        } else {
            val result = ProfileCatalog.parse(text)
            entries = result.entries
            invalidSkipped = result.invalidCount
        }
    }

    var query by remember { mutableStateOf("") }
    var page by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<CatalogEntry?>(null) }
    var applied by remember { mutableStateOf(false) }

    val filtered = ProfileCatalog.search(entries, query)
    val forThisGame = if (selected == null) ProfileCatalog.forGame(filtered, appId) else emptyList()
    val forThisGameIds = forThisGame.map { it.id }.toSet()
    val ordered = (forThisGame + filtered.filter { it.id !in forThisGameIds })

    val searchRequester = remember { androidx.compose.ui.focus.FocusRequester() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        GamepadFocusScope(
            enabled = true,
            backAction = onDismiss,
            initialFocusRequester = searchRequester,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .gamepadBackHandler(onDismiss),
                color = MaterialTheme.colorScheme.background,
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    // ── Header ──
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.profile_catalog_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.profile_catalog_back))
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Filled.Close, contentDescription = null)
                        }
                    }

                    val detail = selected
                    if (detail == null) {
                        // ── Lista (busca + paginação) ──
                        GamepadSearchField(
                            query = query,
                            onQueryChange = {
                                query = it
                                page = 0
                            },
                            placeholder = stringResource(R.string.profile_catalog_search_placeholder),
                            focusIndex = 0,
                            onFocusIndexChanged = {},
                            focusRequester = searchRequester,
                        )
                        Text(
                            text = stringResource(
                                R.string.profile_catalog_subtitle,
                                ordered.size,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                        if (ordered.isEmpty()) {
                            Text(
                                text = stringResource(R.string.profile_catalog_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            CatalogPageRows(
                                ordered = ordered,
                                page = page,
                                appId = appId,
                                forThisGameIds = forThisGameIds,
                                onOpen = { selected = it },
                            )
                        }
                        CatalogPageFooter(
                            page = page,
                            count = ordered.size,
                            onPageChange = { page = it },
                        )
                        if (invalidSkipped > 0) {
                            Text(
                                text = stringResource(R.string.profile_catalog_invalid_skipped, invalidSkipped),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    } else {
                        // ── Preview da entry (§1.3): descrição + diff-resumo ──
                        CatalogEntryDetail(
                            entry = detail,
                            appId = appId,
                            applied = applied,
                            onApply = {
                                appId?.let {
                                    PluviaApp.gamepadHub.saveGameProfile(it, detail.profile)
                                    applied = true
                                    onApply(detail)
                                }
                            },
                            onBack = { selected = null },
                            onDismiss = onDismiss,
                        )
                    }
                }
            }
        }
    }
}

private const val CATALOG_PAGE_SIZE = 8

@Composable
private fun CatalogPageRows(
    ordered: List<CatalogEntry>,
    page: Int,
    appId: String?,
    forThisGameIds: Set<String>,
    onOpen: (CatalogEntry) -> Unit,
) {
    val pageItems = ordered.drop(page * CATALOG_PAGE_SIZE).take(CATALOG_PAGE_SIZE)
    for (entry in pageItems) {
        CatalogRow(
            entry = entry,
            isForThisGame = appId != null && entry.id in forThisGameIds,
            onClick = { onOpen(entry) },
        )
    }
}

@Composable
private fun CatalogRow(
    entry: CatalogEntry,
    isForThisGame: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .gamepadSelectable(
                selected = false,
                onClick = onClick,
                shape = RoundedCornerShape(10.dp),
                interactionSource = interactionSource,
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    entry.game?.let { "game: $it" },
                    entry.controller,
                ).joinToString(" · ").ifBlank { stringResource(R.string.profile_catalog_any_game) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isForThisGame) {
            Text(
                text = stringResource(R.string.profile_catalog_for_this_game),
                style = MaterialTheme.typography.labelSmall,
                color = PluviaTheme.colors.accentCyan,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Text(
            text = entry.author,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun CatalogPageFooter(
    page: Int,
    count: Int,
    onPageChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
    ) {
        val prevInteraction = remember { MutableInteractionSource() }
        // Guarda explícita: o onPreviewKeyEvent do gamepadSelectable não lê
        // `enabled` (linha desabilitada nunca ganha foco via clickable desabilitado,
        // mas o guarda no lambda torna o no-op À PROVA de estado).
        val goPrev = { if (page > 0) onPageChange(ShaderPagingLogic.decidePage(page, -1, count, CATALOG_PAGE_SIZE)) }
        TextButton(
            enabled = page > 0,
            modifier = Modifier.gamepadSelectable(
                selected = false,
                enabled = page > 0,
                onClick = goPrev,
                shape = RoundedCornerShape(8.dp),
                interactionSource = prevInteraction,
            ),
            onClick = goPrev,
        ) {
            Text(stringResource(R.string.profile_catalog_previous_page))
        }
        Text(
            text = stringResource(
                R.string.profile_catalog_page,
                page + 1,
                maxOf(1, (count + CATALOG_PAGE_SIZE - 1) / CATALOG_PAGE_SIZE),
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        val nextInteraction = remember { MutableInteractionSource() }
        val hasNext = (page + 1) * CATALOG_PAGE_SIZE < count
        val goNext = { if (hasNext) onPageChange(ShaderPagingLogic.decidePage(page, 1, count, CATALOG_PAGE_SIZE)) }
        TextButton(
            enabled = hasNext,
            modifier = Modifier.gamepadSelectable(
                selected = false,
                enabled = hasNext,
                onClick = goNext,
                shape = RoundedCornerShape(8.dp),
                interactionSource = nextInteraction,
            ),
            onClick = goNext,
        ) {
            Text(stringResource(R.string.profile_catalog_next_page))
        }
    }
}

@Composable
private fun CatalogEntryDetail(
    entry: CatalogEntry,
    appId: String?,
    applied: Boolean,
    onApply: () -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        val backInteraction = remember { MutableInteractionSource() }
        TextButton(
            modifier = Modifier.gamepadSelectable(
                selected = false,
                onClick = onBack,
                shape = RoundedCornerShape(8.dp),
                interactionSource = backInteraction,
            ),
            onClick = onBack,
        ) {
            Text("← " + stringResource(R.string.profile_catalog_back))
        }
        Text(
            text = entry.name,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(
                R.string.profile_catalog_by,
                listOfNotNull(entry.author, entry.game?.let { "game: $it" }, entry.controller)
                    .joinToString(" · "),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        entry.downloads?.let { downloads ->
            Text(
                text = stringResource(R.string.profile_catalog_downloads, downloads),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = entry.description,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(10.dp))
        val summary = ProfileCatalog.summaryOf(entry.profile)
        if (summary.isNotEmpty()) {
            Text(
                text = stringResource(R.string.profile_catalog_touches),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (category in summary) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = PluviaTheme.colors.accentPurple.copy(alpha = 0.14f),
                    ) {
                        Text(
                            text = stringResource(summaryLabelRes(category)),
                            style = MaterialTheme.typography.labelMedium,
                            color = PluviaTheme.colors.accentPurple,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
        }
        HorizontalDivider()
        Spacer(modifier = Modifier.height(10.dp))
        val applyInteraction = remember { MutableInteractionSource() }
        // Guarda explícita no lambda (mesma razão do footer de paginação).
        val applyGuard = { if (appId != null && !applied) onApply() }
        TextButton(
            enabled = appId != null && !applied,
            modifier = Modifier
                .fillMaxWidth()
                .gamepadSelectable(
                    selected = applied,
                    enabled = appId != null && !applied,
                    onClick = applyGuard,
                    shape = RoundedCornerShape(10.dp),
                    interactionSource = applyInteraction,
                ),
            onClick = applyGuard,
        ) {
            Text(
                text = when {
                    applied -> stringResource(R.string.profile_catalog_applied)
                    appId == null -> stringResource(R.string.profile_catalog_apply_disabled_hint)
                    else -> stringResource(R.string.profile_catalog_apply)
                },
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** Rótulo localizado de cada categoria do diff-resumo (E §1.3). */
private fun summaryLabelRes(category: ProfileSummaryCategory): Int = when (category) {
    ProfileSummaryCategory.BINDINGS -> R.string.profile_summary_bindings
    ProfileSummaryCategory.EXPR -> R.string.profile_summary_expr
    ProfileSummaryCategory.GYRO -> R.string.profile_summary_gyro
    ProfileSummaryCategory.LAYERS -> R.string.profile_summary_layers
    ProfileSummaryCategory.SWIPES -> R.string.profile_summary_swipes
    ProfileSummaryCategory.STICK -> R.string.profile_summary_stick
    ProfileSummaryCategory.RUMBLE -> R.string.profile_summary_rumble
    ProfileSummaryCategory.TOUCHPAD -> R.string.profile_summary_touchpad
}
