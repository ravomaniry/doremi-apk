package mg.maniry.doremii.settings

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import mg.maniry.doremii.R
import mg.maniry.doremii.commonUtils.FileManager
import mg.maniry.doremii.commonUtils.PlaybackSettings
import mg.maniry.doremii.commonUtils.navigateBack
import mg.maniry.doremii.editor.AboutActivity
import mg.maniry.doremii.editor.HelpActivity


class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        FileManager.initDir(this)
        setContentView(R.layout.settings_activity)

        findViewById<MaterialToolbar>(R.id.screen_toolbar).navigateBack(this)
        bindInstrument()
        bindVelocity()
        findViewById<android.view.View>(R.id.settings_help).setOnClickListener {
            startActivity(Intent(this, HelpActivity::class.java))
        }
        findViewById<android.view.View>(R.id.settings_about).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
    }


    private fun bindInstrument() {
        val names = resources.getStringArray(R.array.instruments_list)
        val input = findViewById<MaterialAutoCompleteTextView>(R.id.instrument_input)
        input.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, names))
        val saved = FileManager.redInstrumentName()
        input.setText(if (saved in names) saved else names.first(), false)
        input.setOnItemClickListener { _, _, _, _ ->
            FileManager.saveInstrumentName(input.text.toString())
        }
    }


    private fun bindVelocity() {
        findViewById<MaterialSwitch>(R.id.velocity_switch)
            .apply {
                isChecked = PlaybackSettings.velocity(this@SettingsActivity)
                setOnCheckedChangeListener { _, checked ->
                    PlaybackSettings.setVelocity(this@SettingsActivity, checked)
                }
            }
    }
}
