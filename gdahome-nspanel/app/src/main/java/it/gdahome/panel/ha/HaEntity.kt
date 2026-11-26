package it.gdahome.panel.ha

import org.json.JSONObject

data class HaEntity(
    val entityId: String,
    val state: String,
    val attributes: JSONObject,
) {
    val domain get() = entityId.substringBefore('.')
    val friendlyName: String get() = attributes.optString("friendly_name", entityId)
    val unit: String get() = attributes.optString("unit_of_measurement", "")

    val isOn: Boolean
        get() = when (state) {
            "on", "open", "opening", "unlocked", "playing", "home", "heat", "cool", "auto", "heat_cool" -> true
            else -> false
        }

    val toggleable get() = domain in setOf("light", "switch", "fan", "input_boolean", "cover", "lock", "media_player", "automation", "siren", "humidifier")
    val actionOnly get() = domain in setOf("scene", "script", "button", "input_button")
}
