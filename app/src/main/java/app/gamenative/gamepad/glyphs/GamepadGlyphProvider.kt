package app.gamenative.gamepad.glyphs

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.gamepad.FaceStyle
import app.gamenative.gamepad.GamepadButton

/**
 * Label por botão × face style (spec 2026-08-13, Passo 7 — D8): as labels vivem em
 * resources (EN + pt-rBR), NUNCA hardcoded, e a UI desenha o símbolo conforme o estilo
 * do controle (A/B/X/Y no Xbox, ✕/◯/▢/△ no PlayStation, A/B/X/Y no Nintendo — as
 * LABELS variam, a POSIÇÃO semântica não).
 */
object GamepadGlyphProvider {

    @StringRes
    fun labelRes(button: GamepadButton, faceStyle: FaceStyle): Int = when (faceStyle) {
        FaceStyle.XBOX -> when (button) {
            GamepadButton.FACE_BOTTOM -> R.string.gamepad_glyph_xbox_face_bottom
            GamepadButton.FACE_RIGHT -> R.string.gamepad_glyph_xbox_face_right
            GamepadButton.FACE_LEFT -> R.string.gamepad_glyph_xbox_face_left
            GamepadButton.FACE_TOP -> R.string.gamepad_glyph_xbox_face_top
            GamepadButton.LEFT_BUMPER -> R.string.gamepad_glyph_xbox_bumper_left
            GamepadButton.RIGHT_BUMPER -> R.string.gamepad_glyph_xbox_bumper_right
            GamepadButton.LEFT_TRIGGER -> R.string.gamepad_glyph_xbox_trigger_left
            GamepadButton.RIGHT_TRIGGER -> R.string.gamepad_glyph_xbox_trigger_right
            GamepadButton.LEFT_STICK -> R.string.gamepad_glyph_xbox_stick_left
            GamepadButton.RIGHT_STICK -> R.string.gamepad_glyph_xbox_stick_right
            GamepadButton.START -> R.string.gamepad_glyph_xbox_start
            GamepadButton.SELECT -> R.string.gamepad_glyph_xbox_select
            GamepadButton.GUIDE -> R.string.gamepad_glyph_xbox_guide
            GamepadButton.DPAD_UP -> R.string.gamepad_glyph_dpad_up
            GamepadButton.DPAD_DOWN -> R.string.gamepad_glyph_dpad_down
            GamepadButton.DPAD_LEFT -> R.string.gamepad_glyph_dpad_left
            GamepadButton.DPAD_RIGHT -> R.string.gamepad_glyph_dpad_right
            // K3 (spec 2026-08-16-K3, §1.4): extras (MISC1/paddles/touchpad) sem
            // label por estilo — caem no label genérico (fora do escopo desta fase).
            else -> R.string.gamepad_glyph_generic_other
        }
        FaceStyle.PLAYSTATION -> when (button) {
            GamepadButton.FACE_BOTTOM -> R.string.gamepad_glyph_ps_face_bottom
            GamepadButton.FACE_RIGHT -> R.string.gamepad_glyph_ps_face_right
            GamepadButton.FACE_LEFT -> R.string.gamepad_glyph_ps_face_left
            GamepadButton.FACE_TOP -> R.string.gamepad_glyph_ps_face_top
            GamepadButton.LEFT_BUMPER -> R.string.gamepad_glyph_ps_bumper_left
            GamepadButton.RIGHT_BUMPER -> R.string.gamepad_glyph_ps_bumper_right
            GamepadButton.LEFT_TRIGGER -> R.string.gamepad_glyph_ps_trigger_left
            GamepadButton.RIGHT_TRIGGER -> R.string.gamepad_glyph_ps_trigger_right
            GamepadButton.LEFT_STICK -> R.string.gamepad_glyph_ps_stick_left
            GamepadButton.RIGHT_STICK -> R.string.gamepad_glyph_ps_stick_right
            GamepadButton.START -> R.string.gamepad_glyph_ps_start
            GamepadButton.SELECT -> R.string.gamepad_glyph_ps_select
            GamepadButton.GUIDE -> R.string.gamepad_glyph_ps_guide
            GamepadButton.DPAD_UP -> R.string.gamepad_glyph_dpad_up
            GamepadButton.DPAD_DOWN -> R.string.gamepad_glyph_dpad_down
            GamepadButton.DPAD_LEFT -> R.string.gamepad_glyph_dpad_left
            GamepadButton.DPAD_RIGHT -> R.string.gamepad_glyph_dpad_right
            // K3 (spec 2026-08-16-K3, §1.4): extras (MISC1/paddles/touchpad) sem
            // label por estilo — caem no label genérico (fora do escopo desta fase).
            else -> R.string.gamepad_glyph_generic_other
        }
        FaceStyle.NINTENDO -> when (button) {
            GamepadButton.FACE_BOTTOM -> R.string.gamepad_glyph_nintendo_face_bottom
            GamepadButton.FACE_RIGHT -> R.string.gamepad_glyph_nintendo_face_right
            GamepadButton.FACE_LEFT -> R.string.gamepad_glyph_nintendo_face_left
            GamepadButton.FACE_TOP -> R.string.gamepad_glyph_nintendo_face_top
            GamepadButton.LEFT_BUMPER -> R.string.gamepad_glyph_nintendo_bumper_left
            GamepadButton.RIGHT_BUMPER -> R.string.gamepad_glyph_nintendo_bumper_right
            GamepadButton.LEFT_TRIGGER -> R.string.gamepad_glyph_nintendo_trigger_left
            GamepadButton.RIGHT_TRIGGER -> R.string.gamepad_glyph_nintendo_trigger_right
            GamepadButton.LEFT_STICK -> R.string.gamepad_glyph_nintendo_stick_left
            GamepadButton.RIGHT_STICK -> R.string.gamepad_glyph_nintendo_stick_right
            GamepadButton.START -> R.string.gamepad_glyph_nintendo_start
            GamepadButton.SELECT -> R.string.gamepad_glyph_nintendo_select
            GamepadButton.GUIDE -> R.string.gamepad_glyph_nintendo_guide
            GamepadButton.DPAD_UP -> R.string.gamepad_glyph_dpad_up
            GamepadButton.DPAD_DOWN -> R.string.gamepad_glyph_dpad_down
            GamepadButton.DPAD_LEFT -> R.string.gamepad_glyph_dpad_left
            GamepadButton.DPAD_RIGHT -> R.string.gamepad_glyph_dpad_right
            // K3 (spec 2026-08-16-K3, §1.4): extras (MISC1/paddles/touchpad) sem
            // label por estilo — caem no label genérico (fora do escopo desta fase).
            else -> R.string.gamepad_glyph_generic_other
        }
        FaceStyle.GENERIC -> when (button) {
            GamepadButton.FACE_BOTTOM -> R.string.gamepad_glyph_generic_face_bottom
            GamepadButton.FACE_RIGHT -> R.string.gamepad_glyph_generic_face_right
            GamepadButton.FACE_LEFT -> R.string.gamepad_glyph_generic_face_left
            GamepadButton.FACE_TOP -> R.string.gamepad_glyph_generic_face_top
            GamepadButton.DPAD_UP -> R.string.gamepad_glyph_dpad_up
            GamepadButton.DPAD_DOWN -> R.string.gamepad_glyph_dpad_down
            GamepadButton.DPAD_LEFT -> R.string.gamepad_glyph_dpad_left
            GamepadButton.DPAD_RIGHT -> R.string.gamepad_glyph_dpad_right
            else -> R.string.gamepad_glyph_generic_other
        }
    }
}

/**
 * Glyph composable: um símbolo centralizado em [size]×[size], com a label do recurso.
 * Sem dependências externas (accompanist removido — D8) e sem labels hardcoded.
 */
@Composable
fun GamepadGlyph(
    button: GamepadButton,
    faceStyle: FaceStyle,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val label = stringResource(GamepadGlyphProvider.labelRes(button, faceStyle))
    val fontSize = with(LocalDensity.current) { (size * 0.45f).coerceAtLeast(10.dp).toSp() }
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Text(
            text = label,
            fontSize = fontSize,
            color = contentColor,
            maxLines = 1,
        )
    }
}
