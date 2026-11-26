package it.gdahome.panel.hw

/** Retroilluminazione hardware opzionale (sysfs). La luminosità della finestra viene gestita dall'Activity. */
object Backlight {
    fun set(path: String, max: Int, percent: Int) {
        if (path.isBlank()) return
        val v = (max * percent.coerceIn(0, 100) / 100.0).toInt()
        SysfsIo.write(path, v.toString())
    }
}
