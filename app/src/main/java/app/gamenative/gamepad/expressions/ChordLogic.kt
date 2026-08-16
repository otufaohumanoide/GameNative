package app.gamenative.gamepad.expressions

/**
 * J2 (spec 2026-08-16-J-expressions-dolphin, §3): chords com suppression — port
 * clean-room do `HotkeyExpression` do Dolphin
 * (`reference/dolphin/.../ExpressionParser.cpp` ~linhas 555-636, HotkeySuppressions
 * ~67-113; GPL-2.0, nada copiado):
 *
 * - Sintaxe: `A + B` DENTRO de `expr:` — o `+` aqui é CHORD, não soma; o conflito
 *   resolve como no Dolphin: chord exige operandos [ExprAst.InputRef] PUROS
 *   (botões — eixo desqualifica; qualquer outro operando devolve a soma normal).
 * - Semântica: enquanto os MODIFICADORES (A…) estão ativos (> 0.5), o binding
 *   SIMPLES do botão FINAL (B) é suprimido; o chord emite quando B também ativa
 *   (o valor do chord é 1 com TODOS segurados).
 * - Chords que compartilham teclas: o de MAIOR conjunto vence (superconjunto —
 *   `HotkeySuppressions`): um chord totalmente segurado é suprimido quando outro
 *   chord cujo conjunto de botões o contém ESTRITAMENTE também está totalmente
 *   segurado.
 *
 * Estado derivado do perfil (parse-time, no cache M1 do hub) — nada novo por
 * device além do conjunto de segurados.
 */
object ChordLogic {

    /** Chord = lista ORDENADA de botões [modificadores…, final]. */
    data class Chord(val buttons: List<String>) {
        val final: String get() = buttons.last()
        val modifiers: List<String> get() = buttons.dropLast(1)
    }

    /**
     * Extrai o chord de um source `expr:` — cadeia TOP-LEVEL de `+` com
     * [ExprAst.InputRef] de BOTÃO puros (≥ 2). Qualquer outro operando/estrutura
     * ⇒ null (a expressão é avaliada como soma normal).
     */
    fun parseChord(source: String): Chord? {
        val ast = runCatching { ExprParser.parse(source) }.getOrNull() ?: return null
        val refs = mutableListOf<ExprAst.InputRef>()
        var node: ExprAst = ast
        while (node is ExprAst.Binary && node.op == ExprOp.ADD) {
            val rhs = node.rhs
            if (rhs !is ExprAst.InputRef || rhs.axis) return null
            refs.add(0, rhs) // A + B + C = (A + B) + C — o rhs é o mais à direita
            node = node.lhs
        }
        if (node !is ExprAst.InputRef || node.axis) return null
        refs.add(0, node)
        if (refs.size < 2) return null
        return Chord(refs.map { it.name })
    }

    /**
     * Valor do chord: 1 quando TODOS os botões estão segurados (> 0.5) e nenhum
     * chord SUPERSET totalmente segurado o suprime (o maior conjunto vence).
     */
    fun chordValue(chord: Chord, held: Set<String>, all: List<Chord>): Float {
        if (!chord.buttons.all { held.contains(it) }) return 0f
        val superseded = all.any { other ->
            other.buttons.size > chord.buttons.size &&
                chord.buttons.all { it in other.buttons } &&
                other.buttons.all { held.contains(it) }
        }
        return if (superseded) 0f else 1f
    }

    /**
     * Supressão do binding SIMPLES: o botão é o FINAL de um chord cujos
     * modificadores estão todos segurados (o chord é o dono do evento).
     */
    fun suppressFinal(chords: List<Chord>, held: Set<String>, button: String): Boolean =
        chords.any { chord ->
            chord.final == button &&
                chord.modifiers.isNotEmpty() &&
                chord.modifiers.all { held.contains(it) }
        }
}
