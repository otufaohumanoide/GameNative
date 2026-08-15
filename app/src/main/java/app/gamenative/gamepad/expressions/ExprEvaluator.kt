package app.gamenative.gamepad.expressions

import kotlin.math.roundToInt

/**
 * J1 §2.1: avaliador PURO — `eval(ast, inputReader, state, dtMs, nowMs)`.
 * `and/or/not` usam [ExprFuncs.THRESHOLD] = 0.5 (CONDITION_THRESHOLD do Dolphin,
 * FunctionExpression.h); comparações retornam 1/0; divisão por zero/inf → 0
 * (BinaryExpression CalculateValue do Dolphin). O `relative(..., shared)` com 4º
 * argumento NUMÉRICO usa o pool `relative-shared@<slot>` — pares up/down
 * compartilham estado (variáveis `$` são non-goal — decisão registrada).
 */
object ExprEvaluator {

    fun eval(
        ast: ExprAst,
        reader: (String, Boolean) -> Float,
        state: ExprState,
        dtMs: Long,
        nowMs: Long,
    ): Float {
        return when (ast) {
            is ExprAst.NumberLit -> ast.value
            is ExprAst.InputRef -> reader(ast.name, ast.axis)
            is ExprAst.Unary -> -eval(ast.operand, reader, state, dtMs, nowMs)
            is ExprAst.Not -> if (eval(ast.operand, reader, state, dtMs, nowMs) > ExprFuncs.THRESHOLD) 0f else 1f
            is ExprAst.Binary -> {
                val l = eval(ast.lhs, reader, state, dtMs, nowMs)
                val r = eval(ast.rhs, reader, state, dtMs, nowMs)
                when (ast.op) {
                    ExprOp.ADD -> l + r
                    ExprOp.SUB -> l - r
                    ExprOp.MUL -> l * r
                    ExprOp.DIV -> {
                        val result = if (r == 0f) Float.POSITIVE_INFINITY else l / r
                        if (result.isFinite()) result else 0f
                    }
                    ExprOp.AND -> if (l > ExprFuncs.THRESHOLD && r > ExprFuncs.THRESHOLD) 1f else 0f
                    ExprOp.OR -> if (l > ExprFuncs.THRESHOLD || r > ExprFuncs.THRESHOLD) 1f else 0f
                    ExprOp.GT -> if (l > r) 1f else 0f
                    ExprOp.LT -> if (l < r) 1f else 0f
                    ExprOp.GE -> if (l >= r) 1f else 0f
                    ExprOp.LE -> if (l <= r) 1f else 0f
                    ExprOp.EQ -> if (l == r) 1f else 0f
                    ExprOp.NE -> if (l != r) 1f else 0f
                }
            }
            is ExprAst.Ternary -> {
                if (eval(ast.cond, reader, state, dtMs, nowMs) > ExprFuncs.THRESHOLD) {
                    eval(ast.whenTrue, reader, state, dtMs, nowMs)
                } else {
                    eval(ast.whenFalse, reader, state, dtMs, nowMs)
                }
            }
            is ExprAst.Call -> {
                val spec = ExprFuncs.FUNCTIONS[ast.name]
                    ?: return 0f // nunca acontece — o parser valida
                val args = ast.args.map { eval(it, reader, state, dtMs, nowMs) }
                val funcState = if (ast.name == "relative" && ast.args.size == 4 &&
                    ast.args[3] is ExprAst.NumberLit
                ) {
                    val slot = ast.args[3].let { (it as ExprAst.NumberLit).value }.roundToInt()
                    state.funcs.getOrPut("relative-shared@$slot") { FuncState() }
                } else {
                    state.funcs.getOrPut("expr|f${ast.name}@${ast.index}") { FuncState() }
                }
                spec.fn(args, funcState, dtMs, nowMs)
            }
        }
    }
}
