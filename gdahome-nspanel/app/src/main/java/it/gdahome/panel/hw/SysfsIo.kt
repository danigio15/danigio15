package it.gdahome.panel.hw

import android.util.Log
import java.io.File

/**
 * Lettura/scrittura di file sysfs (GPIO, backlight, sensori).
 * Se l'app non ha i permessi e il pannello è stato sbloccato con l'accordo ADB/root,
 * ripiega su `su -c`.
 */
object SysfsIo {
    private const val TAG = "GDA/Sysfs"
    @Volatile var useSu: Boolean = true
    private val suAvailable: Boolean by lazy {
        listOf("/system/xbin/su", "/system/bin/su", "/sbin/su", "/su/bin/su").any { File(it).exists() }
    }

    fun read(path: String): String? {
        return try {
            File(path).readText().trim()
        } catch (e: Exception) {
            if (useSu && suAvailable) exec("cat '$path'")?.trim() else {
                Log.w(TAG, "read $path: ${e.message}"); null
            }
        }
    }

    fun write(path: String, value: String): Boolean {
        return try {
            File(path).writeText(value)
            true
        } catch (e: Exception) {
            if (useSu && suAvailable) {
                exec("echo '$value' > '$path'") != null
            } else {
                Log.w(TAG, "write $path: ${e.message}"); false
            }
        }
    }

    fun hasSu() = suAvailable

    /** Esegue un comando come root; restituisce stdout oppure null in caso di errore. */
    fun exec(cmd: String): String? = try {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (p.waitFor() == 0) out else { Log.w(TAG, "su '$cmd' -> $out"); null }
    } catch (e: Exception) {
        Log.w(TAG, "su non disponibile: ${e.message}"); null
    }

    /** Esegue un comando shell senza root. */
    fun sh(cmd: String): String = try {
        val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(); out
    } catch (e: Exception) { "ERR ${e.message}" }
}
