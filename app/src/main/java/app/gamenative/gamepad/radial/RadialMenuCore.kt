package app.gamenative.gamepad.radial

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Core PURO do Radial Menu (F3.1 do spec 2026-08-15-input-core-avancado): modelo de
 * setores/macros, geometria (ângulo → setor) e plano de execução (sequência de teclas
 * com timing). Zero android.*, JVM-testável — a execução real (KeyEvent sintético) e
 * a UI vivem em arquivos próprios (limite dex do XServerScreen).
 *
 * Ativação: o menu é aberto pela CAMADA existente (U3) — [triggerLayer] referencia o
 * nome de uma camada do perfil do device; a camada ativa (HOLD Select/L3 etc.) abre
 * o overlay, a desativação fecha. Setores: 2..8 (v1 = sempre 8 visualmente; setores
 * sem macro são inertes). Seleção touch-first (slide → release executa), fallback
 * stick (direção → destaque; confirmação com A).
 */
/**
 * D (spec 2026-08-16-D-touchpad-swipes-macros): keyCode RESERVADO do swipe → abrir o
 * radial. NUNCA um keyCode Android real (valor negativo fora do range): o
 * [app.gamenative.gamepad.GamepadTouchpadForwarder] intercepta ANTES do executor —
 * nenhum KeyEvent sintético carrega este valor. O binding é a lista com UM
 * `RadialMacroKey(SWIPE_OPEN_RADIAL)` no mapa `GamepadProfile.touchpadSwipes`.
 */
const val SWIPE_OPEN_RADIAL: Int = -1000

@Serializable
data class RadialMacroKey(
    /** Keycode Android cru (tabela AndroidConstants — nada inventado). */
    val keyCode: Int,
    /** Tempo segurado (ms). */
    val holdMs: Long = 60L,
    /** Pausa após a tecla (ms). */
    val gapMs: Long = 40L,
)

@Serializable
data class RadialSector(
    val label: String,
    val keys: List<RadialMacroKey> = emptyList(),
    /** Índice na paleta de cores do overlay (0..7). */
    val colorIndex: Int = 0,
    /**
     * F (spec 2026-08-16-F-radial-v2-modeshift-turbo, §1.1): submenu aninhado —
     * UM nível. Filhos NUNCA têm children (o parser zera recursivamente no load;
     * nunca crash com JSON malformado). Setor com children abre a sub-roda em vez
     * de executar macro.
     */
    val children: List<RadialSector> = emptyList(),
    /**
     * F §1.1: ícone da allowlist ([RadialMenuConfig.ICON_ALLOWLIST]) — nome fora
     * da allowlist vira null no load (label só, nunca crash). A apresentação
     * (Material icon) vive na UI ([RadialMenuIcons]); aqui só o nome semântico.
     */
    val iconKey: String? = null,
)

@Serializable
data class RadialMenuConfig(
    /** Camada (nome no perfil do device) cuja ativação abre o menu; null = nunca. */
    val triggerLayer: String? = null,
    /** 2..8 setores (índice = posição angular). */
    val sectors: List<RadialSector> = emptyList(),
    /**
     * F (spec 2026-08-16-F-radial-v2-modeshift-turbo, §1.1): v2 = submenus/ícones/
     * executeMode. JSON v1 antigo lê normal (campos novos têm default; o
     * ignoreUnknownKeys preserva extras em builds antigos).
     */
    val schemaVersion: Int = 2,
    /** F §1.1: TAP_RELEASE = v1 (executa e fecha); HOLD = painel persistente. */
    val executeMode: ExecuteMode = ExecuteMode.TAP_RELEASE,
) {
    fun toJson(): String = json.encodeToString(RadialMenuConfig.serializer(), this)

    /**
     * F §1.1: sanitização NO LOAD (nunca exceção com JSON malformado — risco §6):
     * - `iconKey` fora da allowlist vira null (label só);
     * - `children` dos FILHOS são zerados recursivamente (submenu de 1 nível —
     *   netos e além são descartados por completo).
     * Idempotente: config válida passa incólume (roundtrip testado).
     */
    fun sanitized(): RadialMenuConfig = copy(
        sectors = sectors.map { sector ->
            sector.copy(
                iconKey = sector.iconKey?.takeIf { it in ICON_ALLOWLIST },
                children = sector.children.map { child ->
                    child.copy(
                        iconKey = child.iconKey?.takeIf { it in ICON_ALLOWLIST },
                        children = emptyList(),
                    )
                },
            )
        },
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** null = JSON inválido (degrade, nunca exceção — risco §6 do spec). */
        fun fromJson(text: String): RadialMenuConfig? =
            runCatching { json.decodeFromString<RadialMenuConfig>(text) }
                .getOrNull()
                ?.sanitized()

        const val MAX_SECTORS = 8
        const val MIN_SECTORS = 2

        /**
         * F §1.1: allowlist de ícones (map nome → Material icon NO overlay, nunca
         * asset). Nome fora da lista = ícone nulo (label só) — nunca crash.
         */
        val ICON_ALLOWLIST: Set<String> = setOf(
            "sword", "potion", "map", "bag", "run", "gear", "heart", "star",
            "home", "save", "load", "camera", "chat", "trade", "craft", "fight",
        )
    }
}

/**
 * F (spec 2026-08-16-F-radial-v2-modeshift-turbo, §1.1): modo de execução do menu.
 */
enum class ExecuteMode {
    /** v1 — seleção executa ao SOLTAR (touch) / com A (stick) e o menu fecha. */
    TAP_RELEASE,

    /**
     * O setor destacado executa SEM FECHAR enquanto o gatilho de camada estiver
     * seguro (repetindo o macro a cada ativação de setor nova — anti-repeat de
     * 120 ms); `GamepadLayerEvent(false)` é quem fecha. Resolve a deviação nº 6
     * do impl doc 2026-08-15 (execução fechava o menu mesmo com HOLD segurado).
     */
    HOLD,
}

/** Geometria do overlay (pura — testada). */
object RadialMenuGeometry {

    /**
     * Ângulo (graus, 0 = cima, sentido horário — convenção do touch screen) → índice
     * do setor. Ângulos fora de [0,360) são normalizados. `count` vazio = -1.
     */
    fun sectorIndex(angleDeg: Float, count: Int): Int {
        if (count <= 0) return -1
        val normalized = ((angleDeg % 360f) + 360f) % 360f
        return (normalized / (360f / count)).toInt().coerceIn(0, count - 1)
    }

    /**
     * Ângulo central de um setor (graus) — usado pelo fallback de stick: a direção
     * do vetor (x,y) do stick, com 0° = cima (y negativo), sentido horário.
     */
    fun angleOf(x: Float, y: Float): Float {
        val deg = Math.toDegrees(Math.atan2(x.toDouble(), (-y).toDouble())).toFloat()
        return ((deg % 360f) + 360f) % 360f
    }
}

/** Plano de execução de um macro (puro — testado): tempos absolutos relativos a t0. */
object RadialMenuPlan {

    data class TimedKey(val keyCode: Int, val downAtMs: Long, val upAtMs: Long)

    /**
     * Sequência de teclas com timing: cada tecla segura por [RadialMacroKey.holdMs] e
     * com [RadialMacroKey.gapMs] de pausa. Tempos cumulativos a partir de 0.
     */
    fun plan(keys: List<RadialMacroKey>): List<TimedKey> {
        val result = mutableListOf<TimedKey>()
        var cursor = 0L
        for (key in keys) {
            val hold = key.holdMs.coerceAtLeast(1L)
            val gap = key.gapMs.coerceAtLeast(0L)
            result += TimedKey(key.keyCode, cursor, cursor + hold)
            cursor += hold + gap
        }
        return result
    }

    /** Duração total do plano (ms) — 0 para macro vazio. */
    fun totalMs(keys: List<RadialMacroKey>): Long {
        val timed = plan(keys)
        return timed.lastOrNull()?.upAtMs ?: 0L
    }
}
