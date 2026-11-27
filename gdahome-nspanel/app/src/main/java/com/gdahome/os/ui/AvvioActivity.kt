package com.gdahome.os.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.gdahome.os.BuildConfig
import com.gdahome.os.Config
import com.gdahome.os.GdaService
import com.gdahome.os.R

/**
 * La schermata home del pannello. Non è un'interfaccia: è l'avvio.
 *
 * Accende il pannello col marchio GdAHome e apre subito l'app GdAHome, che è
 * l'unica interfaccia. Se GdAHome si chiude (o cade) Android torna qui, e da
 * qui si riapre da sola dopo qualche secondo. Se cade troppe volte di fila si
 * ferma e aspetta un tocco, invece di girare in tondo.
 *
 * Tenendo premuto il marchio si entra nelle impostazioni del pannello (PIN).
 */
class AvvioActivity : AppCompatActivity() {

    private lateinit var cfg: Config
    private lateinit var stato: TextView
    private lateinit var tasti: View
    private lateinit var anello: View
    private val handler = Handler(Looper.getMainLooper())
    private var contoAllaRovescia = 0
    private var fermo = false

    private val tick = object : Runnable {
        override fun run() {
            if (contoAllaRovescia <= 0) { apriGdAHome(); return }
            stato.text = "Riapro GdAHome tra $contoAllaRovescia…"
            contoAllaRovescia--
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_avvio)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        stato = findViewById(R.id.stato)
        tasti = findViewById(R.id.tasti)
        anello = findViewById(R.id.anello)
        findViewById<TextView>(R.id.versione).text = "GdAHome OS ${BuildConfig.VERSION_NAME}"
        findViewById<View>(R.id.apri).setOnClickListener { fermo = false; aperture.clear(); apriGdAHome() }
        findViewById<View>(R.id.impostazioni).setOnClickListener { chiediPin() }
        findViewById<View>(R.id.marchio).setOnLongClickListener { chiediPin(); true }
        // Un tocco sull'avvio ferma il conto alla rovescia e mostra i tasti
        findViewById<View>(android.R.id.content).setOnClickListener { fermaIlConto() }
        GdaService.start(this)
    }

    override fun onResume() {
        super.onResume()
        cfg = Config(this)
        nascondiLeBarre()
        handler.removeCallbacks(tick)
        if (fermo) return mostraTasti(null)

        if (packageManager.getLaunchIntentForPackage(cfg.gdaPackage) == null) {
            return mostraTasti(getString(R.string.manca_app))
        }
        val adesso = SystemClock.elapsedRealtime()
        aperture.removeAll { adesso - it > 60_000 }
        if (aperture.size >= 4) {
            fermo = true
            return mostraTasti(getString(R.string.troppi_riavvii))
        }
        tasti.visibility = View.GONE
        anello.visibility = View.VISIBLE
        // Al primo avvio si apre subito; quando GdAHome si chiude si aspetta un attimo
        contoAllaRovescia = if (aperture.isEmpty()) 0 else cfg.riapriDopo
        if (cfg.riapriDopo == 0 && aperture.isNotEmpty()) return mostraTasti(null)
        stato.setText(R.string.avvio)
        handler.postDelayed(tick, if (aperture.isEmpty()) 600 else 0)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) nascondiLeBarre()
    }

    @Deprecated("La home del pannello: indietro non esce")
    override fun onBackPressed() { fermaIlConto() }

    private fun apriGdAHome() {
        val intent = packageManager.getLaunchIntentForPackage(cfg.gdaPackage)
            ?: return mostraTasti(getString(R.string.manca_app))
        aperture += SystemClock.elapsedRealtime()
        stato.setText(R.string.avvio)
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        } catch (e: Exception) {
            mostraTasti("Non riesco ad aprire GdAHome: ${e.message}")
        }
    }

    private fun fermaIlConto() {
        handler.removeCallbacks(tick)
        mostraTasti(null)
    }

    private fun mostraTasti(messaggio: String?) {
        anello.visibility = View.INVISIBLE
        tasti.visibility = View.VISIBLE
        stato.text = messaggio ?: "Pannello GdAHome"
    }

    private fun nascondiLeBarre() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    private fun chiediPin() {
        handler.removeCallbacks(tick)
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            gravity = Gravity.CENTER
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.enter_pin)
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                if (input.text.toString() == cfg.pin) {
                    fermo = true
                    startActivity(Intent(this, SettingsActivity::class.java))
                } else Toast.makeText(this, R.string.wrong_pin, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annulla") { _, _ -> mostraTasti(null) }
            .show()
    }

    companion object {
        /** Quando è stata aperta GdAHome negli ultimi 60 secondi (per accorgersi se cade in continuazione). */
        private val aperture = mutableListOf<Long>()
    }
}
