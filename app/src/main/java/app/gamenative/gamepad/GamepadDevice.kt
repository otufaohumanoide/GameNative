package app.gamenative.gamepad

import app.gamenative.gamepad.mapping.GamepadCapabilities

/**
 * A IDENTIDADE de um controle conectado (spec 2026-08-13, Parte I §1).
 *
 * Os três identificadores do Android têm papéis distintos e NÃO podem ser misturados:
 * - [deviceId]: índice efêmero do InputDevice — roteamento NAQUELA sessão (volátil).
 * - [descriptor]: identidade estável do hardware — chave de persistência de perfil.
 * - [vendorId]/[productId]: identificam o MODELO (ex.: 054c:09cc = DS4) — chave do
 *   MappingDatabase. Juntos viram [mappingKey] (hex 8 minúsculo, formato idêntico ao
 *   RetroArch autoconfig / GUID da SDL).
 *
 * [name] é APENAS display — nunca usado como chave.
 */
data class GamepadDevice(
    val deviceId: Int,
    val descriptor: String,
    val vendorId: Int,
    val productId: Int,
    val name: String,
    val deviceClass: DeviceClass,
    val faceStyle: FaceStyle,
    /** Capability gating (spec 2026-08-14, U1/U7 — V11): coletado no hotplug, fora do
     *  hot path. API 31+ via getSensorManager; API < 31 → false (degradação silenciosa). */
    val hasGyro: Boolean = false,
    /** O device expõe SOURCE_CLASS_POINTER (touchpad físico — DS4/DualSense). */
    val hasTouchpad: Boolean = false,
    /** Nível de bateria 0..100 (API 31+); null = desconhecido/sem bateria. */
    val batteryPercent: Int? = null,
    /**
     * K3 (spec 2026-08-16-K3, §1.1): capacidades coletadas no hotplug (UMA chamada
     * binder `InputDevice.hasKeys` — fora do hot path, padrão V11 do hasGyro).
     * null = não coletado → a cadeia degrada para o default estático atual.
     */
    val capabilities: GamepadCapabilities? = null,
    /**
     * K3 (spec 2026-08-16-K3, §1.5): origem (tier) do mapping efetivo deste device —
     * UI/log. null = ainda não resolvido (a UI esconde a linha).
     */
    val mappingSource: MappingSource? = null,
    /**
     * K4 (spec 2026-08-16-K4, §1.4): nome do quirk ativo deste device (resolvido uma
     * vez no hotplug). null = sem quirk — nada muda (degradação byte-identical).
     */
    val quirkName: String? = null,
) {
    val mappingKey: String get() = "%04x%04x".format(vendorId, productId)

    /**
     * K4 §1.4: label de diagnóstico do mapping efetivo — ganha o sufixo "+QUIRK"
     * quando há quirk ativo (ex.: "SDL_DB+QUIRK"). A UI do device card usa ESTA
     * label; o enum [MappingSource] continua puro.
     */
    val mappingSourceLabel: String?
        get() = mappingSource?.let {
            if (quirkName != null) "${it.name}+QUIRK" else it.name
        }
}

/**
 * Origem do mapping efetivo (spec 2026-08-16-K3, §1.5). Ordem de declaração = ordem
 * de prioridade da cadeia (regra de escalonamento do SDL, SDL_gamepad.c:2214-2221):
 * USER (fase K5, reservado) > MODEL > SDL_DB > CAPABILITIES > DEFAULT.
 */
enum class MappingSource { USER, MODEL, SDL_DB, CAPABILITIES, DEFAULT }
