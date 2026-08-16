package app.gamenative.gamepad.mapping

import app.gamenative.gamepad.GamepadAxis
import app.gamenative.gamepad.GamepadButton

/**
 * K4 (spec 2026-08-16-K4, §1.1/§1.2) — tabela DECLARATIVA de quirks por
 * vid/pid/nome/transporte, port clean-room do moonlight-android (GPL-3):
 * `reference/moonlight-android/app/src/main/java/com/limelight/binding/input/
 * ControllerHandler.java` — `handleRemapping` (:1312-1547, tabela de fixup por
 * device) e a cascata de eixos de trigger de `createInputDeviceContextForDevice`
 * (:710-930). SEMÂNTICAS reimplementadas em Kotlin puro (zero `android.*`,
 * JVM-testável), cada entry citando a origem no KDoc — NUNCA copiar código.
 *
 * Modelo: [DeviceQuirks.resolve] devolve a PRIMEIRA entry cujo matcher casa E cujo
 * gate de capabilities (K3) passa — a capability decide se o quirk é necessário;
 * [DeviceQuirks.apply] é pura: mapping in → mapping out (CÓPIA — a entry da DB
 * nunca é mutada); fixup null ou sem substituições → retorna o MESMO objeto
 * (identidade de referência, degradação zero, sem alocação).
 */
object DeviceQuirks {

    /**
     * Tabela na ordem de PRIORIDADE (primeira que casa vence): devices específicos
     * antes, catch-all (raw d-pad) POR ÚLTIMO.
     */
    val table: List<DeviceQuirk> = listOf(
        // ── 8BitDo — botão de MODE por scanCode cru ──
        // Origem ControllerHandler.java:1341-1344 ("Override mode button for 8BitDo
        // controllers"): scanCode 306 vira BUTTON_MODE. O moonlight NÃO gateia nome
        // nem transporte nesta entry (a tabela resumida do spec cita BT/nome — a
        // fonte é a autoridade, spec §1.2); o guard de keyCode UNKNOWN do caminho de
        // evento (§1.3.2) impede falsos positivos em firmware com .kl correto.
        DeviceQuirk(
            name = "8BitDo mode button",
            source = "ControllerHandler.java:1341-1344",
            matcher = DeviceQuirkMatcher(vendorId = 0x2dc8),
            fixup = DeviceQuirkFixup(
                scanCodeAliases = mapOf(306 to AndroidConstants.BUTTON_MODE),
            ),
        ),
        // ── Switch Pro (057e:2009) — mapeamento pré-hid-nintendo (Android < 10) ──
        // Origem ControllerHandler.java:1349-1379: o moonlight re-remapa POR SCANCODE
        // quando SDK < Q (0x130..0x13B, 0x13D = 304..315, 317). Aqui o guard de
        // keyCode UNKNOWN torna a entry inerte quando o .kl moderno (hid-nintendo,
        // Android 11+) já entrega os keycodes certos — mesma degradação, sem checar
        // SDK na tabela pura.
        DeviceQuirk(
            name = "Switch Pro (pre-hid-nintendo)",
            source = "ControllerHandler.java:1349-1379",
            matcher = DeviceQuirkMatcher(
                vendorId = 0x057e,
                productId = 0x2009,
                bluetoothOnly = true,
            ),
            fixup = DeviceQuirkFixup(
                scanCodeAliases = mapOf(
                    304 to AndroidConstants.BUTTON_A,
                    305 to AndroidConstants.BUTTON_B,
                    306 to AndroidConstants.BUTTON_X,
                    307 to AndroidConstants.BUTTON_Y,
                    308 to AndroidConstants.BUTTON_L1,
                    309 to AndroidConstants.BUTTON_R1,
                    310 to AndroidConstants.BUTTON_L2,
                    311 to AndroidConstants.BUTTON_R2,
                    312 to AndroidConstants.BUTTON_SELECT,
                    313 to AndroidConstants.BUTTON_START,
                    314 to AndroidConstants.BUTTON_THUMBL,
                    315 to AndroidConstants.BUTTON_THUMBR,
                    317 to AndroidConstants.BUTTON_MODE,
                ),
            ),
        ),
        // ── DS4 BT sem .kl — triggers em RX/RY + clickpad em BUTTON_1 ──
        // Origem ControllerHandler.java:818-878 (cascata: sem LTRIGGER/RTRIGGER e sem
        // BRAKE/GAS/THROTTLE, Sony com RX/RY → "non-standard DualShock 4": triggers
        // em RX/RY, :851-859), :72-105 (BUTTON_1 = clique do touchpad) e :1335-1339
        // (clickpad do .kl do Shield: BUTTON_SELECT com scanCode 317 → BUTTON_1).
        // Gate de capabilities (spec §4): só ativa quando os triggers NÃO vieram em
        // LTRIGGER/RTRIGGER e RX/RY existem — decidido pelas capabilities de K3.
        DeviceQuirk(
            name = "DS4 non-standard (RX/RY triggers)",
            source = "ControllerHandler.java:818-878, 1335-1339, 72-105",
            matcher = DeviceQuirkMatcher(
                vendorId = 0x054c,
                bluetoothOnly = true,
            ),
            needed = { caps ->
                caps != null &&
                    AndroidConstants.AXIS_LTRIGGER !in caps.axes &&
                    AndroidConstants.AXIS_RTRIGGER !in caps.axes &&
                    AndroidConstants.AXIS_RX in caps.axes &&
                    AndroidConstants.AXIS_RY in caps.axes
            },
            fixup = DeviceQuirkFixup(
                replaceAxis = mapOf(
                    GamepadAxis.LEFT_TRIGGER to RawBinding.Axis(AndroidConstants.AXIS_RX, +1),
                    GamepadAxis.RIGHT_TRIGGER to RawBinding.Axis(AndroidConstants.AXIS_RY, +1),
                ),
                replaceButton = mapOf(
                    GamepadButton.TOUCHPAD to RawBinding.Key(AndroidConstants.BUTTON_1),
                ),
                scanCodeAliases = mapOf(317 to AndroidConstants.BUTTON_1),
            ),
        ),
        // ── Xbox Wireless Controller — firmware BT antigo (sem eixo GAS) ──
        // Origem ControllerHandler.java:979-993 (detecção: nome exato + gasRange
        // ausente = firmware antigo do Xbox One S BT) e :1440-1468 (botões re-mapados
        // por scanCode: 306→X, 307→Y, 308→L1, 309→R1, 310→SELECT, 311→START,
        // 312→THUMBL, 313→THUMBR, 139→MODE; MENU = botão guide → MODE, :1464-1467).
        // O remap incondicional por scanCode do moonlight vira alias com guard de
        // keyCode UNKNOWN (§1.3.2); o MENU→guide é mapping-level (GUIDE→MENU).
        DeviceQuirk(
            name = "Xbox Wireless Controller (old BT firmware)",
            source = "ControllerHandler.java:979-993, 1440-1468",
            matcher = DeviceQuirkMatcher(
                nameContains = "Xbox Wireless Controller",
                bluetoothOnly = true,
            ),
            needed = { caps ->
                caps != null && AndroidConstants.AXIS_GAS !in caps.axes
            },
            fixup = DeviceQuirkFixup(
                replaceButton = mapOf(
                    GamepadButton.GUIDE to RawBinding.Key(AndroidConstants.MENU),
                ),
                scanCodeAliases = mapOf(
                    306 to AndroidConstants.BUTTON_X,
                    307 to AndroidConstants.BUTTON_Y,
                    308 to AndroidConstants.BUTTON_L1,
                    309 to AndroidConstants.BUTTON_R1,
                    310 to AndroidConstants.BUTTON_SELECT,
                    311 to AndroidConstants.BUTTON_START,
                    312 to AndroidConstants.BUTTON_THUMBL,
                    313 to AndroidConstants.BUTTON_THUMBR,
                    139 to AndroidConstants.BUTTON_MODE,
                ),
            ),
        ),
        // ── ASUS ROG Kunai — botões M1-M4 → START/SELECT por scanCode ──
        // Origem ControllerHandler.java:1469-1485: 264/266 → START, 265/267 → SELECT
        // (USB 0x7900 e Bluetooth 0x7902).
        DeviceQuirk(
            name = "ASUS ROG Kunai (USB)",
            source = "ControllerHandler.java:1469-1485",
            matcher = DeviceQuirkMatcher(vendorId = 0x0b05, productId = 0x7900),
            fixup = DeviceQuirkFixup(
                scanCodeAliases = mapOf(
                    264 to AndroidConstants.BUTTON_START,
                    265 to AndroidConstants.BUTTON_SELECT,
                    266 to AndroidConstants.BUTTON_START,
                    267 to AndroidConstants.BUTTON_SELECT,
                ),
            ),
        ),
        DeviceQuirk(
            name = "ASUS ROG Kunai (BT)",
            source = "ControllerHandler.java:1469-1485",
            matcher = DeviceQuirkMatcher(vendorId = 0x0b05, productId = 0x7902),
            fixup = DeviceQuirkFixup(
                scanCodeAliases = mapOf(
                    264 to AndroidConstants.BUTTON_START,
                    265 to AndroidConstants.BUTTON_SELECT,
                    266 to AndroidConstants.BUTTON_START,
                    267 to AndroidConstants.BUTTON_SELECT,
                ),
            ),
        ),
        // ── ASUS Gamepad sem START/MENU — BACK vira START, MODE vira SELECT ──
        // Origem ControllerHandler.java:943-959: `backIsStart`/`modeIsSelect` quando o
        // device NÃO tem BUTTON_START nem MENU (gate por hasKeys). Mapping-level:
        // START→BACK e SELECT→MODE — o gate de capabilities espelha o hasKeys.
        DeviceQuirk(
            name = "ASUS Gamepad (back=start, mode=select)",
            source = "ControllerHandler.java:943-959",
            matcher = DeviceQuirkMatcher(
                vendorId = 0x0b05,
                nameContains = "ASUS Gamepad",
            ),
            needed = { caps ->
                caps != null &&
                    AndroidConstants.BUTTON_START !in caps.keycodes &&
                    AndroidConstants.MENU !in caps.keycodes
            },
            fixup = DeviceQuirkFixup(
                replaceButton = mapOf(
                    GamepadButton.START to RawBinding.Key(AndroidConstants.BACK),
                    GamepadButton.SELECT to RawBinding.Key(AndroidConstants.BUTTON_MODE),
                ),
            ),
        ),
        // ── SHIELD v01.03/v01.04 — botão NVIDIA (SEARCH) vira MODE ──
        // Origem ControllerHandler.java:961-968 (nomes exatos) + :1541-1543
        // (searchIsMode: KEYCODE_SEARCH → BUTTON_MODE). Mapping-level: GUIDE→SEARCH.
        DeviceQuirk(
            name = "SHIELD Controller v01.03 (search=mode)",
            source = "ControllerHandler.java:961-968, 1541-1543",
            matcher = DeviceQuirkMatcher(
                vendorId = 0x0955,
                nameContains = "NVIDIA Controller v01.03",
            ),
            needed = { caps ->
                caps == null || AndroidConstants.BUTTON_MODE !in caps.keycodes
            },
            fixup = DeviceQuirkFixup(
                replaceButton = mapOf(
                    GamepadButton.GUIDE to RawBinding.Key(AndroidConstants.SEARCH),
                ),
            ),
        ),
        DeviceQuirk(
            name = "SHIELD Controller v01.04 (search=mode)",
            source = "ControllerHandler.java:961-968, 1541-1543",
            matcher = DeviceQuirkMatcher(
                vendorId = 0x0955,
                nameContains = "NVIDIA Controller v01.04",
            ),
            needed = { caps ->
                caps == null || AndroidConstants.BUTTON_MODE !in caps.keycodes
            },
            fixup = DeviceQuirkFixup(
                replaceButton = mapOf(
                    GamepadButton.GUIDE to RawBinding.Key(AndroidConstants.SEARCH),
                ),
            ),
        ),
        // ── Catch-all: scancodes crus de d-pad (sem .kl) ──
        // Origem ControllerHandler.java:1487-1509: sem eixos de HAT e keyCode UNKNOWN,
        // scancodes 704..707 viram DPAD esq/dir/cima/baixo. Gate de capabilities:
        // sem HAT_X/HAT_Y (o análogo do `hatXAxis == -1 && hatYAxis == -1`). Além
        // dos aliases, os botões DPAD entram no mapping (Key nos keycodes DPAD_*):
        // o tier CAPABILITIES de um device desconhecido NÃO emite bindings de dpad
        // quando a capability não reporta keycodes DPAD — sem isso o alias corrigiria
        // o keycode mas o tradutor não teria binding para casar (o moonlight não tem
        // mapping DB; aqui o binding É o mapping).
        DeviceQuirk(
            name = "Raw d-pad scancodes (704-707)",
            source = "ControllerHandler.java:1487-1509",
            matcher = DeviceQuirkMatcher(),
            needed = { caps -> caps != null && !caps.hasHat },
            fixup = DeviceQuirkFixup(
                replaceButton = mapOf(
                    GamepadButton.DPAD_UP to RawBinding.Key(AndroidConstants.DPAD_UP),
                    GamepadButton.DPAD_DOWN to RawBinding.Key(AndroidConstants.DPAD_DOWN),
                    GamepadButton.DPAD_LEFT to RawBinding.Key(AndroidConstants.DPAD_LEFT),
                    GamepadButton.DPAD_RIGHT to RawBinding.Key(AndroidConstants.DPAD_RIGHT),
                ),
                scanCodeAliases = mapOf(
                    704 to AndroidConstants.DPAD_LEFT,
                    705 to AndroidConstants.DPAD_RIGHT,
                    706 to AndroidConstants.DPAD_UP,
                    707 to AndroidConstants.DPAD_DOWN,
                ),
            ),
        ),
    )

    /**
     * Primeira entry cujo MATCHER casa (sem o gate de capabilities) — o contrato de
     * teste §1.1 (match por vid/pid/nome/BT). O catch-all (raw d-pad) casa com
     * qualquer device, como o moonlight.
     */
    fun firstMatch(vendorId: Int, productId: Int, name: String, isBt: Boolean): DeviceQuirk? =
        table.firstOrNull { it.matches(vendorId, productId, name, isBt) }

    /**
     * Primeira entry cujo matcher casa E cujo gate de capabilities passa (caps null =
     * conservador: entries gateadas por capability NÃO ativam — degradação
     * byte-identical ao caminho atual). É o que o hub chama UMA vez no hotplug.
     */
    fun resolve(
        vendorId: Int,
        productId: Int,
        name: String,
        isBt: Boolean,
        caps: GamepadCapabilities?,
    ): DeviceQuirk? =
        table.firstOrNull { it.matches(vendorId, productId, name, isBt) && it.needed(caps) }

    /**
     * Aplica o fixup como pós-processamento do mapping escolhido pela cadeia
     * (spec §1.3.1): mapping in → mapping out (CÓPIA — a entry da DB nunca é mutada).
     * fixup null ou sem substituições de button/eixo → retorna o MESMO objeto
     * (identidade de referência — degradação zero, sem alocação no hot path).
     */
    fun apply(mapping: GamepadMapping, fixup: DeviceQuirkFixup?): GamepadMapping {
        if (fixup == null ||
            (fixup.replaceButton.isEmpty() && fixup.replaceAxis.isEmpty())
        ) {
            return mapping
        }
        return mapping.copy(
            buttons = if (fixup.replaceButton.isEmpty()) {
                mapping.buttons
            } else {
                mapping.buttons + fixup.replaceButton
            },
            axes = if (fixup.replaceAxis.isEmpty()) {
                mapping.axes
            } else {
                mapping.axes + fixup.replaceAxis
            },
        )
    }
}

/** Critérios de identidade do device (spec §1.1) — campos null = qualquer. */
data class DeviceQuirkMatcher(
    val vendorId: Int? = null,
    val productId: Int? = null,
    /** Substring case-insensitive no nome do InputDevice. */
    val nameContains: String? = null,
    /** null = qualquer transporte; true = só Bluetooth (heurística do hub). */
    val bluetoothOnly: Boolean? = null,
)

/** Substituições aplicadas sobre o mapping (spec §1.1) — sempre CÓPIA, nunca mutação. */
data class DeviceQuirkFixup(
    /** Botão semântico → novo binding cru (ex.: SELECT→START). Adiciona se ausente. */
    val replaceButton: Map<GamepadButton, RawBinding> = emptyMap(),
    /** Eixo semântico → novo binding cru (ex.: trigger em RX/RY). Adiciona se ausente. */
    val replaceAxis: Map<GamepadAxis, RawBinding> = emptyMap(),
    /** scanCode cru → keycode (ex.: 704→DPAD_LEFT) — aplicado por evento, só com keyCode UNKNOWN. */
    val scanCodeAliases: Map<Int, Int> = emptyMap(),
)

/**
 * Entry da tabela: matcher + fixup + identidade de diagnóstico (nome no card do
 * device e no log único do addDevice — spec §1.4).
 */
data class DeviceQuirk(
    val name: String,
    /** Origem da semântica (ControllerHandler.java:NNN) — atribuição clean-room. */
    val source: String,
    val matcher: DeviceQuirkMatcher,
    val fixup: DeviceQuirkFixup,
    /**
     * Gate de necessidade pelas capabilities reais (K3) — "capability decide se o
     * quirk é necessário" (master roadmap §4, K4). Default: sempre (quando o
     * matcher casa, o quirk é necessário).
     */
    val needed: (GamepadCapabilities?) -> Boolean = { true },
) {
    /** Matcher puro (spec §1.1): vid/pid/nome/BT; null = critério não exigido. */
    fun matches(vendorId: Int, productId: Int, name: String, isBt: Boolean): Boolean =
        (matcher.vendorId == null || matcher.vendorId == vendorId) &&
            (matcher.productId == null || matcher.productId == productId) &&
            (matcher.nameContains == null ||
                name.contains(matcher.nameContains, ignoreCase = true)) &&
            (matcher.bluetoothOnly == null || matcher.bluetoothOnly == isBt)
}
