package com.gdahome.os.hw

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Gestisce il processo nativo `gdaserial` che espone il chip Zigbee del pannello in rete
 * (socket://<IP>:<porta>) per Zigbee2MQTT o ZHA. Viene eseguito come root se disponibile
 * e riavviato automaticamente se termina.
 */
class ZigbeeBridge(
    private val context: Context,
    private val device: String,
    private val baud: Int,
    private val port: Int,
    private val rtscts: Boolean,
    private val onStatus: (running: Boolean, message: String) -> Unit,
) {
    private val tag = "GDA/Zigbee"
    @Volatile private var stopped = false
    @Volatile private var process: Process? = null
    private var thread: Thread? = null

    fun start() {
        stopped = false
        thread = Thread({ loop() }, "gda-zigbee").apply { isDaemon = true; start() }
    }

    fun stop() {
        stopped = true
        process?.destroy()
        killStale()
        thread?.interrupt()
    }

    private fun loop() {
        val bin = binary() ?: run { onStatus(false, "gdaserial non disponibile per ${Build.SUPPORTED_ABIS.joinToString()}"); return }
        while (!stopped) {
            killStale()
            val args = buildString {
                append("exec ").append(bin).append(" -d ").append(device).append(" -b ").append(baud).append(" -p ").append(port)
                if (rtscts) append(" -f")
            }
            try {
                if (SysfsIo.hasSu()) SysfsIo.exec("chmod 666 $device")
                val pb = if (SysfsIo.hasSu()) ProcessBuilder("su", "-c", args) else ProcessBuilder("sh", "-c", args)
                val p = pb.redirectErrorStream(true).start()
                process = p
                p.inputStream.bufferedReader().forEachLine { line ->
                    Log.i(tag, line)
                    onStatus(true, line.removePrefix("gdaserial: "))
                }
                val code = p.waitFor()
                onStatus(false, "terminato (codice $code)")
            } catch (e: Exception) {
                onStatus(false, e.message ?: "errore")
            }
            if (!stopped) try { Thread.sleep(5000) } catch (_: InterruptedException) { return }
        }
    }

    private fun killStale() {
        if (SysfsIo.hasSu()) SysfsIo.exec("pkill -f 'gdaserial -d' || true")
    }

    /** Preferisce la copia installata da GDAHome OS in /system/bin, altrimenti estrae quella inclusa nell'app. */
    private fun binary(): String? {
        val sys = File("/system/bin/gdaserial")
        if (sys.exists()) return sys.absolutePath
        val assets = context.assets.list("")?.toSet() ?: emptySet()
        val abi = Build.SUPPORTED_ABIS.firstOrNull { "gdaserial-$it" in assets } ?: return null
        val out = File(context.filesDir, "gdaserial")
        try {
            context.assets.open("gdaserial-$abi").use { input -> out.outputStream().use { input.copyTo(it) } }
            out.setExecutable(true, false)
            out.setReadable(true, false)
        } catch (e: Exception) {
            Log.e(tag, "estrazione: ${e.message}"); return null
        }
        return out.absolutePath
    }
}
