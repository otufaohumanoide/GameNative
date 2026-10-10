package app.gamenative.utils

/**
 * Normalização pura dos knobs do wrapper que **não** são de BCn (frente **F** do
 * `docs/plano-mali-mediatek.md`).
 *
 * Referência externa: `reference/GameNative-mesa/src/vulkan/wrapper/wrapper_device_memory.c:542-553`.
 * Não duplicar estas regras em outro lugar.
 */
object WrapperTuning {

    /** Chave em `graphicsDriverConfig` (também usada pelo toggle na aba Gráficos). */
    const val KEY_BYPASS_SWAPCHAIN_PLACED = "bypassSwapchainPlaced"

    /**
     * `WRAPPER_BYPASS_SWAPCHAIN_PLACED` — pula a emulação de `VK_EXT_map_memory_placed` para
     * imagens de swapchain (as marcadas como `is_wsi_image`).
     *
     * **Por que importa (plano §2.2/§2.3):** em **todo** Mali a importação de AHB `BGRA_8888` é
     * ilegal, o swapchain externo falha e tudo cai no caminho de blit — um penhasco de
     * desempenho documentado em `winlator-internals/_posts/2026-07-28-wsi-woes-mali.md`. E a
     * cadeia causal agrava: forçamos `BOX64_MMAP32=0` no Mali, o que impede
     * `WRAPPER_DISABLE_PLACED` de ser setado, então `VK_EXT_map_memory_placed` fica exposto e a
     * emulação problemática roda.
     *
     * **Opt-in deliberado, default OFF.** O próprio wrapper mantém o default em 0, com um
     * `// TODO: turn on by default if safe` — ou seja, o autor não tinha certeza. A regra de
     * ouro da §5 do plano proíbe virar default sem A/B on-device. Ligar isto é o primeiro passo
     * do A/B; virar default do Mali é o passo seguinte, só com medição.
     */
    fun bypassSwapchainPlaced(raw: String?): Boolean = raw?.trim() == "1"

    /**
     * Valor do env var correspondente: `"1"` ligado, `"0"` desligado.
     *
     * Sempre emitimos um valor explícito (nunca ausente) para que o estado na UI e o que o
     * wrapper recebe não possam divergir.
     */
    fun bypassSwapchainPlacedEnv(raw: String?): String = if (bypassSwapchainPlaced(raw)) "1" else "0"
}
