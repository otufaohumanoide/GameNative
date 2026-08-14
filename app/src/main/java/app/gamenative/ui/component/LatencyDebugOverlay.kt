package app.gamenative.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gamenative.BuildConfig
import app.gamenative.gamepad.processing.LatencyTracker
import kotlinx.coroutines.delay

/**
 * HUD de latência do F0 (spec 2026-08-15-input-core-avancado) — arquivo PRÓPRIO
 * (limite dex do XServerScreen): mede t0 na ingestão → t1 no PhysicalControllerHandler
 * (KEY/MOTION via dispatch; SENSOR via onSensorSample → applyCameraGyro) e mostra
 * p50/p95 de cada fonte.
 *
 * Toggle via propriedade de sistema, mesmo padrão do harness de input:
 *   adb shell setprop debug.gamenative.latency 1     (liga HUD + coleta)
 *   adb shell setprop debug.gamenative.latency 0     (desliga)
 *
 * A coleta vive no [LatencyTracker] (puro — os pontos de t0/t1 chamam begin/end
 * direto); este composable só liga/desliga a flag e pinta o HUD. `latency:report`
 * (harness V12) faz o dump agregado no logcat sem depender do HUD.
 */
@Composable
fun LatencyDebugOverlay() {
    if (!BuildConfig.DEBUG) return
    var visible by remember { mutableStateOf(false) }
    var snapshots by remember { mutableStateOf<Map<LatencyTracker.Source, LatencyTracker.Snapshot>?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            val on = DebugPropertyCache.read(LATENCY_PROPERTY) == "1"
            LatencyTracker.enabled = on
            if (on) {
                visible = true
                snapshots = LatencyTracker.allSnapshots()
            } else {
                visible = false
                snapshots = null
            }
            delay(500)
        }
    }

    val stats = snapshots
    if (visible && stats != null) {
        Surface(
            modifier = Modifier.padding(start = 8.dp, top = 140.dp),
            shape = RoundedCornerShape(6.dp),
            color = Color(0xCC000000),
            tonalElevation = 0.dp,
        ) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                Text(
                    text = "INPUT LATENCY (p50/p95 ms)",
                    color = Color(0xFF9CCC65),
                    fontSize = 10.sp,
                    style = MaterialTheme.typography.labelSmall,
                )
                for (source in LatencyTracker.Source.entries) {
                    val s = stats[source] ?: LatencyTracker.Snapshot.EMPTY
                    Text(
                        text = if (s.count == 0) {
                            "${source.name}: no samples"
                        } else {
                            "${source.name}: n=${s.count} p50=${fmtMs(s.p50Ms)} p95=${fmtMs(s.p95Ms)} max=${fmtMs(s.maxMs)}"
                        },
                        color = if (s.p95Ms < 16f) Color(0xFF9CCC65) else Color(0xFFFFB74D),
                        fontSize = 11.sp,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

private fun fmtMs(v: Float): String = "%.2f".format(v)

/** Propriedade do toggle (ver DebugPropertyCache — leitura com cache compartilhado). */
const val LATENCY_PROPERTY = "debug.gamenative.latency"
