package com.gdahome.os

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/** Le impostazioni di GdAHome OS. L'interfaccia e le sue impostazioni stanno nell'app GdAHome. */
class Config(context: Context) {
    val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    private fun str(key: String, def: String = "") = prefs.getString(key, def)?.trim() ?: def
    private fun int(key: String, def: Int) = str(key, def.toString()).toIntOrNull() ?: def
    private fun bool(key: String, def: Boolean) = prefs.getBoolean(key, def)

    // Generale
    val panelName get() = str("panel_name", "Pannello GdAHome").ifEmpty { "Pannello GdAHome" }
    val pin get() = str("pin", "1234")
    /** L'app che fa da interfaccia: quella ufficiale di GdAHome. */
    val gdaPackage get() = str("gda_package", GDA_APP).ifEmpty { GDA_APP }
    /** Riapre GdAHome se si chiude (secondi di attesa, 0 = mai). */
    val riapriDopo get() = int("riapri_dopo", 3)

    // MQTT
    val mqttHost get() = str("mqtt_host")
    val mqttPort get() = int("mqtt_port", 1883)
    val mqttUser get() = str("mqtt_user")
    val mqttPass get() = str("mqtt_pass")
    /** Identificativo univoco del pannello (usato nei topic): solo [a-z0-9_] */
    val deviceId get() = str("device_id", "gdahome_muro").lowercase().replace(Regex("[^a-z0-9_]"), "_")
    val mqttDiscovery get() = bool("mqtt_discovery", true)

    // Relè (fino a 2). Il path può essere un file sysfs oppure solo il numero GPIO.
    fun relay(n: Int) = RelayConfig(
        index = n,
        enabled = bool("relay${n}_enabled", false),
        name = str("relay${n}_name", "Relè $n").ifEmpty { "Relè $n" },
        path = str("relay${n}_path"),
        onValue = str("relay${n}_on", "1").ifEmpty { "1" },
        offValue = str("relay${n}_off", "0").ifEmpty { "0" },
    )

    val useSu get() = bool("use_su", true)
    val invertProximity get() = bool("invert_proximity", false)

    // Zigbee integrato → rete (per Zigbee2MQTT / ZHA)
    val zigbeeEnabled get() = bool("zigbee_enabled", false)
    val zigbeeDevice get() = str("zigbee_device")
    val zigbeeBaud get() = int("zigbee_baud", 115200)
    val zigbeePort get() = int("zigbee_port", 6638)
    val zigbeeRtsCts get() = bool("zigbee_rtscts", false)

    // Temperatura (sysfs opzionale se il sensore Android non è esposto)
    val tempPath get() = str("temp_path")
    val tempDivisor get() = str("temp_divisor", "1000").toDoubleOrNull()?.takeIf { it != 0.0 } ?: 1000.0
    val tempOffset get() = str("temp_offset", "0").toDoubleOrNull() ?: 0.0

    companion object {
        const val GDA_APP = "com.gdahome.gdahome"
    }
}

data class RelayConfig(
    val index: Int,
    val enabled: Boolean,
    val name: String,
    val path: String,
    val onValue: String,
    val offValue: String,
)
