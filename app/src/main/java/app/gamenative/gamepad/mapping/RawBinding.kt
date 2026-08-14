package app.gamenative.gamepad.mapping

/**
 * ONDE um controle físico emite um botão/eixo (spec 2026-08-13, Parte I §4).
 *
 * - [Key]: keycode Android REAL (tabela de AndroidConstants) — ex.: 96 = BUTTON_A.
 * - [Axis]: eixo físico (constante AXIS_* real) com direção ±1 — metade positiva/negativa
 *   ou invertido. direction 0 é PROIBIDO (a gramática SDL distingue `aN`/+`aN`/`-aN`/`~aN`;
 *   o modelo colapsa em ±1 conforme a Parte I §4).
 * - [Hat]: hat N com bitmask SDL (1=up, 2=right, 4=down, 8=left) — ex.: `h0.4` = hat 0,
 *   máscara 4 (down). No Android o hat chega como AXIS_HAT_X/Y e o TRADUTOR converte.
 */
sealed interface RawBinding {
    data class Key(val keyCode: Int) : RawBinding
    data class Axis(val axis: Int, val direction: Int) : RawBinding
    data class Hat(val hat: Int, val mask: Int) : RawBinding
}
