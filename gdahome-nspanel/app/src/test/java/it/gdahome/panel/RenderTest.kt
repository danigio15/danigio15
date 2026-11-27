package it.gdahome.panel

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import it.gdahome.panel.ui.MainActivity
import it.gdahome.panel.ui.SettingsActivity
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Calendar
import java.util.TimeZone

/**
 * Genera render reali dell'interfaccia GDAHome alle risoluzioni dei pannelli:
 *  - NSPanel Pro 86  : 480 x 480 px  (3,95")  → w480dp mdpi
 *  - NSPanel Pro 120 : 750 x 1334 px (4,7")   → w375dp xhdpi
 * Le immagini vengono salvate in docs/renders/raw/.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class RenderTest {

    private val relayFile = File.createTempFile("gda_relay1", ".value")

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Rome"))
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        relayFile.writeText("1")
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
            .putBoolean("demo_mode", true)
            .putString("room_name", "Casa")
            .putBoolean("relay1_enabled", true)
            .putString("relay1_name", "Luce ingresso")
            .putString("relay1_path", relayFile.absolutePath)
            .putBoolean("use_su", false)
            .putString("dim_after", "0")
            .putString("off_after", "0")
            .commit()
        MainActivity.previewTime = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 27, 19, 42, 0)
        }.timeInMillis
        PanelHub.stop()
        PanelHub.start(ctx)
        // aspetta la prima lettura del relè dal file
        repeat(30) { if (PanelHub.state.value.relays[1] == true) return@repeat; Thread.sleep(100) }
        PanelHub.setStateForPreview(
            PanelHub.state.value.copy(
                relays = mapOf(1 to true),
                mqttConfigured = true, mqttConnected = true,
                zigbeeEnabled = true, zigbeeRunning = true,
            )
        )
    }

    @After
    fun tearDown() {
        PanelHub.stop()
        relayFile.delete()
    }

    @Test @Config(qualifiers = "w480dp-h480dp-mdpi")
    fun home_86() = capture("home_86", MainActivity::class.java)

    @Test @Config(qualifiers = "w375dp-h667dp-port-xhdpi")
    fun home_120() = capture("home_120", MainActivity::class.java)

    @Test @Config(qualifiers = "w480dp-h480dp-mdpi")
    fun screensaver_86() = capture("screensaver_86", MainActivity::class.java) {
        PanelHub.sendScreenForPreview(ScreenCmd.DIM)
    }

    @Test @Config(qualifiers = "w375dp-h667dp-port-xhdpi")
    fun screensaver_120() = capture("screensaver_120", MainActivity::class.java) {
        PanelHub.sendScreenForPreview(ScreenCmd.DIM)
    }

    @Test @Config(qualifiers = "w480dp-h480dp-mdpi")
    fun settings_86() = capture("settings_86", SettingsActivity::class.java)

    private fun <T : Activity> capture(name: String, cls: Class<T>, before: (T) -> Unit = {}) {
        val controller = Robolectric.buildActivity(cls).setup()
        val activity = controller.get()
        before(activity)
        // lascia girare coroutine, DiffUtil e layout
        repeat(8) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(120)
        }
        val dm = activity.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val root = activity.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        shadowOf(Looper.getMainLooper()).idle()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val dir = File(System.getProperty("gda.renderDir") ?: "build/renders").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("render: $name ${w}x$h")
        controller.pause().stop().destroy()
    }
}
