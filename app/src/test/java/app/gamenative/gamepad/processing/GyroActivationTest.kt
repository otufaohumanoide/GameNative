package app.gamenative.gamepad.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G5 (spec 2026-08-16-G-gyro-v2, §3): latch do toggle de ativação — a borda de
 * descida do botão inverte o latch (aperta liga, aperta de novo desliga); hold
 * continua lendo o botão pressionado; o recenter da borda off→on fica por conta do
 * GyroProcessor (sai de graça — nada testado aqui além da decisão pura).
 */
class GyroActivationTest {

    @Test
    fun `toggle flips the latch on every release edge`() {
        var latch = false
        latch = GyroActivation.onReleaseButton(latch, toggle = true)
        assertTrue(latch)
        latch = GyroActivation.onReleaseButton(latch, toggle = true)
        assertFalse(latch)
        latch = GyroActivation.onReleaseButton(latch, toggle = true)
        assertTrue(latch)
    }

    @Test
    fun `release edge does not flip the latch in hold mode`() {
        var latch = false
        latch = GyroActivation.onReleaseButton(latch, toggle = false)
        assertFalse(latch)
    }

    @Test
    fun `toggle mode reads the latch not the button`() {
        // Botão solto + latch aberto ⇒ ativo; botão segurado + latch fechado ⇒ inativo.
        assertTrue(GyroActivation.active(held = false, latch = true, toggle = true))
        assertFalse(GyroActivation.active(held = true, latch = false, toggle = true))
    }

    @Test
    fun `hold mode reads the button not the latch`() {
        assertTrue(GyroActivation.active(held = true, latch = false, toggle = false))
        assertFalse(GyroActivation.active(held = false, latch = true, toggle = false))
    }

    @Test
    fun `full press-release cycle in toggle mode`() {
        var held = false
        var latch = false
        // DOWN não muda o latch (a borda de descida é que flipa).
        held = true
        assertEquals(false, GyroActivation.active(held, latch, toggle = true))
        // UP com toggle ⇒ latch abre.
        held = false
        latch = GyroActivation.onReleaseButton(latch, toggle = true)
        assertEquals(true, GyroActivation.active(held, latch, toggle = true))
        // Segundo ciclo ⇒ latch fecha.
        held = true
        held = false
        latch = GyroActivation.onReleaseButton(latch, toggle = true)
        assertEquals(false, GyroActivation.active(held, latch, toggle = true))
    }
}
