package app.gamenative.gamepad.remap

import app.gamenative.gamepad.mapping.RawBinding

/**
 * Serialização de [RawBinding] para o campo `layers` do perfil (spec 2026-08-13,
 * Parte I §7 — `GamepadButton.name → binding serializado`). Formato próprio, texto
 * simples e estável: `key:<keyCode>`, `axis:<axis>:<direction>`, `hat:<hat>:<mask>`.
 * Round-trip garantido por teste.
 */
object GamepadBindingCodec {

    fun encode(binding: RawBinding): String = when (binding) {
        is RawBinding.Key -> "key:${binding.keyCode}"
        is RawBinding.Axis -> "axis:${binding.axis}:${binding.direction}"
        is RawBinding.Hat -> "hat:${binding.hat}:${binding.mask}"
    }

    /** null = token inválido (degrade, nunca exceção). */
    fun decode(token: String): RawBinding? {
        val parts = token.split(':')
        return when (parts.getOrNull(0)) {
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
    }

    /** Dois bindings disputam a MESMA fonte física (mesmo keycode/eixo/hat+máscara). */
    fun conflicts(a: RawBinding, b: RawBinding): Boolean = when {
        a is RawBinding.Key && b is RawBinding.Key -> a.keyCode == b.keyCode
        a is RawBinding.Axis && b is RawBinding.Axis -> a.axis == b.axis
        a is RawBinding.Hat && b is RawBinding.Hat -> a.hat == b.hat && (a.mask and b.mask) != 0
        else -> false
    }
}
