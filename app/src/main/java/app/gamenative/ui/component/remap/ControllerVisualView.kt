package app.gamenative.ui.component.remap

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gamenative.R
import app.gamenative.gamepad.FaceStyle
import app.gamenative.gamepad.GamepadButton
import app.gamenative.gamepad.glyphs.GamepadGlyphProvider
import app.gamenative.gamepad.mapping.ControllerVisualLayout
import app.gamenative.gamepad.mapping.HotspotKind
import app.gamenative.gamepad.mapping.VisualHotspot
import app.gamenative.ui.component.gamepadSelectable
import kotlin.math.max
import kotlin.math.min

/** Estado do controle no mapa visual (spec 2026-08-16-B-remap-visual-ppsspp, §1.2). */
enum class VisualControlState {
    /** Campo null do perfil efetivo — mapeamento automático (MappingDatabase/SDL). */
    AUTO,

    /** Binding explícito no perfil efetivo (merged) — desenhado em accent. */
    OVERRIDE,
}

/** Duração do flash ao vivo do PPSSPP (NotifyPressed → ~600 ms com decaimento). */
private const val FLASH_DURATION_MS = 600L

/**
 * Mock clicável do controle estilo PPSSPP/DS4Windows (spec 2026-08-16-B-remap-visual-
 * ppsspp, §1.2/§1.3): Canvas 100% vetorial (SEM assets PNG), cores do MaterialTheme
 * (dark/light), badges AUTO/OVERRIDE por controle, flash ao vivo com decaimento e
 * hotspots navegáveis por gamepad (`gamepadSelectable` — view-level, regra do AGENTS.md
 * para diálogos) + clique por toque.
 *
 * O flash entra como [flash] (controles acesos nos últimos ~600 ms — o DIALOG mantém o
 * set via bus `PluviaApp.events`); o decaimento é local: cada entrada do set recebe um
 * timestamp na entrada e decai até zero (holders vivos — nada é capturado de composição
 * antiga, lição C1).
 */
@Composable
fun ControllerVisualView(
    faceStyle: FaceStyle,
    hotspots: List<VisualHotspot>,
    stateOf: (String) -> VisualControlState,
    flash: State<Set<String>>,
    onHotspotTap: (String) -> Unit,
    capturingControl: String?,
    onCancelCapture: () -> Unit,
    onRestoreControl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val autoBadge = stringResource(R.string.gamepad_visual_badge_auto)
    val textMeasurer = rememberTextMeasurer()

    // Labels por controle resolvidas NA composição (glyphs por FaceStyle via
    // GamepadGlyphProvider) — o Canvas só lê o mapa pronto, nunca chama @Composable.
    val labels = hotspots.associate { hotspot ->
        hotspot.control to runCatching { GamepadButton.valueOf(hotspot.control) }
            .map { stringResource(GamepadGlyphProvider.labelRes(it, faceStyle)) }
            .getOrDefault(hotspot.control)
    }

    fun labelFor(control: String): String = labels[control] ?: control

    // Decaimento do flash: timestamps de ENTRADA no set (o dialog expira o set após
    // ~600 ms); um laço de frames mantém `nowMs` enquanto houver controles acesos.
    val entryTimes = remember { mutableStateMapOf<String, Long>() }
    LaunchedEffect(flash.value) {
        val now = SystemClock.uptimeMillis()
        for (control in flash.value) entryTimes[control] = now
        for (control in entryTimes.keys - flash.value) entryTimes.remove(control)
    }
    var nowMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(entryTimes.isNotEmpty()) {
        if (entryTimes.isEmpty()) return@LaunchedEffect
        while (entryTimes.isNotEmpty()) {
            withFrameNanos { }
            nowMs = SystemClock.uptimeMillis()
            entryTimes.entries.removeAll { (_, at) -> nowMs - at >= FLASH_DURATION_MS }
        }
        nowMs = 0L
    }

    fun flashAlpha(control: String): Float {
        val at = entryTimes[control] ?: return 0f
        val elapsed = nowMs - at
        if (elapsed >= FLASH_DURATION_MS) return 0f
        return (1f - elapsed / FLASH_DURATION_MS.toFloat()).coerceIn(0f, 1f)
    }

    // Controle selecionado (foco ou toque) — alimenta a faixa de contexto com o botão
    // "Restaurar automático" (§1.4, por controle).
    var selectedControl by remember { mutableStateOf<String?>(null) }

    Column(modifier = modifier) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(ControllerVisualLayout.BODY_ASPECT)
                .clipToBounds(),
        ) {
            val neutralFill = MaterialTheme.colorScheme.surfaceVariant
            val neutralBorder = MaterialTheme.colorScheme.outlineVariant
            val neutralText = MaterialTheme.colorScheme.onSurfaceVariant
            val accent = MaterialTheme.colorScheme.primary
            val accentText = MaterialTheme.colorScheme.onPrimary

            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                // Escala uniforme: o raio normalizado vira PIXEL pelo eixo Y (altura) —
                // círculos permanecem círculos na tela em qualquer largura de canvas.
                fun px(hotspot: VisualHotspot) = Offset(hotspot.cx * w, hotspot.cy * h)
                fun radius(hotspot: VisualHotspot) = hotspot.r * h

                // ── Corpo: rounded rect (spec §1.2: "corpo = rounded rect") ──
                val inset = h * 0.02f
                drawRoundRect(
                    color = neutralFill.copy(alpha = 0.35f),
                    topLeft = Offset(inset, inset),
                    size = Size(w - inset * 2f, h - inset * 2f),
                    cornerRadius = CornerRadius((h - inset * 2f) / 2f),
                )
                drawRoundRect(
                    color = neutralBorder.copy(alpha = 0.8f),
                    topLeft = Offset(inset, inset),
                    size = Size(w - inset * 2f, h - inset * 2f),
                    cornerRadius = CornerRadius((h - inset * 2f) / 2f),
                    style = Stroke(width = max(1f, h * 0.006f)),
                )

                // Centro da cruz do d-pad (média dos 4 braços) — os braços desenham do
                // centro até a borda externa do próprio hotspot.
                val dpadSpots = hotspots.filter { it.kind == HotspotKind.DPAD_DIR }
                val cross = if (dpadSpots.isNotEmpty()) {
                    Offset(
                        dpadSpots.map { it.cx }.average().toFloat() * w,
                        dpadSpots.map { it.cy }.average().toFloat() * h,
                    )
                } else {
                    Offset(0f, 0f)
                }

                hotspots.forEach { hotspot ->
                    val center = px(hotspot)
                    val r = radius(hotspot)
                    val override = stateOf(hotspot.control) == VisualControlState.OVERRIDE
                    val fill = if (override) accent else neutralFill
                    val border = if (override) accent else neutralBorder
                    val glow = flashAlpha(hotspot.control)

                    // Flash ao vivo: halo accent com decaimento (NotifyPressed do PPSSPP).
                    if (glow > 0f) {
                        drawCircle(
                            color = accent.copy(alpha = 0.45f * glow),
                            radius = r * 1.5f,
                            center = center,
                        )
                    }

                    when (hotspot.kind) {
                        HotspotKind.STICK -> {
                            // 2 círculos: externo (anel) + base (spec §1.2).
                            drawCircle(border, radius = r, center = center, style = Stroke(width = max(1.5f, h * 0.005f)))
                            drawCircle(fill, radius = r * 0.62f, center = center)
                        }

                        HotspotKind.BUTTON_ROUND -> {
                            drawCircle(fill, radius = r, center = center)
                            drawCircle(border, radius = r, center = center, style = Stroke(width = max(1f, h * 0.004f)))
                            val label = labelFor(hotspot.control)
                            if (label.isNotEmpty()) {
                                val style = TextStyle(
                                    color = if (override) accentText else neutralText,
                                    fontSize = (r * 1.05f).toSp(),
                                )
                                val measured = textMeasurer.measure(label, style)
                                drawText(
                                    textLayoutResult = measured,
                                    topLeft = Offset(
                                        center.x - measured.size.width / 2f,
                                        center.y - measured.size.height / 2f,
                                    ),
                                )
                            }
                        }

                        HotspotKind.DPAD_DIR -> {
                            // Braço da cruz: do centro do d-pad até a borda externa do
                            // próprio hotspot; espessura uniforme em px (0.8r de altura).
                            val armHalf = r * 0.8f
                            val corner = CornerRadius(min(armHalf, h * 0.015f))
                            val topLeft: Offset
                            val size: Size
                            when (hotspot.control) {
                                GamepadButton.DPAD_UP.name -> {
                                    topLeft = Offset(cross.x - armHalf, center.y - r)
                                    size = Size(armHalf * 2f, cross.y - (center.y - r))
                                }
                                GamepadButton.DPAD_DOWN.name -> {
                                    topLeft = Offset(cross.x - armHalf, cross.y)
                                    size = Size(armHalf * 2f, center.y + r - cross.y)
                                }
                                GamepadButton.DPAD_LEFT.name -> {
                                    topLeft = Offset(center.x - r, cross.y - armHalf)
                                    size = Size(cross.x - (center.x - r), armHalf * 2f)
                                }
                                else -> { // DPAD_RIGHT
                                    topLeft = Offset(cross.x, cross.y - armHalf)
                                    size = Size(center.x + r - cross.x, armHalf * 2f)
                                }
                            }
                            if (size.width > 0f && size.height > 0f) {
                                drawRoundRect(color = fill, topLeft = topLeft, size = size, cornerRadius = corner)
                            }
                        }

                        HotspotKind.BUMPER, HotspotKind.TRIGGER -> {
                            // Arco/trapézio na borda superior: pílula arredondada; o
                            // gatilho (L2/R2) é mais largo/atrás do bumper (L1/R1).
                            // Extensões no sistema do desenho: X em unidades do eixo X
                            // (×w) e Y em unidades do eixo Y (×h) — pílulas horizontais.
                            // L2/R2 em cx=0.165/0.835: halfW = 0.055×2.8 = 0.154 ≤ 0.165
                            // (nunca sangra para fora do canvas).
                            val widthScale = if (hotspot.kind == HotspotKind.TRIGGER) 2.8f else 3.0f
                            val heightScale = if (hotspot.kind == HotspotKind.TRIGGER) 1.3f else 1.0f
                            val halfW = r * widthScale * w
                            val halfH = r * heightScale * h
                            drawRoundRect(
                                color = fill,
                                topLeft = Offset(center.x - halfW, center.y - halfH),
                                size = Size(halfW * 2f, halfH * 2f),
                                cornerRadius = CornerRadius(halfH),
                            )
                        }

                        HotspotKind.SMALL -> {
                            // Pílula pequena (SELECT/START/GUIDE) com a label curta
                            // (X na escala X, Y na escala Y).
                            val pillW = r * 2.8f * w
                            val pillH = r * 1.5f * h
                            drawRoundRect(
                                color = fill,
                                topLeft = Offset(center.x - pillW / 2f, center.y - pillH / 2f),
                                size = Size(pillW, pillH),
                                cornerRadius = CornerRadius(pillH / 2f),
                            )
                            val label = labelFor(hotspot.control)
                            if (label.isNotEmpty()) {
                                val style = TextStyle(
                                    color = if (override) accentText else neutralText,
                                    fontSize = (r * 0.95f).toSp(),
                                )
                                val measured = textMeasurer.measure(label, style)
                                drawText(
                                    textLayoutResult = measured,
                                    topLeft = Offset(
                                        center.x - measured.size.width / 2f,
                                        center.y - measured.size.height / 2f,
                                    ),
                                )
                            }
                        }
                    }

                    // Mini-badge "A" de AUTO no quadrante superior-direito (spec §1.2).
                    if (!override) {
                        // Offset diagonal em escala uniforme (mesmo eixo do círculo).
                        val badgeCenter = Offset(
                            center.x + r * 0.7f * h,
                            center.y - r * 0.7f * h,
                        )
                        val badgeRadius = max(r * 0.42f, h * 0.012f)
                        drawCircle(color = neutralFill, radius = badgeRadius, center = badgeCenter)
                        drawCircle(
                            color = neutralBorder,
                            radius = badgeRadius,
                            center = badgeCenter,
                            style = Stroke(width = max(1f, h * 0.003f)),
                        )
                        val style = TextStyle(color = neutralText, fontSize = (badgeRadius * 1.15f).toSp())
                        val measured = textMeasurer.measure(autoBadge, style)
                        drawText(
                            textLayoutResult = measured,
                            topLeft = Offset(
                                badgeCenter.x - measured.size.width / 2f,
                                badgeCenter.y - measured.size.height / 2f,
                            ),
                        )
                    }
                }
            }

            // ── Hotspots navegáveis (foco de gamepad + toque) ──
            hotspots.forEach { hotspot ->
                val interactionSource = remember(hotspot.control) { MutableInteractionSource() }
                val diameter = (maxHeight * hotspot.r * 2f).coerceAtLeast(20.dp)
                Box(
                    modifier = Modifier
                        .offset(
                            x = maxWidth * hotspot.cx - diameter / 2,
                            y = maxHeight * hotspot.cy - diameter / 2,
                        )
                        .size(diameter)
                        .gamepadSelectable(
                            selected = capturingControl == hotspot.control,
                            onClick = {
                                selectedControl = hotspot.control
                                onHotspotTap(hotspot.control)
                            },
                            shape = CircleShape,
                            interactionSource = interactionSource,
                        )
                        .onFocusChanged { if (it.isFocused) selectedControl = hotspot.control }
                        .semantics { contentDescription = labelFor(hotspot.control) },
                )
            }

            // ── Chip flutuante de captura (§1.3) ──
            if (capturingControl != null) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shadowElevation = 6.dp,
                ) {
                    Row(
                        modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(
                                R.string.gamepad_visual_capture_prompt,
                                labelFor(capturingControl),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onCancelCapture) {
                            Text(stringResource(R.string.gamepad_remap_cancel))
                        }
                    }
                }
            }
        }

        // ── Legenda AUTO/OVERRIDE (badges §1.2) ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(autoBadge, fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    text = stringResource(R.string.gamepad_visual_legend_auto),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
                Text(
                    text = stringResource(R.string.gamepad_visual_legend_override),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ── Faixa de contexto: controle selecionado + "Restaurar automático" (§1.4) ──
        selectedControl?.let { control ->
            val state = stateOf(control)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = labelFor(control) + " — " + stringResource(
                        if (state == VisualControlState.OVERRIDE) {
                            R.string.gamepad_visual_state_override
                        } else {
                            R.string.gamepad_visual_state_auto
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (state == VisualControlState.OVERRIDE) {
                    val restoreInteraction = remember(control) { MutableInteractionSource() }
                    Text(
                        text = stringResource(R.string.gamepad_visual_restore_control),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .gamepadSelectable(
                                selected = false,
                                onClick = { onRestoreControl(control) },
                                shape = RoundedCornerShape(8.dp),
                                interactionSource = restoreInteraction,
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}
