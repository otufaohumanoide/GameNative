package app.gamenative.gamepad.expressions

/**
 * J1 §2.1: lexer PURO — identificadores (nomes de entrada/funções, incluindo
 * `_` e `.`), números Float, operadores `+ - * / ( ) , ? : > < >= <= == !=` e as
 * palavras `and or not`. Cada token carrega a COLUNA (1-based) para os erros com
 * posição que a UI mostra.
 */
data class ExprToken(val type: ExprTokenType, val text: String, val column: Int)

enum class ExprTokenType {
    NUMBER, IDENT, ADD, SUB, MUL, DIV, LPAREN, RPAREN, COMMA, QUESTION, COLON,
    GT, LT, GE, LE, EQ, NE, AND, OR, NOT, EOF,
}

object ExprLexer {

    fun tokenize(source: String): List<ExprToken> {
        val tokens = mutableListOf<ExprToken>()
        var i = 0
        while (i < source.length) {
            val c = source[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() || (c == '.' && i + 1 < source.length && source[i + 1].isDigit()) -> {
                    val start = i
                    while (i < source.length && (source[i].isDigit() || source[i] == '.')) i++
                    val text = source.substring(start, i)
                    val value = text.toFloatOrNull()
                    if (value == null) {
                        throw ExprParseException("número inválido: $text", start + 1)
                    }
                    tokens += ExprToken(ExprTokenType.NUMBER, text, start + 1)
                }
                c.isLetter() || c == '_' -> {
                    val start = i
                    while (i < source.length && (source[i].isLetterOrDigit() || source[i] == '_' || source[i] == '.')) i++
                    val text = source.substring(start, i)
                    tokens += when (text.lowercase()) {
                        "and" -> ExprToken(ExprTokenType.AND, text, start + 1)
                        "or" -> ExprToken(ExprTokenType.OR, text, start + 1)
                        "not" -> ExprToken(ExprTokenType.NOT, text, start + 1)
                        else -> ExprToken(ExprTokenType.IDENT, text, start + 1)
                    }
                }
                else -> {
                    val start = i
                    val two = if (i + 1 < source.length) source.substring(i, i + 2) else ""
                    val type = when {
                        two == ">=" -> ExprTokenType.GE
                        two == "<=" -> ExprTokenType.LE
                        two == "==" -> ExprTokenType.EQ
                        two == "!=" -> ExprTokenType.NE
                        else -> when (c) {
                            '+' -> ExprTokenType.ADD
                            '-' -> ExprTokenType.SUB
                            '*' -> ExprTokenType.MUL
                            '/' -> ExprTokenType.DIV
                            '(' -> ExprTokenType.LPAREN
                            ')' -> ExprTokenType.RPAREN
                            ',' -> ExprTokenType.COMMA
                            '?' -> ExprTokenType.QUESTION
                            ':' -> ExprTokenType.COLON
                            '>' -> ExprTokenType.GT
                            '<' -> ExprTokenType.LT
                            '!' -> null // só válido como !=
                            else -> null
                        }
                    }
                    if (type == null) {
                        throw ExprParseException("caractere inesperado: '$c'", start + 1)
                    }
                    i += if (type in listOf(ExprTokenType.GE, ExprTokenType.LE, ExprTokenType.EQ, ExprTokenType.NE)) 2 else 1
                    tokens += ExprToken(type, source.substring(start, i), start + 1)
                }
            }
        }
        tokens += ExprToken(ExprTokenType.EOF, "", source.length + 1)
        return tokens
    }
}

/** Erro de parse com posição (coluna 1-based) — a UI mostra linha/col. */
class ExprParseException(message: String, val column: Int) : Exception(message)
