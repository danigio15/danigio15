package com.gdahome.os.ui

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.gdahome.os.PanelHub
import com.gdahome.os.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProbeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_probe)
        val out = findViewById<TextView>(R.id.output)
        val run = findViewById<Button>(R.id.btn_run)
        findViewById<Button>(R.id.btn_close).setOnClickListener { finish() }
        fun go() {
            run.isEnabled = false
            out.text = "Analisi in corso…"
            lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) { Probe.run(this@ProbeActivity) }
                out.text = r
                PanelHub.publishProbe(r)
                run.isEnabled = true
            }
        }
        run.setOnClickListener { go() }
        go()
    }
}
