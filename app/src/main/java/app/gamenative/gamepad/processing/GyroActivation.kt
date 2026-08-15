package app.gamenative.gamepad.processing

/**
 * G5 do spec 2026-08-16-G-gyro-v2, §1: decisões puras da ativação TOGGLE do gyro.
 *
 * Hold (comportamento atual): o gyro fica ativo ENQUANTO o botão de ativação está
 * pressionado. Toggle (opt-in por perfil): cada borda de PRESS (ButtonDown) do
 * botão de ativação inverte um latch por device — aperta liga, aperta de novo
 * desliga. O recenter da borda off→on já existe no GyroProcessor (a primeira
 * amostra ativa re-ancora o offset — sai de graça).
 *
 * Correção G-v2-revisão: flip no PRESS, não no release — padrão DS4Windows
 * (`IsGyroTriggerActive`, toggle edge-triggered no press). O release-flip do texto
 * original do spec tinha a surpresa "segurar mantém ligado, SOLTAR desliga".
 *
 * PURO (JVM-testável): o latch vive no hub (V6 — morto no removeDevice); aqui ficam
 * só as transições, chamadas pelo emitLogical no MESMO caminho onde o
 * `gyroActivateHeld` é escrito hoje.
 */
object GyroActivation {

    /** Borda de press do botão de ativação: com toggle, inverte o latch. */
    fun onPressButton(latch: Boolean, toggle: Boolean): Boolean =
        if (toggle) !latch else latch

    /** Ativação efetiva: toggle lê o latch; hold lê o botão pressionado. */
    fun active(held: Boolean, latch: Boolean, toggle: Boolean): Boolean =
        if (toggle) latch else held
}
