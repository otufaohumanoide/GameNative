package app.gamenative.events

import app.gamenative.gamepad.GamepadDevice
import app.gamenative.gamepad.InputEvent

/**
 * Wrappers de transporte do bus síncrono do app (spec 2026-08-13 — gamepad-universal-
 * correcao, Parte III "Bus"). O hot path é multicast e síncrono: `GamepadInputEvent`
 * carrega um [InputEvent] lógico e NÃO altera o retorno do dispatch do Android (o
 * retorno Boolean é apenas o "algum consumidor processou?").
 *
 * NOTA de implementação: `Event<T>` (EventDispatcher.kt) é SEALED — implementações só
 * podem viver no mesmo pacote. O spec pediu para não editar AndroidEvent.kt, então
 * estes wrappers são um arquivo NOVO ao lado dele (nada do arquivo existente muda).
 */
class GamepadInputEvent(val input: InputEvent) : Event<Boolean>
class GamepadDeviceAddedEvent(val device: GamepadDevice) : Event<Unit>
class GamepadDeviceRemovedEvent(val deviceId: Int) : Event<Unit>
