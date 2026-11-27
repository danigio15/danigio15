package it.gdahome.panel.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import it.gdahome.panel.Config
import it.gdahome.panel.GdaService
import it.gdahome.panel.PanelHub
import it.gdahome.panel.PanelState
import it.gdahome.panel.R
import it.gdahome.panel.ScreenCmd
import it.gdahome.panel.hw.Backlight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        /** Ora fissa per i render di anteprima (null = ora reale). */
        @Volatile var previewTime: Long? = null
    }

    private enum class Mode { ACTIVE, DIM, OFF }

    private lateinit var cfg: Config
    private lateinit var adapter: TileAdapter
    private lateinit var grid: RecyclerView
    private lateinit var empty: TextView
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var temperature: TextView
    private lateinit var status: TextView
    private lateinit var saver: View
    private lateinit var saverContent: View
    private lateinit var saverClock: TextView
    private lateinit var saverInfo: TextView
    private lateinit var saverDate: TextView
    private lateinit var greeting: TextView
    private lateinit var tempChip: View

    private val handler = Handler(Looper.getMainLooper())
    private var mode = Mode.ACTIVE
    private var lastTouch = System.currentTimeMillis()
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.ITALY)
    private val dateFmt = SimpleDateFormat("EEEE d MMMM", Locale.ITALY)

    private val ticker = object : Runnable {
        override fun run() {
            val now = Date(previewTime ?: System.currentTimeMillis())
            val t = timeFmt.format(now)
            clock.text = t
            saverClock.text = t
            val d = dateFmt.format(now).replaceFirstChar { it.uppercase() }
            date.text = d
            saverDate.text = d
            updateGreeting(now)
            checkIdle()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // Lo schermo non deve MAI spegnersi via Android: sul firmware AOSP del NSPanel Pro
        // senza l'app Sonoff non si riaccenderebbe più. Lo "spegnimento" lo gestiamo noi.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        grid = findViewById(R.id.grid)
        empty = findViewById(R.id.empty)
        clock = findViewById(R.id.clock)
        date = findViewById(R.id.date)
        temperature = findViewById(R.id.temperature)
        status = findViewById(R.id.status)
        saver = findViewById(R.id.screensaver)
        saverContent = findViewById(R.id.saver_content)
        saverClock = findViewById(R.id.saver_clock)
        saverInfo = findViewById(R.id.saver_info)
        saverDate = findViewById(R.id.saver_date)
        greeting = findViewById(R.id.greeting)
        tempChip = findViewById(R.id.temp_chip)

        adapter = TileAdapter(::onTileTap, ::onTileLongPress)
        grid.adapter = adapter
        grid.itemAnimator = null

        findViewById<View>(R.id.logo).setOnLongClickListener { askPin(); true }

        GdaService.start(this)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { PanelHub.state.collect { render(it) } }
                launch {
                    PanelHub.screenCommands.collect {
                        when (it) {
                            ScreenCmd.WAKE -> wake()
                            ScreenCmd.DIM -> setMode(Mode.DIM)
                            ScreenCmd.SLEEP -> setMode(Mode.OFF)
                        }
                    }
                }
                launch {
                    PanelHub.uiCommands.collect { cmd ->
                        when (cmd) {
                            "probe" -> {
                                val r = withContext(Dispatchers.IO) { Probe.run(this@MainActivity) }
                                PanelHub.publishProbe(r)
                            }
                            "reload" -> { GdaService.restart(this@MainActivity); recreate() }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        cfg = Config(this)
        hideSystemUi()
        val widthDp = resources.configuration.screenWidthDp
        val span = (widthDp / 150).coerceIn(2, 6)
        grid.layoutManager = GridLayoutManager(this, span)
        lastTouch = System.currentTimeMillis()
        setMode(Mode.ACTIVE, force = true)
        render(PanelHub.state.value)
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Deprecated("Launcher: il tasto indietro non deve uscire")
    override fun onBackPressed() { /* ignorato */ }

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    // ---------- Schermo / salvaschermo ----------

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        lastTouch = System.currentTimeMillis()
        if (mode != Mode.ACTIVE) {
            // Il primo tocco risveglia soltanto, non attiva i pulsanti sotto
            if (ev.action == MotionEvent.ACTION_DOWN) wake()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun checkIdle() {
        if (!::cfg.isInitialized) return
        val idle = ((System.currentTimeMillis() - lastTouch) / 1000).toInt()
        when (mode) {
            Mode.ACTIVE -> if (cfg.dimAfterSec in 1..idle) setMode(Mode.DIM)
                else if (cfg.offAfterSec in 1..idle) setMode(Mode.OFF)
            Mode.DIM -> if (cfg.offAfterSec in 1..idle) setMode(Mode.OFF)
            Mode.OFF -> {}
        }
        // Sposta l'orologio del salvaschermo per evitare segni sul display
        if (mode == Mode.DIM && idle % 60 == 0) {
            val maxX = (saver.width - saverContent.width).coerceAtLeast(1)
            val maxY = (saver.height - saverContent.height).coerceAtLeast(1)
            saverContent.translationX = (Math.random() * maxX - maxX / 2).toFloat() / 2
            saverContent.translationY = (Math.random() * maxY - maxY / 2).toFloat() / 2
        }
    }

    private fun wake() {
        lastTouch = System.currentTimeMillis()
        if (mode != Mode.ACTIVE) setMode(Mode.ACTIVE)
    }

    private fun setMode(m: Mode, force: Boolean = false) {
        if (m == mode && !force) return
        mode = m
        val pct = when (m) {
            Mode.ACTIVE -> cfg.brightnessActive
            Mode.DIM -> cfg.brightnessDim
            Mode.OFF -> 0
        }
        val lp = window.attributes
        lp.screenBrightness = (pct / 100f).coerceIn(0f, 1f)
        window.attributes = lp
        Backlight.set(cfg.backlightPath, cfg.backlightMax, pct)
        saver.visibility = if (m == Mode.ACTIVE) View.GONE else View.VISIBLE
        saverContent.visibility = if (m == Mode.DIM) View.VISIBLE else View.INVISIBLE
        PanelHub.reportScreen(m == Mode.ACTIVE)
    }

    // ---------- Rendering ----------

    private fun render(s: PanelState) {
        if (!::cfg.isInitialized) return
        val tiles = mutableListOf<Tile>()
        s.relays.keys.sorted().forEach { i ->
            if (PanelHub.relayTile(i)) tiles += Tile.LocalRelay(i, PanelHub.relayName(i), s.relays[i])
        }
        cfg.haEntities.forEach { (id, label) -> tiles += Tile.Entity(id, label, s.haEntities[id]) }
        adapter.submitList(tiles)
        empty.visibility = if (tiles.isEmpty()) View.VISIBLE else View.GONE
        grid.visibility = if (tiles.isEmpty()) View.GONE else View.VISIBLE

        val t = s.temperature?.let { String.format(Locale.ITALY, "%.1f°", it) }
        temperature.text = t ?: ""
        tempChip.visibility = if (t == null) View.GONE else View.VISIBLE
        saverInfo.text = t ?: ""
        saverInfo.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (t == null) 0 else R.drawable.mdi_thermometer, 0, 0, 0)
        saverInfo.compoundDrawablesRelative[0]?.setTint(getColor(R.color.gda_yellow))

        // Stato connessioni: "● HA  ● MQTT  ● Zigbee" con pallino verde/rosso
        val sb = SpannableStringBuilder()
        fun item(label: String, ok: Boolean) {
            if (sb.isNotEmpty()) sb.append("   ")
            val start = sb.length
            sb.append("●")
            sb.setSpan(ForegroundColorSpan(getColor(if (ok) R.color.gda_ok else R.color.gda_err)), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(" ").append(label)
        }
        if (s.haConfigured) item(if (s.haMessage == "demo") "Demo" else "Home Assistant", s.haConnected)
        if (s.mqttConfigured) item("MQTT", s.mqttConnected)
        if (s.zigbeeEnabled) item("Zigbee", s.zigbeeRunning)
        status.text = sb
    }

    private fun updateGreeting(now: Date) {
        if (!::cfg.isInitialized) return
        val h = java.util.Calendar.getInstance().apply { time = now }.get(java.util.Calendar.HOUR_OF_DAY)
        val g = when (h) { in 5..12 -> "Buongiorno"; in 13..17 -> "Buon pomeriggio"; else -> "Buonasera" }
        greeting.text = "$g · ${cfg.roomName}"
    }

    // ---------- Interazione ----------

    private fun onTileTap(t: Tile) {
        when (t) {
            is Tile.LocalRelay -> PanelHub.toggleRelay(t.index)
            is Tile.Entity -> {
                val e = t.entity ?: return
                if (e.domain == "climate" || e.domain == "sensor" || e.domain == "binary_sensor") onTileLongPress(t)
                else PanelHub.haTap(e)
            }
        }
    }

    private fun onTileLongPress(t: Tile) {
        val e = (t as? Tile.Entity)?.entity ?: return
        when (e.domain) {
            "light" -> brightnessDialog(t.title, e.entityId, e.attributes.optInt("brightness", 0))
            "climate" -> climateDialog(t.title, e.entityId, e.attributes.optDouble("temperature", 20.0))
            "cover" -> AlertDialog.Builder(this).setTitle(t.title)
                .setItems(arrayOf("Apri", "Stop", "Chiudi")) { _, w ->
                    PanelHub.haCall("cover", listOf("open_cover", "stop_cover", "close_cover")[w], e.entityId, JSONObject())
                }.show()
            else -> AlertDialog.Builder(this).setTitle(t.title).setMessage(t.subtitle).setPositiveButton("OK", null).show()
        }
    }

    private fun brightnessDialog(title: String, entityId: String, current: Int) {
        val label = TextView(this).apply { gravity = Gravity.CENTER; textSize = 20f }
        val bar = SeekBar(this).apply {
            max = 100
            progress = current * 100 / 255
            label.text = "$progress%"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { label.text = "$p%" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar) {
                    if (s.progress == 0) PanelHub.haCall("light", "turn_off", entityId, JSONObject())
                    else PanelHub.haCall("light", "turn_on", entityId, JSONObject().put("brightness_pct", s.progress))
                }
            })
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
            addView(label); addView(bar)
        }
        AlertDialog.Builder(this).setTitle(title).setView(box).setPositiveButton("Chiudi", null).show()
    }

    private fun climateDialog(title: String, entityId: String, current: Double) {
        var target = if (current.isNaN()) 20.0 else current
        val label = TextView(this).apply { gravity = Gravity.CENTER; textSize = 32f; text = "$target°" }
        fun step(d: Double) {
            target = Math.round((target + d) * 2) / 2.0
            label.text = "$target°"
            PanelHub.haCall("climate", "set_temperature", entityId, JSONObject().put("temperature", target))
        }
        AlertDialog.Builder(this).setTitle(title).setView(label)
            .setNegativeButton("−0,5") { _, _ -> step(-0.5) }
            .setPositiveButton("+0,5") { _, _ -> step(0.5) }
            .setNeutralButton("Chiudi", null)
            .show()
    }

    private fun askPin() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            gravity = Gravity.CENTER
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.enter_pin)
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                if (input.text.toString() == cfg.pin) startActivity(Intent(this, SettingsActivity::class.java))
                else Toast.makeText(this, R.string.wrong_pin, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }
}
