package app.gamenative.gamepad.expressions

/**
 * J1 §2.1: estado das expressões POR DEVICE (V6 — o hub mata no removeDevice e
 * reseta na troca de perfil). Chaves dos [ExprFuncs.FuncState]:
 * - `expr<índice>|out` — nível (0/1) emitido por binding (transições Down/Up);
 * - `expr<índice>|axis` — último valor contínuo do binding de eixo;
 * - `expr<índice>|f<nome>@<callSite>` — estado de função com memória;
 * - `relative-shared@<slot>` — pool compartilhado do `relative(..., shared)`.
 * [reset] = reset total (borda de ativação / troca de perfil — padrão GyroProcessor).
 */
class ExprState {
    val funcs = mutableMapOf<String, FuncState>()

    fun reset() {
        funcs.clear()
    }
}
