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
 * DO device. Eventos chegam por callback em THREAD PRÓPRIA — nunca no dispatch (V3).
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
                // Thread própria do sensor — o hub processa PURO e injeta no sink
                // (XServer aceita de qualquer thread); nenhuma coroutine aqui (V3).
                hub.onSensorSample(
                    deviceId = deviceId,
                    gyroX = event.values[0],
                    gyroY = event.values[1],
                    gyroZ = event.values[2],
                    nowMs = SystemClock.uptimeMillis(),
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
