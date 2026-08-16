package app.gamenative.gamepad.mapping

import app.gamenative.gamepad.FaceStyle
import app.gamenative.gamepad.GamepadAxis
import app.gamenative.gamepad.GamepadButton

/**
 * Parser da gramática do SDL_GameControllerDB (spec 2026-08-13, Parte I §4), adaptada
 * para o Android — licença zlib (SDL_gamepad.c:1682-1846).
 *
 * Formato:
 * ```
 * GUID,nome,a:b0,b:b1,leftx:a0,lefty:a1,dpup:h0.1,dpdown:h0.4,...,platform:Windows,
 * ```
 *
 * Regras:
 * - campo 0 = GUID (não vira binding; quando está no formato SDL de 16 bytes, o parser
 *   extrai vendor+product para o mappingKey);
 * - campo 1 = nome de display;
 * - `bN` → RawBinding.Key(188 + N) — o espaço genérico BUTTON_1..16 do Android
 *   (Parte I §5: "indexado pelo mapping b0..b15");
 * - `aN`/`+aN`/`-aN`/`~aN` (e combinações) → RawBinding.Axis(N, ±1) — metade
 *   positiva/negativa e inversão colapsam na direção (o modelo não guarda half-axis);
 * - `hN.M` → RawBinding.Hat(N, M) — bitmask SDL (1=up, 2=right, 4=down, 8=left);
 * - `lefttrigger:b6` (trigger como BOTÃO) vai para o mapa de botões — no Android o
 *   trigger-botão chega como KeyEvent (L2/R2 = 104/105 ou BUTTON_1..16);
 * - `platform:...` e qualquer campo desconhecido (`hint:`, `type:`, ...) são
 *   IGNORADOS (tolerância — a DB real tem esses campos);
 * - K3 (spec 2026-08-16-K3, §1.4): `misc1`, `paddle1..4` e `touchpad` viram botões
 *   extras ([GamepadButton] MISC1/PADDLE_1-4/TOUCHPAD — análogos do enum SDL3,
 *   zlib).
 */
object MappingParser {

    /** Máscaras SDL do hat (1=up, 2=right, 4=down, 8=left). */
    const val HAT_UP = 1
    const val HAT_RIGHT = 2
    const val HAT_DOWN = 4
    const val HAT_LEFT = 8

    /**
     * Parseia uma linha do gamecontrollerdb. Retorna null para linha inválida (menos de
     * 2 campos, ou sem nenhum binding reconhecido).
     */
    fun parse(line: String): GamepadMapping? {
        val fields = line.split(',').map { it.trim() }
        if (fields.size < 2) return null

        val mappingKey = mappingKeyFromGuid(fields[0])
        val name = fields[1]
        val buttons = mutableMapOf<GamepadButton, RawBinding>()
        val axes = mutableMapOf<GamepadAxis, RawBinding>()

        for (field in fields.drop(2)) {
            if (field.isEmpty()) continue
            val colon = field.indexOf(':')
            if (colon <= 0) continue // sem ':' não é um binding válido — ignora
            val semantic = field.substring(0, colon)
            val raw = field.substring(colon + 1)
            if (semantic == "platform") continue

            val binding = parseRawBinding(raw) ?: continue

            // Eixos semânticos ("leftx", "righty", "lefttrigger", ...).
            val axis = axisByName(semantic)
            if (axis != null) {
                // Trigger como botão: o binding físico é um KeyEvent → mapa de botões
                // (o GamepadAxis correspondente vira botão: LEFT_TRIGGER/RIGHT_TRIGGER).
                if (binding is RawBinding.Key) {
                    val button = triggerButtonForAxis(axis) ?: continue
                    buttons[button] = binding
                } else {
                    axes[axis] = binding
                }
                continue
            }

            // Botões semânticos ("a", "b", "dpup", "leftshoulder", ...).
            val button = buttonByName(semantic) ?: continue
            buttons[button] = binding
        }

        if (buttons.isEmpty() && axes.isEmpty()) return null
        return GamepadMapping(mappingKey, name, FaceStyle.GENERIC, buttons, axes)
    }

    /** Extrai vendor+product de um GUID SDL de 16 bytes ("03000000vvvvpppp..."). */
    private fun mappingKeyFromGuid(guid: String): String {
        if (guid.length < 20) return ""
        if (!guid.startsWith("03000000") && !guid.startsWith("05000000")) return ""
        val vendorHex = guid.substring(8, 12).toIntOrNull(16) ?: return ""
        val productHex = guid.substring(16, 20).toIntOrNull(16) ?: return ""
        // GUID guarda vendor/product em little-endian.
        val vendor = ((vendorHex and 0xFF) shl 8) or (vendorHex shr 8)
        val product = ((productHex and 0xFF) shl 8) or (productHex shr 8)
        return "%04x%04x".format(vendor, product)
    }

    /** `bN` → Key, `hN.M` → Hat, `aN`/`+aN`/`-aN`/`~aN` → Axis. */
    private fun parseRawBinding(raw: String): RawBinding? {
        if (raw.isEmpty()) return null
        return when {
            raw.startsWith("b") -> raw.substring(1).toIntOrNull()
                ?.takeIf { it in 0..15 }
                ?.let { RawBinding.Key(AndroidConstants.BUTTON_1 + it) }

            raw.startsWith("h") -> parseHat(raw)

            else -> parseAxisBinding(raw)
        }
    }

    /** `hN.M` — hat N com máscara M (1=up, 2=right, 4=down, 8=left). */
    private fun parseHat(raw: String): RawBinding.Hat? {
        if (!raw.startsWith("h")) return null
        val dot = raw.indexOf('.')
        if (dot < 2) return null
        val hat = raw.substring(1, dot).toIntOrNull() ?: return null
        val mask = raw.substring(dot + 1).toIntOrNull() ?: return null
        if (hat < 0 || mask <= 0) return null
        return RawBinding.Hat(hat, mask)
    }

    /**
     * `aN` / `+aN` / `-aN` / `~aN` (e combinações `+aN~`, `-aN~`) → Axis(N, ±1).
     * A direção é +1 para eixo inteiro/metade positiva, -1 para metade negativa/invertido
     * (xor entre prefixo e sufixo, espelhando o SDL_PrivateParseGamepadElement).
     */
    private fun parseAxisBinding(raw: String): RawBinding.Axis? {
        var s = raw
        var negative = false
        if (s.startsWith("+")) {
            s = s.substring(1)
        } else if (s.startsWith("-")) {
            negative = true
            s = s.substring(1)
        }
        if (s.endsWith("~")) {
            negative = !negative
            s = s.dropLast(1)
        }
        if (!s.startsWith("a")) return null
        val axis = s.substring(1).toIntOrNull() ?: return null
        if (axis < 0) return null
        return RawBinding.Axis(axis, if (negative) -1 else +1)
    }

    private fun axisByName(name: String): GamepadAxis? = when (name) {
        "leftx" -> GamepadAxis.LEFT_X
        "lefty" -> GamepadAxis.LEFT_Y
        "rightx" -> GamepadAxis.RIGHT_X
        "righty" -> GamepadAxis.RIGHT_Y
        "lefttrigger" -> GamepadAxis.LEFT_TRIGGER
        "righttrigger" -> GamepadAxis.RIGHT_TRIGGER
        else -> null
    }

    private fun triggerButtonForAxis(axis: GamepadAxis): GamepadButton? = when (axis) {
        GamepadAxis.LEFT_TRIGGER -> GamepadButton.LEFT_TRIGGER
        GamepadAxis.RIGHT_TRIGGER -> GamepadButton.RIGHT_TRIGGER
        else -> null
    }

    private fun buttonByName(name: String): GamepadButton? = when (name) {
        "a" -> GamepadButton.FACE_BOTTOM
        "b" -> GamepadButton.FACE_RIGHT
        "x" -> GamepadButton.FACE_LEFT
        "y" -> GamepadButton.FACE_TOP
        "dpup" -> GamepadButton.DPAD_UP
        "dpdown" -> GamepadButton.DPAD_DOWN
        "dpleft" -> GamepadButton.DPAD_LEFT
        "dpright" -> GamepadButton.DPAD_RIGHT
        "leftshoulder" -> GamepadButton.LEFT_BUMPER
        "rightshoulder" -> GamepadButton.RIGHT_BUMPER
        "lefttrigger" -> GamepadButton.LEFT_TRIGGER
        "righttrigger" -> GamepadButton.RIGHT_TRIGGER
        "leftstick" -> GamepadButton.LEFT_STICK
        "rightstick" -> GamepadButton.RIGHT_STICK
        "start" -> GamepadButton.START
        "back" -> GamepadButton.SELECT
        "guide" -> GamepadButton.GUIDE
        // K3 (spec 2026-08-16-K3, §1.4): botões extras do DB (nomes do SDL3,
        // zlib — SDL_gamepad.h). Paddles na ordem posicional paddle1..paddle4.
        "misc1" -> GamepadButton.MISC1
        "paddle1" -> GamepadButton.PADDLE_1
        "paddle2" -> GamepadButton.PADDLE_2
        "paddle3" -> GamepadButton.PADDLE_3
        "paddle4" -> GamepadButton.PADDLE_4
        "touchpad" -> GamepadButton.TOUCHPAD
        else -> null
    }
}
