package it.gdahome.panel.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import it.gdahome.panel.R
import it.gdahome.panel.ha.HaEntity

sealed class Tile {
    abstract val key: String
    abstract val title: String
    abstract val subtitle: String
    @get:DrawableRes abstract val icon: Int
    abstract val active: Boolean

    data class LocalRelay(val index: Int, override val title: String, val on: Boolean?) : Tile() {
        override val key get() = "relay$index"
        override val subtitle get() = when (on) { true -> "Acceso"; false -> "Spento"; null -> "Non disponibile" }
        override val icon get() = if (on == true) R.drawable.mdi_flash else R.drawable.mdi_flash_outline
        override val active get() = on == true
    }

    data class Entity(val entityId: String, val label: String?, val entity: HaEntity?) : Tile() {
        override val key get() = entityId
        override val title get() = label ?: entity?.friendlyName ?: entityId
        override val active get() = entity?.isOn == true
        override val subtitle: String get() {
            val e = entity ?: return "Non disponibile"
            return when (e.domain) {
                "sensor" -> "${e.state} ${e.unit}".trim()
                "climate" -> {
                    val cur = e.attributes.optString("current_temperature", "")
                    val tgt = e.attributes.optString("temperature", "")
                    listOfNotNull(
                        cur.takeIf { it.isNotEmpty() }?.let { "$it°" },
                        tgt.takeIf { it.isNotEmpty() }?.let { "→ $it°" },
                    ).joinToString(" ").ifEmpty { stateIt(e.state) }
                }
                "light" -> if (e.isOn) {
                    val b = e.attributes.optInt("brightness", -1)
                    if (b >= 0) "Accesa · ${b * 100 / 255}%" else "Accesa"
                } else "Spenta"
                "cover" -> {
                    val pos = e.attributes.optInt("current_position", -1)
                    if (pos in 1..99) "Aperta $pos%" else stateIt(e.state)
                }
                "scene", "script", "button", "input_button" -> "Tocca per attivare"
                "binary_sensor" -> if (e.isOn) "Rilevato" else "Libero"
                else -> stateIt(e.state)
            }
        }
        override val icon: Int get() = when (entityId.substringBefore('.')) {
            "light" -> if (active) R.drawable.mdi_lightbulb else R.drawable.mdi_lightbulb_outline
            "switch", "input_boolean" -> if (active) R.drawable.mdi_power_plug else R.drawable.mdi_power_plug_off
            "fan" -> if (active) R.drawable.mdi_fan else R.drawable.mdi_fan_off
            "cover" -> if (active) R.drawable.mdi_window_shutter_open else R.drawable.mdi_window_shutter
            "lock" -> if (entity?.state == "locked") R.drawable.mdi_lock else R.drawable.mdi_lock_open_variant
            "climate" -> R.drawable.mdi_thermostat
            "scene" -> R.drawable.mdi_palette
            "script", "automation" -> R.drawable.mdi_script_text_play
            "button", "input_button" -> R.drawable.mdi_gesture_tap_button
            "media_player" -> R.drawable.mdi_speaker
            "binary_sensor" -> if (active) R.drawable.mdi_door_open else R.drawable.mdi_door_closed
            "sensor" -> when (entity?.attributes?.optString("device_class")) {
                "temperature" -> R.drawable.mdi_thermometer
                "humidity" -> R.drawable.mdi_water_percent
                else -> R.drawable.mdi_gauge
            }
            else -> R.drawable.mdi_gauge
        }
    }

    companion object {
        fun stateIt(s: String) = when (s) {
            "on" -> "Acceso"; "off" -> "Spento"
            "open" -> "Aperta"; "closed" -> "Chiusa"; "opening" -> "In apertura"; "closing" -> "In chiusura"
            "locked" -> "Chiusa a chiave"; "unlocked" -> "Aperta"
            "unavailable" -> "Non disponibile"; "unknown" -> "Sconosciuto"
            "heat" -> "Riscaldamento"; "cool" -> "Raffrescamento"; "auto" -> "Auto"; "heat_cool" -> "Auto"
            "playing" -> "In riproduzione"; "paused" -> "In pausa"; "idle" -> "Inattivo"
            else -> s
        }
    }
}

class TileAdapter(
    private val onTap: (Tile) -> Unit,
    private val onLongPress: (Tile) -> Unit,
) : ListAdapter<Tile, TileAdapter.VH>(object : DiffUtil.ItemCallback<Tile>() {
    override fun areItemsTheSame(a: Tile, b: Tile) = a.key == b.key
    override fun areContentsTheSame(a: Tile, b: Tile) =
        a.title == b.title && a.subtitle == b.subtitle && a.active == b.active && a.icon == b.icon
}) {
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.icon)
        val title: TextView = v.findViewById(R.id.title)
        val subtitle: TextView = v.findViewById(R.id.subtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_tile, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val t = getItem(position)
        val ctx = h.itemView.context
        h.icon.setImageResource(t.icon)
        h.icon.setColorFilter(ContextCompat.getColor(ctx, if (t.active) R.color.gda_yellow else R.color.gda_text_dim))
        h.title.text = t.title
        h.subtitle.text = t.subtitle
        h.subtitle.setTextColor(ContextCompat.getColor(ctx, if (t.active) R.color.gda_text_on else R.color.gda_text_dim))
        h.itemView.isActivated = t.active
        h.itemView.setOnClickListener { onTap(t) }
        h.itemView.setOnLongClickListener { onLongPress(t); true }
    }
}
