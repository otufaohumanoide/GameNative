package app.gamenative.gamepad.profiles

import app.gamenative.gamepad.FaceStyle
import app.gamenative.gamepad.GyroMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Parser PURO do catálogo comunitário de perfis (spec 2026-08-16-E-profile-catalog-
 * comunitario, §1.2) — espelho do asset `profile-catalog.json` gerado por
 * `tools/profiles/sync_profile_repo.py` (determinístico, commit pinado, nada embarca
 * dinamicamente). Zero android.* — testável em JVM.
 *
 * Robustez (risco §6 herdado do shader catalog): [parse] NUNCA lança — entry
 * malformada é descartada com contagem; `ignoreUnknownKeys` deixa o catálogo evoluir
 * sem quebrar APKs antigos.
 */
@Serializable
data class CatalogEntry(
    /** Id estável do catálogo (chave de ordenação do sync). */
    val id: String,
    /** appId do container-alvo; null = universal (aparece em todo jogo, mas NÃO casa em [ProfileCatalog.forGame]). */
    val game: String? = null,
    /** FaceStyle que o perfil assume (rotulação do controle no preview). */
    val faceStyle: FaceStyle? = null,
    /** Controle-alvo em texto livre (ex.: "DualShock 4 / DualSense"). */
    val controller: String? = null,
    val name: String,
    val author: String,
    val description: String = "",
    val downloads: Long? = null,
    val profile: GamepadProfile,
)

/**
 * Categorias que um perfil TOCA (diff-resumo do preview, spec E §1.3) — ordem fixa
 * de apresentação. O spec lista bindings/gyro/camadas/swipes; STICK/RUMBLE/TOUCHPAD
 * completam a cobertura dos campos reais do GamepadProfile (decisão registrada no
 * impl doc — um perfil só de rumble não pode renderizar resumo vazio).
 */
enum class ProfileSummaryCategory { BINDINGS, GYRO, LAYERS, SWIPES, STICK, RUMBLE, TOUCHPAD }

/** Resultado do parse: entries válidas + contagem de descartes (nunca exceção). */
data class CatalogResult(
    val entries: List<CatalogEntry>,
    val invalidCount: Int,
    val parsedCount: Int,
)

object ProfileCatalog {

    private val json = Json { ignoreUnknownKeys = true }

    /** Shell do asset (schema do sync §1.1) — campos extras ignorados por design. */
    @Serializable
    private data class CatalogShell(
        val generatedFrom: String = "",
        val schemaVersion: Int = 1,
        val profiles: List<kotlinx.serialization.json.JsonElement> = emptyList(),
    )

    /**
     * Parse robusto do asset: cada entry é decodificada isolada — uma entry
     * inválida vira contagem em [CatalogResult.invalidCount] e NUNCA derruba o
     * resto (texto ilegível = catálogo vazio, sem exceção).
     */
    fun parse(text: String): CatalogResult {
        val shell = runCatching { json.decodeFromString<CatalogShell>(text) }.getOrNull()
            ?: return CatalogResult(emptyList(), 0, 0)
        val entries = mutableListOf<CatalogEntry>()
        var invalid = 0
        for (element in shell.profiles) {
            val entry = runCatching {
                json.decodeFromJsonElement(CatalogEntry.serializer(), element)
            }.getOrNull()
            if (entry == null) {
                invalid++
            } else {
                entries.add(entry)
            }
        }
        return CatalogResult(entries, invalid, shell.profiles.size)
    }

    /**
     * Busca por tokens (case-insensitive) sobre game/nome/controle/autor/descrição.
     * Query vazia/blank = todos. TODOS os tokens precisam casar (busca por
     * interseção — previsível com teclado).
     */
    fun search(profiles: List<CatalogEntry>, query: String): List<CatalogEntry> {
        val tokens = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return profiles
        return profiles.filter { entry ->
            val haystack = listOf(
                entry.game ?: "",
                entry.name,
                entry.controller ?: "",
                entry.author,
                entry.description,
            ).joinToString(" ").lowercase()
            tokens.all { token -> token in haystack }
        }
    }

    /**
     * Perfis cujo `game` casa EXATO (case-insensitive) com [appId]. null appId →
     * vazio; entries universais (game null) NÃO casam (decisão: "para este jogo"
     * significa alvo declarado — o universal aparece na lista geral/busca).
     */
    fun forGame(profiles: List<CatalogEntry>, appId: String?): List<CatalogEntry> {
        if (appId == null) return emptyList()
        return profiles.filter { it.game != null && it.game.equals(appId, ignoreCase = true) }
    }

    /**
     * Resumo do que o perfil toca (ordem fixa de [ProfileSummaryCategory]):
     * - BINDINGS: camadas com bindings OU swap OK/Cancel;
     * - GYRO: QUALQUER campo de gyro (inclui desligar — toca o gyro);
     * - LAYERS: triggers de camada;
     * - SWIPES: swipes do touchpad (D);
     * - STICK: deadzones/modos/curvas/LUTs/flick (F1);
     * - RUMBLE: haptics do menu (U5);
     * - TOUCHPAD: duplo-toque (P2-6).
     */
    fun summaryOf(profile: GamepadProfile): List<ProfileSummaryCategory> {
        val categories = mutableListOf<ProfileSummaryCategory>()
        if (profile.layers.isNotEmpty() || profile.swapOkCancel != null) {
            categories += ProfileSummaryCategory.BINDINGS
        }
        if (profile.gyroMode != null || profile.gyroSensitivity != null ||
            profile.gyroDeadzone != null || profile.gyroActivateButton != null ||
            profile.gyroFusionEnabled != null || profile.gyroFusionKp != null ||
            profile.gyroFusionKi != null
        ) {
            categories += ProfileSummaryCategory.GYRO
        }
        if (profile.layerTriggers.isNotEmpty()) {
            categories += ProfileSummaryCategory.LAYERS
        }
        if (!profile.touchpadSwipes.isNullOrEmpty()) {
            categories += ProfileSummaryCategory.SWIPES
        }
        if (profile.leftStickDeadzone != null || profile.rightStickDeadzone != null ||
            profile.leftTriggerDeadzone != null || profile.rightTriggerDeadzone != null ||
            profile.leftStickDeadzoneMode != null || profile.rightStickDeadzoneMode != null ||
            profile.leftStickCurve != null || profile.rightStickCurve != null ||
            profile.leftStickLut != null || profile.rightStickLut != null ||
            profile.flickStickEnabled != null || profile.flickStickActivationRadius != null ||
            profile.flickStickSnapAngle != null
        ) {
            categories += ProfileSummaryCategory.STICK
        }
        if (profile.rumbleOnActivate != null || profile.rumbleOnBack != null) {
            categories += ProfileSummaryCategory.RUMBLE
        }
        if (profile.touchpadDoubleTapRightClick != null) {
            categories += ProfileSummaryCategory.TOUCHPAD
        }
        return categories
    }
}
