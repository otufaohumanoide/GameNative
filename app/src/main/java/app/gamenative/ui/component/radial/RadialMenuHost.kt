package app.gamenative.ui.component.radial

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.gamenative.PluviaApp
import app.gamenative.events.GamepadLayerEvent
import app.gamenative.gamepad.profiles.ActionLayer
import app.gamenative.gamepad.radial.RadialMenuConfig
import app.gamenative.gamepad.radial.RadialMenuExecutor
import app.gamenative.gamepad.radial.RadialMenuStore
import java.io.File

/**
 * Estado do Radial Menu mantido pelo XServerScreen (F3.1 do spec
 * 2026-08-15-input-core-avancado) — UM holder Compose em vez de vários estados
 * soltos (registro dex do XServerScreen no limite). Escrito/observado pelo
 * [RadialMenuHost] e pelo QuickMenu (editor).
 */
class RadialMenuStateHolder {
    var open by mutableStateOf(false)
    var deviceId by mutableIntStateOf(-1)
    var editorOpen by mutableStateOf(false)
}

/**
 * HOST do Radial Menu — arquivo PRÓPRIO (limite dex do XServerScreen): toda a
 * lógica da F3.1 vive aqui — store por jogo, listener do gatilho de camada (U3),
 * pause/resume do jogo e renderização do overlay + editor.
 *
 * O XServerScreen só guarda o holder e chama este composable dentro da Box
 * principal (mesma posição do LatencyDebugOverlay).
 *
 * Ciclo: camada de gatilho ATIVA → abre (pausa o jogo se nenhum outro overlay já
 * pausou — `pauseGame`/`resumeGame` são os callbacks do XServerScreen, que
 * reusam pauseForOverlayIfAllowed/resumeIfAllowedAfterOverlay e as políticas de
 * suspend) → seleção touch/stick → executa o macro E fecha (retoma) → camada
 * desativa → fecha (retoma). Pause/resume par-e-par: só retoma o que este host
 * pausou (QuickMenu/editor têm ciclo próprio — isOverlayPaused é compartilhado).
 */
@Composable
fun RadialMenuHost(
    containerId: String,
    state: RadialMenuStateHolder,
    filesDir: File,
    pauseGame: () -> Unit,
    resumeGame: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val store = remember { RadialMenuStore(File(File(filesDir, "gamepad"), "radial_menus.json")) }
    // MUTÁVEL: o editor salva e o host passa a usar a config nova NA MESMA sessão
    // (sem isto o overlay usaria a config velha até reiniciar o container).
    var config by remember(containerId) {
        mutableStateOf(store.load(containerId) ?: RadialMenuConfig())
    }

    var pausedByRadial by remember { mutableStateOf(false) }

    // Gatilho: eventos de camada do hub (resolveLayerTriggers). Registrado UMA vez;
    // holders vivos (lição C1) — `state`/`config`/`pausedByRadial` são estáveis.
    DisposableEffect(config) {
        val listener: (GamepadLayerEvent) -> Unit = { event ->
            if (event.layer == config.triggerLayer) {
                if (event.activated) {
                    if (config.sectors.isNotEmpty()) {
                        state.deviceId = event.deviceId
                        state.open = true
                        // Só pausa o que não estava pausado (QuickMenu/editor têm
                        // ciclo próprio — isOverlayPaused é compartilhado).
                        pausedByRadial = !PluviaApp.isOverlayPaused
                        if (pausedByRadial) pauseGame()
                    }
                } else if (state.open) {
                    state.open = false
                    if (pausedByRadial) resumeGame()
                    pausedByRadial = false
                }
            }
        }
        PluviaApp.events.on<GamepadLayerEvent, Unit>(listener)
        onDispose {
            PluviaApp.events.off<GamepadLayerEvent, Unit>(listener)
        }
    }

    if (state.open) {
        RadialMenuOverlay(
            config = config,
            deviceId = state.deviceId,
            onExecute = { sector ->
                state.open = false
                if (pausedByRadial) resumeGame()
                pausedByRadial = false
                if (activity != null) {
                    RadialMenuExecutor.execute(sector.keys, state.deviceId, activity)
                }
            },
            onCancel = {
                state.open = false
                if (pausedByRadial) resumeGame()
                pausedByRadial = false
            },
        )
    }

    if (state.editorOpen) {
        val deviceId = state.deviceId
        val profileLayers = if (deviceId >= 0) {
            PluviaApp.gamepadHub.profileFor(deviceId, PluviaApp.gamepadHub.activeAppId)
                .layers.keys.filter { it != ActionLayer.DEFAULT.name }
        } else {
            emptyList()
        }
        RadialMenuEditorDialog(
            deviceId = deviceId,
            profileLayers = profileLayers,
            config = config,
            onSave = { saved ->
                store.save(containerId, saved)
                config = saved
                state.editorOpen = false
            },
            onDismiss = { state.editorOpen = false },
        )
    }
}
