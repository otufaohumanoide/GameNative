package app.gamenative.ui.component.radial

import android.app.Activity
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gamenative.PluviaApp
import app.gamenative.events.GamepadInputEvent
import app.gamenative.gamepad.GamepadAxis
import app.gamenative.gamepad.InputEvent
import app.gamenative.gamepad.radial.RadialMenuConfig
import app.gamenative.gamepad.radial.RadialMenuGeometry
import app.gamenative.gamepad.radial.RadialSector
import app.gamenative.ui.component.GamepadHaptics
import kotlin.math.hypot

/**
 * Overlay do Radial Menu (F3.1 do spec 2026-08-15-input-core-avancado) — arquivo
 * PRÓPRIO (limite dex do XServerScreen). Superfície in-game: o game já está pausado
 * pelo chamador (`pauseForOverlayIfAllowed` + `overlayInputState` OVERLAY) — este
 * overlay NUNCA toca o jogo diretamente, só reporta seleção/execução.
 *
 * Seleção TOUCH-FIRST (padrão Backbone): dedo desce → slide até o setor (ângulo do
 * toque define o destaque) → soltar EXECUTA o macro do setor. Toque sem arrasto
 * (distância < 25% do raio) = CANCELA (fecha sem executar).
 *
 * Fallback STICK (usuário gamepad-only/HDMI): direção do stick esquerdo (magnitude
 * ≥ 0.5) destaca o setor — janela de estabilidade 120 ms (padrão GamepadMoveDedupe) e
 * tick háptico por MUDANÇA de setor (F2.3); botão A (FACE_BOTTOM) executa; o gatilho
 * de camada (HOLD) continua dono do fechamento.
 */
@Composable
fun RadialMenuOverlay(
    config: RadialMenuConfig,
    deviceId: Int,
    onExecute: (RadialSector) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val sectors = config.sectors
    if (sectors.isEmpty()) return

    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    var selected by remember { mutableIntStateOf(-1) }
    var lastTickSector by remember { mutableIntStateOf(-1) }
    var stickLastChangeMs by remember { mutableLongStateOf(0L) }
    var stickActive by remember { mutableStateOf(false) }
    // Holder vivo do vetor do stick (amostras chegam eixo a eixo) — lição C1.
    var stickX by remember { mutableFloatStateOf(0f) }
    var stickY by remember { mutableFloatStateOf(0f) }
    // Callbacks lidos NO MOMENTO do evento (lição C1 — nunca capturar val stale).
    val currentExecute by rememberUpdatedState(onExecute)
    val currentCancel by rememberUpdatedState(onCancel)

    fun select(sector: Int) {
        if (sector == selected) return
        selected = sector
        // F2.3: tick háptico por mudança de setor (mesmo gate global do rumble).
        if (sector != lastTickSector) {
            GamepadHaptics.tickDevice(deviceId)
            lastTickSector = sector
        }
    }

    // Fallback stick: eventos LÓGICOS do bus (janela in-game — regra do AGENTS.md:
    // overlays usam bus navigators, nunca view-focus).
    DisposableEffect(deviceId, sectors) {
        val listener: (GamepadInputEvent) -> Boolean = listener@{ event ->
            when (val input = event.input) {
                is InputEvent.AxisMotion -> {
                    if (input.deviceId != deviceId) return@listener false
                    val isLeft = input.axis == GamepadAxis.LEFT_X || input.axis == GamepadAxis.LEFT_Y
                    if (!isLeft) return@listener false
                    if (input.axis == GamepadAxis.LEFT_X) stickX = input.value
                    if (input.axis == GamepadAxis.LEFT_Y) stickY = input.value
                    val magnitude = hypot(stickX, stickY)
                    val now = SystemClock.uptimeMillis()
                    if (magnitude >= 0.5f) {
                        if (now - stickLastChangeMs >= 120L) {
                            stickLastChangeMs = now
                            stickActive = true
                            select(
                                RadialMenuGeometry.sectorIndex(
                                    RadialMenuGeometry.angleOf(stickX, stickY),
                                    sectors.size,
                                ),
                            )
                        }
                    } else {
                        stickActive = false
                    }
                    true
                }

                is InputEvent.ButtonDown -> {
                    if (input.deviceId != deviceId) return@listener false
                    if (input.button.name == "FACE_BOTTOM" && stickActive && selected >= 0) {
                        currentExecute(sectors[selected])
                        true
                    } else {
                        false
                    }
                }

                else -> false
            }
        }
        PluviaApp.events.on<GamepadInputEvent, Boolean>(listener)
        onDispose {
            PluviaApp.events.off<GamepadInputEvent, Boolean>(listener)
        }
    }

    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { sizePx = it }
            .pointerInput(sectors, sizePx) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var last = down.position
                    var dragged = false
                    val center = Offset(sizePx.width / 2f, sizePx.height / 2f)
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.consume() // o overlay dona o gesto — nada vaza para o jogo
                        last = change.position
                        val delta = Offset(last.x - center.x, last.y - center.y)
                        val dist = hypot(delta.x, delta.y)
                        val radius = kotlin.math.min(sizePx.width, sizePx.height) / 2f
                        if (dist > radius * 0.25f) {
                            dragged = true
                            select(
                                RadialMenuGeometry.sectorIndex(
                                    RadialMenuGeometry.angleOf(delta.x, delta.y),
                                    sectors.size,
                                ),
                            )
                        }
                    } while (change.pressed)
                    if (dragged && selected >= 0) {
                        currentExecute(sectors[selected])
                    } else {
                        currentCancel()
                    }
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val radius = kotlin.math.min(size.width, size.height) * 0.38f
            // dim do fundo (o jogo está pausado — dim é só feedback visual)
            drawRect(Color.Black.copy(alpha = 0.55f))
            val count = sectors.size
            val sweep = 360f / count
            for (i in 0 until count) {
                val startAngle = -90f + i * sweep
                val path = Path().apply {
                    moveTo(cx, cy)
                    arcTo(
                        Rect(cx - radius, cy - radius, cx + radius, cy + radius),
                        startAngle,
                        sweep,
                        false,
                    )
                    close()
                }
                val base = SECTOR_PALETTE[i % SECTOR_PALETTE.size]
                val color = if (i == selected) base.copy(alpha = 0.95f) else base.copy(alpha = 0.55f)
                drawPath(path, color)
            }
        }
        if (sizePx.width > 0) {
            val radius = kotlin.math.min(sizePx.width, sizePx.height) * 0.30f
            val cx = sizePx.width / 2f
            val cy = sizePx.height / 2f
            sectors.forEachIndexed { i, sector ->
                if (sector.label.isEmpty()) return@forEachIndexed
                val angleRad = Math.toRadians(
                    -90.0 + i * (360.0 / sectors.size) + (360.0 / sectors.size / 2.0),
                )
                val xPx = (cx + kotlin.math.cos(angleRad).toFloat() * radius)
                val yPx = (cy + kotlin.math.sin(angleRad).toFloat() * radius)
                Text(
                    text = sector.label,
                    color = if (i == selected) Color.White else Color.White.copy(alpha = 0.75f),
                    fontSize = 13.sp,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.offset(
                        x = with(density) { (xPx / density.density).dp },
                        y = with(density) { (yPx / density.density).dp },
                    ),
                )
            }
        }
        Text(
            text = "✕",
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 20.sp,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

private val SECTOR_PALETTE = listOf(
    Color(0xFF8D6E63), Color(0xFFF06292), Color(0xFF7986CB), Color(0xFF4DB6AC),
    Color(0xFFFFB74D), Color(0xFF9575CD), Color(0xFF4FC3F7), Color(0xFF81C784),
)
