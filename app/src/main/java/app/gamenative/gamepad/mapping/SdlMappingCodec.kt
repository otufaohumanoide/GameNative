package app.gamenative.gamepad.mapping

import app.gamenative.gamepad.FaceStyle
import app.gamenative.gamepad.GamepadAxis
import app.gamenative.gamepad.GamepadButton
import app.gamenative.gamepad.GamepadDevice

/**
 * K6 (spec 2026-08-16-K6, §1.1) — ENCODE do formato SDL_GameControllerDB (o
 * complemento do decode do [SdlControllerDb]): fecha o ciclo com o ecossistema —
 * o usuário com controle exótico cola uma mapping string de fórum/GitHub
 * (ferramentas `controllermap`, `testcontroller`, sdl2-gamepad-tool) e o
 * GameNative gera a string para compartilhar (o formato de PR do
 * SDL_GameControllerDB — follow-up declarado do spec §1.4).
 *
 * Port clean-room do formato da SDL (zlib — NUNCA copiar código):
 * - GUID bus-style 2.0.16+ (SDL_CreateJoystickGUID + capability bits em
 *   `reference/SDL/src/joystick/android/SDL_sysjoystick.c:385-440`; masks do
 *   `SDLControllerManager.java` `getAxisMask`:449 / `getButtonMask`:485):
 *   16 bytes `bus 05 | 00 | crc16 0000 | vid LE | pid LE | version 0000 |
 *   assinatura 00 | 00 | button_mask LE | axis_mask LE`;
 * - campos `<semantic>:<binding>` com `bN` = enum SDL_CONTROLLER_BUTTON do
 *   backend Android (`keycode_to_SDL`), `aN` = ordem de eixos do driver,
 *   `hN.M` = hat com máscara SDL (1=up, 2=right, 4=down, 8=left) e os prefixos
 *   `+`/`-`/`~` (SDL_gamepad.c:1682-1849);
 * - hint `SDL_GAMECONTROLLER_USE_BUTTON_LABELS:=1` quando faceStyle NINTENDO
 *   (simétrico ao decode do K3 §1.3);
 * - botão/eixo SEM binding é OMITIDO (formato SDL — o consumidor usa o default
 *   dele); binding com keycode/eixo fora do vocabulário bN/aN do backend
 *   Android também é omitido (não há expressão SDL para ele).
 *
 * Round-trip obrigatório (spec §1.1): `decode(encode(x))` == mapping — teste de
 * propriedade sobre as entradas do asset e os defaults (ver SdlMappingCodecTest).
 * Puro Kotlin, zero android.* — JVM-testável; chamado da main thread (card/remap).
 */
object SdlMappingCodec {

    /**
     * GUID bus-style do device (32 hex) no formato do SDL2 ANDROID — o formato das
     * entries `platform:Android` do SDL_GameControllerDB pinado e o que o
     * [SdlControllerDb.mappingKeyFromGuid] lê (vid nos bytes 4..5, pid nos 8..9).
     * As masks vêm das capabilities coletadas no hotplug (K3);
     * `capabilities == null` → masks 0x0000 (GUID válido, sem bits — o decode usa
     * só vid/pid).
     *
     * Layout (SDL2 2.0.16 — SDL_CreateJoystickGUID, SDL_joystick.c:2480-2510 +
     * Android_AddJoystick, SDL_sysjoystick.c:347-353): o GUID é montado em Uint16
     * `bus | crc | vendor | 0 | product | 0 | version` e o backend Android
     * SOBRESCREVE os dois últimos Uint16 com button_mask/axis_mask:
     * `05 00 | 0000 | vid LE | 0000 | pid LE | 0000 | button_mask LE | axis_mask LE`.
     */
    fun guidFor(device: GamepadDevice): String {
        val caps = device.capabilities
        val buttonMask = caps?.let(::buttonMaskFor) ?: 0
        val axisMask = caps?.let(::axisMaskFor) ?: 0
        val vid = device.vendorId
        val pid = device.productId
        val bytes = intArrayOf(
            // data[0..1] — bus SDL_HARDWARE_BUS_BLUETOOTH (a SDL usa SEMPRE 0x05
            // no Android, mesmo para USB) + byte 0.
            0x05, 0x00,
            // data[2..3] — crc16 (vendor_name NULL → 0).
            0x00, 0x00,
            // data[4..5] — vendor little-endian.
            vid and 0xFF, (vid shr 8) and 0xFF,
            // data[6..7] — 0 (slot vazio entre vendor e product do SDL2).
            0x00, 0x00,
            // data[8..9] — product little-endian.
            pid and 0xFF, (pid shr 8) and 0xFF,
            // data[10..11] — 0 (version do SDL2).
            0x00, 0x00,
            // data[12..15] — capability bits (Uint16 LE sobrescrevendo os slots).
            buttonMask and 0xFF, (buttonMask shr 8) and 0xFF,
            axisMask and 0xFF, (axisMask shr 8) and 0xFF,
        )
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Monta a mapping string SDL do [mapping] para o [device]. O GUID é do DEVICE
     * (capacities no hotplug); [faceStyle] decide o hint de rótulos (NINTENDO).
     * Campos na ordem: GUID, nome, botões (ordem do [GamepadButton]), eixos
     * (ordem do [GamepadAxis]), hint, `platform:Android` — com a vírgula final do
     * formato do DB.
     */
    fun encode(device: GamepadDevice, mapping: GamepadMapping, faceStyle: FaceStyle): String {
        val fields = mutableListOf(guidFor(device), mapping.name)
        for (button in GamepadButton.entries) {
            val binding = mapping.buttons[button] ?: continue
            val raw = encodeRawBinding(binding) ?: continue
            fields += "${buttonSemantic(button)}:$raw"
        }
        for (axis in GamepadAxis.entries) {
            val binding = mapping.axes[axis] ?: continue
            // Eixo semântico com binding de TECLA só existe para triggers (o
            // decode do SdlControllerDb guarda `lefttrigger:b15` no mapa de
            // BOTÕES; o caminho inverso é o simétrico — trigger-como-botão).
            val raw = if (binding is RawBinding.Key &&
                axis != GamepadAxis.LEFT_TRIGGER && axis != GamepadAxis.RIGHT_TRIGGER
            ) {
                null
            } else {
                encodeRawBinding(binding)
            } ?: continue
            fields += "${axisSemantic(axis)}:$raw"
        }
        if (faceStyle == FaceStyle.NINTENDO) {
            fields += "hint:SDL_GAMECONTROLLER_USE_BUTTON_LABELS:=1"
        }
        fields += "platform:Android"
        return fields.joinToString(",") + ","
    }

    /**
     * Binding cru no formato SDL (`bN` / `aN` / `aN~` / `hN.M`). null = sem
     * expressão no vocabulário do backend Android (keycode fora da tabela bN,
     * eixo fora da ordem a0..a5) — o chamador omite o campo.
     */
    fun encodeRawBinding(binding: RawBinding): String? = when (binding) {
        is RawBinding.Key -> sdlButtonIndex(binding.keyCode)?.let { "b$it" }
        is RawBinding.Axis -> axisIndex(binding.axis)?.let {
            if (binding.direction < 0) "a$it~" else "a$it"
        }
        is RawBinding.Hat -> "h${binding.hat}.${binding.mask}"
    }

    /**
     * Diff do import (§1.2): botões/eixos que MUDAM entre o mapping atual e o
     * importado — a UI renderiza `+ SEMANTIC: raw` (novo), `− SEMANTIC: raw`
     * (removido) e `± SEMANTIC: from → to` (mudou). O raw é o formato SDL.
     */
    fun diff(current: GamepadMapping, imported: GamepadMapping): List<MappingDiff> {
        val result = mutableListOf<MappingDiff>()
        for (button in GamepadButton.entries) {
            val from = current.buttons[button]?.let { encodeRawBinding(it) }
            val to = imported.buttons[button]?.let { encodeRawBinding(it) }
            if (from != to) result += MappingDiff(button.name, from, to)
        }
        for (axis in GamepadAxis.entries) {
            val from = current.axes[axis]?.let { encodeRawBinding(it) }
            val to = imported.axes[axis]?.let { encodeRawBinding(it) }
            if (from != to) result += MappingDiff(axis.name, from, to)
        }
        return result
    }

    // ── GUID masks (port do backend Android da SDL — origem citada acima) ──

    /**
     * `getButtonMask` (SDLControllerManager.java:485-535): bit N = a capability
     * existe. Bits 0-14 = botões padrão do enum SDL_CONTROLLER_BUTTON; 15/16/17/18
     * = L2/R2/C/Z do backend Android; 20..31 = BUTTON_1..12. BUTTON_13..16 não
     * cabem no Int — a SDL usa o sentinela 0xFFFFFFFF ("out of room"), que o campo
     * Uint16 do GUID trunca para 0xFFFF. Hat presente vira os 4 bits de DPAD
     * (SDL_sysjoystick.c:427-431).
     */
    private fun buttonMaskFor(caps: GamepadCapabilities): Int {
        val keys = caps.keycodes
        var mask = 0
        fun bit(keyCode: Int, position: Int) {
            if (keyCode in keys) mask = mask or (1 shl position)
        }
        bit(AndroidConstants.BUTTON_A, 0)
        bit(AndroidConstants.BUTTON_B, 1)
        bit(AndroidConstants.BUTTON_X, 2)
        bit(AndroidConstants.BUTTON_Y, 3)
        bit(AndroidConstants.BACK, 4)
        bit(AndroidConstants.BUTTON_MODE, 5)
        bit(AndroidConstants.MENU, 6)
        bit(AndroidConstants.BUTTON_START, 6)
        bit(AndroidConstants.BUTTON_THUMBL, 7)
        bit(AndroidConstants.BUTTON_THUMBR, 8)
        bit(AndroidConstants.BUTTON_L1, 9)
        bit(AndroidConstants.BUTTON_R1, 10)
        bit(AndroidConstants.DPAD_UP, 11)
        bit(AndroidConstants.DPAD_DOWN, 12)
        bit(AndroidConstants.DPAD_LEFT, 13)
        bit(AndroidConstants.DPAD_RIGHT, 14)
        bit(AndroidConstants.BUTTON_SELECT, 4)
        bit(AndroidConstants.DPAD_CENTER, 0)
        bit(AndroidConstants.BUTTON_L2, 15)
        bit(AndroidConstants.BUTTON_R2, 16)
        bit(AndroidConstants.BUTTON_C, 17)
        bit(AndroidConstants.BUTTON_Z, 18)
        for (i in 0..11) bit(AndroidConstants.BUTTON_1 + i, 20 + i)
        for (i in 12..15) {
            if (AndroidConstants.BUTTON_1 + i in keys) return -1
        }
        if (caps.hasHat) {
            mask = mask or (1 shl 11) or (1 shl 12) or (1 shl 13) or (1 shl 14)
        }
        return mask
    }

    /**
     * `getAxisMask` (SDLControllerManager.java:449-481): só distingue 2/4/6 eixos
     * (0x0003 / +0x000C / +0x0030 — ordem canônica X,Y,Z,RZ,LTRIGGER,RTRIGGER) e
     * o bit 0x8000 de "ordem de sort mudou" (AXIS_Z presente E um eixo entre Z e
     * RZ — RX/RY — presente; desabilita entries antigas do DB).
     */
    private fun axisMaskFor(caps: GamepadCapabilities): Int {
        val axes = caps.axes
        var mask = 0
        if (axes.size >= 2) mask = mask or 0x0003
        if (axes.size >= 4) mask = mask or 0x000C
        if (axes.size >= 6) mask = mask or 0x0030
        val haveZ = AndroidConstants.AXIS_Z in axes
        val havePastZBeforeRz = axes.any {
            it > AndroidConstants.AXIS_Z && it < AndroidConstants.AXIS_RZ
        }
        if (haveZ && havePastZBeforeRz) mask = mask or 0x8000
        return mask
    }

    // ── Inversos das tabelas do decode (SdlControllerDb) ──

    /**
     * keycode Android → índice bN do enum SDL_CONTROLLER_BUTTON (inverso de
     * `sdlButtonKeyCode`, MESMA tabela + os aliases do `getButtonMask`: BACK≡SELECT
     * (b4), MENU≡START (b6) — o backend da SDL expressa os dois como b4/b6).
     * keycode fora da tabela (ex.: DPAD_CENTER=23) → null.
     */
    private fun sdlButtonIndex(keyCode: Int): Int? = when (keyCode) {
        AndroidConstants.BUTTON_A -> 0
        AndroidConstants.BUTTON_B -> 1
        AndroidConstants.BUTTON_X -> 2
        AndroidConstants.BUTTON_Y -> 3
        AndroidConstants.BUTTON_SELECT, AndroidConstants.BACK -> 4
        AndroidConstants.BUTTON_MODE -> 5
        AndroidConstants.BUTTON_START, AndroidConstants.MENU -> 6
        AndroidConstants.BUTTON_THUMBL -> 7
        AndroidConstants.BUTTON_THUMBR -> 8
        AndroidConstants.BUTTON_L1 -> 9
        AndroidConstants.BUTTON_R1 -> 10
        AndroidConstants.DPAD_UP -> 11
        AndroidConstants.DPAD_DOWN -> 12
        AndroidConstants.DPAD_LEFT -> 13
        AndroidConstants.DPAD_RIGHT -> 14
        AndroidConstants.BUTTON_L2 -> 15
        AndroidConstants.BUTTON_R2 -> 16
        AndroidConstants.BUTTON_C -> 17
        AndroidConstants.BUTTON_Z -> 18
        in AndroidConstants.BUTTON_1..AndroidConstants.BUTTON_16 ->
            20 + (keyCode - AndroidConstants.BUTTON_1)
        else -> null
    }

    /** Eixo do modelo (ids REAIS do MotionEvent) → índice aN da ordem do driver. */
    private fun axisIndex(axis: Int): Int? = when (axis) {
        AndroidConstants.AXIS_X -> 0
        AndroidConstants.AXIS_Y -> 1
        AndroidConstants.AXIS_Z -> 2
        AndroidConstants.AXIS_RZ -> 3
        AndroidConstants.AXIS_LTRIGGER -> 4
        AndroidConstants.AXIS_RTRIGGER -> 5
        else -> null
    }

    /** Inverso de `buttonByName` do SdlControllerDb (nomes do DB/SDL3, zlib). */
    private fun buttonSemantic(button: GamepadButton): String = when (button) {
        GamepadButton.FACE_BOTTOM -> "a"
        GamepadButton.FACE_RIGHT -> "b"
        GamepadButton.FACE_LEFT -> "x"
        GamepadButton.FACE_TOP -> "y"
        GamepadButton.DPAD_UP -> "dpup"
        GamepadButton.DPAD_DOWN -> "dpdown"
        GamepadButton.DPAD_LEFT -> "dpleft"
        GamepadButton.DPAD_RIGHT -> "dpright"
        GamepadButton.LEFT_BUMPER -> "leftshoulder"
        GamepadButton.RIGHT_BUMPER -> "rightshoulder"
        GamepadButton.LEFT_TRIGGER -> "lefttrigger"
        GamepadButton.RIGHT_TRIGGER -> "righttrigger"
        GamepadButton.LEFT_STICK -> "leftstick"
        GamepadButton.RIGHT_STICK -> "rightstick"
        GamepadButton.START -> "start"
        GamepadButton.SELECT -> "back"
        GamepadButton.GUIDE -> "guide"
        GamepadButton.MISC1 -> "misc1"
        GamepadButton.PADDLE_1 -> "paddle1"
        GamepadButton.PADDLE_2 -> "paddle2"
        GamepadButton.PADDLE_3 -> "paddle3"
        GamepadButton.PADDLE_4 -> "paddle4"
        GamepadButton.TOUCHPAD -> "touchpad"
    }

    /** Inverso de `axisByName` do SdlControllerDb. */
    private fun axisSemantic(axis: GamepadAxis): String = when (axis) {
        GamepadAxis.LEFT_X -> "leftx"
        GamepadAxis.LEFT_Y -> "lefty"
        GamepadAxis.RIGHT_X -> "rightx"
        GamepadAxis.RIGHT_Y -> "righty"
        GamepadAxis.LEFT_TRIGGER -> "lefttrigger"
        GamepadAxis.RIGHT_TRIGGER -> "righttrigger"
    }
}

/**
 * K6 §1.2: uma linha do diff do import — [semantic] é o nome do enum
 * ([GamepadButton]/[GamepadAxis]); [from]/[to] são os bindings no formato SDL
 * (null = campo ausente naquele lado).
 */
data class MappingDiff(
    val semantic: String,
    val from: String?,
    val to: String?,
)
