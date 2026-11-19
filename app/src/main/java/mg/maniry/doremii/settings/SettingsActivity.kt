package mg.maniry.doremii.settings

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import mg.maniry.doremii.R
import mg.maniry.doremii.commonUtils.FileManager
import mg.maniry.doremii.commonUtils.PlaybackSettings
import mg.maniry.doremii.commonUtils.navigateBack
import mg.maniry.doremii.editor.AboutActivity
import mg.maniry.doremii.editor.HelpActivity
import mg.maniry.doremii.soundfont.SoundFontsActivity


class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        FileManager.initDir(this)
        setContentView(R.layout.settings_activity)

        findViewById<MaterialToolbar>(R.id.screen_toolbar).navigateBack(this)
        findViewById<View>(R.id.settings_soundfonts).setOnClickListener {
            startActivity(Intent(this, SoundFontsActivity::class.java))
        }
        bindVelocity()
        findViewById<View>(R.id.settings_help).setOnClickListener {
            startActivity(Intent(this, HelpActivity::class.java))
        }
        findViewById<View>(R.id.settings_about).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
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
