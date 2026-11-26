package it.gdahome.panel.mqtt

import android.os.Build
import android.util.Log
import it.gdahome.panel.BuildConfig
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ponte MQTT: espone relè, sensori e schermo del pannello a Home Assistant
 * con MQTT Discovery (le entità compaiono da sole in HA).
 *
 * Topic base: gdahome/<device_id>/...
 */
class MqttBridge(
    private val host: String,
    private val port: Int,
    private val user: String,
    private val pass: String,
    private val deviceId: String,
    private val panelName: String,
    private val discovery: Boolean,
    private val relays: List<Pair<Int, String>>, // indice, nome
    private val hasProximity: Boolean,
    private val hasTemperature: Boolean,
    private val hasLight: Boolean,
    private val onCommand: (topic: String, payload: String) -> Unit,
    private val onConnection: (Boolean) -> Unit,
    private val onConnected: () -> Unit,
) {
    private val tag = "GDA/MQTT"
    val base = "gdahome/$deviceId"
    private val availability = "$base/status"
    @Volatile private var client: MqttClient? = null
    /** Le pubblicazioni avvengono su un thread dedicato per non bloccare la UI. */
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()

    fun start() {
        Thread {
            try {
                val c = MqttClient("tcp://$host:$port", "gdahome-$deviceId", MemoryPersistence())
                client = c
                c.setCallback(object : MqttCallbackExtended {
                    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                        Log.i(tag, "connesso a $serverURI (reconnect=$reconnect)")
                        onConnection(true)
                        try {
                            c.subscribe("$base/+/set", 1)
                            c.subscribe("$base/command", 1)
                            c.subscribe("homeassistant/status", 1)
                            publish(availability, "online")
                            if (discovery) publishDiscovery()
                        } catch (e: Exception) { Log.w(tag, "subscribe: ${e.message}") }
                        onConnected()
                    }

                    override fun connectionLost(cause: Throwable?) {
                        Log.w(tag, "connessione persa: ${cause?.message}")
                        onConnection(false)
                    }

                    override fun messageArrived(topic: String, message: MqttMessage) {
                        val payload = String(message.payload).trim()
                        if (topic == "homeassistant/status") {
                            if (payload == "online") { if (discovery) publishDiscovery(); onConnected() }
                            return
                        }
                        onCommand(topic.removePrefix("$base/"), payload)
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                })
                val opts = MqttConnectOptions().apply {
                    isAutomaticReconnect = true
                    isCleanSession = true
                    keepAliveInterval = 30
                    connectionTimeout = 10
                    if (user.isNotEmpty()) { userName = user; password = pass.toCharArray() }
                    setWill(availability, "offline".toByteArray(), 1, true)
                }
                connectWithRetry(c, opts)
            } catch (e: Exception) {
                Log.e(tag, "init: ${e.message}")
                onConnection(false)
            }
        }.apply { name = "gda-mqtt"; isDaemon = true }.start()
    }

    private fun connectWithRetry(c: MqttClient, opts: MqttConnectOptions) {
        var delay = 2000L
        while (client === c) {
            try { c.connect(opts); return } catch (e: Exception) {
                Log.w(tag, "connect: ${e.message}")
                onConnection(false)
                Thread.sleep(delay)
                delay = (delay * 2).coerceAtMost(60_000)
            }
        }
    }

    fun stop() {
        val c = client ?: return
        client = null
        Thread {
            try {
                if (c.isConnected) { publish(availability, "offline", c); c.disconnect(2000) }
                c.close()
            } catch (_: Exception) {}
            io.shutdown()
        }.start()
    }

    val connected get() = client?.isConnected == true

    fun publish(topic: String, payload: String, retained: Boolean = true) {
        val c = client ?: return
        try { io.execute { publish(topic, payload, c, retained) } } catch (_: Exception) {}
    }

    private fun publish(topic: String, payload: String, c: MqttClient?, retained: Boolean = true) {
        if (c == null || !c.isConnected) return
        try {
            c.publish(topic, MqttMessage(payload.toByteArray()).apply { qos = 1; isRetained = retained })
        } catch (e: Exception) { Log.w(tag, "publish $topic: ${e.message}") }
    }

    private fun deviceBlock() = JSONObject()
        .put("identifiers", JSONArray().put("gdahome_$deviceId"))
        .put("name", panelName)
        .put("manufacturer", "GDAHome")
        .put("model", "GDAHome Panel (${Build.MODEL})")
        .put("sw_version", BuildConfig.VERSION_NAME)

    private fun disc(component: String, objectId: String, cfg: JSONObject) {
        cfg.put("unique_id", "gdahome_${deviceId}_$objectId")
            .put("object_id", "${deviceId}_$objectId")
            .put("availability_topic", availability)
            .put("device", deviceBlock())
        publish("homeassistant/$component/gdahome_$deviceId/$objectId/config", cfg.toString())
    }

    private fun publishDiscovery() {
        relays.forEach { (i, name) ->
            disc("switch", "relay$i", JSONObject()
                .put("name", name)
                .put("state_topic", "$base/relay$i/state")
                .put("command_topic", "$base/relay$i/set")
                .put("icon", "mdi:electric-switch"))
        }
        disc("switch", "screen", JSONObject()
            .put("name", "Schermo")
            .put("state_topic", "$base/screen/state")
            .put("command_topic", "$base/screen/set")
            .put("icon", "mdi:tablet-dashboard"))
        disc("button", "reload", JSONObject()
            .put("name", "Ricarica pannello")
            .put("command_topic", "$base/command")
            .put("payload_press", "reload")
            .put("icon", "mdi:refresh"))
        disc("button", "probe", JSONObject()
            .put("name", "Analisi hardware")
            .put("command_topic", "$base/command")
            .put("payload_press", "probe")
            .put("entity_category", "diagnostic")
            .put("icon", "mdi:magnify-scan"))
        if (hasProximity) disc("binary_sensor", "proximity", JSONObject()
            .put("name", "Presenza")
            .put("state_topic", "$base/proximity/state")
            .put("device_class", "occupancy"))
        if (hasTemperature) disc("sensor", "temperature", JSONObject()
            .put("name", "Temperatura")
            .put("state_topic", "$base/temperature/state")
            .put("device_class", "temperature")
            .put("unit_of_measurement", "°C")
            .put("state_class", "measurement"))
        if (hasLight) disc("sensor", "illuminance", JSONObject()
            .put("name", "Luminosità ambiente")
            .put("state_topic", "$base/illuminance/state")
            .put("device_class", "illuminance")
            .put("unit_of_measurement", "lx")
            .put("state_class", "measurement"))
    }
}
