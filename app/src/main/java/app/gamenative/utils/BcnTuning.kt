package app.gamenative.utils

/**
 * Normalização pura dos knobs de transcode BCN do `wrapper-gamenative` (frente H2 do
 * `docs/plano-mali-mediatek.md`, reframada por H3).
 *
 * O wrapper lê `WRAPPER_BCN_GPU_CAP_MB` como teto de memória transiente do caminho GPU de
 * transcode (default 128 MB, mínimo 16 MB) — confirmado no binário shipado por H3. Esta é a
 * única fonte da normalização, para o valor mostrado na UI e o env var concordarem.
 *
 * `WRAPPER_BCN_POLICY` fica de fora de propósito: está no fonte do wrapper mas **ausente do
 * blob** distribuído, então expô-lo seria tuning falso (mesmo erro de `ENABLE_BCN_COMPUTE`).
 */
object BcnTuning {

    const val DEFAULT_GPU_CAP_MB = 128
    const val MIN_GPU_CAP_MB = 16

    /**
     * Normaliza o valor salvo na UI para o teto em MB: vazio ou não numérico cai no default;
     * valor abaixo do mínimo sobe para o mínimo. Não há teto superior (o wrapper também não impõe).
     */
    fun gpuCapMb(raw: String?): Int {
        val parsed = raw?.trim()?.toIntOrNull() ?: return DEFAULT_GPU_CAP_MB
        return parsed.coerceAtLeast(MIN_GPU_CAP_MB)
    }

    /** `WRAPPER_BCN_ASTC` default é 1 (ASTC nativo no Mali); só o valor "0" desliga. */
    fun astcEnabled(raw: String?): Boolean = raw?.trim() != "0"

    /**
     * O shader do transcode GPU **assume destino ASTC**; com ASTC desligado o destino vira RGBA8 e
     * o compute escreveria ASTC no lugar errado (H3 §1.4). Por isso o caminho GPU exige ASTC ligado.
     */
    fun gpuTranscodeEnabled(transcoder: String?, astcEnabled: Boolean): Boolean =
        astcEnabled && transcoder.equals("gpu", ignoreCase = true)
}
