package com.gdahome.os.hw

import com.gdahome.os.RelayConfig

/**
 * Relè fisico del pannello pilotato via sysfs.
 * `path` può essere:
 *  - un file completo, es. `/sys/class/gpio/gpio37/value` o `/sys/class/leds/relay1/brightness`
 *  - solo un numero GPIO, es. `37` (viene esportato e impostato come output automaticamente)
 */
class Relay(val cfg: RelayConfig) {
    private val valuePath: String = cfg.path.toIntOrNull()?.let { "/sys/class/gpio/gpio$it/value" } ?: cfg.path
    @Volatile var lastState: Boolean? = null
        private set

    val usable get() = cfg.enabled && cfg.path.isNotBlank()

    fun init() {
        val gpio = cfg.path.toIntOrNull() ?: return
        val base = "/sys/class/gpio/gpio$gpio"
        if (!java.io.File(base).exists()) SysfsIo.write("/sys/class/gpio/export", gpio.toString())
        val dir = SysfsIo.read("$base/direction")
        if (dir != null && dir != "out") SysfsIo.write("$base/direction", "out")
    }

    fun read(): Boolean? {
        if (!usable) return null
        val v = SysfsIo.read(valuePath) ?: return null
        val s = when (v) {
            cfg.onValue -> true
            cfg.offValue -> false
            else -> (v.toIntOrNull() ?: 0) > 0
        }
        lastState = s
        return s
    }

    fun set(on: Boolean): Boolean {
        if (!usable) return false
        val ok = SysfsIo.write(valuePath, if (on) cfg.onValue else cfg.offValue)
        if (ok) lastState = on
        return ok
    }
}
