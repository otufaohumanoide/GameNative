package app.gamenative.gamepad.processing

/**
 * Processador PURO do gyro (spec 2026-08-14-gamepad-u1-gyro, §1.3 — doc de intuito
 * U1): amostras de giroscópio (rad/s, eixos Android: X lateral-direita, Y frontal,
 * Z vertical) → deltas de rotação por amostra, com recenter explícito, ativação por
 * botão (padrão DS4Windows) e deadzone angular com histerese.
 *
 * JVM-testável (V5): nenhum android.*; o estado entre amostras ([GyroState]) vive no
 * hub keyed por deviceId e morre no `removeDevice` (V6).
 *
 * Semântica:
 * - Recenter: na BORDA de ativação (off→on) o offset atual vira zero (bootstrap do
 *   Dolphin — "better than zeros").
 * - Ativação: [activate] vem do botão do perfil (hold) ou true quando sempre ativo.
 * - Calibração contínua (P2-2): janela estável por [GyroConfig.calibPeriodMs]
 *   (default 3 s, Dolphin; 0 = desligada) — o offset passa a ser a média da janela
 *   de repouso, matando o drift do sensor ao longo da sessão (o recenter sozinho
 *   envelhece). Stillness = desvio da média corrida ≤ deadzone em todos os eixos +
 *   accel ≈ 1g quando conhecido (P2-3). Movimento zera a janela.
 * - Deadzone angular com histerese: acima de `deadzone*1.2` passa a valer; abaixo de
 *   `deadzone*0.8` zera (evita tremulação na fronteira).
 * - Sinais: yaw (rotação no plano) → deltaX; pitch (inclinação) → deltaY. O sinal
 *   segue a convenção "girar para a direita = deltaX positivo, inclinar para cima =
 *   deltaY negativo" (verificação on-device pode inverter — anotado no spec).
 * - G6 (spec 2026-08-16-G-gyro-v2): grip angle [GyroConfig.gripAngleDeg] — rotação
 *   do par (X, Z) calibrado em torno do eixo longitudinal ANTES de yaw/pitch e da
 *   deadzone (pad segurado inclinado deixa de misturar pitch/yaw); θ=0 = atual.
 */
data class GyroSample(
    val gyroX: Float,
    val gyroY: Float,
    val gyroZ: Float,
    val nowMs: Long,
    // P2-3 (spec 2026-08-14-gamepad-upgrades-pendencias): accel do MESMO evento
    // (m/s²). 0,0,0 = sem info (harness/device sem accel) — o critério de stillness
    // do accel é ignorado nesse caso.
    val accelX: Float = 0f,
    val accelY: Float = 0f,
    val accelZ: Float = 0f,
)

data class GyroConfig(
    /** Deadzone angular, rad/s (default 0.05 — ~3°/s). */
    val deadzone: Float = 0.05f,
    /**
     * Período da calibração contínua (P2-2 — Dolphin IMUGyroscope), ms. Default 3 s;
     * 0 = desligada (só o recenter de ativação). Expor `gyroCalibPeriod` no perfil é
     * follow-up (registrado no spec).
     */
    val calibPeriodMs: Long = 3000L,
    /**
     * Tolerância de stillness do accel (P2-3 — padrão JoyShockLibrary): |mag/1g − 1|
     * ≤ tolerância para considerar o device parado. 0,0,0 (sem accel) ignora o critério.
     */
    val accelStillnessTolerance: Float = 0.2f,
    /**
     * G6 (spec 2026-08-16-G-gyro-v2, §1): ângulo de pegada (grip) em GRAUS — rotação
     * do par (X, Z) calibrado por θ em torno do eixo longitudinal (Y) ANTES da
     * extração yaw/pitch e da deadzone. 0 = eixos atuais (pad plano — byte-identical).
     * O ângulo vem de [gripAngleFromAccel] (botão "Calibrar grip" do card de
     * diagnóstico) ou do slider da seção Gyro do remap.
     */
    val gripAngleDeg: Float = 0f,
)

/** Estado entre amostras — UMA instância por device, morta no removeDevice (V6). */
class GyroState {
    var active: Boolean = false
    var aboveDeadzone: Boolean = false
    var offsetX: Float = 0f
    var offsetY: Float = 0f
    var offsetZ: Float = 0f
    var lastSample: GyroSample? = null
    // P2-2: acumulador da calibração contínua (janela estável — Dolphin).
    var runningCount: Int = 0
    var runningSumX: Float = 0f
    var runningSumY: Float = 0f
    var runningSumZ: Float = 0f
    var windowStartMs: Long = 0L
}

data class GyroOutput(
    /** Rotação acumulada desde a última amostra, em RADIANOS (yaw → deltaX). */
    val deltaXRad: Float,
    /** Rotação acumulada desde a última amostra, em RADIANOS (pitch → deltaY). */
    val deltaYRad: Float,
    /**
     * Velocidade angular morta pela deadzone, em RAD/S (yaw) — P1-2 do spec
     * 2026-08-14-gamepad-upgrades-pendencias: o CAMERA mode é controle de TAXA
     * (padrão DS4Windows/JoyShockLibrary), não integral — a deflexão é função da
     * velocidade, e parar de girar ⇒ deflexão volta a 0.
     */
    val yawRadS: Float,
    /** Velocidade angular morta pela deadzone, em RAD/S (pitch) — ver [yawRadS]. */
    val pitchRadS: Float,
    /** false = inativo (botão solto) ou primeira amostra (sem delta ainda). */
    val active: Boolean,
) {
    companion object {
        val NONE = GyroOutput(0f, 0f, 0f, 0f, false)
    }
}

object GyroProcessor {

    /**
     * Reaplica a âncora de offset do [state] com a [sample] (spec 2026-08-16-C, §1.2 —
     * refator pura): a MESMA operação da borda de ativação (off→on), extraída para ser
     * reutilizada pelo recenter explícito do card de diagnóstico
     * (`GamepadHub.recenterGyro`). Não altera `active`/calibração — só o offset.
     */
    fun recenter(state: GyroState, sample: GyroSample) {
        state.offsetX = sample.gyroX
        state.offsetY = sample.gyroY
        state.offsetZ = sample.gyroZ
    }

    /**
     * G6 (spec 2026-08-16-G-gyro-v2, §1): ângulo de pegada a partir do accel
     * corrente — θ = atan2(ax, az) em GRAUS (padrão JoyShockLibrary de grip angle).
     * O accel em repouso mede a gravidade no frame do pad: com o pad plano, ax = 0 e
     * az ≈ 1g ⇒ θ = 0; inclinado, a componente ax aparece e θ descreve a rotação no
     * eixo longitudinal (Y). O valor alimenta o botão "Calibrar grip" do
     * DeviceDiagnosticsCard (GamepadHub.calibrateGrip) e o perfil
     * (gyroGripAngleDeg). Pura: sem android.*, testada em GyroProcessorTest.
     */
    fun gripAngleFromAccel(accelX: Float, accelZ: Float): Float =
        Math.toDegrees(kotlin.math.atan2(accelX.toDouble(), accelZ.toDouble())).toFloat()

    fun process(sample: GyroSample, state: GyroState, config: GyroConfig, activate: Boolean): GyroOutput {
        // Recenter na borda de ativação (off→on): o desvio atual vira zero — padrão
        // DS4Windows ("recenter a cada ativação"). Operação compartilhada com o
        // recenter explícito do card de diagnóstico (spec 2026-08-16-C, §1.2).
        if (activate && !state.active) {
            recenter(state, sample)
        }
        state.active = activate
        if (!activate) {
            state.lastSample = null
            state.aboveDeadzone = false
            resetCalibration(state)
            return GyroOutput.NONE
        }

        // P2-2: calibração contínua roda ANTES do delta (o offset pode mudar nesta
        // amostra quando a janela completa — o delta abaixo já usa o offset novo).
        updateCalibration(sample, state, config)

        val last = state.lastSample
        state.lastSample = sample
        if (last == null) {
            // Primeira amostra do período ativo: ancora, sem delta nem velocidade.
            return GyroOutput(0f, 0f, 0f, 0f, true)
        }

        val dt = ((sample.nowMs - last.nowMs).coerceIn(1L, 100L)) / 1000f
        val calX = sample.gyroX - state.offsetX
        val calZ = sample.gyroZ - state.offsetZ
        // G6 (spec 2026-08-16-G-gyro-v2, §1): grip angle — rotação do par (X, Z)
        // CALIBRADO por θ em torno do eixo longitudinal (Y), ANTES da extração
        // yaw/pitch e da deadzone. A calibração contínua continua usando o par
        // raw−offset por eixo (janela inalterada — a rotação só afeta o delta).
        // θ = atan2(ax, az) do accel é o INVERSO da rotação física do pad; compensar
        // é girar por −θ (R(−θ): x' = x·cosθ − z·sinθ, z' = x·sinθ + z·cosθ).
        // θ=0 degenera para os eixos atuais (byte-identical).
        val rotX: Float
        val rotZ: Float
        if (config.gripAngleDeg == 0f) {
            rotX = calX
            rotZ = calZ
        } else {
            val gripRad = Math.toRadians(config.gripAngleDeg.toDouble())
            val cosG = kotlin.math.cos(gripRad).toFloat()
            val sinG = kotlin.math.sin(gripRad).toFloat()
            rotX = calX * cosG - calZ * sinG
            rotZ = calX * sinG + calZ * cosG
        }
        var yaw = -rotZ // girar à direita = -Z → +deltaX
        var pitch = -rotX // inclinar para cima = -X → -deltaY
        // Histerese correta (P1-4 do spec 2026-08-14-gamepad-upgrades-pendencias):
        // entrada 1.2× / saída 0.8× — acima de deadzone*1.2 passa a valer; abaixo de
        // deadzone*0.8 zera. O estado usa o MESMO threshold aplicado (o deadzone cru
        // desincronizava o limiar e invertia o comportamento na banda 0.8×–1.2×).
        val threshold = if (state.aboveDeadzone) config.deadzone * 0.8f else config.deadzone * 1.2f
        yaw = if (kotlin.math.abs(yaw) < threshold) 0f else yaw
        pitch = if (kotlin.math.abs(pitch) < threshold) 0f else pitch
        state.aboveDeadzone = kotlin.math.abs(yaw) >= threshold ||
            kotlin.math.abs(pitch) >= threshold
        return GyroOutput(
            deltaXRad = yaw * dt,
            deltaYRad = pitch * dt,
            yawRadS = yaw,
            pitchRadS = pitch,
            active = true,
        )
    }

    /**
     * P2-2 (spec 2026-08-14-gamepad-upgrades-pendencias) — calibração contínua por
     * janela estável (base: Dolphin IMUGyroscope, lido em
     * reference/dolphin/Source/Core/InputCommon/ControllerEmu/ControlGroup/
     * IMUGyroscope.cpp — período, média corrida, movimento invalida):
     * - Período 0 ⇒ desligada (só o recenter de ativação — comportamento atual);
     * - Sem input gate (gyro inativo) ⇒ nada acumula (a fonte nem registra fora do
     *   container — P1-3 — o gate sai de graça);
     * - Stillness = |VELOCIDADE CALIBRADA| < deadzone em TODOS os eixos (critério
     *   ABSOLUTO da especificação — divergência deliberada do Dolphin: o desvio da
     *   média corrida dele calibra em cima de rotação CONSTANTE (o pan de câmera
     *   congelaria após o período); o absoluto só completa janela com repouso real);
     * - Accel conhecido (P2-3): |mag/1g − 1| > tolerância ⇒ movimento ⇒ zera
     *   (padrão JoyShockLibrary — o accel vê translação que o gyro mascara);
     * - Janela completa (período) ⇒ offset = média da janela (estável por definição —
     *   suaviza o recenter ruidoso e acompanha o drift lento do sensor) e reinicia.
     */
    private fun updateCalibration(sample: GyroSample, state: GyroState, config: GyroConfig) {
        val periodMs = config.calibPeriodMs
        if (periodMs <= 0L) return
        if (!state.active) {
            resetCalibration(state)
            return
        }
        // P2-3: stillness pelo accel (padrão JoyShockLibrary) — mais confiável que o
        // gyro sozinho (ruído do gyro pode mascarar micro-movimento lento).
        val hasAccel = sample.accelX != 0f || sample.accelY != 0f || sample.accelZ != 0f
        if (hasAccel && !accelStill(sample, config)) {
            resetCalibration(state)
            return
        }
        // Stillness pelo gyro: velocidade calibrada (raw − offset atual) dentro da
        // deadzone em TODOS os eixos. Movimento (inclusive rotação constante) zera a
        // janela sem empurrar a amostra — nunca calibra em cima de movimento.
        val still = kotlin.math.abs(sample.gyroZ - state.offsetZ) < config.deadzone &&
            kotlin.math.abs(sample.gyroX - state.offsetX) < config.deadzone &&
            kotlin.math.abs(sample.gyroY - state.offsetY) < config.deadzone
        if (!still) {
            resetCalibration(state)
            return
        }
        if (state.runningCount == 0) {
            state.windowStartMs = sample.nowMs
        }
        state.runningCount++
        state.runningSumX += sample.gyroX
        state.runningSumY += sample.gyroY
        state.runningSumZ += sample.gyroZ
        if (sample.nowMs - state.windowStartMs >= periodMs) {
            state.offsetX = state.runningSumX / state.runningCount
            state.offsetY = state.runningSumY / state.runningCount
            state.offsetZ = state.runningSumZ / state.runningCount
            resetCalibration(state)
        }
    }

    private fun accelStill(sample: GyroSample, config: GyroConfig): Boolean {
        val mag = kotlin.math.sqrt(
            sample.accelX * sample.accelX + sample.accelY * sample.accelY + sample.accelZ * sample.accelZ,
        )
        return kotlin.math.abs(mag / GRAVITY_M_S2 - 1f) <= config.accelStillnessTolerance
    }

    private fun resetCalibration(state: GyroState) {
        state.runningCount = 0
        state.runningSumX = 0f
        state.runningSumY = 0f
        state.runningSumZ = 0f
        state.windowStartMs = 0L
    }

    private const val GRAVITY_M_S2 = 9.81f
}
