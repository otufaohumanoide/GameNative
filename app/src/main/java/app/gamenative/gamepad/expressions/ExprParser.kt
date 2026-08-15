package app.gamenative.gamepad.expressions

import app.gamenative.gamepad.GamepadAxis
import app.gamenative.gamepad.GamepadButton

/**
 * J1 §2.1: parser descendente com precedência DO DOLPHIN
 * (`reference/dolphin/.../ExpressionParser.cpp` — `OperatorPrecedence`:
 * mul/div = 1, add/sub = 2, gthan/lthan = 3, and = 4, or = 6, question = 7) —
 * gramática do spec: ternário ACIMA DE TUDO (nível mais externo), depois
 * `or < and < not < comparação < + - < * / < menos unário < primário`.
 * Erros com posição (coluna 1-based) — a UI mostra.
 *
 * Entradas: identificador seguido de `:` = eixo (`axis:left_x` — nome validado
 * contra [GamepadAxis]); identificador com `(` = chamada (aridade validada via
 * [ExprFuncs]); senão = botão ([GamepadButton.name] case-insensitive + alias
 * l1/r1/l2/r2/l3/r3). Desconhecido ⇒ erro de parse com coluna.
 */
object ExprParser {

    private val BUTTON_ALIASES = mapOf(
        "l1" to "LEFT_BUMPER", "r1" to "RIGHT_BUMPER",
        "l2" to "LEFT_TRIGGER", "r2" to "RIGHT_TRIGGER",
        "l3" to "LEFT_STICK", "r3" to "RIGHT_STICK",
    )

    /** Nomes de entrada válidos (para o editor "inserir entrada"). */
    val INPUT_NAMES: List<String> =
        GamepadButton.entries.map { it.name.lowercase() } + BUTTON_ALIASES.keys +
            GamepadAxis.entries.map { "axis:${it.name.lowercase()}" }

    private class Cursor(val tokens: List<ExprToken>) {
        var pos = 0
        fun peek(): ExprToken = tokens[pos]
        fun next(): ExprToken = tokens[pos++]
        fun at(type: ExprTokenType): Boolean = peek().type == type
        fun expect(type: ExprTokenType, what: String): ExprToken {
            val tok = peek()
            if (tok.type != type) {
                throw ExprParseException("esperado $what, encontrado '${tok.text}'", tok.column)
            }
            return next()
        }
    }

    fun parse(source: String): ExprAst {
        val tokens = ExprLexer.tokenize(source)
        val cursor = Cursor(tokens)
        val ast = parseTernary(cursor)
        if (cursor.peek().type != ExprTokenType.EOF) {
            val tok = cursor.peek()
            throw ExprParseException("texto inesperado após a expressão: '${tok.text}'", tok.column)
        }
        return ast
    }

    /** Ternário — acima de tudo (o nível mais externo da gramática do spec). */
    private fun parseTernary(c: Cursor): ExprAst {
        val cond = parseOr(c)
        if (!c.at(ExprTokenType.QUESTION)) return cond
        c.next()
        val whenTrue = parseOr(c)
        c.expect(ExprTokenType.COLON, "':' do ternário")
        val whenFalse = parseTernary(c)
        return ExprAst.Ternary(cond, whenTrue, whenFalse)
    }

    private fun parseOr(c: Cursor): ExprAst {
        var lhs = parseAnd(c)
        while (c.at(ExprTokenType.OR)) {
            c.next()
            lhs = ExprAst.Binary(ExprOp.OR, lhs, parseAnd(c))
        }
        return lhs
    }

    private fun parseAnd(c: Cursor): ExprAst {
        var lhs = parseNot(c)
        while (c.at(ExprTokenType.AND)) {
            c.next()
            lhs = ExprAst.Binary(ExprOp.AND, lhs, parseNot(c))
        }
        return lhs
    }

    private fun parseNot(c: Cursor): ExprAst {
        if (c.at(ExprTokenType.NOT)) {
            c.next()
            return ExprAst.Not(parseNot(c))
        }
        return parseComparison(c)
    }

    private fun parseComparison(c: Cursor): ExprAst {
        var lhs = parseAdd(c)
        while (true) {
            val op = when (c.peek().type) {
                ExprTokenType.GT -> ExprOp.GT
                ExprTokenType.LT -> ExprOp.LT
                ExprTokenType.GE -> ExprOp.GE
                ExprTokenType.LE -> ExprOp.LE
                ExprTokenType.EQ -> ExprOp.EQ
                ExprTokenType.NE -> ExprOp.NE
                else -> return lhs
            }
            c.next()
            lhs = ExprAst.Binary(op, lhs, parseAdd(c))
        }
    }

    private fun parseAdd(c: Cursor): ExprAst {
        var lhs = parseMul(c)
        while (c.at(ExprTokenType.ADD) || c.at(ExprTokenType.SUB)) {
            val op = if (c.next().type == ExprTokenType.ADD) ExprOp.ADD else ExprOp.SUB
            lhs = ExprAst.Binary(op, lhs, parseMul(c))
        }
        return lhs
    }

    private fun parseMul(c: Cursor): ExprAst {
        var lhs = parseUnary(c)
        while (c.at(ExprTokenType.MUL) || c.at(ExprTokenType.DIV)) {
            val op = if (c.next().type == ExprTokenType.MUL) ExprOp.MUL else ExprOp.DIV
            lhs = ExprAst.Binary(op, lhs, parseUnary(c))
        }
        return lhs
    }

    private fun parseUnary(c: Cursor): ExprAst {
        if (c.at(ExprTokenType.SUB)) {
            c.next()
            return ExprAst.Unary(parseUnary(c))
        }
        return parsePrimary(c)
    }

    private var callCounter = 0

    private fun parsePrimary(c: Cursor): ExprAst {
        val tok = c.peek()
        return when (tok.type) {
            ExprTokenType.NUMBER -> {
                c.next()
                ExprAst.NumberLit(tok.text.toFloat())
            }
            ExprTokenType.LPAREN -> {
                c.next()
                val inner = parseTernary(c)
                c.expect(ExprTokenType.RPAREN, "')'")
                inner
            }
            ExprTokenType.IDENT -> {
                c.next()
                if (c.at(ExprTokenType.LPAREN)) {
                    parseCall(c, tok)
                } else if (c.at(ExprTokenType.COLON)) {
                    c.next()
                    val axisName = c.expect(ExprTokenType.IDENT, "nome do eixo após ':'")
                    val axis = GamepadAxis.entries.firstOrNull {
                        it.name.equals(axisName.text, ignoreCase = true)
                    }
                    if (axis == null) {
                        throw ExprParseException("eixo desconhecido: '${axisName.text}'", axisName.column)
                    }
                    ExprAst.InputRef(axis.name.lowercase(), axis = true)
                } else {
                    val normalized = resolveButtonName(tok.text)
                        ?: throw ExprParseException("entrada desconhecida: '${tok.text}'", tok.column)
                    ExprAst.InputRef(normalized.lowercase(), axis = false)
                }
            }
            else -> throw ExprParseException("esperado início de expressão, encontrado '${tok.text}'", tok.column)
        }
    }

    private fun parseCall(c: Cursor, nameTok: ExprToken): ExprAst {
        val name = nameTok.text.lowercase()
        if (name !in ExprFuncs.FUNCTIONS) {
            throw ExprParseException("função desconhecida: '${nameTok.text}'", nameTok.column)
        }
        c.expect(ExprTokenType.LPAREN, "'('")
        val args = mutableListOf<ExprAst>()
        if (!c.at(ExprTokenType.RPAREN)) {
            while (true) {
                args += parseTernary(c)
                if (c.at(ExprTokenType.COMMA)) {
                    c.next()
                } else {
                    break
                }
            }
        }
        c.expect(ExprTokenType.RPAREN, "')'")
        ExprFuncs.arityError(name, args.size)?.let {
            throw ExprParseException(it, nameTok.column)
        }
        return ExprAst.Call(name, args, callCounter++)
    }

    /** Nome de botão → GamepadButton.name (case-insensitive + alias l1..r3). */
    private fun resolveButtonName(text: String): String? {
        val lower = text.lowercase()
        BUTTON_ALIASES[lower]?.let { return it }
        return GamepadButton.entries.firstOrNull { it.name.equals(lower, ignoreCase = true) }?.name
    }
}
