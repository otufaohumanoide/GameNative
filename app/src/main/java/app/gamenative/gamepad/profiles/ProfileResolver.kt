package app.gamenative.gamepad.profiles

import app.gamenative.gamepad.GamepadDevice

/**
 * Resolve o perfil EFETIVO de um device no momento do evento (spec 2026-08-13,
 * Parte I §7 — padrão holder vivo, lição C1 do hardening).
 *
 * Correção D7: a chave de escopo de jogo é o appId do container (estável), NUNCA o
 * deviceId (volátil entre sessões). Chave de device = mappingKey (vendor+product).
 */
object ProfileResolver {

    fun resolve(
        device: GamepadDevice,
        appId: String?,
        deviceStore: GamepadProfileStore,
        gameStore: GamepadProfileStore,
    ): GamepadProfile {
        val deviceProfile = deviceStore.load(device.mappingKey)
        val gameProfile = appId?.let { gameStore.load(it) }
        return GamepadProfileStore.merged(deviceProfile, gameProfile)
    }
}
