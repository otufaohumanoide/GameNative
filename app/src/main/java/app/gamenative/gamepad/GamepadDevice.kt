package app.gamenative.gamepad

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
) {
    val mappingKey: String get() = "%04x%04x".format(vendorId, productId)
}
