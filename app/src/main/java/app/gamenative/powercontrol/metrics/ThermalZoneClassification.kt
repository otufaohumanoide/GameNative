package app.gamenative.powercontrol.metrics

import java.util.Locale

/**
 * Classificação pura de zonas térmicas do sysfs para o ranking de CPU/GPU.
 *
 * Motivação (spec Mali/MediaTek, itens I1a/I1b): nem toda `thermal_zone` é um termômetro.
 *
 * - **I1a — trip points de mitigação.** Zonas cujo tipo termina em `-step` (ou contém
 *   `-step-`, `avg-step`, `max-step`) são limiares de throttling, não leituras. Ainda assim
 *   casam com `contains("cpu")`/`contains("gpu")` e podiam ser escolhidas no lugar do sensor
 *   real (um trip de 85 °C é reportado como "85 °C" permanente).
 * - **I1b — `soc` isolado em Qualcomm é State of Charge da bateria (%), não temperatura.**
 *   O ranker de CPU mapeava `soc` para o rank 3; uma bateria a 85 % virava "85 °C". Zonas
 *   `soc_thermal`/`soc-thermal`/`soc_max`/`soc-temp`/`soc_cpu` continuam válidas (em MediaTek
 *   são temperatura legítima).
 *
 * Referência externa: `reference/FPS-Meter-Android/.../SocThermalMonitor.kt:228-270`
 * (`isExcludedZone`/`isValidSocZoneType`). Ver `docs/referencias-externas-mali.md`.
 *
 * Lógica pura, sem dependência Android — testável em JVM (`ThermalZoneClassificationTest`).
 * Não duplicar estas regras em outro lugar.
 */
object ThermalZoneClassification {

    /**
     * `true` quando o tipo de zona não deve ser usado como leitura de temperatura
     * (trip point de mitigação ou "soc" isolado de bateria). Entrada é normalizada.
     */
    fun isExcludedZoneType(type: String): Boolean {
        val t = type.trim().lowercase(Locale.US)
        if (t.isEmpty()) return true

        // I1a: trip points de mitigação térmica (limiares, não termômetros).
        if (t.endsWith("-step") || t.contains("-step-") ||
            t.contains("avg-step") || t.contains("max-step")
        ) {
            return true
        }

        // I1b: "soc" isolado (sem qualificador de temperatura) é State of Charge (%). Mantém
        // soc_thermal/soc-thermal/soc_max/soc-temp/soc_cpu, que são temperatura de verdade.
        if (t.startsWith("soc") &&
            !t.contains("thermal") && !t.contains("temp") &&
            !t.contains("max") && !t.contains("cpu")
        ) {
            return true
        }

        return false
    }

    /** Rank de representatividade da temperatura de CPU (menor vence); `null` = ignorar zona. */
    fun cpuRank(type: String): Int? {
        val t = type.trim().lowercase(Locale.US)
        if (isExcludedZoneType(t)) return null
        return when {
            t.contains("cpu-silicon") -> 0
            t.contains("cpu-0") -> 1
            t.contains("cpu") && !t.contains("gpu") -> 2
            t.contains("soc") -> 3
            t.contains("s5p-tmu") -> 4
            t.contains("cputop") -> 5
            t.contains("tsens") -> 6
            t.contains("cluster") -> 7
            t.contains("big") || t.contains("little") -> 8
            else -> null
        }
    }

    /** Rank de representatividade da temperatura de GPU (menor vence); `null` = ignorar zona. */
    fun gpuRank(type: String): Int? {
        val t = type.trim().lowercase(Locale.US)
        if (isExcludedZoneType(t)) return null
        return when {
            t.contains("gpu-silicon") -> 0
            t.contains("gpu") -> 1
            t.contains("g3d") -> 2
            t.contains("kgsl") -> 3
            t.contains("mali") -> 4
            else -> null
        }
    }
}
