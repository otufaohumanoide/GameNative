package app.gamenative.gamepad.profiles

import java.io.File
import kotlinx.serialization.json.Json

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
            return cached!!
        }
        val parsed = runCatching {
            json.decodeFromString<Map<String, GamepadProfile>>(file.readText())
        }.getOrElse { emptyMap() }
        cached = parsed
        return parsed
    }

    private fun write(entries: Map<String, GamepadProfile>) {
        cached = entries
        // Sem entrada = sem arquivo (default em tudo).
        if (entries.isEmpty()) {
            file.delete()
            return
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(entries))
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Merge device → game (game vence campo a campo; null/ausente preserva o de
         * baixo). `layers`: o jogo substitui apenas quando tem camadas próprias.
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
                layers = override.layers.ifEmpty { base.layers },
            )
        }
    }
}
