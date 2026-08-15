package app.gamenative.gamepad.remap

import app.gamenative.gamepad.mapping.RawBinding

/**
 * Serialização de [RawBinding] para o campo `layers` do perfil (spec 2026-08-13,
 * Parte I §7 — `GamepadButton.name → binding serializado`). Formato próprio, texto
 * simples e estável: `key:<keyCode>`, `axis:<axis>:<direction>`, `hat:<hat>:<mask>`
 * + sufixo opcional `:turbo` (spec 2026-08-16-F-radial-v2-modeshift-turbo, §1.4).
 * Round-trip garantido por teste.
 *
 * F §1.4: o flag de turbo vive no TOKEN (não no [RawBinding]): a fonte física é a
 * mesma — a CAMADA decide se o alvo pulsa (rapid-fire DS4Windows, período fixo
 * [app.gamenative.gamepad.processing.TurboScheduler.PERIOD_DEFAULT_MS] de 80 ms).
 * Default OFF = token byte-identical ao v1 (`encode(binding)` sem sufixo; tokens
 * legados decodificam com `turbo = false`).
 */
object GamepadBindingCodec {

    /**
     * Token de camada decodificado: fonte física + flag turbo.
     * (O nome "Binding" do spec colidiria com `com.winlator.inputcontrols.Binding`
     * nos arquivos de injeção — chamado de LayerBinding; decisão registrada no impl doc.)
     */
    data class LayerBinding(val raw: RawBinding, val turbo: Boolean = false)

    fun encode(binding: RawBinding, turbo: Boolean = false): String {
        val base = when (binding) {
            is RawBinding.Key -> "key:${binding.keyCode}"
            is RawBinding.Axis -> "axis:${binding.axis}:${binding.direction}"
            is RawBinding.Hat -> "hat:${binding.hat}:${binding.mask}"
        }
        return if (turbo) "$base:turbo" else base
    }

    /** null = token inválido (degrade, nunca exceção). */
    fun decode(token: String): LayerBinding? {
        val turbo = token.endsWith(":turbo")
        val base = if (turbo) token.removeSuffix(":turbo") else token
        val parts = base.split(':')
        val binding = when (parts.getOrNull(0)) {
            "key" -> parts.getOrNull(1)?.toIntOrNull()?.let { RawBinding.Key(it) }
            "axis" -> {
                val axis = parts.getOrNull(1)?.toIntOrNull() ?: return null
                val direction = parts.getOrNull(2)?.toIntOrNull() ?: return null
                if (direction != -1 && direction != 1) return null
                RawBinding.Axis(axis, direction)
            }
            "hat" -> {
                val hat = parts.getOrNull(1)?.toIntOrNull() ?: return null
                val mask = parts.getOrNull(2)?.toIntOrNull() ?: return null
                if (mask <= 0) return null
                RawBinding.Hat(hat, mask)
            }
            else -> null
        }
        return binding?.let { LayerBinding(it, turbo) }
    }

    /** Dois bindings disputam a MESMA fonte física (mesmo keycode/eixo/hat+máscara). */
    fun conflicts(a: RawBinding, b: RawBinding): Boolean = when {
        a is RawBinding.Key && b is RawBinding.Key -> a.keyCode == b.keyCode
        a is RawBinding.Axis && b is RawBinding.Axis -> a.axis == b.axis
        a is RawBinding.Hat && b is RawBinding.Hat -> a.hat == b.hat && (a.mask and b.mask) != 0
        else -> false
    }
}
