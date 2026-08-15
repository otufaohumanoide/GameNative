package app.gamenative.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Visual constants of the focus language (spec 2026-08-15 focus-feedback-v2).
/** Focused background overlay alpha (§1.1) — the whole focused element lights up. */
private const val FOCUSED_OVERLAY_ALPHA = 0.08f
/** Sweep alpha over the solid base ring (§1.1) — the base always shows through the gradient. */
private const val SWEEP_ALPHA = 0.75f
/** How far the solid base ring is lifted toward white ("primária clara", §1.1). */
private const val BASE_RING_LIGHTEN_FRACTION = 0.35f
/** Selected persistent background tint alpha (§1.2). */
private const val SELECTED_TINT_ALPHA = 0.10f
/** Selected hairline border alpha (§1.2). */
private const val SELECTED_HAIRLINE_ALPHA = 0.5f

/**
 * Single visual language for gamepad focus across every QuickMenu surface
 * (spec 2026-08-09, §3.2 — replaces the heterogeneous focusRing + cyan borders + gradients).
 *
 * Three semantically distinct states, theme-aware (Pluvia palette, light/dark), with a fixed
 * visual-weight hierarchy (spec 2026-08-15 focus-feedback-v2, §1.4):
 *
 *   Focused (bright animated ring + background overlay) > Locked (thick solid ring)
 *   > Selected (quiet persistent tint).
 *
 * The bright/animated border is EXCLUSIVE to focus — selection states stay visible but
 * never compete with it in visual weight (§1.2).
 *
 * Pass `null` for no decoration. Apply AFTER the clip/background so the decoration draws
 * on top of the element.
 */
enum class GamepadFocusState {
    /** The node has focus: solid bright base ring + rotating primary/tertiary sweep +
     *  translucent accent background overlay — the strongest state. */
    Focused,

    /** The node is the current *selection* of the surface (chosen tab, active preset,
     *  enabled toggle): persistent accent background tint (alpha ≈ 0.10) with a hairline
     *  1dp border (alpha 0.5). Deliberately quiet (spec 2026-08-15 focus-feedback-v2, §1.2). */
    Selected,

    /** An adjustment row with an active A-lock: thick solid accent ring. The `●` indicator
     *  is drawn by the row itself (see `quick_menu_locked_indicator`). With [Selected]
     *  downgraded to a tint, this is the ONLY static solid border besides [Focused] (§1.4). */
    Locked,
}

@Composable
fun Modifier.gamepadFocus(
    state: GamepadFocusState?,
    shape: Shape,
    interactionSource: InteractionSource,
    width: Dp = 3.dp,
    durationMillis: Int = 1200,
    accentColor: Color = MaterialTheme.colorScheme.primary,
): Modifier {
    // Focus is collected HERE, in the caller's composition scope — not inside the ring.
    // The ring composable only exists while `state == Focused`, so a collector started
    // inside it would subscribe AFTER the Focus event was emitted (MutableInteractionSource
    // interactions has no replay) and would miss it forever — the ring never rendered
    // (root cause of the 2026-08-15 focus-feedback-v2 "lost focus" report). This collector
    // starts on the FIRST composition (even with state == null) and catches every event.
    val focused by interactionSource.collectIsFocusedAsState()
    if (state == null) return this
    return when (state) {
        GamepadFocusState.Focused -> animatedFocusRing(focused, shape, width, durationMillis, accentColor)
        // Selected is now a quiet persistent tint — the bright/animated border is
        // exclusive to focus (spec 2026-08-15 focus-feedback-v2, §1.2).
        GamepadFocusState.Selected -> selectedTint(accentColor, shape)
        GamepadFocusState.Locked -> border((width * 1.5f), accentColor, shape)
    }
}

/**
 * Focus + visual + accessibility semantics in one helper: applies [gamepadFocus] and makes the
 * node focusable with the given [interactionSource] (the source the caller already observes).
 */
@Composable
fun Modifier.gamepadFocusable(
    state: GamepadFocusState?,
    shape: Shape,
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
): Modifier = this
    .gamepadFocus(state, shape, interactionSource)
    .focusable(enabled = enabled, interactionSource = interactionSource)

/**
 * Animated focus ring (spec 2026-08-15 focus-feedback-v2, §1.1): a sweep-gradient border whose
 * colors rotate around the element while [focused] (tracked by [gamepadFocus] in the caller's
 * composition scope), drawn OVER a solid bright base ring so the focus never disappears into
 * the gradient's dark bands, plus a translucent accent overlay (alpha ≈ 0.08) that lights the
 * whole focused element — the background tint of the original Library, promoted into the
 * common language.
 *
 * Draw order: content → background overlay → solid base ring → rotating sweep. Only the
 * sweep moves; the shape stays put. Everything is clipped to [shape].
 */
@Composable
private fun Modifier.animatedFocusRing(
    focused: Boolean,
    shape: Shape,
    width: Dp,
    durationMillis: Int,
    accentColor: Color,
): Modifier {
    // The Animatable and its driver are created unconditionally (stable remember slots), so the
    // slot count doesn't change between focused/unfocused and the ring can't flicker on
    // recompose. The spin runs only while focused; losing focus cancels the effect and snaps
    // back to 0, so an unfocused ring schedules no animation frames.
    val angle = remember { Animatable(0f) }
    LaunchedEffect(focused) {
        if (focused) {
            angle.animateTo(
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
            )
        } else {
            angle.snapTo(0f)
        }
    }

    if (!focused) return this

    // First == last so the sweep loops seamlessly. Only primary and tertiary; secondary is a
    // near-black gray and would show as a dark band in the ring.
    val colors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.primary,
    )
    // "Primária clara" (§1.1): the base ring is lifted toward white so the ring reads as
    // bright even on dark surfaces instead of blending into them.
    val baseRingColor = lerp(accentColor, Color.White, BASE_RING_LIGHTEN_FRACTION)
    val overlayPaint = Paint().apply { color = accentColor.copy(alpha = FOCUSED_OVERLAY_ALPHA) }
    val strokePx = with(LocalDensity.current) { width.toPx() }

    return drawWithCache {
        // Rebuilt only when the size changes.
        val outline = shape.createOutline(size, layoutDirection, this)
        val bounds = Rect(Offset.Zero, size)
        val center = bounds.center
        val sweep = Brush.sweepGradient(colors, center)
        // Allocate the layer Paint once per cache build (size change), not once per frame.
        val layerPaint = Paint()
        val clipPath = Path().apply {
            when (val o = outline) {
                is Outline.Rectangle -> addRect(o.rect)
                is Outline.Rounded -> addRoundRect(o.roundRect)
                is Outline.Generic -> addPath(o.path)
            }
        }

        onDrawWithContent {
            drawContent()
            // Reading angle here keeps the animation in the draw phase, off recomposition.
            val canvas = drawContext.canvas

            // 1) Focused background overlay: the whole element lights up, not just its
            //    border (Library pattern — spec focus-feedback-v2, §1.1).
            canvas.save()
            canvas.clipPath(clipPath)
            canvas.drawRect(bounds, overlayPaint)
            canvas.restore()

            // 2) Ring: solid bright base under the rotating sweep.
            canvas.saveLayer(bounds, layerPaint)

            // Keep the ring's outer edge flush with the element.
            canvas.clipPath(clipPath)

            // Stroke at 2x width; the clipped-off outer half leaves an inward border of `width`.
            // The base is a solid bright primary, so the ring never vanishes between the
            // sweep's gradient colors (the old black mask let the sweep go dark).
            drawOutline(outline, color = baseRingColor, style = Stroke(strokePx * 2f))

            // Paint the gradient over the base ring at reduced alpha: the sweep is the visual
            // identity, but the solid base always shows through. Oversized circle covers any
            // rotation.
            rotate(angle.value, pivot = center) {
                drawCircle(
                    brush = sweep,
                    radius = size.maxDimension,
                    blendMode = BlendMode.SrcIn,
                    alpha = SWEEP_ALPHA,
                )
            }

            canvas.restore()
        }
    }
}

/**
 * Quiet persistent selection decoration (spec 2026-08-15 focus-feedback-v2, §1.2): accent
 * background tint (alpha ≈ 0.10) with a hairline 1dp border at 50% alpha, clipped to [shape].
 * Static by design — the bright/animated border is exclusive to [GamepadFocusState.Focused],
 * so a checked toggle, active tab or active preset stays visible without competing with the
 * focus ring in visual weight.
 */
private fun Modifier.selectedTint(
    accentColor: Color,
    shape: Shape,
): Modifier = drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val bounds = Rect(Offset.Zero, size)
    val clipPath = Path().apply {
        when (val o = outline) {
            is Outline.Rectangle -> addRect(o.rect)
            is Outline.Rounded -> addRoundRect(o.roundRect)
            is Outline.Generic -> addPath(o.path)
        }
    }
    val tintPaint = Paint().apply { color = accentColor.copy(alpha = SELECTED_TINT_ALPHA) }
    onDrawWithContent {
        drawContent()
        val canvas = drawContext.canvas
        canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawRect(bounds, tintPaint)
        canvas.restore()
    }
}
    .border(1.dp, accentColor.copy(alpha = SELECTED_HAIRLINE_ALPHA), shape)
