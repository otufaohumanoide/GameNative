package app.gamenative.gamepad.expressions

import app.gamenative.gamepad.GamepadAxis
import app.gamenative.gamepad.GamepadButton
import app.gamenative.gamepad.InputEvent

/**
 * J1 §2.2: camada PURA entre o hub e o avaliador — extrai os bindings `expr:` dos
 * bindings efetivos (parse no CACHE, não no hot path), avalia e converte em
 * eventos lógicos (transição 0↔1 no threshold 0.5 ⇒ ButtonDown/Up do botão dono;
 * eixo ⇒ AxisMotion contínuo). Parse com erro ⇒ binding PULADO (degrade, nunca
 * crash). Testável em JVM (o fake do hub usa isto — spec §4).
 */
object ExprBindingProcessor {

    data class Parsed(
        val button: GamepadButton,
        val axis: GamepadAxis?,
        val source: String,
        val ast: ExprAst,
        val index: Int,
        /** J2 §3: chord do source (cadeia top-level de `+` com InputRefs puros). */
        val chord: ChordLogic.Chord? = null,
    )

    /** Tokens `expr:` dos bindings efetivos → bindings parseados (erros pulados). */
    fun parseBindings(effectiveBindings: Map<String, String>): List<Parsed> {
        val result = mutableListOf<Parsed>()
        var index = 0
        for ((name, token) in effectiveBindings) {
            if (!token.startsWith("expr:")) continue
            val source = token.removePrefix("expr:")
            val button = GamepadButton.entries.firstOrNull { it.name == name } ?: continue
            val ast = runCatching { ExprParser.parse(source) }.getOrNull() ?: continue
            val axis = GamepadAxis.entries.firstOrNull { it.name == name }
            result += Parsed(button, axis, source, ast, index++, chord = ChordLogic.parseChord(source))
        }
        return result
    }

    /** True quando o mapa de bindings contém PELO MENOS um token `expr:`. */
    fun hasExpressionTokens(effectiveBindings: Map<String, String>): Boolean =
        effectiveBindings.values.any { it.startsWith("expr:") }

    /**
     * Chords do conjunto de bindings (J2 §3 — registro por perfil, derivado do
     * cache de parse; sem chord ⇒ lista vazia).
     */
    fun chordsOf(bindings: List<Parsed>): List<ChordLogic.Chord> =
        bindings.mapNotNull { it.chord }

    /**
     * Avalia TODOS os bindings e devolve os eventos lógicos a emitir. O nível
     * emitido por binding vive em `expr<idx>|out` (transições); eixos emitem
     * AxisMotion só quando o valor muda.
     *
     * J2 §3: binding de CHORD avalia via [ChordLogic.chordValue] (o AST é só a
     * forma sintática); o evento SIMPLES do botão final de um chord armado é
     * SUPRIMIDO aqui (o chord é o dono) — a supressão do caminho FÍSICO é do hub.
     */
    fun evaluate(
        bindings: List<Parsed>,
        reader: (String, Boolean) -> Float,
        state: ExprState,
        dtMs: Long,
        nowMs: Long,
        deviceId: Int,
        heldButtons: Set<String> = emptySet(),
        chords: List<ChordLogic.Chord> = emptyList(),
    ): List<InputEvent> {
        val events = mutableListOf<InputEvent>()
        for (binding in bindings) {
            // Supressão do binding simples: botão final de chord armado não emite
            // por conta própria (o chord emite).
            if (binding.chord == null &&
                ChordLogic.suppressFinal(chords, heldButtons, binding.button.name.lowercase())
            ) {
                continue
            }
            val value = binding.chord?.let { chord ->
                ChordLogic.chordValue(chord, heldButtons, chords)
            } ?: ExprEvaluator.eval(binding.ast, reader, state, dtMs, nowMs)
            val outKey = "expr${binding.index}|out"
            val out = state.funcs.getOrPut(outKey) { FuncState() }
            val level = if (value > ExprFuncs.THRESHOLD) 1f else 0f
            if (level != out.value) {
                out.value = level
                events += if (level == 1f) {
                    InputEvent.ButtonDown(deviceId, binding.button)
                } else {
                    InputEvent.ButtonUp(deviceId, binding.button)
                }
            }
            val axis = binding.axis
            if (axis != null) {
                val axisKey = "expr${binding.index}|axis"
                val prev = state.funcs.getOrPut(axisKey) { FuncState() }
                val clamped = value.coerceIn(0f, 1f)
                if (clamped != prev.value) {
                    prev.value = clamped
                    events += InputEvent.AxisMotion(deviceId, axis, clamped)
                }
            }
        }
        return events
    }
}
