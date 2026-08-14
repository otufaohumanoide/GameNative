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

    /**
     * U2 (spec 2026-08-14-gamepad-u2-touchpad-mouse, §1.2): extrai o estado do TOUCHPAD
     * de um MotionEvent — absoluto normalizado AXIS_X/AXIS_Y + presença de dedo.
     * null = evento sem eixos de toque (não é touchpad) ou ação irrelevante.
     *
     * Chamado pelo MainActivity NO PONTO do gate de ghost input (antes do consume) —
     * o caminho do touchpad NUNCA entra no onAxis do hub (decisão Onda 2).
     */
    fun toRawTouch(event: MotionEvent): RawTouchInput? {
        val action = event.actionMasked
        val down = when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> false
            MotionEvent.ACTION_MOVE -> event.pointerCount > 0
            else -> return null
        }
        // Sem ponteiro não há o que ler (ACTION_MOVE sem dedo).
        if (event.pointerCount == 0) return null
        val x = event.getAxisValue(MotionEvent.AXIS_X).coerceIn(0f, 1f)
        val y = event.getAxisValue(MotionEvent.AXIS_Y).coerceIn(0f, 1f)
        return RawTouchInput(
            deviceId = event.deviceId,
            source = event.source,
            down = down,
            x = x,
            y = y,
            nowMs = android.os.SystemClock.uptimeMillis(),
        )
    }

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
