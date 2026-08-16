package app.gamenative.gamepad.mapping

import app.gamenative.gamepad.FaceStyle
import app.gamenative.gamepad.GamepadAxis
import app.gamenative.gamepad.GamepadButton

/**
 * Síntese PURA de mapping pelas capacidades reais do device (spec 2026-08-16-K3, §1.2)
 * — port clean-room de `SDL_CreateMappingForAndroidGamepad` (zlib; SDL3
 * `reference/SDL/src/joystick/SDL_gamepad.c:705-831`), reimplementado em Kotlin sem
 * `android.*` (JVM-testável). Substitui o [MappingDatabase.defaultAndroidMapping]
 * para o device que NÃO bateu no [MappingDatabase] nem no gamecontrollerdb: binding
 * emitido APENAS para o que o device reporta — sem R3 fantasma, sem face button
 * inexistente, sem stick direito em pad de 1 stick.
 *
 * Regras (numeração do spec §1.2):
 * 1. botão no mapping só se o keycode está em `caps.keycodes`; eixo só se em
 *    `caps.axes` (a capability É o gate — SDL gateia guide por API, aqui não há API,
 *    há o keycode);
 * 2. sem NENHUM botão de face e sem dpad → REMOTE: BACK → FACE_BOTTOM (o remote
 *    navega menus — SDL faz BACK→"b" em SDL_gamepad.c:778-781; no fork a posição de
 *    confirmação é FACE_BOTTOM) e o resto vazio. Sem botão E sem eixo → null (fica o
 *    default estático atual + log — device sem input não é pad);
 * 3. GUIDE só entra com KEYCODE_BUTTON_MODE presente;
 * 4. triggers: eixo LTRIGGER/RTRIGGER quando existem; senão keycode L2/R2 como botão
 *    (nunca ambos — o SDL prioriza o axis);
 * 5. dpad: keycodes DPAD_* se existirem; senão `hasHat` → bindings de hat (mesmo
 *    caminho do `genericDInput(dpadViaHat = true)` da [MappingDatabase]);
 * 6. stick direito: AXIS_Z/RZ se presentes; ausentes → sem RIGHT_X/RIGHT_Y.
 */
object CapabilityMapping {

    /** Botões de face normalizados pelo Android (KEYCODE_BUTTON_A/B/X/Y = 96/97/99/100). */
    private val FACE_KEYCODES = setOf(
        AndroidConstants.BUTTON_A,
        AndroidConstants.BUTTON_B,
        AndroidConstants.BUTTON_X,
        AndroidConstants.BUTTON_Y,
    )

    /** Dpad posicional (KEYCODE_DPAD_* = 19-22). */
    private val DPAD_KEYCODES = setOf(
        AndroidConstants.DPAD_UP,
        AndroidConstants.DPAD_DOWN,
        AndroidConstants.DPAD_LEFT,
        AndroidConstants.DPAD_RIGHT,
    )

    /** Identidade estável dos mappings sintetizados (tier CAPABILITIES da cadeia). */
    const val MAPPING_KEY = "capabilities"

    /**
     * Shape do device (spec §1.2): GAMEPAD (declara SOURCE_GAMEPAD), DINPUT_GENERIC
     * (joystick sem o flag, mas com face/dpad/hat), REMOTE (sem face e sem dpad) e
     * KEYBOARD (sem botão e sem eixo — sentinela de "device sem input").
     */
    fun classify(caps: GamepadCapabilities): DeviceShape = when {
        caps.keycodes.isEmpty() && caps.axes.isEmpty() -> DeviceShape.KEYBOARD
        caps.isGamepadSource -> DeviceShape.GAMEPAD
        FACE_KEYCODES.any { it in caps.keycodes } ||
            DPAD_KEYCODES.any { it in caps.keycodes } ||
            caps.hasHat -> DeviceShape.DINPUT_GENERIC
        else -> DeviceShape.REMOTE
    }

    /**
     * Mapping sintetizado das capacidades (regras 1-6 acima). null = device sem
     * input (o chamador degrada para o default estático + log — SDL_gamepad.c:745-750
     * e 753-755 devolvem NULL na mesma situação).
     */
    fun synthesize(caps: GamepadCapabilities, faceStyle: FaceStyle): GamepadMapping? {
        if (caps.keycodes.isEmpty() && caps.axes.isEmpty()) return null

        val hasFace = FACE_KEYCODES.any { it in caps.keycodes }
        val hasDpad = DPAD_KEYCODES.any { it in caps.keycodes } || caps.hasHat

        // Regra 2 — REMOTE: sem face e sem dpad. BACK vira o botão de confirmação
        // (navegação de menu; SDL usa BACK→"b" em SDL_gamepad.c:778-781) e o resto
        // fica vazio. Sem BACK/SELECT não há o que navegar → null.
        if (!hasFace && !hasDpad) {
            val backKey = listOf(AndroidConstants.BACK, AndroidConstants.BUTTON_SELECT)
                .firstOrNull { it in caps.keycodes } ?: return null
            return GamepadMapping(
                mappingKey = MAPPING_KEY,
                name = "Capabilities (remote)",
                faceStyle = faceStyle,
                buttons = mapOf(GamepadButton.FACE_BOTTOM to RawBinding.Key(backKey)),
                axes = emptyMap(),
            )
        }

        val buttons = mutableMapOf<GamepadButton, RawBinding>()
        val axes = mutableMapOf<GamepadAxis, RawBinding>()
        fun key(button: GamepadButton, candidates: List<Int>): Boolean {
            val keyCode = candidates.firstOrNull { it in caps.keycodes } ?: return false
            buttons[button] = RawBinding.Key(keyCode)
            return true
        }

        // Botões na ordem do SDL_CreateMappingForAndroidGamepad (sul/leste/oeste/
        // norte, back, guide, start, sticks, shoulders, dpad) — só o que existe.
        key(GamepadButton.FACE_BOTTOM, listOf(AndroidConstants.BUTTON_A))
        // SDL_gamepad.c:773-776 — EAST ausente usa BACK como "b" para navegação de
        // menu com remote. O SDL só chega nesse branch com a máscara de face ≠ 0,
        // então o fallback é gateado por hasFace (um remote só-dpad não ganha face
        // fantasma).
        var backConsumed = false
        if (hasFace && !key(GamepadButton.FACE_RIGHT, listOf(AndroidConstants.BUTTON_B))) {
            if (AndroidConstants.BACK in caps.keycodes) {
                buttons[GamepadButton.FACE_RIGHT] = RawBinding.Key(AndroidConstants.BACK)
                backConsumed = true
            }
        }
        key(GamepadButton.FACE_LEFT, listOf(AndroidConstants.BUTTON_X))
        key(GamepadButton.FACE_TOP, listOf(AndroidConstants.BUTTON_Y))
        // BACK consumido pelo fallback de EAST não pode virar SELECT também (mesmo
        // clear de máscara do SDL: `button_mask &= ~(1 << BACK)`).
        if (backConsumed) {
            key(GamepadButton.SELECT, listOf(AndroidConstants.BUTTON_SELECT))
        } else {
            key(GamepadButton.SELECT, listOf(AndroidConstants.BUTTON_SELECT, AndroidConstants.BACK))
        }
        // Regra 3 — GUIDE gateado pela capability (o Android moderno entrega MODE).
        key(GamepadButton.GUIDE, listOf(AndroidConstants.BUTTON_MODE))
        key(GamepadButton.START, listOf(AndroidConstants.BUTTON_START, AndroidConstants.MENU))
        key(GamepadButton.LEFT_STICK, listOf(AndroidConstants.BUTTON_THUMBL))
        key(GamepadButton.RIGHT_STICK, listOf(AndroidConstants.BUTTON_THUMBR))
        key(GamepadButton.LEFT_BUMPER, listOf(AndroidConstants.BUTTON_L1))
        key(GamepadButton.RIGHT_BUMPER, listOf(AndroidConstants.BUTTON_R1))

        // Regra 5 — dpad por keycode quando existir; senão hat (caminho do
        // genericDInput(dpadViaHat = true) da MappingDatabase).
        if (DPAD_KEYCODES.any { it in caps.keycodes }) {
            key(GamepadButton.DPAD_UP, listOf(AndroidConstants.DPAD_UP))
            key(GamepadButton.DPAD_DOWN, listOf(AndroidConstants.DPAD_DOWN))
            key(GamepadButton.DPAD_LEFT, listOf(AndroidConstants.DPAD_LEFT))
            key(GamepadButton.DPAD_RIGHT, listOf(AndroidConstants.DPAD_RIGHT))
        } else if (caps.hasHat) {
            buttons[GamepadButton.DPAD_UP] = RawBinding.Hat(0, MappingParser.HAT_UP)
            buttons[GamepadButton.DPAD_DOWN] = RawBinding.Hat(0, MappingParser.HAT_DOWN)
            buttons[GamepadButton.DPAD_LEFT] = RawBinding.Hat(0, MappingParser.HAT_LEFT)
            buttons[GamepadButton.DPAD_RIGHT] = RawBinding.Hat(0, MappingParser.HAT_RIGHT)
        }

        // Eixos — só o que existe (regras 1 e 6: pad de 1 stick não ganha stick
        // direito; a ordem a0..a5 do driver Android vira a presença do AXIS_* real).
        fun axis(axis: GamepadAxis, androidAxis: Int) {
            if (androidAxis in caps.axes) axes[axis] = RawBinding.Axis(androidAxis, +1)
        }
        axis(GamepadAxis.LEFT_X, AndroidConstants.AXIS_X)
        axis(GamepadAxis.LEFT_Y, AndroidConstants.AXIS_Y)
        axis(GamepadAxis.RIGHT_X, AndroidConstants.AXIS_Z)
        axis(GamepadAxis.RIGHT_Y, AndroidConstants.AXIS_RZ)

        // Regra 4 — trigger como EIXO quando existe; senão como BOTÃO (L2/R2).
        // Nunca ambos (o SDL prioriza o axis para trigger).
        if (AndroidConstants.AXIS_LTRIGGER in caps.axes) {
            axes[GamepadAxis.LEFT_TRIGGER] = RawBinding.Axis(AndroidConstants.AXIS_LTRIGGER, +1)
        } else {
            key(GamepadButton.LEFT_TRIGGER, listOf(AndroidConstants.BUTTON_L2))
        }
        if (AndroidConstants.AXIS_RTRIGGER in caps.axes) {
            axes[GamepadAxis.RIGHT_TRIGGER] = RawBinding.Axis(AndroidConstants.AXIS_RTRIGGER, +1)
        } else {
            key(GamepadButton.RIGHT_TRIGGER, listOf(AndroidConstants.BUTTON_R2))
        }

        return GamepadMapping(
            mappingKey = MAPPING_KEY,
            name = "Capabilities",
            faceStyle = faceStyle,
            buttons = buttons,
            axes = axes,
        )
    }
}

/** Shape de um device pelas capabilities (spec 2026-08-16-K3, §1.2). */
enum class DeviceShape { GAMEPAD, DINPUT_GENERIC, REMOTE, KEYBOARD }
