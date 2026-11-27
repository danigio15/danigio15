package com.gdahome.os.ui

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import com.gdahome.os.hw.SysfsIo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Analisi dell'hardware del pannello: serve a trovare i percorsi reali di relè (GPIO),
 * retroilluminazione, sensori e porta seriale Zigbee su NSPanel Pro / Pro 120.
 */
object Probe {
    fun run(ctx: Context): String {
        val sb = StringBuilder()
        fun h(t: String) = sb.append("\n===== $t =====\n")
        fun l(t: String) = sb.append(t).append('\n')

        l("GdAHome OS — analisi del pannello ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ITALY).format(Date())}")
        h("Dispositivo")
        l("Produttore: ${Build.MANUFACTURER}  Marca: ${Build.BRAND}")
        l("Modello: ${Build.MODEL}  Prodotto: ${Build.PRODUCT}  Board: ${Build.BOARD}  HW: ${Build.HARDWARE}")
        l("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})  Build: ${Build.DISPLAY}")
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        l("Display: ${dm.widthPixels}x${dm.heightPixels} px, densità ${dm.densityDpi} dpi, ${ctx.resources.configuration.screenWidthDp}x${ctx.resources.configuration.screenHeightDp} dp")
        l("Root (su): ${if (SysfsIo.hasSu()) "presente" else "assente"}")
        l(sh("grep -E 'MemTotal|MemAvailable' /proc/meminfo"))

        h("GdAHome e WebView")
        val pmi = ctx.packageManager
        val gda = com.gdahome.os.Config(ctx).gdaPackage
        l("App GdAHome ($gda): " + try {
            @Suppress("DEPRECATION") val pi = pmi.getPackageInfo(gda, 0)
            "${pi.versionName} (${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(pi)})"
        } catch (e: Exception) { "NON installata" })
        val wv = try { android.webkit.WebView.getCurrentWebViewPackage() } catch (e: Exception) { null }
        if (wv != null) {
            val major = wv.versionName?.substringBefore('.')?.toIntOrNull() ?: 0
            l("WebView: ${wv.packageName} ${wv.versionName}" + if (major in 1..107) "  ← TROPPO VECCHIA per la plancia: aggiornala" else "")
        } else l("WebView: non trovata")

        h("Sensori Android")
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sm.getSensorList(android.hardware.Sensor.TYPE_ALL).forEach {
            l("tipo=${it.type} '${it.name}' (${it.vendor}) range=${it.maximumRange}")
        }

        h("GPIO (/sys/class/gpio)")
        listDir("/sys/class/gpio").forEach { name ->
            val base = "/sys/class/gpio/$name"
            if (name.startsWith("gpiochip")) {
                l("$name base=${rd("$base/base")} ngpio=${rd("$base/ngpio")} label=${rd("$base/label")}")
            } else if (name.startsWith("gpio")) {
                l("$name direction=${rd("$base/direction")} value=${rd("$base/value")}")
            }
        }
        l(sh("cat /sys/kernel/debug/gpio 2>/dev/null | head -n 120"))

        h("LED / uscite (/sys/class/leds)")
        listDir("/sys/class/leds").forEach { l("$it brightness=${rd("/sys/class/leds/$it/brightness")} max=${rd("/sys/class/leds/$it/max_brightness")}") }

        h("Retroilluminazione (/sys/class/backlight)")
        listDir("/sys/class/backlight").forEach {
            l("/sys/class/backlight/$it/brightness = ${rd("/sys/class/backlight/$it/brightness")} (max ${rd("/sys/class/backlight/$it/max_brightness")})")
        }

        h("Termico (/sys/class/thermal)")
        listDir("/sys/class/thermal").filter { it.startsWith("thermal_zone") }.forEach {
            l("/sys/class/thermal/$it/temp type=${rd("/sys/class/thermal/$it/type")} temp=${rd("/sys/class/thermal/$it/temp")}")
        }

        h("IIO / hwmon (sensori temperatura/umidità)")
        listDir("/sys/bus/iio/devices").forEach { d ->
            l("/sys/bus/iio/devices/$d name=${rd("/sys/bus/iio/devices/$d/name")}")
            listDir("/sys/bus/iio/devices/$d").filter { it.startsWith("in_") }.forEach { f ->
                l("   $f = ${rd("/sys/bus/iio/devices/$d/$f")}")
            }
        }
        listDir("/sys/class/hwmon").forEach { d ->
            l("/sys/class/hwmon/$d name=${rd("/sys/class/hwmon/$d/name")}")
            listDir("/sys/class/hwmon/$d").filter { it.endsWith("_input") }.forEach { f ->
                l("   $f = ${rd("/sys/class/hwmon/$d/$f")}")
            }
        }

        h("Porte seriali (Zigbee / RS485)")
        l(sh("ls -l /dev/ttyS* /dev/ttyUSB* /dev/ttyACM* 2>/dev/null"))

        h("Input (touch, pulsanti)")
        l(sh("cat /proc/bus/input/devices 2>/dev/null | grep -E 'Name|Handlers'"))

        h("Pacchetti Sonoff / eWeLink / vendor")
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .map { it.packageName }
            .filter { p -> listOf("sonoff", "ewelink", "itead", "coolkit", "nspanel", "smatek", "launcher", "zigbee", "siliconlabs")
                .any { p.contains(it, ignoreCase = true) } }
            .sorted()
            .forEach { l(it) }

        h("Proprietà di sistema rilevanti")
        l(sh("getprop | grep -iE 'product|board|platform|hardware|version|sonoff|ewelink' | head -n 60"))

        val report = sb.toString()
        try {
            val f = File(ctx.getExternalFilesDir(null), "gdahome_probe.txt")
            f.writeText(report)
            sb.append("\nSalvato in: ${f.absolutePath}\n")
        } catch (_: Exception) {}
        return sb.toString()
    }

    private fun listDir(path: String): List<String> =
        File(path).list()?.sorted()
            ?: (if (SysfsIo.hasSu()) SysfsIo.exec("ls '$path'")?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } else null)
            ?: emptyList()

    private fun rd(path: String) = SysfsIo.read(path) ?: "?"

    private fun sh(cmd: String): String {
        val out = if (SysfsIo.hasSu()) SysfsIo.exec(cmd) else null
        return (out ?: SysfsIo.sh(cmd)).trimEnd()
    }
}
