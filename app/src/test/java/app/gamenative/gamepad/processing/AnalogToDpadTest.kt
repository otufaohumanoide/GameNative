package app.gamenative.gamepad.processing

import app.gamenative.ui.component.GamepadStickDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * AnalogToDpad (spec 2026-08-13, Parte III — Processing + D6): assinatura do contrato
 * `(sample: DeadzoneResult, hatX, hatY, deadZone)`; hat (|v| > 0.5) vence; senão o
 * stick pós-deadzone; null = neutro. Convenção Android: Y negativo = cima.
 */
class AnalogToDpadTest {

    private fun sample(x: Float, y: Float, inDeadzone: Boolean = false) =
        DeadzoneResult(x = x, y = y, inDeadzone = inDeadzone)

    @Test
    fun `hat priority right returns right`() {
        val result = AnalogToDpad.sampleToDirection(sample(0f, 0f), hatX = 1f, hatY = 0f, deadZone = 0.3f)
        assertEquals(GamepadStickDirection.Right, result)
    }

    @Test
    fun `hat priority up returns up`() {
        val result = AnalogToDpad.sampleToDirection(sample(0f, 0f), hatX = 0f, hatY = -1f, deadZone = 0.3f)
        assertEquals(GamepadStickDirection.Up, result)
    }

    @Test
    fun `hat priority down returns down`() {
        val result = AnalogToDpad.sampleToDirection(sample(0f, 0f), hatX = 0f, hatY = 1f, deadZone = 0.3f)
        assertEquals(GamepadStickDirection.Down, result)
    }

    @Test
    fun `hat priority left returns left`() {
        val result = AnalogToDpad.sampleToDirection(sample(0f, 0f), hatX = -1f, hatY = 0f, deadZone = 0.3f)
        assertEquals(GamepadStickDirection.Left, result)
    }

    @Test
    fun `fallback to stick magnitude when hat inactive`() {
        val result = AnalogToDpad.sampleToDirection(sample(0.6f, 0f), hatX = 0f, hatY = 0f, deadZone = 0.3f)
        assertEquals(GamepadStickDirection.Right, result)
    }

    @Test
    fun `fallback stick up and down follow the android y convention`() {
        assertEquals(
            GamepadStickDirection.Up,
            AnalogToDpad.sampleToDirection(sample(0f, -0.6f), hatX = 0f, hatY = 0f, deadZone = 0.3f),
        )
        assertEquals(
            GamepadStickDirection.Down,
            AnalogToDpad.sampleToDirection(sample(0f, 0.6f), hatX = 0f, hatY = 0f, deadZone = 0.3f),
        )
    }

    @Test
    fun `fallback neutral stick returns null`() {
        val result = AnalogToDpad.sampleToDirection(sample(0.1f, 0.1f), hatX = 0f, hatY = 0f, deadZone = 0.3f)
        assertNull(result)
    }

    @Test
    fun `fallback stick within deadzone returns null`() {
        val result = AnalogToDpad.sampleToDirection(sample(0.1f, 0.1f, inDeadzone = true), hatX = 0f, hatY = 0f, deadZone = 0.3f)
        assertNull(result)
    }

    @Test
    fun `weak hat below threshold defers to the stick`() {
        val result = AnalogToDpad.sampleToDirection(sample(0.6f, 0f), hatX = 0.4f, hatY = 0f, deadZone = 0.3f)
        assertEquals(GamepadStickDirection.Right, result)
    }
}
