package app.gamenative.gamepad.radial

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Store por JOGO do Radial Menu (F3.1 do spec 2026-08-15-input-core-avancado) —
 * mesmo padrão do GamepadProfileStore/PerGameShaderStore: arquivo único keyed por
 * appId, write atômico (tmp + rename), conteúdo malformado degrada a vazio e se
 * recupera no próximo save. Macros são específicas do jogo (o gatilho é uma camada
 * do perfil do DEVICE — o device escolhe o gatilho, o jogo escolhe os setores).
 */
class RadialMenuStore(private val file: File) {

    private var cached: Map<String, RadialMenuConfig>? = null

    fun load(appId: String): RadialMenuConfig? = entries()[appId]

    fun save(appId: String, config: RadialMenuConfig) {
        val current = entries().toMutableMap()
        if (config.sectors.isEmpty() && config.triggerLayer == null) {
            current.remove(appId)
        } else {
            current[appId] = config
        }
        write(current)
    }

    private fun entries(): Map<String, RadialMenuConfig> {
        cached?.let { return it }
        if (!file.isFile) {
            cached = emptyMap()
            return cached!!
        }
        val text = file.readText()
        val parsed = runCatching {
            json.decodeFromString<Map<String, RadialMenuConfig>>(text)
        }.getOrElse { emptyMap() }
        cached = parsed
        return parsed
    }

    private fun write(entries: Map<String, RadialMenuConfig>) {
        cached = entries
        if (entries.isEmpty()) {
            file.delete()
            return
        }
        file.parentFile?.mkdirs()
        val out = buildJsonObject {
            for ((key, config) in entries) {
                val known = runCatching {
                    json.parseToJsonElement(config.toJson()).jsonObject
                }.getOrElse { JsonObject(emptyMap()) }
                put(key, known)
            }
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(out.toString())
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }
}
