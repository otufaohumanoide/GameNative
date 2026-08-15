package app.gamenative.gamepad

import app.gamenative.gamepad.mapping.GamepadMapping
import app.gamenative.ui.component.GamepadStickDirection

/**
 * Bridge view-level dos diálogos (spec 2026-08-13-onda2 §1.5): janelas de diálogo são
 * superfícies separadas que NUNCA chegam ao bus do MainActivity — recebem
 * KeyEvent/MotionEvent via listeners de view. Este objeto decide, de forma PURA e
 * JVM-testável, o que cada evento cru significa usando a mesma lógica da camada
 * universal (mapping + FaceStyle + deadzone de perfil).
 *
 * Regras do AGENTS.md: diálogos usam GamepadFocusScope (view-level) — nunca misturar
 * com navigators de bus na mesma superfície.
 */
object GamepadViewBridge {

    /** O evento cru é o botão de confirmação do mapping ativo (FaceStyle + swap)? */
    fun isConfirmKey(keyCode: Int, mapping: GamepadMapping, swapOkCancel: Boolean): Boolean =
        mapping.confirmKeyCode(swapOkCancel) == keyCode

    /**
     * Direção de navegação de um stick (menu de diálogo).
     *
     * @param x/y valores crus dos eixos (AXIS_X/AXIS_Y) do MotionEvent.
     * @param hatX/hatY valores crus do hat (AXIS_HAT_X/AXIS_HAT_Y); hat vence (|v| > 0.5).
     * @param deadzone deadzone do MENU (profile.leftStickDeadzone ?:
     *   PrefManager.gamepadMenuStickDeadzone). A comparação é feita sobre o valor CRU
     *   (mesma semântica do BusJoystickFocusNavigator) — nunca sobre o já-rescalonado
     *   do jogo (0.15), senão o menu perderia sensibilidade.
     */
    fun stickDirection(
        x: Float,
        y: Float,
        hatX: Float,
        hatY: Float,
        deadzone: Float,
    ): GamepadStickDirection? {
        // Hat tem prioridade (|v| > 0.5).
        if (kotlin.math.abs(hatX) > 0.5f || kotlin.math.abs(hatY) > 0.5f) {
            return when {
                hatY < -0.5f -> GamepadStickDirection.Up
                hatY > 0.5f -> GamepadStickDirection.Down
                hatX < -0.5f -> GamepadStickDirection.Left
                hatX > 0.5f -> GamepadStickDirection.Right
                else -> null
            }
        }

        // Stick: comparação axial sobre o valor cru contra a deadzone do menu.
        return when {
            y < -deadzone -> GamepadStickDirection.Up
            y > deadzone -> GamepadStickDirection.Down
            x < -deadzone -> GamepadStickDirection.Left
            x > deadzone -> GamepadStickDirection.Right
            else -> null
        }
    }

}
