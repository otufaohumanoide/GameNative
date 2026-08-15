package app.gamenative.gamepad.mapping

import app.gamenative.gamepad.FaceStyle
import app.gamenative.gamepad.GamepadAxis
import app.gamenative.gamepad.GamepadButton
import app.gamenative.gamepad.InputEvent
import kotlin.math.hypot

/**
 * Como desenhar o controle no mock clicável (spec 2026-08-16-B-remap-visual-ppsspp,
 * §1.1): geometria PURA, JVM-testável, zero `android.*` (pacote `gamepad/mapping`).
 * Inspirado no PPSSPP `ControlMappingScreen.cpp` (MockPSP: um hotspot por controle
 * clicável com flash ao vivo) e no DS4Windows (regiões clicáveis na imagem do pad).
 */
enum class HotspotKind {
    /** Botão de face — círculo com glyph (✕○□△ / ABXY / BAYX). */
    BUTTON_ROUND,

    /** Stick analógico (clique L3/R3) — círculo externo + base interna. */
    STICK,

    /** Gatilho (L2/R2) — trapézio/arco na borda superior. */
    TRIGGER,

    /** Bumper (L1/R1) — arco na borda superior. */
    BUMPER,

    /** Direção do D-pad — braço da cruz. */
    DPAD_DIR,

    /** Botão pequeno (SELECT/START/GUIDE) — pílula. */
    SMALL,
}

/**
 * Região clicável de um controle no desenho do pad. Coordenadas NORMALIZADAS 0..1 sobre
 * o bounding box do desenho (corpo canônico 480×220, spec §1.1). [control] é a chave
 * lógica — sempre um `GamepadButton.name` (mesmo vocabulário do campo `layers` do
 * perfil, o que torna o bridge visual→perfil trivial).
 */
data class VisualHotspot(
    val control: String,
    val cx: Float,
    val cy: Float,
    val r: Float,
    val kind: HotspotKind,
)

object ControllerVisualLayout {

    /** Corpo canônico do desenho (spec §1.1: "16:9-ish, corpo ~480×220"). */
    const val BODY_WIDTH = 480f
    const val BODY_HEIGHT = 220f
    const val BODY_ASPECT = BODY_WIDTH / BODY_HEIGHT

    /**
     * Layout por FaceStyle. PS/XBOX/GENERIC compartilham a geometria canônica — a
     * POSIÇÃO física não muda entre esses estilos (regra do [GamepadButton]); só as
     * labels variam. NINTENDO tem geometria própria (Switch Pro: sticks diagonais,
     * d-pad embaixo à esquerda, faces em cima à direita).
     */
    fun layoutFor(faceStyle: FaceStyle): List<VisualHotspot> = when (faceStyle) {
        FaceStyle.NINTENDO -> nintendoLayout()
        FaceStyle.PLAYSTATION, FaceStyle.XBOX, FaceStyle.GENERIC -> canonicalLayout()
    }

    /**
     * Hit-test determinístico: o hotspot mais próximo DENTRO do raio; null fora de todos.
     * Invariante do §1.1 (raio ≤ 45% da distância ao centro vizinho mais próximo) ⇒ os
     * círculos nunca se sobrepõem ⇒ 1 ponto nunca acerta 2 hotspots (verificado por
     * teste: ControllerVisualLayoutTest).
     */
    fun hitTest(x: Float, y: Float, hotspots: List<VisualHotspot>): VisualHotspot? {
        var best: VisualHotspot? = null
        var bestDistance = Float.MAX_VALUE
        for (hotspot in hotspots) {
            val distance = hypot(x - hotspot.cx, y - hotspot.cy)
            if (distance <= hotspot.r && distance < bestDistance) {
                best = hotspot
                bestDistance = distance
            }
        }
        return best
    }

    /**
     * Qual controle lógico o evento físico deve ACENDER no mock (§1.2 — flash ao vivo).
     * Pura e JVM-testável: botão acende o próprio botão; eixo de stick acende o stick;
     * eixo de gatilho acende o gatilho; deviceId diferente = null (o listener do dialog
     * filtra por device antes de acender).
     */
    fun flashControlFor(input: InputEvent, deviceId: Int): String? = when (input) {
        is InputEvent.ButtonDown -> if (input.deviceId == deviceId) input.button.name else null
        is InputEvent.ButtonUp -> if (input.deviceId == deviceId) input.button.name else null
        is InputEvent.AxisMotion -> if (input.deviceId == deviceId) {
            when (input.axis) {
                GamepadAxis.LEFT_X, GamepadAxis.LEFT_Y -> GamepadButton.LEFT_STICK.name
                GamepadAxis.RIGHT_X, GamepadAxis.RIGHT_Y -> GamepadButton.RIGHT_STICK.name
                GamepadAxis.LEFT_TRIGGER -> GamepadButton.LEFT_TRIGGER.name
                GamepadAxis.RIGHT_TRIGGER -> GamepadButton.RIGHT_TRIGGER.name
            }
        } else {
            null
        }
        else -> null
    }

    // ── Geometrias ──────────────────────────────────────────────────────────────
    // Layout desenhado sobre o corpo 480×220 (spec §1.1). Invariante do §1.1 mantida
    // por construção e verificada por teste: raio ≤ 45% da distância ao centro vizinho
    // mais próximo (⇒ círculos sem sobreposição; hit-test sem ambiguidade).

    /** Canônica (PS/XBOX/GENERIC): sticks simétricos embaixo, d-pad à esquerda, faces à direita. */
    private fun canonicalLayout(): List<VisualHotspot> = listOf(
        spot(GamepadButton.LEFT_TRIGGER, 0.165f, 0.11f, 0.055f, HotspotKind.TRIGGER),
        spot(GamepadButton.LEFT_BUMPER, 0.30f, 0.05f, 0.05f, HotspotKind.BUMPER),
        spot(GamepadButton.RIGHT_BUMPER, 0.70f, 0.05f, 0.05f, HotspotKind.BUMPER),
        spot(GamepadButton.RIGHT_TRIGGER, 0.835f, 0.11f, 0.055f, HotspotKind.TRIGGER),
        spot(GamepadButton.DPAD_UP, 0.16f, 0.36f, 0.05f, HotspotKind.DPAD_DIR),
        spot(GamepadButton.DPAD_LEFT, 0.08f, 0.57f, 0.05f, HotspotKind.DPAD_DIR),
        spot(GamepadButton.DPAD_RIGHT, 0.24f, 0.57f, 0.05f, HotspotKind.DPAD_DIR),
        spot(GamepadButton.DPAD_DOWN, 0.16f, 0.78f, 0.05f, HotspotKind.DPAD_DIR),
        spot(GamepadButton.LEFT_STICK, 0.39f, 0.57f, 0.065f, HotspotKind.STICK),
        spot(GamepadButton.RIGHT_STICK, 0.61f, 0.57f, 0.065f, HotspotKind.STICK),
        spot(GamepadButton.FACE_TOP, 0.84f, 0.36f, 0.05f, HotspotKind.BUTTON_ROUND),
        spot(GamepadButton.FACE_LEFT, 0.76f, 0.57f, 0.05f, HotspotKind.BUTTON_ROUND),
        spot(GamepadButton.FACE_RIGHT, 0.92f, 0.57f, 0.05f, HotspotKind.BUTTON_ROUND),
        spot(GamepadButton.FACE_BOTTOM, 0.84f, 0.78f, 0.05f, HotspotKind.BUTTON_ROUND),
        spot(GamepadButton.SELECT, 0.415f, 0.24f, 0.045f, HotspotKind.SMALL),
        spot(GamepadButton.START, 0.585f, 0.24f, 0.045f, HotspotKind.SMALL),
        spot(GamepadButton.GUIDE, 0.50f, 0.08f, 0.045f, HotspotKind.SMALL),
    )

    /** Switch Pro: LEFT_STICK em cima à esquerda, d-pad embaixo à esquerda, faces em cima à direita, RIGHT_STICK embaixo à direita. */
    private fun nintendoLayout(): List<VisualHotspot> = listOf(
        spot(GamepadButton.LEFT_TRIGGER, 0.165f, 0.11f, 0.055f, HotspotKind.TRIGGER),
        spot(GamepadButton.LEFT_BUMPER, 0.30f, 0.05f, 0.05f, HotspotKind.BUMPER),
        spot(GamepadButton.RIGHT_BUMPER, 0.70f, 0.05f, 0.05f, HotspotKind.BUMPER),
        spot(GamepadButton.RIGHT_TRIGGER, 0.835f, 0.11f, 0.055f, HotspotKind.TRIGGER),
        spot(GamepadButton.LEFT_STICK, 0.16f, 0.34f, 0.065f, HotspotKind.STICK),
        spot(GamepadButton.DPAD_UP, 0.155f, 0.60f, 0.05f, HotspotKind.DPAD_DIR),
        spot(GamepadButton.DPAD_LEFT, 0.075f, 0.76f, 0.05f, HotspotKind.DPAD_DIR),
        spot(GamepadButton.DPAD_RIGHT, 0.235f, 0.76f, 0.05f, HotspotKind.DPAD_DIR),
        spot(GamepadButton.DPAD_DOWN, 0.155f, 0.92f, 0.05f, HotspotKind.DPAD_DIR),
        spot(GamepadButton.FACE_TOP, 0.84f, 0.30f, 0.05f, HotspotKind.BUTTON_ROUND),
        spot(GamepadButton.FACE_LEFT, 0.76f, 0.47f, 0.05f, HotspotKind.BUTTON_ROUND),
        spot(GamepadButton.FACE_RIGHT, 0.92f, 0.47f, 0.05f, HotspotKind.BUTTON_ROUND),
        spot(GamepadButton.FACE_BOTTOM, 0.84f, 0.64f, 0.05f, HotspotKind.BUTTON_ROUND),
        spot(GamepadButton.RIGHT_STICK, 0.84f, 0.785f, 0.065f, HotspotKind.STICK),
        spot(GamepadButton.SELECT, 0.415f, 0.24f, 0.045f, HotspotKind.SMALL),
        spot(GamepadButton.START, 0.585f, 0.24f, 0.045f, HotspotKind.SMALL),
        spot(GamepadButton.GUIDE, 0.50f, 0.08f, 0.045f, HotspotKind.SMALL),
    )

    private fun spot(
        button: GamepadButton,
        cx: Float,
        cy: Float,
        r: Float,
        kind: HotspotKind,
    ): VisualHotspot = VisualHotspot(control = button.name, cx = cx, cy = cy, r = r, kind = kind)
}
