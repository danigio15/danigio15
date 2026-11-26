package it.gdahome.panel.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import it.gdahome.panel.GdaService
import it.gdahome.panel.R

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.settings)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, Fragment())
                .commit()
        }
    }

    override fun onPause() {
        super.onPause()
        // Ogni modifica viene applicata uscendo dalle impostazioni
        GdaService.restart(this)
    }

    class Fragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs, rootKey)

            val numeric = listOf("zigbee_baud", "zigbee_port", "mqtt_port", "dim_after", "off_after", "brightness_active", "brightness_dim", "backlight_max")
            numeric.forEach { key ->
                findPreference<EditTextPreference>(key)?.setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_NUMBER }
            }
            listOf("temp_divisor", "temp_offset").forEach { key ->
                findPreference<EditTextPreference>(key)?.setOnBindEditTextListener {
                    it.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                }
            }
            listOf("pin").forEach { key ->
                findPreference<EditTextPreference>(key)?.setOnBindEditTextListener {
                    it.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                }
            }
            listOf("mqtt_pass", "ha_token").forEach { key ->
                findPreference<EditTextPreference>(key)?.setOnBindEditTextListener {
                    it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
            }
            findPreference<EditTextPreference>("ha_entities")?.setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                it.minLines = 6
                it.isSingleLine = false
            }

            findPreference<androidx.preference.Preference>("action_probe")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), ProbeActivity::class.java)); true
            }
            findPreference<androidx.preference.Preference>("action_apply")?.setOnPreferenceClickListener {
                GdaService.restart(requireContext())
                Toast.makeText(requireContext(), "Servizio riavviato", Toast.LENGTH_SHORT).show()
                true
            }
            findPreference<androidx.preference.Preference>("action_android")?.setOnPreferenceClickListener {
                startActivity(Intent(Settings.ACTION_SETTINGS)); true
            }
            findPreference<androidx.preference.Preference>("action_home")?.setOnPreferenceClickListener {
                try { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
                catch (e: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
                true
            }
        }
    }
}
