package app.gamenative.gamepad.mapping

import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Adapter fino (spec 2026-08-13-onda2, §1.2): converte KeyEvent/MotionEvent do Android
 * nos records puros [RawKeyInput]/[RawAxisInput] que o [EventTranslator] consome.
 *
 * Apenas este arquivo e o GamepadHub tocam android.* no caminho de tradução — a lógica
 * de tradução (EventTranslator/MappingParser/DeadzoneProcessor) é 100% JVM-testável.
 */
object AndroidInputAdapter {

    fun toRawKey(event: KeyEvent): RawKeyInput = RawKeyInput(
        deviceId = event.deviceId,
        source = event.source,
        keyCode = event.keyCode,
        action = event.action,
        repeatCount = event.repeatCount,
    )

    /** Eixos relevantes para gamepad; ausentes ficam de fora do mapa (null-safe). */
    fun toRawAxis(event: MotionEvent): RawAxisInput {
        val axes = mutableMapOf<Int, Float>()
        val candidates = intArrayOf(
            MotionEvent.AXIS_X,
            MotionEvent.AXIS_Y,
            MotionEvent.AXIS_Z,
            MotionEvent.AXIS_RZ,
            MotionEvent.AXIS_HAT_X,
            MotionEvent.AXIS_HAT_Y,
            MotionEvent.AXIS_LTRIGGER,
            MotionEvent.AXIS_RTRIGGER,
            MotionEvent.AXIS_BRAKE,
            MotionEvent.AXIS_GAS,
        )
        for (axis in candidates) {
            axes[axis] = event.getAxisValue(axis)
        }
        return RawAxisInput(
            deviceId = event.deviceId,
            source = event.source,
            action = event.actionMasked,
            axisValues = axes,
        )
    }
}
