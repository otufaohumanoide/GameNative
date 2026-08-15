package app.gamenative.gamepad.mapping

import app.gamenative.gamepad.FaceStyle
import app.gamenative.gamepad.GamepadAxis
import app.gamenative.gamepad.GamepadButton
import app.gamenative.gamepad.InputEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * Gate do spec 2026-08-16-B-remap-visual-ppsspp §3: hit-test de TODOS os controles por
 * FaceStyle (PS/XBOX/NINTENDO/GENERIC), sem ambiguidade (1 ponto nunca acerta 2
 * hotspots), fora do desenho = null. Geometria pura — JVM, sem android.*.
 */
class ControllerVisualLayoutTest {

    private val allControls: Set<String> = GamepadButton.entries.map { it.name }.toSet()

    // ── Cobertura ────────────────────────────────────────────────────────────────

    @Test
    fun `layout covers every control exactly once for every face style`() {
        for (style in FaceStyle.entries) {
            val hotspots = ControllerVisualLayout.layoutFor(style)
            assertEquals(
                "estilo $style: controles cobertos",
                allControls,
                hotspots.map { it.control }.toSet(),
            )
        }
    }

    @Test
    fun `all hotspots fit inside the normalized drawing box`() {
        for (style in FaceStyle.entries) {
            for (hotspot in ControllerVisualLayout.layoutFor(style)) {
                assertTrue(
                    "estilo $style: ${hotspot.control} sai do box (x=${hotspot.cx}, y=${hotspot.cy}, r=${hotspot.r})",
                    hotspot.cx - hotspot.r >= 0f && hotspot.cx + hotspot.r <= 1f &&
                        hotspot.cy - hotspot.r >= 0f && hotspot.cy + hotspot.r <= 1f,
                )
            }
        }
    }

    // ── Hit-test por controle, por FaceStyle ─────────────────────────────────────

    @Test
    fun `hit test at each hotspot center returns that control for every face style`() {
        for (style in FaceStyle.entries) {
            val hotspots = ControllerVisualLayout.layoutFor(style)
            for (hotspot in hotspots) {
                assertEquals(
                    "estilo $style: centro de ${hotspot.control}",
                    hotspot,
                    ControllerVisualLayout.hitTest(hotspot.cx, hotspot.cy, hotspots),
                )
            }
        }
    }

    @Test
    fun `hit test rejects a point just outside the radius`() {
        for (style in FaceStyle.entries) {
            val hotspots = ControllerVisualLayout.layoutFor(style)
            val faceBottom = hotspots.first { it.control == GamepadButton.FACE_BOTTOM.name }
            assertNull(
                ControllerVisualLayout.hitTest(
                    faceBottom.cx + faceBottom.r + 0.001f,
                    faceBottom.cy,
                    hotspots,
                ),
            )
        }
    }

    @Test
    fun `hit test outside the drawing is null for every face style`() {
        for (style in FaceStyle.entries) {
            val hotspots = ControllerVisualLayout.layoutFor(style)
            assertNull(ControllerVisualLayout.hitTest(0.5f, 0.99f, hotspots))
            assertNull(ControllerVisualLayout.hitTest(0.5f, 0.01f, hotspots))
            assertNull(ControllerVisualLayout.hitTest(-0.5f, -0.5f, hotspots))
            assertNull(ControllerVisualLayout.hitTest(1.5f, 0.5f, hotspots))
        }
    }

    // ── Sem ambiguidade (§1.1: 1 ponto nunca acerta 2 hotspots) ─────────────────

    @Test
    fun `no two hotspot circles overlap in any face style`() {
        for (style in FaceStyle.entries) {
            val hotspots = ControllerVisualLayout.layoutFor(style)
            for (a in hotspots) {
                for (b in hotspots) {
                    if (a === b) continue
                    val distance = hypot(a.cx - b.cx, a.cy - b.cy)
                    assertTrue(
                        "estilo $style: ${a.control} vs ${b.control} sobrepõem (d=$distance <= rA+rB=${a.r + b.r})",
                        distance > a.r + b.r,
                    )
                }
            }
        }
    }

    @Test
    fun `every radius respects 45 percent of the nearest center distance`() {
        for (style in FaceStyle.entries) {
            val hotspots = ControllerVisualLayout.layoutFor(style)
            for (a in hotspots) {
                val nearest = hotspots
                    .filter { it !== a }
                    .minOf { hypot(a.cx - it.cx, a.cy - it.cy) }
                assertTrue(
                    "estilo $style: ${a.control} r=${a.r} > 45% de $nearest",
                    a.r <= 0.45f * nearest,
                )
            }
        }
    }

    @Test
    fun `grid scan never hits two hotspots at a single point`() {
        for (style in FaceStyle.entries) {
            val hotspots = ControllerVisualLayout.layoutFor(style)
            var x = 0f
            while (x <= 1f) {
                var y = 0f
                while (y <= 1f) {
                    val hits = hotspots.filter { hypot(x - it.cx, y - it.cy) <= it.r }
                    assertTrue(
                        "estilo $style: ponto ($x,$y) acerta ${hits.map { it.control }}",
                        hits.size <= 1,
                    )
                    y += 0.02f
                }
                x += 0.02f
            }
        }
    }

    // ── Geometria por estilo ─────────────────────────────────────────────────────

    @Test
    fun `nintendo layout is the switch geometry and canonical keeps the symmetric pad`() {
        val nintendo = ControllerVisualLayout.layoutFor(FaceStyle.NINTENDO)
        val canonical = ControllerVisualLayout.layoutFor(FaceStyle.XBOX)
        fun listOf(list: List<VisualHotspot>, control: GamepadButton) =
            list.first { it.control == control.name }
        // Switch Pro: LEFT_STICK em cima do d-pad; canônico: d-pad em cima do stick esquerdo.
        assertTrue(
            "Nintendo: LEFT_STICK deve ficar ACIMA do d-pad",
            listOf(nintendo, GamepadButton.LEFT_STICK).cy < listOf(nintendo, GamepadButton.DPAD_UP).cy,
        )
        assertTrue(
            "Canônico: LEFT_STICK deve ficar ABAIXO do d-pad",
            listOf(canonical, GamepadButton.LEFT_STICK).cy > listOf(canonical, GamepadButton.DPAD_UP).cy,
        )
        // Switch Pro: RIGHT_STICK embaixo das faces; canônico: stick simétrico ao esquerdo.
        assertTrue(
            "Nintendo: RIGHT_STICK deve ficar ABAIXO das faces",
            listOf(nintendo, GamepadButton.RIGHT_STICK).cy > listOf(nintendo, GamepadButton.FACE_BOTTOM).cy,
        )
        assertTrue(
            "Canônico: RIGHT_STICK ao lado (mesma linha) do LEFT_STICK",
            kotlin.math.abs(listOf(canonical, GamepadButton.RIGHT_STICK).cy - listOf(canonical, GamepadButton.LEFT_STICK).cy) < 0.001f,
        )
        // As três geometrias não-Nintendo são idênticas (só as labels variam — regra do FaceStyle).
        assertEquals(
            ControllerVisualLayout.layoutFor(FaceStyle.PLAYSTATION),
            ControllerVisualLayout.layoutFor(FaceStyle.XBOX),
        )
        assertEquals(
            ControllerVisualLayout.layoutFor(FaceStyle.XBOX),
            ControllerVisualLayout.layoutFor(FaceStyle.GENERIC),
        )
    }

    // ── Flash ao vivo (§1.2 — mapeamento puro evento → controle) ─────────────────

    @Test
    fun `flash control maps buttons of the matching device only`() {
        assertEquals(
            GamepadButton.FACE_BOTTOM.name,
            ControllerVisualLayout.flashControlFor(InputEvent.ButtonDown(7, GamepadButton.FACE_BOTTOM), 7),
        )
        assertEquals(
            GamepadButton.DPAD_UP.name,
            ControllerVisualLayout.flashControlFor(InputEvent.ButtonUp(7, GamepadButton.DPAD_UP), 7),
        )
        assertNull(
            ControllerVisualLayout.flashControlFor(InputEvent.ButtonDown(8, GamepadButton.FACE_BOTTOM), 7),
        )
    }

    @Test
    fun `flash control maps stick and trigger axes`() {
        assertEquals(
            GamepadButton.LEFT_STICK.name,
            ControllerVisualLayout.flashControlFor(InputEvent.AxisMotion(7, GamepadAxis.LEFT_X, 0.5f), 7),
        )
        assertEquals(
            GamepadButton.LEFT_STICK.name,
            ControllerVisualLayout.flashControlFor(InputEvent.AxisMotion(7, GamepadAxis.LEFT_Y, -0.5f), 7),
        )
        assertEquals(
            GamepadButton.RIGHT_STICK.name,
            ControllerVisualLayout.flashControlFor(InputEvent.AxisMotion(7, GamepadAxis.RIGHT_X, 0.5f), 7),
        )
        assertEquals(
            GamepadButton.LEFT_TRIGGER.name,
            ControllerVisualLayout.flashControlFor(InputEvent.AxisMotion(7, GamepadAxis.LEFT_TRIGGER, 0.9f), 7),
        )
        assertEquals(
            GamepadButton.RIGHT_TRIGGER.name,
            ControllerVisualLayout.flashControlFor(InputEvent.AxisMotion(7, GamepadAxis.RIGHT_TRIGGER, 0.9f), 7),
        )
        // device errado → nunca acende
        assertNull(
            ControllerVisualLayout.flashControlFor(InputEvent.AxisMotion(9, GamepadAxis.LEFT_X, 0.5f), 7),
        )
    }

    @Test
    fun `flash control ignores non input events`() {
        assertNull(
            ControllerVisualLayout.flashControlFor(
                InputEvent.SensorUpdate(7, 0f, 0f, 0f, 0f, 0f, 0f),
                7,
            ),
        )
        assertNull(
            ControllerVisualLayout.flashControlFor(InputEvent.TouchpadMotion(7, 0.1f, 0.2f), 7),
        )
    }
}
