package app.gamenative.gamepad.expressions

/**
 * J1 (spec 2026-08-16-J-expressions-dolphin, §2.1): AST puro da linguagem de
 * expressões — port clean-room da GRAMÁTICA do Dolphin
 * (`reference/dolphin/Source/Core/InputCommon/ControlReference/ExpressionParser.h/.cpp`,
 * GPL-2.0; nada copiado). Nós selados, zero android.*.
 */
sealed interface ExprAst {
    data class NumberLit(val value: Float) : ExprAst

    /** Entrada nomeada: botão (GamepadButton.name case-insensitive) ou eixo (`axis:left_x`). */
    data class InputRef(val name: String, val axis: Boolean) : ExprAst

    /** Menos unário (`-x` — o `minus()` do Dolphin). */
    data class Unary(val operand: ExprAst) : ExprAst

    /** `not x` — nível de precedência entre `and` e comparação (gramática do spec §2.1). */
    data class Not(val operand: ExprAst) : ExprAst

    data class Binary(val op: ExprOp, val lhs: ExprAst, val rhs: ExprAst) : ExprAst

    /** Ternário `a ? b : c` (acima de tudo — nível mais externo). */
    data class Ternary(val cond: ExprAst, val whenTrue: ExprAst, val whenFalse: ExprAst) : ExprAst

    /**
     * Chamada de função. [index] = ordem de aparição na expressão — o estado de
     * funções com memória (toggle/smooth/…) é POR call-site, não por nome.
     */
    data class Call(val name: String, val args: List<ExprAst>, val index: Int) : ExprAst
}

enum class ExprOp { ADD, SUB, MUL, DIV, AND, OR, GT, LT, GE, LE, EQ, NE }
