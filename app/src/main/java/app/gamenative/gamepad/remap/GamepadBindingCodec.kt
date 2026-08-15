package app.gamenative.gamepad.remap

import app.gamenative.gamepad.mapping.RawBinding
import app.gamenative.gamepad.processing.BindingModifier
import app.gamenative.gamepad.processing.BindingModifiers
import kotlin.math.roundToInt

/**
 * Serialização de [RawBinding] para o campo `layers` do perfil (spec 2026-08-13,
 * Parte I §7 — `GamepadButton.name → binding serializado`). Formato próprio, texto
 * simples e estável: `key:<keyCode>`, `axis:<axis>:<direction>`, `hat:<hat>:<mask>`
 * + sufixo opcional `:turbo` (spec 2026-08-16-F-radial-v2-modeshift-turbo, §1.4)
 * + sufixo opcional `:m=<modificadores>` (spec 2026-08-16-H-binding-modifiers-
 * duckstation, §2.2). Round-trip garantido por teste.
 *
 * F §1.4: o flag de turbo vive no TOKEN (não no [RawBinding]): a fonte física é a
 * mesma — a CAMADA decide se o alvo pulsa (rapid-fire DS4Windows, período fixo
 * [app.gamenative.gamepad.processing.TurboScheduler.PERIOD_DEFAULT_MS] de 80 ms).
 * Default OFF = token byte-identical ao v1 (`encode(binding)` sem sufixo; tokens
 * legados decodificam com `turbo = false`).
 *
 * H §2.2: o modificador POR BINDING vive no MESMO token, em um ÚNICO campo `:` extra
 * (o decode já separa por `:`; vírgulas separam subcampos DENTRO do bloco):
 * `:m=<full,inv,s<%>,dz<%>>` — ex.: `key:96:m=inv`, `axis:17:1:m=full,s130,dz5`,
 * `axis:17:1:turbo:m=inv`. Encode escreve SÓ campos não-default (null/false/1.0 →
 * omitidos) — sem modificadores o token fica IDÊNTICO ao atual. Decode é LENIENTE:
 * campos desconhecidos entre vírgulas são IGNORADOS (política V1 do store — nunca
 * quebrar perfil futuro); token inválido ⇒ null (comportamento atual).
 */
object GamepadBindingCodec {

    /**
     * Token de camada decodificado: fonte física + flag turbo + modificadores (H).
     * (O nome "Binding" do spec colidiria com `com.winlator.inputcontrols.Binding`
     * nos arquivos de injeção — chamado de LayerBinding; decisão registrada no impl doc.)
     */
    data class LayerBinding(
        val raw: RawBinding,
        val turbo: Boolean = false,
        /** H: modificadores por binding (sufixo `:m=`). null = default/ausente. */
        val mod: BindingModifier? = null,
    )

    fun encode(binding: RawBinding, turbo: Boolean = false, mod: BindingModifier? = null): String {
        val base = when (binding) {
            is RawBinding.Key -> "key:${binding.keyCode}"
            is RawBinding.Axis -> "axis:${binding.axis}:${binding.direction}"
            is RawBinding.Hat -> "hat:${binding.hat}:${binding.mask}"
        }
        val suffix = encodeModSuffix(mod)
        return when {
            turbo && suffix != null -> "$base:turbo:$suffix"
            turbo -> "$base:turbo"
            suffix != null -> "$base:$suffix"
            else -> base
        }
    }

    /**
     * H §2.2: `:m=<full,inv,s<%>,dz<%>>` — ordem canônica fixa; só campos não-default
     * (fullAxis/invert só quando true; scale ≠ 100% e deadzone ≠ 0% — os percentuais
     * inteiros mantêm o round-trip estável). null quando nada a escrever.
     */
    fun encodeModSuffix(mod: BindingModifier?): String? {
        if (mod == null) return null
        val fields = mutableListOf<String>()
        if (mod.fullAxis == true) fields += "full"
        if (mod.invert == true) fields += "inv"
        mod.scale?.let { scale ->
            val pct = (scale.coerceIn(BindingModifiers.SCALE_MIN, BindingModifiers.SCALE_MAX) * 100f).roundToInt()
            if (pct != 100) fields += "s$pct"
        }
        mod.deadzone?.let { dz ->
            val pct = (dz.coerceIn(BindingModifiers.DEADZONE_MIN, BindingModifiers.DEADZONE_MAX) * 100f).roundToInt()
            if (pct != 0) fields += "dz$pct"
        }
        return if (fields.isEmpty()) null else "m=" + fields.joinToString(",")
    }

    /** null = token inválido (degrade, nunca exceção). */
    fun decode(token: String): LayerBinding? {
        var parts = token.split(':')
        // H: o bloco `m=...` é o ÚLTIMO campo `:` (se existir) — vírgulas separam
        // subcampos DENTRO dele, então o split por `:` continua seguro.
        var mod: BindingModifier? = null
        val last = parts.lastOrNull()
        if (last != null && last.startsWith("m=")) {
            mod = decodeModSuffix(last.removePrefix("m="))
            parts = parts.dropLast(1)
        }
        val turbo = parts.lastOrNull() == "turbo"
        val base = if (turbo) parts.dropLast(1) else parts
        val binding = when (base.getOrNull(0)) {
            "key" -> base.getOrNull(1)?.toIntOrNull()?.let { RawBinding.Key(it) }
            "axis" -> {
                val axis = base.getOrNull(1)?.toIntOrNull() ?: return null
                val direction = base.getOrNull(2)?.toIntOrNull() ?: return null
                if (direction != -1 && direction != 1) return null
                RawBinding.Axis(axis, direction)
            }
            "hat" -> {
                val hat = base.getOrNull(1)?.toIntOrNull() ?: return null
                val mask = base.getOrNull(2)?.toIntOrNull() ?: return null
                if (mask <= 0) return null
                RawBinding.Hat(hat, mask)
            }
            else -> null
        }
        return binding?.let { LayerBinding(it, turbo, mod) }
    }

    /**
     * H §2.2: LENIENTE — campos desconhecidos entre vírgulas são IGNORADOS (política
     * V1: nunca quebrar perfil futuro); percentuais fora da faixa são CLAMPADOS
     * (s50..s200, dz0..dz50); tudo default ⇒ mod null (token canônico).
     */
    private fun decodeModSuffix(block: String): BindingModifier? {
        var invert: Boolean? = null
        var fullAxis: Boolean? = null
        var scale: Float? = null
        var deadzone: Float? = null
        for (field in block.split(',')) {
            when {
                field == "full" -> fullAxis = true
                field == "inv" -> invert = true
                field.startsWith("s") -> {
                    val pct = field.removePrefix("s").toIntOrNull() ?: continue
                    scale = pct.coerceIn(50, 200) / 100f
                }
                field.startsWith("dz") -> {
                    val pct = field.removePrefix("dz").toIntOrNull() ?: continue
                    deadzone = pct.coerceIn(0, 50) / 100f
                }
                else -> {} // campo futuro/desconhecido — ignorado
            }
        }
        val mod = BindingModifier(invert = invert, fullAxis = fullAxis, scale = scale, deadzone = deadzone)
        return if (mod.isDefault()) null else mod
    }

    /** Dois bindings disputam a MESMA fonte física (mesmo keycode/eixo/hat+máscara). */
    fun conflicts(a: RawBinding, b: RawBinding): Boolean = when {
        a is RawBinding.Key && b is RawBinding.Key -> a.keyCode == b.keyCode
        a is RawBinding.Axis && b is RawBinding.Axis -> a.axis == b.axis
        a is RawBinding.Hat && b is RawBinding.Hat -> a.hat == b.hat && (a.mask and b.mask) != 0
        else -> false
    }
}
