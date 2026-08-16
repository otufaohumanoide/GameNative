package app.gamenative.gamepad.mapping

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * K5 (spec 2026-08-16-K5, §1.1) — store do autoconfig por device: UM arquivo JSON
 * por controle em `<filesDir>/deviceMappings/<mappingKey>.json` (deletar/resetar é
 * trivial — o "Restaurar automático" é um delete de arquivo). Padrão do
 * `GamepadProfileStore` (write atômico tmp + rename; conteúdo malformado degrada a
 * vazio e se recupera no próximo save).
 *
 * Política V1 do repo (obrigatória): `ignoreUnknownKeys` + preservar chaves
 * desconhecidas no save — downgrade de build é real (canais beta); perda silenciosa
 * de config do usuário é pior que o custo do passthrough. Chaves fora do schema
 * conhecido de [DeviceAutoconfig] são re-injetadas no arquivo salvo.
 *
 * Cache em memória por instância (mesmo padrão M1 do `GamepadProfileStore`): o
 * store é single-instance no hub e o arquivo só muda por [save]/[delete] DESTE
 * processo (sem concorrente de escritor) — [load] serve do cache, [save]/[delete]
 * atualizam cache E disco. Main thread apenas.
 */
class DeviceMappingStore(private val dir: File) {

    /** Cache de load por mappingKey (null = já visto e ausente). */
    private val cached = mutableMapOf<String, DeviceAutoconfig?>()

    /** V1: o cru de cada arquivo, para o SAVE preservar chaves desconhecidas. */
    private val rawCache = mutableMapOf<String, JsonObject?>()

    fun load(mappingKey: String): DeviceAutoconfig? {
        cached[mappingKey]?.let { return it }
        val file = fileFor(mappingKey)
        if (!file.isFile) {
            cached[mappingKey] = null
            return null
        }
        val text = file.readText()
        rawCache[mappingKey] = runCatching { json.parseToJsonElement(text).jsonObject }
            .getOrNull()
        val parsed = runCatching { json.decodeFromString<DeviceAutoconfig>(text) }
            .getOrNull()
        cached[mappingKey] = parsed
        return parsed
    }

    /** Persiste [config] no arquivo do seu [DeviceAutoconfig.mappingKey] (V1 — extras intactos). */
    fun save(config: DeviceAutoconfig) {
        val key = config.mappingKey
        // V1: ler o arquivo atual ANTES do write (se ainda não lido) — as chaves
        // desconhecidas do build futuro precisam estar no rawCache para o merge.
        load(key)
        cached[key] = config
        val known = json.encodeToJsonElement(DeviceAutoconfig.serializer(), config).jsonObject
        val extras = rawCache[key]?.filterKeys { k -> k !in KNOWN_FIELDS } ?: emptyMap()
        val out = buildJsonObject {
            known.forEach { (k, v) -> put(k, v) }
            extras.forEach { (k, v) -> put(k, v) }
        }
        dir.mkdirs()
        val file = fileFor(key)
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(out.toString())
        rawCache[key] = out
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    /** Remove o arquivo do [mappingKey]; chave ausente = no-op. */
    fun delete(mappingKey: String) {
        cached[mappingKey] = null
        rawCache.remove(mappingKey)
        fileFor(mappingKey).delete()
    }

    /** K5 §1.1: autoconfigs salvos (futura tela de gestão — o card usa load/save/delete). */
    fun list(): List<DeviceAutoconfig> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?: return emptyList()
        return files.mapNotNull { load(it.nameWithoutExtension) }.sortedBy { it.mappingKey }
    }

    private fun fileFor(mappingKey: String): File = File(dir, "$mappingKey.json")

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Campos que ESTE build conhece — tudo além disso é "desconhecido" (V1). */
        private val KNOWN_FIELDS: Set<String> = run {
            val descriptor = DeviceAutoconfig.serializer().descriptor
            (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }.toSet()
        }
    }
}
