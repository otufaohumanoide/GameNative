package app.gamenative.gamepad

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import timber.log.Timber

/**
 * Fonte de sensores por device (spec 2026-08-14-gamepad-u1-gyro, §1.4 — doc de
 * intuito U1): `InputDevice.getSensorManager()` (API **31+**) com listener no manager
 * DO device.
 *
 * Threading (P2-7 do spec 2026-08-14-gamepad-upgrades-pendencias — decisão A):
 * `registerListener` sem Handler associa o listener ao Looper da thread que chamou
 * [setSuspended]/[onDeviceAdded] — SEMPRE a main (MainActivity.onResume/onPause e
 * XServerScreen). Entrega portanto na MAIN THREAD, nunca concorrente; registrar
 * listeners de sensor de outra thread exigiria Handler explícito + coleções
 * sincronizadas (decisão B — não adotada).
 *
 * Lifecycle (V3 — vazamento = bateria drenando com o app "fechado"):
 * - [setSuspended]: register quando o container roda E o app está em foreground;
 *   unregister em pause/exit/screen-off. O XServerScreen manda `false` quando o jogo
 *   inicia e `true` no exit; o MainActivity suspende em onPause e retoma em onResume
 *   (só quando um container está de pé).
 * - [stop]: unregister total (hub.stop / processo morrendo).
 *
 * Rate: [SensorManager.SENSOR_DELAY_GAME] (~50 Hz — suficiente para cursor; FASTEST
 * é opt-in futuro, custo de bateria — decisão do intuito U1(c)).
 */
class GamepadSensorSource(private val hub: GamepadHub) {

    private val registered = mutableMapOf<Int, SensorEventListener>()
    // P2-1 (spec 2026-08-14-gamepad-upgrades-pendencias): último timestamp do SENSOR
    // (ns) por device — guarda de monotonicidade para o dt derivado do evento.
    // Acesso só da main thread (P2-7 — decisão A).
    private val lastSensorTsNs = mutableMapOf<Int, Long>()
    private var started = false

    @Volatile
    private var suspended = true

    fun start() {
        started = true
        if (!suspended) registerAll()
    }

    fun stop() {
        started = false
        unregisterAll()
    }

    /** Suspend/retoma os listeners (pause/exit/screen-off vs container ativo). */
    fun setSuspended(suspend: Boolean) {
        suspended = suspend
        if (!started) return
        if (suspend) unregisterAll() else registerAll()
    }

    /** Hotplug (chamado pelo hub): registra device novo quando ativo. */
    fun onDeviceAdded(deviceId: Int) {
        if (started && !suspended) register(deviceId)
    }

    /** Hotplug: desregistra (V6 — deviceId efêmero). */
    fun onDeviceRemoved(deviceId: Int) {
        unregister(deviceId)
    }

    private fun registerAll() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        for (device in hub.connectedDevices.value.values) {
            if (device.deviceClass == DeviceClass.UNKNOWN || device.deviceClass == DeviceClass.SENSOR) continue
            if (!device.hasGyro) continue
            // P1-3 (spec 2026-08-14-gamepad-upgrades-pendencias): registro dirigido
            // pelo USO (padrão SDL — só registra com consumidor ativo). Perfil
            // efetivo consultado fora do hot path (cache M1); gyroMode OFF ⇒ o dado
            // não tem destino ⇒ não registra (bateria).
            val mode = hub.profileFor(device.deviceId, hub.activeAppId).gyroMode ?: GyroMode.OFF
            if (mode == GyroMode.OFF) continue
            register(device.deviceId)
        }
    }

    private fun register(deviceId: Int) {
        if (registered.containsKey(deviceId)) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val inputDevice = InputDevice.getDevice(deviceId) ?: return
        val sensorManager = runCatching { inputDevice.getSensorManager() }.getOrNull() ?: return
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                // Entrega na main thread (P2-7 — registerListener sem Handler usa o
                // Looper de quem registrou). O hub processa PURO e injeta no sink;
                // nenhuma coroutine aqui (V3).
                // P2-1: o dt deve refletir QUANDO O SENSOR MEDIU (event.timestamp, ns),
                // não quando o app processou — o callback pode atrasar na main thread
                // (jogo pesado) sem inflar/deflacionar a integração. Guarda de
                // monotonicidade (padrão DS4Windows para timestamps duplicados):
                // ts <= anterior ⇒ cai para o relógio do sistema.
                val tsNs = event.timestamp
                val prevNs = lastSensorTsNs[deviceId] ?: 0L
                val nowMs = if (tsNs > prevNs) {
                    lastSensorTsNs[deviceId] = tsNs
                    tsNs / 1_000_000L
                } else {
                    SystemClock.uptimeMillis()
                }
                hub.onSensorSample(
                    deviceId = deviceId,
                    gyroX = event.values[0],
                    gyroY = event.values[1],
                    gyroZ = event.values[2],
                    nowMs = nowMs,
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (sensorManager.registerListener(listener, gyro, SensorManager.SENSOR_DELAY_GAME)) {
            registered[deviceId] = listener
            Timber.d("GamepadSensor: gyro registered device=%d", deviceId)
        }
    }

    private fun unregisterAll() {
        for (deviceId in registered.keys.toList()) {
            unregister(deviceId)
        }
    }

    private fun unregister(deviceId: Int) {
        val listener = registered.remove(deviceId) ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching {
            InputDevice.getDevice(deviceId)?.getSensorManager()?.unregisterListener(listener)
        }
        Timber.d("GamepadSensor: gyro unregistered device=%d", deviceId)
    }
}
