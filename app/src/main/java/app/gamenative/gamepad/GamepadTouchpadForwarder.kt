package app.gamenative.gamepad

import android.view.InputDevice
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.gamepad.mapping.RawTouchInput
import app.gamenative.gamepad.processing.TouchSample
import app.gamenative.gamepad.processing.TouchpadConfig
import app.gamenative.gamepad.processing.TouchpadProcessor
import app.gamenative.gamepad.processing.TouchpadState
import com.winlator.xserver.Pointer
import timber.log.Timber

/**
 * Forwarder do touchpad do controle → mouse (spec 2026-08-14-gamepad-u2-touchpad-mouse,
 * §1.3): lê o touchpad NO PONTO DO GATE de ghost input do MainActivity (V7 — o gate
 * continua consumindo; o forwarder é o ÚNICO caminho criado, antes do consume).
 *
 * Regras:
 * - Só age com `PrefManager.gamepadTouchpadMouseEnabled` (default OFF — opt-in) E o
 *   gate `ignoreControllerTouchpad` ativo (o mesmo ponto de plug: com o gate desligado
 *   o touchpad vira input normal e NUNCA vira mouse — sem double-input).
 * - Fonte própria, fora do `onKey/onAxis` do hub (V3): chamada síncrona no dispatch,
 *   sem coroutine e sem timer — o touchpad já entrega ~60-100 Hz de ACTION_MOVE; o
 *   delta é injetado por evento.
 * - Estado por device ([TouchpadState]) morto em [onDeviceRemoved] (V6).
 * - Sink de injeção trocável: [XServerTouchpadMouseSink] (jogo rodando via
 *   `PluviaApp.xServerView`); sem XServer o sink é no-op (touchpad só atua no jogo).
 */
class GamepadTouchpadForwarder {

    interface TouchpadMouseSink {
        fun move(deltaX: Int, deltaY: Int)
        fun click()
    }

    private val states = mutableMapOf<Int, TouchpadState>()

    @Volatile
    var sink: TouchpadMouseSink = NoopSink

    /** Chamado pelo MainActivity ANTES do consume do gate. Retorna true se processou. */
    fun onRawTouch(raw: RawTouchInput): Boolean {
        if (!PrefManager.gamepadTouchpadMouseEnabled) return false
        if (!PrefManager.ignoreControllerTouchpad) return false
        if ((raw.source and InputDevice.SOURCE_CLASS_POINTER) == 0) return false
        val device = PluviaApp.gamepadHub.deviceFor(raw.deviceId) ?: return false
        if (device.deviceClass == DeviceClass.UNKNOWN || device.deviceClass == DeviceClass.SENSOR) return false

        val state = states.getOrPut(raw.deviceId) { TouchpadState() }
        val decision = TouchpadProcessor.process(
            sample = TouchSample(down = raw.down, x = raw.x, y = raw.y, nowMs = raw.nowMs),
            state = state,
            config = TouchpadConfig(sensitivity = PrefManager.gamepadTouchpadSensitivity),
        )
        if (decision.deltaX != 0 || decision.deltaY != 0) {
            sink.move(decision.deltaX, decision.deltaY)
        }
        if (decision.tap) {
            sink.click()
        }
        return true
    }

    /** V6: estado do device morto no removeDevice (mesmo padrão buttonStates do hub). */
    fun onDeviceRemoved(deviceId: Int) {
        states.remove(deviceId)
    }

    private object NoopSink : TouchpadMouseSink {
        override fun move(deltaX: Int, deltaY: Int) {}
        override fun click() {}
    }
}

/**
 * Sink de injeção no XServer (U2 §1.3): converte deltas do processador em
 * `injectPointerMoveDelta` e tap em clique esquerdo. Consulta `PluviaApp.xServerView`
 * NO CALL TIME — sem container rodando (xServerView null) o sink é no-op.
 */
class XServerTouchpadMouseSink : GamepadTouchpadForwarder.TouchpadMouseSink {

    override fun move(deltaX: Int, deltaY: Int) {
        val xServer = PluviaApp.xServerView?.getxServer() ?: return
        if (deltaX == 0 && deltaY == 0) return
        xServer.injectPointerMoveDelta(deltaX, deltaY)
    }

    override fun click() {
        val xServer = PluviaApp.xServerView?.getxServer() ?: return
        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT)
        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT)
        Timber.d("GamepadTouchpad: tap -> click")
    }
}
