package app.gamenative.gamepad.radial

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Executor ANDROID de um macro do Radial Menu (F3.1 do spec 2026-08-15-input-core-
 * avancado): dispara KeyEvents sintéticos pelo MESMO caminho do harness de debug
 * (activity.dispatchKeyEvent, SOURCE_GAMEPAD, deviceId do jogador) — o input passa
 * pelo roteamento normal e chega ao jogo. O PLANO (timing) é puro
 * ([RadialMenuPlan]); aqui só existe o disparo com Handler postDelayed.
 *
 * Execução: o overlay fecha e o jogo retoma ANTES do execute (o chamador garante),
 * então os eventos caem no caminho do jogo (overlayInputContext NONE).
 */
object RadialMenuExecutor {

    private val handler = Handler(Looper.getMainLooper())

    /** Dispara o macro (lista vazia = no-op). */
    fun execute(keys: List<RadialMacroKey>, deviceId: Int, activity: Activity) {
        if (keys.isEmpty()) return
        val plan = RadialMenuPlan.plan(keys)
        for (step in plan) {
            handler.postDelayed(
                { dispatchKey(activity, deviceId, step.keyCode, KeyEvent.ACTION_DOWN) },
                step.downAtMs,
            )
            handler.postDelayed(
                { dispatchKey(activity, deviceId, step.keyCode, KeyEvent.ACTION_UP) },
                step.upAtMs,
            )
        }
    }

    private fun dispatchKey(activity: Activity, deviceId: Int, keyCode: Int, action: Int) {
        val now = SystemClock.uptimeMillis()
        val event = KeyEvent(
            now, now, action, keyCode, 0, 0, deviceId, 0, 0, InputDevice.SOURCE_GAMEPAD,
        )
        runCatching { activity.dispatchKeyEvent(event) }
    }
}
