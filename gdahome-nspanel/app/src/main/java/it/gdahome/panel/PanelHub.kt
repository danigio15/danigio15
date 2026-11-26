package it.gdahome.panel

import android.content.Context
import android.util.Log
import it.gdahome.panel.ha.HaClient
import it.gdahome.panel.ha.HaEntity
import it.gdahome.panel.hw.PanelSensors
import it.gdahome.panel.hw.Relay
import it.gdahome.panel.hw.SysfsIo
import it.gdahome.panel.hw.ZigbeeBridge
import it.gdahome.panel.mqtt.MqttBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

data class PanelState(
    val relays: Map<Int, Boolean?> = emptyMap(),
    val haEntities: Map<String, HaEntity> = emptyMap(),
    val haConfigured: Boolean = false,
    val haConnected: Boolean = false,
    val haMessage: String = "",
    val mqttConfigured: Boolean = false,
    val mqttConnected: Boolean = false,
    val temperature: Double? = null,
    val proximityNear: Boolean = false,
    val lux: Float? = null,
    val zigbeeEnabled: Boolean = false,
    val zigbeeRunning: Boolean = false,
    val zigbeeMessage: String = "",
)

enum class ScreenCmd { WAKE, SLEEP }

/** Cuore dell'app: possiede hardware, connessione HA e MQTT. Vive nel servizio in primo piano. */
object PanelHub {
    private const val TAG = "GDA/Hub"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(PanelState())
    val state: StateFlow<PanelState> = _state.asStateFlow()
    private val _screen = MutableSharedFlow<ScreenCmd>(extraBufferCapacity = 8)
    val screenCommands = _screen.asSharedFlow()
    private val _commands = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Comandi generici per la UI (es. "probe", "reload"). */
    val uiCommands = _commands.asSharedFlow()

    private lateinit var app: Context
    private var running = false
    private var relays: Map<Int, Relay> = emptyMap()
    private var ha: HaClient? = null
    private var mqtt: MqttBridge? = null
    private var sensors: PanelSensors? = null
    private var zigbee: ZigbeeBridge? = null
    private var loop: Job? = null
    @Volatile var screenOn: Boolean = true
        private set

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
                mqtt?.publish("${mqtt?.base}/proximity/state", if (near) "ON" else "OFF")
                if (near && cfg.wakeOnProximity) _screen.tryEmit(ScreenCmd.WAKE)
            },
            onLight = { lux ->
                _state.update { it.copy(lux = lux) }
                mqtt?.publish("${mqtt?.base}/illuminance/state", String.format(Locale.US, "%.0f", lux))
            },
            onTemperature = { t -> updateTemperature(t + cfg.tempOffset) },
            invertProximity = cfg.invertProximity,
        ).also { it.start() }

        _state.value = PanelState(
            relays = relays.mapValues { null },
            haConfigured = cfg.haUrl.isNotEmpty() && cfg.haToken.isNotEmpty(),
            mqttConfigured = cfg.mqttHost.isNotEmpty(),
            zigbeeEnabled = cfg.zigbeeEnabled && cfg.zigbeeDevice.isNotEmpty(),
        )

        if (cfg.zigbeeEnabled && cfg.zigbeeDevice.isNotEmpty()) {
            zigbee = ZigbeeBridge(app, cfg.zigbeeDevice, cfg.zigbeeBaud, cfg.zigbeePort, cfg.zigbeeRtsCts) { ok, msg ->
                _state.update { it.copy(zigbeeRunning = ok, zigbeeMessage = msg) }
            }.also { it.start() }
        }

        if (cfg.haUrl.isNotEmpty() && cfg.haToken.isNotEmpty()) {
            ha = HaClient(cfg.haUrl, cfg.haToken,
                onStates = { all -> _state.update { it.copy(haEntities = all) } },
                onConnection = { ok, msg -> _state.update { it.copy(haConnected = ok, haMessage = msg) } },
            ).also { it.start() }
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
        Log.i(TAG, "avviato: relè=${relays.keys} ha=${ha != null} mqtt=${mqtt != null}")
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        loop?.cancel()
        sensors?.stop(); sensors = null
        zigbee?.stop(); zigbee = null
        ha?.stop(); ha = null
        mqtt?.stop(); mqtt = null
        relays = emptyMap()
    }

    fun restart(context: Context) { stop(); start(context) }

    private fun updateTemperature(t: Double) {
        val rounded = Math.round(t * 10) / 10.0
        if (_state.value.temperature == rounded) return
        _state.update { it.copy(temperature = rounded) }
        mqtt?.publish("${mqtt?.base}/temperature/state", String.format(Locale.US, "%.1f", rounded))
    }

    private fun publishRelay(i: Int, on: Boolean?) {
        val m = mqtt ?: return
        if (on != null) m.publish("${m.base}/relay$i/state", if (on) "ON" else "OFF")
    }

    private fun publishAll() {
        val m = mqtt ?: return
        val s = _state.value
        s.relays.forEach { (i, on) -> publishRelay(i, on) }
        m.publish("${m.base}/screen/state", if (screenOn) "ON" else "OFF")
        m.publish("${m.base}/proximity/state", if (s.proximityNear) "ON" else "OFF")
        s.temperature?.let { m.publish("${m.base}/temperature/state", String.format(Locale.US, "%.1f", it)) }
    }

    private fun onMqttCommand(sub: String, payload: String) {
        Log.d(TAG, "cmd $sub = $payload")
        val on = payload.equals("ON", true) || payload == "1" || payload.equals("true", true)
        when {
            sub.startsWith("relay") && sub.endsWith("/set") -> {
                sub.removePrefix("relay").removeSuffix("/set").toIntOrNull()?.let { setRelay(it, on) }
            }
            sub == "screen/set" -> _screen.tryEmit(if (on) ScreenCmd.WAKE else ScreenCmd.SLEEP)
            sub == "command" -> _commands.tryEmit(payload.lowercase())
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

    fun toggleRelay(index: Int) {
        val cur = _state.value.relays[index] ?: false
        setRelay(index, !cur)
    }

    fun relayName(index: Int) = relays[index]?.cfg?.name ?: "Relè $index"
    fun relayTile(index: Int) = relays[index]?.cfg?.showTile ?: false

    fun haTap(e: HaEntity) { scope.launch { ha?.tap(e) } }

    fun haCall(domain: String, service: String, entityId: String, data: org.json.JSONObject) {
        scope.launch { ha?.callService(domain, service, entityId, data) }
    }

    fun reportScreen(on: Boolean) {
        screenOn = on
        mqtt?.let { it.publish("${it.base}/screen/state", if (on) "ON" else "OFF") }
    }

    fun publishProbe(report: String) {
        mqtt?.let { it.publish("${it.base}/probe", report, retained = true) }
    }

    fun requestProbe() { _commands.tryEmit("probe") }
}
