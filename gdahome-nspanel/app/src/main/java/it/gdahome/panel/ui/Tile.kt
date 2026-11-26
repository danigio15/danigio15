package it.gdahome.panel.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import it.gdahome.panel.R
import it.gdahome.panel.ha.HaEntity

sealed class Tile {
    abstract val key: String
    abstract val title: String
    abstract val subtitle: String
    abstract val icon: String
    abstract val active: Boolean

    data class LocalRelay(val index: Int, override val title: String, val on: Boolean?) : Tile() {
        override val key get() = "relay$index"
        override val subtitle get() = when (on) { true -> "Acceso"; false -> "Spento"; null -> "Non disponibile" }
        override val icon get() = "⚡"
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
                    listOf(cur.takeIf { it.isNotEmpty() }?.let { "$it°" }, tgt.takeIf { it.isNotEmpty() }?.let { "→ $it°" }, stateIt(e.state))
                        .filterNotNull().joinToString(" ")
                }
                "light" -> if (e.isOn) {
                    val b = e.attributes.optInt("brightness", -1)
                    if (b >= 0) "Acceso · ${b * 100 / 255}%" else "Acceso"
                } else "Spento"
                "scene", "script", "button", "input_button" -> "Tocca per attivare"
                else -> stateIt(e.state)
            }
        }
        override val icon: String get() = when (entityId.substringBefore('.')) {
            "light" -> "💡"
            "switch", "input_boolean" -> "🔌"
            "fan" -> "🌀"
            "cover" -> if (active) "🔼" else "🔽"
            "lock" -> if (entity?.state == "locked") "🔒" else "🔓"
            "climate" -> "🌡"
            "scene" -> "✨"
            "script", "automation" -> "▶"
            "button", "input_button" -> "🔘"
            "media_player" -> "🎵"
            "binary_sensor" -> if (active) "🔴" else "⚪"
            "camera" -> "📷"
            else -> "📈"
        }
    }

    companion object {
        fun stateIt(s: String) = when (s) {
            "on" -> "Acceso"; "off" -> "Spento"
            "open" -> "Aperto"; "closed" -> "Chiuso"; "opening" -> "In apertura"; "closing" -> "In chiusura"
            "locked" -> "Chiuso a chiave"; "unlocked" -> "Aperto"
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
        val icon: TextView = v.findViewById(R.id.icon)
        val title: TextView = v.findViewById(R.id.title)
        val subtitle: TextView = v.findViewById(R.id.subtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_tile, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val t = getItem(position)
        h.icon.text = t.icon
        h.title.text = t.title
        h.subtitle.text = t.subtitle
        h.itemView.isActivated = t.active
        h.itemView.setOnClickListener { onTap(t) }
        h.itemView.setOnLongClickListener { onLongPress(t); true }
    }
}
