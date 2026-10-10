package app.gamenative.utils

import java.util.Locale

/**
 * Mapeamento do `rendererPresentMode` persistido no container para o
 * `VkPresentModeKHR` usado pelo renderer Vulkan de display.
 *
 * Os valores seguem a ordem do enum Vulkan: 0 = IMMEDIATE, 1 = MAILBOX,
 * 2 = FIFO, 3 = FIFO_RELAXED. Extraído do `when` inline que vivia no
 * XServerScreen para ser testável em JVM e reduzir código no arquivo que está
 * no limite do registry de dex (ver AGENTS.md).
 *
 * Spec: docs/spec-2026-10-09-mali-mediatek-performance.md
 */
object PresentModeMapping {

    const val VK_PRESENT_MODE_IMMEDIATE = 0
    const val VK_PRESENT_MODE_MAILBOX = 1
    const val VK_PRESENT_MODE_FIFO = 2
    const val VK_PRESENT_MODE_FIFO_RELAXED = 3

    /**
     * Modos oferecidos na UI, em ordem. **Anexar sempre no fim**: o índice é o
     * valor persistido na UI do container (ContainerConfigDialog resolve o índice
     * com `indexOfFirst`), então reordenar trocaria a configuração de containers
     * existentes.
     */
    val UI_MODES: List<String> = listOf("fifo", "mailbox", "immediate", "relaxed")

    /**
     * Modo desconhecido, vazio ou ausente cai em FIFO — mesmo fallback que o
     * caminho nativo já aplica quando o driver não suporta o modo pedido
     * (VulkanRendererContext.cpp).
     */
    fun toVkPresentMode(mode: String?): Int =
        when (mode?.lowercase(Locale.ENGLISH)) {
            "mailbox" -> VK_PRESENT_MODE_MAILBOX
            "immediate" -> VK_PRESENT_MODE_IMMEDIATE
            "relaxed" -> VK_PRESENT_MODE_FIFO_RELAXED
            else -> VK_PRESENT_MODE_FIFO
        }
}
