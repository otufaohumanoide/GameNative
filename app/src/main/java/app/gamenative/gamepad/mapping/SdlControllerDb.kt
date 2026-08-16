package app.gamenative.gamepad.mapping

import app.gamenative.gamepad.FaceStyle
import app.gamenative.gamepad.GamepadAxis
import app.gamenative.gamepad.GamepadButton

/**
 * Lookup do SDL_GameControllerDB para AUTO-MAPEAMENTO de controles genéricos
 * (F1.4 do spec 2026-08-15-input-core-avancado): asset `gamecontrollerdb.txt`
 * pinado por commit (42f28e22, SDL_GameControllerDB) com as entradas
 * `platform:Android`; o fallback continua sendo o DeviceClassifier/defaultAndroidMapping
 * (byte-identical quando nada bate).
 *
 * Interpretação ANDROID do DB (reference/sdl/gamecontrollerdb-notes.md):
 * - índice por (vendor, product) extraído do GUID bus-style — ÚNICO formato de entrada
 *   Android que carrega vid/pid. GUIDs legado (hex do nome, SDL ≤ 2.0.5) são
 *   ignorados (sem chave estável).
 * - `bN` = enum SDL_CONTROLLER_BUTTON do backend Android (b0=A…b14=DPAD_RIGHT,
 *   b15/16=L2/R2, b17/18=C/Z, b20..35=BUTTON_1..16) → keycodes Android normalizados;
 * - K3 (spec 2026-08-16-K3, §1.3): `hint:SDL_GAMECONTROLLER_USE_BUTTON_LABELS:=1`
 *   (sem `!`) vira [FaceStyle.NINTENDO] para vendor não-identificável — o análogo do
 *   `SDL_ConvertMappingToPositionalBAXY` (SDL_gamepad.c:2535) na camada de rótulo
 *   que o fork já tem, sem mudar o formato interno;
 * - `aN` = ordem de eixos do driver Android (0/1=X/Y, 2/3=Z/RZ, 4/5=LTRIGGER/RTRIGGER)
 *   → ids REAIS do MotionEvent (a2=AXIS_Z=11, a3=AXIS_RZ=14 — fix do guia universal
 *   input, pré-K6);
 * - `hN.M` = hat com máscara SDL (1=up, 2=right, 4=down, 8=left — igual ao
 *   MappingParser do fork);
 * - prefixos `+`/`-`/`~` → direção ±1.
 *
 * Puro Kotlin, zero android.* — JVM-testável com fixtures. A LEITURA do asset vive
 * no GamepadHub (appContext.assets), fora deste objeto.
 */
object SdlControllerDb {

    /**
     * Parseia o texto do asset em um mapa `"vvvvpppp" → GamepadMapping`. Linhas
     * malformadas, GUID legado (sem vid/pid) e bindings sem keycode/eixo conhecido
     * são descartados SILENCIOSAMENTE — nunca exceção (risco §6 do spec: o DB é dado
     * externo). Determinístico: última entrada para a mesma chave vence.
     */
    fun parse(text: String): Map<String, GamepadMapping> {
        val result = mutableMapOf<String, GamepadMapping>()
        for (line in text.lineSequence()) {
            val mapping = parseLine(line) ?: continue
            if (mapping.mappingKey.isNotEmpty()) {
                result[mapping.mappingKey] = mapping
            }
        }
        return result
    }

    /** Parseia UMA linha do DB (formato 2.0.16: campos separados por vírgula). */
    fun parseLine(line: String): GamepadMapping? {
        val fields = line.split(',').map { it.trim() }
        if (fields.size < 3) return null
        if (fields[0].startsWith("#")) return null

        val mappingKey = mappingKeyFromGuid(fields[0])
        if (mappingKey.isEmpty()) return null // GUID legado sem vid/pid — ignora

        // K3 §1.3: hint de rótulos lido ANTES do loop (o campo em si continua
        // ignorado como binding). A forma `!NOME:=1` do SDL NEGA o hint (mapping
        // posicional — usado como está), então só a forma positiva conta.
        val usesButtonLabels = fields.drop(2).any { field ->
            field.startsWith("hint:") &&
                field.contains("SDL_GAMECONTROLLER_USE_BUTTON_LABELS:=1") &&
                !field.contains("!")
        }

        val buttons = mutableMapOf<GamepadButton, RawBinding>()
        val axes = mutableMapOf<GamepadAxis, RawBinding>()

        for (field in fields.drop(2)) {
            if (field.isEmpty()) continue
            val colon = field.indexOf(':')
            if (colon <= 0) continue
            val semantic = field.substring(0, colon)
            val raw = field.substring(colon + 1)
            if (semantic == "platform" || semantic == "hint" || semantic == "type") continue

            val binding = parseRawBinding(raw) ?: continue

            val axis = axisByName(semantic)
            if (axis != null) {
                if (binding is RawBinding.Key) {
                    // trigger como botão (L2/R2 chegam como KeyEvent no Android)
                    val button = triggerButtonForAxis(axis) ?: continue
                    buttons[button] = binding
                } else {
                    axes[axis] = binding
                }
                continue
            }
            val button = buttonByName(semantic) ?: continue
            buttons[button] = binding
        }

        if (buttons.isEmpty() && axes.isEmpty()) return null
        return GamepadMapping(
            mappingKey = mappingKey,
            name = fields[1],
            faceStyle = faceStyleForVendor(mappingKey, usesButtonLabels),
            buttons = buttons,
            axes = axes,
        )
    }

    /**
     * GUID bus-style ("05 00 crc crc | vv vv | pp pp | …") → "vvvvpppp". Bytes 4–5 =
     * vendor LE, 6–7 = product LE (SDL_CreateJoystickGUID + capability masks em
     * 12–15 — notes.md §2). Qualquer outro formato → "" (ignorado pelo chamador).
     */
    fun mappingKeyFromGuid(guid: String): String {
        if (guid.length < 20) return ""
        val bus = guid.substring(0, 2)
        if (bus != "05" && bus != "03" && bus != "02" && bus != "04") return ""
        val vendorHex = guid.substring(8, 12).toIntOrNull(16) ?: return ""
        val productHex = guid.substring(16, 20).toIntOrNull(16) ?: return ""
        val vendor = ((vendorHex and 0xFF) shl 8) or (vendorHex shr 8)
        val product = ((productHex and 0xFF) shl 8) or (productHex shr 8)
        return "%04x%04x".format(vendor, product)
    }

    /**
     * K6 (spec 2026-08-16-K6, §1.2): campo `platform:` da string — a validação do
     * import usa (ausente = desktop = bloqueio; o spec §1.2 manda bloquear string
     * desktop com explicação). null = campo ausente/vazio.
     */
    fun platformOf(line: String): String? {
        for (field in line.split(',')) {
            val trimmed = field.trim()
            if (!trimmed.startsWith("platform:")) continue
            val value = trimmed.substringAfter(':').trim()
            if (value.isNotEmpty()) return value
            return null
        }
        return null
    }

    /**
     * FaceStyle inferido do vendor (glyphs + swap OK/Cancel; só visual/semântico).
     *
     * K3 §1.3: [usesButtonLabels] (hint `SDL_GAMECONTROLLER_USE_BUTTON_LABELS:=1`)
     * vira NINTENDO para vendors não-identificáveis — o hint VENCE para quem não é
     * inequívoco, PERDE para Sony/MS (054c/045e) que o vendor já define.
     */
    fun faceStyleForVendor(mappingKey: String, usesButtonLabels: Boolean = false): FaceStyle =
        when (mappingKey.substring(0, 4)) {
            "054c" -> FaceStyle.PLAYSTATION
            "045e" -> FaceStyle.XBOX
            "057e" -> FaceStyle.NINTENDO
            else -> if (usesButtonLabels) FaceStyle.NINTENDO else FaceStyle.GENERIC
        }

    /**
     * `bN` → RawBinding.Key com o keycode Android do enum SDL_CONTROLLER_BUTTON
     * (tabela do backend Android — notes.md §3). `hN.M` → Hat. `aN`/prefixos → Axis.
     */
    private fun parseRawBinding(raw: String): RawBinding? {
        if (raw.isEmpty()) return null
        return when {
            raw.startsWith("b") -> raw.substring(1).toIntOrNull()?.let { n ->
                sdlButtonKeyCode(n)?.let { RawBinding.Key(it) }
            }

            raw.startsWith("h") -> parseHat(raw)
            else -> parseAxisBinding(raw)
        }
    }

    /** Enum SDL_CONTROLLER_BUTTON → AKEYCODE (SDL_sysjoystick.c keycode_to_SDL). */
    private fun sdlButtonKeyCode(n: Int): Int? = when (n) {
        0 -> AndroidConstants.BUTTON_A
        1 -> AndroidConstants.BUTTON_B
        2 -> AndroidConstants.BUTTON_X
        3 -> AndroidConstants.BUTTON_Y
        4 -> AndroidConstants.BUTTON_SELECT
        5 -> AndroidConstants.BUTTON_MODE
        6 -> AndroidConstants.BUTTON_START
        7 -> AndroidConstants.BUTTON_THUMBL
        8 -> AndroidConstants.BUTTON_THUMBR
        9 -> AndroidConstants.BUTTON_L1
        10 -> AndroidConstants.BUTTON_R1
        11 -> AndroidConstants.DPAD_UP
        12 -> AndroidConstants.DPAD_DOWN
        13 -> AndroidConstants.DPAD_LEFT
        14 -> AndroidConstants.DPAD_RIGHT
        15 -> AndroidConstants.BUTTON_L2
        16 -> AndroidConstants.BUTTON_R2
        17 -> AndroidConstants.BUTTON_C
        18 -> AndroidConstants.BUTTON_Z
        in 20..35 -> AndroidConstants.BUTTON_1 + (n - 20)
        else -> null
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
     * `aN` / `+aN` / `-aN` / `~aN` (e combinações) → Axis(eixo Android, ±1). A ordem
     * `aN` do driver Android: 0=X, 1=Y, 2=Z, 3=RZ, 4=LTRIGGER, 5=RTRIGGER.
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
        val n = s.substring(1).toIntOrNull() ?: return null
        val axis = when (n) {
            0 -> AndroidConstants.AXIS_X
            1 -> AndroidConstants.AXIS_Y
            2 -> AndroidConstants.AXIS_Z
            3 -> AndroidConstants.AXIS_RZ
            4 -> AndroidConstants.AXIS_LTRIGGER
            5 -> AndroidConstants.AXIS_RTRIGGER
            else -> return null
        }
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
