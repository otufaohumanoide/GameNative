package app.gamenative.ui.component.remap

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.gamenative.gamepad.processing.StickSample
import app.gamenative.gamepad.processing.StickTransform
import app.gamenative.gamepad.processing.StickTransformConfig

/**
 * K7 (spec 2026-08-16-K7, §1.2) — view de histórico do stick (port clean-room do
 * `JoystickHistoryView` do PPSSPP `ControlMappingScreen.cpp:487-585`): trilha das
 * últimas N posições com decaimento de alpha (o rastro mostra drift/redeada do
 * stick), ponto atual, círculo da deadzone e anel do anti-deadzone desenhados por
 * cima (visual imediato do que cada slider faz). Puro DESENHO — sem lógica de
 * input; o caller alimenta os samples (bus do hub na tab de calibração).
 *
 * [mode] RAW desenha os samples como chegam; CALIBRATED aplica [config] por
 * amostra (deadzone → anti-deadzone → curve → maxOutput — a MESMA ordem do
 * pipeline). Duas instâncias lado a lado — a comparabilidade É a feature.
 */
enum class JoystickHistoryMode { RAW, CALIBRATED }

@Composable
fun JoystickHistoryView(
    mode: JoystickHistoryMode,
    config: StickTransformConfig,
    samples: State<List<StickSample>>,
    modifier: Modifier = Modifier,
) {
    val trail = samples.value
    val historyColor = if (mode == JoystickHistoryMode.RAW) {
        Color(0xFF8BC34A)
    } else {
        Color(0xFF29B6F6)
    }
    Canvas(modifier = modifier.size(160.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = size.minDimension / 2f - 6.dp.toPx()

        // Guias: círculo externo (deflexão 1.0) + cruzeta.
        drawCircle(
            color = Color.White.copy(alpha = 0.15f),
            radius = radius,
            center = Offset(cx, cy),
            style = Stroke(width = 1.dp.toPx()),
        )
        drawLine(
            color = Color.White.copy(alpha = 0.08f),
            start = Offset(cx - radius, cy),
            end = Offset(cx + radius, cy),
            strokeWidth = 1.dp.toPx(),
        )
        drawLine(
            color = Color.White.copy(alpha = 0.08f),
            start = Offset(cx, cy - radius),
            end = Offset(cx, cy + radius),
            strokeWidth = 1.dp.toPx(),
        )

        // Círculo da deadzone (raio = config.deadzone).
        drawCircle(
            color = Color.White.copy(alpha = 0.35f),
            radius = radius * config.deadzone.coerceIn(0f, 1f),
            center = Offset(cx, cy),
            style = Stroke(width = 1.dp.toPx()),
        )

        // Anel do anti-deadzone (raio = config.antiDeadzone).
        if (config.antiDeadzone > 0f) {
            drawCircle(
                color = Color(0xFFFF9800).copy(alpha = 0.6f),
                radius = radius * config.antiDeadzone.coerceIn(0f, 1f),
                center = Offset(cx, cy),
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }

        fun project(sample: StickSample): Offset {
            val x = sample.x.coerceIn(-1f, 1f) * radius
            val y = -sample.y.coerceIn(-1f, 1f) * radius // tela: y para baixo
            return Offset(cx + x, cy + y)
        }

        // Trilha com decaimento de alpha (mais recente = mais vivo).
        val n = trail.size
        trail.forEachIndexed { index, sample ->
            val raw = if (mode == JoystickHistoryMode.CALIBRATED) {
                val r = StickTransform.apply(sample, config)
                if (r.inDeadzone) StickSample(0f, 0f) else StickSample(r.x, r.y)
            } else {
                sample
            }
            val alpha = 0.15f + 0.85f * (index + 1).toFloat() / n.coerceAtLeast(1)
            drawCircle(
                color = historyColor.copy(alpha = alpha),
                radius = 2.5.dp.toPx(),
                center = project(raw),
            )
        }

        // Ponto atual (maior, borda).
        val current = trail.lastOrNull() ?: return@Canvas
        val currentRaw = if (mode == JoystickHistoryMode.CALIBRATED) {
            val r = StickTransform.apply(current, config)
            if (r.inDeadzone) StickSample(0f, 0f) else StickSample(r.x, r.y)
        } else {
            current
        }
        drawCircle(
            color = historyColor,
            radius = 5.dp.toPx(),
            center = project(currentRaw),
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.9f),
            radius = 2.dp.toPx(),
            center = project(currentRaw),
        )
    }
}
