package it.gdahome.panel

import it.gdahome.panel.ha.HaEntity
import org.json.JSONObject

/** Casa di esempio per la modalità demo (mostra il pannello senza Home Assistant). */
object DemoData {
    val entityList: List<Pair<String, String?>> = listOf(
        "light.soggiorno" to "Soggiorno",
        "light.cucina" to "Cucina",
        "cover.tapparella_sala" to "Tapparella sala",
        "climate.termostato" to "Termostato",
        "switch.presa_tv" to "Presa TV",
        "scene.buonanotte" to "Buonanotte",
        "lock.porta_ingresso" to "Ingresso",
        "sensor.umidita_bagno" to "Umidità bagno",
        "fan.ventilatore" to "Ventilatore",
    )

    fun entities(): Map<String, HaEntity> = listOf(
        e("light.soggiorno", "on", "brightness" to 204),
        e("light.cucina", "off"),
        e("cover.tapparella_sala", "open", "current_position" to 65),
        e("climate.termostato", "heat", "current_temperature" to 20.5, "temperature" to 21.5),
        e("switch.presa_tv", "on"),
        e("scene.buonanotte", "scening"),
        e("lock.porta_ingresso", "locked"),
        e("sensor.umidita_bagno", "58", "unit_of_measurement" to "%", "device_class" to "humidity"),
        e("fan.ventilatore", "off"),
    ).associateBy { it.entityId }

    private fun e(id: String, state: String, vararg attrs: Pair<String, Any>) =
        HaEntity(id, state, JSONObject().apply { attrs.forEach { (k, v) -> put(k, v) } })

    /** Simula un comando in modalità demo. */
    fun toggle(e: HaEntity): HaEntity {
        val next = when (e.domain) {
            "lock" -> if (e.state == "locked") "unlocked" else "locked"
            "cover" -> if (e.state == "open") "closed" else "open"
            "climate", "sensor", "scene", "script" -> e.state
            else -> if (e.state == "on") "off" else "on"
        }
        return e.copy(state = next)
    }
}
