package app.gamenative.gamepad

import app.gamenative.gamepad.DeviceClassifier.DeviceFeatures
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Classificação pura (spec 2026-08-13, Parte I §1 — regra do ExternalController.
 * isGameController, adaptada para CONTROLLER/TOUCHPAD/SENSOR/UNKNOWN).
 */
class DeviceClassifierTest {

    private fun features(
        isVirtual: Boolean = false,
        sources: Int = DeviceClassifier.SOURCE_GAMEPAD,
        hasAnyFaceButton: Boolean = true,
        hasAxisX: Boolean = false,
        hasAxisY: Boolean = false,
    ) = DeviceFeatures(isVirtual, sources, hasAnyFaceButton, hasAxisX, hasAxisY)

    @Test
    fun `gamepad source with face buttons is a controller`() {
        val result = DeviceClassifier.classify(features())
        assertEquals(DeviceClass.CONTROLLER, result)
    }

    @Test
    fun `joystick with axes is a controller`() {
        val result = DeviceClassifier.classify(
            features(sources = DeviceClassifier.SOURCE_JOYSTICK, hasAxisX = true),
        )
        assertEquals(DeviceClass.CONTROLLER, result)
    }

    @Test
    fun `controller with pointer source is a touchpad`() {
        val result = DeviceClassifier.classify(
            features(sources = DeviceClassifier.SOURCE_GAMEPAD or DeviceClassifier.SOURCE_CLASS_POINTER),
        )
        assertEquals(DeviceClass.TOUCHPAD, result)
    }

    @Test
    fun `virtual device is a sensor - never a controller`() {
        val result = DeviceClassifier.classify(features(isVirtual = true))
        assertEquals(DeviceClass.SENSOR, result)
    }

    @Test
    fun `keyboard only is unknown`() {
        val result = DeviceClassifier.classify(
            features(sources = 0x00000101 /* SOURCE_KEYBOARD */, hasAnyFaceButton = false),
        )
        assertEquals(DeviceClass.UNKNOWN, result)
    }

    @Test
    fun `joystick without axes is unknown`() {
        val result = DeviceClassifier.classify(
            features(sources = DeviceClassifier.SOURCE_JOYSTICK, hasAnyFaceButton = false),
        )
        assertEquals(DeviceClass.UNKNOWN, result)
    }

    @Test
    fun `gamepad source without face buttons is unknown`() {
        val result = DeviceClassifier.classify(features(hasAnyFaceButton = false))
        assertEquals(DeviceClass.UNKNOWN, result)
    }
}
