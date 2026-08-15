package app.gamenative.gamepad.processing

/**
 * Turbo/rapid-fire PURO (spec 2026-08-16-F-radial-v2-modeshift-turbo, §1.4) — onda
 * quadrada de down/up sintéticos com duty cycle 50%: cada toggle acontece MEIO
 * período depois do anterior, então o ciclo completo (down → up → down) dura
 * [PERIOD_DEFAULT_MS]. Zero android.* — o agendamento real (Handler main) vive no
 * PhysicalControllerHandler; aqui só a decisão determinística de TEMPO.
 *
 * `phase` documenta o estado lógico da fonte no instante `nowMs` (0 = solta,
 * próximo toggle é DOWN; 1 = segurada, próximo toggle é UP) e alterna a cada
 * toggle aplicado pelo handler — o handler injeta a BORDA correspondente à fase
 * no instante retornado.
 */
object TurboScheduler {

    /** Período fixo v2 (spec §1.4) — ciclo completo down→up→down em 80 ms. */
    const val PERIOD_DEFAULT_MS = 80L

    /** Período mínimo aceito (proteção contra divisão por zero / agendamento 0 ms). */
    const val MIN_PERIOD_MS = 2L

    /**
     * Próximo instante (ms, mesmo relógio de `nowMs`) do toggle sintético:
     * `nowMs` + meio período. Determinístico: mesmos argumentos → mesmo resultado;
     * período degradado é clampado a [MIN_PERIOD_MS] (nunca exceção — hot path).
     */
    fun nextToggleAt(nowMs: Long, periodMs: Long, phase: Int): Long =
        nowMs + (periodMs.coerceAtLeast(MIN_PERIOD_MS) / 2L)
}
