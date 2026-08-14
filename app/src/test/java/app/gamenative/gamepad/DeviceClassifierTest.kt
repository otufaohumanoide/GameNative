package app.gamenative.gamepad

import app.gamenative.gamepad.DeviceClassifier.DeviceFeatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Classificação pura (spec 2026-08-13, Parte I §1 — regra do ExternalController.
 * isGameController, adaptada para CONTROLLER/TOUCHPAD/SENSOR/UNKNOWN; redesign P5 —
 * spec 2026-08-14-gamepad-upgrades-pendencias, Parte V).
 *
 * P5: o Android não tem "um device = uma classe" — um InputDevice pode ser gamepad +
 * touchpad + sensor ao mesmo tempo (DS4 fundido no MIUI). POINTER NÃO rebaixa a
 * classe: vira a capacidade [hasTouchpad]. TOUCHPAD é só o sub-device puro (pointer
 * sem entrada de jogo).
 */
class DeviceClassifierTest {

    /** Perfil REAL do DS4 fundido no MIUI (dumpsys input, id 24 "Wireless Controller
     *  Touchpad", Mi 11 — sessão 2026-08-14): GAMEPAD|JOYSTICK|POINTER|SENSOR. */
    private val mergedDS4Sources =
        DeviceClassifier.SOURCE_GAMEPAD or
            DeviceClassifier.SOURCE_JOYSTICK or
            DeviceClassifier.SOURCE_CLASS_POINTER or
            0x04000000 // SOURCE_SENSOR

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
    fun `controller with pointer source is still a controller - P5`() {
        // ANTES (P4): POINTER rebaixava para TOUCHPAD → o DS4 fundido do MIUI perdia
        // TODA a entrada de jogo (joystick morto no menu — sessão on-device 2026-08-14).
        // AGORA (P5): entrada de jogo decide CONTROLLER; POINTER vira hasTouchpad.
        val result = DeviceClassifier.classify(
            features(sources = DeviceClassifier.SOURCE_GAMEPAD or DeviceClassifier.SOURCE_CLASS_POINTER),
        )
        assertEquals(DeviceClass.CONTROLLER, result)
    }

    @Test
    fun `merged DS4 from MIUI is a controller with touchpad capability - regression P5`() {
        val f = features(sources = mergedDS4Sources, hasAxisX = true, hasAxisY = true)
        assertEquals(DeviceClass.CONTROLLER, DeviceClassifier.classify(f))
        assertTrue("merged DS4 tem POINTER → hasTouchpad", DeviceClassifier.hasTouchpad(f))
    }

    @Test
    fun `pure touchpad sub-device without game input is touchpad - P5`() {
        // Kernel que SEPARA os devices: o sub-device touchpad não tem entrada de jogo.
        val f = features(
            sources = DeviceClassifier.SOURCE_CLASS_POINTER or 0x00100002 /* SOURCE_TOUCHPAD */,
            hasAnyFaceButton = false,
        )
        assertEquals(DeviceClass.TOUCHPAD, DeviceClassifier.classify(f))
        assertTrue(DeviceClassifier.hasTouchpad(f))
    }

    @Test
    fun `touchscreen is not a touchpad - regression P5 (fts_ts Mi 11)`() {
        // O touchscreen do Mi 11 (fts_ts, POINTER-class sem entrada de jogo) virou
        // TOUCHPAD por engano na sessão 2026-08-14 (12:27) — era UNKNOWN antes do P5.
        val f = features(
            sources = DeviceClassifier.SOURCE_TOUCHSCREEN,
            hasAnyFaceButton = false,
        )
        assertEquals(DeviceClass.UNKNOWN, DeviceClassifier.classify(f))
    }

    @Test
    fun `mouse is not a touchpad - regression P5`() {
        val f = features(
            sources = DeviceClassifier.SOURCE_MOUSE,
            hasAnyFaceButton = false,
        )
        assertEquals(DeviceClass.UNKNOWN, DeviceClassifier.classify(f))
    }

    @Test
    fun `touchpad capability is false without pointer source`() {
        val f = features(sources = DeviceClassifier.SOURCE_GAMEPAD)
        assertFalse(DeviceClassifier.hasTouchpad(f))
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
