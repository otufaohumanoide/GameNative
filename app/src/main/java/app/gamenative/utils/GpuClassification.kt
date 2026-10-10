package app.gamenative.utils

import java.util.Locale

/**
 * Família de GPU relevante para as decisões de configuração do container.
 *
 * Antes desta camada a detecção de Mali era feita por `String.contains("Mali")`
 * espalhado em três sítios (XServerScreen.kt, BionicProgramLauncherComponent.java,
 * GlibcProgramLauncherComponent.java) — case-sensitive e cego para "Immortalis",
 * que é a mesma arquitetura ARM mas não contém a substring "Mali".
 *
 * Spec: docs/spec-2026-10-09-mali-mediatek-performance.md
 */
enum class GpuVendor {
    ADRENO,
    MALI,
    XCLIPSE,
    POWERVR,
    OTHER,
}

/**
 * Classificação de GPU ARM/Mali em lógica pura, sem dependência de Android, para
 * ser testável em JVM (mesmo padrão de GamepadStickLogic / ShaderPagingLogic).
 *
 * Toda decisão de configuração condicionada a vendor deve passar por aqui em vez
 * de inspecionar a string do renderer localmente.
 */
object GpuClassification {

    const val VENDOR_QUALCOMM = 0x5143
    const val VENDOR_ARM = 0x13B5
    const val VENDOR_SAMSUNG = 0x144D
    const val VENDOR_IMAGINATION = 0x1010

    /**
     * Regex de modelo reusada da forma já validada em CommunityConfigService
     * (`\b(mali|immortalis)[ -]*([a-z]\d+)\b`), que cobre "Mali-G78",
     * "Mali-G57 MC2", "Mali-T860" e "Immortalis-G715".
     */
    private val MALI_MODEL_REGEX = Regex("\\b(mali|immortalis)[ -]*([a-z]\\d+)\\b")

    /**
     * `true` para Mali **e** Immortalis. Case-insensitive e null-safe: um renderer
     * ausente/desconhecido devolve `false`, o que mantém o comportamento anterior
     * (falha segura) em vez de aplicar configuração de Mali sem certeza.
     */
    fun isMali(renderer: String?): Boolean {
        val r = renderer?.lowercase(Locale.ENGLISH) ?: return false
        return r.contains("mali") || r.contains("immortalis")
    }

    /**
     * Token de modelo normalizado em minúsculas: "g57", "g78", "g715", "t860".
     * `null` quando o renderer nomeia a GPU sem número de modelo (ex.: "Mali-400").
     *
     * É a chave estável para políticas futuras por geração — ver
     * [shouldExcludeBcnCompute] e a seção de pontos de extensão do spec.
     */
    fun maliModel(renderer: String?): String? {
        val r = renderer?.lowercase(Locale.ENGLISH) ?: return null
        return MALI_MODEL_REGEX.find(r)?.groupValues?.get(2)
    }

    /**
     * Série numérica derivada de [maliModel]: 57, 78, 715, 860. `null` quando o
     * modelo não é parseável.
     */
    fun maliSeries(renderer: String?): Int? =
        maliModel(renderer)?.dropWhile { !it.isDigit() }?.takeIf { it.isNotEmpty() }?.toIntOrNull()

    /**
     * Família da GPU combinando vendor id e string do renderer. O renderer é
     * consultado antes do id porque `GPUInformation.getVendorID` pode falhar e
     * devolver 0 conforme o driver.
     */
    fun vendorFamily(vendorId: Int, renderer: String?): GpuVendor {
        val r = renderer?.lowercase(Locale.ENGLISH).orEmpty()
        return when {
            isMali(renderer) || vendorId == VENDOR_ARM -> GpuVendor.MALI
            r.contains("adreno") || vendorId == VENDOR_QUALCOMM -> GpuVendor.ADRENO
            r.contains("xclipse") || vendorId == VENDOR_SAMSUNG -> GpuVendor.XCLIPSE
            r.contains("powervr") || vendorId == VENDOR_IMAGINATION -> GpuVendor.POWERVR
            else -> GpuVendor.OTHER
        }
    }

    /**
     * Decide se o caminho **BCN compute** do wrapper deve ser desabilitado.
     *
     * Regra atual (espelha exatamente a decisão do PR #1673 para Xclipse, que
     * omitiu Mali apesar de o PR ser sobre suporte DX12 em Mali/Exynos):
     *  - Adreno (`0x5143`): sempre excluído — comportamento histórico preservado;
     *  - Xclipse (`0x144D`) + wrapper-gamenative: excluído — preservado;
     *  - Mali/Immortalis + wrapper-gamenative: excluído — **novo**.
     *
     * PONTO DE EXTENSÃO (não implementado de propósito): um mapa por geração
     * (G57/G68/G77/G78/Immortalis) exige medição on-device que ainda não existe;
     * inventá-lo agora seria chutar valores. [maliModel]/[maliSeries] já entregam
     * a chave pronta para quando houver dados — ver spec.
     *
     * Não altera `WRAPPER_EMULATE_BCN`: apenas `ENABLE_BCN_COMPUTE` e
     * `BCN_COMPUTE_AUTO` são gateados por este predicado.
     */
    fun shouldExcludeBcnCompute(
        vendorId: Int,
        renderer: String?,
        isWrapperGamenative: Boolean,
    ): Boolean = when {
        vendorId == VENDOR_QUALCOMM -> true
        vendorId == VENDOR_SAMSUNG && isWrapperGamenative -> true
        isMaliDevice(vendorId, renderer) && isWrapperGamenative -> true
        else -> false
    }

    /**
     * Mali por string de renderer **ou** por vendor id ARM — cobre o caso em que a
     * string do driver não nomeia a GPU mas o vendor é ARM.
     */
    private fun isMaliDevice(vendorId: Int, renderer: String?): Boolean =
        isMali(renderer) || vendorId == VENDOR_ARM
}
