package app.gamenative.gamepad.profiles

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Store JSON atômico por escopo (spec 2026-08-13, Parte I §7 — padrão do
 * `PerGameShaderStore`): arquivo único keyed por id, write atômico (tmp + rename),
 * conteúdo malformado degrada a vazio e se recupera no próximo save.
 *
 * Dois escopos na mesma classe: device (chave = mappingKey do modelo) e game
 * (chave = appId do container). Salvar um perfil default REMOVE a entrada.
 */
class GamepadProfileStore(private val file: File) {

    /**
     * Cache em memória por instância (spec 2026-08-14-onda2-pos-implementacao, M1 — L1):
     * o hot path do gamepad (hub.profileFor, ~120 Hz por stick) não pode pagar disco +
     * JSON por evento. O store é single-instance no hub e o arquivo só muda por
     * [save]/[clear] DESTE processo (sem concorrente de escritor), então nunca há
     * invalidação externa: [load] serve do cache, [save]/[clear] atualizam cache E disco.
     */
    private var cached: Map<String, GamepadProfile>? = null

    /**
     * V1 (spec 2026-08-14-gamepad-intuito-validacao-upgrades, V1 — política r2): o cru
     * do arquivo como JsonObject, para o SAVE preservar chaves desconhecidas por
     * entrada (downgrade de build é real — canais beta; perda silenciosa de config do
     * usuário é pior que o custo do passthrough). Chaves fora do schema conhecido de
     * [GamepadProfile] são re-injetadas no objeto salvo daquela entrada.
     */
    private var rawJson: JsonObject? = null

    fun load(key: String): GamepadProfile? = entries()[key]

    /** Persiste [profile] para [key]; um perfil default REMOVE a entrada (sem arquivo = sem preferência). */
    fun save(key: String, profile: GamepadProfile) {
        val current = entries().toMutableMap()
        if (profile.isDefault()) {
            current.remove(key)
        } else {
            current[key] = profile
        }
        write(current)
    }

    /** Remove a entrada de [key] apenas; chave ausente = no-op. */
    fun clear(key: String) {
        val current = entries().toMutableMap()
        if (current.remove(key) == null) return
        write(current)
    }

    private fun entries(): Map<String, GamepadProfile> {
        cached?.let { return it }
        if (!file.isFile) {
            cached = emptyMap()
            rawJson = JsonObject(emptyMap())
            return cached!!
        }
        val text = file.readText()
        rawJson = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: JsonObject(emptyMap())
        val parsed = runCatching {
            json.decodeFromString<Map<String, GamepadProfile>>(text)
        }.getOrElse { emptyMap() }
        cached = parsed
        return parsed
    }

    private fun write(entries: Map<String, GamepadProfile>) {
        cached = entries
        // Sem entrada = sem arquivo (default em tudo).
        if (entries.isEmpty()) {
            file.delete()
            rawJson = JsonObject(emptyMap())
            return
        }
        file.parentFile?.mkdirs()
        val out = buildJsonObject {
            for ((key, profile) in entries) {
                val known = runCatching {
                    json.parseToJsonElement(profile.toJson()).jsonObject
                }.getOrElse { JsonObject(emptyMap()) }
                // V1: chaves desconhecidas do rawJson desta entrada voltam intactas.
                val extras = rawJson?.get(key)?.jsonObject
                    ?.filterKeys { k -> k !in KNOWN_FIELDS }
                    ?: emptyMap()
                put(key, buildJsonObject {
                    known.forEach { (k, v) -> put(k, v) }
                    extras.forEach { (k, v) -> put(k, v) }
                })
            }
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(out.toString())
        rawJson = out
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Campos que ESTE build conhece — tudo além disso é "desconhecido" (V1). */
        private val KNOWN_FIELDS: Set<String> = run {
            val descriptor = GamepadProfile.serializer().descriptor
            (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }.toSet()
        }

        /**
         * Merge device → game (game vence campo a campo; null/ausente preserva o de
         * baixo).
         *
         * `layers`: merge GRANULAR (spec 2026-08-14-gamepad-u3-u4-layers-remap-jogo,
         * §1.3 — decisão do intuito U3(c)) — o jogo adiciona/substitui SÓ as camadas
         * que define, nunca apaga as do device. `layerTriggers` idem. U1/U5: campos
         * novos seguem o mesmo padrão null-preserva.
         */
        fun merged(device: GamepadProfile?, game: GamepadProfile?): GamepadProfile {
            val base = device ?: GamepadProfile()
            val override = game ?: return base
            return GamepadProfile(
                faceStyle = override.faceStyle ?: base.faceStyle,
                swapOkCancel = override.swapOkCancel ?: base.swapOkCancel,
                leftStickDeadzone = override.leftStickDeadzone ?: base.leftStickDeadzone,
                rightStickDeadzone = override.rightStickDeadzone ?: base.rightStickDeadzone,
                leftTriggerDeadzone = override.leftTriggerDeadzone ?: base.leftTriggerDeadzone,
                rightTriggerDeadzone = override.rightTriggerDeadzone ?: base.rightTriggerDeadzone,
                layers = base.layers + override.layers,
                gyroMode = override.gyroMode ?: base.gyroMode,
                gyroSensitivity = override.gyroSensitivity ?: base.gyroSensitivity,
                gyroDeadzone = override.gyroDeadzone ?: base.gyroDeadzone,
                gyroActivateButton = override.gyroActivateButton ?: base.gyroActivateButton,
                layerTriggers = base.layerTriggers + override.layerTriggers,
                rumbleOnActivate = override.rumbleOnActivate ?: base.rumbleOnActivate,
                rumbleOnBack = override.rumbleOnBack ?: base.rumbleOnBack,
                touchpadDoubleTapRightClick = override.touchpadDoubleTapRightClick
                    ?: base.touchpadDoubleTapRightClick,
                // F1 (spec 2026-08-15-input-core-avancado): mesmos null-preserva.
                leftStickDeadzoneMode = override.leftStickDeadzoneMode ?: base.leftStickDeadzoneMode,
                rightStickDeadzoneMode = override.rightStickDeadzoneMode ?: base.rightStickDeadzoneMode,
                leftStickCurve = override.leftStickCurve ?: base.leftStickCurve,
                rightStickCurve = override.rightStickCurve ?: base.rightStickCurve,
                leftStickLut = override.leftStickLut ?: base.leftStickLut,
                rightStickLut = override.rightStickLut ?: base.rightStickLut,
                flickStickEnabled = override.flickStickEnabled ?: base.flickStickEnabled,
                flickStickActivationRadius = override.flickStickActivationRadius ?: base.flickStickActivationRadius,
                flickStickSnapAngle = override.flickStickSnapAngle ?: base.flickStickSnapAngle,
                gyroFusionEnabled = override.gyroFusionEnabled ?: base.gyroFusionEnabled,
                gyroFusionKp = override.gyroFusionKp ?: base.gyroFusionKp,
                gyroFusionKi = override.gyroFusionKi ?: base.gyroFusionKi,
            )
        }
    }
}
