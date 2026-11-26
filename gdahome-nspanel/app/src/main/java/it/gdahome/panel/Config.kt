package it.gdahome.panel

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/** Tutte le impostazioni del pannello, salvate nelle SharedPreferences di default. */
class Config(context: Context) {
    val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    private fun str(key: String, def: String = "") = prefs.getString(key, def)?.trim() ?: def
    private fun int(key: String, def: Int) = str(key, def.toString()).toIntOrNull() ?: def
    private fun bool(key: String, def: Boolean) = prefs.getBoolean(key, def)

    // Generali
    val panelName get() = str("panel_name", "GDAHome").ifEmpty { "GDAHome" }
    val pin get() = str("pin", "1234")

    // Home Assistant
    val haUrl get() = str("ha_url").trimEnd('/')
    val haToken get() = str("ha_token")
    /** Una entità per riga, formato: `light.cucina` oppure `light.cucina|Luce cucina` */
    val haEntities: List<Pair<String, String?>>
        get() = str("ha_entities").lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val parts = line.split("|", limit = 2)
                parts[0].trim() to parts.getOrNull(1)?.trim()?.ifEmpty { null }
            }

    // MQTT
    val mqttHost get() = str("mqtt_host")
    val mqttPort get() = int("mqtt_port", 1883)
    val mqttUser get() = str("mqtt_user")
    val mqttPass get() = str("mqtt_pass")
    /** Identificativo univoco del pannello (usato nei topic): solo [a-z0-9_] */
    val deviceId get() = str("device_id", "gdahome_panel").lowercase().replace(Regex("[^a-z0-9_]"), "_")
    val mqttDiscovery get() = bool("mqtt_discovery", true)

    // Relè (fino a 2). Il path può essere un file sysfs oppure solo il numero GPIO.
    fun relay(n: Int) = RelayConfig(
        index = n,
        enabled = bool("relay${n}_enabled", false),
        name = str("relay${n}_name", "Relè $n").ifEmpty { "Relè $n" },
        path = str("relay${n}_path"),
        onValue = str("relay${n}_on", "1").ifEmpty { "1" },
        offValue = str("relay${n}_off", "0").ifEmpty { "0" },
        showTile = bool("relay${n}_tile", true),
    )

    val useSu get() = bool("use_su", true)

    // Zigbee integrato → rete (per Zigbee2MQTT / ZHA)
    val zigbeeEnabled get() = bool("zigbee_enabled", false)
    val zigbeeDevice get() = str("zigbee_device")
    val zigbeeBaud get() = int("zigbee_baud", 115200)
    val zigbeePort get() = int("zigbee_port", 6638)
    val zigbeeRtsCts get() = bool("zigbee_rtscts", false)

    // Schermo
    val dimAfterSec get() = int("dim_after", 60)
    val offAfterSec get() = int("off_after", 300)
    val brightnessActive get() = int("brightness_active", 80).coerceIn(1, 100)
    val brightnessDim get() = int("brightness_dim", 8).coerceIn(0, 100)
    val wakeOnProximity get() = bool("wake_proximity", true)
    val invertProximity get() = bool("invert_proximity", false)
    val backlightPath get() = str("backlight_path")
    val backlightMax get() = int("backlight_max", 255)

    // Temperatura (sysfs opzionale se il sensore Android non è esposto)
    val tempPath get() = str("temp_path")
    val tempDivisor get() = str("temp_divisor", "1000").toDoubleOrNull()?.takeIf { it != 0.0 } ?: 1000.0
    val tempOffset get() = str("temp_offset", "0").toDoubleOrNull() ?: 0.0
}

data class RelayConfig(
    val index: Int,
    val enabled: Boolean,
    val name: String,
    val path: String,
    val onValue: String,
    val offValue: String,
    val showTile: Boolean,
)
