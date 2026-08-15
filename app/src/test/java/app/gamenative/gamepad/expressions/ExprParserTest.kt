package app.gamenative.gamepad.expressions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * J1 (spec 2026-08-16-J-expressions-dolphin, §4): precedência DOLPHIN
 * (1+2*3; or < and < not), ternário acima de tudo, parênteses, aridade das
 * calls, erro com coluna e expr vazio/lixo ⇒ erro.
 */
class ExprParserTest {

    private fun parseError(source: String): String? =
        runCatching { ExprParser.parse(source) }.exceptionOrNull()?.message

    @Test
    fun `precedencia aritmetica 1+2 vezes 3`() {
        val ast = ExprParser.parse("1+2*3")
        assertTrue(ast is ExprAst.Binary && ast.op == ExprOp.ADD)
        val rhs = (ast as ExprAst.Binary).rhs
        assertTrue(rhs is ExprAst.Binary && rhs.op == ExprOp.MUL)
    }

    @Test
    fun `or e mais frouxo que and`() {
        // a or b and c → a or (b and c)
        val ast = ExprParser.parse("face_bottom or face_right and face_left")
        assertTrue(ast is ExprAst.Binary && ast.op == ExprOp.OR)
        val rhs = (ast as ExprAst.Binary).rhs
        assertTrue(rhs is ExprAst.Binary && rhs.op == ExprOp.AND)
    }

    @Test
    fun `not liga mais forte que and`() {
        // not a and b → (not a) and b
        val ast = ExprParser.parse("not face_bottom and face_right")
        assertTrue(ast is ExprAst.Binary && ast.op == ExprOp.AND)
        assertTrue((ast as ExprAst.Binary).lhs is ExprAst.Not)
    }

    @Test
    fun `not liga mais fraco que comparacao`() {
        // not axis > 0.5 → not (axis > 0.5) — comparação mais apertada (spec).
        val ast = ExprParser.parse("not axis:left_x > 0.5")
        assertTrue(ast is ExprAst.Not)
        assertTrue((ast as ExprAst.Not).operand is ExprAst.Binary)
    }

    @Test
    fun `ternario acima de tudo`() {
        val ast = ExprParser.parse("face_bottom ? 1 : 0")
        assertTrue(ast is ExprAst.Ternary)
        // ternário é o nível mais externo: a or b ? c : d → (a or b) ? c : d
        val nested = ExprParser.parse("face_bottom or face_right ? 1 : 0")
        assertTrue(nested is ExprAst.Ternary)
        assertTrue((nested as ExprAst.Ternary).cond is ExprAst.Binary)
    }

    @Test
    fun `parenteses mudam a associacao`() {
        val ast = ExprParser.parse("(1+2)*3")
        assertTrue(ast is ExprAst.Binary && ast.op == ExprOp.MUL)
        val lhs = (ast as ExprAst.Binary).lhs
        assertTrue(lhs is ExprAst.Binary && lhs.op == ExprOp.ADD)
    }

    @Test
    fun `referencias de eixo e botao com alias`() {
        val axis = ExprParser.parse("axis:left_x")
        assertTrue((axis as ExprAst.InputRef).axis)
        assertEquals("left_x", axis.name)
        val button = ExprParser.parse("FACE_BOTTOM")
        assertTrue(button is ExprAst.InputRef)
        assertEquals("face_bottom", (button as ExprAst.InputRef).name)
        val alias = ExprParser.parse("l1")
        assertEquals("left_bumper", (alias as ExprAst.InputRef).name)
    }

    @Test
    fun `aridade errada e erro de parse com coluna`() {
        val error = parseError("deadzone(1)")
        assertTrue(error!!.contains("deadzone"))
        val at = parseError("min(1, 2, 3)")
        assertTrue(at!!.contains("min"))
    }

    @Test
    fun `funcao desconhecida e erro com coluna`() {
        val error = parseError("bogus(1)")
        assertTrue(error!!.contains("bogus"))
        // coluna do nome da função
        val ex = runCatching { ExprParser.parse("1 + bogus(1)") }.exceptionOrNull() as ExprParseException
        assertEquals(5, ex.column)
    }

    @Test
    fun `erro reporta a coluna do problema`() {
        val ex = runCatching { ExprParser.parse("face_bottom +") }.exceptionOrNull() as ExprParseException
        assertTrue(ex.column > 0)
        val badChar = runCatching { ExprParser.parse("face_bottom @ 1") }.exceptionOrNull() as ExprParseException
        assertEquals(13, badChar.column)
    }

    @Test
    fun `expr vazia ou lixo falha`() {
        assertEquals("esperado início de expressão, encontrado ''", parseError(""))
        assertTrue(parseError("!!!!") != null)
        assertTrue(parseError("axis:banana")!!.contains("banana"))
        assertTrue(parseError("entrada_desconhecida")!!.contains("entrada_desconhecida"))
        // texto após a expressão também falha
        assertTrue(parseError("1 2") != null)
    }

    @Test
    fun `call sem parenteses e invalida`() {
        assertTrue(parseError("toggle face_bottom") != null)
    }
}
