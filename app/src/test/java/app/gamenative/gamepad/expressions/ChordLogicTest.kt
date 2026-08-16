package app.gamenative.gamepad.expressions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * J2 (spec 2026-08-16-J-expressions-dolphin, §3): chords com suppression — sintaxe
 * `A + B` (só InputRefs puros), valor 1 com todos segurados, supressão do binding
 * simples do botão final e o SUPERCONJUNTO vence (HotkeySuppressions do Dolphin).
 */
class ChordLogicTest {

    @Test
    fun `cadeia de mais com refs puros e chord`() {
        val chord = ChordLogic.parseChord("face_bottom + face_right")
        assertEquals(ChordLogic.Chord(listOf("face_bottom", "face_right")), chord)
        val three = ChordLogic.parseChord("face_bottom + face_right + face_left")
        assertEquals(ChordLogic.Chord(listOf("face_bottom", "face_right", "face_left")), three)
        assertEquals("face_left", three!!.final)
        assertEquals(listOf("face_bottom", "face_right"), three.modifiers)
    }

    @Test
    fun `operando nao puro desqualifica o chord`() {
        // Número, eixo ou outro operador ⇒ soma normal (null).
        assertNull(ChordLogic.parseChord("face_bottom + 1"))
        assertNull(ChordLogic.parseChord("face_bottom + axis:left_y"))
        assertNull(ChordLogic.parseChord("face_bottom and face_right"))
        assertNull(ChordLogic.parseChord("face_bottom"))
        assertNull(ChordLogic.parseChord("face_bottom +")) // erro de parse
        assertNull(ChordLogic.parseChord("deadzone(axis:left_y, 0.3)"))
    }

    @Test
    fun `valor do chord exige todos segurados`() {
        val chord = ChordLogic.Chord(listOf("a", "b"))
        val all = listOf(chord)
        assertEquals(0f, ChordLogic.chordValue(chord, setOf("a"), all))
        assertEquals(0f, ChordLogic.chordValue(chord, emptySet(), all))
        assertEquals(1f, ChordLogic.chordValue(chord, setOf("a", "b"), all))
    }

    @Test
    fun `superset totalmente segurado suprime o menor`() {
        val short = ChordLogic.Chord(listOf("a", "b"))
        val long = ChordLogic.Chord(listOf("a", "b", "c"))
        val all = listOf(short, long)
        // Só o menor segurado → 1.
        assertEquals(1f, ChordLogic.chordValue(short, setOf("a", "b"), all))
        // O maior também segurado → o menor é suprimido; o maior vence.
        assertEquals(0f, ChordLogic.chordValue(short, setOf("a", "b", "c"), all))
        assertEquals(1f, ChordLogic.chordValue(long, setOf("a", "b", "c"), all))
        // O maior SEM todos os extras segurados não suprime o menor.
        assertEquals(1f, ChordLogic.chordValue(short, setOf("a", "b"), all))
    }

    @Test
    fun `supressao do binding simples do final`() {
        val chord = ChordLogic.Chord(listOf("a", "b"))
        val chords = listOf(chord)
        // Modificador segurado → o final é suprimido.
        assertTrue(ChordLogic.suppressFinal(chords, setOf("a"), "b"))
        // Modificador solto → o final passa.
        assertFalse(ChordLogic.suppressFinal(chords, emptySet(), "b"))
        // O modificador não é suprimido (só o FINAL).
        assertFalse(ChordLogic.suppressFinal(chords, setOf("b"), "a"))
        // Botão alheio passa.
        assertFalse(ChordLogic.suppressFinal(chords, setOf("a"), "c"))
    }
}
