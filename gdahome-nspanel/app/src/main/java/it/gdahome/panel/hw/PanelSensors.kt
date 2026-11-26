package it.gdahome.panel.hw

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/** Sensori Android del pannello: prossimità, luce ambiente, temperatura (se esposta). */
class PanelSensors(
    context: Context,
    private val onProximity: (near: Boolean) -> Unit,
    private val onLight: (lux: Float) -> Unit,
    private val onTemperature: (celsius: Double) -> Unit,
    private val invertProximity: Boolean = false,
) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val proximity: Sensor? = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val light: Sensor? = sm.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val temp: Sensor? = sm.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)
    private var lastLightPublish = 0L

    val hasProximity get() = proximity != null
    val hasLight get() = light != null
    val hasTemperature get() = temp != null

    fun start() {
        proximity?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        light?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        temp?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_PROXIMITY -> {
                val v = event.values[0]
                // Standard Android: valore < maximumRange = oggetto vicino.
                // Alcuni firmware lo riportano al contrario: c'è l'opzione "inverti" nelle impostazioni.
                val near = v < event.sensor.maximumRange
                onProximity(if (invertProximity) !near else near)
            }
            Sensor.TYPE_LIGHT -> {
                val now = System.currentTimeMillis()
                if (now - lastLightPublish > 10_000) { lastLightPublish = now; onLight(event.values[0]) }
            }
            Sensor.TYPE_AMBIENT_TEMPERATURE -> onTemperature(event.values[0].toDouble())
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
