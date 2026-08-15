package app.gamenative.gamepad.layers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * I (spec 2026-08-16-I-trigger-engine-keymapper, §4): motor PURO dos modos novos —
 * LONG_PRESS (limiar via onClock, soltar antes = nada, soltar depois = Deactivate)
 * e SEQUENCE (completa descartando o retardo, botão errado/timeout liberando o
 * retardo, timeout POR PASSO, overlap com a mais longa vencendo). HOLD/TOGGLE/
 * DOUBLE_TAP continuam no LayerResolver (intocado — testes existentes verdes).
 */
class TriggerEngineTest {

    private fun spec(vararg buttons: String, timeout: Int = 400): LayerTriggerSpec =
        LayerTriggerSpec(
            button = buttons.first(),
            mode = LayerTriggerMode.SEQUENCE,
            sequence = buttons.drop(1),
            seqTimeoutMs = timeout,
        )

    // ── LONG_PRESS ────────────────────────────────────────────────────────

    @Test
    fun `long press ativa no limiar via onClock`() {
        val state = TriggerEngineState()
        val specs = mapOf("SNIPER" to LayerTriggerSpec("LEFT_STICK", LayerTriggerMode.LONG_PRESS, longPressMs = 500))
        val trigger = specs.getValue("SNIPER")
        // Down consome e arma (isShift implícito — o botão não chega ao jogo).
        assertEquals(listOf(TriggerOutcome.Consume), TriggerEngine.onButtonDown(state, specs, "SNIPER", trigger, "LEFT_STICK", 1000L))
        // Antes do limiar: nada.
        assertTrue(TriggerEngine.onClock(state, specs, 1499L).isEmpty())
        // No limiar: Activate (e o clock não re-dispara).
        assertEquals(listOf(TriggerOutcome.Activate("SNIPER")), TriggerEngine.onClock(state, specs, 1500L))
        assertTrue(TriggerEngine.onClock(state, specs, 1600L).isEmpty())
    }

    @Test
    fun `long press soltar antes do limiar nao ativa nem emite`() {
        val state = TriggerEngineState()
        val specs = mapOf("SNIPER" to LayerTriggerSpec("LEFT_STICK", LayerTriggerMode.LONG_PRESS, longPressMs = 500))
        val trigger = specs.getValue("SNIPER")
        TriggerEngine.onButtonDown(state, specs, "SNIPER", trigger, "LEFT_STICK", 1000L)
        // Up antes do limiar: só Consume — NEM Activate, NEM Deactivate, nada vaza.
        assertEquals(listOf(TriggerOutcome.Consume), TriggerEngine.onButtonUp(state, specs, "SNIPER", trigger, "LEFT_STICK", 1300L))
        assertTrue(state.longPressArmed == null)
        assertTrue(TriggerEngine.onClock(state, specs, 1600L).isEmpty())
    }

    @Test
    fun `long press ativar e soltar desativa`() {
        val state = TriggerEngineState()
        val specs = mapOf("SNIPER" to LayerTriggerSpec("LEFT_STICK", LayerTriggerMode.LONG_PRESS, longPressMs = 500))
        val trigger = specs.getValue("SNIPER")
        TriggerEngine.onButtonDown(state, specs, "SNIPER", trigger, "LEFT_STICK", 1000L)
        assertEquals(listOf(TriggerOutcome.Activate("SNIPER")), TriggerEngine.onClock(state, specs, 1500L))
        assertEquals(
            listOf(TriggerOutcome.Deactivate("SNIPER"), TriggerOutcome.Consume),
            TriggerEngine.onButtonUp(state, specs, "SNIPER", trigger, "LEFT_STICK", 1700L),
        )
    }

    @Test
    fun `long press limiar configuravel`() {
        val state = TriggerEngineState()
        val specs = mapOf("L" to LayerTriggerSpec("A", LayerTriggerMode.LONG_PRESS, longPressMs = 200))
        val trigger = specs.getValue("L")
        TriggerEngine.onButtonDown(state, specs, "L", trigger, "A", 1000L)
        assertTrue(TriggerEngine.onClock(state, specs, 1199L).isEmpty())
        assertEquals(listOf(TriggerOutcome.Activate("L")), TriggerEngine.onClock(state, specs, 1200L))
    }

    // ── SEQUENCE ──────────────────────────────────────────────────────────

    @Test
    fun `sequence completa com retardo do primeiro botao descartado`() {
        val state = TriggerEngineState()
        val specs = mapOf("RADIAL" to spec("A", "B"))
        val trigger = specs.getValue("RADIAL")
        // Arma: o Down de A fica RETARDADO (disambiguação #1386).
        assertEquals(
            listOf(TriggerOutcome.DelayEmit("A", 1400L)),
            TriggerEngine.onButtonDown(state, specs, "RADIAL", trigger, "A", 1000L),
        )
        // Completa: ativa e DESCARTA o retardo.
        assertEquals(
            listOf(TriggerOutcome.Activate("RADIAL"), TriggerOutcome.ConsumeDelay("A")),
            TriggerEngine.onButtonDown(state, specs, "RADIAL", trigger, "B", 1100L),
        )
        assertTrue(state.seqProgress.isEmpty())
        // O Up de A ainda é consumido (o Down dele nunca chegou ao jogo — balanço).
        assertEquals(listOf(TriggerOutcome.Consume), TriggerEngine.onButtonUp(state, specs, "RADIAL", trigger, "A", 1200L))
    }

    @Test
    fun `botao errado mata a sequencia e libera o retardo`() {
        val state = TriggerEngineState()
        val specs = mapOf("R" to spec("A", "B", "C"))
        val trigger = specs.getValue("R")
        TriggerEngine.onButtonDown(state, specs, "R", trigger, "A", 1000L)
        TriggerEngine.onButtonDown(state, specs, "R", trigger, "B", 1100L)
        // D está FORA da lista → não é consumido; a sequência morre e libera A.
        assertEquals(
            listOf(TriggerOutcome.ReleaseDelay("A")),
            TriggerEngine.onButtonDown(state, specs, "R", trigger, "D", 1200L),
        )
        assertTrue(state.seqProgress.isEmpty())
        // O Down de A foi liberado → o Up de A passa (não é mais consumido).
        assertTrue(TriggerEngine.onButtonUp(state, specs, "R", trigger, "A", 1300L).isEmpty())
    }

    @Test
    fun `botao da sequencia fora de ordem e consumido e mata`() {
        val state = TriggerEngineState()
        val specs = mapOf("R" to spec("A", "B", "C"))
        val trigger = specs.getValue("R")
        TriggerEngine.onButtonDown(state, specs, "R", trigger, "A", 1000L)
        // C espera o passo 2 (B): C É da lista → consumido (tecla do trigger pendente).
        assertEquals(
            listOf(TriggerOutcome.Consume, TriggerOutcome.ReleaseDelay("A")),
            TriggerEngine.onButtonDown(state, specs, "R", trigger, "C", 1100L),
        )
    }

    @Test
    fun `timeout sem proximo passo libera o retardo`() {
        val state = TriggerEngineState()
        val specs = mapOf("R" to spec("A", "B"))
        val trigger = specs.getValue("R")
        TriggerEngine.onButtonDown(state, specs, "R", trigger, "A", 1000L)
        // Clock no vencimento: a sequência morre e o retardo é LIBERADO.
        assertEquals(listOf(TriggerOutcome.ReleaseDelay("A")), TriggerEngine.onClock(state, specs, 1400L))
        assertTrue(state.seqProgress.isEmpty())
    }

    @Test
    fun `timeout por passo - B aceito mas demora no C morre no C`() {
        val state = TriggerEngineState()
        val specs = mapOf("R" to spec("A", "B", "C"))
        val trigger = specs.getValue("R")
        TriggerEngine.onButtonDown(state, specs, "R", trigger, "A", 1000L)
        TriggerEngine.onButtonDown(state, specs, "R", trigger, "B", 1200L)
        // O passo B re-armou o relógio (timeout POR PASSO, não total): 1500 ainda vive.
        assertTrue(TriggerEngine.onClock(state, specs, 1500L).isEmpty())
        assertEquals(listOf(TriggerOutcome.ReleaseDelay("A")), TriggerEngine.onClock(state, specs, 1600L))
    }

    @Test
    fun `sequencia de 3 passos completa`() {
        val state = TriggerEngineState()
        val specs = mapOf("R" to spec("A", "B", "C"))
        val trigger = specs.getValue("R")
        TriggerEngine.onButtonDown(state, specs, "R", trigger, "A", 1000L)
        TriggerEngine.onButtonDown(state, specs, "R", trigger, "B", 1100L)
        assertEquals(
            listOf(TriggerOutcome.Activate("R"), TriggerOutcome.ConsumeDelay("A")),
            TriggerEngine.onButtonDown(state, specs, "R", trigger, "C", 1200L),
        )
    }

    // ── OVERLAP (mesmo 1º botão) ──────────────────────────────────────────

    @Test
    fun `overlap - a sequencia longa que completa vence`() {
        val state = TriggerEngineState()
        val short = spec("A", "B")
        val long = spec("A", "B", "C")
        val specs = mapOf("SHORT" to short, "LONG" to long)
        TriggerEngine.onButtonDown(state, specs, "SHORT", short, "A", 1000L)
        TriggerEngine.onButtonDown(state, specs, "LONG", long, "A", 1000L)
        // B completa a CURTA, mas a LONGA ainda pendente → ativação RETARDADA
        // (só o retardo é descartado por enquanto).
        assertEquals(
            listOf(TriggerOutcome.ConsumeDelay("A")),
            TriggerEngine.onButtonDown(state, specs, "SHORT", short, "B", 1100L),
        )
        assertEquals(
            listOf(TriggerOutcome.DelayEmit("A", 1500L)),
            TriggerEngine.onButtonDown(state, specs, "LONG", long, "B", 1100L),
        )
        // C completa a LONGA: só ela ativa (a curta foi descartada).
        assertEquals(
            listOf(TriggerOutcome.Activate("LONG"), TriggerOutcome.ConsumeDelay("A")),
            TriggerEngine.onButtonDown(state, specs, "LONG", long, "C", 1200L),
        )
    }

    @Test
    fun `overlap - a curta ativa quando a longa morre no botao extra`() {
        val state = TriggerEngineState()
        val short = spec("A", "B")
        val long = spec("A", "B", "C")
        val specs = mapOf("SHORT" to short, "LONG" to long)
        TriggerEngine.onButtonDown(state, specs, "SHORT", short, "A", 1000L)
        TriggerEngine.onButtonDown(state, specs, "LONG", long, "A", 1000L)
        TriggerEngine.onButtonDown(state, specs, "SHORT", short, "B", 1100L)
        TriggerEngine.onButtonDown(state, specs, "LONG", long, "B", 1100L)
        // D (botão extra) mata a LONGA → a CURTA (completada) ativa agora.
        assertEquals(
            listOf(TriggerOutcome.Activate("SHORT"), TriggerOutcome.ReleaseDelay("A")),
            TriggerEngine.onButtonDown(state, specs, "LONG", long, "D", 1200L),
        )
    }

    @Test
    fun `overlap - timeout da longa libera a curta`() {
        val state = TriggerEngineState()
        val short = spec("A", "B")
        val long = spec("A", "B", "C")
        val specs = mapOf("SHORT" to short, "LONG" to long)
        TriggerEngine.onButtonDown(state, specs, "SHORT", short, "A", 1000L)
        TriggerEngine.onButtonDown(state, specs, "LONG", long, "A", 1000L)
        TriggerEngine.onButtonDown(state, specs, "SHORT", short, "B", 1100L)
        TriggerEngine.onButtonDown(state, specs, "LONG", long, "B", 1100L)
        // Vencimento do passo C da longa → a curta ativa + retardo liberado.
        assertEquals(
            listOf(TriggerOutcome.Activate("SHORT"), TriggerOutcome.ReleaseDelay("A")),
            TriggerEngine.onClock(state, specs, 1500L),
        )
    }

    // ── DEGRADAÇÃO ─────────────────────────────────────────────────────────

    @Test
    fun `modos antigos nao passam pelo engine`() {
        val state = TriggerEngineState()
        val specs = mapOf(
            "H" to LayerTriggerSpec("A", LayerTriggerMode.HOLD),
            "T" to LayerTriggerSpec("B", LayerTriggerMode.TOGGLE),
            "D" to LayerTriggerSpec("C", LayerTriggerMode.DOUBLE_TAP),
        )
        for ((layer, trigger) in specs) {
            assertTrue(TriggerEngine.onButtonDown(state, specs, layer, trigger, trigger.button, 1000L).isEmpty())
            assertTrue(TriggerEngine.onButtonUp(state, specs, layer, trigger, trigger.button, 1100L).isEmpty())
        }
        assertTrue(TriggerEngine.onClock(state, specs, 2000L).isEmpty())
        assertTrue(state.seqProgress.isEmpty())
        assertTrue(state.longPressArmed == null)
    }

    @Test
    fun `up de botao que nao pertence a sequencia pendente passa`() {
        val state = TriggerEngineState()
        val specs = mapOf("R" to spec("A", "B"))
        val trigger = specs.getValue("R")
        TriggerEngine.onButtonDown(state, specs, "R", trigger, "A", 1000L)
        // Up de um botão alheio: não é consumido pelo engine.
        assertTrue(TriggerEngine.onButtonUp(state, specs, "R", trigger, "X", 1100L).isEmpty())
    }
}
