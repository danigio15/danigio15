package com.gdahome.os

import android.content.Context
import android.content.Intent
import android.util.Log
import com.gdahome.os.hw.PanelSensors
import com.gdahome.os.hw.Relay
import com.gdahome.os.hw.SysfsIo
import com.gdahome.os.hw.ZigbeeBridge
import com.gdahome.os.mqtt.MqttBridge
import com.gdahome.os.ui.Probe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

data class PanelState(
    val relays: Map<Int, Boolean?> = emptyMap(),
    val mqttConfigured: Boolean = false,
    val mqttConnected: Boolean = false,
    val temperature: Double? = null,
    val proximityNear: Boolean = false,
    val lux: Float? = null,
    val zigbeeEnabled: Boolean = false,
    val zigbeeRunning: Boolean = false,
    val zigbeeMessage: String = "",
)

/**
 * Il cuore di GdAHome OS: l'hardware del pannello (relè, sensori, Zigbee) e il
 * ponte MQTT verso Home Assistant. Vive nel servizio in primo piano. Le cose
 * del pannello diventano entità di Home Assistant e da lì compaiono nella
 * plancia a muro di GdAHome come qualunque altra luce o sensore.
 */
object PanelHub {
    private const val TAG = "GdAOS/Hub"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(PanelState())
    val state: StateFlow<PanelState> = _state.asStateFlow()

    private lateinit var app: Context
    private var running = false
    private var relays: Map<Int, Relay> = emptyMap()
    private var mqtt: MqttBridge? = null
    private var sensors: PanelSensors? = null
    private var zigbee: ZigbeeBridge? = null
    private var loop: Job? = null

    lateinit var config: Config
        private set

    @Synchronized
    fun start(context: Context) {
        if (running) return
        app = context.applicationContext
        config = Config(app)
        running = true
        val cfg = config
        SysfsIo.useSu = cfg.useSu

        relays = (1..2).map { Relay(cfg.relay(it)) }.filter { it.usable }.associateBy { it.cfg.index }
        relays.values.forEach { r -> scope.launch { r.init() } }

        sensors = PanelSensors(app,
            onProximity = { near ->
                _state.update { it.copy(proximityNear = near) }
                mqtt?.let { it.publish("${it.base}/proximity/state", if (near) "ON" else "OFF") }
            },
            onLight = { lux ->
                _state.update { it.copy(lux = lux) }
                mqtt?.let { it.publish("${it.base}/illuminance/state", String.format(Locale.US, "%.0f", lux)) }
            },
            onTemperature = { t -> updateTemperature(t + cfg.tempOffset) },
            invertProximity = cfg.invertProximity,
        ).also { it.start() }

        _state.value = PanelState(
            relays = relays.mapValues { null },
            mqttConfigured = cfg.mqttHost.isNotEmpty(),
            zigbeeEnabled = cfg.zigbeeEnabled && cfg.zigbeeDevice.isNotEmpty(),
        )

        if (cfg.zigbeeEnabled && cfg.zigbeeDevice.isNotEmpty()) {
            zigbee = ZigbeeBridge(app, cfg.zigbeeDevice, cfg.zigbeeBaud, cfg.zigbeePort, cfg.zigbeeRtsCts) { ok, msg ->
                _state.update { it.copy(zigbeeRunning = ok, zigbeeMessage = msg) }
            }.also { it.start() }
        }

        if (cfg.mqttHost.isNotEmpty()) {
            val s = sensors!!
            mqtt = MqttBridge(
                host = cfg.mqttHost, port = cfg.mqttPort, user = cfg.mqttUser, pass = cfg.mqttPass,
                deviceId = cfg.deviceId, panelName = cfg.panelName, discovery = cfg.mqttDiscovery,
                relays = relays.values.map { it.cfg.index to it.cfg.name },
                hasProximity = s.hasProximity,
                hasTemperature = s.hasTemperature || cfg.tempPath.isNotEmpty(),
                hasLight = s.hasLight,
                onCommand = ::onMqttCommand,
                onConnection = { ok -> _state.update { it.copy(mqttConnected = ok) } },
                onConnected = ::publishAll,
            ).also { it.start() }
        }

        loop = scope.launch {
            var tick = 0
            while (isActive) {
                relays.values.forEach { r ->
                    val before = _state.value.relays[r.cfg.index]
                    val now = r.read()
                    if (now != before) {
                        _state.update { it.copy(relays = it.relays + (r.cfg.index to now)) }
                        publishRelay(r.cfg.index, now)
                    }
                }
                if (tick % 15 == 0 && cfg.tempPath.isNotEmpty()) {
                    SysfsIo.read(cfg.tempPath)?.toDoubleOrNull()?.let { raw ->
                        updateTemperature(raw / cfg.tempDivisor + cfg.tempOffset)
                    }
                }
                tick++
                delay(2000)
            }
        }
        Log.i(TAG, "avviato: relè=${relays.keys} mqtt=${mqtt != null} zigbee=${zigbee != null}")
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        loop?.cancel()
        sensors?.stop(); sensors = null
        zigbee?.stop(); zigbee = null
        mqtt?.stop(); mqtt = null
        relays = emptyMap()
    }

    fun restart(context: Context) { stop(); start(context) }

    private fun updateTemperature(t: Double) {
        val rounded = Math.round(t * 10) / 10.0
        if (_state.value.temperature == rounded) return
        _state.update { it.copy(temperature = rounded) }
        mqtt?.let { it.publish("${it.base}/temperature/state", String.format(Locale.US, "%.1f", rounded)) }
    }

    private fun publishRelay(i: Int, on: Boolean?) {
        val m = mqtt ?: return
        if (on != null) m.publish("${m.base}/relay$i/state", if (on) "ON" else "OFF")
    }

    private fun publishAll() {
        val m = mqtt ?: return
        val s = _state.value
        s.relays.forEach { (i, on) -> publishRelay(i, on) }
        m.publish("${m.base}/proximity/state", if (s.proximityNear) "ON" else "OFF")
        s.temperature?.let { m.publish("${m.base}/temperature/state", String.format(Locale.US, "%.1f", it)) }
    }

    private fun onMqttCommand(sub: String, payload: String) {
        Log.d(TAG, "cmd $sub = $payload")
        val on = payload.equals("ON", true) || payload == "1" || payload.equals("true", true)
        when {
            sub.startsWith("relay") && sub.endsWith("/set") ->
                sub.removePrefix("relay").removeSuffix("/set").toIntOrNull()?.let { setRelay(it, on) }
            sub == "command" -> when (payload.lowercase()) {
                "riapri_app", "reload" -> riapriGdAHome()
                "riavvia", "reboot" -> scope.launch { SysfsIo.exec("svc power reboot || reboot") }
                "probe" -> scope.launch { publishProbe(Probe.run(app)) }
            }
        }
    }

    fun setRelay(index: Int, on: Boolean) {
        val r = relays[index] ?: return
        scope.launch {
            if (r.set(on)) {
                _state.update { it.copy(relays = it.relays + (index to on)) }
                publishRelay(index, on)
            } else Log.w(TAG, "relè $index: scrittura fallita (${r.cfg.path})")
        }
    }

    /** Chiude e riapre l'app GdAHome (serve root per chiuderla). */
    fun riapriGdAHome() {
        scope.launch {
            SysfsIo.exec("am force-stop ${config.gdaPackage}")
            delay(500)
            app.packageManager.getLaunchIntentForPackage(config.gdaPackage)?.let {
                app.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            }
        }
    }

    fun publishProbe(report: String) {
        mqtt?.let { it.publish("${it.base}/probe", report, retained = true) }
    }
}
