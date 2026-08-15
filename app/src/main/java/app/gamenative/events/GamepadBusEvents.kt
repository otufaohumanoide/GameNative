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

/**
 * F3.1 (spec 2026-08-15-input-core-avancado): ativação/desativação de camada do
 * perfil (U3) — consumidores como o Radial Menu abrem/fecham no gatilho da camada.
 * Emitido pelo GamepadHub.resolveLayerTriggers (main thread, síncrono).
 */
class GamepadLayerEvent(val deviceId: Int, val layer: String, val activated: Boolean) : Event<Unit>

/**
 * D (spec 2026-08-16-D-touchpad-swipes-macros): swipe do touchpad mapeado para
 * "abrir radial" (binding `RadialMacroKey(SWIPE_OPEN_RADIAL)`) — emitido pelo
 * GamepadTouchpadForwarder (main thread, síncrono), consumido pelo RadialMenuHost:
 * abre o menu com o pause/resume par-e-par do caminho de camada, SEM exigir
 * triggerLayer configurado. O caminho GamepadLayerEvent permanece intacto.
 */
class GamepadSwipeEvent(val deviceId: Int) : Event<Unit>
