package app.gamenative.gamepad.processing

import app.gamenative.gamepad.GamepadButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * K2 (spec 2026-08-16-K2, §1.1): modo mouse — toggle por hold de START 750 ms
 * confirmado no release (moonlight :2371-2375), rampa quadrática
 * (convertRawStickAxisToPixelMovement :1837), report de 50 ms, A/B = cliques,
 * dpad = scroll com anti-repeat de 120 ms e sub-pixel (padrão G1).
 */
class MouseModeProcessorTest {

    private fun state() = MouseModeState()

    // ── Toggle ──

    @Test
    fun `START curto nao ativa o modo (release antes do limiar)`() {
        val s = state()
        assertEquals(MouseModeOutcome.None, MouseModeProcessor.onKey(s, GamepadButton.START, true, 1_000))
        assertEquals(MouseModeOutcome.None, MouseModeProcessor.onKey(s, GamepadButton.START, false, 1_300))
        assertTrue(!s.active)
    }

    @Test
    fun `START segurado 750ms ativa no release e o segundo toggle desativa`() {
        val s = state()
        assertEquals(MouseModeOutcome.None, MouseModeProcessor.onKey(s, GamepadButton.START, true, 1_000))
        assertEquals(MouseModeOutcome.Activated, MouseModeProcessor.onKey(s, GamepadButton.START, false, 1_800))
        assertTrue(s.active)

        // Segundo toggle: down → up após o limiar → Deactivated.
        assertEquals(MouseModeOutcome.None, MouseModeProcessor.onKey(s, GamepadButton.START, true, 2_000))
        assertEquals(MouseModeOutcome.Deactivated, MouseModeProcessor.onKey(s, GamepadButton.START, false, 2_900))
        assertTrue(!s.active)
    }

    @Test
    fun `crossing do limiar arma no meio (qualquer evento observa)`() {
        val s = state()
        assertEquals(MouseModeOutcome.None, MouseModeProcessor.onKey(s, GamepadButton.START, true, 1_000))
        // Um evento de stick depois do limiar arma (não flipa).
        assertNull(MouseModeProcessor.onStick(s, 0.5f, 0f, 2_000))
        assertTrue(s.armed)
        assertEquals(MouseModeOutcome.Activated, MouseModeProcessor.onKey(s, GamepadButton.START, false, 2_100))
    }

    @Test
    fun `desativar limpa o dpad segurado e o sub-pixel`() {
        val s = state()
        MouseModeProcessor.onKey(s, GamepadButton.START, true, 1_000)
        MouseModeProcessor.onKey(s, GamepadButton.START, false, 2_000)
        // Ativo: segura DPAD_UP e move o stick.
        assertEquals(MouseModeOutcome.MouseScroll(1), MouseModeProcessor.onKey(s, GamepadButton.DPAD_UP, true, 2_100))
        MouseModeProcessor.onStick(s, 0.5f, 0.5f, 2_200, MouseModeSpeed(basePps = 100f, gainPps = 0f))
        // Desativa.
        MouseModeProcessor.onKey(s, GamepadButton.START, true, 2_500)
        MouseModeProcessor.onKey(s, GamepadButton.START, false, 3_500)
        assertEquals(null, s.dpadHeld)
        assertEquals(0f, s.pixelState.remX)
        assertEquals(0f, s.pixelState.remY)
    }

    // ── Cliques ──

    @Test
    fun `A e B viram cliques enquanto ativo`() {
        val s = state()
        MouseModeProcessor.onKey(s, GamepadButton.START, true, 1_000)
        MouseModeProcessor.onKey(s, GamepadButton.START, false, 2_000)

        assertEquals(
            MouseModeOutcome.MouseButton(left = true, down = true),
            MouseModeProcessor.onKey(s, GamepadButton.FACE_BOTTOM, true, 2_100),
        )
        assertEquals(
            MouseModeOutcome.MouseButton(left = true, down = false),
            MouseModeProcessor.onKey(s, GamepadButton.FACE_BOTTOM, false, 2_200),
        )
        assertEquals(
            MouseModeOutcome.MouseButton(left = false, down = true),
            MouseModeProcessor.onKey(s, GamepadButton.FACE_RIGHT, true, 2_300),
        )
    }

    @Test
    fun `A fora do modo nao vira clique`() {
        val s = state()
        assertEquals(
            MouseModeOutcome.None,
            MouseModeProcessor.onKey(s, GamepadButton.FACE_BOTTOM, true, 1_000),
        )
    }

    // ── Scroll ──

    @Test
    fun `dpad vira scroll na borda de down e repete com janela de 120ms`() {
        val s = state()
        MouseModeProcessor.onKey(s, GamepadButton.START, true, 1_000)
        MouseModeProcessor.onKey(s, GamepadButton.START, false, 2_000)

        assertEquals(MouseModeOutcome.MouseScroll(1), MouseModeProcessor.onKey(s, GamepadButton.DPAD_UP, true, 2_100))
        assertEquals(GamepadButton.DPAD_UP, s.dpadHeld)
        // Repeat antes da janela → nada.
        assertEquals(MouseModeOutcome.None, MouseModeProcessor.onScrollRepeat(s, 2_180))
        // Repeat depois de 120 ms → +1.
        assertEquals(MouseModeOutcome.MouseScroll(1), MouseModeProcessor.onScrollRepeat(s, 2_300))
        // Solta → sem repeat.
        assertEquals(MouseModeOutcome.None, MouseModeProcessor.onKey(s, GamepadButton.DPAD_UP, false, 2_400))
        assertEquals(null, s.dpadHeld)
        assertEquals(MouseModeOutcome.None, MouseModeProcessor.onScrollRepeat(s, 2_500))
    }

    @Test
    fun `DPAD_DOWN rola para baixo`() {
        val s = state()
        MouseModeProcessor.onKey(s, GamepadButton.START, true, 1_000)
        MouseModeProcessor.onKey(s, GamepadButton.START, false, 2_000)
        assertEquals(MouseModeOutcome.MouseScroll(-1), MouseModeProcessor.onKey(s, GamepadButton.DPAD_DOWN, true, 2_100))
        assertEquals(MouseModeOutcome.MouseScroll(-1), MouseModeProcessor.onScrollRepeat(s, 2_300))
    }

    @Test
    fun `DPAD_LEFT RIGHT sao consumidos sem scroll (sem hscroll no sink)`() {
        val s = state()
        MouseModeProcessor.onKey(s, GamepadButton.START, true, 1_000)
        MouseModeProcessor.onKey(s, GamepadButton.START, false, 2_000)
        assertEquals(MouseModeOutcome.None, MouseModeProcessor.onKey(s, GamepadButton.DPAD_LEFT, true, 2_100))
        assertEquals(MouseModeOutcome.None, MouseModeProcessor.onKey(s, GamepadButton.DPAD_RIGHT, true, 2_200))
        assertEquals(MouseModeOutcome.None, MouseModeProcessor.onScrollRepeat(s, 2_400))
    }

    // ── Stick / rampa ──

    @Test
    fun `rampa quadratica do moonlight - borda 4px por report e meio 1px`() {
        val s = state()
        s.active = true
        // mag=1 → 80 px/s * 0.05 s = 4 px.
        val full = MouseModeProcessor.onStick(s, 1f, 0f, 1_000)
        assertEquals(MouseMove(4, 0), full)
        // mag=0.5 → x*80*0.25*0.05 = 0.5 px/report (moonlight: vx = x·4·mag²);
        // o sub-pixel acumula e emite 1 no segundo report.
        assertNull(MouseModeProcessor.onStick(s, 0.5f, 0f, 1_100))
        assertEquals(MouseMove(1, 0), MouseModeProcessor.onStick(s, 0.5f, 0f, 1_200))
    }

    @Test
    fun `gate de 50ms sem timer - eventos antes da janela nao emitem`() {
        val s = state()
        s.active = true
        assertEquals(MouseMove(4, 0), MouseModeProcessor.onStick(s, 1f, 0f, 1_000))
        assertNull(MouseModeProcessor.onStick(s, 1f, 0f, 1_030))
        assertEquals(MouseMove(4, 0), MouseModeProcessor.onStick(s, 1f, 0f, 1_100))
    }

    @Test
    fun `primeiro movimento apos stick parado nao salta (dt fixo de 50ms)`() {
        val s = state()
        s.active = true
        // Stick parado por 5 s: o primeiro movimento usa o período fixo (4 px),
        // nunca o dt real (que daria 400 px).
        assertEquals(MouseMove(4, 0), MouseModeProcessor.onStick(s, 1f, 0f, 6_000))
    }

    @Test
    fun `sub-pixel acumula movimento lento`() {
        val s = state()
        s.active = true
        // 8 px/s * 0.05 = 0.4 px/report × 3 = 1.2 px → emite 1 no terceiro report.
        val first = MouseModeProcessor.onStick(s, 1f, 0f, 1_000, MouseModeSpeed(basePps = 8f, gainPps = 0f))
        val second = MouseModeProcessor.onStick(s, 1f, 0f, 1_100, MouseModeSpeed(basePps = 8f, gainPps = 0f))
        val third = MouseModeProcessor.onStick(s, 1f, 0f, 1_200, MouseModeSpeed(basePps = 8f, gainPps = 0f))
        assertNull(first)
        assertNull(second)
        assertEquals(MouseMove(1, 0), third)
    }

    @Test
    fun `stick centrado nao emite`() {
        val s = state()
        s.active = true
        assertNull(MouseModeProcessor.onStick(s, 0f, 0f, 1_000))
        assertNull(MouseModeProcessor.onStick(s, 0.001f, 0.001f, 1_100))
    }

    @Test
    fun `stick desativado nao emite movimento`() {
        val s = state()
        assertNull(MouseModeProcessor.onStick(s, 1f, 1f, 1_000))
    }

    @Test
    fun `base e gain configuráveis mudam a rampa`() {
        val s = state()
        s.active = true
        // base 100 + gain 0 → 100 px/s * 0.05 = 5 px em qualquer deflexão.
        assertEquals(
            MouseMove(5, 0),
            MouseModeProcessor.onStick(s, 1f, 0f, 1_000, MouseModeSpeed(basePps = 100f, gainPps = 0f)),
        )
        assertEquals(
            MouseMove(2, 0),
            MouseModeProcessor.onStick(s, 1f, 0f, 1_100, MouseModeSpeed(basePps = 40f, gainPps = 0f)),
        )
    }
}
